package com.autotrade.app.lab;

import com.autotrade.app.lab.experiment.ExperimentCatalog;
import com.autotrade.app.lab.experiment.ExperimentFolders;
import com.autotrade.app.lab.experiment.ExperimentJobStream;
import com.autotrade.app.lab.experiment.ExperimentJobs;
import com.autotrade.app.lab.experiment.ExperimentRegistrations;
import com.autotrade.app.lab.experiment.InMemoryExperimentCatalog;
import com.autotrade.app.lab.experiment.SnapshotList;
import com.autotrade.app.lab.live.LiveLabService;
import com.autotrade.app.lab.profile.SelectionProfileStore;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import com.autotrade.app.lab.settings.ExperimentLimitsPublisher;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.RuntimeConfigHolder;
import com.autotrade.config.RuntimeConfigStore;
import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlSocket;
import com.autotrade.marketdata.feed.FeedSocket;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import com.autotrade.sim.live.LiveCheckMain;
import com.autotrade.sim.run.CodeVersion;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 대시보드가 읽고 쓰는 저장소 연결(ADR-005). */
@Configuration(proxyBeanMethods = false)
public class LabWebConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LabWebConfiguration.class);

    /** 종목명 표는 설정 폴더의 {@code symbols/<MARKET>.csv}에서 읽는다. 없거나 깨져도 이름만 비고 기동에는 영향 없다. */
    @Bean
    ExperimentCatalog experimentCatalog(RuntimeConfigStore store) {
        Path configDir = store.paths().runtimeFile().getParent();
        SymbolNameFiles.Loaded loaded = SymbolNameFiles.load(configDir);
        loaded.warnings().forEach(w -> log.warn("종목명 표: {}", w));
        log.info("종목명 표: {}건 ({})", loaded.names().size(), configDir.resolve(SymbolNameFiles.DIR));
        return new InMemoryExperimentCatalog(loaded.names());
    }

    /**
     * 조합 실험 폴더(D-062). 실행 명령이 결과를 쓰는 {@code experiments/}와 같은 폴더를 가리켜야 결과를 불러올 수 있다.
     */
    @Bean
    ExperimentFolders experimentFolders(
            @Value("${autotrade.lab.experiments-dir:experiments}") String dir, ExperimentCatalog catalog) {
        ExperimentFolders folders = new ExperimentFolders(Path.of(dir), catalog);
        log.info("실험 폴더: {}", folders.root());
        return folders;
    }

    /** 실험 생성 화면이 등록 파일을 쓰는 곳(D-074). 결과를 불러오는 실험 폴더와 같은 폴더다. */
    @Bean
    ExperimentRegistrations experimentRegistrations(
            @Value("${autotrade.lab.experiments-dir:experiments}") String dir, StrategyProfileStore strategies,
            SelectionProfileStore selections) {
        return new ExperimentRegistrations(Path.of(dir), strategies, selections);
    }

    /** 실험 실행 명령의 진행 상태·취소 요청(D-089). 실행 명령과 같은 설정 폴더의 {@code experiment-state/jobs}를 본다. */
    @Bean
    ExperimentJobs experimentJobs(RuntimeConfigStore store, Clock clock) {
        ExperimentJobs jobs = new ExperimentJobs(store.paths().runtimeFile().toAbsolutePath().getParent(), clock);
        log.info("실험 작업 상태 폴더: {}", jobs.root());
        return jobs;
    }

    /**
     * 실험실 설정의 병렬 상한을 실험 실행 명령이 읽는 상한 파일로 내보낸다(D-094). 기동 때 한 번 쓰고, 설정 화면 저장 뒤에 다시 쓴다.
     * 실험실이 꺼져 있을 때 {@code runtime-config.json}을 손으로 고쳤다면 다음 기동 때 반영된다. 그 파일이 깨져 기본값으로 떴으면
     * 기동 때 쓰지 않는다(PR #121 architect F1).
     */
    @Bean
    ExperimentLimitsPublisher experimentLimitsPublisher(RuntimeConfigStore store, RuntimeConfigHolder holder,
            LoadedConfig loaded, Clock clock) {
        ExperimentLimitsPublisher publisher =
                new ExperimentLimitsPublisher(store.paths().runtimeFile().toAbsolutePath().getParent(), clock);
        publisher.publishAtStartup(loaded, holder::current);
        return publisher;
    }

    /** 실험 진행 상태 SSE(UI-01·UI-02, D-093). 연결이 있을 때만 작업 폴더를 다시 읽는다. */
    @Bean(destroyMethod = "close")
    ExperimentJobStream experimentJobStream(ExperimentJobs jobs, Clock clock) {
        return new ExperimentJobStream(jobs, clock);
    }

    /**
     * 진행 상태 SSE의 읽기 스레드를 띄우고, 종료 때 열린 연결을 웹 서버 graceful shutdown보다 먼저 닫는다(모니터링 SSE와 같은
     * 이유, D-091: 닫지 않으면 graceful shutdown이 30초를 기다려 실전 운영 화면을 싣는 실험실 재기동이 늦어진다).
     */
    @Bean
    SmartLifecycle experimentJobStreamLifecycle(ExperimentJobStream stream) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                stream.start();
                running = true;
            }

            @Override
            public void stop() {
                stream.closeStreams();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    /** 수집 스냅샷 폴더(설정 폴더 아래 {@code snapshots}, 실행 명령의 기본값과 같음, ADR-008 2.4). */
    @Bean
    SnapshotList snapshotList(RuntimeConfigStore store) {
        SnapshotList list = new SnapshotList(store.paths().runtimeFile().getParent().resolve("snapshots"));
        log.info("수집 스냅샷 폴더: {}", list.root());
        return list;
    }

    /**
     * 실전 엔진 명령 채널 클라이언트(ADR-006). 엔진이 꺼져 있어도 실험실은 그대로 뜨고, 운영 화면만 "엔진 꺼짐"을 보여 준다.
     * 응답 대기 상한은 비밀번호 해시 검증(PBKDF2 600,000회)과 활성화 단계를 감안한 값이다 `[가정]`.
     */
    @Bean(destroyMethod = "close")
    ControlClient controlClient(RuntimeConfigStore store) {
        return new ControlClient(ControlSocket.path(store.paths()), Duration.ofSeconds(15));
    }

    @Bean
    StrategyProfileStore strategyProfileStore(
            @Value("${autotrade.lab.strategies-dir:strategies}") String dir, Clock clock) {
        StrategyProfileStore store = new StrategyProfileStore(Path.of(dir), clock);
        log.info("전략 프로필 폴더: {}", store.root());
        return store;
    }

    /** 선정 프로필 폴더(ADR-007 2.1). 실험 실행기가 읽는 {@code selections/}와 같은 폴더다. */
    @Bean
    SelectionProfileStore selectionProfileStore(
            @Value("${autotrade.lab.selections-dir:selections}") String dir, Clock clock) {
        SelectionProfileStore store = new SelectionProfileStore(Path.of(dir), clock);
        log.info("선정 프로필 폴더: {}", store.root());
        return store;
    }

    /**
     * 실시간 모의 운용(ADR-010, D-076). 게이트웨이 시세 전달 소켓({@code <설정 폴더>/run/market-feed.sock})을 받고, 입력 기록은
     * 설정 폴더 아래 {@code live-sessions}(저장소 밖)에 쓴다. 저장소 루트는 전략 프로필 폴더의 부모다.
     */
    @Bean(destroyMethod = "close")
    LiveLabService liveLabService(RuntimeConfigStore store,
            @Value("${autotrade.lab.experiments-dir:experiments}") String experimentsDir,
            @Value("${autotrade.lab.strategies-dir:strategies}") String strategiesDir,
            @Value("${autotrade.code-commit:unknown}") String commit,
            @Value("${autotrade.code-dirty:true}") boolean dirty,
            @Value("${autotrade.lab.live.min-free-gb:20}") long minFreeGb, Clock clock) {
        Path configDir = store.paths().runtimeFile().toAbsolutePath().getParent();
        Path repoRoot = Path.of(strategiesDir).toAbsolutePath().normalize().getParent();
        return new LiveLabService(Path.of(experimentsDir), repoRoot, configDir.resolve(LiveCheckMain.SESSIONS_DIR),
                FeedSocket.path(configDir), configDir.resolve(LiveLabService.STOP_REQUEST), new CodeVersion(commit, dirty),
                clock, LiveLabService.FILE_STORE, minFreeGb);
    }
}
