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
 * 타임아웃 뒤 접수 여부 확인(D-145, 장애표 4번). "없음"은 모든 조건이 맞을 때만 나온다. 틀리면 중복 주문이므로
 * 각 조건을 하나씩 깨서 "모름"이 되는지 본다.
 */
class KisOrderReconcilerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    /** 09:30:00 KST에 보내기 시작, 10초 뒤 시간 초과. 없음 확정은 09:30:40 이후 조회부터. */
    private static final Instant SENT = Instant.parse("2026-11-02T00:30:00Z");
    private static final Instant ENDED = SENT.plus(Duration.ofSeconds(10));
    private static final Instant SETTLED = ENDED.plus(KisOrderReconciler.SETTLE);
    private static final KisOrderReconciler.Fingerprint LIMIT_BUY = new KisOrderReconciler.Fingerprint(
            "005930", Side.BUY, OrderType.LIMIT, 10, new BigDecimal("70000"), at(SENT), at(ENDED));

    /** 시계 단계 변화가 없는 순간(단조 값 = SENT부터 벽시계 경과). */
    static Stamp at(Instant wall) {
        return new Stamp(wall, Duration.between(SENT, wall).toNanos());
    }

    static KisDailyOrder order(String odno, String original, Side side, String code, String division, long qty,
            String price, LocalTime time) {
        return new KisDailyOrder(DAY, time, "91252", odno, original, side, code, division, qty, new BigDecimal(price),
                0, null, 0, qty, 0);
    }

    /** 우리 시계와 한국투자 시계가 같은 조회(응답 Date = 조회 시각). */
    static KisDailyOrders day(Instant queriedFrom, boolean complete, KisDailyOrder... rows) {
        return dayWithServerClock(queriedFrom, Duration.ZERO, complete, rows);
    }

    /** 한국투자 시계가 우리보다 {@code serverMinusLocal}만큼 앞선(음수면 늦은) 조회. 응답은 1초 뒤 받음. */
    static KisDailyOrders dayWithServerClock(Instant queriedFrom, Duration serverMinusLocal, boolean complete,
            KisDailyOrder... rows) {
        Instant received = queriedFrom.plusSeconds(1);
        Instant serverDate = received.plus(serverMinusLocal).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new KisDailyOrders(DAY, at(queriedFrom), List.of(rows), complete, 0,
                new KisDailyOrders.ServerClock(serverDate, queriedFrom, received));
    }

    private static final KisDailyOrder OURS =
            order("0000117057", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 30, 1));

    @Test
    void 반영_대기_뒤_완전한_조회에_같은_특징의_새_주문_하나뿐이면_찾음이다() {
        assertThat(KisOrderReconciler.find(day(SETTLED, true, OURS), LIMIT_BUY, Set.of()))
                .isEqualTo(new KisOrderReconciler.Found(OURS, false));
    }

    @Test
    void 반영_대기_전이거나_불완전한_조회에서는_후보가_하나여도_찾음을_내지_않는다() {
        assertThat(KisOrderReconciler.find(day(SENT.plusSeconds(1), true, OURS), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        assertThat(KisOrderReconciler.find(day(SETTLED, false, OURS), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
    }

    @Test
    void 반영_대기_뒤_완전한_조회의_창_안에_묶이지_않은_행이_없을_때만_없음이다() {
        KisDailyOrder before = order("1", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 28, 59));
        KisDailyOrder otherSymbol = order("2", "", Side.BUY, "000660", "00", 10, "70000", LocalTime.of(9, 30, 1));
        KisDailyOrder otherSide = order("3", "", Side.SELL, "005930", "00", 10, "70000", LocalTime.of(9, 30, 1));
        Set<KisOrderNo> ours = Set.of(new KisOrderNo("91252", "2"), new KisOrderNo("91252", "3"));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, before, otherSymbol, otherSide), LIMIT_BUY, ours))
                .isSameAs(KisOrderReconciler.NotFound.INSTANCE);
        // 종목·방향이 달라도 묶이지 않은 행이 창 안에 있으면 확정하지 않는다(종목코드 형식 해석이 틀린 경우 대비)
        for (KisDailyOrder other : List.of(otherSymbol, otherSide)) {
            assertThat(KisOrderReconciler.find(day(SETTLED, true, before, other), LIMIT_BUY, Set.of()))
                    .as(other.odno()).isInstanceOf(KisOrderReconciler.Indeterminate.class);
        }
    }

    @Test
    void 원주문번호_칸에_자기_번호가_오거나_종목코드에_접두가_붙어도_우리_주문을_놓치지_않는다() {
        KisDailyOrder selfOrig = order("0000117057", "0000117057", Side.BUY, "005930", "00", 10, "70000",
                LocalTime.of(9, 30, 1));
        assertThat(selfOrig.isCancelOrModify()).isFalse();
        assertThat(KisOrderReconciler.find(day(SETTLED, true, selfOrig), LIMIT_BUY, Set.of()))
                .isEqualTo(new KisOrderReconciler.Found(selfOrig, false));
        KisDailyOrder prefixed = order("0000117057", "", Side.BUY, "A005930", "00", 10, "70000", LocalTime.of(9, 30, 1));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, prefixed), LIMIT_BUY, Set.of()))
                .isEqualTo(new KisOrderReconciler.Found(prefixed, false));
        // 해석할 수 없는 종목코드라도 창 안에 있으면 없음이 아니다
        KisDailyOrder odd = order("0000117057", "", Side.BUY, "KR7005930003", "00", 10, "70000", LocalTime.of(9, 30, 1));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, odd), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
    }

    @Test
    void 반영_대기_전이거나_불완전하거나_날짜가_다르면_모름이다() {
        assertThat(KisOrderReconciler.find(day(SETTLED.minusSeconds(1), true), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        assertThat(KisOrderReconciler.find(day(SETTLED, false), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        KisDailyOrders yesterday = new KisDailyOrders(DAY.minusDays(1), at(SETTLED), List.of(), true, 0,
                new KisDailyOrders.ServerClock(SETTLED, SETTLED, SETTLED));
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
    void 취소_주문_행은_후보가_아니고_묶이지_않았으면_확정을_막는다() {
        KisDailyOrder cancel = order("0000117060", "0000117057", Side.BUY, "005930", "00", 10, "70000",
                LocalTime.of(9, 30, 5));
        assertThat(KisOrderReconciler.find(day(SETTLED, true, cancel), LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        assertThat(KisOrderReconciler.find(day(SETTLED, true, cancel), LIMIT_BUY,
                Set.of(new KisOrderNo("91252", "117060")))).isSameAs(KisOrderReconciler.NotFound.INSTANCE);
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
                "005930", Side.SELL, OrderType.MARKET, 3, null, at(SENT), at(ENDED));
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

    @Test
    void 응답_시각이_보내기_시작보다_앞서면_특징을_만들지_않는다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new KisOrderReconciler.Fingerprint(
                "005930", Side.BUY, OrderType.LIMIT, 10, new BigDecimal("70000"), at(SENT), at(SENT.minusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 우리_시계가_61초_빠르면_잰_시계_차이만큼_창을_옮겨_접수된_주문을_놓치지_않는다() {
        // QA PR #247 발견 1 S1: 우리 09:30:00에 보낸 주문의 한국투자 주문시각은 09:28:59
        KisDailyOrder accepted = order("0000117057", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 28, 59));
        assertThat(KisOrderReconciler.find(dayWithServerClock(SETTLED, Duration.ofSeconds(-61), true, accepted),
                LIMIT_BUY, Set.of())).isEqualTo(new KisOrderReconciler.Found(accepted, false));
        // 3분 빨라도 마찬가지(여유 60초를 넘는 차이도 재서 옮긴다)
        KisDailyOrder far = order("0000117057", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 27, 0));
        assertThat(KisOrderReconciler.find(dayWithServerClock(SETTLED, Duration.ofMinutes(-3), true, far),
                LIMIT_BUY, Set.of())).isEqualTo(new KisOrderReconciler.Found(far, false));
        // 창이 옮겨진 만큼 그보다 앞선 행은 여전히 창 밖
        KisDailyOrder older = order("1", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 26, 58));
        assertThat(KisOrderReconciler.find(dayWithServerClock(SETTLED, Duration.ofSeconds(-61), true, older),
                LIMIT_BUY, Set.of())).isSameAs(KisOrderReconciler.NotFound.INSTANCE);
    }

    @Test
    void 보낸_뒤_조회_전에_PC_시계가_단계로_바뀌면_찾음도_없음도_내지_않는다() {
        // risk-ops PR #247 재검토 N1: 61초 빠른 시계로 09:30:00에 보냈고 실제 주문시각은 09:28:59. 조회 전 시간 동기화로 시계가
        // 61초 뒤로 바로잡혀 조회 때 잰 차이는 0이다
        KisDailyOrder accepted = order("0000117057", "", Side.BUY, "005930", "00", 10, "70000", LocalTime.of(9, 28, 59));
        Instant queriedWall = SETTLED.minusSeconds(61);
        Stamp queried = new Stamp(queriedWall, Duration.between(SENT, SETTLED).toNanos());
        KisDailyOrders corrected = new KisDailyOrders(DAY, queried, List.of(accepted), true, 0,
                new KisDailyOrders.ServerClock(queriedWall.plusSeconds(1), queriedWall, queriedWall.plusSeconds(1)));
        assertThat(KisOrderReconciler.find(corrected, LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        KisDailyOrders correctedEmpty = new KisDailyOrders(DAY, queried, List.of(), true, 0, corrected.serverClock());
        assertThat(KisOrderReconciler.find(correctedEmpty, LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        // 앞으로 뛴 경우: 벽시계로는 반영 대기가 지났지만 실제(단조)로는 5초뿐이다
        Stamp jumped = new Stamp(SETTLED, Duration.between(SENT, ENDED.plusSeconds(5)).toNanos());
        KisDailyOrders early = new KisDailyOrders(DAY, jumped, List.of(), true, 0,
                new KisDailyOrders.ServerClock(SETTLED, SETTLED, SETTLED));
        assertThat(KisOrderReconciler.find(early, LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        // 허용 범위(2초) 안의 차이는 단계 변화로 보지 않는다
        Stamp drift = new Stamp(SETTLED.plusSeconds(2), Duration.between(SENT, SETTLED).toNanos());
        KisDailyOrders drifted = new KisDailyOrders(DAY, drift, List.of(), true, 0,
                new KisDailyOrders.ServerClock(SETTLED.plusSeconds(2), SETTLED.plusSeconds(2), SETTLED.plusSeconds(2)));
        assertThat(KisOrderReconciler.find(drifted, LIMIT_BUY, Set.of())).isSameAs(KisOrderReconciler.NotFound.INSTANCE);
    }

    @Test
    void 서버_시각을_읽지_못한_조회로는_찾음도_없음도_내지_않는다() {
        KisDailyOrders noDate = new KisDailyOrders(DAY, at(SETTLED), List.of(), true, 0, null);
        assertThat(KisOrderReconciler.find(noDate, LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        KisDailyOrders noDateOurs = new KisDailyOrders(DAY, at(SETTLED), List.of(OURS), true, 0, null);
        assertThat(KisOrderReconciler.find(noDateOurs, LIMIT_BUY, Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
    }

    @Test
    void 시계_차이_하한은_응답_받은_시각_기준이라_차이를_작게_재지_않는다() {
        // Date 10:00:00(잘림), 우리 요청 09:59:58, 응답 10:00:03 → 하한 −3초(실제 차이는 −3초 이상)
        KisDailyOrders.ServerClock c = new KisDailyOrders.ServerClock(Instant.parse("2026-11-02T01:00:00Z"),
                Instant.parse("2026-11-02T00:59:58Z"), Instant.parse("2026-11-02T01:00:03Z"));
        assertThat(c.offsetLowerBound()).isEqualTo(Duration.ofSeconds(-3));
    }

    private static OrderState state(long ordered, long filled, long cancelled, long remaining, long rejected) {
        return new KisDailyOrder(DAY, LocalTime.NOON, "91252", "1", "", Side.BUY, "005930", "00", ordered,
                new BigDecimal("70000"), filled, filled > 0 ? new BigDecimal("70000") : null, cancelled, remaining,
                rejected).state();
    }
}
