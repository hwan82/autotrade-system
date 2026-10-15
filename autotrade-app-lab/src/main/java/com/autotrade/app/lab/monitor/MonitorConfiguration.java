package com.autotrade.app.lab.monitor;

import com.autotrade.monitorsources.MetricNames;
import com.autotrade.monitorsources.StandardSources;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실험실 모니터링 연결(MON-05, D-090). 속성은 docs/config/config-keys.md "실험실 프로세스 속성". */
@Configuration(proxyBeanMethods = false)
public class MonitorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitorConfiguration.class);

    @Bean
    MonitorSettings monitorSettings(@Value("${autotrade.lab.monitor.enabled:true}") boolean enabled,
            @Value("${autotrade.lab.monitor.interval-seconds:5}") int intervalSeconds,
            @Value("${autotrade.lab.monitor.history-size:720}") int historySize,
            @Value("${autotrade.lab.monitor.disks:}") String disks,
            @Value("${autotrade.lab.monitor.thresholds:}") String thresholds) {
        return new MonitorSettings(enabled, intervalSeconds, historySize, MonitorSettings.parseDisks(disks),
                MonitorSettings.parseThresholds(thresholds, MetricNames.ALL));
    }

    @Bean
    MonitorAgentFactory monitorAgentFactory(Clock clock) {
        return settings -> StandardSources.agent(clock, settings.diskFilter(), settings.thresholds(), settings.historySize(),
                MonitorSettings.EVENT_HISTORY);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    MonitorService monitorService(MonitorSettings settings, MonitorAgentFactory factory, Clock clock) {
        return create(settings, factory, clock);
    }

    /**
     * 에이전트를 만들지 못해도(예: OSHI 네이티브 적재 실패) 실험실은 뜬다. 모니터링만 "사용 불가"가 된다. 실험실은 실전 운영
     * 화면(내리기·PANIC SELL)을 함께 싣는 프로세스라 화면용 부가 기능이 그 기동을 막으면 안 된다(risk-ops PR #114 R1).
     */
    static MonitorService create(MonitorSettings settings, MonitorAgentFactory factory, Clock clock) {
        if (!settings.enabled()) {
            return new MonitorService(settings, null, clock, null);
        }
        try {
            return new MonitorService(settings, factory.create(settings), clock, null);
        } catch (RuntimeException | LinkageError e) {
            log.warn("모니터링 수집원을 만들지 못해 모니터링 없이 기동합니다: {}", e.getClass().getSimpleName());
            return new MonitorService(settings, null, clock, "수집원을 만들지 못했습니다(" + e.getClass().getSimpleName()
                    + "). 실험실 로그를 확인하세요. 실험실의 다른 기능은 그대로 동작합니다");
        }
    }
}
