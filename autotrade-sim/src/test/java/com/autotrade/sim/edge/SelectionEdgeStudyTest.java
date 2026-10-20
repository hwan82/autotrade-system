package com.autotrade.sim.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.combo.CombinationPlan;
import com.autotrade.sim.combo.SnapshotInputs;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.run.RunGate;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 합성 일봉 스냅샷(번들 캘린더 2026년 첫 60거래일)으로 선정 엣지 검사를 돌린다. 보통주 8종목(000010~000080)의 종가는 날마다
 * 정해진 식으로 바뀌어 일부 날에 3% 넘게 오르고, ETF 000090과 900원 종목 000100은 하드 필터에서 빠진다. 시가 = 전날 종가,
 * 고가·저가 = 시가·종가 ± 50원. 000030은 30번째 거래일에 거래정지(거래량 0).
 */
class SelectionEdgeStudyTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<LocalDate> DAYS =
            KRX.tradingDays(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31)).subList(0, 60);
    private static final String SNAPSHOT = "krx-daily-edge-0001";
    private static final String ID = "EXP-20261020-EDGET";

    @TempDir
    Path tmp;

    /** 종목 i(1~10), 거래일 d의 종가. */
    private static long close(int i, int d) {
        if (i == 10) {
            return 900;
        }
        return 10_000 + 100L * ((d * (i + 2) + i * 5) % 13);
    }

    private Path snapshot(String id, BiFunction<Integer, Long, Long> closeOverride) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("snapshots").resolve("krx-daily").resolve(id));
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        int n = 0;
        for (int i = 1; i <= 10; i++) {
            long prev = close(i, 0);
            for (int d = 0; d < DAYS.size(); d++) {
                long c = closeOverride.apply(d, close(i, d));
                long o = d == 0 ? c : prev;
                long volume = i == 3 && d == 30 ? 0 : 1000 + i;
                bars.append("KRX,").append(code(i)).append(',').append(DAYS.get(d)).append(',').append(o).append(',')
                        .append(Math.max(o, c) + 50).append(',').append(Math.min(o, c) - 50).append(',').append(c)
                        .append(',').append(volume).append(",1\n");
                prev = c;
                n++;
            }
        }
        Path b = write(dir, SnapshotManifest.DAILY_BARS, bars.toString());
        StringBuilder hist = new StringBuilder("market,code,name,kind,board,valid_from,valid_to\n");
        for (int i = 1; i <= 10; i++) {
            hist.append("KRX,").append(code(i)).append(",종목").append(i).append(',').append(i == 9 ? "ETF" : "STOCK")
                    .append(",KOSPI,2000-01-04,\n");
        }
        Path h = write(dir, SnapshotManifest.INSTRUMENT_HISTORY, hist.toString());
        write(dir, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":%d}],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                """.formatted(id, Sha256.ofFile(b), n, Sha256.ofFile(h), 10, DAYS.get(0), DAYS.get(DAYS.size() - 1)));
        return tmp.resolve("snapshots");
    }

    private static String code(int i) {
        return String.format("%06d", i * 10);
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    /** 저장소 흉내: 선정 프로필과 등록 파일. */
    private Path repo(int parallelism) throws IOException {
        Path root = tmp.resolve("repo");
        write(root.resolve("selections").resolve("test-edge-hot"), "v1.yaml", """
                id: "test-edge-hot"
                version: 1
                market: "KRX"
                style: "INTRADAY"
                created-at: "2026-10-20T00:00:00Z"
                parameters:
                  hypothesis: "시험용"
                  refresh: "PRE_OPEN_DAILY"
                  max-symbols: "2"
                  filter.kinds: "STOCK"
                  filter.min-price: "1000"
                  filter.min-bar-days: "2"
                  gate.01.metric: "return"
                  gate.01.window: "1"
                  gate.01.op: "GE"
                  gate.01.value: "0.03"
                  score.metric: "return"
                  score.window: "1"
                  score.order: "DESC"
                """);
        write(root.resolve("experiments").resolve(ID), SelectionEdgeStudy.REGISTRATION, """
                id: "%s"
                question: "시험"
                market: "KRX"
                bars-snapshot: "%s"
                selection: "test-edge-hot@1"
                test: "RANGE"
                family-size: "1"
                seed: "7"
                null-seeds: "39"
                trade-notional: "1000000"
                holdout-from: "%s"
                parallelism: "%d"
                windows:
                  - "%s..%s"
                  - "%s..%s"
                  - "%s..%s"
                descriptive:
                  - "%s..%s"
                """.formatted(ID, SNAPSHOT, DAYS.get(59), parallelism, DAYS.get(15), DAYS.get(29), DAYS.get(30),
                DAYS.get(44), DAYS.get(45), DAYS.get(58), DAYS.get(3), DAYS.get(14)));
        return root;
    }

    private static String[] args(Path root, Path snapshots, boolean verify) {
        return verify
                ? new String[] {root.toString(), "abc", "false", ID, "--snapshots-dir", snapshots.toString(),
                    "--verify-determinism"}
                : new String[] {root.toString(), "abc", "false", ID, "--snapshots-dir", snapshots.toString()};
    }

    @Test
    void 등록_파일로_결과를_쓰고_병렬_수와_무관하게_같다() throws IOException {
        Path snapshots = snapshot(SNAPSHOT, (d, c) -> c);
        Path root = repo(4);
        assertThat(SelectionEdgeMain.run(args(root, snapshots, true), tmp.resolve("config"), RunGate.NONE)).isZero();
        Path dir = root.resolve("experiments").resolve(ID);
        for (String f : List.of("results.md", "windows.tsv", "days.tsv", "null.tsv", "inputs.yaml")) {
            assertThat(dir.resolve(f)).exists();
        }
        String inputs = Files.readString(dir.resolve("inputs.yaml"));
        assertThat(inputs).contains("determinism: same").contains("bars-snapshot: " + SNAPSHOT)
                .contains("cost-model: KR-DEFAULT-1c6a40d3efb8").contains("model: EDGE-DAILY-V1");
        String results = Files.readString(dir.resolve("results.md"));
        assertThat(results).contains("## 1. 판정").contains("W1 ").contains("D1 ").contains("해석 한계");
        String days = Files.readString(dir.resolve("days.tsv"));
        assertThat(days.lines().count()).as("머리 줄 + 판정 44일 + 기술 통계 12일").isEqualTo(1 + 44 + 12);

        String first = Files.readString(dir.resolve("days.tsv")) + Files.readString(dir.resolve("results.md"));
        repo(1);
        assertThat(SelectionEdgeMain.run(args(root, snapshots, false), tmp.resolve("config"), RunGate.NONE)).isZero();
        String second = Files.readString(dir.resolve("days.tsv")) + Files.readString(dir.resolve("results.md"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 정지_확인이_멈추라면_결과를_쓰지_않고_4() throws IOException {
        Path snapshots = snapshot(SNAPSHOT, (d, c) -> c);
        Path root = repo(2);
        assertThat(SelectionEdgeMain.run(args(root, snapshots, false), tmp.resolve("config"),
                () -> Optional.of("시험 정지"))).isEqualTo(4);
        assertThat(root.resolve("experiments").resolve(ID).resolve("results.md")).doesNotExist();
    }

    @Test
    void 등록_파일이_없거나_틀리면_1() throws IOException {
        Path snapshots = snapshot(SNAPSHOT, (d, c) -> c);
        Path root = repo(2);
        assertThat(SelectionEdgeMain.run(new String[] {root.toString(), "abc", "false", "EXP-20261020-NONE"},
                tmp.resolve("config"), RunGate.NONE)).isEqualTo(1);
        Path reg = root.resolve("experiments").resolve(ID).resolve(SelectionEdgeStudy.REGISTRATION);
        Files.writeString(reg, Files.readString(reg).replace("null-seeds: \"39\"", "null-seeds: \"10\""));
        assertThat(SelectionEdgeMain.run(args(root, snapshots, false), tmp.resolve("config"), RunGate.NONE))
                .isEqualTo(1);
    }

    @Test
    void 선정과_대조군_모집단은_T_일봉을_보지_않는다() throws IOException {
        int tIndex = 40;
        LocalDate t = DAYS.get(tIndex);
        Path a = snapshot(SNAPSHOT, (d, c) -> c);
        // T 이후 종가만 크게 바꾼 스냅샷: 선정·하드 필터 통과 집합은 같고 결과 변수만 달라야 한다
        snapshot("krx-daily-edge-0002", (d, c) -> d >= tIndex ? c + 3_000 : c);
        Path root = repo(1);
        EdgePlan plan = EdgePlan.parse(Files.readString(root.resolve("experiments").resolve(ID)
                .resolve(SelectionEdgeStudy.REGISTRATION)), "t");
        SelectionProfile profile = CombinationExperiment.loadSelection(root, CombinationPlan.Ref.parse("test-edge-hot@1"));
        SelectionEdgeStudy.Day da = day(plan, profile, a, SNAPSHOT, t);
        SelectionEdgeStudy.Day db = day(plan, profile, a, "krx-daily-edge-0002", t);
        assertThat(db.selected()).containsExactly(da.selected());
        assertThat(db.hardPass()).isEqualTo(da.hardPass()).as("ETF·900원 종목 제외").isEqualTo(8);
        assertThat(db.c()).isEqualTo(DAYS.get(tIndex - 1));
        assertThat(db.range()).isNotEqualTo(da.range());
    }

    private static SelectionEdgeStudy.Day day(EdgePlan plan, SelectionProfile profile, Path snapshots, String id,
            LocalDate t) {
        SnapshotInputs in = SnapshotInputs.load(Market.KRX, id, Optional.empty(), snapshots, KRX, RunGate.NONE, s -> { },
                (f, l) -> { });
        KrxMarketRules rules = new KrxMarketRules(KRX, s -> InstrumentKind.STOCK);
        CostModel cost = CostModels.krDefault(s -> KrxBoard.KOSPI);
        return SelectionEdgeStudy.day(plan, profile, new SelectionCalculator(in.selectionData()), in, rules, cost, t);
    }

    @Test
    void 거래정지일은_거래_불가() throws IOException {
        Path snapshots = snapshot(SNAPSHOT, (d, c) -> c);
        Path root = repo(1);
        EdgePlan plan = EdgePlan.parse(Files.readString(root.resolve("experiments").resolve(ID)
                .resolve(SelectionEdgeStudy.REGISTRATION)), "t");
        SelectionProfile profile = CombinationExperiment.loadSelection(root, CombinationPlan.Ref.parse("test-edge-hot@1"));
        SelectionEdgeStudy.Day d = day(plan, profile, snapshots, SNAPSHOT, DAYS.get(30));
        // 하드 필터 통과는 코드 순 000010..000080, 000030은 세 번째
        assertThat(d.range()[2]).isNaN();
        assertThat(d.net()[2]).isNaN();
        assertThat(d.range()[0]).isNotNaN();
    }

    @Test
    void 시가_매수_종가_매도_비용_차감_손_계산() {
        Symbol s = new Symbol(Market.KRX, "000010");
        LocalDate t = LocalDate.of(2026, 3, 3);
        DailyBar b = new DailyBar(s, t, new BigDecimal("10000"), new BigDecimal("10600"), new BigDecimal("9900"),
                new BigDecimal("10500"), 1000, BigDecimal.ONE);
        KrxMarketRules rules = new KrxMarketRules(KRX, x -> InstrumentKind.STOCK);
        CostModel cost = CostModels.krDefault(x -> KrxBoard.KOSPI);
        // 매수 10,000 × 1.0015 = 10,015 → 호가 10원 올림 10,020, 매도 10,500 × 0.9985 = 10,484.25 → 내림 10,480
        // 수량 ⌊1,000,000 ÷ 10,020⌋ = 99. 매수 991,980 + 수수료 148(0.015% 절사)
        // 매도 1,037,520 − 수수료 155 − 거래세 518(코스피 2026 0.05%) − 농특세 1,556(0.15%)
        double expected = new BigDecimal("1035291").divide(new BigDecimal("992128"), MathContext.DECIMAL64)
                .subtract(BigDecimal.ONE).doubleValue();
        assertThat(SelectionEdgeStudy.net(b, s, t, rules, cost, new BigDecimal("1000000"))).isEqualTo(expected);
        assertThat(SelectionEdgeStudy.range(b)).isCloseTo(0.07, within(1e-12));
    }
}
