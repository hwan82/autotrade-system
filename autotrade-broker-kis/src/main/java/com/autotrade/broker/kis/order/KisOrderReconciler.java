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
 * 주문 결과를 모를 때(타임아웃 등) 접수 여부를 일별 체결 조회로 확인한다(D-145, plan D4).
 *
 * <p>한국투자 주문에는 멱등 키 칸이 없다 `[사실]`(order_cash 입력값). 그래서 "보내기 시작한 시각 이후에 들어온 같은 종목·방향·
 * 수량·주문구분(지정가면 가격까지)의 새 주문"을 찾는다. 이미 다른 키에 묶인 주문번호({@code claimed})는 뺀다.
 *
 * <p>판정(안전한 쪽으로 기운다: "없음"이 틀리면 중복 주문, "찾음"이 틀리면 남의 주문을 우리 것으로 묶는다)
 * <ul>
 *   <li>먼저 조회가 완전하고(모든 쪽, 응답 칸 있음, 읽지 못한 행 0) 같은 날이며, 조회 시작이 "보내기 시작 + 요청 시간 상한 +
 *       반영 대기"보다 뒤여야 한다. 아니면 {@link Indeterminate}(찾음도 내지 않는다).</li>
 *   <li>창(보내기 시작 − 시계 오차 이후)에 다른 키에 묶이지 않은 행이 하나도 없으면 {@link NotFound}. 종목·방향·취소 여부를
 *       가리지 않고 센다: 종목코드 형식이나 원주문번호 칸 해석이 틀려 우리 주문을 놓쳐도 없음이 나오지 않게 한다.</li>
 *   <li>창 안의 묶이지 않은 행이 정확히 하나이고 그것이 같은 특징이면 {@link Found}. 그 밖에는 {@link Indeterminate}.</li>
 *   <li>그래서 호출자는 {@code claimed}에 이 계좌에서 우리가 받은 모든 주문번호(취소 주문 번호 포함)를 넣는다. 빠뜨리면
 *       모름이 늘어날 뿐 잘못된 확정은 나오지 않는다.</li>
 *   <li>사용자가 HTS에서 같은 종목·수량으로 직접 낸 주문만 창 안에 있고 우리 주문이 실제로 없으면 우리 것으로 잡을 수 있다
 *       ({@code byOrderNo=false}로 표시, 묶음 E에서 사람 확인 조건을 정한다).</li>
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
            if (!sendStartedAt.atZone(SEOUL).toLocalDate().equals(tradingDate)) {
                throw new IllegalArgumentException("보내기 시작한 시각의 한국 날짜가 주문 날짜와 다릅니다");
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
        // 찾음·없음 둘 다 같은 전제를 요구한다: 완전한 조회, 반영 대기 뒤(risk-ops PR #247 R1)
        if (!day.complete()) {
            return new Indeterminate("조회가 불완전함(쪽 상한, 응답 칸 없음 또는 읽지 못한 행)");
        }
        Instant settled = fp.sendStartedAt().plus(fp.requestTimeout()).plus(SETTLE);
        if (day.queriedFrom().isBefore(settled)) {
            return new Indeterminate("반영 대기 시간 전 조회");
        }
        Instant windowStart = fp.sendStartedAt().minus(CLOCK_SKEW);
        // 창 안의 묶이지 않은 행 전부(종목·방향·취소 여부를 가리지 않음). 가정 칸(pdno 형식, orgn_odno)에 기대지 않는다
        List<KisDailyOrder> inWindow = day.rows().stream()
                .filter(r -> !r.orderDate().atTime(r.orderTime()).atZone(SEOUL).toInstant().isBefore(windowStart))
                .filter(r -> claimed.stream().noneMatch(c -> c.sameOrder(r.odno())))
                .toList();
        List<KisDailyOrder> candidates = inWindow.stream()
                .filter(r -> !r.isCancelOrModify())
                .filter(r -> sameSymbol(r.symbolCode(), fp.symbolCode()) && r.side() == fp.side())
                .filter(r -> r.orderedQuantity() == fp.quantity())
                .filter(r -> sameDivisionAndPrice(r, fp))
                .toList();
        if (candidates.size() == 1 && inWindow.size() == 1) {
            return new Found(candidates.get(0), false);
        }
        if (!inWindow.isEmpty()) {
            // 후보가 둘 이상이거나, 특징이 다른 행이 함께 있다. 칸 해석이 틀렸을 수 있으므로 어느 쪽으로도 확정하지 않는다
            return new Indeterminate("창 안의 묶이지 않은 주문 행 " + inWindow.size() + "건, 같은 특징 " + candidates.size() + "건");
        }
        return NotFound.INSTANCE;
    }

    /** 종목코드 비교. 앞에 시장 구분 문자가 붙어 오는 형식(예 {@code A005930})도 같은 종목으로 본다 `[가정]`. */
    static boolean sameSymbol(String rowCode, String code) {
        return rowCode.equals(code) || (rowCode.length() == code.length() + 1 && rowCode.endsWith(code));
    }

    private static boolean sameDivisionAndPrice(KisDailyOrder r, Fingerprint fp) {
        if (fp.type() == OrderType.MARKET) {
            return "01".equals(r.orderDivision()); // 시장가 행의 단가는 0이거나 상한가일 수 있어 보지 않는다
        }
        return "00".equals(r.orderDivision()) && r.orderPrice().compareTo(fp.limitPrice()) == 0;
    }
}
