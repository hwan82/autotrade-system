package com.autotrade.app.lab.monitor;

import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitorsources.MetricNames;
import com.autotrade.monitorsources.StandardSources;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실험실 모니터링 연결(MON-05, D-090). 속성은 docs/config/config-keys.md "실험실 프로세스 속성". */
@Configuration(proxyBeanMethods = false)
public class MonitorConfiguration {

    @Bean
    MonitorSettings monitorSettings(@Value("${autotrade.lab.monitor.enabled:true}") boolean enabled,
            @Value("${autotrade.lab.monitor.interval-seconds:5}") int intervalSeconds,
            @Value("${autotrade.lab.monitor.history-size:720}") int historySize,
            @Value("${autotrade.lab.monitor.disks:}") String disks,
            @Value("${autotrade.lab.monitor.thresholds:}") String thresholds) {
        return new MonitorSettings(enabled, intervalSeconds, historySize, MonitorSettings.parseDisks(disks),
                MonitorSettings.parseThresholds(thresholds, MetricNames.ALL));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    MonitorService monitorService(MonitorSettings settings, Clock clock) {
        MonitorAgent agent = settings.enabled()
                ? StandardSources.agent(clock, settings.diskFilter(), settings.thresholds(), settings.historySize(),
                        MonitorSettings.EVENT_HISTORY)
                : null;
        return new MonitorService(settings, agent);
    }
}
