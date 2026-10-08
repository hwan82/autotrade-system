package com.autotrade.domain.account;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 계좌 식별자(실전·모의 공통). 실제 증권사 계좌번호가 아니라 내부 별칭이다.
 * 계좌번호는 비밀값이므로 여기에 넣지 않는다(프로젝트 지침 4장 4항).
 * 예: 실전 {@code KIS-US-1}, 모의 {@code EXP-20261008-01-A03}.
 */
public record AccountId(String value) implements Comparable<AccountId> {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public AccountId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("계좌 별칭 형식이 아닙니다: " + value);
        }
    }

    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
