package com.autotrade.selection.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.SnapshotFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 일봉 스냅샷의 실제 거래대금 선택 파일 읽기(수집 명령 krx-daily가 쓰는 형식, D-133·D-139). */
class SnapshotTurnoverTest {

    @TempDir
    Path dir;

    /** 수집기는 날짜 순, 그날 안에서 종목 순으로 쓴다. 0원은 거래정지일. */
    static final String ROWS = """
            market,code,date,turnover
            KRX,005930,2026-10-19,1500000000000
            KRX,000660,2026-10-19,0
            KRX,005930,2026-10-20,1600000000000
            KRX,000660,2026-10-21,7000000000
            KRX,005930,2026-10-21,1700000000000
            """;

    private void write(String rows, String shaOverride, Long rowsOverride) throws Exception {
        Files.writeString(dir.resolve(SnapshotTurnover.FILE), rows, StandardCharsets.UTF_8);
        long n = rowsOverride != null ? rowsOverride : rows.lines().filter(l -> !l.isEmpty()).count() - 1;
        Files.writeString(dir.resolve("manifest.json"), """
                {"snapshotId":"krx-daily-2026-10-21-0000aaaa","schemaVersion":1,"createdAt":"2026-10-22T00:00:00Z",
                 "scriptVersion":"0.1.0","sources":[],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":0},{"name":"daily_turnover.csv","sha256":"%s","rows":%d}],
                 "coverage":{"firstDate":"2026-10-19","lastTradingDate":"2026-10-21","sharesAsOf":null,"lastCollectedAt":null}}
                """.formatted("0".repeat(64), shaOverride != null ? shaOverride : sha(rows), n));
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 종목_날짜별_금액과_마지막_날짜를_읽는다() throws Exception {
        write(ROWS, null, null);
        SnapshotTurnover t = SnapshotTurnover.load(dir);
        Symbol samsung = Symbol.krx("005930");
        Symbol hynix = Symbol.krx("000660");
        assertThat(t.turnover(samsung, LocalDate.of(2026, 10, 20))).isEqualTo(OptionalLong.of(1_600_000_000_000L));
        assertThat(t.turnover(hynix, LocalDate.of(2026, 10, 19))).as("정지일 0원").isEqualTo(OptionalLong.of(0));
        assertThat(t.turnover(hynix, LocalDate.of(2026, 10, 20))).as("행 없음").isEmpty();
        assertThat(t.turnover(Symbol.krx("999999"), LocalDate.of(2026, 10, 20))).isEmpty();
        assertThat(t.lastDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(t.sha256()).isEqualTo(sha(ROWS));
        assertThat(t.snapshotId()).isEqualTo("krx-daily-2026-10-21-0000aaaa");
        assertThat(SnapshotTurnover.present(SnapshotFiles.readManifest(dir))).isTrue();
    }

    @Test
    void 날짜_순이_아니어도_정렬해_찾는다() throws Exception {
        String rows = """
                market,code,date,turnover
                KRX,005930,2026-10-21,3
                KRX,005930,2026-10-19,1
                KRX,005930,2026-10-20,2
                """;
        write(rows, null, null);
        SnapshotTurnover t = SnapshotTurnover.load(dir);
        assertThat(t.turnover(Symbol.krx("005930"), LocalDate.of(2026, 10, 19))).isEqualTo(OptionalLong.of(1));
        assertThat(t.turnover(Symbol.krx("005930"), LocalDate.of(2026, 10, 21))).isEqualTo(OptionalLong.of(3));
    }

    @Test
    void 행이_없는_파일은_마지막_날짜가_없다() throws Exception {
        write("market,code,date,turnover\n", null, null);
        assertThat(SnapshotTurnover.load(dir).lastDate()).isNull();
    }

    @Test
    void manifest와_다르면_거부한다() throws Exception {
        write(ROWS, "f".repeat(64), null);
        assertThatThrownBy(() -> SnapshotTurnover.load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256");
        write(ROWS, null, 4L);
        assertThatThrownBy(() -> SnapshotTurnover.load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("행 수");
    }

    @Test
    void 형식이_틀린_행이_하나라도_있으면_스냅샷_전체를_거부한다() throws Exception {
        String head = "market,code,date,turnover\n";
        for (String[] bad : new String[][] {
                {"KRX,005930,2026-10-21,-1\n", "0 이상 정수"},
                {"KRX,005930,2026-10-21,1.5\n", "0 이상 정수"},
                {"KRX,005930,2026-10-21,\n", "0 이상 정수"},
                {"KRX,005930,2026-13-01,1\n", "날짜 형식"},
                {"XXX,005930,2026-10-21,1\n", "모르는 시장"},
                {"KRX,,2026-10-21,1\n", "종목 코드"},
                {"KRX,005930,2026-10-21\n", "열 수"},
                {"KRX,005930,2026-10-21,1\nKRX,005930,2026-10-20,1\nKRX,005930,2026-10-21,2\n", "두 번"}}) {
            write(head + bad[0], null, null);
            assertThatThrownBy(() -> SnapshotTurnover.load(dir)).as(bad[0]).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(bad[1]);
        }
        write("market,code,date,value\n", null, null);
        assertThatThrownBy(() -> SnapshotTurnover.load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("헤더");
    }

    @Test
    void 거래대금_파일이_없는_스냅샷은_읽지_않는다() throws Exception {
        Files.writeString(dir.resolve("manifest.json"), """
                {"snapshotId":"krx-daily-old","schemaVersion":1,"createdAt":"2026-10-17T00:00:00Z","scriptVersion":"0.1.0",
                 "sources":[],"files":[{"name":"daily_bars.csv","sha256":"%s","rows":0}]}
                """.formatted("0".repeat(64)), StandardCharsets.UTF_8);
        assertThat(SnapshotTurnover.present(SnapshotFiles.readManifest(dir))).isFalse();
        assertThatThrownBy(() -> SnapshotTurnover.load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--without-turnover");
    }
}
