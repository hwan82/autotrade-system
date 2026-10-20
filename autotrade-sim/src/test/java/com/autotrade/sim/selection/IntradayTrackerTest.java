package com.autotrade.sim.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.rules.BundledMarketRules;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.combo.CombinationPlan;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 장중 순위 선정 추적기(D-127): 정규장 시작 ~ at 전 boards 체결만 모으고, at 이상 첫 이벤트(누락 표시 제외)에서 그 이벤트를 넣기 전
 * 순위를 매긴다. 늦게 온 at 전 체결·장전 체결·boards 밖 체결은 넣지 않고, 정규장 누락 종목은 SESSION_GAP, blacklist는 메우지 않고 뺀다.
 *
 * <p>손 계산(예시 (마): session-return 0.01~0.15, session-turnover 내림차순, 최대 10종목)
 * <pre>
 * A 005930  09:00 100×10, 09:10 102×10        거래대금 2,020   수익률 0.02  → 통과
 * B 000660  09:05 100×1,  09:20 105×1         거래대금   205   수익률 0.05  → 통과 (늦은 09:29 200×1은 빠짐)
 * C 035420  09:15 100×100, 09:25 110×100      거래대금 21,000  수익률 0.10  → 통과, blacklist라 후보에서 뺌
 * D 051910  09:01 100×1, 09:02 103×1, 정규장 누락 → SESSION_GAP
 * E 207940  09:03 100×1, 09:04 100×1          수익률 0     → GATE_01
 * 선정 C, A, B → 후보 A, B, 적용 시작 = 09:30 첫 이벤트 순번 20
 * </pre>
 */
class IntradayTrackerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate D1 = LocalDate.of(2026, 10, 14);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 15);
    private static final Set<String> BOARDS = Set.of("005930", "000660", "035420", "051910", "207940");

    private static SelectionProfile momentum() {
        return CombinationExperiment.loadSelection(Path.of(".."), new CombinationPlan.Ref("kr-intraday-open-momentum", 1));
    }

    private static IntradayTracker tracker() {
        return new IntradayTracker(List.of(momentum(), SelectionFixtures.intradayHot(Path.of(".."))),
                new IntradayUniverse(BOARDS, Set.of("035420"), BundledMarketRules.of(Market.KRX)));
    }

    private static Instant at(LocalDate d, int h, int m, int s) {
        return d.atTime(h, m, s).atZone(SEOUL).toInstant();
    }

    private static TradeTick trade(String code, long seq, Instant t, long price, long qty) {
        return new TradeTick(Symbol.krx(code), seq, t, t, BigDecimal.valueOf(price), qty);
    }

    private static List<MarketEvent> dayOne() {
        List<MarketEvent> e = new ArrayList<>();
        e.add(trade("005930", 1, at(D1, 8, 50, 0), 90, 1000)); // 장전: 넣지 않음
        e.add(trade("005930", 2, at(D1, 9, 0, 0), 100, 10));
        e.add(trade("051910", 3, at(D1, 9, 1, 0), 100, 1));
        e.add(trade("051910", 4, at(D1, 9, 2, 0), 103, 1));
        e.add(trade("207940", 5, at(D1, 9, 3, 0), 100, 1));
        e.add(trade("207940", 6, at(D1, 9, 4, 0), 100, 1));
        e.add(trade("000660", 7, at(D1, 9, 5, 0), 100, 1));
        e.add(trade("005930", 8, at(D1, 9, 10, 0), 102, 10));
        e.add(trade("035420", 9, at(D1, 9, 15, 0), 100, 100));
        e.add(trade("000660", 10, at(D1, 9, 20, 0), 105, 1));
        e.add(new DataGap(Symbol.krx("051910"), 11, at(D1, 9, 21, 0), at(D1, 9, 21, 0), at(D1, 9, 20, 0),
                at(D1, 9, 21, 0), DataGap.GapReason.DISCONNECTED));
        e.add(trade("035420", 12, at(D1, 9, 25, 0), 110, 100));
        e.add(trade("000270", 13, at(D1, 9, 26, 0), 100, 1_000_000)); // boards 밖
        // at 뒤에 시작한 누락 표시는 순위를 매기지 않고(순위 시각 신호가 아님) 그 종목을 SESSION_GAP으로도 만들지 않는다
        e.add(new DataGap(Symbol.krx("000660"), 14, at(D1, 9, 31, 0), at(D1, 9, 31, 0), at(D1, 9, 31, 0),
                at(D1, 9, 31, 0), DataGap.GapReason.DISCONNECTED));
        e.add(trade("005930", 20, at(D1, 9, 30, 0), 101, 1)); // 정확히 at → 순위 시각(이 체결은 넣지 않음)
        e.add(trade("000660", 21, at(D1, 9, 29, 0), 200, 1)); // 늦게 온 at 전 체결: 넣지 않음
        return e;
    }

    @Test
    void ranksAtFirstEventAtOrAfterAtWithOnlyEarlierRegularSessionTrades() {
        IntradayTracker t = tracker();
        SelectionProfile p = momentum();
        assertThat(t.tracks(p.selectionHash())).isTrue();
        assertThat(t.tracks(SelectionFixtures.intradayHot(Path.of("..")).selectionHash())).isFalse();
        List<MarketEvent> events = dayOne();
        for (int i = 0; i < events.size(); i++) {
            MarketEvent e = events.get(i);
            if (e.sequence() == 20) {
                // 순위 시각 직전까지는 기다리는 중(신규 진입 0건)
                SelectionDay waiting = t.day(p.selectionHash(), D1);
                assertThat(waiting.awaiting()).isTrue();
                assertThat(waiting.reason()).isEqualTo(SelectionInvalidReason.NOT_YET);
                assertThat(waiting.allowsEntry(Symbol.krx("005930"), 19)).isFalse();
                assertThat(t.status(D1)).singleElement().satisfies(s -> assertThat(s.ranked()).isNull());
            }
            t.onEvent(e);
        }
        assertThat(t.ranked()).singleElement().satisfies(r -> {
            assertThat(r.activeFrom()).isEqualTo(20);
            assertThat(r.result().selected()).extracting(SelectionResult.Selected::code)
                    .containsExactly("035420", "005930", "000660");
            assertThat(r.candidates().candidates()).containsExactly("005930", "000660");
            assertThat(r.candidates().excludedCodes(SelectionCandidates.Exclusion.BLACKLISTED)).containsExactly("035420");
            assertThat(evaluation(r, "005930").values()).containsEntry("score", "2020")
                    .containsEntry("gate.01", "0.02");
            // 늦게 온 09:29 200원 체결은 빠져 수익률 0.05 그대로
            assertThat(evaluation(r, "000660").values()).containsEntry("gate.01", "0.05");
            assertThat(evaluation(r, "051910").reasons()).containsExactly("SESSION_GAP");
            assertThat(evaluation(r, "207940").reasons()).containsExactly("GATE_01");
            assertThat(r.result().universe()).isEqualTo(5);
            assertThat(r.contentHash()).isEqualTo(r.result().contentHash());
            assertThat(IntradayTracker.fileName(r)).isEqualTo("2026-10-14/" + p.selectionHash() + ".intraday.json");
        });
        SelectionDay day = t.day(p.selectionHash(), D1);
        assertThat(day.awaiting()).isFalse();
        assertThat(day.reason()).isNull();
        assertThat(day.activeFrom()).isEqualTo(20);
        assertThat(day.allowsEntry(Symbol.krx("005930"), 19)).isFalse();
        assertThat(day.allowsEntry(Symbol.krx("005930"), 20)).isTrue();
        assertThat(day.allowsEntry(Symbol.krx("035420"), 30)).isFalse(); // blacklist
        assertThat(day.allowsEntry(Symbol.krx("207940"), 30)).isFalse(); // 탈락
        assertThat(t.status(D1)).singleElement().satisfies(s -> {
            assertThat(s.ranked()).isNotNull();
            assertThat(s.at()).hasToString("09:30");
        });
    }

    @Test
    void sameEventsGiveSameResultAndNextDayStartsFresh() {
        IntradayTracker a = tracker();
        IntradayTracker b = tracker();
        dayOne().forEach(a::onEvent);
        dayOne().forEach(b::onEvent);
        assertThat(a.ranked().get(0).contentHash()).isEqualTo(b.ranked().get(0).contentHash());

        // 다음 날: 전날 요약을 이어 쓰지 않고, at에 이르지 않으면 기다리는 중으로 끝난다
        a.onEvent(trade("005930", 100, at(D2, 9, 0, 0), 100, 1));
        a.onEvent(trade("005930", 101, at(D2, 9, 5, 0), 110, 1));
        String hash = momentum().selectionHash();
        assertThat(a.day(hash, D2).awaiting()).isTrue();
        assertThat(a.day(hash, D2).allowsEntry(Symbol.krx("005930"), Long.MAX_VALUE)).isFalse();
        assertThat(a.day(hash, D1).awaiting()).isFalse();
        a.onEvent(trade("000660", 102, at(D2, 10, 0, 0), 100, 1));
        assertThat(a.ranked()).hasSize(2);
        assertThat(a.ranked().get(1).result().selected()).extracting(SelectionResult.Selected::code)
                .containsExactly("005930");
        assertThat(a.day(hash, D2).activeFrom()).isEqualTo(102);
    }

    @Test
    void noTradesBeforeAtSelectsNothingAndUnknownSelectionIsRejected() {
        IntradayTracker t = tracker();
        t.onEvent(trade("005930", 1, at(D1, 9, 45, 0), 100, 1));
        assertThat(t.ranked()).singleElement().satisfies(r -> {
            assertThat(r.result().selected()).isEmpty();
            assertThat(r.candidates().candidates()).isEmpty();
        });
        assertThat(t.day(momentum().selectionHash(), D1).allowsEntry(Symbol.krx("005930"), 10)).isFalse();
        assertThatThrownBy(() -> t.day("unknown", D1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSelectionOfAnotherMarket() {
        assertThatThrownBy(() -> new IntradayTracker(List.of(momentum()),
                new IntradayUniverse(BOARDS, Set.of(), BundledMarketRules.of(Market.US))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("시장");
    }

    @Test
    void dayFirstObservedAfterOpenGraceMarksEveryBoardSymbolAsGap() {
        // strategy-researcher PR #200 M2: 그날 첫 이벤트가 개장 + 60초 뒤면 장 초반 체결을 못 받은 날이라 전 종목 SESSION_GAP
        IntradayTracker late = tracker();
        late.onEvent(trade("005930", 1, at(D1, 9, 5, 0), 100, 10));
        late.onEvent(trade("005930", 2, at(D1, 9, 10, 0), 103, 10));
        late.onEvent(trade("005930", 3, at(D1, 9, 30, 0), 103, 1));
        assertThat(late.ranked()).singleElement().satisfies(r -> {
            assertThat(r.result().selected()).isEmpty();
            assertThat(evaluation(r, "005930").reasons()).containsExactly("SESSION_GAP");
        });
        // 개장 + 60초 안(09:00:59)에 처음 받았으면 정상으로 계산한다
        IntradayTracker onTime = tracker();
        onTime.onEvent(trade("005930", 1, at(D1, 9, 0, 59), 100, 10));
        onTime.onEvent(trade("005930", 2, at(D1, 9, 10, 0), 103, 10));
        onTime.onEvent(trade("005930", 3, at(D1, 9, 30, 0), 103, 1));
        assertThat(onTime.ranked().get(0).result().selected()).extracting(SelectionResult.Selected::code)
                .containsExactly("005930");
    }

    @Test
    void gapOverlappingWindowMarksSymbolEvenIfItEndsAfterClose() {
        // strategy-researcher PR #200 R2: [개장, at)과 겹치면 끝이 장 마감 뒤여도 표시, 개장 전에 끝난 누락과 at 뒤에 시작한 누락은 표시 안 함
        IntradayTracker t = tracker();
        t.onEvent(trade("005930", 1, at(D1, 8, 40, 0), 100, 1));
        t.onEvent(new DataGap(Symbol.krx("000660"), 2, at(D1, 8, 50, 0), at(D1, 8, 50, 0), at(D1, 8, 30, 0),
                at(D1, 8, 50, 0), DataGap.GapReason.DISCONNECTED)); // 개장 전에 끝남
        t.onEvent(new DataGap(Symbol.krx("051910"), 3, at(D1, 9, 5, 0), at(D1, 9, 5, 0), at(D1, 9, 4, 0),
                at(D1, 16, 0, 0), DataGap.GapReason.DISCONNECTED)); // 장 마감 뒤에 끝남
        for (String code : List.of("005930", "000660", "051910")) {
            t.onEvent(trade(code, 10, at(D1, 9, 0, 30), 100, 10));
            t.onEvent(trade(code, 11, at(D1, 9, 20, 0), 103, 10));
        }
        t.onEvent(trade("005930", 20, at(D1, 9, 30, 0), 103, 1));
        assertThat(t.ranked()).singleElement().satisfies(r -> {
            assertThat(evaluation(r, "051910").reasons()).containsExactly("SESSION_GAP");
            assertThat(r.result().selected()).extracting(SelectionResult.Selected::code)
                    .containsExactlyInAnyOrder("005930", "000660");
        });
    }

    @Test
    void eventOfEarlierTradingDayDoesNotReopenRankedDay() {
        // architect PR #200 F2: 다음 날 이벤트 뒤에 전날 이벤트가 와도 전날 순위·적용 시작 순번은 그대로다
        IntradayTracker t = tracker();
        dayOne().forEach(t::onEvent);
        IntradayTracker.Ranked before = t.ranked().get(0);
        t.onEvent(trade("005930", 100, at(D2, 9, 0, 0), 100, 1));
        t.onEvent(trade("005930", 101, at(D1, 9, 10, 0), 150, 1000)); // 거래일이 거꾸로 감
        t.onEvent(trade("005930", 102, at(D1, 9, 40, 0), 150, 1));
        assertThat(t.ranked().get(0)).isEqualTo(before);
        assertThat(t.day(momentum().selectionHash(), D1).activeFrom()).isEqualTo(20);
        assertThat(t.day(momentum().selectionHash(), D2).awaiting()).isTrue();
    }

    private static SelectionResult.Evaluation evaluation(IntradayTracker.Ranked r, String code) {
        return r.result().evaluations().stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
    }
}
