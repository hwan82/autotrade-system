package com.autotrade.app.engine;

import com.autotrade.app.engine.command.ActivationStepRunner;
import com.autotrade.app.engine.command.CommandAuditLog;
import com.autotrade.app.engine.command.NotifyingCommandAuditLog;
import com.autotrade.app.engine.command.EngineCommandHandler;
import com.autotrade.app.engine.command.OperatorAuthenticator;
import com.autotrade.app.engine.command.OperatorPasswordHash;
import com.autotrade.app.engine.command.PanicLiquidator;
import com.autotrade.app.engine.order.LoggingGuardAuditLog;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.ProcessStartup;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.control.ControlServer;
import com.autotrade.control.ControlSocket;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.monitor.HeartbeatWriter;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.NotificationQueue;
import com.autotrade.notification.NotificationSetup;
import com.autotrade.notification.Severity;
import com.autotrade.risk.guard.RealTradingGuard;
import java.io.Console;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * 실전 매매 엔진(D-013 ①). 설정을 읽고 RealTradingGuard(모든 실전 계좌 OFF)와 실전 제어 명령 채널(ADR-006)을 띄운 뒤
 * 종료 신호까지 돈다. 계좌별 워커, 주문 큐, 증권사 연동은 7단계 risk-ops 작업.
 *
 * <p>{@code hash-password} 인자로 실행하면 운영자 비밀번호 해시를 만들어 출력하고 끝난다(ADR-006 2.4).
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class EngineMain {

    static final String PROCESS = "engine";
    static final String HASH_PASSWORD = "hash-password";

    private static final Logger LOG = System.getLogger("com.autotrade." + PROCESS);

    private EngineMain() {}

    public static void main(String[] args) {
        if (args.length == 1 && HASH_PASSWORD.equals(args[0])) {
            System.exit(hashPassword(System.console()));
        }
        Clock clock = Clock.systemUTC();
        Engine engine;
        try {
            engine = start(ConfigPaths.defaultLocation(), clock);
        } catch (IOException e) {
            LOG.log(Level.ERROR, "실전 제어 명령 채널을 열지 못해 기동을 중단합니다: {0}", e.getMessage());
            System.exit(1);
            return;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            engine.close();
            stopped.countDown();
        }, "engine-shutdown"));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 기동하고 곧바로 닫는다(테스트·점검용). 종료 코드를 돌려준다(0: 정상). */
    static int run(ConfigPaths paths, Clock clock) {
        try {
            start(paths, clock).close();
            return 0;
        } catch (IOException e) {
            LOG.log(Level.ERROR, "실전 제어 명령 채널을 열지 못했습니다: {0}", e.getMessage());
            return 1;
        }
    }

    /** 설정 로딩 → Guard(모두 OFF) → 알림 큐 → 명령 채널 → 하트비트(감시가 읽음, MON-02·D-092). */
    static Engine start(ConfigPaths paths, Clock clock) throws IOException {
        LoadedConfig loaded = ProcessStartup.start(PROCESS, paths, clock);
        RuntimeConfig config = loaded.config();
        RealTradingGuard guard = startGuard(config, clock);
        // 알림은 전용 스레드 하나에서만 보내고, 엔진 스레드는 큐에 넣기만 한다(D-100, 절대 규칙 6)
        NotificationQueue notifications = startNotifications(config, paths, clock, guard.accounts().size());
        try {
            EngineCommandHandler handler = commandHandler(config, guard, clock, loaded.warning(),
                    new NotifyingCommandAuditLog(CommandAuditLog.LOGGING, notifications));
            ControlServer server = ControlServer.start(ControlSocket.path(paths), handler);
            LOG.log(Level.INFO, "실전 제어 명령 채널을 열었습니다(로컬 소켓, 네트워크 포트 없음)");
            // 하트비트 쓰기 실패는 로그만 남기고 엔진을 멈추지 않는다(HeartbeatWriter)
            HeartbeatWriter heartbeat = HeartbeatWriter.start(paths.runtimeFile().getParent(), PROCESS, clock,
                    HeartbeatWriter.DEFAULT_INTERVAL);
            notifications.offer(EventRoutes.ENGINE_STARTED, PROCESS, "실전 엔진 시작",
                    "실전 계좌 " + guard.accounts().size() + "개, RealTradingGuard 모두 OFF");
            if (!loaded.warning().isEmpty()) {
                // 설정 경고 문구는 값을 싣지 않는다(실전 운영 화면에도 나가는 문구, PR #100·#101)
                notifications.offer(EventRoutes.CONFIG_WARNING, PROCESS, "실전 엔진 설정 파일 경고", loaded.warning());
            }
            return new Engine(guard, handler, server, heartbeat, notifications);
        } catch (IOException | RuntimeException e) {
            notifications.close();
            throw e;
        }
    }

    /** 설정의 알림 값으로 채널을 꾸리고 보내는 스레드를 띄운다. 채널 설정이 틀려도 기동을 막지 않는다(로그 채널은 늘 있음). */
    static NotificationQueue startNotifications(RuntimeConfig config, ConfigPaths paths, Clock clock, int realAccounts) {
        NotificationQueue queue = setup(config.notification()).start(paths.runtimeFile().getParent(), PROCESS, clock);
        LOG.log(Level.INFO, "알림 채널: 긴급 {0}, 정보 {1}", queue.notifier().channelNames(Severity.URGENT),
                queue.notifier().channelNames(Severity.INFO));
        if (realAccounts > 0 && !queue.notifier().hasExternalChannel(Severity.URGENT)) {
            // 실전 계좌가 있는데 긴급 알림이 로그로만 가면 사람이 장애를 늦게 안다(ADR-005 표: 마지막 알림 채널 경고)
            LOG.log(Level.WARNING, "실전 계좌가 있는데 외부 알림 채널(Slack·카카오톡)이 없습니다. 긴급 알림이 엔진 로그에만 남습니다");
        }
        return queue;
    }

    /** 설정 값을 알림 모듈 값으로 이름을 붙여 옮긴다(알림 모듈은 설정 모듈을 모른다, PR #128 architect R1·R2). */
    static NotificationSetup setup(RuntimeConfig.Notification n) {
        RuntimeConfig.Kakao k = n.kakao();
        return NotificationSetup.builder()
                .slackEnabled(n.slack().enabled())
                .slackWebhookUrl(n.slack().webhookUrl().reveal())
                .kakaoEnabled(k.enabled())
                .kakaoPaidMode(k.mode() == KakaoMode.BIZ)
                .kakaoRestApiKey(k.restApiKey().reveal())
                .kakaoClientSecret(k.clientSecret().reveal())
                .kakaoAccessToken(k.accessToken().reveal())
                .kakaoRefreshToken(k.refreshToken().reveal())
                .kakaoLinkUrl(k.linkUrl())
                .displayZone(RuntimeConfig.Notification.DISPLAY_ZONE)
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP)
                .build();
    }

    /**
     * 설정의 실전 계좌로 Guard를 만든다. 모든 계좌는 OFF로 시작하며, 설정 어디에도 이를 바꿀 키가 없다(CFG-02, ADR-002).
     */
    static RealTradingGuard startGuard(RuntimeConfig config, Clock clock) {
        RuntimeConfig.RealTrading realTrading = config.realTrading();
        RealTradingGuard guard = new RealTradingGuard(realTrading.accountRefs(), clock, new LoggingGuardAuditLog());
        LOG.log(Level.INFO, "실전 계좌 {0}개, RealTradingGuard 모두 OFF로 시작", guard.accounts().size());
        for (AccountRef account : guard.accounts()) {
            if (!realTrading.isConfigured(account.id().value())) {
                LOG.log(Level.WARNING, "실전 계좌 {0}: 계좌번호가 없어 활성화할 수 없습니다(secrets.json)", account.id());
            }
        }
        return guard;
    }

    /** @param configWarning 기동 때 설정 로딩 경고. 상태 조회로 운영 화면에 보인다(1단계 QA 권고 R3) */
    static EngineCommandHandler commandHandler(
            RuntimeConfig config, RealTradingGuard guard, Clock clock, String configWarning) {
        return commandHandler(config, guard, clock, configWarning, CommandAuditLog.LOGGING);
    }

    static EngineCommandHandler commandHandler(
            RuntimeConfig config, RealTradingGuard guard, Clock clock, String configWarning, CommandAuditLog audit) {
        RuntimeConfig.RealTrading realTrading = config.realTrading();
        Optional<OperatorPasswordHash> hash = OperatorPasswordHash.parse(realTrading.operatorPasswordHash().reveal());
        if (hash.isEmpty()) {
            LOG.log(Level.WARNING, realTrading.operatorPasswordHash().isPresent()
                    ? "real-trading.operator-password-hash 형식이 올바르지 않아 실전 활성화·PANIC SELL을 받지 않습니다"
                    : "운영자 비밀번호가 없어 실전 활성화·PANIC SELL을 받지 않습니다(" + HASH_PASSWORD + "로 만드세요)");
        }
        // 증권사 주문 연동 전이라 활성화 단계와 PANIC 증권사 단계는 수행 불가로 끝난다(ADR-006 2.5)
        return new EngineCommandHandler(guard, new OperatorAuthenticator(hash, clock), realTrading::isConfigured,
                ActivationStepRunner.BROKER_NOT_CONNECTED, PanicLiquidator.BROKER_NOT_CONNECTED, audit, configWarning);
    }

    /** 비밀번호를 두 번 입력받아(화면에 보이지 않음) 해시를 출력한다. 평문은 출력·저장하지 않는다. */
    static int hashPassword(Console console) {
        if (console == null) {
            System.err.println("터미널에서 직접 실행해야 합니다(비밀번호를 화면에 보이지 않게 입력받습니다)");
            return 2;
        }
        char[] first = console.readPassword("운영자 비밀번호(%d자 이상): ", OperatorPasswordHash.MIN_PASSWORD_LENGTH);
        char[] second = console.readPassword("한 번 더 입력: ");
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                System.err.println("두 입력이 다릅니다");
                return 2;
            }
            String hash = OperatorPasswordHash.create(first, new SecureRandom());
            console.printf("secrets.json의 real-trading.operator-password-hash에 아래 값을 넣고 실전 엔진을 다시 시작하세요.%n%s%n",
                    hash);
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }

    /**
     * 실행 중인 엔진. 닫으면 명령 채널을 닫고 하트비트 파일을 지운다(정상 종료 표시). 알림 큐는 남은 알림을 최대 3초 보내 보고
     * 나머지는 로그에만 남긴다. Guard는 메모리 상태라 함께 사라진다.
     */
    record Engine(RealTradingGuard guard, EngineCommandHandler handler, ControlServer server, HeartbeatWriter heartbeat,
            NotificationQueue notifications) implements AutoCloseable {
        @Override
        public void close() {
            try {
                server.close();
            } finally {
                try {
                    // 채널 닫기가 실패해도 정상 종료 표시는 남긴다(멈춤으로 오인되지 않게, architect PR #116 R1)
                    heartbeat.close();
                } finally {
                    notifications.close();
                }
            }
        }
    }
}
