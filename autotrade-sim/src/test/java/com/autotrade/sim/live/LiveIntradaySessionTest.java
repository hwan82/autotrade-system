package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.recording.EventSequences;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.SessionSelections;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실시간 모의의 장중 순위 선정 계좌(D-127, ADR-012 5.2 2차-a): 그날 09:30 전 받은 체결로 boards 안 순위를 매기고, 순위를 매긴
 * 이벤트부터 후보만 새로 산다. 장 중간 재기동과 리플레이 확인은 같은 기록으로 같은 순위·같은 계좌를 낸다. 선정 입력(selection-input)
 * 없이 등록한다. 선정이 없는 계좌는 선정 계좌를 더해도 결과가 같다.
 */
class LiveIntradaySessionTest {

    private static final Path REPO = Path.of("..");
    private static final String ID = "EXP-TEST-LIVEAT";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate D1 = LocalDate.of(2026, 10, 14);
    private static final LocalTime AT = LocalTime.of(9, 30);

    @TempDir
    Path tmp;

    final LiveSessionTest.Mutable clock = new LiveSessionTest.Mutable();

    private static String yaml(boolean withSelections) {
        StringBuilder sb = new StringBuilder(String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"장중 순위 계좌가 순위 시각 뒤 후보만 사는가\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-15\"",
                "seed: \"20261014\"",
                "seed-capital: \"10000000\"",
                "profiles:",
                "  - \"kr-orb-30@1\"",
                "  - \"kr-random-control@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "  - \"000660 KOSPI\"",
                ""));
        if (withSelections) {
            sb.append(String.join("\n",
                    "selections:",
                    "  - \"kr-intraday-open-momentum@1 x kr-random-control@1\"",
                    ""));
        }
        return sb.toString();
    }

    private Path experimentDir(Path root, boolean withSelections) throws Exception {
        Path dir = root.resolve("experiments").resolve(ID);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("experiment.yaml"), yaml(withSelections), StandardCharsets.UTF_8);
        return dir;
    }

    private static LivePlan plan(boolean withSelections) {
        return LivePlan.parse(yaml(withSelections), "experiment.yaml");
    }

    /**
     * {@link LiveSessionTest#twoDays()} 앞에 거래일마다 09:00:00.5 삼성전자 57,000원·SK하이닉스 171,000원 체결을 하나씩 넣는다. 그날
     * 09:30 전 장중 수익률이 약 5%(0.01~0.15 안)가 되어 후보가 생긴다(무작위 걸음만으로는 1%를 넘지 않아 시험이 비게 됨).
     */
    static List<MarketEvent> events() {
        List<MarketEvent> out = new java.util.ArrayList<>();
        LocalDate last = null;
        for (MarketEvent e : LiveSessionTest.twoDays()) {
            LocalDate d = e.exchangeTime().atZone(SEOUL).toLocalDate();
            if (!d.equals(last)) {
                java.time.Instant t = d.atTime(9, 0, 0, 500_000_000).atZone(SEOUL).toInstant();
                out.add(new TradeTick(Symbol.krx("005930"), 0, t, t, new java.math.BigDecimal("57000"), 10));
                out.add(new TradeTick(Symbol.krx("000660"), 0, t, t, new java.math.BigDecimal("171000"), 10));
                last = d;
            }
            out.add(e);
        }
        List<MarketEvent> renumbered = new java.util.ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            renumbered.add(EventSequences.withSequence(out.get(i), i + 1));
        }
        return renumbered;
    }

    @Test
    void intradayAccountBuysOnlyRankedCandidatesFromRankEventAndReplaysTheSame() throws Exception {
        Path root = tmp.resolve("with");
        Path experimentDir = experimentDir(root, true);
        Path sessions = root.resolve("live-sessions");
        List<MarketEvent> events = events();
        // D1 11:00(순위 시각 뒤) 재기동: 기록을 다시 돌려 추적기의 순위·적용 시작 순번도 되살린다. 순위 시각 전 재기동은
        // 재기동 공백 표시가 boards 전 종목을 SESSION_GAP으로 만든다(아래 restartBeforeAtMarksEveryBoardSymbolAsGap)
        int restartAt = 0;
        while (events.get(restartAt).exchangeTime().atZone(SEOUL).toLocalTime().isBefore(LocalTime.of(11, 0))) {
            restartAt++;
        }
        assertThat(restartAt).isGreaterThan(0);
        Map<LocalDate, Long> firstAtOrAfter = new TreeMap<>();
        for (MarketEvent e : events) {
            var local = e.exchangeTime().atZone(SEOUL);
            if (!local.toLocalTime().isBefore(AT)) {
                firstAtOrAfter.putIfAbsent(local.toLocalDate(), e.sequence());
            }
        }

        Map<LocalDate, Long> rankedFrom = new TreeMap<>();
        LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { });
        assertThat(s.hasSelections()).isFalse(); // 장전 선정 파일은 쓰지 않는다
        for (int n = 0; n < events.size(); n++) {
            MarketEvent e = events.get(n);
            clock.now = e.receivedAt();
            if (n == restartAt) {
                s.close();
                s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { });
            }
            if (e.sequence() == firstAtOrAfter.get(D1)) {
                assertThat(s.status().intraday()).singleElement()
                        .satisfies(st -> assertThat(st.state()).isEqualTo("AWAITING"));
            }
            assertThat(s.accept("KIWOOM-WS", e)).isEqualTo(LiveSession.Accepted.ACCEPTED);
            if (e.sequence() == firstAtOrAfter.get(D1)) {
                assertThat(s.status().intraday()).singleElement().satisfies(st -> {
                    assertThat(st.state()).isEqualTo("RANKED");
                    // 순번은 세션 기록 위치(재기동 공백 표시가 끼면 받은 이벤트 순번과 다르다)
                    rankedFrom.put(D1, st.activeFrom());
                    assertThat(st.at()).isEqualTo("09:30");
                });
            }
        }
        assertThat(Files.exists(sessions.resolve(ID).resolve(SessionSelections.DIR))).isFalse();

        SimulationReport live = s.finish(new CodeVersion("live", false));
        assertThat(live.runs()).hasSize(3);
        RunReport sel = live.runs().get(2);
        assertThat(sel.combination().hasSelection()).isTrue();
        List<RunSelectionDay> days = sel.selectionDays();
        assertThat(days).extracting(d -> d.selection().date()).containsExactly(D1, LocalDate.of(2026, 10, 15));
        for (RunSelectionDay d : days) {
            assertThat(d.selection().awaiting()).isFalse();
            assertThat(d.selection().reason()).isNull();
            assertThat(d.selection().activeFrom()).isPositive();
        }
        assertThat(days.get(0).selection().activeFrom()).isEqualTo(rankedFrom.get(D1));
        // 새로 산 것은 모두 그날 후보이고 순위를 매긴 이벤트 이후 신호다(빈 확인이 되지 않도록 산 것이 있어야 한다)
        assertThat(sel.fills()).filteredOn(f -> f.fill().side() == Side.BUY).isNotEmpty().allSatisfy(f -> {
            RunSelectionDay d = days.stream().filter(x -> x.selection().date().equals(f.fill().exchangeDate()))
                    .findFirst().orElseThrow();
            assertThat(d.selection().symbols()).contains(f.fill().symbol());
            assertThat(f.fill().signalEventSeq()).isGreaterThanOrEqualTo(d.selection().activeFrom());
        });

        Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), ReportWriter.render(live),
                StandardCharsets.UTF_8);
        LiveReplayCheck.Outcome out = LiveReplayCheck.run(experimentDir, REPO, sessions, new CodeVersion("check", true),
                m -> { }, RunGate.NONE);
        assertThat(out.same()).isTrue();

        // 선정 없는 계좌는 선정 계좌를 더하지 않은 세션과 결과가 같다
        Path plainRoot = tmp.resolve("plain");
        experimentDir(plainRoot, false);
        Path plainSessions = plainRoot.resolve("live-sessions");
        LiveSession plain = LiveSession.open(plan(false), REPO, plainSessions, clock, m -> { });
        for (int n = 0; n < events.size(); n++) {
            clock.now = events.get(n).receivedAt();
            if (n == restartAt) { // 같은 자리에서 재기동해야 기록 위치(순번)가 같다
                plain.close();
                plain = LiveSession.open(plan(false), REPO, plainSessions, clock, m -> { });
            }
            plain.accept("KIWOOM-WS", events.get(n));
        }
        assertThat(plain.status().intraday()).isEmpty();
        SimulationReport base = plain.finish(new CodeVersion("live", false));
        assertThat(base.runs()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(live.runs().get(i).summary().runId()).isEqualTo(base.runs().get(i).summary().runId());
            assertThat(live.runs().get(i).fills()).isEqualTo(base.runs().get(i).fills());
            assertThat(live.runs().get(i).rejections()).isEqualTo(base.runs().get(i).rejections());
        }
    }

    @Test
    void restartBeforeAtMarksEveryBoardSymbolAsGap() throws Exception {
        // 실험실이 꺼져 있던 동안 받지 못한 체결이 있을 수 있어 재기동 공백 표시가 boards 전 종목에 붙는다. 그날 순위는 SESSION_GAP으로
        // 후보 0(신규 진입 0)이 된다. 값을 믿을 수 없는 순위로 사지 않는다(D-127)
        Path sessions = tmp.resolve("live-sessions");
        experimentDir(tmp, true);
        List<MarketEvent> events = events();
        LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { });
        boolean restarted = false;
        for (MarketEvent e : events) {
            LocalTime local = e.exchangeTime().atZone(SEOUL).toLocalTime();
            if (!local.isBefore(LocalTime.of(10, 0))) {
                break;
            }
            if (!restarted && !local.isBefore(LocalTime.of(9, 15))) {
                s.close();
                s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { });
                restarted = true;
            }
            clock.now = e.receivedAt();
            s.accept("KIWOOM-WS", e);
        }
        assertThat(restarted).isTrue();
        assertThat(s.status().intraday()).singleElement().satisfies(st -> {
            assertThat(st.state()).isEqualTo("RANKED");
            assertThat(st.selectedCount()).isZero();
            assertThat(st.candidates()).isEmpty();
        });
        s.close();
    }

    @Test
    void dayThatNeverReachesAtBuysNothing() throws Exception {
        Path sessions = tmp.resolve("live-sessions");
        experimentDir(tmp, true);
        List<MarketEvent> early = LiveSessionTest.twoDays().stream()
                .filter(e -> e.exchangeTime().atZone(SEOUL).toLocalTime().isBefore(AT)
                        && e.exchangeTime().atZone(SEOUL).toLocalDate().equals(D1))
                .toList();
        try (LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { })) {
            for (MarketEvent e : early) {
                clock.now = e.receivedAt();
                s.accept("KIWOOM-WS", e);
            }
            assertThat(s.status().intraday()).singleElement()
                    .satisfies(st -> assertThat(st.state()).isEqualTo("AWAITING"));
            SimulationReport r = s.finish(new CodeVersion("live", false));
            RunReport sel = r.runs().get(2);
            assertThat(sel.selectionDays()).singleElement().satisfies(d -> {
                assertThat(d.selection().awaiting()).isTrue();
                assertThat(d.selection().reason()).isEqualTo(SelectionInvalidReason.NOT_YET);
            });
            assertThat(sel.fills()).filteredOn(f -> f.fill().side() == Side.BUY).isEmpty();
        }
    }

    @Test
    void intradayOnlyRegistrationRejectsSelectionInput() {
        String withInput = yaml(true) + "selection-input:\n  daily: \"latest\"\n";
        assertThatThrownBy(() -> LiveSetup.of(LivePlan.parse(withInput, "x"), REPO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input");
    }
}
