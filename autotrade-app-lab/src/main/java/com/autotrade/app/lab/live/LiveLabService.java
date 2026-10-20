package com.autotrade.app.lab.live;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.sim.live.LivePlan;
import com.autotrade.sim.live.LiveSelectionPrep;
import com.autotrade.sim.live.LiveSession;
import com.autotrade.sim.recording.RecordingPlan;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.SelectionResultFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 실험실의 실시간 모의 운용(ADR-010 2.4~2.7, D-076). 게이트웨이 시세 전달 소켓에서 받은 이벤트를 실시간 모의 세션 하나에 넘긴다.
 *
 * <ul>
 *   <li><b>실험실이 먼저 양보한다</b>(절대 규칙 6, SIM-14). 받는 쪽 스레드는 크기 제한 큐({@link #INBOX_CAPACITY})에 넣기만 하고, 차면
 *       버린 뒤 그 종목의 다음 이벤트 앞에 공백({@value LiveSession#INBOX_SOURCE}, CONSUMER_OVERFLOW)을 넣는다. 게이트웨이는 받는 쪽마다
 *       큐가 따로라 이쪽이 느려도 녹화와 다른 받는 쪽에 영향이 없다.</li>
 *   <li>감시 프로세스의 정지 요청 파일({@value #STOP_REQUEST})이 있거나, 입력 기록 디스크 여유가 하한 미만이면 시작하지 않고, 돌던
 *       세션은 멈춘다(기록을 닫고 연결을 끊음). 실전 엔진의 상태 기록이 같은 디스크를 쓸 수 있다(장애표 #8·#10). 장 시간 보호(D-063)는
 *       적용하지 않는다: 실시간 모의는 장중에 도는 것이 목적이고, 증권사를 직접 부르지 않으며 이벤트 하나씩 처리하는 가벼운 일이다.
 *       무거운 리플레이 확인은 실행 명령({@code runLiveReplayCheck})으로 장 밖에서 한다.</li>
 *   <li>입력 기록으로 계좌를 되살리는 일은 요청 스레드가 아니라 가장 낮은 우선순위의 별도 스레드에서 기록일 하나씩 하고, 날마다 정지
 *       요청과 디스크를 본다(risk-ops PR #87 R2). 그동안 상태는 {@code restoring}이고 게이트웨이 연결은 아직 열지 않는다.</li>
 *   <li>세션은 한 번에 하나다. 실험실이 다시 뜨면 자동으로 다시 시작하지 않는다(다시 시작하면 입력 기록으로 계좌를 되살린다).</li>
 *   <li><b>선정 준비</b>(D-124, ADR-012 3.5). 등록 파일에 {@code selections}가 있으면 세션이 연결된 뒤 가장 낮은 우선순위의 준비
 *       스레드가 장 밖 창({@link LiveSelectionPrep#QUIET_FROM} ~ 다음 날 {@link LiveSelectionPrep#QUIET_TO})에서
 *       {@value #PREPARE_INTERVAL_MINUTES}분마다, 또 화면의 "지금 준비"로 다음 거래일 선정 결과 고정 파일을 만든다. 정지 요청·디스크
 *       관문을 스냅샷을 읽기 전과 선정 하나마다 본다(architect PR #191 N2). 일봉 스냅샷이 실험실 힙에 비해 크면 읽지 않고 준비 실행
 *       명령({@code runLiveSelectionPrepare})을 쓰라고 남긴다. 이벤트 처리 스레드는 계산하지 않고 그날 첫 이벤트에서 고정 파일만
 *       읽는다.</li>
 * </ul>
 */
public class LiveLabService implements AutoCloseable {

    /** 감시가 만드는 정지 요청 파일(실험 실행 명령과 같은 위치, D-058·D-063). */
    public static final String STOP_REQUEST = "experiment-state/stop-request";
    /** 설정 폴더 안 수집 스냅샷 폴더(세션 폴더 {@code live-sessions}와 같은 부모, ADR-008 2.4). */
    static final String SNAPSHOTS_DIR = "snapshots";
    public static final int INBOX_CAPACITY = 100_000;
    /** 입력 기록 디스크 여유 기본 하한(수집기 {@code --min-free-gb} 기본값과 같음, ADR-008 2.7). */
    public static final long DEFAULT_MIN_FREE_GB = 20;
    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    /** 등록 파일의 {@code input: live} 줄(따옴표 있든 없든). 형식 검사는 {@link LivePlan#parse}가 한다. */
    private static final Pattern LIVE_INPUT = Pattern.compile("input:\\s*[\"']?" + LivePlan.INPUT + "[\"']?\\s*(#.*)?");
    private static final Logger log = LoggerFactory.getLogger(LiveLabService.class);
    /** 준비 스레드가 장 밖 창에서 다시 보는 간격(분). */
    public static final int PREPARE_INTERVAL_MINUTES = 5;

    /** 디스크 여유 바이트. 테스트는 고정값을 넣는다. */
    @FunctionalInterface
    public interface FreeSpace {
        long usableBytes(Path existingDir) throws IOException;
    }

    public static final FreeSpace FILE_STORE = dir -> Files.getFileStore(dir).getUsableSpace();

    private final Path experimentsRoot;
    private final Path repoRoot;
    private final Path sessionsRoot;
    private final Path feedSocket;
    private final Path stopRequest;
    private final CodeVersion code;
    private final Clock clock;
    private final FreeSpace freeSpace;
    private final long minFreeBytes;
    private Running running;
    /** 멈춘 세션의 되살리기 스레드(아직 끝나지 않았으면 세션 잠금을 쥐고 있을 수 있다). */
    private Thread lingering;
    private String lastStopReason = "";
    private StopKind lastStopKind = StopKind.NONE;

    /**
     * @param experimentsRoot 등록 파일과 결과가 있는 {@code experiments/}
     * @param repoRoot {@code strategies/}가 있는 저장소 루트
     * @param sessionsRoot 세션 폴더(설정 폴더 아래 {@code live-sessions}, 저장소 밖)
     * @param feedSocket 게이트웨이 시세 전달 소켓
     * @param stopRequest 감시의 정지 요청 파일
     */
    public LiveLabService(Path experimentsRoot, Path repoRoot, Path sessionsRoot, Path feedSocket, Path stopRequest,
            CodeVersion code, Clock clock) {
        this(experimentsRoot, repoRoot, sessionsRoot, feedSocket, stopRequest, code, clock, FILE_STORE,
                DEFAULT_MIN_FREE_GB);
    }

    /** 디스크 여유 하한 설정의 상한(GB, 1PB). */
    static final long MAX_MIN_FREE_GB = 1_000_000L;

    /** @param minFreeGb 세션 폴더 디스크 여유 하한(GB). 이보다 적으면 시작하지 않고 돌던 세션을 멈춘다 */
    public LiveLabService(Path experimentsRoot, Path repoRoot, Path sessionsRoot, Path feedSocket, Path stopRequest,
            CodeVersion code, Clock clock, FreeSpace freeSpace, long minFreeGb) {
        this.experimentsRoot = experimentsRoot.toAbsolutePath().normalize();
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.sessionsRoot = sessionsRoot.toAbsolutePath().normalize();
        this.feedSocket = Objects.requireNonNull(feedSocket, "feedSocket");
        this.stopRequest = Objects.requireNonNull(stopRequest, "stopRequest");
        this.code = Objects.requireNonNull(code, "code");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.freeSpace = Objects.requireNonNull(freeSpace, "freeSpace");
        // 0이면 하한이 꺼져 실전 엔진 기록과 같은 디스크를 채울 수 있다(QA PR #87 R2). 위 한도는 바이트 환산 넘침을 막는다
        if (minFreeGb < 1 || minFreeGb > MAX_MIN_FREE_GB) {
            throw new IllegalArgumentException(
                    "디스크 여유 하한(autotrade.lab.live.min-free-gb)은 1~" + MAX_MIN_FREE_GB + "GB여야 합니다: " + minFreeGb);
        }
        this.minFreeBytes = minFreeGb * 1024L * 1024L * 1024L;
    }

    /**
     * 등록 파일 {@code experiments/<id>/experiment.yaml}(input: live)로 세션을 시작한다. 등록 파일 검사까지만 여기서 하고, 입력
     * 기록으로 계좌를 되살리는 일과 게이트웨이 연결은 별도 스레드에서 한다(끝나면 상태가 {@code restoring=false}로 바뀜).
     *
     * @throws IllegalStateException 이미 세션이 돌고 있거나 감시가 정지를 요청했거나 디스크 여유가 모자랄 때
     * @throws IllegalArgumentException 등록 파일이 없거나 형식이 틀림
     */
    public synchronized View start(String id) {
        if (running != null) {
            throw new IllegalStateException("이미 실시간 모의 세션이 돌고 있습니다: " + running.plan.id());
        }
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("실험 ID 형식이 아닙니다");
        }
        if (lingering != null && lingering.isAlive()) {
            throw new IllegalStateException("앞서 멈춘 세션을 정리하는 중입니다. 잠시 뒤 다시 시작하세요");
        }
        Optional<String> stop = stopReason();
        if (stop.isPresent()) {
            throw new IllegalStateException(stop.get());
        }
        Path dir = experimentsRoot.resolve(id).normalize();
        Path file = dir.resolve("experiment.yaml");
        if (!dir.getParent().equals(experimentsRoot) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("등록 파일이 없습니다: experiments/" + id + "/experiment.yaml");
        }
        String yaml;
        try {
            yaml = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 파일이 다른 프로그램에 잠겼거나 권한이 없을 때. 사용자가 고칠 수 있는 상태라 500이 아니라 거절로 알린다
            throw new IllegalStateException("등록 파일을 읽을 수 없습니다(" + e.getClass().getSimpleName() + "): experiments/"
                    + id + "/experiment.yaml. 다른 프로그램이 파일을 잡고 있지 않은지, 읽기 권한이 있는지 확인하세요");
        }
        // 조합·녹화 실험 등록 파일이면 형식 검사의 "모르는 키" 대신 원인을 바로 알린다(5단계 QA R5). 목록과 같은 기준
        if (!isLiveRegistration(yaml)) {
            throw new IllegalArgumentException("실시간 세션 등록 파일(input: live)이 아닙니다: experiments/" + id
                    + "/experiment.yaml");
        }
        LivePlan plan = LivePlan.parse(yaml, "experiments/" + id + "/experiment.yaml");
        if (!plan.id().value().equals(id)) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다");
        }
        Running r = new Running(plan);
        running = r;
        lastStopReason = "";
        lastStopKind = StopKind.NONE;
        r.starter = Thread.ofPlatform().name("live-restore").daemon(true).priority(Thread.MIN_PRIORITY)
                .start(() -> openAndConnect(r));
        log.info("실시간 모의 세션 시작 요청: {} (계좌 {}개, 입력 기록 되살리는 중)", id,
                plan.profiles().size() + plan.selections().entries().size());
        return status();
    }

    /** 되살리기 스레드: 세션을 열고(기록일마다 정지 확인) 끝나면 받는 쪽과 처리 스레드를 시작한다. */
    private void openAndConnect(Running r) {
        LiveSession session;
        long restoreStart = System.nanoTime();
        try {
            session = LiveSession.open(r.plan, repoRoot, sessionsRoot, clock, m -> log.info("실시간 모의: {}", m), () -> {
                Optional<String> stop = r.cancelled ? Optional.of("되살리는 중 멈춤") : stopReason();
                if (stop.isPresent()) {
                    r.gateStop = stop.get();
                    throw new IllegalStateException(stop.get());
                }
            });
        } catch (RuntimeException e) {
            synchronized (this) {
                if (running == r) {
                    running = null;
                    // 정지 점검이 멈춘 것이면 그 사유(이 클래스가 만든 문구)를, 그 밖의 예외는 종류만 화면에 남긴다.
                    // 기록·잠금 파일 예외 메시지에는 절대 경로가 들어 있을 수 있다(PR #96 N2). 메시지 전체는 로그에만
                    lastStopReason = r.gateStop != null ? "시작 실패: " + r.gateStop
                            : "시작 실패(" + e.getClass().getSimpleName() + "). 실험실 로그를 확인하세요";
                    lastStopKind = StopKind.PROBLEM;
                    log.warn("실시간 모의 세션을 열지 못했습니다: {} ({}: {})", r.plan.id(), e.getClass().getSimpleName(),
                            e.getMessage());
                }
            }
            return;
        }
        synchronized (this) {
            if (running != r) { // 되살리는 동안 멈춤·종료됨
                session.close();
                return;
            }
            r.session = session;
            r.client = new FeedClient(feedSocket, Set.of(r.plan.market()), "lab", r::offer, clock);
            r.pump = Thread.ofPlatform().name("live-pump").daemon(true).priority(Thread.MIN_PRIORITY).unstarted(r::pump);
            r.pump.start();
            r.client.start();
            if (LiveSelectionPrep.hasSelections(r.plan)) {
                r.prep = Thread.ofPlatform().name("live-selection-prep").daemon(true).priority(Thread.MIN_PRIORITY)
                        .unstarted(r::prepareLoop);
                r.prep.start();
            }
            // 되살리기에 걸린 시간: 6단계 주간 점검이 재기동한 날 적는 값(절차서 stage-6-live-session.md). 앞 날 기록을 한 번 더
            // 읽는 비용(architect PR #94 R2)을 고칠지 이 값으로 판단한다
            log.info("실시간 모의 세션 연결: {} (되살린 이벤트 {}, 되살리기 {}초)", r.plan.id(), session.status().restored(),
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - restoreStart));
        }
    }

    /**
     * 사용자가 세션을 멈춘다(입력 기록을 닫음, {@link StopKind#NORMAL}). 다시 시작하면 기록으로 계좌를 되살린다. 돌고 있지 않으면
     * 아무것도 하지 않는다.
     */
    public synchronized void stop(String reason) {
        stop(reason, StopKind.NORMAL);
    }

    private synchronized void stop(String reason, StopKind kind) {
        if (running == null) {
            return;
        }
        Running r = running;
        running = null;
        r.halt();
        if (r.session != null) {
            r.session.close();
        }
        lingering = r.starter;
        lastStopReason = reason;
        lastStopKind = kind;
        log.info("실시간 모의 세션 멈춤: {} ({})", r.plan.id(), reason);
    }

    /**
     * 세션을 끝낸다: 마지막 거래일을 마감하고 상세 보고서를 {@code experiments/<id>/live-detail.txt}에 쓴다. 이 뒤로 같은 세션은
     * 이어 쓰지 않는다(리플레이 확인 {@code runLiveReplayCheck}으로 넘어감).
     *
     * @throws IllegalStateException 돌고 있는 세션이 없거나 아직 되살리는 중일 때
     * @throws ReportNotWrittenException 세션은 끝냈고(입력 기록은 정상 종료로 닫힘) 보고서만 못 썼을 때. 메시지는 화면에 보여도 된다
     */
    public synchronized Finished finish() {
        if (running == null) {
            throw new IllegalStateException("돌고 있는 실시간 모의 세션이 없습니다");
        }
        if (running.session == null) {
            throw new IllegalStateException("입력 기록으로 계좌를 되살리는 중입니다. 끝난 뒤 다시 하세요");
        }
        Running r = running;
        running = null;
        r.halt();
        SimulationReport report;
        try {
            report = r.session.finish(code);
        } catch (RuntimeException e) {
            // 마감이나 입력 기록 닫기 실패(메시지에 절대 경로가 있을 수 있어 사유에는 종류만 남김). 기록이 정상 종료로 닫혔는지 모른다
            lastStopReason = "끝내기 실패(" + e.getClass().getSimpleName() + "). 실험실 로그를 확인하세요";
            lastStopKind = StopKind.PROBLEM;
            log.warn("실시간 모의 세션을 끝내지 못했습니다: {} ({})", r.plan.id(), e.getClass().getSimpleName());
            throw e;
        }
        Path target = experimentsRoot.resolve(r.plan.id().value()).resolve(LiveSession.LIVE_DETAIL_FILE);
        String shown = "experiments/" + r.plan.id() + "/" + LiveSession.LIVE_DETAIL_FILE;
        try {
            Files.writeString(target, ReportWriter.render(report), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 세션은 이미 끝났고(기록은 정상 종료로 닫힘) 보고서만 못 썼다. 다시 시작하면 기록으로 되살린 뒤 끝내기를 다시 할 수 있다
            lastStopReason = "끝냈지만 보고서를 쓰지 못했습니다(" + e.getClass().getSimpleName() + "): " + shown;
            lastStopKind = StopKind.PROBLEM;
            log.warn("실시간 보고서를 쓸 수 없습니다: {} ({})", target, e.getClass().getSimpleName());
            throw new ReportNotWrittenException(
                    lastStopReason + ". 공간·권한을 확인한 뒤 세션을 다시 시작하고 끝내기를 다시 하세요", e);
        }
        lastStopReason = "끝냄";
        lastStopKind = StopKind.NORMAL;
        log.info("실시간 모의 세션 끝: {} → {}", r.plan.id(), target);
        long failed = report.runs().stream().filter(x -> !x.summary().failureReason().isEmpty()).count();
        return new Finished(r.plan.id().value(), shown, report.runs().size(), (int) failed);
    }

    /** 세션은 끝냈고 입력 기록도 정상 종료로 닫았지만 상세 보고서를 쓰지 못했다. 메시지에는 저장소 상대 경로만 있다. */
    public static final class ReportNotWrittenException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReportNotWrittenException(String message, IOException cause) {
            super(message, cause);
        }
    }

    /** 세션을 끝낸 결과. */
    public record Finished(String experimentId, String detailFile, int accounts, int failedAccounts) {}

    /** 화면용 상태. 돌고 있는 세션이 없으면 experimentId가 빈 문자열이고 session은 비어 있다. */
    public synchronized View status() {
        if (running == null) {
            return new View("", "", List.of(), false, Optional.empty(), ConnectionState.CLOSED, "", 0, 0, 0,
                    lastStopReason, lastStopKind, Optional.empty(), false, Optional.empty());
        }
        Running r = running;
        String market = r.plan.market().name();
        List<String> profiles = accounts(r.plan);
        if (r.session == null) {
            return new View(r.plan.id().value(), market, profiles, true, Optional.empty(), ConnectionState.NEW, "", 0, 0,
                    0, "", StopKind.NONE, Optional.ofNullable(r.lastPrep), LiveSelectionPrep.hasSelections(r.plan),
                    Optional.empty());
        }
        String reason = r.session.status().stopReason();
        return new View(r.plan.id().value(), market, profiles, false, Optional.of(r.session.status()), r.client.state(),
                r.client.lastError(), r.client.reconnectCount(), r.inbox.size(), r.inboxDropped.get(), reason,
                reason.isEmpty() ? StopKind.NONE : StopKind.PROBLEM, Optional.ofNullable(r.lastPrep),
                LiveSelectionPrep.hasSelections(r.plan), nextDay(r));
    }

    /**
     * 준비 스레드가 지금 준비할 거래일의 파일 상태(읽기만, D-124·D-126). 마지막 준비 결과는 메모리에만 있어 실험실을 다시 띄우면
     * 사라지지만, 준비 작업이 남긴 사유 파일은 남으므로 화면이 그것을 보인다. 장전 선정이 없거나 그날이 운용 기간 밖이거나 캘린더
     * 범위 밖이면 빈 값(화면은 멈추지 않는다).
     */
    private Optional<NextDay> nextDay(Running r) {
        Optional<com.autotrade.sim.selection.SessionSelections> files = r.session.selectionFiles();
        if (files.isEmpty()) {
            return Optional.empty();
        }
        try {
            if (r.calendar == null) {
                r.calendar = ExchangeCalendar.bundled(r.plan.market());
            }
            java.time.LocalDate date = LiveSelectionPrep.targetDate(r.calendar, clock.instant());
            if (date.isBefore(r.plan.from()) || date.isAfter(r.plan.to())) {
                return Optional.empty();
            }
            return Optional.of(new NextDay(date, files.get().prepared(date)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 다음 거래일(준비 스레드가 지금 준비할 거래일 T) 선정마다 세션 폴더에 놓인 파일 상태.
     *
     * @param items 선정 해시순
     */
    public record NextDay(java.time.LocalDate date, List<com.autotrade.sim.selection.SessionSelections.Prepared> items) {
        public NextDay {
            items = List.copyOf(items);
        }
    }

    /**
     * 화면의 "지금 준비": 준비 스레드를 깨워 장 밖 창이 아니어도 다음 거래일 준비를 한 번 한다(이벤트 처리와 별도 스레드).
     *
     * @throws IllegalStateException 돌고 있는 세션이 없거나 되살리는 중이거나 선정 조합 계좌가 없을 때
     */
    public synchronized void prepareNow() {
        if (running == null || running.session == null) {
            throw new IllegalStateException("연결된 실시간 모의 세션이 없습니다");
        }
        if (running.prep == null) {
            throw new IllegalStateException("이 세션에는 선정 조합 계좌(selections)가 없습니다");
        }
        running.prepRequested = true;
        java.util.concurrent.locks.LockSupport.unpark(running.prep);
    }

    /** 계좌 순서대로 이름: profiles 계좌 뒤에 선정 조합 계좌({@code 선정@버전 x 전략@버전}, D-124). */
    static List<String> accounts(LivePlan plan) {
        List<String> out = new ArrayList<>(plan.profiles().stream().map(RecordingPlan.ProfileRef::text).toList());
        plan.selections().entries().forEach(e -> out.add(e.text()));
        return List.copyOf(out);
    }



    /**
     * 멈춘 이유의 종류. 화면은 문구가 아니라 이것으로 경고 색을 고른다(PR #96 R5).
     */
    public enum StopKind {
        /** 멈춘 적 없음(이번 실험실 실행에서 세션을 끝내거나 멈추지 않았음). */
        NONE,
        /** 사용자가 끝냈거나 멈춤(화면의 멈춤·끝내기, 실험실 종료). */
        NORMAL,
        /** 문제로 멈춤(감시 정지 요청, 디스크 부족, 기록 실패, 시작·끝내기 실패, 보고서 쓰기 실패). 경고로 보인다. */
        PROBLEM
    }

    /**
     * @param experimentId 돌고 있는(되살리는 중 포함) 세션의 실험 ID, 없으면 빈 문자열
     * @param market 돌고 있는 세션의 시장({@code KRX}, {@code US}), 없으면 빈 문자열. 화면은 이 시장의 현지 시간으로 시각을 보인다
     * @param profiles 계좌 순서대로 전략 프로필({@code id@버전}). {@code session.runs()}의 같은 자리 계좌가 이 프로필로 돈다
     * @param restoring 입력 기록으로 계좌를 되살리는 중(아직 게이트웨이에 연결하지 않음)
     * @param feedState 게이트웨이 시세 전달 연결 상태
     * @param inboxQueued 받는 큐에 남은 수
     * @param inboxDropped 받는 큐가 차서 버린 수(공백으로 남음)
     * @param stopReason 마지막 멈춘 이유(감시 정지 요청, 디스크 부족, 기록 실패, 시작 실패 등)
     * @param stopKind {@code stopReason}의 종류. 세션이 도는 중에는 세션이 스스로 멈췄을 때만 {@link StopKind#PROBLEM}
     * @param prepare 선정 조합 계좌가 있으면 마지막 선정 준비 결과(D-124). 아직 준비하지 않았으면 비어 있다
     * @param dailySelections 장전 선정 조합 계좌가 있어 준비 스레드가 돈다("지금 준비" 버튼). 장중 순위 선정(D-127)만 있으면 false
     * @param nextDay 다음 거래일 선정 파일 상태(장전 선정이 있고 연결된 세션만). 실험실을 다시 띄워도 준비 사유 파일이 보인다
     */
    public record View(String experimentId, String market, List<String> profiles, boolean restoring,
            Optional<LiveSession.Status> session, ConnectionState feedState, String feedError, long feedReconnects,
            int inboxQueued, long inboxDropped, String stopReason, StopKind stopKind,
            Optional<LiveSelectionPrep.Report> prepare, boolean dailySelections, Optional<NextDay> nextDay) {
        public View {
            profiles = List.copyOf(profiles);
        }
    }

    /** 화면에 보여 줄 최근 공백 최대 개수. */
    public static final int MAX_GAPS_SHOWN = 200;

    /**
     * 돌고 있는 세션이 계좌에 준 공백(되살린 기록 포함) 중 최근 {@code limit}개, 최근 것부터. 세션이 없거나 되살리는 중이면 빈 목록.
     *
     * @param limit 1~{@value #MAX_GAPS_SHOWN}
     */
    public synchronized Gaps gaps(int limit) {
        if (limit < 1 || limit > MAX_GAPS_SHOWN) {
            throw new IllegalArgumentException("limit은 1~" + MAX_GAPS_SHOWN + "이어야 합니다");
        }
        if (running == null || running.session == null) {
            return new Gaps(0, List.of());
        }
        LiveSession session = running.session;
        List<GapView> items = new ArrayList<>();
        for (DataGap g : session.recentGaps(limit)) {
            items.add(new GapView(g.symbol().code(), g.reason(), g.gapStart(), g.gapEnd(), g.receivedAt()));
        }
        java.util.Collections.reverse(items);
        return new Gaps(session.status().dataGaps(), items);
    }

    /**
     * 돌고 있는 세션의 계좌 하나의 체결과 거절·취소 기록(계좌 상세 화면, D-120). 세션이 없거나 되살리는 중이거나 그 세션에 없는
     * 계좌면 비어 있다. 모의 계좌 기록만 읽으며 실전 주문 경로와 무관하다.
     */
    public synchronized Optional<LiveSession.RunActivity> activity(String runId) {
        if (running == null || running.session == null) {
            return Optional.empty();
        }
        return running.session.activity(runId);
    }

    /** @param total 세션 전체 공백 수(items는 그중 최근 것만) */
    public record Gaps(int total, List<GapView> items) {
        public Gaps {
            items = List.copyOf(items);
        }
    }

    /** 공백 하나: 종목, 사유, 빠진 구간, 받은 시각. */
    public record GapView(String symbol, DataGap.GapReason reason, Instant gapStart, Instant gapEnd, Instant receivedAt) {}

    /**
     * 실시간 모의 등록 파일({@code input: live})이 있는 실험 폴더(실험 ID 내림차순). 다른 입력의 등록 파일은 뺀다. 읽을 수 없거나
     * 형식이 틀린 실시간 등록 파일은 {@code error}에 사유를 담아 보인다(시작 버튼을 막는 데 쓴다).
     */
    public List<Registration> registrations() {
        if (!Files.isDirectory(experimentsRoot)) {
            return List.of();
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(experimentsRoot)) {
            dirs = s.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            throw new IllegalStateException("실험 폴더를 읽을 수 없습니다(" + e.getClass().getSimpleName() + ")");
        }
        List<Registration> out = new ArrayList<>();
        for (Path dir : dirs) {
            String id = dir.getFileName().toString();
            Path file = dir.resolve("experiment.yaml");
            if (!EXPERIMENT_ID.matcher(id).matches() || !Files.isRegularFile(file)) {
                continue;
            }
            String yaml;
            try {
                yaml = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue; // 실시간 등록인지 알 수 없다. 시작하면 같은 사유로 거절된다
            }
            if (!isLiveRegistration(yaml)) {
                continue;
            }
            boolean journal = Files.isDirectory(sessionsRoot.resolve(id));
            boolean finished = Files.isRegularFile(dir.resolve(LiveSession.LIVE_DETAIL_FILE));
            try {
                LivePlan plan = LivePlan.parse(yaml, "experiments/" + id + "/experiment.yaml");
                String error = plan.id().value().equals(id) ? null : "폴더 이름과 실험 ID가 다릅니다";
                out.add(new Registration(id, plan.question(), plan.from() + " ~ " + plan.to(),
                        accounts(plan), plan.boards().size(),
                        journal, finished, error));
            } catch (RuntimeException e) {
                out.add(new Registration(id, null, null, List.of(), 0, journal, finished,
                        e.getMessage() == null ? "등록 파일을 읽을 수 없습니다(" + e.getClass().getSimpleName() + ")"
                                : e.getMessage()));
            }
        }
        return out;
    }

    /**
     * 실시간 모의 등록 파일 하나.
     *
     * @param hasJournal 세션 입력 기록이 있음(시작하면 기록으로 계좌를 되살린 뒤 이어 받는다)
     * @param finished 끝내기로 상세 보고서({@value LiveSession#LIVE_DETAIL_FILE})를 이미 썼음
     * @param error 등록 파일 오류. 정상이면 null이고, 있으면 질문·기간이 null이다
     */
    public record Registration(String id, String question, String period, List<String> profiles, int symbols,
            boolean hasJournal, boolean finished, String error) {
        public Registration {
            profiles = List.copyOf(profiles);
        }
    }

    /** 감시 정지 요청이나 디스크 부족이면 그 이유. */
    Optional<String> stopReason() {
        if (!Files.notExists(stopRequest)) { // 있는지 판단할 수 없으면 멈추는 쪽
            return Optional.of("감시 프로세스가 자원 부족으로 정지를 요청했습니다(" + stopRequest.getFileName()
                    + "). 감시가 요청을 거두면 다시 시작하세요");
        }
        long free;
        try {
            Files.createDirectories(sessionsRoot);
            free = freeSpace.usableBytes(sessionsRoot);
        } catch (IOException | RuntimeException e) {
            return Optional.of("입력 기록 폴더의 디스크 여유를 확인할 수 없어 멈춥니다(" + e.getClass().getSimpleName() + ")");
        }
        if (free < minFreeBytes) {
            return Optional.of("입력 기록 디스크 여유가 " + (free >> 30) + "GB로 하한 " + (minFreeBytes >> 30)
                    + "GB보다 적어 멈춥니다. 실전 엔진 기록과 같은 디스크일 수 있습니다. 공간을 비운 뒤 다시 시작하세요");
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        Thread starter;
        synchronized (this) {
            starter = running == null ? null : running.starter;
            stop("실험실 종료");
        }
        if (starter != null) {
            try {
                starter.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 돌고 있는 세션 하나와 그 받는 쪽·처리 스레드. session·client·pump는 되살리기가 끝난 뒤 정해진다. */
    private final class Running {
        private final LivePlan plan;
        private final BlockingQueue<Item> inbox = new ArrayBlockingQueue<>(INBOX_CAPACITY);
        /** 받는 큐가 차서 버리기 시작한 시각(종목별). 받는 쪽 스레드에서만 쓴다. */
        private final Map<Symbol, Instant> overflowSince = new HashMap<>();
        private final AtomicLong inboxDropped = new AtomicLong();
        private Thread starter;
        private LiveSession session;
        private FeedClient client;
        private Thread pump;
        /** 선정 준비 스레드(선정 조합 계좌가 있을 때만). */
        private Thread prep;
        private volatile boolean prepRequested;
        private volatile LiveSelectionPrep.Report lastPrep;
        /** 화면의 다음 거래일 계산용 캘린더(처음 볼 때 한 번 읽음, 서비스 잠금 안에서만). */
        private ExchangeCalendar calendar;
        private volatile boolean halted;
        private volatile boolean cancelled;
        /** 되살리기 정지 점검이 세션 열기를 멈춘 사유(되살리기 스레드만 쓰고 읽음). */
        private String gateStop;

        Running(LivePlan plan) {
            this.plan = plan;
        }

        /** 받는 쪽 스레드: 넣기만 하고 기다리지 않는다. */
        void offer(String sourceId, MarketEvent e) {
            Instant since = overflowSince.get(e.symbol());
            if (since != null) {
                DataGap gap = new DataGap(e.symbol(), 0, e.receivedAt(), e.receivedAt(), since, e.receivedAt(),
                        DataGap.GapReason.CONSUMER_OVERFLOW);
                if (!inbox.offer(new Item(LiveSession.INBOX_SOURCE, gap))) {
                    inboxDropped.incrementAndGet();
                    return;
                }
                overflowSince.remove(e.symbol());
            }
            if (!inbox.offer(new Item(sourceId, e))) {
                inboxDropped.incrementAndGet();
                overflowSince.putIfAbsent(e.symbol(), e.receivedAt());
            }
        }

        /** 처리 스레드: 큐에서 꺼내 세션에 준다. 1초마다 기록을 내리고 감시 정지 요청과 디스크 여유를 본다. */
        void pump() {
            long lastCheck = System.nanoTime();
            while (!halted) {
                Item item;
                try {
                    item = inbox.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (item != null && session.accept(item.sourceId(), item.event()) == LiveSession.Accepted.STOPPED
                        && !session.status().stopReason().isEmpty()) {
                    stopFromPump(session.status().stopReason());
                    return;
                }
                if (System.nanoTime() - lastCheck >= TimeUnit.SECONDS.toNanos(1)) {
                    lastCheck = System.nanoTime();
                    session.flush();
                    Optional<String> stop = stopReason();
                    if (stop.isPresent()) {
                        stopFromPump(stop.get());
                        return;
                    }
                }
            }
        }

        /**
         * 준비 스레드: 장 밖 창이거나 화면이 요청했을 때 다음 거래일 선정 결과를 준비한다. 정지 요청·디스크 부족이면 건너뛴다.
         * 실패는 사유로만 남기고 세션은 멈추지 않는다(그날 결과가 없으면 선정 계좌만 {@code SELECTION_MISSING}).
         */
        void prepareLoop() {
            ExchangeCalendar calendar = ExchangeCalendar.bundled(plan.market());
            while (!halted) {
                Instant now = clock.instant();
                boolean asked = prepRequested;
                prepRequested = false;
                if (asked || LiveSelectionPrep.inQuietHours(calendar, now)) {
                    prepareOnce(calendar, now);
                }
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MINUTES.toNanos(PREPARE_INTERVAL_MINUTES));
            }
        }

        private void prepareOnce(ExchangeCalendar calendar, Instant now) {
            try {
                if (stopReason().isPresent()) {
                    return;
                }
                java.time.LocalDate date = LiveSelectionPrep.targetDate(calendar, now);
                if (date.isBefore(plan.from()) || date.isAfter(plan.to())) {
                    return;
                }
                long maxHeap = Runtime.getRuntime().maxMemory();
                lastPrep = LiveSelectionPrep.prepare(plan, repoRoot, sessionsRoot, sessionsRoot.getParent()
                        .resolve(SNAPSHOTS_DIR), date, new SelectionResultFile.Producer(code.commit(), code.dirty(), now),
                        LiveLabService.this::stopReason, rows -> LiveSelectionPrep.heapFits(rows, maxHeap),
                        m -> log.info("실시간 모의: {}", m));
            } catch (RuntimeException e) {
                // 정지 관문으로 멈춘 경우도 여기로 온다. 다음 차례에 다시 본다. 메시지에 경로가 있을 수 있어 로그에만
                log.warn("선정 준비를 하지 못했습니다: {} ({}: {})", plan.id(), e.getClass().getSimpleName(), e.getMessage());
            }
        }

        private void stopFromPump(String reason) {
            Thread.ofPlatform().daemon(true).start(() -> {
                synchronized (LiveLabService.this) {
                    if (running == this) {
                        stop(reason, StopKind.PROBLEM);
                    }
                }
            });
        }

        /** 받는 쪽과 처리 스레드를 멈춘다. 큐에 남은 이벤트는 버린다(기록에 없으므로 다시 시작해도 어긋나지 않는다). */
        void halt() {
            halted = true;
            cancelled = true;
            if (prep != null) {
                java.util.concurrent.locks.LockSupport.unpark(prep);
            }
            if (client != null) {
                client.close();
            }
            // 처리 스레드는 끼어들기(interrupt)로 멈추지 않는다: 기록 파일 쓰기 중에 끼어들면 파일 채널이 닫혀 기록이 깨진다.
            // 큐를 0.5초마다 보므로 halted를 보고 스스로 끝난다
            if (pump != null && Thread.currentThread() != pump) {
                try {
                    pump.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record Item(String sourceId, MarketEvent event) {}

    /**
     * 실시간 세션 등록 파일인지(앞뒤 공백을 뺀 한 줄이 {@code input: live}). 목록({@link #registrations()})과
     * 시작({@code start}) 사전 검사가 같은 기준을 쓰도록 여기에만 둔다(PR #156 architect R1).
     */
    private static boolean isLiveRegistration(String yaml) {
        return yaml.lines().map(String::strip).anyMatch(l -> LIVE_INPUT.matcher(l).matches());
    }
}
