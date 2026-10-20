package com.autotrade.sim.combo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.run.RunGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실험 입력에 공시 스냅샷(D-122) 잇기: 폴더 {@code dart-disclosures/<ID>}를 읽어 선정 데이터에 넘긴다. */
class SnapshotInputsDisclosuresTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<LocalDate> DAYS =
            KRX.tradingDays(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31)).subList(0, 30);
    private static final String BARS = "krx-daily-test-0001";
    private static final String DIS = "dart-disclosures-test-0001";

    @TempDir
    Path tmp;

    private Path snapshots(String disclosuresManifestId) throws IOException {
        Path root = tmp.resolve("snapshots");
        Path dir = Files.createDirectories(root.resolve("krx-daily").resolve(BARS));
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        for (LocalDate d : DAYS) {
            bars.append("KRX,000010,").append(d).append(",10000,10100,9900,10000,1000,1\n");
        }
        Path b = write(dir, SnapshotManifest.DAILY_BARS, bars.toString());
        Path h = write(dir, SnapshotManifest.INSTRUMENT_HISTORY, """
                market,code,name,kind,board,valid_from,valid_to
                KRX,000010,가,STOCK,KOSPI,2000-01-04,
                """);
        write(dir, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":1}],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                """.formatted(BARS, Sha256.ofFile(b), DAYS.size(), Sha256.ofFile(h), DAYS.get(0),
                DAYS.get(DAYS.size() - 1)));

        Path d = Files.createDirectories(root.resolve("dart-disclosures").resolve(DIS));
        Path rows = write(d, "disclosures.csv", """
                market,code,code_source,corp_code,corp_cls,pblntf_ty,rcept_no,rcept_dt,report_nm
                KRX,000010,DART,00000001,Y,B,20260115000001,2026-01-15,주요사항보고서
                KRX,000010,DART,00000001,Y,B,20260116000001,2026-01-16,[기재정정]주요사항보고서
                """);
        Path types = write(d, "disclosure_types.csv", """
                pblntf_ty,from,to
                B,2026-01-01,2026-02-28
                """);
        write(d, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"disclosures.csv","sha256":"%s","rows":2},{"name":"disclosure_types.csv","sha256":"%s","rows":1}]}
                """.formatted(disclosuresManifestId, Sha256.ofFile(rows), Sha256.ofFile(types)));
        return root;
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static SnapshotInputs load(Path root, Optional<String> disclosures, List<String> log) {
        return SnapshotInputs.load(Market.KRX, BARS, Optional.empty(), disclosures, root, KRX, RunGate.NONE, log::add,
                (a, b) -> { });
    }

    @Test
    void 공시_스냅샷을_요청하면_읽어서_선정_데이터에_넘긴다() throws IOException {
        List<String> log = new ArrayList<>();
        SnapshotInputs in = load(snapshots(DIS), Optional.of(DIS), log);

        assertThat(in.disclosures()).isNotNull();
        assertThat(in.selectionData().disclosures()).hasValueSatisfying(x -> {
            assertThat(x.snapshotId()).isEqualTo(DIS);
            assertThat(x.types()).containsExactly("B");
            // 정정 공시는 세지 않는다
            assertThat(x.count(Symbol.krx("000010"), Set.of("B"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                    .isEqualTo(1);
        });
        assertThat(log).anyMatch(l -> l.startsWith("공시 스냅샷 읽기: "));
    }

    @Test
    void 요청하지_않으면_공시_입력이_없다() throws IOException {
        SnapshotInputs in = load(snapshots(DIS), Optional.empty(), new ArrayList<>());

        assertThat(in.disclosures()).isNull();
        assertThat(in.selectionData().disclosures()).isEmpty();
    }

    @Test
    void 폴더_이름과_manifest_ID가_다르면_거절한다() throws IOException {
        Path root = snapshots("dart-disclosures-other-0002");

        assertThatThrownBy(() -> load(root, Optional.of(DIS), new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(DIS);
    }
}
