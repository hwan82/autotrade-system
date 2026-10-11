package com.autotrade.broker.kis;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * 한국투자 REST 응답의 결과 코드 해석. 조회 응답은 {@code rt_cd}(0 정상)·{@code msg_cd}·{@code msg1}, 토큰 발급 오류 응답은
 * {@code error_code}·{@code error_description}으로 온다.
 *
 * <p>근거(요약은 docs/research/kis-open-api-spec.md 4장)
 * <ul>
 *   <li>`[사실]` {@code EGW00201} 초당 거래건수 초과: 공식 저장소 README "초당 거래건수 초과 (EGW00201)",
 *       backtester/kis_backtest/providers/kis/data.py(61초 쉬고 최대 3회 재시도).</li>
 *   <li>`[사실]` {@code EGW00123} 토큰 만료: 공식 저장소 legacy/rest/current_price_samle.py(HTTP 200에 msg_cd EGW00123이면
 *       토큰을 다시 받아 한 번 더 부름).</li>
 *   <li>`[사실]` {@code EGW00215} 원장에서 허용 가능한 초당 거래건수 초과: 사용자 제공 한국투자 오픈API 전체문서
 *       (2026-10-10 수신) "주식잔고조회" 개요(개인 유량과 무관하게 TR 전체 초당 120 TPS, 재시도 처리 요청).</li>
 *   <li>`[사실]` 접근토큰 재발급은 1분당 1회: 공식 저장소 README 5장. 유효기간 24시간, 6시간 안 재신청은 직전 토큰 반환:
 *       전체문서 "접근토큰발급(P)".</li>
 *   <li>`[가정]` {@code EGW00121}(유효하지 않은 토큰)·{@code EGW00122}(토큰 없음)도 토큰을 다시 받아야 하는 코드이고,
 *       재발급 제한 응답 코드는 {@code EGW00133}이다. 전체문서에도 오류 코드표가 없고 포털(apiportal.koreainvestment.com)을
 *       이 개발 환경에서 열 수 없어 2차 자료 기준이다. 첫 실접속(Q-015)에서 확인한다.</li>
 * </ul>
 * 응답 메시지 원문은 로그·예외에 옮기지 않고 코드와 고정 안내 문구만 쓴다(안전 불변식 9).
 */
final class KisErrorCode {

    enum Kind {
        OK,
        /** 토큰 만료·무효. 토큰을 새로 받아 한 번 더 부른다. */
        TOKEN_EXPIRED,
        /** 호출 한도 초과. 쉬었다가 다시 부른다. */
        RATE_LIMITED,
        /** 토큰·접속키 재발급 제한(1분당 1회). 자격 증명 거절이 아니다. */
        ISSUE_THROTTLED,
        /** 그 밖의 오류(입력값, 종목 없음, 서버 오류, 키 거절 등). */
        OTHER
    }

    /** 초당 거래건수 초과(개인 유량), 원장 초당 거래건수 초과(TR 전체 120 TPS, 공식 문서 "재시도 처리"). */
    static final Set<String> RATE_CODES = Set.of("EGW00201", "EGW00215");
    static final String ISSUE_THROTTLE = "EGW00133";
    static final Set<String> TOKEN_CODES = Set.of("EGW00123", "EGW00121", "EGW00122");

    private final boolean ok;
    private final String code;

    private KisErrorCode(boolean ok, String code) {
        this.ok = ok;
        this.code = code;
    }

    /** 응답 JSON에서 읽는다. {@code rt_cd}가 "0"이면 정상. 결과 코드는 {@code msg_cd}, 없으면 {@code error_code}. */
    static KisErrorCode of(JsonNode body) {
        String code = body.path("msg_cd").asText("").strip();
        if (code.isEmpty()) {
            code = body.path("error_code").asText("").strip();
        }
        boolean ok = "0".equals(body.path("rt_cd").asText("").strip());
        return new KisErrorCode(ok, code.matches("[A-Za-z0-9_]{1,20}") ? code : "");
    }

    boolean ok() {
        return ok;
    }

    String code() {
        return code;
    }

    Kind kind() {
        if (ok) {
            return Kind.OK;
        }
        if (TOKEN_CODES.contains(code)) {
            return Kind.TOKEN_EXPIRED;
        }
        if (RATE_CODES.contains(code)) {
            return Kind.RATE_LIMITED;
        }
        if (ISSUE_THROTTLE.equals(code)) {
            return Kind.ISSUE_THROTTLED;
        }
        return Kind.OTHER;
    }

    /** 로그·예외용 설명. 코드와 고정 안내만 담는다. */
    String describe() {
        String hint = switch (kind()) {
            case TOKEN_EXPIRED -> " 토큰 만료·무효";
            case RATE_LIMITED -> " 초당 호출 한도 초과";
            case ISSUE_THROTTLED -> " 토큰 재발급 제한(1분당 1회)";
            default -> "";
        };
        return "결과 코드 " + (code.isEmpty() ? "없음" : code) + hint;
    }
}
