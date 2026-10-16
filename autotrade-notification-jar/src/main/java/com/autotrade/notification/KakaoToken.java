package com.autotrade.notification;

import java.time.Instant;
import java.util.Objects;

/**
 * 카카오 토큰 한 벌. 값은 {@code toString}·예외 문구에 나오지 않는다(레코드가 아닌 이유).
 *
 * <p>{@code accessExpiresAt}을 모르면(설정 파일에서 처음 읽을 때) {@link Instant#EPOCH}로 둔다. 그러면 첫 발송 전에 갱신해서
 * 만료 시각을 알게 된다.
 */
public final class KakaoToken {

    private final String accessToken;
    private final String refreshToken;
    private final Instant accessExpiresAt;

    public KakaoToken(String accessToken, String refreshToken, Instant accessExpiresAt) {
        this.accessToken = requireText(accessToken, "access-token");
        this.refreshToken = requireText(refreshToken, "refresh-token");
        this.accessExpiresAt = Objects.requireNonNull(accessExpiresAt, "accessExpiresAt");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("카카오 " + name + "이 비었습니다");
        }
        return value.strip();
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public Instant accessExpiresAt() {
        return accessExpiresAt;
    }

    @Override
    public String toString() {
        return "KakaoToken[access=****, refresh=****, accessExpiresAt=" + accessExpiresAt + "]";
    }
}
