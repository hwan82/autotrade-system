package com.autotrade.broker;

import java.util.Objects;

/**
 * 증권사 앱 키와 시크릿(키움 secret-key, 토스 client-secret 포함). 프로세스 진입점이 설정의 비밀값에서 만들어 플러그인에 넘긴다.
 * {@link #toString()}은 값을 가린다. 값은 토큰 발급 요청 본문에만 쓰고 로그·예외 메시지에 넣지 않는다(안전 불변식 9).
 */
public record BrokerCredentials(String appKey, String appSecret) {

    public BrokerCredentials {
        appKey = appKey == null ? "" : appKey.strip();
        appSecret = appSecret == null ? "" : appSecret.strip();
    }

    public static BrokerCredentials none() {
        return new BrokerCredentials("", "");
    }

    /** 둘 다 있으면 설정된 것으로 본다. 없으면 플러그인은 NOT_CONFIGURED다. */
    public boolean isConfigured() {
        return !appKey.isEmpty() && !appSecret.isEmpty();
    }

    @Override
    public String toString() {
        return "BrokerCredentials[" + (isConfigured() ? "****" : "") + "]";
    }
}
