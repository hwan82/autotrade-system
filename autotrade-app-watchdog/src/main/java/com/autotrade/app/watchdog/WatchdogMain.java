package com.autotrade.app.watchdog;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.ProcessStartup;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * 감시·킬스위치(D-013 ④, MON-02 1차 D-092). 상주 프로세스 하트비트와 드라이브 여유를 보고, 자원이 위험하면 단발 일괄 명령 정지 요청
 * 파일을 만든다. 어떤 프로세스도 죽이거나 띄우지 않는다(D-058). 실전 계좌 긴급 정지·청산 경로, CPU·메모리 감시, 자동 재기동,
 * 알림 발송은 후속 작업이다(D-092 범위 밖).
 *
 * <p>감시 설정({@code <설정 폴더>/watchdog.properties})이 틀리면 기동을 거부한다(종료 코드 2).
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
            watchdog.close();
            stopped.countDown();
        }, "watchdog-shutdown"));
        stopped.await();
    }

    /** 기동 점검: 설정을 읽고 감시 설정을 검사만 한다. 종료 코드를 돌려준다(0: 정상, 2: 감시 설정 오류). */
    static int run(ConfigPaths paths, Clock clock) {
        ProcessStartup.start(PROCESS, paths, clock);
        try {
            WatchdogSettings.load(configDir(paths));
            return 0;
        } catch (IllegalArgumentException e) {
            refuse(e);
            return EXIT_BAD_SETTINGS;
        }
    }

    /** 설정 로딩 → 감시 설정 검사(틀리면 던짐) → 점검 시작. */
    static Watchdog start(ConfigPaths paths, Clock clock) {
        ProcessStartup.start(PROCESS, paths, clock);
        Path dir = configDir(paths);
        WatchdogSettings settings = WatchdogSettings.load(dir);
        // 지키는 설정 폴더를 남긴다. 잘못 적은 폴더면 모든 프로세스가 꺼짐으로만 보인다(architect PR #116 N2)
        LOG.log(Level.INFO, "감시 시작: 설정 폴더 {6}, 주기 {0}초, 하트비트 {1}({2}초 넘으면 멈춤), 드라이브 {3}, 여유 경고 {4}GB·위험 {5}GB",
                settings.interval().toSeconds(), settings.processes(), settings.heartbeatStale().toSeconds(), settings.disks(),
                Math.round(settings.diskWarnGb()), Math.round(settings.diskCriticalGb()), dir.toAbsolutePath());
        Watchdog watchdog = new Watchdog(dir, settings, clock, DiskFreeSource.FILE_STORE);
        watchdog.start();
        return watchdog;
    }

    private static Path configDir(ConfigPaths paths) {
        return paths.runtimeFile().getParent();
    }

    private static void refuse(IllegalArgumentException e) {
        LOG.log(Level.ERROR, "감시 설정이 틀려 기동하지 않습니다: {0}", e.getMessage());
        System.err.println("감시 설정이 틀려 기동하지 않습니다(감시 없이 실전·실험을 돌리지 마세요): " + e.getMessage());
    }
}
