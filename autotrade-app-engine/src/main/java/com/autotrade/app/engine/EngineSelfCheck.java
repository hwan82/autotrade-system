package com.autotrade.app.engine;

import com.autotrade.config.RuntimeConfig;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.monitor.AtomicFile;
import com.autotrade.monitor.Heartbeat;
import com.autotrade.monitor.HeartbeatWriter;
import com.autotrade.monitor.SmallFile;
import com.autotrade.notification.EventRoutes;
import com.autotrade.risk.guard.GuardTransition;
import com.autotrade.risk.guard.RealTradingGuard;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 실전 엔진의 주기 자가 확인(D-142 C6·C11, 장애 테스트 3-R). 명령 채널 서버의 소켓·잠금 파일 확인({@code ControlServer})과 따로,
 * 엔진 단위로 두 가지를 본다.
 *
 * <ol>
 *   <li>두 엔진이 같이 도는지: 엔진마다 자기 PID 이름의 존재 파일({@code heartbeat/engine-<pid>.presence}, 하트비트 형식)을
 *       확인할 때마다 쓰고, 다른 살아 있는 엔진의 존재 파일이 {@link #FRESH} 안에 쓰였으면 두 엔진이 같이 돈다. 공용 하트비트
 *       ({@code engine.hb})는 두 엔진이 같은 파일을 덮어써 서로를 놓칠 수 있어(architect·risk-ops PR #240 R2) 존재 파일을 따로 둔다.
 *       잠금 파일과 run 폴더를 지워도 하트비트 폴더는 남으므로 두 번째 증거가 된다. 늦게 뜬 쪽이 명령 응답을 멈추고 소켓·잠금
 *       자리를 내주며({@code silence}) 자기 계좌를 모두 내린다(OPEN → 보호 모드, ACTIVATING → 출발 상태). 먼저 뜬 쪽은 그대로
 *       돌고 알리기만 한다. 시작 시각이 같으면 PID가 큰 쪽을 늦게 뜬 것으로 본다.</li>
 *   <li>실전 설정이 바뀌었는지: 계좌 목록·계좌번호·운영자 비밀번호 해시를 다시 읽어 마지막으로 본 값과 다르면 알린다. 엔진은 다시
 *       시작하기 전에는 새 값을 쓰지 않는다. 알림에 값은 싣지 않는다.</li>
 * </ol>
 *
 * <p>기동 전에는 {@link #otherEngine}로 공용 하트비트와 존재 파일을 보고, 다른 엔진이 돌고 있으면 기동하지 않는다(run 폴더를 지운 뒤의 두 번째
 * 엔진 차단).
 *
 * <p>어떤 확인 실패도 엔진을 멈추지 않는다. 같은 일은 한 번만 알린다.
 */
final class EngineSelfCheck implements AutoCloseable {

    /** 확인 주기. 하트비트 주기와 같다. */
    static final Duration INTERVAL = HeartbeatWriter.DEFAULT_INTERVAL;
    /** 이 안에 쓴 하트비트면 그 엔진이 돈다고 본다. 감시의 멈춤 기준(하트비트 주기의 세 배)과 같다. */
    static final Duration FRESH = HeartbeatWriter.DEFAULT_INTERVAL.multipliedBy(3);

    /** 존재 파일 이름 앞뒤({@code engine-<pid>.presence}). 감시가 읽는 {@code .hb}와 겹치지 않는다. */
    static final String PRESENCE_PREFIX = EngineMain.PROCESS + "-";
    static final String PRESENCE_SUFFIX = ".presence";

    private static final Logger LOG = System.getLogger("com.autotrade." + EngineMain.PROCESS);

    /** 알림 하나(종류, 제목, 본문). 큐에 넣기만 하고 빨리 돌아와야 한다. */
    interface Alert {
        void send(String kind, String title, String body);
    }

    private final Path configDir;
    private final long myPid;
    private final Instant myStartedAt;
    private final Clock clock;
    private final RealTradingGuard guard;
    private final Consumer<String> silencer;
    private final Supplier<RuntimeConfig.RealTrading> currentConfig;
    private final Alert alert;
    private final Object lock = new Object();
    private RuntimeConfig.RealTrading lastConfig;
    private long reportedPid = -1;
    private boolean silenced;
    private boolean configUnreadable;
    private long presenceSeq;
    private ScheduledExecutorService scheduler;

    /**
     * @param myStartedAt 이 엔진이 뜬 시각. 두 엔진 중 누가 늦게 떴는지 하트비트의 시작 시각과 견준다
     * @param silencer 명령 채널 응답을 멈추게 하는 쪽(이유를 받음)
     * @param startConfig 기동 때 쓴 실전 설정
     * @param currentConfig 지금 파일의 실전 설정을 읽는다. 던지면 "읽히지 않음"으로 알린다
     */
    EngineSelfCheck(Path configDir, long myPid, Instant myStartedAt, Clock clock, RealTradingGuard guard,
            Consumer<String> silencer, RuntimeConfig.RealTrading startConfig,
            Supplier<RuntimeConfig.RealTrading> currentConfig, Alert alert) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.myPid = myPid;
        this.myStartedAt = Objects.requireNonNull(myStartedAt, "myStartedAt");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.silencer = Objects.requireNonNull(silencer, "silencer");
        this.lastConfig = Objects.requireNonNull(startConfig, "startConfig");
        this.currentConfig = Objects.requireNonNull(currentConfig, "currentConfig");
        this.alert = Objects.requireNonNull(alert, "alert");
    }

    /**
     * 이 PID가 아닌 살아 있는 엔진이 {@link #FRESH} 안에 공용 하트비트나 존재 파일을 썼으면 그중 가장 먼저 뜬 엔진의 기록. 없거나,
     * 읽히지 않거나, 오래됐거나, 쓴 프로세스가 없으면 빈 값(잠금 파일 검사가 그다음을 맡는다). 죽은 프로세스의 존재 파일은 지운다.
     */
    static Optional<Heartbeat> otherEngine(Path configDir, long myPid, Clock clock) {
        List<Heartbeat> others = new ArrayList<>();
        Instant now = clock.instant();
        consider(Heartbeat.file(configDir, EngineMain.PROCESS), myPid, now, false, others);
        Path dir = configDir.resolve(Heartbeat.DIR);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, PRESENCE_PREFIX + "*" + PRESENCE_SUFFIX)) {
                for (Path file : files) {
                    consider(file, myPid, now, true, others);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "엔진 존재 파일을 읽지 못했습니다(이번에는 공용 하트비트만 봄): {0}",
                        e.getClass().getSimpleName());
            }
        }
        return others.stream().min(Comparator.comparing(Heartbeat::startedAt).thenComparingLong(Heartbeat::pid));
    }

    private static void consider(Path file, long myPid, Instant now, boolean presence, List<Heartbeat> others) {
        String text;
        try {
            text = SmallFile.read(file);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "엔진 하트비트를 읽지 못했습니다(이 파일은 이번에 건너뜀): {0}", e.getClass().getSimpleName());
            return;
        }
        Optional<Heartbeat> hb = Heartbeat.parse(text);
        if (hb.isEmpty() || hb.get().pid() == myPid) {
            return;
        }
        boolean alive;
        try {
            alive = ProcessHandle.of(hb.get().pid()).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException e) {
            // 프로세스 정보를 볼 수 없으면 도는 것으로 본다(두 번째 엔진을 막는 쪽)
            alive = true;
        }
        if (!alive) {
            if (presence) {
                deleteQuietly(file);
            }
            return;
        }
        if (!hb.get().writtenAt().isBefore(now.minus(FRESH))) {
            others.add(hb.get());
        }
    }

    /** 이 엔진의 존재 파일 위치. */
    static Path presenceFile(Path configDir, long pid) {
        return configDir.resolve(Heartbeat.DIR).resolve(PRESENCE_PREFIX + pid + PRESENCE_SUFFIX);
    }

    private void writePresence() {
        try {
            AtomicFile.write(presenceFile(configDir, myPid),
                    new Heartbeat(EngineMain.PROCESS, myPid, myStartedAt, clock.instant(), ++presenceSeq).format());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "엔진 존재 파일을 쓰지 못했습니다: {0}", e.getClass().getSimpleName());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            // 다음 확인 때 다시 시도한다
        }
    }

    /** 주기 확인을 시작한다(데몬 스레드 하나). */
    void start(Duration interval) {
        synchronized (lock) {
            if (scheduler != null) {
                throw new IllegalStateException("이미 시작했습니다");
            }
            writePresence();
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("engine-self-check").daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::checkQuietly, interval.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void checkQuietly() {
        try {
            check();
        } catch (Throwable e) {
            // 예약 작업이 Throwable로 끝나면 다음 주기가 조용히 취소된다
        }
    }

    /** 한 번 확인한다. */
    void check() {
        synchronized (lock) {
            writePresence();
            checkOtherEngine();
            checkRealConfig();
        }
    }

    private void checkOtherEngine() {
        Optional<Heartbeat> other = otherEngine(configDir, myPid, clock);
        if (other.isEmpty() || other.get().pid() == reportedPid) {
            return;
        }
        Heartbeat hb = other.get();
        reportedPid = hb.pid();
        boolean iAmLate = hb.startedAt().isBefore(myStartedAt)
                || hb.startedAt().equals(myStartedAt) && hb.pid() < myPid;
        if (iAmLate) {
            if (!silenced) {
                silenced = true;
                silencer.accept("먼저 뜬 실전 엔진(PID " + hb.pid() + ")이 돌고 있습니다");
                for (AccountRef account : guard.accounts()) {
                    guard.deactivate(account.id(), GuardTransition.SYSTEM, "두 실전 엔진이 같이 돎(늦게 뜬 엔진)");
                }
            }
            send(EventRoutes.CONTROL_CHANNEL, "실전 엔진이 두 개 돕니다",
                    "먼저 뜬 엔진(PID " + hb.pid() + ")이 있어 이 엔진(PID " + myPid + ")은 명령 응답을 멈추고 계좌를 모두 내렸습니다."
                            + " 이 엔진을 끄세요");
        } else {
            send(EventRoutes.CONTROL_CHANNEL, "실전 엔진이 두 개 돕니다",
                    "뒤에 뜬 엔진(PID " + hb.pid() + ")이 하트비트를 씁니다. 뒤에 뜬 엔진은 명령 응답을 멈춥니다. 그 엔진을 끄세요");
        }
    }

    private void checkRealConfig() {
        RuntimeConfig.RealTrading now;
        try {
            now = currentConfig.get();
        } catch (RuntimeException e) {
            if (!configUnreadable) {
                configUnreadable = true;
                send(EventRoutes.REAL_CONFIG_CHANGED, "실전 설정을 읽지 못했습니다",
                        "설정 파일을 다시 읽지 못했습니다(" + e.getClass().getSimpleName() + "). 엔진은 기동 때 값으로 계속 돕니다");
            }
            return;
        }
        configUnreadable = false;
        if (Objects.requireNonNull(now, "실전 설정").equals(lastConfig)) {
            return;
        }
        lastConfig = now;
        send(EventRoutes.REAL_CONFIG_CHANGED, "실전 설정이 바뀌었습니다",
                "실전 계좌 목록·계좌번호·운영자 비밀번호 해시 중 무언가가 바뀌었습니다(파일이 깨져 비었을 수도 있습니다)."
                        + " 직접 바꾼 것이 아니면 확인하세요. 엔진은 다시 시작하기 전에는 새 값을 쓰지 않습니다");
    }

    private void send(String kind, String title, String body) {
        try {
            alert.send(kind, title, body);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "자가 확인 알림을 넣지 못했습니다: {0}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            deleteQuietly(presenceFile(configDir, myPid));
        }
    }
}
