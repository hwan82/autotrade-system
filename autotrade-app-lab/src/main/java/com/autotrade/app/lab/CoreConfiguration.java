package com.autotrade.app.lab;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.RuntimeConfigHolder;
import com.autotrade.config.RuntimeConfigStore;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시계와 설정 체계 연결. 이 프로세스에서 시스템 시계를 만드는 곳은 여기 하나다(ArchitectureTest가 강제).
 */
@Configuration(proxyBeanMethods = false)
public class CoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CoreConfiguration.class);

    /** 실시간 기본 시계(UTC). 시장 현지 시간은 MarketRules.zone()으로 바꿔 쓴다. 리플레이 실행은 ReplayClock을 주입한다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RuntimeConfigStore runtimeConfigStore(@Value("${autotrade.config-dir:}") String configDir) {
        ConfigPaths paths = configDir.isBlank() ? ConfigPaths.defaultLocation() : ConfigPaths.in(Path.of(configDir));
        return new RuntimeConfigStore(paths);
    }

    @Bean
    LoadedConfig loadedConfig(RuntimeConfigStore store) {
        LoadedConfig loaded = new ConfigLoader().load(store.paths());
        if (!loaded.warning().isEmpty()) {
            log.warn("설정 파일 문제: {}", loaded.warning());
        }
        log.info("설정 로딩: {} ({})", loaded.source(), store.paths().runtimeFile().getParent());
        return loaded;
    }

    @Bean
    RuntimeConfigHolder runtimeConfigHolder(LoadedConfig loaded, RuntimeConfigStore store) {
        return new RuntimeConfigHolder(loaded.config(), store);
    }
}
