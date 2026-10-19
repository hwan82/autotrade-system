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
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 실전 매매 엔진(D-013 ①). 설정을 읽고 RealTradingGuard(모든 실전 계좌 OFF)와 실전 제어 명령 채널(ADR-006)을 띄운 뒤
 * 종료 신호까지 돈다. 계좌별 워커, 주문 큐, 증권사 연동은 7단계 risk-ops 작업.
 *
 * <p>기동 실패는 종류로 나눠 알린다(D-107, 감시 D-106과 같은 기준): 설정 폴더 경로가 틀리면 사람이 고칠 설정 오류로 종료 코드 2,
 * 실전 제어 명령 채널을 열지 못하면(다른 엔진이 이미 돎 등) 종료 코드 1, 그 밖의 기동 예외(알림 채널 꾸리기 같은 코드 실수)는 "설정
 * 오류"로 표시하지 않고 프로그램 오류로 알린 뒤 종료 코드 1. 어느 경우든 엔진은 뜨지 않고, Guard는 메모리 상태라 다음 기동도 모든 계좌
 * OFF로 시작한다.
 *
 * <p>{@code hash-password} 인자로 실행하면 운영자 비밀번호 해시를 만들어 출력하고 끝난다(ADR-006 2.4).
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class EngineMain {

    static final String PROCESS = "engine";
    static final String HASH_PASSWORD = "hash-password";
    /** 기동 실패: 명령 채널을 열지 못함이나 프로그램 오류(D-107). JVM이 처리되지 않은 예외로 끝낼 때와 같은 값이다. */
    static final int EXIT_START_FAILED = 1;
    /** 사람이 고칠 설정 오류로 기동 거부(설정 폴더 경로, D-107). 감시의 같은 뜻 코드(D-106)와 같은 값이다. */
    static final int EXIT_BAD_SETTINGS = 2;

    private static final Logger LOG = System.getLogger("com.autotrade." + PROCESS);

    private EngineMain() {}

    public static void main(String[] args) {
        if (args.length == 1 && HASH_PASSWORD.equals(args[0])) {
            System.exit(hashPassword(System.console()));
        }
        Engine engine = launch(ConfigPaths::defaultLocation, Clock.systemUTC(), EngineMain::setup, System::exit);
        if (engine == null) {
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

    /** 기동하고 곧바로 닫는다(테스트·점검용). 종료 코드를 돌려준다(0: 정상, 1: 명령 채널을 열지 못함). 그 밖의 예외는 던진다. */
    static int run(ConfigPaths paths, Clock clock) {
        try {
            start(paths, clock).close();
            return 0;
        } catch (IOException e) {
            StartFailure failure = StartFailure.of(e);
            failure.report(e);
            return failure.exitCode();
        }
    }

    /**
     * 진입점의 기동 절차(D-107). 설정 폴더를 정하고 엔진을 띄운다. 실패하면 {@link StartFailure}로 알리고 그 종료 코드로 {@code exit}를
     * 부른 뒤 null을 돌려준다. 시험이 {@code exit}와 알림 설정 옮기기({@code setup})를 바꿔 끼워 진입점의 판정·종료 코드를 직접 본다.
     */
    static Engine launch(Supplier<ConfigPaths> paths, Clock clock, Function<RuntimeConfig.Notification, NotificationSetup> setup,
            IntConsumer exit) {
        try {
            return start(configPaths(paths), clock, setup);
        } catch (IOException | RuntimeException e) {
            StartFailure failure = StartFailure.of(e);
            failure.report(e);
            exit.accept(failure.exitCode());
            return null;
        }
    }

    /**
     * 설정 폴더 경로를 정한다. {@code autotrade.config-dir}·{@code AUTOTRADE_CONFIG_DIR} 값이 경로 형식이 아니면(예: Windows에서 따옴표를
     * 넣음) 사람이 고칠 설정 문제로 거부한다(종료 코드 2). 감시의 같은 검사(D-106 4번)와 같은 기준이다.
     */
    private static ConfigPaths configPaths(Supplier<ConfigPaths> paths) {
        try {
            return paths.get();
        } catch (IllegalArgumentException e) {
            throw new EngineRefusal("설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR): " + e.getMessage());
        }
    }

    /** 설정 로딩 → Guard(모두 OFF) → 알림 큐 → 명령 채널 → 하트비트(감시가 읽음, MON-02·D-092). */
    static Engine start(ConfigPaths paths, Clock clock) throws IOException {
        return start(paths, clock, EngineMain::setup);
    }

    private static Engine start(ConfigPaths paths, Clock clock, Function<RuntimeConfig.Notification, NotificationSetup> setup)
            throws IOException {
        LoadedConfig loaded = ProcessStartup.start(PROCESS, paths, clock);
        RuntimeConfig config = loaded.config();
        RealTradingGuard guard = startGuard(config, clock);
        // 알림은 전용 스레드 하나에서만 보내고, 엔진 스레드는 큐에 넣기만 한다(D-100, 절대 규칙 6)
        NotificationQueue notifications = startNotifications(setup.apply(config.notification()), paths, clock,
                guard.accounts().size());
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
    static NotificationQueue startNotifications(NotificationSetup setup, ConfigPaths paths, Clock clock, int realAccounts) {
        NotificationQueue queue = setup.start(paths.runtimeFile().getParent(), PROCESS, clock);
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

    /** 사람이 고칠 설정 문제로 엔진이 뜨지 않기로 판정한 이유(종료 코드 2, D-107). 지금은 설정 폴더 경로뿐이다. */
    static final class EngineRefusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        EngineRefusal(String detail) {
            super(detail);
        }
    }

    /**
     * 기동 실패를 운영자에게 어떻게 알릴지(D-107, PR #144 독립 QA R2). {@link EngineRefusal}만 설정 오류로 종료 코드 2, 명령 채널을 열지
     * 못한 {@link IOException}은 예전 문구 그대로 종료 코드 1, 나머지는 설정 문제가 아닌 프로그램 오류로 종료 코드 1이다. 알림 채널 이름
     * 검사 같은 코드 실수도 {@link IllegalArgumentException}이지만 설정 오류로 표시하지 않는다.
     *
     * @param exitCode 종료 코드
     * @param headline 콘솔·로그 첫머리 문구
     * @param detail 실제 원인(예외 메시지, 프로그램 오류면 예외 종류도)
     * @param programError 프로그램 오류인지(로그에 예외 흐름도 남김)
     */
    record StartFailure(int exitCode, String headline, String detail, boolean programError) {

        static final String SETTINGS_HEADLINE = "실전 엔진 설정이 틀려 기동하지 않습니다";
        static final String CHANNEL_HEADLINE = "실전 제어 명령 채널을 열지 못해 기동을 중단합니다";
        // 괄호로 끝나지 않게 한다: 표준 오류 줄이 첫머리 뒤에 "(실전 엔진이 뜨지 않았습니다)"를 붙인다(PR #151 QA R1)
        static final String PROGRAM_ERROR_HEADLINE =
                "실전 엔진이 기동 중 프로그램 오류로 멈췄습니다. 설정 파일 문제가 아니니 로그의 예외를 개발 쪽에 알려 주세요";

        private static final Pattern LINE_BREAK = Pattern.compile("\\R");
        private static final Pattern LINE_BREAK_RUN = Pattern.compile("\\s*\\R\\s*");

        static StartFailure of(Exception e) {
            if (e instanceof EngineRefusal) {
                return new StartFailure(EXIT_BAD_SETTINGS, SETTINGS_HEADLINE, oneLine(e.getMessage()), false);
            }
            if (e instanceof IOException) {
                return new StartFailure(EXIT_START_FAILED, CHANNEL_HEADLINE, oneLine(e.getMessage()), false);
            }
            return new StartFailure(EXIT_START_FAILED, PROGRAM_ERROR_HEADLINE,
                    e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()), true);
        }

        /**
         * 원인 메시지의 줄바꿈을 " / "로 접어 로그 ERROR와 표준 오류가 늘 한 줄이게 한다(D-112, PR #169 독립 QA R2). 줄바꿈이 없으면
         * 글자 그대로 둔다. 프로그램 오류의 예외 흐름(스택)에는 원래 메시지가 그대로 남는다.
         */
        static String oneLine(String message) {
            if (message == null || !LINE_BREAK.matcher(message).find()) {
                return String.valueOf(message);
            }
            return LINE_BREAK_RUN.matcher(message.strip()).replaceAll(" / ");
        }

        /** 로그(ERROR)와 표준 오류에 한 줄씩 남긴다. 프로그램 오류면 로그에 예외 흐름도 남긴다. */
        void report(Exception e) {
            if (programError) {
                LOG.log(Level.ERROR, headline + ": " + detail, e);
            } else {
                LOG.log(Level.ERROR, headline + ": " + detail);
            }
            System.err.println(headline + "(실전 엔진이 뜨지 않았습니다): " + detail);
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
