package com.autotrade.broker.kis.order;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

/**
 * 주문·취소 요청 결과. "접수됨", "접수 안 됨(확정)", "모름" 셋을 구분한다.
 *
 * <p>{@link Unknown}이면 같은 주문을 다시 보내지 않고 일별 체결 조회({@link KisAccountQueries#dailyOrders})와
 * {@link KisOrderReconciler}로 접수 여부부터 확인한다(프로젝트 지침 4장 2항, broker-and-fault-design.md 장애표 4번).
 * 이 클라이언트는 어떤 결과에서도 스스로 다시 보내지 않는다.
 */
public sealed interface KisOrderResult
        permits KisOrderResult.Accepted, KisOrderResult.NotAccepted, KisOrderResult.Unknown {

    /** 한국투자가 접수했다. {@code orderTime}은 응답의 주문시각(HHmmss, 한국 시간), 읽을 수 없으면 null. */
    record Accepted(KisOrderNo orderNo, LocalTime orderTime) implements KisOrderResult {
        public Accepted {
            Objects.requireNonNull(orderNo, "orderNo");
        }
    }

    /** 접수되지 않았음이 확정됐다. 결과 코드는 코드 형식 값만 담는다(응답 메시지 원문은 담지 않는다). */
    record NotAccepted(Reason reason, String resultCode) implements KisOrderResult {
        public NotAccepted {
            Objects.requireNonNull(reason, "reason");
            resultCode = resultCode == null ? "" : resultCode;
        }
    }

    /**
     * 보냈는지, 접수됐는지 모른다(응답 시간 초과, 연결 끊김, 결과 코드 없는 응답 등). 재주문 금지, 조회부터.
     *
     * @param sendStartedAt 실제 POST를 시작한 시각(세션 시계, 간격 대기·토큰 발급 뒤). 접수 여부 확인 창의 기준이다
     * @param sendEndedAt 응답·예외를 받은 시각(세션 시계). 반영 대기는 이 시각부터 센다
     */
    record Unknown(String reason, Instant sendStartedAt, Instant sendEndedAt) implements KisOrderResult {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(sendStartedAt, "sendStartedAt");
            Objects.requireNonNull(sendEndedAt, "sendEndedAt");
            if (sendEndedAt.isBefore(sendStartedAt)) {
                throw new IllegalArgumentException("응답 시각이 보내기 시작 시각보다 앞섭니다");
            }
        }
    }

    enum Reason {
        /** 한국투자가 결과 코드로 거절했다(잔고 부족, 호가 단위, 거래 정지 등). */
        BROKER_REJECTED,
        /** 초당 호출 한도 초과(EGW00201·EGW00215). 쉬었다가 호출자가 판단한다. */
        RATE_LIMITED,
        /** 토큰 만료·무효. 그 토큰이 아직 지금 토큰이면 버렸고 다음 호출에서 새로 받는다(60초 하한). */
        TOKEN_EXPIRED,
        /** 토큰을 받지 못해 요청을 보내지 않았다. */
        NOT_SENT
    }
}
