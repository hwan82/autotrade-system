package com.autotrade.sim.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureRecordings;
import com.autotrade.sim.testing.TestKrxRules;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 녹화 실험 명령 본체(D-069): 등록 파일 → 녹화 → 실제 KRX 규칙 → 결과 파일. */
class RecordingExperimentTest {

    private static final List<MarketEvent> EVENTS =
            FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, TestKrxRules.INSTANCE.zone());
    private static final String PLAN = """
            id: "EXP-T-REC"
            question: "녹화 실험 명령 시험"
            input: "recording"
            market: "KRX"
            from: "2026-03-03"
            to: "2026-03-04"
            seed: "7"
            seed-capital: "10000000"
            benchmark: "005930"
            parallelism: "2"
            profiles:
              - "kr-bh-baseline@1"
              - "kr-orb-30@1"
              - "kr-random-control@1"
            boards:
              - "005930 KOSPI"
              - "000660 KOSPI"
            """;

    @Test
    void 같은_녹화로_두_번_돌리면_결과_파일이_바이트까지_같고_입력_품질이_남는다(@TempDir Path tmp) throws IOException {
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        Path dir = experiment(tmp, PLAN);

        RecordingExperiment.Outcome first = RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings,
                new CodeVersion("test", false), true, m -> { }, RunGate.NONE);
        Map<String, String> firstFiles = read(dir);
        RecordingExperiment.Outcome second = RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings,
                new CodeVersion("test", false), false, m -> { }, RunGate.NONE);

        assertThat(first.files().get("detail.txt")).isEqualTo(second.files().get("detail.txt"));
        assertThat(firstFiles.get("detail.txt")).isEqualTo(first.files().get("detail.txt"));
        String inputs = firstFiles.get("inputs.yaml");
        assertThat(inputs)
                .contains("data-set: \"" + first.data().version() + "\"")
                .contains("adapter: REC-ADAPTER-V1")
                .contains("execution-model: \"BOOK-V5;latency=PT0.5S\"")
                .contains("  - date: 2026-03-03\n")
                .contains("    recorded-events: 14\n")
                .contains("    complete: true\n")
                .contains("    truncated: false\n")
                .contains("    segments: 1\n")
                .contains("determinism: \"같음(병렬 2 vs 1, 상세 보고서 바이트 일치)\"")
                .contains("detail-sha256: " + RecordingExperiment.sha256(first.files().get("detail.txt")));
        assertThat(first.data().days()).allSatisfy(d -> assertThat(d.fileSha256()).hasSize(64));
        assertThat(inputs).contains("file-sha256: " + first.data().days().get(0).fileSha256());
        String results = firstFiles.get("results.md");
        assertThat(results).contains("| EXP-T-REC-A01 | kr-bh-baseline@1 | 2 | ").contains("| 샤프 |");
        assertThat(first.report().runs()).hasSize(3);
        assertThat(first.report().result().runs()).allSatisfy(r -> assertThat(r.failureReason()).isEmpty());
        // 실제 KRX 매매 단계로 돌아도 체결이 난다(단계 판정이 모두 장 밖으로 빠지는 회귀 방지, architect PR #74 R3)
        assertThat(first.report().runs().get(0).fills()).isNotEmpty();
    }

    @Test
    void 실행_명령_병렬_상한이_1이면_결정론_확인을_같음으로_적지_않는다(@TempDir Path tmp) throws IOException {
        // 등록 파일 parallelism 2라도 실행 명령 상한 1이면 두 실행이 모두 직렬이라 비교가 안 된다(D-089, PR #113 architect F1)
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        Path dir = experiment(tmp, PLAN);
        RunGate capOne = new RunGate() {
            @Override
            public java.util.Optional<String> stopReason() {
                return java.util.Optional.empty();
            }

            @Override
            public int maxParallelRuns() {
                return 1;
            }
        };
        List<String> log = new java.util.ArrayList<>();

        RecordingExperiment.Outcome out = RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings,
                new CodeVersion("test", false), true, log::add, capOne);

        assertThat(out.files().get("inputs.yaml")).doesNotContain("같음(").contains("determinism: \"확인 안 함(실제 병렬 수가 1");
        assertThat(log).contains("프로필 3개 실행(병렬 1)");
    }

    @Test
    void 녹화_종목의_소속부가_없거나_벤치마크가_녹화에_없으면_시작하지_않는다(@TempDir Path tmp) throws IOException {
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        RecordingPlan noBoard = RecordingPlan.parse(PLAN.replace("  - \"000660 KOSPI\"\n", ""), "t");
        assertThatThrownBy(() -> RecordingExperiment.run(noBoard, Path.of(".."), recordings, new CodeVersion("t", false),
                false, m -> { }, RunGate.NONE)).hasMessageContaining("소속부가 없는 녹화 종목입니다: 000660");
        RecordingPlan badBench = RecordingPlan.parse(PLAN.replace("benchmark: \"005930\"", "benchmark: \"035420\""), "t");
        assertThatThrownBy(() -> RecordingExperiment.run(badBench, Path.of(".."), recordings, new CodeVersion("t", false),
                false, m -> { }, RunGate.NONE)).hasMessageContaining("벤치마크 종목이 녹화에 없습니다: 035420");
        RecordingPlan empty = RecordingPlan.parse(PLAN.replace("2026-03-03", "2026-03-05").replace("2026-03-04", "2026-03-06"), "t");
        assertThatThrownBy(() -> RecordingExperiment.run(empty, Path.of(".."), recordings, new CodeVersion("t", false),
                false, m -> { }, RunGate.NONE)).hasMessageContaining("기간 안에 녹화가 없습니다");
    }

    @Test
    void 주식_호가_단위에_맞지_않는_가격이_있으면_시작하지_않는다(@TempDir Path tmp) throws IOException {
        // D-118: 180,050은 100원 단위의 반 칸이라 중간가 체결로 받으므로 반 칸도 아닌 5원 단위 가격(180,005)으로 본다
        // architect PR #74 R2: ETF(5원 단위) 같은 가격이 녹화에 있으면 주식 호가 단위로 돌리지 않고 거절한다
        List<MarketEvent> events = new ArrayList<>(EVENTS);
        MarketEvent last = events.get(events.size() - 1);
        events.add(new TradeTick(Symbol.krx("000660"), last.sequence() + 1, last.exchangeTime().plusSeconds(1),
                last.receivedAt().plusSeconds(1), new BigDecimal("180005"), 1));
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), events);
        RecordingPlan plan = RecordingPlan.parse(PLAN, "t");
        assertThatThrownBy(() -> RecordingExperiment.run(plan, Path.of(".."), recordings, new CodeVersion("t", false),
                false, m -> { }, RunGate.NONE))
                .hasMessageContaining("주식 호가 단위에 맞지 않는 녹화 가격입니다: 000660 180005");
    }

    @Test
    void 중간가_체결과_단위_밖_가격과_호가를_같은_검사가_가른다_D118() {
        var rules = TestKrxRules.INSTANCE;
        Symbol s = Symbol.krx("005930");
        java.time.Instant t = java.time.Instant.parse("2026-03-03T01:00:00Z");
        // 218,750 = 218,500과 219,000의 중간(500원 단위): 체결이면 통과
        assertThat(RecordingExperiment.offStockTick(rules, new TradeTick(s, 1, t, t, new BigDecimal("218750"), 1))).isEmpty();
        // 반 칸이 아닌 단위 밖 체결은 그대로 걸린다
        assertThat(RecordingExperiment.offStockTick(rules, new TradeTick(s, 1, t, t, new BigDecimal("218700"), 1)))
                .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("218700"));
        // 같은 반 칸 가격이라도 호가 가격이면 걸린다
        var book = new com.autotrade.domain.market.OrderBookSnapshot(s, 1, t, t,
                List.of(new com.autotrade.domain.market.PriceLevel(new BigDecimal("218750"), 5)),
                List.of(new com.autotrade.domain.market.PriceLevel(new BigDecimal("218500"), 5)));
        assertThat(RecordingExperiment.offStockTick(rules, book)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("218750"));
        assertThatThrownBy(() -> RecordingExperiment.requireStockTicks(rules, book))
                .hasMessageContaining("주식 호가 단위에 맞지 않는 녹화 가격입니다: 005930 218750");
    }

    @Test
    void 등록_파일은_input_recording과_정해진_모양만_받는다() {
        RecordingPlan p = RecordingPlan.parse(PLAN, "t");
        assertThat(p.profiles()).extracting(RecordingPlan.ProfileRef::text)
                .containsExactly("kr-bh-baseline@1", "kr-orb-30@1", "kr-random-control@1");
        assertThat(p.boards()).containsOnlyKeys("000660", "005930");
        assertThat(p.parallelism()).isEqualTo(2);

        assertThatThrownBy(() -> RecordingPlan.parse(PLAN.replace("input: \"recording\"\n", ""), "f.yaml"))
                .hasMessageContaining("f.yaml: 필수 키가 없습니다: input");
        assertThatThrownBy(() -> RecordingPlan.parse(PLAN.replace("input: \"recording\"", "input: \"daily\""), "f"))
                .hasMessageContaining("input은 \"recording\"이어야 합니다");
        assertThatThrownBy(() -> RecordingPlan.parse(PLAN.replace("  - \"kr-orb-30@1\"", "  - \"kr-x@1 x kr-orb-30@1\""), "f"))
                .hasMessageContaining("프로필은 id@버전 형식이어야 합니다");
        assertThatThrownBy(() -> RecordingPlan.parse(PLAN.replace("000660 KOSPI", "005930 KOSDAQ"), "f"))
                .hasMessageContaining("같은 종목의 소속부가 두 번 있습니다: 005930");
        assertThatThrownBy(() -> RecordingPlan.parse(PLAN.replace("000660 KOSPI", "000660 NASDAQ"), "f"))
                .hasMessageContaining("비용 표에 없는 소속부입니다");
        assertThatThrownBy(() -> RecordingPlan.parse(PLAN.replace("market: \"KRX\"", "market: \"US\""), "f"))
                .hasMessageContaining("국내(KRX)만");
        assertThatThrownBy(() -> RecordingPlan.parse(PLAN + "bars-snapshot: \"x\"\n", "f.yaml"))
                .hasMessageContaining("f.yaml:18: 모르는 키입니다: bars-snapshot");
    }

    private static Path experiment(Path tmp, String plan) throws IOException {
        Path dir = tmp.resolve("experiments").resolve("EXP-T-REC");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("experiment.yaml"), plan, StandardCharsets.UTF_8);
        return dir;
    }

    private static Map<String, String> read(Path dir) throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.forEach(files::add);
        }
        for (Path f : files) {
            out.put(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
        }
        return out;
    }
}
