package com.autotrade.strategy.catalog;

import static com.autotrade.strategy.catalog.Tape.A;
import static com.autotrade.strategy.catalog.Tape.B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
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

/** 공통 매매 골격(진입 만료, 비중, 한도, 청산)과 진입 로직 손 계산 테스트(STR-07·09·12). */
class CatalogStrategyTest {

    private static final CatalogStrategyFactory FACTORY = new CatalogStrategyFactory(Tape.KRX);

    @Test
    void ORB는_범위가_닫힌_뒤_첫_돌파에_사고_수량은_세_상한_중_가장_작다() {
        Tape tape = new Tape(create(orb(Map.of())));
        tape.book("09:00:00", 10000, 10010, 500, 500);
        assertThat(tape.trade("09:00:00", 10000, 100)).isEmpty();
        assertThat(tape.trade("09:02:00", 10050, 100)).isEmpty();
        assertThat(tape.trade("09:04:00", 10060, 100)).as("범위 안(5분)의 고가는 범위를 넓힐 뿐이다").isEmpty();
        assertThat(tape.trade("09:06:00", 10050, 100)).as("범위를 닫는 체결이 범위 고가 이하").isEmpty();
        tape.book("09:07:00", 10070, 10080, 500, 500);

        List<TradeSignal> s = tape.trade("09:07:00", 10070, 100);

        // 평가금액 10,000,000. 위험 50,000 / (10,080 × 1.5%) = 330, 비중 2,000,000 / 10,080 = 198,
        // 현금 (10,000,000 − 500,000) / (10,080 × 1.003) = 939 → 198
        assertThat(s).containsExactly(new TradeSignal(A, Side.BUY, OrderIntent.ENTRY, OrderType.MARKET, null, 198));
        assertThat(tape.trade("09:08:00", 10090, 100)).as("미체결 주문이 없어도 하루 한 번만 신호").isEmpty();
    }

    @Test
    void 손절은_STOP_LOSS_의도로_전량_시장가_매도한다() {
        Tape tape = new Tape(create(orb(Map.of())));
        tape.trade("09:00:00", 10000, 100);
        tape.hold(A, 198, 10071);
        assertThat(tape.trade("09:30:00", 9920, 100)).as("10,071 × 0.985 = 9,919.9보다 위").isEmpty();
        assertThat(tape.trade("09:31:00", 9910, 100))
                .containsExactly(TradeSignal.marketSell(A, OrderIntent.STOP_LOSS, 198));
        assertThat(tape.trade("09:31:01", 9900, 100)).as("5초 안에는 청산 주문을 다시 내지 않는다").isEmpty();
    }

    @Test
    void 장중_프로필은_장_마감_청산_시각에_전량_판다() {
        Tape tape = new Tape(create(orb(Map.of())));
        tape.trade("09:00:00", 10000, 100);
        tape.hold(A, 50, 10000);
        assertThat(tape.trade("15:09:59", 10010, 100)).isEmpty();
        assertThat(tape.trade("15:10:00", 10010, 100)).containsExactly(TradeSignal.marketSell(A, OrderIntent.EXIT, 50));
    }

    @Test
    void 만료된_신호로는_주문하지_않는다() {
        Map<String, String> confirm = Map.of("entry.confirm.volume-ratio", "3", "entry.confirm.window-minutes", "1");
        assertThat(breakoutThenSurgeAt(confirm, "09:12:00")).as("돌파 5분 뒤 확인 신호 → 주문").hasSize(1);
        assertThat(breakoutThenSurgeAt(confirm, "09:18:00")).as("돌파 11분 뒤(만료 600초 초과) → 주문 없음").isEmpty();
    }

    /** 09:07에 돌파(거래량 평이), surgeAt에 거래량 100배. 그날 나온 매수 신호 목록. */
    private List<TradeSignal> breakoutThenSurgeAt(Map<String, String> extra, String surgeAt) {
        Tape tape = new Tape(create(orb(extra)));
        List<TradeSignal> buys = new ArrayList<>();
        tape.book("09:00:00", 10000, 10010, 500, 500);
        for (int m = 0; m <= 20; m++) {
            String t = String.format("09:%02d:00", m);
            long price = m < 7 ? 10000 + (m == 2 ? 50 : 0) : 10100;
            long qty = t.equals(surgeAt) ? 10_000 : 100;
            for (TradeSignal s : tape.trade(t, price, qty)) {
                if (s.side() == Side.BUY) {
                    buys.add(s);
                }
            }
        }
        return buys;
    }

    @Test
    void 당일_손실_한도에_닿으면_그날은_신규_진입하지_않는다() {
        Tape tape = new Tape(create(random(Map.of())));
        tape.trade("09:00:00", 10000, 100); // 당일 시작 평가금액 10,000,000
        tape.cash(9_790_000); // −2.1%
        assertThat(tape.trade("09:10:00", 10000, 100)).isEmpty();
        tape.cash(10_000_000);
        assertThat(tape.trade("09:11:00", 10000, 100)).as("한 번 멈추면 그날은 계속 멈춘다").isEmpty();
        tape.day(LocalDate.of(2026, 3, 3));
        assertThat(tape.trade("09:10:00", 10000, 100)).as("다음 거래일에는 다시 진입").hasSize(1);
    }

    @Test
    void 연속_손절_횟수에_닿으면_그날은_신규_진입하지_않는다() {
        assertThat(entriesAfterOneStop("1")).isEmpty();
        assertThat(entriesAfterOneStop("2")).hasSize(1);
    }

    private List<TradeSignal> entriesAfterOneStop(String maxStops) {
        Tape tape = new Tape(create(random(Map.of("limits.max-consecutive-stops", maxStops))));
        assertThat(tape.trade("09:10:00", 10000, 100)).hasSize(1);
        tape.hold(A, 100, 10000);
        assertThat(tape.trade("09:11:00", 9800, 100)).extracting(TradeSignal::intent).containsExactly(OrderIntent.STOP_LOSS);
        tape.hold(A, 0, 0);
        return tape.trade("09:12:00", 9800, 100);
    }

    @Test
    void 하루_진입_수_상한과_재진입_금지_시간을_지킨다() {
        Tape tape = new Tape(create(random(Map.of("limits.max-entries-per-day", "1"))));
        assertThat(tape.trade("09:10:00", 10000, 100)).hasSize(1);
        tape.hold(A, 100, 10000);
        assertThat(tape.trade("09:11:00", 10400, 100)).extracting(TradeSignal::intent).containsExactly(OrderIntent.EXIT);
        tape.hold(A, 0, 0);
        assertThat(tape.trade("10:30:00", 10000, 100)).as("하루 1건 상한").isEmpty();

        Tape cooldown = new Tape(create(random(Map.of("limits.reentry-cooldown-minutes", "30"))));
        cooldown.trade("09:10:00", 10000, 100);
        cooldown.hold(A, 100, 10000);
        cooldown.trade("09:11:00", 10400, 100);
        cooldown.hold(A, 0, 0);
        assertThat(cooldown.trade("09:12:00", 10000, 100)).as("청산(09:12 관측) 직후").isEmpty();
        assertThat(cooldown.trade("09:41:59", 10000, 100)).as("30분 전").isEmpty();
        assertThat(cooldown.trade("09:42:00", 10000, 100)).as("30분 경과").hasSize(1);
    }

    @Test
    void 실행기가_받지_않는_신규_진입은_신호를_내지_않고_하루_진입_수를_쓰지_않는다() {
        // F7(D-130): 선정 밖 종목 B의 신호가 먼저 와도 하루 1건 한도는 후보 A에 남는다
        Tape tape = new Tape(create(random(Map.of("limits.max-entries-per-day", "1")))).refuse(B);
        assertThat(tape.trade(B, "09:10:00", 10000, 100)).isEmpty();
        assertThat(tape.trade(B, "09:10:30", 10000, 100)).isEmpty();
        assertThat(tape.admitAsks()).as("신호를 내려던 순간마다 한 번").isEqualTo(2);
        assertThat(tape.trade("09:11:00", 10000, 100)).as("후보 A는 그대로 들어간다").hasSize(1);
        assertThat(tape.admitAsks()).isEqualTo(3);
        assertThat(tape.trade("09:12:00", 10000, 100)).as("A가 한도 1건을 썼다").isEmpty();
        assertThat(tape.admitAsks()).as("신호가 켜져도 묻는 것은 한도 확인 전, 한 번").isEqualTo(4);
    }

    @Test
    void 실행기가_받지_않는_신규_진입은_대기_진입_금액도_잡지_않는다() {
        // F7(D-130): B 신호가 대기 진입 금액을 잡으면 A 수량이 현금 상한으로 줄었다
        Map<String, String> p = Map.of("sizing.max-position-pct", "100", "sizing.risk-per-trade-pct", "5");
        Tape alone = new Tape(create(random(p))).cash(1_000_000);
        long expected = alone.trade("09:11:00", 10000, 100).get(0).quantity();

        Tape tape = new Tape(create(random(p))).cash(1_000_000).refuse(B).openOrder(B, false);
        assertThat(tape.trade(B, "09:10:00", 10000, 100)).isEmpty();
        assertThat(tape.trade("09:11:00", 10000, 100)).extracting(TradeSignal::quantity).containsExactly(expected);
        assertThat(expected).as("현금 상한이 걸리는 수량").isEqualTo(94);
    }

    @Test
    void 실행기가_받지_않는_신규_진입은_진입_깔때기에_세지_않는다() {
        // PR #205 QA Q2: 막힌 신호는 깔때기의 신호·진입 어디에도 들어가지 않는다(ADR-013 5장)
        ManagedStrategy strategy = (ManagedStrategy) create(random(Map.of()));
        Tape tape = new Tape(strategy).refuse(B);
        assertThat(tape.trade(B, "09:10:00", 10000, 100)).isEmpty();
        assertThat(tape.trade("09:11:00", 10000, 100)).hasSize(1);
        LocalDate day = LocalDate.of(2026, 3, 2);
        assertThat(strategy.entryFunnel().totals(day, day))
                .extracting(EntryFunnel.Totals::signals, EntryFunnel.Totals::entries).containsExactly(1L, 1L);
    }

    @Test
    void 실행기가_받지_않는_확인_신호에서도_일_손실_한도를_확인한다() {
        // D-135(PR #205 risk-ops 1·QA R2): 손실 중에는 후보 밖 B 신호만 왔고, 회복 뒤 후보 A 신호가 와도 그날은 멈춘 채다
        Tape tape = new Tape(create(random(Map.of()))).refuse(B);
        tape.trade("09:00:00", 10000, 100); // 당일 시작 평가금액 10,000,000
        tape.cash(9_790_000); // −2.1%
        assertThat(tape.trade(B, "09:10:00", 10000, 100)).isEmpty();
        tape.cash(10_000_000);
        assertThat(tape.trade("09:11:00", 10000, 100)).as("후보 밖 신호 때 닿은 손실로 그날 멈춤").isEmpty();
        tape.day(LocalDate.of(2026, 3, 3));
        assertThat(tape.trade("09:10:00", 10000, 100)).as("다음 거래일에는 다시 진입").hasSize(1);
    }

    @Test
    void 보유_수_상한에_막힌_동안에도_받지_않는_확인_신호로_일_손실_한도를_확인한다() {
        // PR #218 QA Q1(변이 P2): D-135는 받지 않은 신호에서 진입 수·재진입·보유 수 확인 없이 일 손실만 본다.
        // 보유 수 상한(1)에 막힌 동안 손실 중 후보 밖 B 신호가 오면 그날 멈추고, A를 판 뒤 회복해도 진입하지 않는다
        Tape tape = new Tape(create(random(Map.of("sizing.max-positions", "1")))).refuse(B);
        tape.trade("09:00:00", 10000, 100); // 당일 시작 평가금액 10,000,000
        tape.hold(A, 100, 10000).cash(8_790_000); // 평가금액 9,790,000(−2.1%), 보유 수 1 = 상한
        assertThat(tape.trade(B, "09:10:00", 10000, 100)).isEmpty();
        tape.hold(A, 0, 0).cash(10_000_000);
        assertThat(tape.trade("09:11:00", 10000, 100)).as("보유 수 상한 때 본 손실로 그날 멈춤").isEmpty();
    }

    @Test
    void 재진입_금지_시간_안에도_받지_않는_확인_신호로_일_손실_한도를_확인한다() {
        // PR #220 QA Q1(변이 P2b): D-135는 받지 않은 신호에서 재진입 금지 확인 없이 일 손실만 본다.
        // 후보 밖 B를 09:10에 막 판 직후(재진입 금지 30분 안) 손실 중 B 신호가 오면 그날 멈추고, 회복 뒤 A도 진입하지 않는다
        Tape tape = new Tape(create(random(Map.of("limits.reentry-cooldown-minutes", "30")))).refuse(B);
        tape.hold(B, 100, 10000).cash(9_000_000);
        tape.trade(B, "09:00:00", 10000, 100); // 당일 시작 평가금액 10,000,000, B 보유 관측
        tape.hold(B, 0, 0).cash(9_790_000); // B 청산, 평가금액 9,790,000(−2.1%)
        assertThat(tape.trade(B, "09:10:00", 10000, 100)).as("B 청산 관측(재진입 금지 시작)과 같은 순간의 확인 신호").isEmpty();
        tape.cash(10_000_000);
        assertThat(tape.trade("09:11:00", 10000, 100)).as("재진입 금지 안에서 본 손실로 그날 멈춤").isEmpty();
    }

    @Test
    void 일_손실이_한도와_정확히_같으면_멈추고_조금_모자라면_진입한다() {
        // PR #218 QA Q2(변이 P9): 한도 경계 −2.0%는 멈춘다(손실이 한도 "이상"이면 중단, TradingRules)
        Tape exact = new Tape(create(random(Map.of())));
        exact.trade("09:00:00", 10000, 100);
        exact.cash(9_800_000); // 정확히 −2.0%
        assertThat(exact.trade("09:10:00", 10000, 100)).isEmpty();

        Tape under = new Tape(create(random(Map.of())));
        under.trade("09:00:00", 10000, 100);
        under.cash(9_800_010); // −1.9999%
        assertThat(under.trade("09:10:00", 10000, 100)).hasSize(1);
    }

    @Test
    void 손실이_없으면_받지_않는_확인_신호는_뒤_후보_진입을_막지_않는다() {
        Tape tape = new Tape(create(random(Map.of()))).refuse(B);
        tape.trade("09:00:00", 10000, 100);
        tape.cash(9_850_000); // −1.5%, 한도(2%) 안
        assertThat(tape.trade(B, "09:10:00", 10000, 100)).isEmpty();
        assertThat(tape.trade("09:11:00", 10000, 100)).hasSize(1);
    }

    @Test
    void 청산은_실행기에_진입_여부를_묻지_않는다() {
        Tape tape = new Tape(create(random(Map.of()))).refuse(A);
        tape.trade("09:00:00", 10000, 100);
        tape.hold(A, 100, 10000);
        assertThat(tape.trade("09:11:00", 9800, 100)).extracting(TradeSignal::intent).containsExactly(OrderIntent.STOP_LOSS);
        assertThat(tape.admitAsks()).isZero();
    }

    @Test
    void 무작위_대조군은_Run_시드가_같으면_같은_신호를_낸다() {
        StrategyProfile p = random(Map.of("signal.per-mille", "300", "limits.max-entries-per-day", "1000",
                "limits.reentry-cooldown-minutes", "0"));
        assertThat(signals(FACTORY.create(p, 7L))).isEqualTo(signals(FACTORY.create(p, 7L)));
        assertThat(signals(FACTORY.create(p, 7L))).isNotEqualTo(signals(FACTORY.create(p, 8L)));
    }

    private static List<String> signals(Strategy s) {
        Tape tape = new Tape(s);
        List<String> out = new ArrayList<>();
        for (int m = 10; m < 60; m++) {
            int minute = m;
            tape.trade(String.format("09:%02d:00", m), 10000, 100).forEach(sig -> out.add(minute + ":" + sig.quantity()));
        }
        return out;
    }

    @Test
    void 이동평균_추세는_전일까지의_종가로만_판단한다() {
        Map<String, String> p = new TreeMap<>(common());
        p.putAll(Map.of("logic", "ma-trend", "signal.fast-days", "2", "signal.slow-days", "3",
                "entry.window.start", "09:05", "entry.window.end", "10:00", "exit.eod", "CARRY",
                "exit.stop-loss-pct", "5"));
        p.remove("exit.eod-time");
        Tape tape = new Tape(create(profile("ma", TradingStyle.SWING, p)));
        long[] closes = {10000, 11000, 12000};
        for (int d = 0; d < 3; d++) {
            tape.day(LocalDate.of(2026, 3, 2 + d));
            assertThat(tape.trade("09:10:00", closes[d], 100)).as("종가 %d개", d).isEmpty();
        }
        tape.day(LocalDate.of(2026, 3, 5));
        assertThat(tape.trade("09:10:00", 9000, 100))
                .as("오늘 가격(9,000)이 아니라 전일까지 종가 3개로 판단: 단기 11,500 > 장기 11,000").hasSize(1);
        tape.hold(A, 10, 9000);
        tape.day(LocalDate.of(2026, 3, 6));
        // 종가 10,000 11,000 12,000 9,000: 단기 10,500 < 장기 10,666 → 로직 청산
        assertThat(tape.trade("09:10:00", 9000, 100)).containsExactly(TradeSignal.marketSell(A, OrderIntent.EXIT, 10));
    }

    @Test
    void 스윙_로직_청산이_거절돼도_같은_날_다시_청산한다() {
        Map<String, String> p = new TreeMap<>(common());
        p.putAll(Map.of("logic", "ma-trend", "signal.fast-days", "1", "signal.slow-days", "2",
                "entry.window.start", "09:05", "entry.window.end", "10:00", "exit.eod", "CARRY",
                "exit.stop-loss-pct", "50"));
        p.remove("exit.eod-time");
        Tape tape = new Tape(create(profile("ma", TradingStyle.SWING, p)));
        tape.day(LocalDate.of(2026, 3, 2)).trade("09:10:00", 12000, 100);
        tape.day(LocalDate.of(2026, 3, 3)).trade("09:10:00", 10000, 100);
        tape.hold(A, 10, 11000);
        tape.day(LocalDate.of(2026, 3, 4));
        // 종가 12,000 10,000: 단기 10,000 < 장기 11,000 → 청산
        assertThat(tape.trade("09:10:00", 10000, 100)).extracting(TradeSignal::intent).containsExactly(OrderIntent.EXIT);
        // 첫 청산 주문이 거절됐다(보유 그대로, 미체결 없음). 재전송 대기 5초 뒤 다시 청산
        assertThat(tape.trade("09:10:03", 10000, 100)).isEmpty();
        assertThat(tape.trade("09:10:06", 10000, 100)).extracting(TradeSignal::intent).containsExactly(OrderIntent.EXIT);
    }

    @Test
    void 거래량_비율은_앞선_구간이_창_길이보다_짧으면_판단하지_않는다() {
        SymbolState s = new SymbolState(A);
        s.roll(LocalDate.of(2026, 3, 2));
        java.time.LocalTime t0 = java.time.LocalTime.of(9, 0);
        for (int m = 0; m < 4; m++) {
            s.update(new com.autotrade.domain.market.TradeTick(A, m + 1, java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                    java.math.BigDecimal.valueOf(10000 + m * 10), m == 3 ? 600 : 100), t0.plusMinutes(m));
        }
        // 09:03 기준 창 2분: 앞선 구간 2분(09:00~09:01) = 창 길이 → 판단 가능. 최근 100+600, 앞선 평균 2분당 200 → 3.5
        assertThat(s.volumeRatio(t0.plusMinutes(3), 2)).isEqualByComparingTo("3.5");
        assertThat(s.volumeRatio(t0.plusMinutes(3), 3)).as("앞선 구간 1분 < 창 3분").isNull();
        assertThat(s.priceMinutesAgo(t0.plusMinutes(3), 2)).isEqualByComparingTo("10010");
        // VWAP = (10000×100 + 10010×100 + 10020×100 + 10030×600) / 900
        assertThat(s.vwap()).isEqualByComparingTo(new java.math.BigDecimal("9021000").divide(java.math.BigDecimal.valueOf(900), java.math.MathContext.DECIMAL64));
    }

    @Test
    void 프로필_검증은_오타_키와_스타일_혼합과_장_마감_정책_누락을_거절한다() {
        assertThatThrownBy(() -> create(orb(Map.of("exit.stop-los-pct", "1"))))
                .hasMessageContaining("exit.stop-los-pct");
        Map<String, String> carry = new TreeMap<>(orbParams(Map.of()));
        carry.put("exit.eod", "CARRY");
        assertThatThrownBy(() -> create(profile("orb", TradingStyle.INTRADAY, carry))).hasMessageContaining("FLATTEN");
        assertThatThrownBy(() -> create(profile("orb", TradingStyle.SWING, orbParams(Map.of()))))
                .hasMessageContaining("INTRADAY");
        Map<String, String> noHypothesis = new TreeMap<>(orbParams(Map.of()));
        noHypothesis.remove("hypothesis");
        assertThatThrownBy(() -> create(profile("orb", TradingStyle.INTRADAY, noHypothesis)))
                .hasMessageContaining("hypothesis");
        Map<String, String> noExpiry = new TreeMap<>(orbParams(Map.of()));
        noExpiry.remove("entry.expiry-seconds");
        assertThatThrownBy(() -> create(profile("orb", TradingStyle.INTRADAY, noExpiry)))
                .as("STR-07 만료 정의 필수").hasMessageContaining("entry.expiry-seconds");
        assertThatThrownBy(() -> new CatalogStrategyFactory(Tape.KRX).create(
                new StrategyProfile("us", 1, Market.US, TradingStyle.INTRADAY, new TreeMap<>(orbParams(Map.of()))), 1L))
                .hasMessageContaining("시장");
    }

    @Test
    void 중기_프로필은_CARRY와_20에서_120거래일_최대_보유일을_요구한다() {
        assertThat(FACTORY.create(position(Map.of()), 1L)).isNotNull();
        Map<String, String> flatten = new TreeMap<>(positionParams(Map.of("exit.eod", "FLATTEN")));
        flatten.put("exit.eod-time", "15:10");
        assertThatThrownBy(() -> create(profile("pos", TradingStyle.POSITION, flatten))).hasMessageContaining("CARRY");
        Map<String, String> noHold = new TreeMap<>(positionParams(Map.of()));
        noHold.remove("exit.max-hold-days");
        assertThatThrownBy(() -> create(profile("pos", TradingStyle.POSITION, noHold)))
                .hasMessageContaining("exit.max-hold-days");
        assertThatThrownBy(() -> create(position(Map.of("exit.max-hold-days", "19")))).hasMessageContaining("20");
        assertThatThrownBy(() -> create(position(Map.of("exit.max-hold-days", "121")))).hasMessageContaining("120");
        assertThat(create(position(Map.of("exit.max-hold-days", "20")))).isNotNull();
        assertThat(create(position(Map.of("exit.max-hold-days", "120")))).isNotNull();
    }

    @Test
    void 스윙_최대_보유일은_선택이고_있으면_2에서_20거래일이며_단타는_받지_않는다() {
        Map<String, String> swing = new TreeMap<>(positionParams(Map.of()));
        swing.remove("exit.max-hold-days");
        assertThat(create(profile("sw", TradingStyle.SWING, swing))).as("기존 v1 스윙 프로필 호환").isNotNull();
        swing.put("exit.max-hold-days", "1");
        assertThatThrownBy(() -> create(profile("sw", TradingStyle.SWING, swing))).hasMessageContaining("2");
        swing.put("exit.max-hold-days", "21");
        assertThatThrownBy(() -> create(profile("sw", TradingStyle.SWING, swing))).hasMessageContaining("20");
        swing.put("exit.max-hold-days", "20");
        assertThat(create(profile("sw", TradingStyle.SWING, swing))).isNotNull();
        assertThatThrownBy(() -> create(orb(Map.of("exit.max-hold-days", "5")))).hasMessageContaining("exit.max-hold-days");
    }

    @Test
    void 최대_보유일에_이른_거래일_첫_체결에서_시간_청산한다() {
        Tape tape = new Tape(create(position(Map.of("exit.max-hold-days", "20", "exit.stop-loss-pct", "50"))));
        LocalDate d = LocalDate.of(2026, 3, 2);
        tape.hold(A, 10, 10000);
        assertThat(tape.day(d).trade("09:10:00", 10000, 100)).as("진입한 거래일 = 0일째").isEmpty();
        for (int i = 1; i < 20; i++) {
            d = d.plusDays(1);
            assertThat(tape.day(d).trade("09:10:00", 10000, 100)).as("보유 %d거래일째", i).isEmpty();
        }
        assertThat(tape.day(d.plusDays(1)).trade("09:10:00", 10000, 100))
                .as("보유 20거래일째 첫 체결").containsExactly(TradeSignal.marketSell(A, OrderIntent.EXIT, 10));
    }

    @Test
    void 일봉_무작위_대조군은_하루_한_번_뽑고_스윙과_중기만_받는다() {
        Map<String, String> p = new TreeMap<>(positionParams(Map.of("logic", "random-daily", "signal.per-mille", "500")));
        p.remove("signal.fast-days");
        p.remove("signal.slow-days");
        StrategyProfile pos = profile("rnd-pos", TradingStyle.POSITION, p);
        assertThat(dailySignals(FACTORY.create(pos, 7L))).isEqualTo(dailySignals(FACTORY.create(pos, 7L)));
        assertThat(dailySignals(FACTORY.create(pos, 7L))).isNotEqualTo(dailySignals(FACTORY.create(pos, 8L)));
        Map<String, String> swing = new TreeMap<>(p);
        swing.put("exit.max-hold-days", "10");
        assertThat(FACTORY.create(profile("rnd-sw", TradingStyle.SWING, swing), 1L)).isNotNull();
        Map<String, String> intraday = new TreeMap<>(common());
        intraday.put("logic", "random-daily");
        intraday.put("signal.per-mille", "500");
        assertThatThrownBy(() -> create(profile("rnd-in", TradingStyle.INTRADAY, intraday)))
                .hasMessageContaining("SWING 또는 POSITION");
    }

    /** 40거래일 동안 매일 체결 3개. 보유는 만들지 않아(신호만 봄) 매일 진입 여부가 그날 뽑기만으로 정해진다. */
    private static List<String> dailySignals(Strategy s) {
        Tape tape = new Tape(s);
        List<String> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 3, 2);
        for (int i = 0; i < 40; i++) {
            tape.day(d.plusDays(i));
            int day = i;
            for (String t : List.of("09:10:00", "09:20:00", "09:30:00")) {
                tape.trade(t, 10000, 100).forEach(sig -> out.add(day + "@" + t));
            }
        }
        assertThat(out.stream().map(x -> x.split("@")[0]).distinct().count())
                .as("하루 한 번만 신호").isEqualTo(out.size());
        return out;
    }

    @Test
    void 이동평균_추세는_중기_프로필도_받는다() {
        assertThat(create(position(Map.of()))).isNotNull();
    }

    private static StrategyProfile position(Map<String, String> extra) {
        return profile("pos", TradingStyle.POSITION, positionParams(extra));
    }

    private static Map<String, String> positionParams(Map<String, String> extra) {
        Map<String, String> p = new TreeMap<>(common());
        p.remove("exit.eod-time");
        p.putAll(Map.of("logic", "ma-trend", "signal.fast-days", "20", "signal.slow-days", "60", "exit.eod", "CARRY",
                "exit.stop-loss-pct", "10", "exit.max-hold-days", "60"));
        p.putAll(extra);
        return p;
    }

    private static Strategy create(StrategyProfile p) {
        return FACTORY.create(p, 42L);
    }

    private static StrategyProfile orb(Map<String, String> extra) {
        return profile("orb", TradingStyle.INTRADAY, orbParams(extra));
    }

    private static Map<String, String> orbParams(Map<String, String> extra) {
        Map<String, String> p = new TreeMap<>(common());
        p.put("logic", "orb");
        p.put("signal.range-minutes", "5");
        p.putAll(extra);
        return p;
    }

    private static StrategyProfile random(Map<String, String> extra) {
        Map<String, String> p = new TreeMap<>(common());
        p.put("logic", "random");
        p.put("signal.per-mille", "1000");
        p.put("limits.reentry-cooldown-minutes", "0");
        p.put("exit.take-profit-pct", "3");
        p.putAll(extra);
        return profile("rnd", TradingStyle.INTRADAY, p);
    }

    private static Map<String, String> common() {
        Map<String, String> p = new TreeMap<>();
        p.put("hypothesis", "테스트 가설");
        p.put("entry.window.start", "09:05");
        p.put("entry.window.end", "15:00");
        p.put("entry.expiry-seconds", "600");
        p.put("sizing.risk-per-trade-pct", "0.5");
        p.put("sizing.max-position-pct", "20");
        p.put("sizing.max-positions", "3");
        p.put("sizing.cash-buffer-pct", "5");
        p.put("limits.max-entries-per-day", "6");
        p.put("limits.reentry-cooldown-minutes", "30");
        p.put("limits.daily-loss-limit-pct", "2");
        p.put("limits.max-consecutive-stops", "3");
        p.put("exit.stop-loss-pct", "1.5");
        p.put("exit.eod", "FLATTEN");
        p.put("exit.eod-time", "15:10");
        return p;
    }

    private static StrategyProfile profile(String id, TradingStyle style, Map<String, String> params) {
        return new StrategyProfile(id, 1, Market.KRX, style, new TreeMap<>(params));
    }
}
