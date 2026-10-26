package com.autotrade.broker.kis.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 타임아웃 뒤 접수 여부 확인(D-143, 장애표 4번). "없음"은 모든 조건이 맞을 때만 나온다. 틀리면 중복 주문이므로
 * 각 조건을 하나씩 깨서 "모름"이 되는지 본다.
 */
class KisOrderReconcilerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    /** 09:30:00 KST에 보내기 시작, 요청 시간 상한 10초. 없음 확정은 09:30:40 이후 조회부터. */
    private static final Instant SENT = Instant.parse("2026-11-02T00:30:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant SETTLED = SENT.plus(TIMEOUT).plus(KisOrderReconciler.SETTLE);
    private static final KisOrderReconciler.Fingerprint LIMIT_BUY = new KisOrderReconciler.Fingerprint(
            DAY, "005930", Side.BUY, OrderType.LIMIT, 10, new BigDecimal("70000"), SENT, TIMEOUT);

    static KisDailyOrder order(String odno, String original, Side side, String code, String division, long qty,
            String price, LocalTime time) {
        return new KisDailyOrder(DAY, time, "91252", odno, original, side, code, division, qty, new BigDecimal(price),
                0, null, 0, qty, 0);
    }

    static KisDailyOrders day(Instant queriedFrom, boolean complete, KisDailyOrder... rows) {
        return new KisDailyOrders(DAY, queriedFrom, List.of(rows), complete, 0);
    }

    private static final KisDailyOrder OURS =
            order("0000117057", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 30, 1));

    @Test
    void 같은_특징의_새_주문_하나면_찾음이다() {
        assertThat(KisOrderReconciler.find(day(SENT.plusSeconds(5), true, OURS), LIMIT_BUY, Set.of()))
                .isEqualTo(new KisOrderReconciler.Found(OURS, false));
    }

    @Test
    void 반영_대기_뒤_완전한_조회에_같은_종목_방향의_새_주문이_없을_때만_없음이다() {
        KisDailyOrder before = order("1", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 28, 59));
        KisDailyOrder otherSymbol = order("2", "", Side.BUY, "000660", "00", 10, "70000", LocalTime.of(9, 30, 1));
        KisDailyOrder otherSide = order("3", "", Side.SELL, "005930", "00", 10, "70000", LocalTime.of(9, 30, 1));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, before, otherSymbol, otherSide), LIMIT_BUY, Set.of()))
                .isSameAs(KisOrderReconciler.NotFound.INSTANCE);
    }

    @Test
    void 반영_대기_전이거나_불완전하거나_날짜가_다르면_모름이다() {
        assertThat(KisOrderReconciler.find(day(SETTLED.minusSeconds(1), true), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        assertThat(KisOrderReconciler.find(day(SETTLED, false), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        KisDailyOrders yesterday = new KisDailyOrders(DAY.minusDays(1), SETTLED, List.of(), true, 0);
        assertThat(KisOrderReconciler.find(yesterday, LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
    }

    @Test
    void 수량_가격_주문구분만_다른_새_주문이_있으면_없음으로_확정하지_않는다() {
        KisDailyOrder otherQty = order("5", "", Side.BUY, "005930", "00", 11, "70000", LocalTime.of(9, 30, 2));
        KisDailyOrder otherPrice = order("6", "", Side.BUY, "005930", "00", 10, "70100", LocalTime.of(9, 30, 2));
        KisDailyOrder market = order("7", "", Side.BUY, "005930", "01", 10, "0", LocalTime.of(9, 30, 2));
        for (KisDailyOrder near : List.of(otherQty, otherPrice, market)) {
            assertThat(KisOrderReconciler.find(day(SETTLED, true, near), LIMIT_BUY, Set.of()))
                    .as(near.odno()).isInstanceOf(KisOrderReconciler.Indeterminate.class);
        }
    }

    @Test
    void 후보가_둘이면_모름이고_다른_키에_묶인_주문은_뺀다() {
        KisDailyOrder twin = order("0000117058", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 30, 3));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, OURS, twin), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        // 117058은 이미 다른 멱등 키의 주문(앞자리 0이 달라도 같은 번호)
        assertThat(KisOrderReconciler.find(day(SETTLED, true, OURS, twin), LIMIT_BUY,
                Set.of(new KisOrderNo("91252", "117058")))).isEqualTo(new KisOrderReconciler.Found(OURS, false));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, OURS), LIMIT_BUY,
                Set.of(new KisOrderNo("91252", "117057")))).isSameAs(KisOrderReconciler.NotFound.INSTANCE);
    }

    @Test
    void 취소_주문_행은_새_주문_후보가_아니다() {
        KisDailyOrder cancel = order("0000117060", "0000117057", Side.BUY, "005930", "00", 10, "70000",
                LocalTime.of(9, 30, 5));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, cancel), LIMIT_BUY, Set.of()))
                .isSameAs(KisOrderReconciler.NotFound.INSTANCE);
    }

    @Test
    void 시계_오차_60초_안의_앞선_주문도_후보로_본다() {
        KisDailyOrder early = order("9", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 29, 0));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, early), LIMIT_BUY, Set.of()))
                .isEqualTo(new KisOrderReconciler.Found(early, false));
    }

    @Test
    void 시장가는_주문구분_01이면_단가와_상관없이_찾는다() {
        KisOrderReconciler.Fingerprint marketSell = new KisOrderReconciler.Fingerprint(
                DAY, "005930", Side.SELL, OrderType.MARKET, 3, null, SENT, TIMEOUT);
        KisDailyOrder upperLimit = order("8", "", Side.SELL, "005930", "01", 3, "91000", LocalTime.of(9, 30, 1));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, upperLimit), marketSell, Set.of()))
                .isEqualTo(new KisOrderReconciler.Found(upperLimit, false));
    }

    @Test
    void 주문번호로_찾고_없으면_모름이다() {
        KisDailyOrders d = day(SETTLED, true, OURS);
        assertThat(KisOrderReconciler.byOrderNo(d, new KisOrderNo("91252", "117057")))
                .isEqualTo(new KisOrderReconciler.Found(OURS, true));
        assertThat(KisOrderReconciler.byOrderNo(d, new KisOrderNo("91252", "117059")))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
    }

    @Test
    void 원주문_상태는_체결_거부_잔량_취소_순으로_정한다() {
        assertThat(state(10, 10, 0, 0, 0)).isEqualTo(OrderState.FILLED);
        assertThat(state(10, 0, 0, 0, 10)).isEqualTo(OrderState.REJECTED);
        assertThat(state(10, 0, 0, 10, 0)).isEqualTo(OrderState.ACCEPTED);
        assertThat(state(10, 4, 0, 6, 0)).isEqualTo(OrderState.PARTIALLY_FILLED);
        assertThat(state(10, 4, 6, 0, 0)).isEqualTo(OrderState.CANCELED);
        assertThat(state(10, 0, 0, 0, 0)).isEqualTo(OrderState.UNKNOWN);
    }

    @Test
    void 잔량이_남은_원주문에_취소_행이_있으면_취소_요청_중이다() {
        KisDailyOrder cancel = order("0000117060", "0000117057", Side.BUY, "005930", "00", 10, "70000",
                LocalTime.of(9, 31, 5));
        KisDailyOrders d = day(SETTLED, true, OURS, cancel);
        assertThat(d.statusOf(new KisOrderNo("91252", "0000117057"))).contains(OrderState.CANCEL_REQUESTED);
        assertThat(day(SETTLED, true, OURS).statusOf(new KisOrderNo("91252", "0000117057")))
                .contains(OrderState.ACCEPTED);
        assertThat(d.statusOf(new KisOrderNo("91252", "1"))).isEmpty();
    }

    private static OrderState state(long ordered, long filled, long cancelled, long remaining, long rejected) {
        return new KisDailyOrder(DAY, LocalTime.NOON, "91252", "1", "", Side.BUY, "005930", "00", ordered,
                new BigDecimal("70000"), filled, filled > 0 ? new BigDecimal("70000") : null, cancelled, remaining,
                rejected).state();
    }
}
