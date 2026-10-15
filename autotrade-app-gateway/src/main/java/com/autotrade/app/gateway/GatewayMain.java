package com.autotrade.app.gateway;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.broker.kis.KisPlugin;
import com.autotrade.broker.kiwoom.KiwoomPlugin;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.ProcessStartup;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.feed.FeedSocket;
import com.autotrade.marketdata.live.JdkHttpPort;
import com.autotrade.marketdata.live.JdkScheduler;
import com.autotrade.marketdata.live.JdkWsTransport;
import com.autotrade.marketdata.live.LiveRuntime;
import com.autotrade.marketdata.live.SerialDispatcher;
import com.autotrade.monitor.HeartbeatWriter;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * 시세 게이트웨이(D-013 ②). 키움·한국투자 실시간 시세를 받아 녹화하고 실험실에 전달한다(ADR-003, ADR-010). 주문 API는
 * 호출하지 않는다(MD-01).
 * 토스는 연결하지 않는다. 토스 토큰은 실전 엔진만 쓰므로 게이트웨이·실험실의 실시간·REST 보정 시세는 키움·한국투자로만 받는다(D-059).
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class GatewayMain {

    static final String PROCESS = "gateway";
    /** 녹화 폴더. 설정 키 {@code market-data.recording-dir}는 제안 상태라 2단계는 설정 폴더 아래 고정(ADR-003 6장). */
    static final String RECORDINGS_DIR = "recordings";
    static final int DISPATCH_QUEUE = 50_000;

    private static final Logger LOG = System.getLogger(GatewayMain.class.getName());

    private GatewayMain() {}

    public static void main(String[] args) throws InterruptedException {
        ConfigPaths paths = ConfigPaths.defaultLocation();
        Clock clock = Clock.systemUTC();
        JdkScheduler scheduler = new JdkScheduler("gateway-scheduler");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        LiveRuntime runtime = new LiveRuntime(new JdkWsTransport(client, Duration.ofSeconds(10)),
                new JdkHttpPort(client, Duration.ofSeconds(10)), clock, scheduler,
                id -> SerialDispatcher.threaded("dispatch-" + id, DISPATCH_QUEUE));
        Gateway gateway;
        try {
            gateway = start(paths, runtime);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.ERROR, "게이트웨이 기동 실패: {0}", e.getClass().getName());
            scheduler.close();
            System.exit(1);
            return;
        }
        HeartbeatWriter heartbeat = startHeartbeat(paths, clock);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            heartbeat.close();
            gateway.close();
            scheduler.close();
            stopped.countDown();
        }, "gateway-shutdown"));
        stopped.await();
    }

    /** 감시가 읽는 하트비트(MON-02, D-092). 쓰기 실패는 로그만 남기고, 종료 훅에서 닫아 파일을 지운다. */
    static HeartbeatWriter startHeartbeat(ConfigPaths paths, Clock clock) {
        return HeartbeatWriter.start(paths.runtimeFile().getParent(), PROCESS, clock, HeartbeatWriter.DEFAULT_INTERVAL);
    }

    /** 기동 점검. 설정만 읽고 연결하지 않는다. 종료 코드를 돌려준다(0: 정상). */
    static int run(ConfigPaths paths, Clock clock) {
        ProcessStartup.start(PROCESS, paths, clock);
        return 0;
    }

    /** 설정과 감시 종목을 읽고 수신·녹화를 시작한다. */
    static Gateway start(ConfigPaths paths, LiveRuntime runtime) throws IOException {
        LoadedConfig loaded = ProcessStartup.start(PROCESS, paths, runtime.clock());
        RuntimeConfig config = loaded.config();
        Path dir = paths.runtimeFile().toAbsolutePath().getParent();
        WatchList watch = WatchList.load(dir.resolve(WatchList.FILE_NAME));
        Set<BrokerId> reserved = reservedForRealTrading(config.realTrading());
        List<BrokerPlugin> plugins = new ArrayList<>();
        for (BrokerId id : config.marketData().priority()) {
            if (reserved.contains(id)) {
                LOG.log(Level.WARNING, "{0}: 실전 계좌가 설정된 증권사라 게이트웨이 실시간을 띄우지 않습니다"
                        + "(키·토큰은 실전 엔진 전용, D-024, ADR-003 4장)", id);
                continue;
            }
            plugin(id, config.brokers().of(id), runtime).ifPresent(plugins::add);
        }
        if (plugins.isEmpty()) {
            LOG.log(Level.WARNING, "실시간 시세용 증권사 키가 설정되지 않았습니다. 수신 없이 대기합니다");
        }
        return Gateway.start(config.marketData().mode(), plugins, watch, dir.resolve(RECORDINGS_DIR), openFeed(dir),
                runtime.clock());
    }

    /**
     * 실험실 실시간 전달 서버(ADR-010, D-076)를 연다. 열지 못하면(소켓 경로가 길거나 다른 게이트웨이가 쓰는 중) 경고만 남기고
     * 녹화만 한다. 전달은 실험실용이라 없어도 수신·녹화는 그대로다.
     */
    static FeedServer openFeed(Path configDir) {
        try {
            FeedServer feed = FeedServer.open(FeedSocket.path(configDir));
            LOG.log(Level.INFO, "실험실 시세 전달 소켓을 열었습니다: {0}", feed.socket());
            return feed;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "실험실 시세 전달을 열지 못해 녹화만 합니다: {0}", e.getMessage());
            return null;
        }
    }

    /**
     * 계좌번호까지 설정된 실전 계좌가 있는 증권사. 그 증권사 키로 게이트웨이가 토큰을 받으면 실전 엔진의 토큰·세션을
     * 밀어낼 수 있으므로(키움 토큰당 세션 1개, 토스 토큰 1개만 유효) 게이트웨이는 쓰지 않는다. 같은 키로 따로 토큰을 받아도
     * 서로 무효화하지 않는다는 실측(Q-015) 전까지의 보수적 규칙이다(D-024).
     */
    static Set<BrokerId> reservedForRealTrading(RuntimeConfig.RealTrading realTrading) {
        Set<BrokerId> out = EnumSet.noneOf(BrokerId.class);
        for (RuntimeConfig.RealAccount a : realTrading.accounts()) {
            if (realTrading.isConfigured(a.id())) {
                out.add(a.broker());
            }
        }
        return out;
    }

    static Optional<BrokerPlugin> plugin(BrokerId id, RuntimeConfig.BrokerKeys keys, LiveRuntime runtime) {
        if (!keys.isConfigured()) {
            return Optional.empty();
        }
        BrokerCredentials creds = new BrokerCredentials(keys.appKey().reveal(), keys.appSecret().reveal());
        return switch (id) {
            case KIWOOM -> Optional.of(KiwoomPlugin.create(creds, runtime));
            case KIS -> Optional.of(KisPlugin.create(creds, runtime));
            case TOSS -> Optional.empty();
        };
    }
}
