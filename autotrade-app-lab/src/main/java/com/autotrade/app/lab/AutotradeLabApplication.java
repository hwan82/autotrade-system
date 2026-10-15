package com.autotrade.app.lab;

import com.autotrade.config.RuntimeConfigStore;
import com.autotrade.monitor.HeartbeatWriter;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/** 시뮬레이션 실험실 프로세스 진입점(D-013의 ③). 실전 주문 경로는 이 프로세스에 두지 않는다. */
@SpringBootApplication
public class AutotradeLabApplication {

    static final String PROCESS = "lab";

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AutotradeLabApplication.class, args);
        startHeartbeat(context);
    }

    /**
     * 감시가 읽는 하트비트를 시작하고 컨텍스트가 닫힐 때 지운다(MON-02, D-092). 빈이 아니라 진입점에서 시작해서, 스프링 시험이
     * 실제 설정 폴더의 실험실 하트비트를 덮어쓰거나 지우지 않게 한다.
     */
    static HeartbeatWriter startHeartbeat(ConfigurableApplicationContext context) {
        RuntimeConfigStore store = context.getBean(RuntimeConfigStore.class);
        HeartbeatWriter heartbeat = HeartbeatWriter.start(store.paths().runtimeFile().getParent(), PROCESS,
                context.getBean(Clock.class), HeartbeatWriter.DEFAULT_INTERVAL);
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) {
                heartbeat.close();
            }
        });
        return heartbeat;
    }
}
