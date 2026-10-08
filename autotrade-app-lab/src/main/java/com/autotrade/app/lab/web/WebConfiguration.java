package com.autotrade.app.lab.web;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 로컬 대시보드 웹 계층 연결(ADR-005). */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration {

    @Bean
    LocalOnlyRequestFilter.CsrfToken csrfToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new LocalOnlyRequestFilter.CsrfToken(HexFormat.of().formatHex(bytes));
    }

    @Bean
    FilterRegistrationBean<LocalOnlyRequestFilter> localOnlyRequestFilter(LocalOnlyRequestFilter.CsrfToken token) {
        FilterRegistrationBean<LocalOnlyRequestFilter> bean = new FilterRegistrationBean<>(new LocalOnlyRequestFilter(token));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 루프백이 아닌 주소에 바인딩하려 하면 기동을 멈춘다(D-020 3번). 외부 접근은 인증 설계와 함께 새 결정으로만 연다.
     * 비워 두면 Spring이 모든 주소(0.0.0.0)에 바인딩하므로 빈 값도 거부한다.
     */
    @Bean
    LoopbackBindingCheck loopbackBindingCheck(@Value("${server.address:}") String address) {
        return new LoopbackBindingCheck(address);
    }

    public static final class LoopbackBindingCheck {
        LoopbackBindingCheck(String address) {
            if (address == null || address.isBlank()) {
                throw new IllegalStateException("server.address가 비어 있습니다. 로컬 전용이므로 127.0.0.1로 둡니다(D-020)");
            }
            try {
                if (!InetAddress.getByName(address.strip()).isLoopbackAddress()) {
                    throw new IllegalStateException("server.address는 루프백 주소만 허용합니다(D-020): " + address);
                }
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException("server.address를 해석할 수 없습니다: " + address, e);
            }
        }
    }
}
