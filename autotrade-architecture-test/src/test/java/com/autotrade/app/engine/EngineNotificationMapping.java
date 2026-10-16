package com.autotrade.app.engine;

import com.autotrade.config.RuntimeConfig;
import com.autotrade.notification.NotificationSetup;

/**
 * 시험 전용 다리: 실전 엔진의 알림 설정 옮기기({@code EngineMain.setup}, 패키지 전용)를 같은 패키지에서 불러
 * {@code NotificationSetupMappingTest}가 세 곳의 결과를 한 시험에서 비교하게 한다(PR #138 architect R1). 운영 코드의 공개 범위를
 * 넓히지 않고 리플렉션도 쓰지 않는다.
 */
public final class EngineNotificationMapping {

    private EngineNotificationMapping() {}

    public static NotificationSetup setup(RuntimeConfig.Notification n) {
        return EngineMain.setup(n);
    }
}
