package com.autotrade.broker.kis.order;

import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.order.Side;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * 일별 주문체결 조회({@code TTTC0081R}) 한 행. 원주문과 취소 주문이 각각 한 행이다(취소 주문은 원주문번호가 있다).
 *
 * <p>`[사실]` 필드 이름: open-trading-api cd63cae examples_llm/domestic_stock/inquire_daily_ccld/chk_inquire_daily_ccld.py
 * ({@code ord_dt, ord_gno_brno, odno, orgn_odno, sll_buy_dvsn_cd, pdno, ord_qty, ord_unpr, ord_tmd, tot_ccld_qty, avg_prvs,
 * cnc_cfrm_qty, rmn_qty, rjct_qty, ord_dvsn_cd}). 매도매수구분 {@code 01} 매도, {@code 02} 매수(같은 파일 입력값 설명).
 *
 * @param orderTime 주문시각(한국 시간)
 * @param originalOdno 원주문번호. 새 주문이면 빈 값
 * @param orderDivision 주문구분코드({@code 00} 지정가, {@code 01} 시장가 …)
 * @param averagePrice 체결 평균가. 체결이 없으면 null
 */
public record KisDailyOrder(
        LocalDate orderDate,
        LocalTime orderTime,
        String orgNo,
        String odno,
        String originalOdno,
        Side side,
        String symbolCode,
        String orderDivision,
        long orderedQuantity,
        BigDecimal orderPrice,
        long filledQuantity,
        BigDecimal averagePrice,
        long cancelConfirmedQuantity,
        long remainingQuantity,
        long rejectedQuantity) {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    public KisDailyOrder {
        Objects.requireNonNull(orderDate, "orderDate");
        Objects.requireNonNull(orderTime, "orderTime");
        Objects.requireNonNull(odno, "odno");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(symbolCode, "symbolCode");
        orgNo = orgNo == null ? "" : orgNo;
        originalOdno = originalOdno == null ? "" : originalOdno;
        orderDivision = orderDivision == null ? "" : orderDivision;
        if (orderedQuantity <= 0 || filledQuantity < 0 || cancelConfirmedQuantity < 0 || remainingQuantity < 0
                || rejectedQuantity < 0 || filledQuantity > orderedQuantity) {
            throw new IllegalArgumentException("일별 체결 수량이 범위를 벗어났습니다");
        }
    }

    /** 원주문번호가 있으면 취소·정정 주문 행이다. 앞자리 0만 있는 값도 없음으로 본다 `[가정]`. */
    public boolean isCancelOrModify() {
        return !originalOdno.isEmpty() && !KisOrderNo.normalize(originalOdno).equals("0");
    }

    public KisOrderNo orderNo() {
        return new KisOrderNo(orgNo, odno);
    }

    /**
     * 원주문 행의 상태. 순서: 전량 체결 → 거부(체결 0) → 잔여가 있으면 접수·일부 체결 → 취소 확인으로 다 설명되면 취소 →
     * 그 밖에는 {@link OrderState#UNKNOWN}. 장 마감 뒤 남은 잔량의 만료 표시는 확인하지 못했다 `[가정]`(Q-015). 취소 요청
     * 중인지는 취소 주문 행과 함께 봐야 해서 {@link KisDailyOrders#statusOf}가 정한다.
     */
    public OrderState state() {
        if (filledQuantity >= orderedQuantity) {
            return OrderState.FILLED;
        }
        if (rejectedQuantity > 0 && filledQuantity == 0) {
            return OrderState.REJECTED;
        }
        if (remainingQuantity > 0) {
            return filledQuantity > 0 ? OrderState.PARTIALLY_FILLED : OrderState.ACCEPTED;
        }
        if (cancelConfirmedQuantity > 0 && filledQuantity + cancelConfirmedQuantity >= orderedQuantity) {
            return OrderState.CANCELED;
        }
        return OrderState.UNKNOWN;
    }

    /** 응답 행을 읽는다. 필수 칸을 읽을 수 없으면 빈 값(호출자가 그 조회를 "불완전"으로 센다). */
    static Optional<KisDailyOrder> parse(JsonNode row) {
        try {
            String sideCode = text(row, "sll_buy_dvsn_cd");
            Side side = switch (sideCode) {
                case "01" -> Side.SELL;
                case "02" -> Side.BUY;
                default -> throw new IllegalArgumentException("매도매수구분");
            };
            String odno = text(row, "odno");
            new KisOrderNo(text(row, "ord_gno_brno"), odno); // 형식 검사
            String tmd = text(row, "ord_tmd");
            LocalTime time = KisOrderClient.time(tmd);
            if (time == null) {
                throw new IllegalArgumentException("주문시각");
            }
            long filled = quantity(row, "tot_ccld_qty");
            return Optional.of(new KisDailyOrder(
                    LocalDate.parse(text(row, "ord_dt"), YYYYMMDD),
                    time,
                    text(row, "ord_gno_brno"),
                    odno,
                    text(row, "orgn_odno"),
                    side,
                    text(row, "pdno"),
                    text(row, "ord_dvsn_cd"),
                    quantity(row, "ord_qty"),
                    amount(row, "ord_unpr"),
                    filled,
                    filled > 0 ? amount(row, "avg_prvs") : null,
                    quantity(row, "cnc_cfrm_qty"),
                    quantity(row, "rmn_qty"),
                    quantity(row, "rjct_qty")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static String text(JsonNode row, String name) {
        return row.path(name).asText("").strip();
    }

    /** 수량. 빈 칸은 0, 소수(".00")는 정수여야 한다. */
    static long quantity(JsonNode row, String name) {
        String t = text(row, name);
        return t.isEmpty() ? 0 : new BigDecimal(t).stripTrailingZeros().longValueExact();
    }

    static BigDecimal amount(JsonNode row, String name) {
        String t = text(row, name);
        return t.isEmpty() ? BigDecimal.ZERO : new BigDecimal(t);
    }
}
