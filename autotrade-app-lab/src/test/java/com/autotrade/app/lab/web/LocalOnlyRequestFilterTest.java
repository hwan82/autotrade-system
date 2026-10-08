package com.autotrade.app.lab.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalOnlyRequestFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.1:8480", "localhost", "LOCALHOST:8480", "[::1]", "[::1]:8480"})
    void 루프백_Host는_허용한다(String host) {
        assertThat(LocalOnlyRequestFilter.isLoopbackHost(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "evil.example", "evil.example:8480", "127.0.0.1.evil.example", "localhost.evil:80",
            "127.0.0.2", "0.0.0.0:8480", "[::1", "[::2]:80", "localhost:", "localhost:80a", "localhost:1234567",
            "localhost@evil.example"})
    void 그_밖의_Host는_거부한다_DNS_rebinding_방지(String host) {
        assertThat(LocalOnlyRequestFilter.isLoopbackHost(host)).isFalse();
    }

    @Test
    void Origin은_루프백_출처만_허용한다() {
        assertThat(LocalOnlyRequestFilter.isLoopbackOrigin("http://127.0.0.1:8480")).isTrue();
        assertThat(LocalOnlyRequestFilter.isLoopbackOrigin("http://localhost:8480")).isTrue();
        assertThat(LocalOnlyRequestFilter.isLoopbackOrigin("https://evil.example")).isFalse();
        assertThat(LocalOnlyRequestFilter.isLoopbackOrigin("null")).isFalse();
        assertThat(LocalOnlyRequestFilter.isLoopbackOrigin("file://localhost")).isFalse();
        assertThat(LocalOnlyRequestFilter.isLoopbackOrigin("http://localhost:8480/path")).isFalse();
        assertThat(LocalOnlyRequestFilter.isLoopbackOrigin("http://user@localhost:8480")).isFalse();
    }

    @Test
    void CSRF_토큰은_짧으면_만들지_않고_문자열로_새지_않는다() {
        assertThatThrownBy(() -> new LocalOnlyRequestFilter.CsrfToken("short")).isInstanceOf(IllegalArgumentException.class);
        LocalOnlyRequestFilter.CsrfToken token = new LocalOnlyRequestFilter.CsrfToken("a".repeat(64));
        assertThat(token.toString()).doesNotContain("aaaa");
        assertThat(token.matches("a".repeat(64))).isTrue();
        assertThat(token.matches("a".repeat(63))).isFalse();
        assertThat(token.matches(null)).isFalse();
    }

    @Test
    void 루프백이_아닌_바인딩_주소면_기동을_거부한다() {
        assertThatThrownBy(() -> new WebConfiguration().loopbackBindingCheck("0.0.0.0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebConfiguration().loopbackBindingCheck(""))
                .isInstanceOf(IllegalStateException.class);
        new WebConfiguration().loopbackBindingCheck("127.0.0.1");
        new WebConfiguration().loopbackBindingCheck("::1");
    }
}
