package com.autotrade.sim.live;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.recording.EventSequences;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.recording.RecordedDayStream;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.MarketEventStream;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 6단계 주간 점검(D-088, ADR-010 4장 "실험실이 본 입력 ≠ 게이트웨이 녹화"): 실시간 모의 세션의 입력 기록과 같은 날 게이트웨이
 * 녹화를 대조해 한 장으로 묶는다. 실험실 상태 화면의 {@code dataGaps}·{@code inboxDropped}·{@code feedReconnects}는 실험실을
 * 다시 띄우면 사라지는 메모리 값이라 손으로 옮기면 빠지기 쉽다. 이 점검은 같은 내용을 디스크에 남은 두 기록에서 다시 센다.
 *
 * <ol>
 *   <li><b>입력 대조</b>(날마다): 녹화에서 등록 파일 boards·운용 기간에 드는 줄과 입력 기록의 줄을 원래 소스·소스 순번·내용으로
 *       맞춘다(전달은 녹화와 같은 줄 형식이고 수신 시각도 게이트웨이 값 그대로, ADR-010 2.2). 녹화에만 있는 줄은 실험실이 받지
 *       못한 이벤트이고, 어느 실험실 쪽 공백 안에 드는지로 원인을 나눈다. 기록에만 있는 줄은 녹화기가 버린 구간(녹화 공백) 안이
 *       아니면 전달 결함이다.</li>
 *   <li><b>공백 출처</b>(날마다): 입력 기록에 실험실 쪽이 남긴 공백 줄을 출처별로 센다. 받는 쪽 끊김(FEED-CLIENT)은 재연결
 *       횟수({@code feedReconnects}에 해당), 실험실 받는 큐 넘침(LAB-INBOX)으로 빠진 이벤트 수는 {@code inboxDropped}에 해당한다.</li>
 *   <li><b>계좌 대조</b>(운용 시작부터 점검 마지막 날까지 누계): 같은 등록 파일·같은 코드로 입력 기록 리플레이(= 실시간 결과,
 *       D-076 합격선)와 같은 날 녹화 리플레이(boards만)를 돌려 계좌별 성과를 나란히 놓는다. 차이는 빠진 이벤트가 성과에 준
 *       영향이다(SIM-13).</li>
 * </ol>
 *
 * <p>메모리는 기간 길이와 상관없다(D-115): 기록과 녹화를 날마다 줄 단위로 읽고 버리며, 계좌 대조도 날마다 디스크에서 다시 읽는다.
 * 대신 같은 파일을 여러 번 푼다. 무거우므로(녹화를 하루씩 다 읽고 리플레이를 두 번 돈다) 실험 명령({@code runLiveWeeklyCheck}, D-063 장 시간 보호·정지
 * 요청 확인)으로만 돌린다. 세션이 열려 있어도 돌 수 있다: 파일을 읽기 전후 해시가 다르면(아직 쓰이는 중) 그날을 거부한다.
 * 결과는 이벤트 내용 없이 건수와 성과만 담으므로 커밋해도 된다(시세 재배포 아님, ADR-003 4장).
 */
public final class LiveWeeklyCheck {

    /** 결과 폴더 안 주간 점검 폴더. 파일 이름은 점검 마지막 날({@code <날짜>.md}). */
    public static final String WEEKLY_DIR = "weekly";
    /** 녹화기가 직접 남기는 공백 줄(재기동, 녹화 큐 넘침)의 소스 이름({@code DayWriter}와 같음). */
    static final String RECORDER_SOURCE = "RECORDER";
    /** 실험실 쪽(전달·받는 쪽·받는 큐·세션 재기동)이 입력 기록에 남기는 공백 출처. 녹화에는 없다. */
    static final List<String> LAB_GAP_SOURCES =
            List.of(FeedClient.SOURCE_ID, RECORDER_SOURCE, FeedServer.SOURCE_ID, LiveSession.INBOX_SOURCE);
    /**
     * 그 공백이 모든 종목의 수신이 끊긴 구간인지(끊김·재기동). 아니면(큐 넘침) 그 종목만. 끊김·재기동 공백 줄은 앞 구간에 나온
     * 종목마다 하나씩이지만(FeedClient, DayWriter), 그 사이에는 연결이나 프로세스 자체가 없어 처음 나오는 종목도 받지 못했다. 그래서
     * 종목과 상관없이 그 시각 구간으로 원인을 매긴다(architect PR #107 N3). 큐 넘침은 버리기 시작한 종목에만 공백이 남고 다른
     * 종목은 계속 들어오므로 그 종목만 본다.
     */
    private static final Set<String> ALL_SYMBOL_SOURCES = Set.of(FeedClient.SOURCE_ID, RECORDER_SOURCE);
    static final String OUTSIDE_SESSION = "세션 밖";
    static final String UNEXPLAINED = "설명 없음";

    private LiveWeeklyCheck() {
    }

    /**
     * 하루 대조 결과.
     *
     * @param journalLines 입력 기록 줄 수(공백 줄 포함)
     * @param recordedEvents 녹화 줄 중 boards·운용 기간에 들고 녹화기 공백이 아닌 것(실험실이 받았어야 할 줄)
     * @param matched 둘 다에 있는 줄
     * @param missing 녹화에만 있는 줄의 원인별 수({@value #OUTSIDE_SESSION}, 실험실 공백 출처, {@value #UNEXPLAINED})
     * @param extraInRecorderGap 기록에만 있고 녹화 공백(녹화기가 버린 구간) 안인 줄
     * @param extraUnexplained 기록에만 있고 녹화 공백으로 설명되지 않는 줄(전달 결함)
     * @param labGaps 실험실 쪽 공백 줄 수(출처별)
     * @param forwardedGaps 녹화에서 전달된 공백 줄 수(증권사 끊김, 소스 전환 등)
     * @param reconnects 받는 쪽 재연결 횟수(FEED-CLIENT 공백의 서로 다른 구간 수)
     * @param journalPresent 같은 날 입력 기록이 있는지(없으면 실험실이 그날 세션을 열지 않았고, 녹화 줄은 모두 빠짐이다)
     * @param recordingPresent 같은 날 녹화가 있는지(없으면 대조 칸은 비어 있다)
     */
    public record DayRow(LocalDate date, boolean journalPresent, long journalLines, long recordedEvents, long matched, Map<String, Long> missing,
            long extraInRecorderGap, long extraUnexplained, Map<String, Long> labGaps, long forwardedGaps, int reconnects,
            boolean journalComplete, boolean recordingPresent, boolean recordingComplete) {
        public DayRow {
            Objects.requireNonNull(date, "date");
            missing = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(missing));
            labGaps = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(labGaps));
        }

        public long missingTotal() {
            return missing.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * 점검 결과.
     *
     * @param defect 설명되지 않는 차이가 있는지(녹화에 없는 기록 줄, 공백 없이 빠진 이벤트). 있으면 명령이 종료 코드 2를 낸다
     * @param accountsCompared 계좌 대조를 했는지(못 했으면 그 이유가 보고서에 있다)
     * @param collection 수집 멈춤 여부(D-105). 종료 코드에는 넣지 않는다(명령이 콘솔에 따로 알린다)
     */
    public record Outcome(LocalDate from, LocalDate to, List<DayRow> days, boolean defect, boolean accountsCompared,
            CollectionFreshness.Status collection, String report) {
        public Outcome {
            days = List.copyOf(days);
        }
    }

    /**
     * @param experimentDir {@code experiments/<실험ID>}
     * @param sessionsRoot 세션 폴더들의 부모(설정 폴더 아래 {@code live-sessions})
     * @param recordingsRoot 게이트웨이 녹화 폴더(설정 폴더 아래 {@code recordings})
     * @param snapshotsRoot 수집 스냅샷 폴더(설정 폴더 아래 {@code snapshots}). 수집 멈춤 여부(D-105)에 쓴다
     * @param to 점검 마지막 날. 비어 있으면 운용 기간 안 입력 기록일과 녹화일 중 마지막 날
     * @throws IllegalArgumentException 입력이 맞지 않을 때(등록 파일, 입력 기록 없음 등)
     */
    public static Outcome run(Path experimentDir, Path repoRoot, Path sessionsRoot, Path recordingsRoot,
            Path snapshotsRoot, Optional<LocalDate> to, CodeVersion code, Consumer<String> log, RunGate gate) {
        Path file = experimentDir.resolve("experiment.yaml");
        LivePlan plan;
        try {
            plan = LivePlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 읽을 수 없습니다: " + file, e);
        }
        if (!plan.id().value().equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + plan.id());
        }
        LiveSetup setup = LiveSetup.of(plan, repoRoot);
        RecordingLayout journal = new RecordingLayout(
                sessionsRoot.toAbsolutePath().resolve(plan.id().value()).resolve(LiveSession.JOURNAL_DIR));
        RecordingLayout recordings = new RecordingLayout(recordingsRoot.toAbsolutePath());
        // 점검 날짜는 입력 기록이 있는 날과 게이트웨이 녹화가 있는 날을 합친다. 실험실을 하루 통째로 끈 날도 빠짐으로 나와야
        // 한다(architect PR #107 R2)
        List<LocalDate> journalDays = new ArrayList<>();
        TreeSet<LocalDate> allDays = new TreeSet<>();
        for (LocalDate d = plan.from(); !d.isAfter(plan.to()); d = d.plusDays(1)) {
            if (Files.isRegularFile(journal.dayDir(plan.market(), d).resolve(RecordingLayout.EVENTS_FILE))) {
                journalDays.add(d);
                allDays.add(d);
            }
            if (Files.isRegularFile(recordings.dayDir(plan.market(), d).resolve(RecordingLayout.EVENTS_FILE))) {
                allDays.add(d);
            }
        }
        if (journalDays.isEmpty()) {
            throw new IllegalArgumentException("운용 기간 안에 입력 기록이 없습니다: " + journal.root());
        }
        LocalDate end = to.orElse(allDays.last());
        if (end.isBefore(plan.from()) || end.isAfter(plan.to())) {
            throw new IllegalArgumentException("점검 마지막 날이 운용 기간 밖입니다: " + end + " (" + plan.from() + " ~ "
                    + plan.to() + ")");
        }
        List<LocalDate> days = allDays.stream().filter(d -> !d.isAfter(end)).toList();
        List<LocalDate> liveDays = journalDays.stream().filter(d -> !d.isAfter(end)).toList();
        if (liveDays.isEmpty()) {
            throw new IllegalArgumentException("점검 마지막 날까지 입력 기록이 없습니다: " + end);
        }
        LocalDate weekFrom = end.minusDays(6).isBefore(plan.from()) ? plan.from() : end.minusDays(6);

        // 날마다 대조한다. 기록과 녹화를 줄 단위로 읽고 날마다 버린다(D-115). 계좌 대조용 녹화(boards·운용 기간)는 날짜와 파일
        // 해시만 남겨 두고 계좌 대조 때 같은 파일인지 확인하며 다시 읽는다
        List<DayRow> rows = new ArrayList<>();
        BoardsRecording recordedForReplay = new BoardsRecording(setup);
        List<String> skipAccounts = new ArrayList<>();
        RecordingCodec codec = new RecordingCodec();
        for (LocalDate d : days) {
            gate.check();
            Path rDir = recordings.dayDir(plan.market(), d);
            Path jDir = journal.dayDir(plan.market(), d);
            Path j = Files.isRegularFile(jDir.resolve(RecordingLayout.EVENTS_FILE)) ? jDir : null;
            Path r = Files.isRegularFile(rDir.resolve(RecordingLayout.EVENTS_FILE)) ? rDir : null;
            DayRow row = compareDay(setup, codec, d, j, r, recordedForReplay);
            rows.add(row);
            if (!row.recordingPresent()) {
                skipAccounts.add(d + " 녹화 없음");
            }
            log.accept(d + ": 기록 " + (row.journalPresent() ? row.journalLines() + "줄" : "없음") + ", 녹화(boards) "
                    + row.recordedEvents() + ", 빠짐 " + row.missingTotal() + ", 녹화에 없는 기록 줄 " + (row.extraInRecorderGap() + row.extraUnexplained()));
        }

        String accounts;
        boolean compared = false;
        if (!skipAccounts.isEmpty()) {
            accounts = "계좌 대조를 하지 않았습니다: " + String.join(", ", skipAccounts)
                    + ". 운용 시작부터 모든 기록일의 녹화가 있어야 같은 조건으로 비교할 수 있습니다.\n";
        } else {
            String offTick = recordedForReplay.firstOffTick;
            if (offTick != null) {
                accounts = "계좌 대조를 하지 않았습니다: 녹화에 주식 호가 단위에 맞지 않는 가격이 있습니다(" + offTick
                        + "). 실시간 세션도 이 가격에서 멈춥니다(D-075 4번).\n";
            } else {
                gate.check();
                accounts = compareAccounts(setup, journal, liveDays, days.size(), recordedForReplay.stream(
                        "REC-BOARDS-" + plan.id() + "-" + liveDays.get(0) + ".." + end, gate), end, code, log, gate);
                compared = true;
            }
        }
        boolean defect = rows.stream().anyMatch(r -> r.extraUnexplained() > 0 || r.missing().getOrDefault(UNEXPLAINED, 0L) > 0);
        CollectionFreshness.Status collection =
                CollectionFreshness.check(snapshotsRoot, plan.market(), end, ExchangeCalendar.bundled(Market.KRX));
        log.accept("수집: " + collection.summary().replace("**", ""));
        String report = render(plan, code, weekFrom, end, rows, defect, accounts, collection);
        return new Outcome(weekFrom, end, rows, defect, compared, collection, report);
    }

    /** {@link #run} 후 보고서를 {@code experiments/<실험ID>/weekly/<마지막 날>.md}에 쓴다. 멈추면 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path sessionsRoot, Path recordingsRoot,
            Path snapshotsRoot, Optional<LocalDate> to, CodeVersion code, Consumer<String> log, RunGate gate) {
        Outcome out = run(experimentDir, repoRoot, sessionsRoot, recordingsRoot, snapshotsRoot, to, code, log, gate);
        Path target = experimentDir.resolve(WEEKLY_DIR).resolve(out.to() + ".md");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, out.report(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
        }
        log.accept("작성: " + target);
        return out;
    }

    private static String fingerprintOf(Path dir) {
        try {
            return fingerprint(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("기록을 읽을 수 없습니다: " + dir, e);
        }
    }

    /** 읽는 동안 파일이 바뀌면(세션·게이트웨이가 아직 쓰는 중) 거부한다. */
    private static void ensureStable(Path dir, String before) {
        if (!before.equals(fingerprintOf(dir))) {
            throw new IllegalStateException("아직 쓰이는 중입니다(읽는 동안 파일이 바뀜). 장이 끝나고 수신이 멈춘 뒤 다시 돌리세요: "
                    + dir);
        }
    }

    /** events 파일과 meta 파일(있으면)의 SHA-256. 메타의 정상 종료·구간도 보고서와 원인 판정에 쓰므로 둘 다 본다(architect PR #107 N2). */
    private static String fingerprint(Path dir) throws IOException {
        Path meta = dir.resolve(RecordingLayout.META_FILE);
        return Sha256.ofFile(dir.resolve(RecordingLayout.EVENTS_FILE)) + "|"
                + (Files.exists(meta) ? Sha256.ofFile(meta) : "");
    }

    private record Seen(Symbol symbol, Instant receivedAt) {}

    /** 세션이 그날 기록을 열고 있던 구간(양끝 포함). */
    record Span(Instant start, Instant end) {
        boolean contains(Instant at) {
            return !at.isBefore(start) && !at.isAfter(end);
        }
    }

    private record Gap(String source, DataGap gap) {
        boolean covers(Symbol s, Instant at, boolean anySymbol) {
            return (anySymbol || gap.symbol().equals(s)) && !at.isBefore(gap.gapStart()) && !at.isAfter(gap.gapEnd());
        }
    }

    /**
     * 하루 대조. 기록과 녹화를 메모리에 올리지 않고 줄 단위로 읽는다(D-115): 기록을 한 번 읽어 공백·세션 구간·줄 열쇠 수를 모으고,
     * 녹화를 읽으며 녹화 줄마다 기록과 맞추고(녹화 순서), 기록을 다시 읽으며 기록 줄마다 녹화와 맞춘다(기록 순서). 같은 열쇠가 여러
     * 번이면 앞에서부터 짝을 지으므로 목록으로 맞추던 때와 같은 줄이 빠짐·남음으로 나온다. 열쇠는 줄 내용의 SHA-256 앞 128비트다.
     * 읽기 전후로 파일 해시(events·meta)가 다르면 거부한다.
     *
     * @param jDir 같은 날 입력 기록 폴더, 없으면 null
     * @param rDir 같은 날 게이트웨이 녹화 폴더, 없으면 null
     * @param replay 계좌 대조용 녹화 기록. 녹화가 있으면 이 날을 더한다
     */
    static DayRow compareDay(LiveSetup setup, RecordingCodec codec, LocalDate date, Path jDir, Path rDir,
            BoardsRecording replay) {
        Map<String, Long> labGaps = new LinkedHashMap<>();
        LAB_GAP_SOURCES.forEach(s -> labGaps.put(s, 0L));
        List<Gap> ownGaps = new ArrayList<>();
        Set<List<Instant>> reconnects = new TreeSet<>((a, b) -> {
            int c = a.get(0).compareTo(b.get(0));
            return c != 0 ? c : a.get(1).compareTo(b.get(1));
        });
        long[] forwarded = {0};
        Map<Key, Integer> jCount = new HashMap<>();
        String jBefore = jDir == null ? null : fingerprintOf(jDir);
        RecordedDayStream.Summary jDay = null;
        List<Span> spans = List.of();
        if (jDir != null) {
            SpanBuilder spanBuilder = new SpanBuilder(RecordedDayStream.readMeta(jDir));
            jDay = RecordedDayStream.read(jDir, l -> {
                spanBuilder.add(l);
                MarketEvent e = l.event();
                if (e instanceof DataGap g && labGaps.containsKey(l.sourceId())) {
                    labGaps.merge(l.sourceId(), 1L, Long::sum);
                    ownGaps.add(new Gap(l.sourceId(), g));
                    if (l.sourceId().equals(FeedClient.SOURCE_ID)) {
                        reconnects.add(List.of(g.gapStart(), g.gapEnd()));
                    }
                    return;
                }
                if (e instanceof DataGap) {
                    forwarded[0]++;
                }
                jCount.merge(key(codec, l), 1, Integer::sum);
            });
            spans = spanBuilder.spans(jDay.meta());
        }
        long jLines = jDay == null ? 0 : jDay.eventCount();
        boolean jComplete = jDay != null && jDay.meta().map(RecordingMeta::complete).orElse(false);
        if (rDir == null) {
            if (jDir != null) {
                ensureStable(jDir, jBefore);
            }
            return new DayRow(date, jDay != null, jLines, 0, 0, Map.of(), 0, 0, labGaps, forwarded[0],
                    reconnects.size(), jComplete, false, false);
        }
        List<Gap> recorderGaps = new ArrayList<>();
        Map<Key, Integer> rCount = new HashMap<>();
        long[] rEntries = {0};
        long[] matched = {0};
        Map<String, Long> missing = new LinkedHashMap<>();
        missing.put(OUTSIDE_SESSION, 0L);
        LAB_GAP_SOURCES.forEach(s -> missing.put(s, 0L));
        missing.put(UNEXPLAINED, 0L);
        List<Span> daySpans = spans;
        String rBefore = fingerprintOf(rDir);
        BoardsRecording.Day replayDay = replay.startDay(rDir, rBefore);
        RecordedDayStream.Summary rDay = RecordedDayStream.read(rDir, l -> {
            MarketEvent e = l.event();
            if (!setup.onBoards(e) || !setup.inPlan(e)) {
                return;
            }
            replayDay.add(e);
            if (e instanceof DataGap g && l.sourceId().equals(RECORDER_SOURCE)) {
                recorderGaps.add(new Gap(l.sourceId(), g));
                return;
            }
            rEntries[0]++;
            Key k = key(codec, l);
            rCount.merge(k, 1, Integer::sum);
            if (take(jCount, k)) {
                matched[0]++;
            } else {
                missing.merge(missingCause(new Seen(e.symbol(), e.receivedAt()), daySpans, ownGaps), 1L, Long::sum);
            }
        });
        ensureStable(rDir, rBefore);
        long[] extraInGap = {0};
        long[] extraUnexplained = {0};
        if (jDir != null) {
            RecordedDayStream.read(jDir, l -> {
                if (l.event() instanceof DataGap && labGaps.containsKey(l.sourceId())) {
                    return;
                }
                if (take(rCount, key(codec, l))) {
                    return;
                }
                MarketEvent e = l.event();
                boolean inGap = recorderGaps.stream().anyMatch(g -> g.covers(e.symbol(), e.receivedAt(),
                        g.gap().reason() == DataGap.GapReason.RECORDER_RESTART));
                if (inGap) {
                    extraInGap[0]++;
                } else {
                    extraUnexplained[0]++;
                }
            });
            ensureStable(jDir, jBefore);
        }
        return new DayRow(date, jDay != null, jLines, rEntries[0], matched[0], missing, extraInGap[0], extraUnexplained[0],
                labGaps, forwarded[0], reconnects.size(), jComplete, true,
                rDay.meta().map(RecordingMeta::complete).orElse(false));
    }

    /**
     * 녹화에만 있는 이벤트의 원인. 먼저 그 수신 시각을 덮는 실험실 쪽 공백(출처)을 찾는다. 공백이 없으면 세션이 그날 기록을 열고
     * 있던 구간 안인지 본다: 안이면 세션이 열린 채 공백 없이 빠진 것이라 설명 없음(MD-05 위반 후보), 밖이면 세션 밖(실험실이
     * 꺼져 있었거나 그날 세션이 없음)이다(architect PR #107 R1).
     */
    private static String missingCause(Seen s, List<Span> spans, List<Gap> ownGaps) {
        for (Gap g : ownGaps) {
            if (g.covers(s.symbol(), s.receivedAt(), ALL_SYMBOL_SOURCES.contains(g.source()))) {
                return g.source();
            }
        }
        for (Span span : spans) {
            if (span.contains(s.receivedAt())) {
                return UNEXPLAINED;
            }
        }
        return OUTSIDE_SESSION;
    }

    /**
     * 세션이 그날 기록을 열고 있던 구간들: 기록 메타의 구간(이 프로세스가 그날 파일을 연 시각 ~ 닫은 시각). 시작은 그 구간 첫 줄
     * 수신 시각과 비교해 이른 쪽(메타 시각은 실험실 시계로 첫 줄을 받은 뒤에 찍힘)으로 잡는다. 닫힌 시각이 없는 구간(비정상 종료,
     * 아직 쓰는 중)은 그 구간 마지막 줄 수신 시각에서 끝낸다. 메타가 없으면 그날 첫 줄 ~ 마지막 줄 하나로 본다.
     * 구간 k의 줄은 순번이 [구간 k 첫 순번, 구간 k+1 첫 순번)인 줄이다. 줄을 하나씩 받아 구간마다 수신 시각 최소·최대를 센다.
     */
    static final class SpanBuilder {
        private final List<RecordingMeta.Segment> segs;
        private final Instant[] min;
        private final Instant[] max;
        private Instant dayMin;
        private Instant dayMax;

        SpanBuilder(Optional<RecordingMeta> meta) {
            this.segs = meta.map(RecordingMeta::segments).orElse(List.of());
            this.min = new Instant[segs.size()];
            this.max = new Instant[segs.size()];
        }

        void add(RecordingCodec.Line l) {
            Instant t = l.event().receivedAt();
            dayMin = dayMin == null || t.isBefore(dayMin) ? t : dayMin;
            dayMax = dayMax == null || t.isAfter(dayMax) ? t : dayMax;
            for (int k = 0; k < segs.size(); k++) {
                long nextFirst = k + 1 < segs.size() ? segs.get(k + 1).firstSeq() : Long.MAX_VALUE;
                if (l.seq() >= segs.get(k).firstSeq() && l.seq() < nextFirst) {
                    min[k] = min[k] == null || t.isBefore(min[k]) ? t : min[k];
                    max[k] = max[k] == null || t.isAfter(max[k]) ? t : max[k];
                }
            }
        }

        /** @param meta 다 읽은 뒤의 메타(구간을 만들 때 쓴 것과 같아야 한다) */
        List<Span> spans(Optional<RecordingMeta> meta) {
            if (!meta.map(RecordingMeta::segments).orElse(List.of()).equals(segs)) {
                throw new IllegalStateException("아직 쓰이는 중입니다(읽는 동안 meta.json이 바뀜)");
            }
            List<Span> out = new ArrayList<>();
            if (segs.isEmpty()) {
                if (dayMin != null) {
                    out.add(new Span(dayMin, dayMax));
                }
                return out;
            }
            for (int k = 0; k < segs.size(); k++) {
                RecordingMeta.Segment seg = segs.get(k);
                Instant start = Instant.parse(seg.startedAt());
                if (min[k] != null && min[k].isBefore(start)) {
                    start = min[k];
                }
                Instant end;
                if (!seg.endedAt().isEmpty()) {
                    end = Instant.parse(seg.endedAt());
                } else if (max[k] != null) {
                    end = max[k];
                } else {
                    end = start;
                }
                out.add(new Span(start, end.isBefore(start) ? start : end));
            }
            return out;
        }
    }

    /** 줄 열쇠의 SHA-256 앞 128비트. 같은 날 줄 수백만 개를 문자열로 들지 않으려고 줄인다(겹칠 확률은 무시할 만큼 작다 `[추론]`). */
    record Key(long hi, long lo) {}

    /** 줄을 맞추는 열쇠: 원래 소스, 소스 순번, 내용(녹화 순번 빼고). 전달은 녹화와 같은 줄 형식이라 같은 이벤트면 같다. */
    static Key key(RecordingCodec codec, RecordingCodec.Line l) {
        String text = codec.encode(0, l.sourceId(), EventSequences.withSequence(l.event(), l.sourceSequence()));
        byte[] h = SHA256.get().digest(text.getBytes(StandardCharsets.UTF_8));
        return new Key(java.nio.ByteBuffer.wrap(h, 0, 8).getLong(), java.nio.ByteBuffer.wrap(h, 8, 8).getLong());
    }

    private static final ThreadLocal<java.security.MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private static boolean take(Map<Key, Integer> counts, Key key) {
        Integer n = counts.get(key);
        if (n == null || n == 0) {
            return false;
        }
        counts.put(key, n - 1);
        return true;
    }

    /**
     * 계좌 대조용 녹화: 녹화가 있는 날마다 boards·운용 기간 줄. 대조 때는 날짜·파일 해시·줄 수와 첫 호가 단위 위반만 남기고,
     * 계좌 대조 때 {@link #stream}이 같은 파일인지 확인하며 다시 읽는다. 이벤트 순번은 날짜 → 녹화 순번 순서의 위치(1부터)다.
     */
    static final class BoardsRecording {
        private final LiveSetup setup;
        private final List<Day> days = new ArrayList<>();
        private long count;
        String firstOffTick;

        BoardsRecording(LiveSetup setup) {
            this.setup = setup;
        }

        Day startDay(Path dir, String fingerprint) {
            Day d = new Day(dir, fingerprint);
            days.add(d);
            return d;
        }

        long count() {
            return count;
        }

        final class Day {
            final Path dir;
            final String fingerprint;
            long events;

            Day(Path dir, String fingerprint) {
                this.dir = dir;
                this.fingerprint = fingerprint;
            }

            void add(MarketEvent e) {
                events++;
                count++;
                if (firstOffTick == null) {
                    RecordingExperiment.offStockTick(setup.rules(), e)
                            .ifPresent(p -> firstOffTick = e.symbol().code() + " " + p.toPlainString());
                }
            }
        }

        MarketEventStream stream(String version, RunGate gate) {
            Market market = setup.plan().market();
            List<Day> fixed = List.copyOf(days);
            long total = count;
            return new MarketEventStream() {
                @Override
                public String version() {
                    return version;
                }

                @Override
                public Market market() {
                    return market;
                }

                @Override
                public long eventCount() {
                    return total;
                }

                @Override
                public void forEach(Consumer<MarketEvent> sink) {
                    long[] seq = {0};
                    for (Day d : fixed) {
                        gate.check();
                        if (!d.fingerprint.equals(fingerprintOf(d.dir))) {
                            throw new IllegalStateException("녹화가 대조한 뒤 바뀌었습니다: " + d.dir);
                        }
                        long[] n = {0};
                        RecordedDayStream.read(d.dir, l -> {
                            MarketEvent e = l.event();
                            if (!setup.onBoards(e) || !setup.inPlan(e)) {
                                return;
                            }
                            if (e.symbol().market() != market) {
                                throw new IllegalArgumentException("다른 시장의 이벤트가 섞여 있습니다: " + e.symbol());
                            }
                            n[0]++;
                            sink.accept(EventSequences.withSequence(e, ++seq[0]));
                        });
                        ensureStable(d.dir, d.fingerprint);
                        if (n[0] != d.events) {
                            throw new IllegalStateException("녹화가 대조한 뒤 바뀌었습니다: " + d.dir);
                        }
                    }
                }
            };
        }
    }

    private static String compareAccounts(LiveSetup setup, RecordingLayout journal, List<LocalDate> days, int checkedDays,
            MarketEventStream rec, LocalDate end, CodeVersion code, Consumer<String> log, RunGate gate) {
        List<Path> dirs = days.stream().map(d -> journal.dayDir(setup.plan().market(), d)).toList();
        // 날마다 디스크에서 다시 읽는다(D-115). 목록으로 모으던 때(RecordedMarketDataSet)와 같은 이벤트·같은 버전이다
        StreamedRecordingDataSet live = StreamedRecordingDataSet.open(setup.plan().market(), dirs, gate::check, e -> { });
        log.accept("계좌 대조: 입력 기록 리플레이(이벤트 " + live.eventCount() + ")");
        SimulationReport a = replay(setup, live, end, code, gate);
        log.accept("계좌 대조: 같은 날 녹화 리플레이(이벤트 " + rec.eventCount() + ")");
        SimulationReport b = replay(setup, rec, end, code, gate);
        int diff = LiveReplayCheck.firstDifference(LiveReplayCheck.withoutCodeLine(ReportWriter.render(a)),
                LiveReplayCheck.withoutCodeLine(ReportWriter.render(b)));
        StringBuilder sb = new StringBuilder();
        sb.append("- 기간: ").append(setup.plan().from()).append(" ~ ").append(end).append(" (점검일 ").append(checkedDays)
                .append("일 누계, 그중 입력 기록 있는 날 ").append(days.size())
                .append("일. 기록 없는 날의 녹화도 녹화 리플레이에 든다)\n");
        sb.append("- 이벤트: 입력 기록 ").append(live.eventCount()).append(", 녹화(boards) ").append(rec.eventCount())
                .append('\n');
        sb.append("- 상세 보고서: ").append(diff < 0 ? "같음(체결 원장까지 일치)"
                : "다름(code 줄 뺀 " + (diff + 1) + "번째 줄부터)").append("\n\n");
        sb.append("| 계좌 | 프로필 | 총수익률 실시간 | 총수익률 녹화 | 차이 | 체결 실시간 | 체결 녹화 | 비용 실시간 | 비용 녹화 | 상태 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (int i = 0; i < a.runs().size(); i++) {
            RunReport ra = a.runs().get(i);
            RunResult sa = ra.summary();
            RunResult sb2 = b.runs().get(i).summary();
            RunMetrics ma = sa.metrics();
            RunMetrics mb = sb2.metrics();
            sb.append("| ").append(sa.accountId()).append(" | ").append(ra.profile().id()).append('@')
                    .append(ra.profile().version()).append(" | ").append(pct(ma == null ? null : ma.totalReturn()))
                    .append(" | ").append(pct(mb == null ? null : mb.totalReturn())).append(" | ")
                    .append(pp(ma == null ? null : ma.totalReturn(), mb == null ? null : mb.totalReturn())).append(" | ")
                    .append(ma == null ? "-" : ma.fillCount()).append(" | ").append(mb == null ? "-" : mb.fillCount())
                    .append(" | ").append(ma == null ? "-" : ma.totalCosts().amount().toPlainString()).append(" | ")
                    .append(mb == null ? "-" : mb.totalCosts().amount().toPlainString()).append(" | ")
                    .append(status(sa)).append(" / ").append(status(sb2)).append(" |\n");
        }
        return sb.toString();
    }

    private static SimulationReport replay(LiveSetup setup, MarketEventStream data, LocalDate end, CodeVersion code,
            RunGate gate) {
        return new SimulationExperimentRunner(data, setup.rules(), setup.cost(), setup.factory(), setup.risk(),
                setup.execution(), code, 1, setup.benchmark(), null, gate)
                .runDetailed(setup.spec(ClockMode.REPLAY, data.version(), end));
    }

    private static String status(RunResult r) {
        return r.failureReason().isEmpty() ? r.status().name() : r.status() + " " + r.failureReason();
    }

    static String render(LivePlan plan, CodeVersion code, LocalDate weekFrom, LocalDate end, List<DayRow> rows,
            boolean defect, String accounts, CollectionFreshness.Status collection) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.id()).append(" 주간 점검 ").append(weekFrom).append(" ~ ").append(end).append("\n\n");
        sb.append("- 실시간 모의 입력 기록과 같은 날 게이트웨이 녹화 대조(D-088). 점검 명령이 씀, 손으로 고치지 않는다.\n");
        sb.append("- code: ").append(code.commit()).append(code.dirty() ? " dirty" : "").append('\n');
        sb.append("- 판정: ").append(defect
                ? "**확인 필요**: 설명되지 않는 차이가 있다(아래 \"설명 없음\" 또는 \"녹화에 없는 기록 줄\"). 전달·기록 경로 결함일 수 있다"
                : "설명되지 않는 차이 없음").append('\n');
        sb.append("- 수집: ").append(collection.summary()).append("\n\n");

        List<DayRow> week = rows.stream().filter(r -> !r.date().isBefore(weekFrom)).toList();
        sb.append("## 1. 입력 대조(이번 주)\n\n");
        sb.append("녹화(boards) = 실험실이 받았어야 할 줄(녹화기 공백 제외). 빠짐 = 녹화에만 있는 줄, 원인은 그 이벤트의 수신 시각이 ")
                .append("든 실험실 공백 출처로 나눈다.\n\n");
        sb.append("| 날짜 | 기록 줄 | 녹화(boards) | 받음 | 빠짐 | 세션 밖 | 끊김 | 재기동 | 게이트웨이 큐 | 실험실 큐 | 설명 없음 "
                + "| 녹화에 없는 기록 줄(녹화 공백 안/설명 없음) | 기록·녹화 정상 종료 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
        for (DayRow r : week) {
            String journalCell = r.journalPresent() ? String.valueOf(r.journalLines()) : "기록 없음";
            if (!r.recordingPresent()) {
                sb.append("| ").append(r.date()).append(" | ").append(journalCell)
                        .append(" | 녹화 없음 | - | - | - | - | - | - | - | - | - | ").append(yn(r.journalComplete()))
                        .append("/- |\n");
                continue;
            }
            sb.append("| ").append(r.date()).append(" | ").append(journalCell).append(" | ").append(r.recordedEvents())
                    .append(" | ").append(r.matched()).append(" | ").append(r.missingTotal());
            for (String k : missingKeys()) {
                sb.append(" | ").append(r.missing().getOrDefault(k, 0L));
            }
            sb.append(" | ").append(r.extraInRecorderGap()).append('/').append(r.extraUnexplained()).append(" | ")
                    .append(r.journalPresent() ? yn(r.journalComplete()) : "-").append('/').append(yn(r.recordingComplete()))
                    .append(" |\n");
        }
        appendTotal(sb, "이번 주 합계", week);
        appendTotal(sb, "운용 시작부터 누계", rows);

        sb.append("\n## 2. 공백 출처(이번 주, 입력 기록의 공백 줄 수)\n\n");
        sb.append("공백 줄은 종목마다 하나씩이다. 재연결 수가 상태 화면의 `feedReconnects`, 실험실 큐 칸의 빠짐 수가 `inboxDropped`에 ")
                .append("해당한다(실험실을 다시 띄워도 남는 값).\n\n");
        sb.append("| 날짜 | 공백 줄 합계 | 끊김(FEED-CLIENT) | 재연결 수 | 재기동(RECORDER) | 게이트웨이 큐(FEED) | 실험실 큐(LAB-INBOX) "
                + "| 녹화에서 전달된 공백 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (DayRow r : week.stream().filter(DayRow::journalPresent).toList()) {
            long own = r.labGaps().values().stream().mapToLong(Long::longValue).sum();
            sb.append("| ").append(r.date()).append(" | ").append(own + r.forwardedGaps()).append(" | ")
                    .append(r.labGaps().get(FeedClient.SOURCE_ID)).append(" | ").append(r.reconnects()).append(" | ")
                    .append(r.labGaps().get(RECORDER_SOURCE)).append(" | ").append(r.labGaps().get(FeedServer.SOURCE_ID))
                    .append(" | ").append(r.labGaps().get(LiveSession.INBOX_SOURCE)).append(" | ").append(r.forwardedGaps())
                    .append(" |\n");
        }

        sb.append("\n## 3. 계좌 대조(운용 시작부터 누계)\n\n");
        sb.append("실시간 = 입력 기록 리플레이(실시간 결과와 같음, D-076 합격선). 녹화 = 같은 날 게이트웨이 녹화에서 boards만 같은 ")
                .append("등록 파일·코드로 리플레이. 차이는 실험실이 받지 못한 이벤트와 실험실 쪽 공백이 성과에 준 영향이다. 비용 차감 ")
                .append("성과이고 투자 자문이 아니다.\n\n");
        sb.append(accounts);
        sb.append(CollectionFreshness.render(collection));
        return sb.toString();
    }

    private static List<String> missingKeys() {
        List<String> out = new ArrayList<>();
        out.add(OUTSIDE_SESSION);
        out.addAll(LAB_GAP_SOURCES);
        out.add(UNEXPLAINED);
        return out;
    }

    private static void appendTotal(StringBuilder sb, String label, List<DayRow> rows) {
        List<DayRow> withRec = rows.stream().filter(DayRow::recordingPresent).toList();
        long journal = rows.stream().mapToLong(DayRow::journalLines).sum();
        sb.append("| ").append(label).append(" | ").append(journal).append(" | ")
                .append(withRec.stream().mapToLong(DayRow::recordedEvents).sum()).append(" | ")
                .append(withRec.stream().mapToLong(DayRow::matched).sum()).append(" | ")
                .append(withRec.stream().mapToLong(DayRow::missingTotal).sum());
        for (String k : missingKeys()) {
            sb.append(" | ").append(withRec.stream().mapToLong(r -> r.missing().getOrDefault(k, 0L)).sum());
        }
        sb.append(" | ").append(withRec.stream().mapToLong(DayRow::extraInRecorderGap).sum()).append('/')
                .append(withRec.stream().mapToLong(DayRow::extraUnexplained).sum()).append(" | 녹화 없는 날 ")
                .append(rows.size() - withRec.size()).append(" |\n");
    }

    private static String yn(boolean b) {
        return b ? "예" : "아니오";
    }

    private static String pct(BigDecimal v) {
        return v == null ? "-" : v.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    private static String pp(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? "-"
                : a.subtract(b).movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%p";
    }
}
