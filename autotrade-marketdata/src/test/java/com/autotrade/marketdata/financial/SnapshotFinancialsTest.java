package com.autotrade.marketdata.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.recording.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotFinancialsTest {

    @TempDir
    Path dir;

    private void write(String rows, long count) throws IOException {
        Path csv = dir.resolve("financial_filings.csv");
        Files.writeString(csv, String.join(",", SnapshotFinancials.HEADER) + "\n" + rows, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), """
                {"snapshotId":"dart-financials-2026-03-19-abcd1234","schemaVersion":1,"createdAt":"2026-03-20T03:00:00Z",
                 "scriptVersion":"0.1.0","sources":[],"files":[{"name":"financial_filings.csv","sha256":"%s","rows":%d}],
                 "coverage":{"firstDate":"2026-03-10","lastCollectedAt":"2026-03-20T03:00:00Z"}}
                """.formatted(Sha256.ofFile(csv), count), StandardCharsets.UTF_8);
    }

    @Test
    void 공시_행을_정렬해_읽고_값_모름은_금액이_없다() throws IOException {
        write("""
                KRX,111111,00000001,2025,11011,20260325000004,2026-03-25,Y,Y,CFS,1100,110,5000,2000,3000
                KRX,111111,00000001,2025,11011,20260310000001,2026-03-10,N,Y,CFS,1000,100,5000,2000,3000
                KRX,222222,00000002,2025,11011,20260311000002,2026-03-11,N,N,,,,,,
                """, 3);
        SnapshotFinancials f = SnapshotFinancials.load(dir);
        assertThat(f.filings()).extracting(FinancialFiling::receiptNo)
                .containsExactly("20260310000001", "20260325000004", "20260311000002");
        FinancialFiling first = f.filingsOf(Symbol.krx("111111")).get(0);
        assertThat(first.receiptDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(first.revenue()).isEqualByComparingTo("1000");
        assertThat(first.correction()).isFalse();
        FinancialFiling unknown = f.filingsOf(Symbol.krx("222222")).get(0);
        assertThat(unknown.valuesKnown()).isFalse();
        assertThat(unknown.totalEquity()).isNull();
        assertThat(f.manifest().coverage().lastCollectedAt()).isEqualTo("2026-03-20T03:00:00Z");
    }

    @Test
    void 접수일이_없는_행이_하나라도_있으면_거부한다() throws IOException {
        write("KRX,111111,00000001,2025,11011,20260310000001,,N,Y,CFS,1000,100,5000,2000,3000\n", 1);
        assertThatThrownBy(() -> SnapshotFinancials.load(dir)).hasMessageContaining("접수일");
    }

    @Test
    void 값_모름_행에_금액이_있으면_거부한다() throws IOException {
        write("KRX,111111,00000001,2025,11011,20260310000001,2026-03-10,N,N,CFS,1000,,,,\n", 1);
        assertThatThrownBy(() -> SnapshotFinancials.load(dir)).hasMessageContaining("값을 모르는");
    }

    @Test
    void 해시가_다르면_거부한다() throws IOException {
        write("KRX,111111,00000001,2025,11011,20260310000001,2026-03-10,N,Y,CFS,1000,100,5000,2000,3000\n", 1);
        Files.writeString(dir.resolve("financial_filings.csv"), "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> SnapshotFinancials.load(dir)).hasMessageContaining("SHA-256");
    }
}
