package com.autotrade.app.engine.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class OperatorPasswordHashTest {

    static final String PASSWORD = "correct horse battery";
    /** 테스트는 하한 반복 횟수로 만든다(운영 기본값 600,000은 느림). */
    static final String HASH = OperatorPasswordHash.create(PASSWORD.toCharArray(), new SecureRandom(),
            OperatorPasswordHash.MIN_ITERATIONS);

    @Test
    void 맞는_비밀번호만_통과한다() {
        OperatorPasswordHash hash = OperatorPasswordHash.parse(HASH).orElseThrow();

        assertThat(hash.matches(PASSWORD.toCharArray())).isTrue();
        assertThat(hash.matches("correct horse batterY".toCharArray())).isFalse();
        assertThat(hash.matches(new char[0])).isFalse();
        assertThat(hash.matches(null)).isFalse();
    }

    @Test
    void 해시_문자열에_평문이_없고_salt가_매번_다르다() {
        String other = OperatorPasswordHash.create(PASSWORD.toCharArray(), new SecureRandom(),
                OperatorPasswordHash.MIN_ITERATIONS);

        assertThat(HASH).startsWith("pbkdf2-sha256$100000$").doesNotContain(PASSWORD);
        assertThat(other).isNotEqualTo(HASH);
        assertThat(OperatorPasswordHash.parse(HASH).orElseThrow().toString()).doesNotContain(HASH);
    }

    @Test
    void 운영_기본_반복_횟수는_OWASP_권고값이다() {
        assertThat(OperatorPasswordHash.ITERATIONS).isEqualTo(600_000);
    }

    @Test
    void 형식이_틀리거나_약한_해시는_미설정으로_본다() {
        assertThat(OperatorPasswordHash.parse(null)).isEmpty();
        assertThat(OperatorPasswordHash.parse("")).isEmpty();
        assertThat(OperatorPasswordHash.parse("plaintext-password")).isEmpty();
        assertThat(OperatorPasswordHash.parse(HASH.replace("pbkdf2-sha256", "md5"))).isEmpty();
        assertThat(OperatorPasswordHash.parse(HASH.replace("$100000$", "$1000$"))).isEmpty();
        assertThat(OperatorPasswordHash.parse(HASH.replace("$100000$", "$abc$"))).isEmpty();
        assertThat(OperatorPasswordHash.parse(HASH + "$extra")).isEmpty();
        assertThat(OperatorPasswordHash.parse("pbkdf2-sha256$100000$!!!$???")).isEmpty();
    }

    @Test
    void 짧은_비밀번호는_해시를_만들지_않는다() {
        assertThatThrownBy(() -> OperatorPasswordHash.create("short".toCharArray(), new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("short");
    }
}
