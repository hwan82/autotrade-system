package com.autotrade.sim.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.sim.selection.SelectionFixtures;
import com.autotrade.sim.selection.SessionSelections;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureRecordings;
import com.autotrade.sim.testing.TestKrxRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 녹화 실험의 선정 조합 계좌(D-124, ADR-012 6장 4번): 녹화일마다 고정한 일봉 스냅샷으로 장전 선정을 계산해 결과 파일과 적용
 * 기록을 결과 폴더 {@code selections/}에 남기고, 선정 계좌는 그날 후보 ∩ boards만 새로 산다. 기준 데이터일보다 이른 스냅샷이면
 * 그날은 {@code STALE_INPUT}이다. 선정 없는 계좌의 결과는 선정 계좌를 더해도 같다.
 *
 * <p>일봉 스냅샷은 {@link SelectionFixtures#dailySnapshot}(마지막 거래일 c = 2026-03-03 직전 거래일): 005930·035720이 예시 (다)
 * {@code kr-intraday-hot@1}에 걸리고 000660은 걸리지 않는다. 035720은 boards 밖이라 후보가 아니다.
 */
class RecordingSelectionTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<MarketEvent> EVENTS =
            FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, TestKrxRules.INSTANCE.zone());
    private static final LocalDate D1 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 4);
    private static final String SNAPSHOT = "krx-daily-sel-0001";
    private static final String BASE = """
            id: "EXP-T-RECSEL"
            question: "녹화 실험 선정 계좌 시험"
            input: "recording"
            market: "KRX"
            from: "2026-03-03"
            to: "2026-03-04"
            seed: "7"
            seed-capital: "10000000"
            parallelism: "2"
            profiles:
              - "kr-orb-30@1"
              - "kr-random-control@1"
            boards:
              - "005930 KOSPI"
              - "000660 KOSPI"
            """;
    private static final String SELECTIONS = """
            selections:
              - "kr-intraday-hot@1 x kr-random-control@1"
            selection-input:
              daily: "%s"
            """.formatted(SNAPSHOT);

    @TempDir
    Path tmp;

    private Path snapshots() {
        return SelectionFixtures.dailySnapshot(tmp.resolve("snapshots"), SNAPSHOT, KRX.previousTradingDay(D1), false);
    }

    private Path experiment(String name, String plan) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name).resolve("EXP-T-RECSEL"));
        write(dir, "experiment.yaml", plan);
        return dir;
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void selectionAccountsUseFixedResultsPerRecordingDay() throws IOException {
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        Path snapshots = snapshots();
        Path dir = experiment("with", BASE + SELECTIONS);

        RecordingExperiment.Outcome out = RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings, snapshots,
                new CodeVersion("test", false), true, m -> { }, RunGate.NONE);

        assertThat(out.report().runs()).hasSize(3);
        RunReport sel = out.report().runs().get(2);
        assertThat(sel.selectionDays()).extracting(RunSelectionDay::selection).satisfiesExactly(
                d -> {
                    assertThat(d.date()).isEqualTo(D1);
                    assertThat(d.symbols()).containsExactly(Symbol.krx("005930"));
                },
                d -> {
                    assertThat(d.date()).isEqualTo(D2);
                    assertThat(d.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
                });
        assertThat(sel.fills()).filteredOn(f -> f.fill().side() == Side.BUY).allSatisfy(f -> {
            assertThat(f.fill().symbol()).isEqualTo(Symbol.krx("005930"));
            assertThat(f.fill().exchangeDate()).isEqualTo(D1);
        });
        // 결정론 확인(병렬 2 vs 1)이 같고, 결과 폴더에 고정 파일과 적용 기록이 남는다
        assertThat(out.files().get("inputs.yaml")).contains("determinism: \"같음(").contains("selection-input:")
                .contains("  daily: \"" + SNAPSHOT + "\"").contains("status: APPLIED").contains("not-subscribed: \"035720\"");
        assertThat(out.files().get("results.md")).contains("## 선정(D-124)").contains("SELECTION_MISSING(STALE_INPUT)");
        String appliedPath = SessionSelections.DIR + "/" + D1 + "/" + SessionSelections.APPLIED_FILE;
        assertThat(out.files()).containsKey(appliedPath);
        assertThat(Files.isRegularFile(dir.resolve(appliedPath))).isTrue();
        assertThat(out.files().keySet()).anySatisfy(k -> assertThat(k).startsWith(SessionSelections.DIR + "/" + D1 + "/")
                .endsWith(".json").doesNotContain("applied").doesNotContain("problem"));

        // 같은 녹화·스냅샷으로 다시 돌리면 고정 파일까지 바이트로 같다(만든 시각을 남기지 않음)
        RecordingExperiment.Outcome again = RecordingExperiment.runAndWrite(experiment("again", BASE + SELECTIONS),
                Path.of(".."), recordings, snapshots, new CodeVersion("test", false), false, m -> { }, RunGate.NONE);
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            if (f.getKey().startsWith(SessionSelections.DIR + "/") || f.getKey().equals("detail.txt")) {
                assertThat(again.files().get(f.getKey())).as(f.getKey()).isEqualTo(f.getValue());
            }
        }

        // 선정 없는 계좌는 선정 계좌를 더하지 않은 실험과 결과가 같다
        RecordingExperiment.Outcome plain = RecordingExperiment.runAndWrite(experiment("plain", BASE), Path.of(".."),
                recordings, snapshots, new CodeVersion("test", false), false, m -> { }, RunGate.NONE);
        assertThat(plain.report().runs()).hasSize(2);
        assertThat(plain.files().keySet()).noneMatch(k -> k.startsWith(SessionSelections.DIR));
        assertThat(plain.files().get("inputs.yaml")).doesNotContain("selection-input:");
        for (int i = 0; i < 2; i++) {
            assertThat(out.report().runs().get(i).fills()).isEqualTo(plain.report().runs().get(i).fills());
        }
    }

    @Test
    void rerunRemovesSelectionFilesOfThePreviousRun() throws IOException {
        // architect PR #197 R4: 기간을 줄이거나 선정을 빼고 다시 돌리면 옛 날짜 고정 파일이 남지 않는다
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        Path snapshots = snapshots();
        Path dir = experiment("rerun", BASE + SELECTIONS);
        Path old = write(Files.createDirectories(dir.resolve(SessionSelections.DIR).resolve("2026-02-27")), "old.json", "{}");
        RecordingExperiment.Outcome out = RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings, snapshots,
                new CodeVersion("test", false), false, m -> { }, RunGate.NONE);
        assertThat(old).doesNotExist();
        assertThat(old.getParent()).doesNotExist();
        for (String key : out.files().keySet()) {
            assertThat(dir.resolve(key)).as(key).isRegularFile();
        }
        // 선정을 빼고 다시 돌리면 선정 폴더가 통째로 없어진다(결과 파일만 남음)
        write(dir, "experiment.yaml", BASE);
        RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings, snapshots, new CodeVersion("test", false), false,
                m -> { }, RunGate.NONE);
        assertThat(dir.resolve(SessionSelections.DIR)).doesNotExist();
        assertThat(dir.resolve("experiment.yaml")).isRegularFile();
    }

    @Test
    void disclosureSelectionReadsTheFixedDisclosureSnapshot() throws IOException {
        // ADR-014 F9: 공시 조건 선정 (라)는 selection-input.disclosures의 스냅샷으로 계산한다. 일봉은 D2 기준 데이터일(D1)까지
        // 담고 공시는 D1 전날까지만 담아, D2는 공시 구간 [D1, D1]을 못 덮어 STALE_INPUT이다(일봉이 아니라 공시 때문에)
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        Path snapshots = SelectionFixtures.dailySnapshot(tmp.resolve("snapshots"), SNAPSHOT, D1, false);
        // D1(03-03) 공시 구간은 기준 데이터일 c(02-27) ~ T 전날(03-02, 대체공휴일): 그 끝날까지만 담는다
        LocalDate c1 = KRX.previousTradingDay(D1);
        SelectionFixtures.disclosureSnapshot(snapshots, "dart-disclosures-sel-0001", LocalDate.of(2026, 1, 1),
                D1.minusDays(1), c1,
                List.of("005930"), "2026-03-03T23:00:00Z");
        String news = """
                selections:
                  - "kr-intraday-news@1 x kr-random-control@1"
                selection-input:
                  daily: "%s"
                  disclosures: "dart-disclosures-sel-0001"
                """.formatted(SNAPSHOT);
        Path dir = experiment("news", BASE + news);
        RecordingExperiment.Outcome out = RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings, snapshots,
                new CodeVersion("test", false), false, m -> { }, RunGate.NONE);
        RunReport sel = out.report().runs().get(2);
        assertThat(sel.selectionDays()).extracting(RunSelectionDay::selection).satisfiesExactly(
                d -> assertThat(d.symbols()).as("공시가 있는 005930만").containsExactly(Symbol.krx("005930")),
                d -> assertThat(d.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT));
        assertThat(out.files().get("inputs.yaml")).contains("  disclosures: \"dart-disclosures-sel-0001\"\n")
                .contains("공시 스냅샷이 gate.01");
        assertThat(out.files().entrySet()).filteredOn(f -> f.getKey().startsWith(SessionSelections.DIR + "/" + D1 + "/")
                && f.getKey().endsWith(".json") && !f.getKey().contains("applied")).singleElement()
                .satisfies(f -> assertThat(f.getValue()).contains("\"disclosures\":{").contains("dart-disclosures-sel-0001"));

        // 공시 입력이 없는 등록은 inputs.yaml에 공시 줄이 없다(F9 전과 같은 모양)
        RecordingExperiment.Outcome hot = RecordingExperiment.runAndWrite(experiment("hot", BASE + SELECTIONS),
                Path.of(".."), recordings, snapshots, new CodeVersion("test", false), false, m -> { }, RunGate.NONE);
        assertThat(hot.files().get("inputs.yaml")).doesNotContain("disclosures");
    }

    @Test
    void disclosureInputMustMatchTheSelections() {
        String news = "selections:\n  - \"kr-intraday-news@1 x kr-random-control@1\"\nselection-input:\n  daily: \""
                + SNAPSHOT + "\"\n";
        // 공시 조건이 있는데 공시 입력이 없음
        assertThatThrownBy(() -> RecordingExperiment.selectionCombinations(Path.of(".."),
                RecordingPlan.parse(BASE + news, "x").selections(), factory()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input.disclosures")
                .hasMessageContaining("필요");
        // 공시 조건이 없는데 공시 입력이 있음
        String hotWith = SELECTIONS + "  disclosures: \"dart-disclosures-sel-0001\"\n";
        assertThatThrownBy(() -> RecordingExperiment.selectionCombinations(Path.of(".."),
                RecordingPlan.parse(BASE + hotWith, "x").selections(), factory()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input.disclosures가 있습니다");
        // 녹화 실험은 공시도 latest를 받지 않는다
        assertThatThrownBy(() -> RecordingPlan.parse(BASE + news + "  disclosures: \"latest\"\n", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input.disclosures에 latest");
        // 형식이 아닌 ID
        assertThatThrownBy(() -> RecordingPlan.parse(BASE + news + "  disclosures: \"Bad_ID\"\n", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("스냅샷 ID 형식");
        // 맞으면 받는다
        RecordingExperiment.selectionCombinations(Path.of(".."),
                RecordingPlan.parse(BASE + news + "  disclosures: \"dart-disclosures-sel-0001\"\n", "x").selections(),
                factory());
    }

    private static com.autotrade.strategy.catalog.CatalogStrategyFactory factory() {
        return new com.autotrade.strategy.catalog.CatalogStrategyFactory(TestKrxRules.INSTANCE);
    }

    @Test
    void selectionPreparationChecksTheHeapBeforeReadingTheSnapshot() {
        // architect PR #197 R4: 녹화 데이터와 같은 힙에 일봉 전체를 올리기 전에 보고, 늘릴 힙을 알려 준다
        long gib = 1024L * 1024 * 1024;
        com.autotrade.sim.selection.SnapshotHeap.requireFits(1_000_000, 3 * gib, "s");
        assertThatThrownBy(() -> com.autotrade.sim.selection.SnapshotHeap.requireFits(3_750_000, 3 * gib, "krx-daily-x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("krx-daily-x")
                .hasMessageContaining("-Pheap=8g");
        assertThat(com.autotrade.sim.selection.SnapshotHeap.suggestedHeapGib(1)).isEqualTo(1);
    }

    @Test
    void selectionsNeedASnapshotFolder() throws IOException {
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        RecordingPlan plan = RecordingPlan.parse(BASE + SELECTIONS, "x");
        assertThatThrownBy(() -> RecordingExperiment.run(plan, Path.of(".."), recordings, new CodeVersion("t", false),
                false, m -> { }, RunGate.NONE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스냅샷");
        // 녹화 실험은 입력을 고정해야 해서 latest를 받지 않는다
        assertThatThrownBy(() -> RecordingPlan.parse(BASE + SELECTIONS.replace(SNAPSHOT, "latest"), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latest");
    }

    @Test
    void intradayAtSelectionRanksFromRecordedEventsWithoutSnapshot() throws IOException {
        // 장중 순위 선정(D-127): 선정 입력 없이 녹화 이벤트로 그날 09:30 순위를 매기고, 결과 폴더에 결과를 남긴다
        Path recordings = tmp.resolve("recordings");
        FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        String intraday = """
                selections:
                  - "kr-intraday-open-momentum@1 x kr-random-control@1"
                """;
        Path dir = experiment("at", BASE + intraday);
        RecordingExperiment.Outcome out = RecordingExperiment.runAndWrite(dir, Path.of(".."), recordings, null,
                new CodeVersion("test", false), true, m -> { }, RunGate.NONE);
        assertThat(out.report().runs()).hasSize(3);
        RunReport sel = out.report().runs().get(2);
        assertThat(sel.selectionDays()).isNotEmpty().allSatisfy(d -> {
            assertThat(d.selection().awaiting()).isFalse();
            assertThat(d.selection().activeFrom()).isPositive();
        });
        assertThat(sel.fills()).filteredOn(f -> f.fill().side() == Side.BUY).allSatisfy(f -> {
            RunSelectionDay d = sel.selectionDays().stream()
                    .filter(x -> x.selection().date().equals(f.fill().exchangeDate())).findFirst().orElseThrow();
            assertThat(d.selection().symbols()).contains(f.fill().symbol());
            assertThat(f.fill().signalEventSeq()).isGreaterThanOrEqualTo(d.selection().activeFrom());
        });
        assertThat(out.files().get("inputs.yaml")).contains("determinism: \"같음(").doesNotContain("selection-input:")
                .contains("intraday-selections:").contains("active-from-seq: ");
        assertThat(out.files().get("results.md")).contains("## 장중 순위 선정(D-127)").doesNotContain("## 선정(D-124)");
        assertThat(out.files().keySet()).filteredOn(k -> k.endsWith(".intraday.json"))
                .hasSize(sel.selectionDays().size())
                .allSatisfy(k -> assertThat(out.files().get(k)).contains("\"kind\":\"session-selection-result\""));
        assertThat(out.files().keySet()).noneMatch(k -> k.endsWith(SessionSelections.APPLIED_FILE));
        // 선정 없는 계좌는 선정 계좌를 더하지 않은 실험과 결과가 같다
        RecordingExperiment.Outcome plain = RecordingExperiment.runAndWrite(experiment("at-plain", BASE), Path.of(".."),
                recordings, null, new CodeVersion("test", false), false, m -> { }, RunGate.NONE);
        for (int i = 0; i < 2; i++) {
            assertThat(out.report().runs().get(i).fills()).isEqualTo(plain.report().runs().get(i).fills());
        }
        // 장중 순위 선정만 있으면 selection-input을 쓰지 않는다
        RecordingPlan withInput = RecordingPlan.parse(BASE + intraday + "selection-input:\n  daily: \"" + SNAPSHOT + "\"\n",
                "x");
        assertThatThrownBy(() -> RecordingExperiment.run(withInput, Path.of(".."), recordings, snapshots(),
                new CodeVersion("t", false), false, m -> { }, RunGate.NONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input");
    }
}
