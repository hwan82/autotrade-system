package com.autotrade.app.watchdog;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.ProcessStartup;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.NotificationQueue;
import com.autotrade.notification.NotificationSetup;
import com.autotrade.notification.Severity;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;

/**
 * 감시·킬스위치(D-013 ④, MON-02 1차 D-092·D-095). 상주 프로세스 하트비트와 드라이브 여유·PC CPU·메모리를 보고, 자원이 위험하면
 * 단발 일괄 명령 정지 요청 파일을 만든다. 어떤 프로세스도 죽이거나 띄우지 않는다(D-058). 상태가 바뀌면 설정의 알림 채널(Slack·카카오톡,
 * 없으면 로그)로 알린다(D-100). 실전 계좌 긴급 정지·청산 경로와 자동 재기동은 후속 작업이다(D-092 범위 밖).
 *
 * <p>감시 설정({@code <설정 폴더>/watchdog.properties})이 틀리거나 이 JVM이 PC CPU·메모리를 줄 수 없으면 기동을 거부한다(종료 코드 2).
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class WatchdogMain {

    static final String PROCESS = Watchdog.PROCESS;
    static final int EXIT_BAD_SETTINGS = 2;

    private static final Logger LOG = System.getLogger("com.autotrade." + PROCESS);

    private WatchdogMain() {}

    public static void main(String[] args) throws InterruptedException {
        Watchdog watchdog;
        try {
            watchdog = start(ConfigPaths.defaultLocation(), Clock.systemUTC());
        } catch (IllegalArgumentException e) {
            refuse(e);
            System.exit(EXIT_BAD_SETTINGS);
            return;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                watchdog.close();
            } finally {
                stopped.countDown();
            }
        }, "watchdog-shutdown"));
        stopped.await();
    }

    /** 기동 점검: 설정을 읽고 감시 설정을 검사만 한다. 종료 코드를 돌려준다(0: 정상, 2: 감시 설정 오류). */
    static int run(ConfigPaths paths, Clock clock) {
        return run(paths, clock, PlatformOsReadings::create);
    }

    static int run(ConfigPaths paths, Clock clock, Supplier<Optional<OsReadings>> osReadings) {
        ProcessStartup.start(PROCESS, paths, clock);
        try {
            WatchdogSettings.load(configDir(paths));
            os(osReadings);
            return 0;
        } catch (IllegalArgumentException e) {
            refuse(e);
            return EXIT_BAD_SETTINGS;
        }
    }

    /** 설정 로딩 → 감시 설정 검사(틀리면 던짐) → 점검 시작. */
    static Watchdog start(ConfigPaths paths, Clock clock) {
        return start(paths, clock, PlatformOsReadings::create);
    }

    static Watchdog start(ConfigPaths paths, Clock clock, Supplier<Optional<OsReadings>> osReadings) {
        LoadedConfig loaded = ProcessStartup.start(PROCESS, paths, clock);
        Path dir = configDir(paths);
        WatchdogSettings settings = WatchdogSettings.load(dir);
        // 지키는 설정 폴더를 남긴다. 잘못 적은 폴더면 모든 프로세스가 꺼짐으로만 보인다(architect PR #116 N2)
        OsReadings os = os(osReadings);
        NotificationQueue notifications = startNotifications(loaded.config(), dir, clock);
        LOG.log(Level.INFO, "감시 시작: 설정 폴더 {6}, 주기 {0}초, 하트비트 {1}({2}초 넘으면 멈춤), 드라이브 {3}, 여유 경고 {4}GB·위험 {5}GB",
                settings.interval().toSeconds(), settings.processes(), settings.heartbeatStale().toSeconds(), settings.disks(),
                Math.round(settings.diskWarnGb()), Math.round(settings.diskCriticalGb()), dir.toAbsolutePath());
        LOG.log(Level.INFO, "감시 자원 임계: CPU 경고 {0}%·위험 {1}%·연속 {2}회, 메모리 경고 {3}%·위험 {4}%·연속 {5}회",
                Math.round(settings.cpu().warn()), Math.round(settings.cpu().critical()), settings.cpu().sustain(),
                Math.round(settings.memory().warn()), Math.round(settings.memory().critical()), settings.memory().sustain());
        Watchdog watchdog;
        try {
            watchdog = new Watchdog(dir, settings, clock, DiskFreeSource.FILE_STORE, os, notifications::offer, notifications);
        } catch (RuntimeException e) {
            notifications.close();
            throw e;
        }
        notifications.offer(EventRoutes.WATCHDOG_STARTED, PROCESS, "감시 시작",
                "지키는 프로세스 " + settings.processes()
                        + (watchdog.stopRequests().active() ? ". 남아 있던 정지 요청 파일을 이어받음(자원이 정상이면 풀림)" : ""));
        watchdog.start();
        return watchdog;
    }

    /** 설정의 알림 값으로 채널을 꾸리고 보내는 스레드를 띄운다. 채널 설정이 틀려도 감시 기동을 막지 않는다(로그 채널은 늘 있음). */
    static NotificationQueue startNotifications(RuntimeConfig config, Path dir, Clock clock) {
        NotificationQueue queue = setup(config.notification()).start(dir, PROCESS, clock);
        LOG.log(Level.INFO, "알림 채널: 긴급 {0}, 정보 {1}", queue.notifier().channelNames(Severity.URGENT),
                queue.notifier().channelNames(Severity.INFO));
        if (!queue.notifier().hasExternalChannel(Severity.URGENT)) {
            // 엔진 멈춤(장애표 #8)을 사람이 늦게 알게 된다(ADR-005 표: 마지막 알림 채널 경고)
            LOG.log(Level.WARNING, "외부 알림 채널(Slack·카카오톡)이 없어 엔진 멈춤 같은 긴급 알림이 감시 로그에만 남습니다");
        }
        return queue;
    }

    /** 설정 값을 알림 모듈 값으로 옮긴다(알림 모듈은 설정 모듈을 모른다). 실전 엔진과 같은 값을 쓴다. */
    static NotificationSetup setup(RuntimeConfig.Notification n) {
        RuntimeConfig.Kakao k = n.kakao();
        return new NotificationSetup(n.slack().enabled(), n.slack().webhookUrl().reveal(), k.enabled(),
                k.mode() == KakaoMode.BIZ, k.restApiKey().reveal(), k.clientSecret().reveal(), k.accessToken().reveal(),
                k.refreshToken().reveal(), k.linkUrl());
    }

    /** PC CPU·메모리는 JDK OperatingSystemMXBean으로만 읽는다(D-095). 못 읽는 JVM이면 감시를 띄우지 않는다(던짐). */
    private static OsReadings os(Supplier<Optional<OsReadings>> osReadings) {
        return osReadings.get().orElseThrow(() -> new IllegalArgumentException(
                "이 JVM은 PC CPU·메모리 사용률을 줄 수 없습니다(com.sun.management.OperatingSystemMXBean 없음, D-095)"));
    }

    private static Path configDir(ConfigPaths paths) {
        return paths.runtimeFile().getParent();
    }

    private static void refuse(IllegalArgumentException e) {
        LOG.log(Level.ERROR, "감시 설정이 틀려 기동하지 않습니다: {0}", e.getMessage());
        System.err.println("감시 설정이 틀려 기동하지 않습니다(감시 없이 실전·실험을 돌리지 마세요): " + e.getMessage());
    }
}
