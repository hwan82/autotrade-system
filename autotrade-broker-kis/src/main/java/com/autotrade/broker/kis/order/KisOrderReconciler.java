package com.autotrade.broker.kis.order;

import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 주문 결과를 모를 때(타임아웃 등) 접수 여부를 일별 체결 조회로 확인한다(D-143, plan D4).
 *
 * <p>한국투자 주문에는 멱등 키 칸이 없다 `[사실]`(order_cash 입력값). 그래서 "보내기 시작한 시각 이후에 들어온 같은 종목·방향·
 * 수량·주문구분(지정가면 가격까지)의 새 주문"을 찾는다. 이미 다른 키에 묶인 주문번호({@code claimed})는 뺀다.
 *
 * <p>판정(안전한 쪽으로 기운다: "없음"이 틀리면 중복 주문, "있음"이 틀리면 재발주를 안 할 뿐이다)
 * <ul>
 *   <li>후보 하나 → {@link Found}. 후보 둘 이상 → {@link Indeterminate}(어느 것이 우리 주문인지 모름).</li>
 *   <li>후보 0 → 다음을 모두 만족할 때만 {@link NotFound}: 조회가 완전(모든 쪽, 읽지 못한 행 0), 조회 날짜가 보낸 날과 같음,
 *       조회 시작이 "보내기 시작 + 요청 시간 상한 + 반영 대기"보다 뒤, 창 안에 같은 종목·방향의 (묶이지 않은) 새 주문이
 *       하나도 없음(수량·가격·주문구분 칸 해석이 틀려 후보를 놓치는 경우를 막는다). 아니면 {@link Indeterminate}.</li>
 *   <li>사용자가 HTS에서 같은 종목·수량으로 직접 낸 주문이 창 안에 있으면 우리 것으로 잡을 수 있다. 이 경우 재발주를 하지 않는
 *       쪽으로 틀리므로 중복 주문은 생기지 않지만, 그 주문을 우리 것으로 다룰 위험이 남는다({@code byOrderNo=false}로 표시,
 *       묶음 E에서 사람 확인 조건을 정한다).</li>
 * </ul>
 * 반영 대기 30초와 시계 오차 60초는 `[가정]`이다(공식 자료 없음, Q-015 실측 항목).
 */
public final class KisOrderReconciler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 접수된 주문이 일별 체결 조회에 보이기까지 기다리는 시간 `[가정]`. */
    public static final Duration SETTLE = Duration.ofSeconds(30);
    /** 우리 시계와 한국투자 주문시각의 오차 여유 `[가정]`. 창을 앞으로 넓혀 후보를 놓치지 않게 한다. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private KisOrderReconciler() {
    }

    /** 결과를 모르는 주문의 특징. */
    public record Fingerprint(
            LocalDate tradingDate,
            String symbolCode,
            Side side,
            OrderType type,
            long quantity,
            BigDecimal limitPrice,
            Instant sendStartedAt,
            Duration requestTimeout) {

        public Fingerprint {
            Objects.requireNonNull(tradingDate, "tradingDate");
            Objects.requireNonNull(symbolCode, "symbolCode");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(sendStartedAt, "sendStartedAt");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (quantity <= 0) {
                throw new IllegalArgumentException("수량은 0보다 커야 합니다");
            }
            if ((type == OrderType.LIMIT) != (limitPrice != null)) {
                throw new IllegalArgumentException("지정가에만 가격이 있습니다");
            }
            if (requestTimeout.isNegative()) {
                throw new IllegalArgumentException("요청 시간 상한이 음수입니다");
            }
        }
    }

    public sealed interface Match permits Found, NotFound, Indeterminate {
    }

    /** @param byOrderNo 주문번호로 찾았으면 true, 특징 대조로 찾았으면 false */
    public record Found(KisDailyOrder row, boolean byOrderNo) implements Match {
        public Found {
            Objects.requireNonNull(row, "row");
        }
    }

    /** 접수되지 않았음이 확정됐다. 새 멱등 키로 1회 재발주할 수 있다(그 결정은 주문 경로가 한다). */
    public enum NotFound implements Match {
        INSTANCE
    }

    /** 확정하지 못했다. 재발주 금지. */
    public record Indeterminate(String reason) implements Match {
        public Indeterminate {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 응답으로 주문번호를 받은 주문을 찾는다. 번호가 안 보이면 없음이 아니라 모름이다(접수 응답을 이미 받았으므로). */
    public static Match byOrderNo(KisDailyOrders day, KisOrderNo no) {
        return day.original(no)
                .<Match>map(row -> new Found(row, true))
                .orElseGet(() -> new Indeterminate("접수 응답의 주문번호가 일별 체결 조회에 없음"));
    }

    /** 결과를 모르는 주문을 특징으로 찾는다. */
    public static Match find(KisDailyOrders day, Fingerprint fp, Set<KisOrderNo> claimed) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(fp, "fp");
        Objects.requireNonNull(claimed, "claimed");
        if (!day.date().equals(fp.tradingDate())) {
            return new Indeterminate("조회 날짜가 주문 날짜와 다름");
        }
        Instant windowStart = fp.sendStartedAt().minus(CLOCK_SKEW);
        List<KisDailyOrder> sameSymbolSide = day.rows().stream()
                .filter(r -> !r.isCancelOrModify())
                .filter(r -> r.orderDate().equals(fp.tradingDate()))
                .filter(r -> !r.orderDate().atTime(r.orderTime()).atZone(SEOUL).toInstant().isBefore(windowStart))
                .filter(r -> r.symbolCode().equals(fp.symbolCode()) && r.side() == fp.side())
                .filter(r -> claimed.stream().noneMatch(c -> c.sameOrder(r.odno())))
                .toList();
        List<KisDailyOrder> candidates = sameSymbolSide.stream()
                .filter(r -> r.orderedQuantity() == fp.quantity())
                .filter(r -> sameDivisionAndPrice(r, fp))
                .toList();
        if (candidates.size() == 1) {
            return new Found(candidates.get(0), false);
        }
        if (candidates.size() > 1) {
            return new Indeterminate("같은 특징의 주문이 " + candidates.size() + "건");
        }
        if (!sameSymbolSide.isEmpty()) {
            // 수량·가격·주문구분만 다른 새 주문이 창 안에 있다. 칸 해석이 틀렸을 수 있으므로 없음으로 확정하지 않는다
            return new Indeterminate("같은 종목·방향의 다른 새 주문이 " + sameSymbolSide.size() + "건");
        }
        if (!day.complete()) {
            return new Indeterminate("조회가 불완전함(쪽 상한 또는 읽지 못한 행)");
        }
        Instant settled = fp.sendStartedAt().plus(fp.requestTimeout()).plus(SETTLE);
        if (day.queriedFrom().isBefore(settled)) {
            return new Indeterminate("반영 대기 시간 전 조회");
        }
        return NotFound.INSTANCE;
    }

    private static boolean sameDivisionAndPrice(KisDailyOrder r, Fingerprint fp) {
        if (fp.type() == OrderType.MARKET) {
            return "01".equals(r.orderDivision()); // 시장가 행의 단가는 0이거나 상한가일 수 있어 보지 않는다
        }
        return "00".equals(r.orderDivision()) && r.orderPrice().compareTo(fp.limitPrice()) == 0;
    }
}
