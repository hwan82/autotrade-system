package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.Secret;
import com.autotrade.monitor.HeartbeatWriter;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.NotificationQueue;
import com.autotrade.notification.NotificationSetup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchdogMainTest {

    @TempDir
    Path dir;

    private final Clock fixed = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void 설정_파일이_없어도_독립적으로_기동한다() {
        assertThat(WatchdogMain.run(ConfigPaths.in(dir), fixed)).isZero();
    }

    @Test
    void 감시_설정이_틀리면_기동을_거부한다() throws Exception {
        Files.writeString(dir.resolve(WatchdogSettings.FILE), "disk.free-gb=20:50:1\n");

        assertThat(WatchdogMain.run(ConfigPaths.in(dir), fixed)).isEqualTo(WatchdogMain.EXIT_BAD_SETTINGS);
        assertThatThrownBy(() -> WatchdogMain.start(ConfigPaths.in(dir), fixed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PC_CPU_메모리를_줄_수_없는_JVM이면_기동을_거부한다() {
        // D-095 4번. 기동 점검(run)과 실제 기동(start)이 같은 판정을 한다(PR #122 architect N4)
        assertThat(WatchdogMain.run(ConfigPaths.in(dir), fixed, java.util.Optional::empty)).isEqualTo(WatchdogMain.EXIT_BAD_SETTINGS);
        assertThatThrownBy(() -> WatchdogMain.start(ConfigPaths.in(dir), fixed, java.util.Optional::empty))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CPU·메모리").hasMessageContaining("D-095");
        assertThat(dir.resolve("heartbeat").resolve("watchdog.hb")).doesNotExist(); // 점검을 시작하지 않았다
    }

    @Test
    void 기동하면_점검을_시작하고_닫으면_자기_하트비트를_지운다() throws Exception {
        Watchdog w = WatchdogMain.start(ConfigPaths.in(dir), fixed);
        Path own = dir.resolve("heartbeat").resolve("watchdog.hb");
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(own) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(own).exists();
        } finally {
            w.close();
        }
        assertThat(own).doesNotExist();
    }
    @Test
    void 운영_배선으로_감시가_본_엔진_멈춤을_알림_큐에_넣는다() throws Exception {
        // PR #128 QA Q1(변이 M14): 감시가 Alerts.NONE을 연결하면 이 시험이 잡는다
        new HeartbeatWriter(dir, "engine", Clock.offset(fixed, java.time.Duration.ofMinutes(-1))).beat();
        Watchdog w = WatchdogMain.start(ConfigPaths.in(dir), fixed);
        try {
            assertThat(w.notifications()).isNotNull();
            assertThat(w.notifications().acceptedCount(EventRoutes.WATCHDOG_STARTED)).isEqualTo(1);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (w.notifications().acceptedCount(EventRoutes.PROCESS_STALE) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(w.notifications().acceptedCount(EventRoutes.PROCESS_STALE)).isEqualTo(1);
        } finally {
            w.close();
        }
    }

    @Test
    void 알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다() {
        // PR #128 architect R1: 문자열 값이 여럿이라 자리가 바뀌어도 컴파일된다. 값마다 다르게 넣어 제자리에 갔는지 본다
        RuntimeConfig.Notification n = new RuntimeConfig.Notification(
                new RuntimeConfig.Slack(true, Secret.of("SLACK-URL-PH")),
                new RuntimeConfig.Kakao(true, KakaoMode.ME, Secret.of("ACCESS-PH"), Secret.of("REST-PH"),
                        Secret.of("REFRESH-PH"), Secret.of("CLIENT-PH"), "http://link.example"));
        NotificationSetup.Builder expected = NotificationSetup.builder()
                .slackEnabled(true)
                .slackWebhookUrl("SLACK-URL-PH")
                .kakaoEnabled(true)
                .kakaoPaidMode(false)
                .kakaoRestApiKey("REST-PH")
                .kakaoClientSecret("CLIENT-PH")
                .kakaoAccessToken("ACCESS-PH")
                .kakaoRefreshToken("REFRESH-PH")
                .kakaoLinkUrl("http://link.example")
                .displayZone(ZoneId.of("Asia/Seoul"))
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP);
        assertThat(WatchdogMain.setup(n)).isEqualTo(expected.build());
        // 같은 값이라도 자리가 바뀌면 다르다(비교가 값을 실제로 본다)
        assertThat(WatchdogMain.setup(n)).isNotEqualTo(expected.kakaoRestApiKey("CLIENT-PH").kakaoClientSecret("REST-PH").build());
        // 재동의 문구는 새 토큰을 넣을 설정 키를 알려 준다(라이브러리 기본 문구가 아님)
        assertThat(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP).contains("notification.kakao.refresh-token")
                .isNotEqualTo(NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP);

        RuntimeConfig.Notification biz = new RuntimeConfig.Notification(new RuntimeConfig.Slack(false, null),
                new RuntimeConfig.Kakao(true, KakaoMode.BIZ, null, null, null, null, null));
        assertThat(WatchdogMain.setup(biz)).isEqualTo(NotificationSetup.builder()
                .kakaoEnabled(true)
                .kakaoPaidMode(true)
                .displayZone(ZoneId.of("Asia/Seoul"))
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP)
                .build());
    }
}
