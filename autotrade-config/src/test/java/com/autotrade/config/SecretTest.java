package com.autotrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

class SecretTest {

    private static final String FAKE_SECRET = "PLACEHOLDER-NOT-A-REAL-KEY-7f3a";

    @Test
    void 문자열로_바뀌면_항상_마스킹된다() {
        Secret s = Secret.of(FAKE_SECRET);
        assertThat(s.toString()).isEqualTo("****");
        assertThat(Secret.empty().toString()).isEmpty();
        assertThat(s.reveal()).isEqualTo(FAKE_SECRET);
    }

    @Test
    void 설정_전체를_로그로_찍어도_비밀값이_나오지_않는다() {
        RuntimeConfig config = new ConfigLoader().defaults();
        RuntimeConfig withKey = new RuntimeConfig(
                config.marketData(),
                config.lab(),
                config.notification(),
                new RuntimeConfig.Brokers(
                        new RuntimeConfig.BrokerKeys(Secret.of(FAKE_SECRET), Secret.of(FAKE_SECRET)),
                        config.brokers().kiwoom(),
                        config.brokers().toss()));
        assertThat(withKey.toString()).doesNotContain(FAKE_SECRET);
    }

    @Test
    void 일반_ObjectMapper로는_평문이_직렬화되지_않는다() throws Exception {
        assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(Secret.of(FAKE_SECRET)))
                .isInstanceOf(Exception.class)
                .hasMessageNotContaining(FAKE_SECRET);
        // Spring Boot 기본처럼 빈 객체 오류를 끈 mapper에서도 값이 나가지 않는다
        ObjectMapper lenient = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        assertThat(lenient.writeValueAsString(Secret.of(FAKE_SECRET))).doesNotContain(FAKE_SECRET);
    }
}
