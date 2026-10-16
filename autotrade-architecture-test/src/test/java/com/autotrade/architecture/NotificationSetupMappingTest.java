package com.autotrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.engine.EngineNotificationMapping;
import com.autotrade.app.lab.settings.LabNotificationMapping;
import com.autotrade.app.watchdog.WatchdogNotificationMapping;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.Secret;
import com.autotrade.notification.NotificationSetup;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 같은 알림 설정이면 실전 엔진·감시·실험실 "알림 시험 보내기"가 같은 {@link NotificationSetup}을 만든다(PR #138 architect R1, D-103
 * K4). 세 프로세스는 서로를 참조할 수 없어 설정 옮기기가 세 곳에 따로 있다. 각 모듈 시험은 손으로 쓴 기대값과 비교하므로, 한 곳만
 * 바뀌어도 다른 곳 시험은 깨지지 않는다. 세 모듈이 함께 올라오는 이 모듈에서 세 결과를 직접 비교해 "실험실 시험 성공 = 엔진·감시
 * 발송 가능"의 전제를 고정한다. 비밀값은 자리표시자다.
 */
class NotificationSetupMappingTest {

    @Test
    void 같은_알림_설정이면_엔진_감시_실험실이_같은_값으로_옮긴다() {
        for (RuntimeConfig.Notification n : configs()) {
            NotificationSetup engine = EngineNotificationMapping.setup(n);
            assertThat(WatchdogNotificationMapping.setup(n)).isEqualTo(engine);
            assertThat(LabNotificationMapping.setup(n)).isEqualTo(engine);
        }
    }

    @Test
    void 설정이_다르면_옮긴_결과도_다르다() {
        // 비교가 값을 실제로 본다(모든 설정이 같은 값으로 옮겨져도 위 시험이 통과하지 않게)
        List<RuntimeConfig.Notification> configs = configs();
        for (int i = 0; i < configs.size(); i++) {
            for (int j = i + 1; j < configs.size(); j++) {
                assertThat(EngineNotificationMapping.setup(configs.get(i)))
                        .as("설정 %d와 %d", i, j)
                        .isNotEqualTo(EngineNotificationMapping.setup(configs.get(j)));
            }
        }
    }

    /** 값마다 다른 자리표시자를 넣어 자리가 바뀌면 드러나게 한 설정들. */
    private static List<RuntimeConfig.Notification> configs() {
        return List.of(
                new RuntimeConfig.Notification(new RuntimeConfig.Slack(true, Secret.of("SLACK-URL-PH")),
                        new RuntimeConfig.Kakao(true, KakaoMode.ME, Secret.of("ACCESS-PH"), Secret.of("REST-PH"),
                                Secret.of("REFRESH-PH"), Secret.of("CLIENT-PH"), "http://link.example")),
                // 액세스 토큰·클라이언트 시크릿 없이
                new RuntimeConfig.Notification(new RuntimeConfig.Slack(true, Secret.of("SLACK-URL-PH")),
                        new RuntimeConfig.Kakao(true, KakaoMode.ME, null, Secret.of("REST-PH"), Secret.of("REFRESH-PH"),
                                null, "http://link.example")),
                // 알림톡(BIZ)
                new RuntimeConfig.Notification(new RuntimeConfig.Slack(false, null),
                        new RuntimeConfig.Kakao(true, KakaoMode.BIZ, null, null, null, null, null)),
                // 카카오만 꺼짐
                new RuntimeConfig.Notification(new RuntimeConfig.Slack(true, Secret.of("SLACK-URL-PH")),
                        new RuntimeConfig.Kakao(false, KakaoMode.ME, null, null, null, null, null)),
                // 모두 꺼짐
                new RuntimeConfig.Notification(new RuntimeConfig.Slack(false, null),
                        new RuntimeConfig.Kakao(false, KakaoMode.ME, null, null, null, null, null)));
    }
}
