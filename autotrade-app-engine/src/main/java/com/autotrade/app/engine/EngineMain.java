package com.autotrade.app.engine;

import com.autotrade.app.engine.command.ActivationStepRunner;
import com.autotrade.app.engine.command.CommandAuditLog;
import com.autotrade.app.engine.command.NotifyingCommandAuditLog;
import com.autotrade.app.engine.command.EngineCommandHandler;
import com.autotrade.app.engine.command.OperatorAuthenticator;
import com.autotrade.app.engine.command.OperatorPasswordHash;
import com.autotrade.app.engine.command.PanicLiquidator;
import com.autotrade.app.engine.order.LoggingGuardAuditLog;
import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.ProcessStartup;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.control.ControlServer;
import com.autotrade.control.ControlSocket;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.monitor.Heartbeat;
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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

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

    /**
     * 설정 로딩 → Guard(모두 OFF) → 알림 큐 → 다른 엔진 확인(하트비트) → 명령 채널 → 하트비트(감시가 읽음, MON-02·D-092) → 자가 확인
     * (D-142 C6·C11).
     */
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
        EngineCommandHandler handler = null;
        ControlServer server = null;
        try {
            Path configDir = paths.runtimeFile().getParent();
            long pid = ProcessHandle.current().pid();
            // run 폴더(소켓·잠금 파일)를 지운 뒤 뜬 두 번째 엔진을 막는다(장애 테스트 3-R). 하트비트 폴더는 따로 있어 남는다
            Optional<Heartbeat> other = EngineSelfCheck.otherEngine(configDir, pid, clock);
            if (other.isPresent()) {
                throw new IOException("다른 실전 엔진(PID " + other.get().pid() + ")이 돌고 있습니다(하트비트 "
                        + other.get().writtenAt() + "). 잠금 파일이나 run 폴더가 지워졌을 수 있습니다");
            }
            handler = commandHandler(config, guard, clock, loaded.warning(),
                    new NotifyingCommandAuditLog(CommandAuditLog.LOGGING, notifications),
                    until -> notifications.offer(EventRoutes.OPERATOR_LOCKED, PROCESS, "실전 2차 확인 잠김",
                            "비밀번호·확인 문구가 " + OperatorAuthenticator.MAX_FAILURES + "번 연속 틀려 " + until
                                    + "까지 실전 활성화·PANIC SELL 확인을 받지 않습니다. 보호 모드 고정은 됩니다. 직접 시도한 것이 아니면"
                                    + " 확인하세요"));
            server = ControlServer.start(ControlSocket.path(paths), handler,
                    (event, detail) -> notifications.offer(EventRoutes.CONTROL_CHANNEL, PROCESS,
                            controlChannelTitle(event), detail));
            LOG.log(Level.INFO, "실전 제어 명령 채널을 열었습니다(로컬 소켓, 네트워크 포트 없음)");
            Instant startedAt = clock.instant();
            // 하트비트 쓰기 실패는 로그만 남기고 엔진을 멈추지 않는다(HeartbeatWriter)
            HeartbeatWriter heartbeat = HeartbeatWriter.start(configDir, PROCESS, clock, HeartbeatWriter.DEFAULT_INTERVAL);
            EngineSelfCheck selfCheck = new EngineSelfCheck(configDir, pid, startedAt, clock, guard, server::silence,
                    config.realTrading(), () -> new ConfigLoader().load(paths).config().realTrading(),
                    (kind, title, body) -> notifications.offer(kind, PROCESS, title, body));
            selfCheck.start(EngineSelfCheck.INTERVAL);
            if (guard.accounts().isEmpty()) {
                notifications.offer(EventRoutes.ENGINE_STARTED, PROCESS, "실전 엔진 시작", "실전 계좌 0개");
            } else {
                // 실전 계좌가 있는 엔진의 (재)기동은 사람이 알아야 한다(D-142 C11). 모든 계좌는 OFF로 시작한다
                notifications.offer(EventRoutes.ENGINE_STARTED_REAL, PROCESS, "실전 엔진 시작",
                        "실전 계좌 " + guard.accounts().size() + "개, RealTradingGuard 모두 OFF. 직접 시작한 것이 아니면 확인하세요");
            }
            if (!loaded.warning().isEmpty()) {
                // 설정 경고 문구는 값을 싣지 않는다(실전 운영 화면에도 나가는 문구, PR #100·#101)
                notifications.offer(EventRoutes.CONFIG_WARNING, PROCESS, "실전 엔진 설정 파일 경고", loaded.warning());
            }
            return new Engine(guard, handler, server, heartbeat, selfCheck, notifications);
        } catch (IOException | RuntimeException e) {
            if (server != null) {
                server.close();
            }
            if (handler != null) {
                handler.close();
            }
            notifications.close();
            throw e;
        }
    }

    /** 명령 채널 문제 알림의 제목. 본문은 서버가 준 설명이다(경로만, 값 없음). */
    static String controlChannelTitle(ControlServer.Event event) {
        return switch (event) {
            case CONNECTIONS_SATURATED -> "실전 명령 채널 연결이 가득 찼습니다";
            case RUN_DIR_RECREATED -> "실전 명령 채널 폴더가 지워져 다시 만들었습니다";
            case LOCK_RECREATED -> "실전 엔진 잠금 파일이 지워져 다시 만들었습니다";
            case LOCK_TAKEN -> "다른 프로세스가 실전 엔진 잠금 파일을 잡았습니다";
            case SOCKET_RECREATED -> "실전 명령 소켓이 지워져 다시 만들었습니다";
            case SOCKET_TAKEN -> "다른 프로세스가 실전 명령 소켓을 잡았습니다";
            case CHECK_FAILED -> "실전 명령 채널 자가 확인 실패";
        };
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
        return commandHandler(config, guard, clock, configWarning, audit, until -> { });
    }

    /** @param onLocked 2차 확인이 연속 실패로 잠기는 순간(풀리는 시각을 받음, C10) */
    static EngineCommandHandler commandHandler(RuntimeConfig config, RealTradingGuard guard, Clock clock, String configWarning,
            CommandAuditLog audit, Consumer<Instant> onLocked) {
        RuntimeConfig.RealTrading realTrading = config.realTrading();
        Optional<OperatorPasswordHash> hash = OperatorPasswordHash.parse(realTrading.operatorPasswordHash().reveal());
        if (hash.isEmpty()) {
            LOG.log(Level.WARNING, realTrading.operatorPasswordHash().isPresent()
                    ? "real-trading.operator-password-hash 형식이 올바르지 않아 실전 활성화·PANIC SELL을 받지 않습니다"
                    : "운영자 비밀번호가 없어 실전 활성화·PANIC SELL을 받지 않습니다(" + HASH_PASSWORD + "로 만드세요)");
        }
        // 증권사 주문 연동 전이라 활성화 단계와 PANIC 증권사 단계는 수행 불가로 끝난다(ADR-006 2.5)
        return new EngineCommandHandler(guard, new OperatorAuthenticator(hash, clock, guard, onLocked), realTrading::isConfigured,
                ActivationStepRunner.BROKER_NOT_CONNECTED, PanicLiquidator.BROKER_NOT_CONNECTED, audit, configWarning, clock,
                EngineCommandHandler.Limits.DEFAULT, System::nanoTime);
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
            console.printf("secrets.json의 real-trading.operator-password-hash에 아래 값을 넣고 실전 엔진과 실험실을 모두 다시 시작하세요"
                    + "(실험실을 다시 시작하지 않으면 설정 화면 저장이 막힙니다).%n%s%n",
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
         * 글자 그대로 둔다. 줄바꿈이 있으면 앞뒤 공백·줄바꿈을 걷고, 줄바꿈이 든 공백·줄바꿈 묶음을 " / " 하나로 바꾼다. 프로그램 오류의
         * 예외 흐름(스택)에는 원래 메시지가 그대로 남는다. 정규식 없이 한 번 훑는다: 긴 공백 묶음에서 정규식 반복이 재귀로
         * {@link StackOverflowError}를 내면 판정·종료 코드·안내 줄이 모두 사라진다(PR #172 risk-ops 재검토 K4).
         */
        static String oneLine(String message) {
            if (message == null) {
                return "null";
            }
            int start = 0;
            int end = message.length();
            boolean hasBreak = false;
            for (int i = start; i < end && !hasBreak; i++) {
                hasBreak = isLineBreak(message.charAt(i));
            }
            if (!hasBreak) {
                return message;
            }
            while (start < end && isBlank(message.charAt(start))) {
                start++;
            }
            while (end > start && isBlank(message.charAt(end - 1))) {
                end--;
            }
            StringBuilder out = new StringBuilder(end - start);
            int i = start;
            while (i < end) {
                if (!isBlank(message.charAt(i))) {
                    out.append(message.charAt(i++));
                    continue;
                }
                int runStart = i;
                boolean runHasBreak = false;
                while (i < end && isBlank(message.charAt(i))) {
                    runHasBreak |= isLineBreak(message.charAt(i));
                    i++;
                }
                if (runHasBreak) {
                    out.append(" / ");
                } else {
                    out.append(message, runStart, i);
                }
            }
            return out.toString();
        }

        /** 정규식 {@code \R}의 줄바꿈 글자({@code \r\n}은 두 글자 모두 해당). */
        private static boolean isLineBreak(char c) {
            return (c >= '\n' && c <= '\r') || c == '\u0085' || c == '\u2028' || c == '\u2029';
        }

        /** 줄바꿈이거나 정규식 {@code \s}의 공백(스페이스·탭). */
        private static boolean isBlank(char c) {
            return c == ' ' || c == '\t' || isLineBreak(c);
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
     * 실행 중인 엔진. 닫으면 자가 확인을 멈추고, 진행 중인 계좌 작업(활성화·청산)을 최대 5초 기다려 마친 뒤 명령 채널을 닫고(진행 중인
     * 연결은 응답을 마침, C7) 하트비트 파일을 지운다(정상 종료 표시). 알림 큐는 남은 알림을 최대 3초 보내 보고 나머지는 로그에만
     * 남긴다. Guard는 메모리 상태라 함께 사라진다.
     */
    record Engine(RealTradingGuard guard, EngineCommandHandler handler, ControlServer server, HeartbeatWriter heartbeat,
            EngineSelfCheck selfCheck, NotificationQueue notifications) implements AutoCloseable {
        @Override
        public void close() {
            // 앞 단계가 실패해도 뒤 단계는 한다. 채널 닫기가 실패해도 정상 종료 표시는 남긴다(멈춤으로 오인되지 않게, architect PR #116 R1)
            closeAll(selfCheck, handler, server, heartbeat, notifications);
        }

        private static void closeAll(AutoCloseable... steps) {
            RuntimeException first = null;
            for (AutoCloseable step : steps) {
                try {
                    step.close();
                } catch (Exception e) {
                    if (first == null) {
                        first = e instanceof RuntimeException r ? r : new IllegalStateException(e);
                    } else {
                        first.addSuppressed(e);
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}
