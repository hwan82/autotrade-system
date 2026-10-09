package com.autotrade.app.lab;

import com.autotrade.app.lab.experiment.ExperimentCatalog;
import com.autotrade.app.lab.experiment.InMemoryExperimentCatalog;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import com.autotrade.config.RuntimeConfigStore;
import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 대시보드가 읽고 쓰는 저장소 연결(ADR-005). */
@Configuration(proxyBeanMethods = false)
public class LabWebConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LabWebConfiguration.class);

    @Bean
    ExperimentCatalog experimentCatalog() {
        return new InMemoryExperimentCatalog();
    }

    /**
     * 실전 엔진 명령 채널 클라이언트(ADR-006). 엔진이 꺼져 있어도 실험실은 그대로 뜨고, 운영 화면만 "엔진 꺼짐"을 보여 준다.
     * 응답 대기 상한은 비밀번호 해시 검증(PBKDF2 600,000회)과 활성화 단계를 감안한 값이다 `[가정]`.
     */
    @Bean(destroyMethod = "close")
    ControlClient controlClient(RuntimeConfigStore store) {
        return new ControlClient(ControlSocket.path(store.paths()), Duration.ofSeconds(15));
    }

    @Bean
    StrategyProfileStore strategyProfileStore(
            @Value("${autotrade.lab.strategies-dir:strategies}") String dir, Clock clock) {
        StrategyProfileStore store = new StrategyProfileStore(Path.of(dir), clock);
        log.info("전략 프로필 폴더: {}", store.root());
        return store;
    }
}
