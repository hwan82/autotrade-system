package com.autotrade.broker.kis.order;

import com.autotrade.domain.order.OrderState;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 하루치 일별 주문체결 조회 결과.
 *
 * @param queriedFrom 첫 쪽을 요청하기 직전 시각. 이 시각 이전에 접수된 주문은 결과에 들어 있다고 본다
 * @param complete 모든 쪽을 받았고 읽지 못한 행이 없다. false면 "없음"을 확정하는 데 쓰지 않는다
 * @param unreadableRows 필수 칸을 읽지 못해 뺀 행 수
 * @param serverClock 첫 쪽 응답의 HTTP {@code Date}로 잰 한국투자 시계. 헤더가 없거나 읽지 못하면 null
 */
public record KisDailyOrders(
        LocalDate date, Instant queriedFrom, List<KisDailyOrder> rows, boolean complete, int unreadableRows,
        ServerClock serverClock) {

    public KisDailyOrders {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(queriedFrom, "queriedFrom");
        rows = List.copyOf(rows);
    }

    /**
     * 한국투자 시계와 우리 시계의 차이를 재는 근거. 응답 {@code Date}(초 단위로 잘림)가 찍힌 순간은 우리 시계로 요청 직전
     * {@code requestedAt}과 응답 받은 {@code receivedAt} 사이다.
     */
    public record ServerClock(Instant serverDate, Instant requestedAt, Instant receivedAt) {
        public ServerClock {
            Objects.requireNonNull(serverDate, "serverDate");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(receivedAt, "receivedAt");
            if (receivedAt.isBefore(requestedAt)) {
                throw new IllegalArgumentException("응답 시각이 요청 시각보다 앞섭니다");
            }
        }

        /**
         * (한국투자 시계 − 우리 시계)의 하한. 한국투자 시계가 우리보다 늦을수록(음수) 작다. 실제 차이는 이 값 이상이다:
         * 헤더 시각 S ≥ {@code serverDate}, 그 순간 우리 시계 ≤ {@code receivedAt}.
         */
        public Duration offsetLowerBound() {
            return Duration.between(receivedAt, serverDate);
        }
    }

    /** 주문번호로 원주문 행을 찾는다. */
    public Optional<KisDailyOrder> original(KisOrderNo no) {
        return rows.stream().filter(r -> !r.isCancelOrModify() && no.sameOrder(r.odno())).findFirst();
    }

    /**
     * 원주문의 상태. 잔량이 남았는데 그 주문의 취소 주문 행이 있으면 {@link OrderState#CANCEL_REQUESTED}(취소 확인 전 체결이
     * 날 수 있어 종결이 아니다).
     */
    public Optional<OrderState> statusOf(KisOrderNo no) {
        return original(no).map(row -> {
            OrderState s = row.state();
            boolean cancelSent = rows.stream().anyMatch(r -> r.isCancelOrModify() && no.sameOrder(r.originalOdno()));
            if (cancelSent && (s == OrderState.ACCEPTED || s == OrderState.PARTIALLY_FILLED)) {
                return OrderState.CANCEL_REQUESTED;
            }
            return s;
        });
    }
}
