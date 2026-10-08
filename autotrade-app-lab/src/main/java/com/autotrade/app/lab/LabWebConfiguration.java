package com.autotrade.app.lab;

import com.autotrade.app.lab.experiment.ExperimentCatalog;
import com.autotrade.app.lab.experiment.InMemoryExperimentCatalog;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 대시보드가 읽고 쓰는 저장소 연결(ADR-005). */
@Configuration(proxyBeanMethods = false)
public class LabWebConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LabWebConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    ExperimentCatalog experimentCatalog() {
        return new InMemoryExperimentCatalog();
    }

    @Bean
    StrategyProfileStore strategyProfileStore(
            @Value("${autotrade.lab.strategies-dir:strategies}") String dir, Clock clock) {
        StrategyProfileStore store = new StrategyProfileStore(Path.of(dir), clock);
        log.info("전략 프로필 폴더: {}", store.root());
        return store;
    }
}
