package com.autotrade.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;

/**
 * 비밀값(API Key, 시크릿, 토큰, 웹훅 URL). 문자열로 바뀌면 항상 마스킹된다.
 *
 * <p>Jackson이 읽을 수 있는 속성이 없어서 일반 ObjectMapper로는 평문이 직렬화되지 않는다
 * (빈 객체 오류 또는 {@code {}}). 실수로 REST 응답에 실려도 값이 나가지 않는다.
 * 평문 저장은 {@link RuntimeConfigStore}만 한다.
 */
public final class Secret {

    private static final Secret EMPTY = new Secret("");
    private static final String MASK = "****";

    private final String value;

    private Secret(String value) {
        this.value = value;
    }

    @JsonCreator
    public static Secret of(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY;
        }
        return new Secret(value.strip());
    }

    public static Secret empty() {
        return EMPTY;
    }

    @JsonIgnore
    public boolean isPresent() {
        return !value.isEmpty();
    }

    /** 평문. 증권사·알림 클라이언트에 넘길 때만 쓰고, 로그·예외 메시지에 넣지 않는다. */
    public String reveal() {
        return value;
    }

    /** 화면 표시용. 값이 있으면 {@code ****}, 없으면 빈 문자열. */
    public String masked() {
        return isPresent() ? MASK : "";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Secret other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return masked();
    }
}
