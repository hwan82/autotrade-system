package com.autotrade.marketdata.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.QualityReport.Rule;
import com.autotrade.marketdata.recording.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotDailyBarSourceTest {

    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol DELISTED = Symbol.krx("000100");
    private static final Symbol MOVED = Symbol.krx("247540");

    @TempDir
    Path dir;

    private static final String BARS = """
            market,code,date,open,high,low,close,volume,adj_factor
            KRX,005930,2026-10-05,71000,72000,70500,71500,1000,1
            KRX,005930,2026-10-06,71500,72500,71000,72000,1200,1
            KRX,005930,2026-10-07,72000,72000,71000,0,1300,1
            KRX,000100,2026-10-05,5000,5100,4900,5000,10,1
            KRX,000100,2026-10-06,5000,5000,5000,5000,0,1
            KRX,000100,2026-10-07,5000,5000,5000,5000,10,1
            KRX,247540,2026-10-05,100000,101000,99000,100000,500,1
            KRX,247540,2026-10-06,50000,51000,49000,50000,500,1
            KRX,247540,2026-10-07,50500,50000,49500,50200,500,1
            """;
    private static final String HISTORY = """
            market,code,name,kind,board,valid_from,valid_to
            KRX,005930,삼성전자,STOCK,KOSPI,1975-06-11,
            KRX,000100,"가나다,상사",STOCK,KOSDAQ,2001-01-02,2026-10-06
            KRX,247540,에코프로비엠,STOCK,KOSDAQ,2019-03-05,2026-10-05
            KRX,247540,에코프로비엠,STOCK,KOSPI,2026-10-06,
            KRX,035720,카카오,STOCK,KOSPI,2017-07-10,
            """;

    @BeforeEach
    void writeSnapshot() throws IOException {
        write(BARS, HISTORY, 9, 5);
    }

    private void write(String bars, String history, long barRows, long historyRows) throws IOException {
        Path b = dir.resolve(SnapshotManifest.DAILY_BARS);
        Path h = dir.resolve(SnapshotManifest.INSTRUMENT_HISTORY);
        Files.writeString(b, bars, StandardCharsets.UTF_8);
        Files.writeString(h, history, StandardCharsets.UTF_8);
        String manifest = """
                {"snapshotId":"krx-daily-test-1","schemaVersion":1,"createdAt":"2026-10-08T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-10-08T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":%d}]}
                """.formatted(Sha256.ofFile(b), barRows, Sha256.ofFile(h), historyRows);
        Files.writeString(dir.resolve(SnapshotManifest.FILE_NAME), manifest, StandardCharsets.UTF_8);
    }

    @Test
    void 그날_상장돼_있던_종목으로_유니버스를_재현한다() {
        SnapshotDailyBarSource src = SnapshotDailyBarSource.load(dir);
        assertThat(src.universe(Market.KRX, LocalDate.of(2026, 10, 6))).containsExactly(DELISTED, SAMSUNG, Symbol.krx("035720"), MOVED);
        // 상장폐지 다음 날에는 빠진다
        assertThat(src.universe(Market.KRX, LocalDate.of(2026, 10, 7))).doesNotContain(DELISTED);
        assertThat(src.instrumentOn(DELISTED, LocalDate.of(2026, 10, 6)).orElseThrow().name()).isEqualTo("가나다,상사");
    }

    @Test
    void 소속부_이력을_날짜별로_돌려준다() {
        SnapshotDailyBarSource src = SnapshotDailyBarSource.load(dir);
        assertThat(src.instrumentOn(MOVED, LocalDate.of(2026, 10, 5)).orElseThrow().board()).isEqualTo("KOSDAQ");
        assertThat(src.instrumentOn(MOVED, LocalDate.of(2026, 10, 6)).orElseThrow().board()).isEqualTo("KOSPI");
    }

    @Test
    void 품질_오류_행은_빼고_경고_행은_남긴다() {
        SnapshotDailyBarSource src = SnapshotDailyBarSource.load(dir);
        QualityReport r = src.qualityReport();
        assertThat(r.count(Rule.NON_POSITIVE_PRICE)).isEqualTo(1);   // 삼성 10-07 종가 0
        assertThat(r.count(Rule.OUTSIDE_LISTING)).isEqualTo(1);      // 폐지 다음 날 일봉
        assertThat(r.count(Rule.OHLC_INCONSISTENT)).isEqualTo(1);    // 247540 10-07 고가 < 시가
        assertThat(r.count(Rule.ZERO_VOLUME)).isEqualTo(1);
        assertThat(r.count(Rule.UNADJUSTED_JUMP)).isEqualTo(1);      // 100000 → 50000, 수정 계수 그대로
        assertThat(r.count(Rule.MISSING_BAR)).isZero();
        assertThat(src.bars(SAMSUNG, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .extracting(DailyBar::date).containsExactly(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6));
        assertThat(src.bars(DELISTED, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).hasSize(2);
    }

    @Test
    void 요청_범위를_넘는_일봉은_주지_않는다() {
        SnapshotDailyBarSource src = SnapshotDailyBarSource.load(dir);
        assertThat(src.bars(SAMSUNG, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5))).hasSize(1);
        assertThat(src.bars(SAMSUNG, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 5))).isEmpty();
    }

    @Test
    void 리포트를_마크다운으로_남긴다() throws IOException {
        QualityReport r = SnapshotDailyBarSource.load(dir).qualityReport();
        Path out = r.writeTo(dir.resolve("_data-quality"));
        String md = Files.readString(out);
        assertThat(out.getFileName().toString()).isEqualTo("krx-daily-test-1.md");
        assertThat(md).contains("| NON_POSITIVE_PRICE | ERROR |").contains("테스트용").contains("KRX:005930 | 2026-10-07");
        assertThat(r.toMarkdown()).isEqualTo(SnapshotDailyBarSource.load(dir).qualityReport().toMarkdown());
    }

    @Test
    void 해시나_행_수가_manifest와_다르면_읽지_않는다() throws IOException {
        Files.writeString(dir.resolve(SnapshotManifest.DAILY_BARS), BARS + "KRX,005930,2026-10-08,1,1,1,1,1,1\n");
        assertThatThrownBy(() -> SnapshotDailyBarSource.load(dir)).hasMessageContaining("SHA-256");
        write(BARS, HISTORY, 8, 5);
        assertThatThrownBy(() -> SnapshotDailyBarSource.load(dir)).hasMessageContaining("행 수");
    }

    @Test
    void 헤더가_스키마와_다르면_읽지_않는다() throws IOException {
        write(BARS.replace("adj_factor", "adj"), HISTORY, 9, 5);
        assertThatThrownBy(() -> SnapshotDailyBarSource.load(dir)).hasMessageContaining("헤더");
    }

    @Test
    void 스냅샷_이후_날짜와_누락_일봉을_잡는다() throws IOException {
        String bars = """
                market,code,date,open,high,low,close,volume,adj_factor
                KRX,005930,2026-10-05,71000,72000,70500,71500,1000,1
                KRX,035720,2026-10-05,40000,41000,39000,40000,100,1
                KRX,035720,2026-10-06,40000,41000,39000,40000,100,1
                KRX,035720,2026-10-09,40000,41000,39000,40000,100,1
                """;
        write(bars, HISTORY, 4, 5);
        QualityReport r = SnapshotDailyBarSource.load(dir).qualityReport();
        assertThat(r.count(Rule.AFTER_SNAPSHOT)).isEqualTo(1);
        assertThat(r.findings()).filteredOn(f -> f.rule() == Rule.MISSING_BAR)
                .extracting(f -> f.symbol().code() + "@" + f.date())
                .containsExactly("005930@2026-10-06", "005930@2026-10-09");
    }

    /** 헤더를 두고 행을 날짜 순(같은 날은 원래 순서)으로 바꾼다. 수집기가 쓰는 순서. */
    private static String byDate(String csv) {
        List<String> lines = csv.lines().toList();
        List<String> rows = new java.util.ArrayList<>(lines.subList(1, lines.size()));
        rows.sort(java.util.Comparator.comparing(l -> l.split(",")[2]));
        return lines.get(0) + "\n" + String.join("\n", rows) + "\n";
    }

    private void assertSameAsFullLoad() throws IOException {
        QualityReport full = SnapshotDailyBarSource.load(dir).qualityReport();
        int[] days = {0};
        QualityReport streamed = DailyBarQualityCheck.checkSnapshot(dir, () -> days[0]++);
        assertThat(streamed.barsChecked()).isEqualTo(full.barsChecked());
        assertThat(streamed.findings()).isEqualTo(full.findings());
        assertThat(streamed.toMarkdown()).isEqualTo(full.toMarkdown());
        assertThat(days[0]).isPositive();
    }

    @Test
    void 날짜_순_스트리밍_검사는_전체_적재_검사와_같은_결과다() throws IOException {
        // 기본 픽스처(0원 종가, 거래정지, 소속부 이동, 점프) + 중복 행
        String bars = byDate(BARS + "KRX,005930,2026-10-06,71500,72500,71000,72000,1200,1\n");
        write(bars, HISTORY, 10, 5);
        assertSameAsFullLoad();
        // 시점 위반·누락 일봉
        write(byDate("""
                market,code,date,open,high,low,close,volume,adj_factor
                KRX,005930,2026-10-05,71000,72000,70500,71500,1000,1
                KRX,035720,2026-10-05,40000,41000,39000,40000,100,1
                KRX,035720,2026-10-06,40000,41000,39000,40000,100,1
                KRX,035720,2026-10-09,40000,41000,39000,40000,100,1
                """), HISTORY, 4, 5);
        assertSameAsFullLoad();
    }

    @Test
    void 날짜_순_스트리밍_검사는_무작위_이상_자료에서도_같은_결과다() throws IOException {
        java.util.Random rnd = new java.util.Random(39);
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        StringBuilder hist = new StringBuilder("market,code,name,kind,board,valid_from,valid_to\n");
        int codes = 30;
        for (int c = 0; c < codes; c++) {
            String code = String.format("%06d", 100000 + c);
            LocalDate from = LocalDate.of(2026, 1, 1).plusDays(rnd.nextInt(20));
            String to = rnd.nextInt(4) == 0 ? LocalDate.of(2026, 3, 1).plusDays(rnd.nextInt(30)).toString() : "";
            if (rnd.nextInt(10) > 0) { // 일부는 종목 이력이 없다
                hist.append("KRX,").append(code).append(",회사").append(c).append(",STOCK,KOSPI,").append(from).append(',')
                        .append(to).append('\n');
            }
        }
        long rows = 0;
        for (LocalDate d = LocalDate.of(2026, 1, 1); d.isBefore(LocalDate.of(2026, 5, 1)); d = d.plusDays(1)) {
            for (int c = 0; c < codes; c++) {
                if (rnd.nextInt(12) == 0) {
                    continue; // 누락
                }
                int price = 1000 + rnd.nextInt(10) * (rnd.nextInt(15) == 0 ? 400 : 10); // 가끔 점프
                int close = rnd.nextInt(50) == 0 ? 0 : price;
                long vol = rnd.nextInt(20) == 0 ? 0 : 10;
                String adj = rnd.nextInt(100) == 0 ? "0.5" : "1";
                String row = "KRX," + String.format("%06d", 100000 + c) + "," + d + "," + price + "," + (price + 5) + ","
                        + (price - 5) + "," + close + "," + vol + "," + adj + "\n";
                bars.append(row);
                rows++;
                if (rnd.nextInt(80) == 0) {
                    bars.append(row); // 중복
                    rows++;
                }
            }
        }
        long histRows = hist.toString().lines().count() - 1;
        // 생성 시각보다 뒤 날짜가 섞이도록 manifest createdAt(2026-10-08)보다 앞뒤 모두를 쓰지 않아도 규칙 대부분이 나온다
        write(bars.toString(), hist.toString(), rows, histRows);
        assertSameAsFullLoad();
        QualityReport r = DailyBarQualityCheck.checkSnapshot(dir, () -> {});
        assertThat(r.count(Rule.MISSING_BAR)).isPositive();
        assertThat(r.count(Rule.DUPLICATE_BAR)).isPositive();
        assertThat(r.count(Rule.UNADJUSTED_JUMP)).isPositive();
        assertThat(r.count(Rule.OUTSIDE_LISTING)).isPositive();
    }

    @Test
    void 스트리밍_검사는_날짜_순이_아니거나_해시나_행_수가_다르면_멈춘다() throws IOException {
        write(BARS, HISTORY, 9, 5); // 종목 순(날짜 순 아님)
        assertThatThrownBy(() -> DailyBarQualityCheck.checkSnapshot(dir, () -> {})).hasMessageContaining("날짜 순");
        write(byDate(BARS), HISTORY, 8, 5);
        assertThatThrownBy(() -> DailyBarQualityCheck.checkSnapshot(dir, () -> {})).hasMessageContaining("행 수");
        write(byDate(BARS), HISTORY, 9, 5);
        Files.writeString(dir.resolve(SnapshotManifest.DAILY_BARS), byDate(BARS).replace("71500", "71600"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> DailyBarQualityCheck.checkSnapshot(dir, () -> {})).hasMessageContaining("SHA-256");
    }

    @Test
    void 스냅샷_기준일은_생성_시각의_거래소_현지_날짜다() {
        // 2026-10-07T16:00Z = 한국 10-08 01:00. 한국 10-08 일봉은 시점 위반이 아니고 10-09는 위반이다
        SnapshotManifest m = new SnapshotManifest("krx-daily-tz-1", 1, "2026-10-07T16:00:00Z", "0.1.0", List.of(), List.of(), null);
        Symbol s = new Symbol(Market.KRX, "005930");
        java.util.function.Function<String, DailyBar> bar = d -> new DailyBar(s, java.time.LocalDate.parse(d),
                new java.math.BigDecimal("100"), new java.math.BigDecimal("100"), new java.math.BigDecimal("100"),
                new java.math.BigDecimal("100"), 1, java.math.BigDecimal.ONE);
        QualityReport r = DailyBarQualityCheck.check(m, List.of(bar.apply("2026-10-08"), bar.apply("2026-10-09")), List.of());
        assertThat(r.findings()).filteredOn(f -> f.rule() == Rule.AFTER_SNAPSHOT)
                .extracting(f -> f.date().toString()).containsExactly("2026-10-09");
    }
}
