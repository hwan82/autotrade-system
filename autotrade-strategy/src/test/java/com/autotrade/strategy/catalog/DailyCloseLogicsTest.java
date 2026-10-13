package com.autotrade.strategy.catalog;

import static com.autotrade.strategy.catalog.Tape.A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradeSignal;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 일봉 진입 로직 세 가지(breakout-days, short-reversal, near-high)의 손 계산 테스트. 종가 목록은 거래일마다 체결 하나로
 * 만들고(다음 거래일 첫 체결에서 전일 종가로 굳는다), 마지막 거래일 가격은 일부러 엉뚱하게 둬 오늘 가격이 판단에 쓰이지
 * 않음을 함께 확인한다.
 */
class DailyCloseLogicsTest {

    private static final CatalogStrategyFactory FACTORY = new CatalogStrategyFactory(Tape.KRX);
    private static final LocalDate START = LocalDate.of(2026, 3, 2);
    /** 판단에 쓰이면 안 되는 오늘 가격. */
    private static final long TODAY = 20_000;

    // ---- breakout-days (N=3) ----

    @Test
    void 채널_돌파는_전일_종가가_그_전_N일_최고_종가를_넘을_때만_진입한다() {
        Map<String, String> p = breakout(Map.of("signal.lookback-days", "3"));
        assertThat(enters(p, 10000, 12000, 11000, 13000)).as("13,000 > max(10,000 12,000 11,000)").isTrue();
        assertThat(enters(p, 10000, 12000, 11000, 12000)).as("같음은 돌파가 아니다").isFalse();
        assertThat(enters(p, 10000, 12000, 11000, 11999)).isFalse();
        assertThat(enters(p, 99000, 10000, 12000, 11000, 12001)).as("N일 밖의 옛 고가(99,000)는 쓰지 않는다").isTrue();
        assertThat(enters(p, 12000, 11000, 13000)).as("종가 N개뿐(N+1개 필요): 예열 부족").isFalse();
        assertThat(enters(p)).as("종가 없음").isFalse();
    }

    @Test
    void 채널_돌파는_종목마다_하루_한_번만_신호하고_로직_청산은_없다() {
        Tape tape = tape(breakout(Map.of("signal.lookback-days", "3")), 10000, 12000, 11000, 13000);
        LocalDate d = START.plusDays(4);
        List<TradeSignal> first = tape.day(d).trade("09:10:00", TODAY, 100);
        assertThat(first).hasSize(1);
        assertThat(tape.trade("09:11:00", TODAY, 100)).as("같은 날 두 번째 체결").isEmpty();
        tape.hold(A, 10, 13000);
        tape.day(d.plusDays(1));
        // 종가 목록이 10,000 12,000 11,000 13,000 20,000: 이미 보유 중이고 로직 청산 조건은 없다
        assertThat(tape.trade("09:10:00", 13000, 100)).isEmpty();
    }

    @Test
    void 채널_돌파는_스윙과_중기를_받고_범위_밖_값을_거절한다() {
        assertThat(FACTORY.create(profile(TradingStyle.SWING, breakout(Map.of())), 1L)).isNotNull();
        Map<String, String> pos = new TreeMap<>(breakout(Map.of("exit.max-hold-days", "60")));
        assertThat(FACTORY.create(profile(TradingStyle.POSITION, pos), 1L)).isNotNull();
        assertThatThrownBy(() -> FACTORY.create(profile(TradingStyle.INTRADAY, breakout(Map.of())), 1L))
                .hasMessageContaining("SWING 또는 POSITION");
        assertThatThrownBy(() -> create(breakout(Map.of("signal.lookback-days", "1"))))
                .hasMessageContaining("signal.lookback-days");
        assertThatThrownBy(() -> create(breakout(Map.of("signal.lookback-days", "251"))))
                .hasMessageContaining("signal.lookback-days");
        Map<String, String> missing = new TreeMap<>(breakout(Map.of()));
        missing.remove("signal.lookback-days");
        assertThatThrownBy(() -> create(missing)).hasMessageContaining("signal.lookback-days");
        assertThatThrownBy(() -> create(breakout(Map.of("signal.fast-days", "3"))))
                .as("안 쓰는 키 거절").hasMessageContaining("signal.fast-days");
    }

    // ---- short-reversal (M=6, K=2, X=3) ----

    @Test
    void 단기_반전은_추세_위에서_K일_연속_하락하면_진입한다() {
        Map<String, String> p = reversal(Map.of());
        assertThat(enters(p, 5000, 5000, 5000, 5000, 20000, 18000, 16000))
                .as("평균(6일) 11,500 < 16,000, 20→18→16 연속 하락").isTrue();
        assertThat(enters(p, 9000, 9000, 9000, 12000, 11000, 10000)).as("종가 = 6일 평균(10,000)은 추세 위가 아니다").isFalse();
        assertThat(enters(p, 5000, 5000, 5000, 5000, 20000, 18000, 18000)).as("같은 종가는 하락이 아니다").isFalse();
        assertThat(enters(p, 5000, 5000, 5000, 5000, 5000, 20000, 18000)).as("하락 하루뿐").isFalse();
        assertThat(enters(p, 5000, 5000, 20000, 18000, 16000)).as("종가 5개 < M=6: 예열 부족").isFalse();
        assertThat(enters(p, 20000, 5000, 5000, 5000, 5000, 20000, 18000, 16000)).as("M일 밖은 평균에 쓰지 않는다").isTrue();
    }

    @Test
    void 단기_반전은_전일_종가가_X일_평균을_넘으면_로직_청산하고_같으면_유지한다() {
        Map<String, String> p = reversal(Map.of());
        assertThat(exits(p, 10000, 10000, 10000, 10000, 10000, 10000, 11000))
                .as("11,000 > 평균(10,000 10,000 11,000)=10,333").isTrue();
        assertThat(exits(p, 10000, 10000, 10000, 10000, 8000, 12000, 10000)).as("10,000 = 평균 10,000: 유지").isFalse();
        assertThat(exits(p, 10000, 10000, 10000, 10000, 10000, 11000, 9000)).isFalse();
        assertThat(exits(p, 11000, 10000)).as("종가가 X=3개 모자라면 청산 판단 없음").isFalse();
    }

    @Test
    void 단기_반전은_진입과_청산_조건이_같이_참이면_진입하지_않는다() {
        Map<String, String> p = reversal(Map.of("signal.down-days", "1"));
        // 5→20→18: 하락 하루, 추세 위(18,000 > 9,667), 그러나 18,000 > 3일 평균(14,333)이라 사자마자 판다
        assertThat(enters(p, 5000, 5000, 5000, 5000, 5000, 20000, 18000)).isFalse();
    }

    @Test
    void 단기_반전은_스윙만_받고_범위_밖_값을_거절한다() {
        assertThat(create(reversal(Map.of()))).isNotNull();
        assertThatThrownBy(() -> FACTORY.create(profile(TradingStyle.POSITION, reversal(Map.of("exit.max-hold-days", "60"))), 1L))
                .hasMessageContaining("SWING");
        assertThatThrownBy(() -> create(reversal(Map.of("signal.down-days", "0")))).hasMessageContaining("signal.down-days");
        assertThatThrownBy(() -> create(reversal(Map.of("signal.down-days", "11")))).hasMessageContaining("signal.down-days");
        assertThatThrownBy(() -> create(reversal(Map.of("signal.exit-days", "1")))).hasMessageContaining("signal.exit-days");
        assertThatThrownBy(() -> create(reversal(Map.of("signal.trend-days", "4")))).hasMessageContaining("signal.trend-days");
        assertThatThrownBy(() -> create(reversal(Map.of("signal.trend-days", "251")))).hasMessageContaining("signal.trend-days");
    }

    // ---- near-high (L=5, 진입 0.9, 청산 0.7) ----

    @Test
    void 고가_근접은_전일_종가가_최고_종가_곱_비율_이상이면_진입한다() {
        Map<String, String> p = nearHigh(Map.of());
        assertThat(enters(p, 10000, 9500, 9500, 9200, 9100)).as("9,100 ≥ 9,000").isTrue();
        assertThat(enters(p, 10000, 9500, 9500, 9200, 9000)).as("같으면 진입").isTrue();
        assertThat(enters(p, 10000, 9500, 9500, 9200, 8999)).isFalse();
        assertThat(enters(p, 30000, 10000, 10000, 10000, 10000, 10000)).as("L일 밖의 옛 고가(30,000)는 쓰지 않는다").isTrue();
        assertThat(enters(p, 10000, 9500, 9500, 9900)).as("종가 4개 < L=5: 예열 부족").isFalse();
    }

    @Test
    void 고가_근접은_최고_종가_곱_청산_비율_미만이면_로직_청산하고_같으면_유지한다() {
        Map<String, String> p = nearHigh(Map.of());
        assertThat(exits(p, 10000, 9000, 8000, 7500, 6999)).as("6,999 < 7,000").isTrue();
        assertThat(exits(p, 10000, 9000, 8000, 7500, 7000)).as("같으면 유지").isFalse();
        assertThat(exits(p, 10000, 9000, 8000, 7500)).as("예열 부족").isFalse();
    }

    @Test
    void 고가_근접은_중기만_받고_비율_범위와_순서를_거절한다() {
        assertThat(create(nearHigh(Map.of()))).isNotNull();
        assertThat(create(nearHigh(Map.of("signal.entry-ratio", "1.0")))).as("1.0 = 신고가만").isNotNull();
        assertThatThrownBy(() -> FACTORY.create(profile(TradingStyle.SWING, nearHigh(Map.of("exit.max-hold-days", "10"))), 1L))
                .hasMessageContaining("POSITION");
        assertThatThrownBy(() -> create(nearHigh(Map.of("signal.entry-ratio", "1.01"))))
                .hasMessageContaining("signal.entry-ratio");
        assertThatThrownBy(() -> create(nearHigh(Map.of("signal.entry-ratio", "0.49"))))
                .hasMessageContaining("signal.entry-ratio");
        assertThatThrownBy(() -> create(nearHigh(Map.of("signal.exit-ratio", "0.9"))))
                .as("청산 비율 ≥ 진입 비율").hasMessageContaining("signal.exit-ratio");
        assertThatThrownBy(() -> create(nearHigh(Map.of("signal.lookback-days", "1"))))
                .hasMessageContaining("signal.lookback-days");
        assertThatThrownBy(() -> create(nearHigh(Map.of("signal.lookback-days", "501"))))
                .hasMessageContaining("signal.lookback-days");
        assertThat(create(nearHigh(Map.of("signal.lookback-days", "300")))).as("±20% 변형 300 허용").isNotNull();
    }

    // ---- 공통 ----

    @Test
    void 같은_시세와_프로필이면_세_로직_모두_같은_신호를_낸다() {
        long[] closes = {5000, 5000, 5000, 5000, 20000, 18000, 16000, 15000, 14000, 16000, 17000, 18000, 19000, 21000};
        for (Map<String, String> p : List.of(breakout(Map.of("signal.lookback-days", "3")), reversal(Map.of()),
                nearHigh(Map.of()))) {
            TradingStyle style = p.get("logic").equals("near-high") ? TradingStyle.POSITION : TradingStyle.SWING;
            assertThat(replay(profile(style, p), closes)).isEqualTo(replay(profile(style, p), closes));
        }
        assertThat(replay(profile(TradingStyle.SWING, breakout(Map.of("signal.lookback-days", "3"))), closes))
                .as("신호가 한 번도 없으면 결정론 검사가 의미 없다").isNotEmpty();
    }

    // ---- 도우미 ----

    private static boolean enters(Map<String, String> params, long... closes) {
        return !tape(params, closes).day(START.plusDays(closes.length)).trade("09:10:00", TODAY, 100).isEmpty();
    }

    /** 보유 중인 종목에 대해 오늘 첫 체결에서 로직 청산 신호가 나는가. */
    private static boolean exits(Map<String, String> params, long... closes) {
        Tape tape = tape(params, closes);
        tape.hold(A, 10, TODAY);
        return tape.day(START.plusDays(closes.length)).trade("09:10:00", TODAY, 100).stream()
                .anyMatch(s -> s.intent() == OrderIntent.EXIT);
    }

    private static Tape tape(Map<String, String> params, long... closes) {
        TradingStyle style = params.get("logic").equals("near-high") ? TradingStyle.POSITION : TradingStyle.SWING;
        Tape tape = new Tape(FACTORY.create(profile(style, params), 42L));
        for (int i = 0; i < closes.length; i++) {
            tape.day(START.plusDays(i)).trade("09:10:00", closes[i], 100);
        }
        return tape;
    }

    private static List<String> replay(StrategyProfile profile, long[] closes) {
        Strategy strategy = FACTORY.create(profile, 42L);
        Tape tape = new Tape(strategy);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            int day = i;
            for (String t : List.of("09:10:00", "09:20:00")) {
                tape.day(START.plusDays(i)).trade(t, closes[i], 100).forEach(s -> out.add(day + "@" + t + ":" + s.quantity()));
            }
        }
        return out;
    }

    private static Strategy create(Map<String, String> params) {
        TradingStyle style = params.get("logic").equals("near-high") ? TradingStyle.POSITION : TradingStyle.SWING;
        return FACTORY.create(profile(style, params), 1L);
    }

    private static StrategyProfile profile(TradingStyle style, Map<String, String> params) {
        return new StrategyProfile("daily-" + params.get("logic"), 1, Market.KRX, style, new TreeMap<>(params));
    }

    private static Map<String, String> breakout(Map<String, String> extra) {
        Map<String, String> p = base();
        p.putAll(Map.of("logic", "breakout-days", "signal.lookback-days", "20", "exit.max-hold-days", "10"));
        p.putAll(extra);
        return p;
    }

    private static Map<String, String> reversal(Map<String, String> extra) {
        Map<String, String> p = base();
        p.putAll(Map.of("logic", "short-reversal", "signal.trend-days", "6", "signal.down-days", "2",
                "signal.exit-days", "3", "exit.max-hold-days", "5"));
        p.putAll(extra);
        return p;
    }

    private static Map<String, String> nearHigh(Map<String, String> extra) {
        Map<String, String> p = base();
        p.putAll(Map.of("logic", "near-high", "signal.lookback-days", "5", "signal.entry-ratio", "0.9",
                "signal.exit-ratio", "0.7", "exit.max-hold-days", "60"));
        p.putAll(extra);
        return p;
    }

    private static Map<String, String> base() {
        Map<String, String> p = new TreeMap<>();
        p.put("hypothesis", "테스트 가설");
        p.put("entry.window.start", "09:00");
        p.put("entry.window.end", "09:30");
        p.put("entry.expiry-seconds", "600");
        p.put("sizing.risk-per-trade-pct", "1.0");
        p.put("sizing.max-position-pct", "24");
        p.put("sizing.max-positions", "4");
        p.put("sizing.cash-buffer-pct", "2");
        p.put("limits.max-entries-per-day", "4");
        p.put("limits.reentry-cooldown-minutes", "0");
        p.put("limits.daily-loss-limit-pct", "5");
        p.put("limits.max-consecutive-stops", "4");
        p.put("exit.stop-loss-pct", "50");
        p.put("exit.eod", "CARRY");
        return p;
    }
}
