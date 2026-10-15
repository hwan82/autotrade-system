package com.autotrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 실험 정지 요청 파일 위치는 한 곳이 바뀌면 감시가 만드는 파일과 어긋난다. 이 파일을 보는 명령은 서로 의존할 수 없는 모듈에 있어
 * (빌드 격리) 상수를 공유하지 못하므로, 모든 모듈이 올라오는 이 모듈에서 값이 같은지 본다(D-085).
 */
class StopRequestPathTest {

    private static String constant(String className) throws ReflectiveOperationException {
        return constant(className, "STOP_REQUEST");
    }

    private static String constant(String className, String field) throws ReflectiveOperationException {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    void 실험_명령과_실험실_세션과_리플레이_점검은_같은_정지_요청_파일을_본다() throws ReflectiveOperationException {
        String experiment = constant("com.autotrade.app.experiment.ExperimentGate");

        assertThat(experiment).isEqualTo("experiment-state/stop-request");
        assertThat(constant("com.autotrade.app.lab.live.LiveLabService")).isEqualTo(experiment);
        assertThat(constant("com.autotrade.app.gateway.ReplayCheckGate")).isEqualTo(experiment);
    }

    /** 감시가 만드는 파일(D-092)과 명령이 보는 파일이 같아야 정지 순서 0번이 동작한다. */
    @Test
    void 감시가_만드는_정지_요청_파일은_명령들이_보는_파일과_같다() throws ReflectiveOperationException {
        String watchdog = "com.autotrade.app.watchdog.StopRequests";

        assertThat(constant(watchdog, "EXPERIMENT_STOP_REQUEST")).isEqualTo(constant("com.autotrade.app.experiment.ExperimentGate"));

        Class<?> options = Class.forName("com.autotrade.app.collector.CollectorMain$Options");
        Method parse = options.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
        Path configDir = Path.of("cfg").toAbsolutePath();
        Object o = parse.invoke(null, (Object) new String[] {"krx-daily", "--config-dir", configDir.toString()});
        Method stopRequest = options.getDeclaredMethod("stopRequest");
        stopRequest.setAccessible(true);
        Path collector = (Path) stopRequest.invoke(o);
        assertThat(configDir.relativize(collector).toString().replace('\\', '/'))
                .isEqualTo(constant(watchdog, "COLLECTOR_STOP_REQUEST"));
    }
}
