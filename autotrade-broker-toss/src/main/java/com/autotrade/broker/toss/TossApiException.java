package com.autotrade.broker.toss;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 토스 Open API가 오류로 답했다. 메시지에는 HTTP 상태, 오류 코드, 요청 식별자만 넣는다. 응답 문구 원문은 넣지 않는다
 * (안전 불변식 9: 토큰·키가 섞일 가능성을 원천 차단).
 *
 * <p>`[사실]` 오류 본문은 {@code {"error":{"requestId","code","message"}}}이고, 토큰 발급만 OAuth2 표준
 * {@code {"error","error_description"}}이다(사용자 제공 토스 OpenAPI 명세 v1.2.24, docs/research/toss-openapi-spec.md 3장).
 */
public final class TossApiException extends IOException {

    private static final long serialVersionUID = 1L;

    /** 우리 처리 분류(D-053). */
    public enum Kind {
        /** 401 {@code expired-token}·{@code invalid-token}: 토큰을 버리고 한 번 다시 받는다. */
        TOKEN_INVALID,
        /** 401 {@code token-revoked}: 같은 클라이언트로 다른 곳이 새 토큰을 받았다. 자동 재발급하지 않는다. */
        TOKEN_REVOKED,
        /** 그 밖의 401(예: {@code login-user-not-found}). */
        UNAUTHORIZED,
        /** 403 권한 부족. */
        FORBIDDEN,
        /** 404 종목·자원 없음. */
        NOT_FOUND,
        /** 400 요청 형식 오류(예: {@code unsupported-symbol}). */
        BAD_REQUEST,
        /** 429 호출 한도 초과. */
        RATE_LIMITED,
        /** 5xx 일시 오류. */
        SERVER_ERROR,
        /** 그 밖의 상태 또는 본문 해석 실패. */
        OTHER
    }

    private final Kind kind;
    private final int status;
    private final String code;
    private final transient Duration retryAfter;

    TossApiException(String what, Kind kind, int status, String code, String requestId, Duration retryAfter) {
        super("토스 " + what + " 실패: HTTP " + status
                + (code.isEmpty() ? "" : ", code=" + code)
                + (requestId.isEmpty() ? "" : ", requestId=" + requestId));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    /** 토스 오류 코드(예: {@code rate-limit-exceeded}). 없으면 빈 문자열. */
    public String code() {
        return code;
    }

    /** 429의 {@code Retry-After}. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    static Kind classify(int status, String code) {
        if (status == 401) {
            return switch (code) {
                case "expired-token", "invalid-token" -> Kind.TOKEN_INVALID;
                case "token-revoked" -> Kind.TOKEN_REVOKED;
                default -> Kind.UNAUTHORIZED;
            };
        }
        if (status == 403) {
            return Kind.FORBIDDEN;
        }
        if (status == 404) {
            return Kind.NOT_FOUND;
        }
        if (status == 400) {
            return Kind.BAD_REQUEST;
        }
        if (status == 429) {
            return Kind.RATE_LIMITED;
        }
        if (status >= 500 && status <= 599) {
            return Kind.SERVER_ERROR;
        }
        return Kind.OTHER;
    }
}
