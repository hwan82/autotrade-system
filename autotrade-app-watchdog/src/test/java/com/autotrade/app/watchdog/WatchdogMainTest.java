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
        assertThatThrownBy(() -> WatchdogMain.start(ConfigPaths.in(dir), fixed)).isInstanceOf(WatchdogRefusal.class)
                .satisfies(e -> {
                    WatchdogMain.StartFailure f = WatchdogMain.StartFailure.of((RuntimeException) e);
                    assertThat(f.exitCode()).isEqualTo(WatchdogMain.EXIT_BAD_SETTINGS);
                    // 수집기 runbook 3.5가 이 문구로 찾게 한다
                    assertThat(f.headline()).isEqualTo("감시 설정이 틀려 기동하지 않습니다");
                    assertThat(f.detail()).contains("disk.free-gb");
                });
    }

    @Test
    void 설정_파일을_못_읽어도_설정_오류로_거부한다() throws Exception {
        Files.createDirectory(dir.resolve(WatchdogSettings.FILE)); // 파일 자리에 폴더

        assertThat(WatchdogMain.run(ConfigPaths.in(dir), fixed)).isEqualTo(WatchdogMain.EXIT_BAD_SETTINGS);
        assertThatThrownBy(() -> WatchdogMain.start(ConfigPaths.in(dir), fixed)).isInstanceOf(WatchdogRefusal.class)
                .hasMessageContaining("감시 설정 파일을 읽지 못했습니다");
    }

    @Test
    void 알림_채널_코드_실수_같은_예외는_설정_오류로_표시하지_않는다() {
        // PR #144 독립 QA R1(D-106): 채널 이름 검사 실패도 IllegalArgumentException이지만 사람이 고칠 설정 문제가 아니다
        IllegalArgumentException codeBug = new IllegalArgumentException("외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x");

        WatchdogMain.StartFailure f = WatchdogMain.StartFailure.of(codeBug);

        assertThat(f.exitCode()).isEqualTo(WatchdogMain.EXIT_START_FAILED).isNotEqualTo(WatchdogMain.EXIT_BAD_SETTINGS);
        assertThat(f.headline()).doesNotContain("설정이 틀려").contains("프로그램 오류").contains("설정 파일 문제가 아닙니다");
        assertThat(f.detail()).isEqualTo("IllegalArgumentException: 외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x");
    }

    @Test
    void 진입점은_설정_오류면_종료_코드_2로_끝내고_감시를_돌려주지_않는다() throws Exception {
        Files.writeString(dir.resolve(WatchdogSettings.FILE), "disk.free-gb=20:50:1\n");
        java.util.List<Integer> exits = new java.util.ArrayList<>();

        assertThat(WatchdogMain.launch(() -> ConfigPaths.in(dir), fixed, PlatformOsReadings::create, exits::add)).isNull();
        assertThat(exits).containsExactly(WatchdogMain.EXIT_BAD_SETTINGS);
    }

    @Test
    void 진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다() {
        // PR #148 architect N1·risk-ops R1: Windows에서 AUTOTRADE_CONFIG_DIR에 따옴표를 넣은 경우
        java.util.List<Integer> exits = new java.util.ArrayList<>();

        assertThat(WatchdogMain.launch(() -> {
            throw new java.nio.file.InvalidPathException("\"C:\\autotrade\"", "Illegal char <\"> at index 0");
        }, fixed, PlatformOsReadings::create, exits::add)).isNull();
        assertThat(exits).containsExactly(WatchdogMain.EXIT_BAD_SETTINGS);
    }

    @Test
    void 진입점은_설정_오류가_아닌_기동_예외를_종료_코드_1로_끝낸다() {
        // PR #148 risk-ops R2 변이 M4: 진입점이 IllegalArgumentException만 잡으면 아래 두 번째 경우가 빠져나간다
        java.util.List<Integer> exits = new java.util.ArrayList<>();

        assertThat(WatchdogMain.launch(() -> ConfigPaths.in(dir), fixed, () -> {
            throw new IllegalArgumentException("외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x");
        }, exits::add)).isNull();
        assertThat(WatchdogMain.launch(() -> ConfigPaths.in(dir), fixed, () -> {
            throw new IllegalStateException("코드 실수");
        }, exits::add)).isNull();
        assertThat(exits).containsExactly(WatchdogMain.EXIT_START_FAILED, WatchdogMain.EXIT_START_FAILED);
        assertThat(dir.resolve("heartbeat").resolve("watchdog.hb")).doesNotExist();
    }

    @Test
    void 기동_점검은_설정_오류가_아닌_예외를_종료_코드로_바꾸지_않고_던진다() {
        // PR #148 risk-ops R2 변이 M6
        assertThatThrownBy(() -> WatchdogMain.run(ConfigPaths.in(dir), fixed, () -> {
            throw new IllegalStateException("코드 실수");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 그_밖의_기동_예외도_프로그램_오류로_종료_코드_1이다() {
        WatchdogMain.StartFailure f = WatchdogMain.StartFailure.of(new IllegalStateException("이미 시작했습니다"));

        assertThat(f.exitCode()).isEqualTo(WatchdogMain.EXIT_START_FAILED);
        assertThat(f.detail()).isEqualTo("IllegalStateException: 이미 시작했습니다");
    }

    @Test
    void 기동_실패는_표준_오류에_첫머리_문구와_원인을_한_줄로_남긴다() {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        IllegalArgumentException codeBug = new IllegalArgumentException("외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x");
        WatchdogRefusal refusal = WatchdogRefusal.settings("감시 설정 오류(x): 1번째 줄: 모르는 키입니다(오타?): y");
        try {
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            WatchdogMain.StartFailure.of(codeBug).report(codeBug);
            WatchdogMain.StartFailure.of(refusal).report(refusal);
        } finally {
            System.setErr(original);
        }

        String err = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(err).contains("감시가 기동 중 프로그램 오류로 멈췄습니다")
                .contains("(감시 없이 실전·실험을 돌리지 마세요): IllegalArgumentException: 외부 채널 이름과 꾸린 채널 이름이 다르다")
                .contains("감시 설정이 틀려 기동하지 않습니다(감시 없이 실전·실험을 돌리지 마세요): 감시 설정 오류(x)");
    }

    @Test
    void PC_CPU_메모리를_줄_수_없는_JVM이면_기동을_거부한다() {
        // D-095 4번. 기동 점검(run)과 실제 기동(start)이 같은 판정을 한다(PR #122 architect N4)
        assertThat(WatchdogMain.run(ConfigPaths.in(dir), fixed, java.util.Optional::empty)).isEqualTo(WatchdogMain.EXIT_BAD_SETTINGS);
        assertThatThrownBy(() -> WatchdogMain.start(ConfigPaths.in(dir), fixed, java.util.Optional::empty))
                .isInstanceOf(WatchdogRefusal.class).hasMessageContaining("CPU·메모리").hasMessageContaining("D-095")
                .satisfies(e -> assertThat(WatchdogMain.StartFailure.of((RuntimeException) e).headline())
                        .isEqualTo("이 JVM에서는 감시가 기동하지 않습니다"));
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
