package com.autotrade.sim.live;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.recording.EventSequences;
import com.autotrade.marketdata.recording.JournalWriter;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.recording.RecordedMarketDataSet;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunStepper;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.SelectionSchedule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 실시간 모의 세션(ADR-010 2.3~2.5, D-076, SIM-03·13·15). 게이트웨이에서 전달받은 실시간 시세 위에서 등록 파일의 조합마다 모의
 * 계좌 하나를 돌린다.
 *
 * <p><b>입력 기록 먼저</b>. 이벤트를 받으면 계좌에 주기 전에 세션 폴더의 입력 기록({@link JournalWriter}, 녹화 형식)에 먼저 쓰고,
 * 기록에 쓴 줄(재기동 공백 포함)을 그 순서대로 계좌에 준다. 이벤트 순번은 기록 전체의 위치(1부터)로 매긴다. 녹화 데이터셋
 * ({@link RecordedMarketDataSet})이 기록을 읽을 때 매기는 순번과 같다. 계좌 처리는 리플레이 실행기와 같은 {@link RunStepper}다.
 * 그래서 기록을 리플레이 실행기로 다시 돌리면 상세 보고서가 바이트까지 같아야 한다({@link LiveReplayCheck}).
 *
 * <p><b>재기동</b>. 실험실이 다시 뜨면 기록을 처음부터 다시 돌려 계좌 상태를 되살린 뒤 이어서 받는다. 상태는 기록에서만 나오므로
 * 비정상 종료로 기록 꼬리(마지막 flush 뒤)가 사라져도 되살린 상태와 기록이 어긋나지 않는다. 멈춘 구간은 재기동 공백
 * (RECORDER_RESTART)으로 남는다. 다음 날로 넘어가 이어 받으면 새 날 첫 줄 앞에 앞 날 마지막 수신 시각부터의 공백이 남는다.
 *
 * <p><b>받지 않는 이벤트</b>: 등록 파일 boards에 없는 종목, 운용 기간(거래일과 수신 날짜 모두) 밖. 기록에도 쓰지 않고 건수만 센다.
 *
 * <p>계좌 하나가 예외로 실패하면 그 계좌만 멈추고 나머지는 계속한다(계좌 격리). 기록 쓰기가 실패하면 세션 전체가 멈춘다(그 뒤로는
 * 리플레이와 같은 결과를 보장할 수 없으므로). 세션 폴더당 한 프로세스만 연다({@value #LOCK_FILE} 파일 잠금).
 *
 * <p>모든 공개 메서드는 이 객체로 직렬화한다. 주문 API·증권사를 쓰지 않는다(MD-01, 모의 계좌만).
 */
public final class LiveSession implements AutoCloseable {

    public static final String LOCK_FILE = "session.lock";
    public static final String JOURNAL_DIR = "journal";
    /** 실험실이 받는 큐가 넘쳐 버린 구간 공백의 출처 이름(실험실 쪽에서 만든다). */
    public static final String INBOX_SOURCE = "LAB-INBOX";
    /** 실시간 세션이 쓰는 상세 보고서. 리플레이 확인({@link LiveReplayCheck})이 같은지 본다. */
    public static final String LIVE_DETAIL_FILE = "live-detail.txt";
    static final Duration FLUSH_INTERVAL = Duration.ofSeconds(1);
    static final String LICENSE = "증권사 Open API 시세(게이트웨이 전달). 개인 연구용 저장만, 재배포 금지(ADR-003 4장)";

    private final LiveSetup setup;
    private final ExperimentSpec spec;
    private final Path sessionDir;
    private final RecordingLayout layout;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final JournalWriter journal;
    private final List<RunStepper> runs = new ArrayList<>();
    private final RunReport[] failed;
    private final List<DataGap> gaps = new ArrayList<>();
    private boolean begun;
    private long position;
    private long restored;
    private long skippedSymbol;
    private long skippedRange;
    private BigDecimal benchFirst;
    private BigDecimal benchLast;
    private Instant lastFlush;
    private Instant lastEventAt;
    private String stopReason = "";
    private boolean closed;

    private LiveSession(LiveSetup setup, Path sessionDir, Clock clock, FileChannel lockChannel, FileLock lock) {
        this.setup = setup;
        this.spec = setup.spec(ClockMode.REALTIME, LiveSetup.LIVE_DATA_VERSION_PREFIX + setup.plan().id());
        this.sessionDir = sessionDir;
        this.layout = new RecordingLayout(sessionDir.resolve(JOURNAL_DIR));
        this.clock = clock;
        this.lockChannel = lockChannel;
        this.lock = lock;
        if (spec.combinations().size() > SimulationExperimentRunner.MAX_COMBINATIONS) {
            throw new IllegalArgumentException("조합 수가 실험실 상한을 넘습니다(SIM-14): " + spec.combinations().size());
        }
        for (int i = 0; i < spec.combinations().size(); i++) {
            runs.add(new RunStepper(spec, i, setup.rules(), setup.cost(), setup.execution(), setup.factory(),
                    setup.risk(), true, SelectionSchedule.empty()));
        }
        this.failed = new RunReport[runs.size()];
        this.journal = new JournalWriter(layout, setup.plan().market(), setup.rules().zone(), Map.of(
                FeedServer.SOURCE_ID, "게이트웨이 전달 큐 넘침 공백",
                FeedClient.SOURCE_ID, "실험실 받는 쪽 끊김 공백",
                INBOX_SOURCE, "실험실 받는 큐 넘침 공백"), LICENSE);
        this.lastFlush = clock.instant();
    }

    /**
     * 세션을 연다. 세션 폴더({@code <sessionsRoot>/<실험ID>})에 입력 기록이 있으면 처음부터 다시 돌려 계좌를 되살린다.
     *
     * @param repoRoot 저장소 루트({@code strategies/}가 있는 곳)
     * @param sessionsRoot 세션 폴더들의 부모(설정 폴더 아래 {@code live-sessions}, 저장소 밖)
     * @param clock 기록 메타와 flush 간격에만 쓴다. 계좌는 이벤트 수신 시각으로 움직인다(결정론)
     * @throws IllegalArgumentException 등록 파일과 입력이 맞지 않을 때
     * @throws IllegalStateException 다른 프로세스가 이 세션을 열고 있을 때
     */
    public static LiveSession open(LivePlan plan, Path repoRoot, Path sessionsRoot, Clock clock, Consumer<String> log) {
        return open(plan, repoRoot, sessionsRoot, clock, log, () -> { });
    }

    /**
     * {@link #open(LivePlan, Path, Path, Clock, Consumer)}과 같고, 입력 기록을 되살리는 동안 기록일 하나를 읽기 전마다
     * {@code beforeEachDay}를 부른다. 정지 요청 등으로 멈춰야 하면 거기서 예외를 던진다(세션은 열리지 않고 잠금이 풀린다).
     */
    public static LiveSession open(LivePlan plan, Path repoRoot, Path sessionsRoot, Clock clock, Consumer<String> log,
            Runnable beforeEachDay) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(beforeEachDay, "beforeEachDay");
        LiveSetup setup = LiveSetup.of(plan, repoRoot);
        Path dir = sessionsRoot.toAbsolutePath().resolve(plan.id().value());
        FileChannel ch = null;
        FileLock lock;
        try {
            Files.createDirectories(dir);
            ch = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                ch.close();
                throw new IllegalStateException("다른 실험실이 이 실시간 모의 세션을 열고 있습니다: " + plan.id());
            }
        } catch (IOException e) {
            closeQuietly(ch);
            throw new UncheckedIOException("세션 폴더를 열 수 없습니다: " + dir, e);
        }
        LiveSession session;
        try {
            session = new LiveSession(setup, dir, clock, ch, lock);
            session.restore(log, beforeEachDay);
        } catch (RuntimeException e) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // 닫으면서 풀린다
            }
            closeQuietly(ch);
            throw e;
        }
        return session;
    }

    /**
     * 입력 기록이 있으면 처음부터 다시 돌린다(기록에는 쓰지 않음). 메모리를 아끼려고 기록일 하나씩 읽는다. 녹화 어댑터는 날짜마다
     * 따로 처리하고 순번만 이어 매기므로, 하루씩 읽어 앞 날짜 이벤트 수만큼 순번을 밀면 전체를 한 번에 읽은 것과 같다
     * (리플레이 확인이 쓰는 {@link RecordedMarketDataSet#fromDirs} 전체 읽기와 같은 순번).
     */
    private void restore(Consumer<String> log, Runnable beforeEachDay) {
        List<Path> days = journalDays();
        if (days.isEmpty()) {
            log.accept("새 세션: " + setup.plan().id() + " (" + runs.size() + "계좌)");
            return;
        }
        long offset = 0;
        Path lastWithLines = null;
        for (Path day : days) {
            beforeEachDay.run();
            RecordedMarketDataSet data = RecordedMarketDataSet.fromDirs(setup.plan().market(), List.of(day));
            if (!data.events().isEmpty()) {
                lastWithLines = day;
            }
            for (MarketEvent e : data.events()) {
                if (!inPlan(e)) {
                    throw new IllegalStateException("입력 기록에 운용 범위 밖 이벤트가 있습니다: " + day + " seq " + e.sequence());
                }
                step(EventSequences.withSequence(e, offset + e.sequence()));
            }
            offset += data.events().size();
        }
        restored = position;
        // 다음 날로 넘어가 이어 받으면 앞 날 나머지가 빠진 것을 공백으로 남긴다(같은 날이면 기록기가 이미 남긴다).
        // 새 날 파일만 열고 첫 줄을 내리기 전에 죽어 읽을 줄이 없는 날은 건너뛰고, 줄이 있는 마지막 날을 기준으로 한다
        // (risk-ops PR #94 N1, architect R1)
        if (lastWithLines != null) {
            journal.resumeAfter(LocalDate.parse(lastWithLines.getFileName().toString()));
        }
        log.accept("입력 기록으로 계좌를 되살렸습니다: 이벤트 " + restored + ", 기록일 " + days.size());
    }

    /** 입력 기록 날짜 폴더(날짜 오름차순). 운용 기간 안만 본다. */
    List<Path> journalDays() {
        List<Path> out = new ArrayList<>();
        for (LocalDate d = setup.plan().from(); !d.isAfter(setup.plan().to()); d = d.plusDays(1)) {
            Path day = layout.dayDir(setup.plan().market(), d);
            if (Files.isRegularFile(day.resolve(RecordingLayout.EVENTS_FILE))) {
                out.add(day);
            }
        }
        return out;
    }

    /** 받은 이벤트 처리 결과. */
    public enum Accepted {
        /** 기록하고 계좌에 줌. */
        ACCEPTED,
        /** boards에 없는 종목이라 뺌. */
        SKIPPED_SYMBOL,
        /** 운용 기간 밖이라 뺌. */
        SKIPPED_RANGE,
        /** 세션이 멈춰 있어 받지 않음. */
        STOPPED
    }

    /**
     * 이벤트 하나를 받는다: 기록에 쓰고, 쓴 줄을 계좌에 준다.
     *
     * @param sourceId 원래 소스 이름(녹화의 {@code src})
     */
    public synchronized Accepted accept(String sourceId, MarketEvent event) {
        if (closed || !stopReason.isEmpty()) {
            return Accepted.STOPPED;
        }
        if (!setup.plan().boards().containsKey(event.symbol().code())
                || event.symbol().market() != setup.plan().market()) {
            skippedSymbol++;
            return Accepted.SKIPPED_SYMBOL;
        }
        if (!inPlan(event)) {
            skippedRange++;
            return Accepted.SKIPPED_RANGE;
        }
        Optional<BigDecimal> offTick = RecordingExperiment.offStockTick(setup.rules(), event);
        if (offTick.isPresent()) {
            // 계좌는 모든 종목을 주식 호가 단위로 본다(D-075 4번). ETF·ETN이 boards에 있으면 결과가 조용히 틀리므로 멈춘다
            stopReason = "주식 호가 단위에 맞지 않는 가격이 와서 세션을 멈췄습니다: " + event.symbol().code() + " "
                    + offTick.get().toPlainString() + ". ETF·ETN 같은 주식 밖 종목은 아직 받지 않으니 boards에서 빼고 새 실험 ID로 "
                    + "등록하세요(기록에는 쓰지 않음)";
            return Accepted.STOPPED;
        }
        List<MarketEvent> written;
        try {
            written = journal.append(sourceId, event, clock.instant());
        } catch (RuntimeException e) {
            stopReason = "입력 기록 쓰기 실패로 세션을 멈췄습니다(" + e.getClass().getSimpleName()
                    + "). 원인을 고친 뒤 다시 시작하면 기록으로 계좌를 되살립니다";
            return Accepted.STOPPED;
        }
        for (MarketEvent e : written) {
            step(EventSequences.withSequence(e, position + 1));
        }
        lastEventAt = event.receivedAt();
        Instant now = clock.instant();
        if (Duration.between(lastFlush, now).compareTo(FLUSH_INTERVAL) >= 0) {
            flushQuietly();
            lastFlush = now;
        }
        return Accepted.ACCEPTED;
    }

    /** 이벤트 하나를 모든 계좌에 준다. {@code e.sequence()}는 기록 전체 위치여야 한다. */
    private void step(MarketEvent e) {
        position = e.sequence();
        if (!begun) {
            begun = true;
            for (int i = 0; i < runs.size(); i++) {
                try {
                    runs.get(i).begin(e.receivedAt(), List.of());
                } catch (RuntimeException ex) {
                    failed[i] = runs.get(i).failed(ex);
                }
            }
        }
        if (e instanceof DataGap g) {
            gaps.add(g);
        }
        if (e instanceof TradeTick t && t.symbol().equals(setup.benchmark())) {
            if (benchFirst == null) {
                benchFirst = t.price();
            }
            benchLast = t.price();
        }
        for (int i = 0; i < runs.size(); i++) {
            if (failed[i] != null) {
                continue;
            }
            try {
                runs.get(i).onEvent(e, () -> { });
            } catch (RuntimeException ex) {
                failed[i] = runs.get(i).failed(ex); // 계좌 격리: 이 계좌만 멈춘다
            }
        }
    }

    private boolean inPlan(MarketEvent e) {
        LocalDate from = setup.plan().from();
        LocalDate to = setup.plan().to();
        LocalDate received = e.receivedAt().atZone(setup.rules().zone()).toLocalDate();
        if (received.isBefore(from) || received.isAfter(to)) {
            return false;
        }
        LocalDate trading;
        try {
            trading = setup.rules().tradingDate(e.exchangeTime());
        } catch (RuntimeException ex) {
            return false; // 캘린더 수록 범위 밖 등 판단할 수 없으면 받지 않는다
        }
        return !trading.isBefore(from) && !trading.isAfter(to);
    }

    /** 쓴 기록을 디스크로 내린다(받을 이벤트가 없을 때 실험실이 주기적으로 부른다). */
    public synchronized void flush() {
        if (!closed) {
            flushQuietly();
            lastFlush = clock.instant();
        }
    }

    private void flushQuietly() {
        try {
            journal.flush();
        } catch (RuntimeException e) {
            stopReason = "입력 기록을 디스크에 내리지 못해 세션을 멈췄습니다(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** 화면용 현재 상태. */
    public synchronized Status status() {
        List<RunStepper.Progress> progress = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            progress.add(runs.get(i).progress());
            failures.add(failed[i] == null ? "" : failed[i].summary().failureReason());
        }
        return new Status(setup.plan().id().value(), setup.plan().from(), setup.plan().to(), position, restored,
                skippedSymbol, skippedRange, gaps.size(), lastEventAt, journal.currentDate(), stopReason, closed,
                progress, failures);
    }

    /**
     * 계좌에 준 공백 중 최근 것(화면용, 오래된 것부터). 되살린 기록의 공백도 들어 있다.
     *
     * @param limit 돌려줄 최대 개수(1 이상)
     */
    public synchronized List<DataGap> recentGaps(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit은 1 이상이어야 합니다: " + limit);
        }
        return List.copyOf(gaps.subList(Math.max(0, gaps.size() - limit), gaps.size()));
    }

    /**
     * 세션 상태.
     *
     * @param events 계좌에 준 이벤트 수(기록 줄 수, 되살린 것 포함)
     * @param restored 시작 때 기록으로 되살린 이벤트 수
     * @param failures 계좌마다 실패 이유(정상이면 빈 문자열)
     */
    public record Status(String experimentId, LocalDate from, LocalDate to, long events, long restored,
            long skippedSymbol, long skippedRange, int dataGaps, Instant lastEventAt, LocalDate journalDate,
            String stopReason, boolean closed, List<RunStepper.Progress> runs, List<String> failures) {
        public Status {
            runs = List.copyOf(runs);
            failures = List.copyOf(failures);
        }
    }

    /**
     * 세션을 끝내고 상세 보고서({@link ReportWriter} 정규 형식)를 돌려준다. 마지막 거래일을 마감하고 기록을 정상 종료로 닫는다.
     * 이 뒤로는 이벤트를 받지 않는다. 보고서는 결과 폴더의 {@value #LIVE_DETAIL_FILE}로 남기고, 리플레이 확인
     * ({@link LiveReplayCheck})이 기록을 다시 돌려 같은지 본다.
     */
    public synchronized SimulationReport finish(CodeVersion code) {
        if (closed) {
            throw new IllegalStateException("이미 닫힌 세션입니다");
        }
        if (!begun) {
            // 이벤트가 하나도 없었으면 리플레이 실행기와 같이 시작일 0시로 시작한다
            Instant start = setup.plan().from().atStartOfDay(setup.rules().zone()).toInstant();
            begun = true;
            for (int i = 0; i < runs.size(); i++) {
                try {
                    runs.get(i).begin(start, List.of());
                } catch (RuntimeException ex) {
                    failed[i] = runs.get(i).failed(ex);
                }
            }
        }
        BigDecimal bench = benchFirst == null ? null
                : benchLast.divide(benchFirst, MathContext.DECIMAL128).subtract(BigDecimal.ONE);
        List<RunReport> reports = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            if (failed[i] != null) {
                reports.add(failed[i]);
                continue;
            }
            try {
                reports.add(runs.get(i).finish(bench, false));
            } catch (RuntimeException ex) {
                reports.add(runs.get(i).failed(ex));
            }
        }
        ExperimentResult result = new ExperimentResult(spec.id(), code.commit(), code.dirty(),
                setup.cost().tableHash(spec.from(), spec.to()), setup.execution().id(),
                reports.stream().map(RunReport::summary).toList());
        close();
        return new SimulationReport(result, reports, gaps);
    }

    /** 기록을 닫고 잠금을 푼다. 계좌 상태는 버린다(다시 열면 기록으로 되살린다). */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            journal.close(clock.instant());
        } finally {
            try {
                lock.release();
            } catch (IOException ignored) {
                // 채널을 닫으면 풀린다
            }
            closeQuietly(lockChannel);
        }
    }

    public Path sessionDir() {
        return sessionDir;
    }

    /** 입력 기록 폴더(녹화 폴더 구조). */
    public Path journalRoot() {
        return layout.root();
    }

    /** 계좌에 준 마지막 이벤트의 순번(기록 전체 위치). */
    long position() {
        return position;
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) {
            return;
        }
        try {
            ch.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }
}
