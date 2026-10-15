package com.autotrade.app.lab.monitor;

import com.autotrade.monitorsources.MetricNames;
import com.autotrade.monitorsources.StandardSources;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실험실 모니터링 연결(MON-05, D-090). 속성은 docs/config/config-keys.md "실험실 프로세스 속성". */
@Configuration(proxyBeanMethods = false)
public class MonitorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitorConfiguration.class);

    /** 설정을 읽은 결과. 잘못됐으면 모니터링은 꺼진 설정과 그 이유를 갖는다. */
    record Loaded(MonitorSettings settings, String problem) {}

    /** 꺼진 기본 설정(설정이 잘못됐을 때 화면 표시용 주기·기록 크기). */
    static final MonitorSettings FALLBACK = new MonitorSettings(false, 5, 720, Set.of(), List.of());

    @Bean
    Loaded monitorSettings(@Value("${autotrade.lab.monitor.enabled:true}") String enabled,
            @Value("${autotrade.lab.monitor.interval-seconds:5}") String intervalSeconds,
            @Value("${autotrade.lab.monitor.history-size:720}") String historySize,
            @Value("${autotrade.lab.monitor.disks:}") String disks,
            @Value("${autotrade.lab.monitor.thresholds:}") String thresholds) {
        return load(enabled, intervalSeconds, historySize, disks, thresholds);
    }

    /**
     * 모니터링 속성을 읽는다. 형식·범위·지표 이름이 틀려도 실험실 기동을 막지 않고 모니터링만 "사용 불가"로 둔다. 실험실은
     * 실전 운영 화면(내리기·PANIC SELL)을 싣는 프로세스라 장중에 임계를 고쳐 다시 띄우다 오타가 나도 그 화면은 떠야 한다(QA PR
     * #114 R1). 오류는 화면 문구와 경고 로그로 알린다.
     */
    static Loaded load(String enabled, String intervalSeconds, String historySize, String disks, String thresholds) {
        try {
            return new Loaded(new MonitorSettings(parseBoolean(enabled), parseInt(intervalSeconds, "interval-seconds"),
                    parseInt(historySize, "history-size"), MonitorSettings.parseDisks(disks),
                    MonitorSettings.parseThresholds(thresholds, MetricNames.ALL)), null);
        } catch (IllegalArgumentException e) {
            log.warn("모니터링 설정이 잘못돼 모니터링을 켜지 않고 기동합니다: {}", e.getMessage());
            return new Loaded(FALLBACK, "모니터링 설정이 잘못됐습니다: " + e.getMessage()
                    + ". 고친 뒤 실험실을 다시 띄우세요. 실험실의 다른 기능은 그대로 동작합니다");
        }
    }

    private static boolean parseBoolean(String text) {
        String v = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("false")) {
            return v.equals("true");
        }
        throw new IllegalArgumentException("autotrade.lab.monitor.enabled는 true 또는 false여야 합니다: " + text);
    }

    private static int parseInt(String text, String key) {
        try {
            return Integer.parseInt(text == null ? "" : text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("autotrade.lab.monitor." + key + "는 정수여야 합니다: " + text);
        }
    }

    @Bean
    MonitorAgentFactory monitorAgentFactory(Clock clock) {
        return settings -> StandardSources.agent(clock, settings.diskFilter(), settings.thresholds(), settings.historySize(),
                MonitorSettings.EVENT_HISTORY);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    MonitorService monitorService(Loaded loaded, MonitorAgentFactory factory, Clock clock) {
        return loaded.problem() == null ? create(loaded.settings(), factory, clock)
                : new MonitorService(loaded.settings(), null, clock, loaded.problem());
    }

    /**
     * 종료 때 열린 SSE를 웹 서버 graceful shutdown보다 먼저 닫는다. 닫지 않으면 graceful shutdown이 SSE를 진행 중 요청으로
     * 보고 30초를 기다려 실험실(실전 운영 화면 포함) 재기동이 그만큼 늦어진다(QA PR #114 R2). SmartLifecycle은 위상이 높은
     * 것부터 멈추고, 기본 위상은 graceful shutdown 위상(기본 - 1024)보다 높다.
     */
    @Bean
    SmartLifecycle monitorStreamCloser(MonitorService service) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                service.closeStreams();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
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
