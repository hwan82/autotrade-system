package com.autotrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 사용자에게 그대로 나가는 알림 고정값(PR #132 QA Q1). */
class RuntimeConfigNotificationTest {

    @Test
    void 재동의_안내는_토큰을_넣을_곳과_다시_시작할_프로세스와_그동안의_채널을_모두_알린다() {
        // 한 단계라도 빠지면 사람이 새 토큰을 넣고도 재기동하지 않아 카카오 알림이 꺼진 채로 남을 수 있다(변이 M15)
        assertThat(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP).isEqualTo(
                "카카오 리프레시 토큰이 거절돼 카카오톡으로 알림을 보내지 못합니다."
                        + " 카카오 로그인으로 다시 동의해 받은 리프레시 토큰을 secrets.json의 notification.kakao.refresh-token에 넣고"
                        + " 실전 엔진·감시를 다시 시작하세요. 그때까지 알림은 Slack·로그로만 갑니다");
        assertThat(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP).contains("다시 동의", "secrets.json",
                "notification.kakao.refresh-token", "실전 엔진·감시를 다시 시작하세요", "Slack·로그");
    }

    @Test
    void 알림_표시_시간대는_서울이다() {
        assertThat(RuntimeConfig.Notification.DISPLAY_ZONE).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
