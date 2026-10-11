package com.autotrade.sim.combo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 수집 스냅샷 폴더 → 조합 실험 → 결과 파일까지(D-060). 저장소의 실제 프로필 {@code kr-ma-trend@1}(3/8일 종가 이동평균,
 * 진입 09:05~10:00)과 (나) 선정 {@code kr-vol-surge-quiet@1}(거래량 20일/앞 20일 ≥ 3, 20일 수익률 < 3%)을 쓴다.
 *
 * <p>일봉(2026-01-02부터 번들 캘린더 거래일 80일, i = 0부터 셈)
 * <ul>
 *   <li>A(000020): 종가 10,000 + 100i, 시가 = 종가 − 50, 거래량 1,000 고정 → 추세는 오르지만 (나)에 안 걸림</li>
 *   <li>B(000010): 종가 10,000 + 10i, 시가 = 종가 − 5, 거래량 i &lt; 20이면 100, 그 뒤 400 → 선정 기준일 i = 40·41·42에만
 *       (나)에 걸림(직전 20일 400 ÷ 앞 20일 평균 100·115·130 ≥ 3, 20일 수익률 약 2%)</li>
 * </ul>
 * 체결가 손 계산: 시장가 매수 = 다음 체결가(09:20 시가) × 1.0015를 호가 단위(5,000~20,000원 10원)로 올림.
 */
class CombinationExperimentTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<LocalDate> DAYS =
            KRX.tradingDays(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31)).subList(0, 80);
    private static final Symbol A = Symbol.krx("000020");
    private static final Symbol B = Symbol.krx("000010");
    private static final String SNAPSHOT = "krx-daily-test-0001";
    private static final String ID = "EXP-TEST-CMB01";

    @TempDir
    Path tmp;

    private Path snapshots() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("snapshots").resolve("krx-daily").resolve(SNAPSHOT));
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        for (int i = 0; i < DAYS.size(); i++) {
            LocalDate d = DAYS.get(i);
            int bc = 10_000 + 10 * i;
            bars.append("KRX,000010,").append(d).append(',').append(bc - 5).append(',').append(bc + 5).append(',')
                    .append(bc - 10).append(',').append(bc).append(',').append(i < 20 ? 100 : 400).append(",1\n");
            int ac = 10_000 + 100 * i;
            bars.append("KRX,000020,").append(d).append(',').append(ac - 50).append(',').append(ac + 50).append(',')
                    .append(ac - 100).append(',').append(ac).append(",1000,1\n");
        }
        Path b = write(dir, SnapshotManifest.DAILY_BARS, bars.toString());
        Path h = write(dir, SnapshotManifest.INSTRUMENT_HISTORY, """
                market,code,name,kind,board,valid_from,valid_to
                KRX,000010,나,STOCK,KOSDAQ/우량기업부,2000-01-04,
                KRX,000020,가,STOCK,KOSPI,2000-01-04,
                """);
        write(dir, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":2}],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                """.formatted(SNAPSHOT, Sha256.ofFile(b), DAYS.size() * 2, Sha256.ofFile(h), DAYS.get(0),
                DAYS.get(DAYS.size() - 1)));
        return tmp.resolve("snapshots");
    }

    private Path experiment(String combinations) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("experiments").resolve(ID));
        write(dir, "experiment.yaml", """
                id: "%s"
                question: "테스트"
                market: "KRX"
                from: "%s"
                to: "%s"
                seed: "7"
                seed-capital: "10000000"
                bars-snapshot: "%s"
                parallelism: "2"
                combinations:
                %s""".formatted(ID, DAYS.get(0), DAYS.get(DAYS.size() - 1), SNAPSHOT, combinations));
        return dir;
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static final String COMBOS = """
              - "none x kr-ma-trend@1"
              - "kr-vol-surge-quiet@1 x kr-ma-trend@1"
            """;

    private CombinationExperiment.Outcome run(boolean verify) throws IOException {
        Path snapshots = snapshots();
        Path dir = experiment(COMBOS);
        List<String> log = new ArrayList<>();
        return CombinationExperiment.runAndWrite(dir, Path.of(".."), snapshots, new CodeVersion("test", false), verify,
                log::add);
    }

    /** Run의 매수 체결(종목, 체결 이벤트의 거래일·시각, 가격). */
    private static List<String> buys(SimulationReport report, int run) {
        RunReport r = report.runs().get(run);
        List<String> out = new ArrayList<>();
        for (AppliedFill a : r.fills()) {
            Fill f = a.fill();
            if (f.side() == Side.BUY) {
                out.add(f.symbol().code() + " " + f.price().toPlainString() + " fill-seq " + f.fillEventSeq());
            }
        }
        return out;
    }

    private static String at(CombinationExperiment.Outcome out, long seq) {
        // 이벤트 순서: 거래일 → 칸 → 종목. 거래일마다 2종목 × 6칸
        long index = seq - 1;
        int day = (int) (index / 12);
        int slot = (int) (index % 12) / 2;
        return DAYS.get(day) + " " + List.of("09:00", "09:10", "09:20", "11:00", "13:00", "15:00").get(slot);
    }

    @Test
    void 선정_없음은_추세가_서는_날_다음_시가에_사고_선정_있는_계좌는_선정된_날만_산다() throws IOException {
        CombinationExperiment.Outcome out = run(true);
        SimulationReport report = out.report();

        assertThat(report.runs()).allSatisfy(r -> assertThat(r.summary().status()).isEqualTo(RunStatus.COMPLETED));
        // 종가 8일이 쌓인 i = 8에 09:10 판단 → 09:20 시가 체결. A 10,750 × 1.0015 = 10,766.1 → 10,770, B 10,075 → 10,090.1 → 10,100
        List<String> none = buys(report, 0);
        assertThat(none).hasSize(2);
        assertThat(none.get(0)).startsWith("000010 10100 ");
        assertThat(none.get(1)).startsWith("000020 10770 ");
        long seqB = Long.parseLong(none.get(0).substring(none.get(0).lastIndexOf(' ') + 1));
        assertThat(at(out, seqB)).isEqualTo(DAYS.get(8) + " 09:20");

        // (나) 선정: B가 처음 걸리는 선정 기준일 i = 40에 산다. 10,395 × 1.0015 = 10,410.6 → 10,420. A는 끝까지 막힌다
        List<String> sel = buys(report, 1);
        assertThat(sel).hasSize(1);
        assertThat(sel.get(0)).startsWith("000010 10420 ");
        long seqSel = Long.parseLong(sel.get(0).substring(sel.get(0).lastIndexOf(' ') + 1));
        assertThat(at(out, seqSel)).isEqualTo(DAYS.get(40) + " 09:20");
        assertThat(report.runs().get(1).selectionDays().stream().mapToInt(d -> d.blockedEntries()).sum()).isPositive();

        // 결과 파일: 입력 스냅샷 ID가 남아 수집기 보관 규칙이 지우지 않는다, 결정론 확인
        Path dir = tmp.resolve("experiments").resolve(ID);
        assertThat(Files.readString(dir.resolve("inputs.yaml"))).contains("snapshot: " + SNAPSHOT)
                .contains("data-set: \"" + SNAPSHOT + "#DAILY-PATH-V1#" + DAYS.get(0) + "~" + DAYS.get(79) + "\"")
                .contains("determinism: \"같음");
        assertThat(Files.readString(dir.resolve("results.md"))).contains("| `none` |").contains("| `kr-vol-surge-quiet@1` |")
                .contains("비교한 조합 수 m = 2");
        assertThat(Files.readString(dir.resolve("selection-days.tsv"))).contains("kr-vol-surge-quiet@1\t" + DAYS.get(40));
        assertThat(Files.readString(dir.resolve("runs.tsv")).lines()).hasSize(3);
        assertThat(dir.resolve("detail.txt")).exists();
    }

    @Test
    void 같은_입력이면_결과_파일이_바이트까지_같다() throws IOException {
        CombinationExperiment.Outcome first = run(false);
        CombinationExperiment.Outcome second = run(false);
        assertThat(second.files()).isEqualTo(first.files());
    }

    @Test
    void 실험_기간이_스냅샷_밖이거나_스타일이_다른_조합은_시작하지_않는다() throws IOException {
        Path snapshots = snapshots();
        CombinationPlan base = CombinationPlan.parse(Files.readString(experiment(COMBOS).resolve("experiment.yaml")), "f");
        CombinationPlan late = new CombinationPlan(base.id(), base.question(), base.market(), base.from(),
                DAYS.get(79).plusDays(7), base.seed(), base.seedCapital(), base.barsSnapshot(), base.financialsSnapshot(),
                base.benchmark(), 1, base.combinations(), base.blacklist());
        assertThatThrownBy(() -> CombinationExperiment.run(late, Path.of(".."), snapshots, new CodeVersion("t", false),
                false, s -> { })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("스냅샷 범위 밖");

        CombinationPlan mixed = CombinationPlan.parse(Files.readString(experiment(
                "  - \"kr-fin-quality-growth@1 x kr-ma-trend@1\"\n").resolve("experiment.yaml")), "f");
        assertThatThrownBy(() -> CombinationExperiment.run(mixed, Path.of(".."), snapshots, new CodeVersion("t", false),
                false, s -> { })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("스타일");
    }

    @Test
    void 소속부는_세부_소속부를_떼고_비용_표_구분으로_바꾼다() {
        var p = new com.autotrade.marketdata.daily.InstrumentPeriod(B, "나",
                com.autotrade.marketdata.rules.InstrumentKind.STOCK, "KOSDAQ/우량기업부", LocalDate.of(2000, 1, 4),
                java.util.Optional.empty());
        assertThat(CombinationExperiment.board(p, B)).hasToString("KOSDAQ");
    }
}
