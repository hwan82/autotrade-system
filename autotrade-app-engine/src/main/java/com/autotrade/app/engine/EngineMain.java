package com.autotrade.app.engine;

import com.autotrade.app.engine.command.ActivationStepRunner;
import com.autotrade.app.engine.command.CommandAuditLog;
import com.autotrade.app.engine.command.EngineCommandHandler;
import com.autotrade.app.engine.command.OperatorAuthenticator;
import com.autotrade.app.engine.command.OperatorPasswordHash;
import com.autotrade.app.engine.command.PanicLiquidator;
import com.autotrade.app.engine.order.LoggingGuardAuditLog;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.ProcessStartup;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.control.ControlServer;
import com.autotrade.control.ControlSocket;
import com.autotrade.domain.account.AccountRef;
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

    /** 설정 로딩 → Guard(모두 OFF) → 명령 채널. */
    static Engine start(ConfigPaths paths, Clock clock) throws IOException {
        LoadedConfig loaded = ProcessStartup.start(PROCESS, paths, clock);
        RuntimeConfig config = loaded.config();
        RealTradingGuard guard = startGuard(config, clock);
        EngineCommandHandler handler = commandHandler(config, guard, clock);
        ControlServer server = ControlServer.start(ControlSocket.path(paths), handler);
        LOG.log(Level.INFO, "실전 제어 명령 채널을 열었습니다(로컬 소켓, 네트워크 포트 없음)");
        return new Engine(guard, handler, server);
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

    static EngineCommandHandler commandHandler(RuntimeConfig config, RealTradingGuard guard, Clock clock) {
        RuntimeConfig.RealTrading realTrading = config.realTrading();
        Optional<OperatorPasswordHash> hash = OperatorPasswordHash.parse(realTrading.operatorPasswordHash().reveal());
        if (hash.isEmpty()) {
            LOG.log(Level.WARNING, realTrading.operatorPasswordHash().isPresent()
                    ? "real-trading.operator-password-hash 형식이 올바르지 않아 실전 활성화·PANIC SELL을 받지 않습니다"
                    : "운영자 비밀번호가 없어 실전 활성화·PANIC SELL을 받지 않습니다(" + HASH_PASSWORD + "로 만드세요)");
        }
        // 증권사 주문 연동 전이라 활성화 단계와 PANIC 증권사 단계는 수행 불가로 끝난다(ADR-006 2.5)
        return new EngineCommandHandler(guard, new OperatorAuthenticator(hash, clock), realTrading::isConfigured,
                ActivationStepRunner.BROKER_NOT_CONNECTED, PanicLiquidator.BROKER_NOT_CONNECTED, CommandAuditLog.LOGGING);
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

    /** 실행 중인 엔진. 닫으면 명령 채널만 닫는다(Guard는 메모리 상태라 함께 사라진다). */
    record Engine(RealTradingGuard guard, EngineCommandHandler handler, ControlServer server) implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }
}
