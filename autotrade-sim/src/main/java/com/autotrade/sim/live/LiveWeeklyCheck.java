package com.autotrade.sim.live;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.recording.EventSequences;
import com.autotrade.marketdata.recording.RecordedDay;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.recording.RecordedMarketDataSet;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.InMemoryMarketDataSet;
import com.autotrade.sim.run.MarketDataSet;
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
 * <p>무거우므로(녹화를 하루씩 다 읽고 리플레이를 두 번 돈다) 실험 명령({@code runLiveWeeklyCheck}, D-063 장 시간 보호·정지
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
    /** 그 공백이 모든 종목의 수신이 끊긴 구간인지(끊김·재기동). 아니면(큐 넘침) 그 종목만. */
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
     * @param recordingPresent 같은 날 녹화가 있는지(없으면 대조 칸은 비어 있다)
     */
    public record DayRow(LocalDate date, long journalLines, long recordedEvents, long matched, Map<String, Long> missing,
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
     */
    public record Outcome(LocalDate from, LocalDate to, List<DayRow> days, boolean defect, boolean accountsCompared,
            String report) {
        public Outcome {
            days = List.copyOf(days);
        }
    }

    /**
     * @param experimentDir {@code experiments/<실험ID>}
     * @param sessionsRoot 세션 폴더들의 부모(설정 폴더 아래 {@code live-sessions})
     * @param recordingsRoot 게이트웨이 녹화 폴더(설정 폴더 아래 {@code recordings})
     * @param to 점검 마지막 날. 비어 있으면 운용 기간 안 입력 기록의 마지막 날
     * @throws IllegalArgumentException 입력이 맞지 않을 때(등록 파일, 입력 기록 없음 등)
     */
    public static Outcome run(Path experimentDir, Path repoRoot, Path sessionsRoot, Path recordingsRoot,
            Optional<LocalDate> to, CodeVersion code, Consumer<String> log, RunGate gate) {
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
        List<LocalDate> journalDays = new ArrayList<>();
        for (LocalDate d = plan.from(); !d.isAfter(plan.to()); d = d.plusDays(1)) {
            if (Files.isRegularFile(journal.dayDir(plan.market(), d).resolve(RecordingLayout.EVENTS_FILE))) {
                journalDays.add(d);
            }
        }
        if (journalDays.isEmpty()) {
            throw new IllegalArgumentException("운용 기간 안에 입력 기록이 없습니다: " + journal.root());
        }
        LocalDate end = to.orElse(journalDays.get(journalDays.size() - 1));
        if (end.isBefore(plan.from()) || end.isAfter(plan.to())) {
            throw new IllegalArgumentException("점검 마지막 날이 운용 기간 밖입니다: " + end + " (" + plan.from() + " ~ "
                    + plan.to() + ")");
        }
        List<LocalDate> days = journalDays.stream().filter(d -> !d.isAfter(end)).toList();
        if (days.isEmpty()) {
            throw new IllegalArgumentException("점검 마지막 날까지 입력 기록이 없습니다: " + end);
        }
        LocalDate weekFrom = end.minusDays(6).isBefore(plan.from()) ? plan.from() : end.minusDays(6);

        // 날마다 대조한다. 계좌 대조용 녹화 이벤트(boards·운용 기간)는 여기서 모아 둔다(녹화를 두 번 읽지 않게)
        List<DayRow> rows = new ArrayList<>();
        List<MarketEvent> recordedForReplay = new ArrayList<>();
        List<String> skipAccounts = new ArrayList<>();
        RecordingCodec codec = new RecordingCodec();
        for (LocalDate d : days) {
            gate.check();
            Path rDir = recordings.dayDir(plan.market(), d);
            RecordedDay jDay = openStable(journal.dayDir(plan.market(), d));
            RecordedDay rDay = Files.isRegularFile(rDir.resolve(RecordingLayout.EVENTS_FILE)) ? openStable(rDir) : null;
            DayRow row = compareDay(setup, codec, d, jDay, rDay, recordedForReplay);
            rows.add(row);
            if (!row.recordingPresent()) {
                skipAccounts.add(d + " 녹화 없음");
            }
            log.accept(d + ": 기록 " + row.journalLines() + "줄, 녹화(boards) " + row.recordedEvents() + ", 빠짐 "
                    + row.missingTotal() + ", 녹화에 없는 기록 줄 " + (row.extraInRecorderGap() + row.extraUnexplained()));
        }

        String accounts;
        boolean compared = false;
        if (!skipAccounts.isEmpty()) {
            accounts = "계좌 대조를 하지 않았습니다: " + String.join(", ", skipAccounts)
                    + ". 운용 시작부터 모든 기록일의 녹화가 있어야 같은 조건으로 비교할 수 있습니다.\n";
        } else {
            String offTick = null;
            for (MarketEvent e : recordedForReplay) {
                Optional<BigDecimal> p = RecordingExperiment.offStockTick(setup.rules(), e);
                if (p.isPresent()) {
                    offTick = e.symbol().code() + " " + p.get().toPlainString();
                    break;
                }
            }
            if (offTick != null) {
                accounts = "계좌 대조를 하지 않았습니다: 녹화에 주식 호가 단위에 맞지 않는 가격이 있습니다(" + offTick
                        + "). 실시간 세션도 이 가격에서 멈춥니다(D-075 4번).\n";
            } else {
                gate.check();
                accounts = compareAccounts(setup, journal, days, recordedForReplay, end, code, log, gate);
                compared = true;
            }
        }
        boolean defect = rows.stream().anyMatch(r -> r.extraUnexplained() > 0 || r.missing().getOrDefault(UNEXPLAINED, 0L) > 0);
        String report = render(plan, code, weekFrom, end, rows, defect, accounts);
        return new Outcome(weekFrom, end, rows, defect, compared, report);
    }

    /** {@link #run} 후 보고서를 {@code experiments/<실험ID>/weekly/<마지막 날>.md}에 쓴다. 멈추면 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path sessionsRoot, Path recordingsRoot,
            Optional<LocalDate> to, CodeVersion code, Consumer<String> log, RunGate gate) {
        Outcome out = run(experimentDir, repoRoot, sessionsRoot, recordingsRoot, to, code, log, gate);
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

    /** 읽는 동안 파일이 바뀌면(세션·게이트웨이가 아직 쓰는 중) 거부한다. */
    private static RecordedDay openStable(Path dir) {
        Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
        try {
            String before = Sha256.ofFile(events);
            RecordedDay day = RecordedDay.open(dir);
            if (!before.equals(Sha256.ofFile(events))) {
                throw new IllegalStateException("아직 쓰이는 중입니다(읽는 동안 파일이 바뀜). 장이 끝나고 수신이 멈춘 뒤 다시 돌리세요: "
                        + dir);
            }
            return day;
        } catch (IOException e) {
            throw new UncheckedIOException("기록을 읽을 수 없습니다: " + dir, e);
        }
    }

    private record Seen(Symbol symbol, Instant receivedAt) {}

    private record Gap(String source, DataGap gap) {
        boolean covers(Symbol s, Instant at, boolean anySymbol) {
            return (anySymbol || gap.symbol().equals(s)) && !at.isBefore(gap.gapStart()) && !at.isAfter(gap.gapEnd());
        }
    }

    static DayRow compareDay(LiveSetup setup, RecordingCodec codec, LocalDate date, RecordedDay jDay, RecordedDay rDay,
            List<MarketEvent> recordedForReplay) {
        Map<String, Long> labGaps = new LinkedHashMap<>();
        LAB_GAP_SOURCES.forEach(s -> labGaps.put(s, 0L));
        List<Gap> ownGaps = new ArrayList<>();
        List<Map.Entry<String, Seen>> jEntries = new ArrayList<>();
        Set<List<Instant>> reconnects = new TreeSet<>((a, b) -> {
            int c = a.get(0).compareTo(b.get(0));
            return c != 0 ? c : a.get(1).compareTo(b.get(1));
        });
        long forwarded = 0;
        Instant first = null;
        Instant last = null;
        for (RecordingCodec.Line l : jDay.lines()) {
            MarketEvent e = l.event();
            if (e instanceof DataGap g && labGaps.containsKey(l.sourceId())) {
                labGaps.merge(l.sourceId(), 1L, Long::sum);
                ownGaps.add(new Gap(l.sourceId(), g));
                if (l.sourceId().equals(FeedClient.SOURCE_ID)) {
                    reconnects.add(List.of(g.gapStart(), g.gapEnd()));
                }
                continue;
            }
            if (e instanceof DataGap) {
                forwarded++;
            }
            jEntries.add(Map.entry(key(codec, l), new Seen(e.symbol(), e.receivedAt())));
            first = first == null || e.receivedAt().isBefore(first) ? e.receivedAt() : first;
            last = last == null || e.receivedAt().isAfter(last) ? e.receivedAt() : last;
        }
        boolean jComplete = jDay.meta().map(RecordingMeta::complete).orElse(false);
        if (rDay == null) {
            return new DayRow(date, jDay.lines().size(), 0, 0, Map.of(), 0, 0, labGaps, forwarded, reconnects.size(),
                    jComplete, false, false);
        }
        List<Gap> recorderGaps = new ArrayList<>();
        List<Map.Entry<String, Seen>> rEntries = new ArrayList<>();
        for (RecordingCodec.Line l : rDay.lines()) {
            MarketEvent e = l.event();
            if (!setup.onBoards(e) || !setup.inPlan(e)) {
                continue;
            }
            if (e instanceof DataGap g && l.sourceId().equals(RECORDER_SOURCE)) {
                recorderGaps.add(new Gap(l.sourceId(), g));
            } else {
                rEntries.add(Map.entry(key(codec, l), new Seen(e.symbol(), e.receivedAt())));
            }
            recordedForReplay.add(EventSequences.withSequence(e, recordedForReplay.size() + 1L));
        }
        Map<String, Integer> jCount = counts(jEntries);
        Map<String, Integer> rCount = counts(rEntries);
        long matched = 0;
        Map<String, Long> missing = new LinkedHashMap<>();
        missing.put(OUTSIDE_SESSION, 0L);
        LAB_GAP_SOURCES.forEach(s -> missing.put(s, 0L));
        missing.put(UNEXPLAINED, 0L);
        for (Map.Entry<String, Seen> r : rEntries) {
            if (take(jCount, r.getKey())) {
                matched++;
                continue;
            }
            missing.merge(missingCause(r.getValue(), first, last, ownGaps), 1L, Long::sum);
        }
        long extraInGap = 0;
        long extraUnexplained = 0;
        for (Map.Entry<String, Seen> j : jEntries) {
            if (take(rCount, j.getKey())) {
                continue;
            }
            Seen s = j.getValue();
            boolean inGap = recorderGaps.stream().anyMatch(g -> g.covers(s.symbol(), s.receivedAt(),
                    g.gap().reason() == DataGap.GapReason.RECORDER_RESTART));
            if (inGap) {
                extraInGap++;
            } else {
                extraUnexplained++;
            }
        }
        return new DayRow(date, jDay.lines().size(), rEntries.size(), matched, missing, extraInGap, extraUnexplained,
                labGaps, forwarded, reconnects.size(), jComplete, true, rDay.meta().map(RecordingMeta::complete).orElse(false));
    }

    /** 녹화에만 있는 이벤트의 원인: 그날 세션 수신 범위 밖, 실험실 공백 안(출처), 그 밖은 설명 없음. */
    private static String missingCause(Seen s, Instant first, Instant last, List<Gap> ownGaps) {
        if (first == null || s.receivedAt().isBefore(first) || s.receivedAt().isAfter(last)) {
            return OUTSIDE_SESSION;
        }
        for (Gap g : ownGaps) {
            if (g.covers(s.symbol(), s.receivedAt(), ALL_SYMBOL_SOURCES.contains(g.source()))) {
                return g.source();
            }
        }
        return UNEXPLAINED;
    }

    /** 줄을 맞추는 열쇠: 원래 소스, 소스 순번, 내용(녹화 순번 빼고). 전달은 녹화와 같은 줄 형식이라 같은 이벤트면 같다. */
    private static String key(RecordingCodec codec, RecordingCodec.Line l) {
        return codec.encode(0, l.sourceId(), EventSequences.withSequence(l.event(), l.sourceSequence()));
    }

    private static Map<String, Integer> counts(List<Map.Entry<String, Seen>> entries) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Seen> e : entries) {
            out.merge(e.getKey(), 1, Integer::sum);
        }
        return out;
    }

    private static boolean take(Map<String, Integer> counts, String key) {
        Integer n = counts.get(key);
        if (n == null || n == 0) {
            return false;
        }
        counts.put(key, n - 1);
        return true;
    }

    private static String compareAccounts(LiveSetup setup, RecordingLayout journal, List<LocalDate> days,
            List<MarketEvent> recorded, LocalDate end, CodeVersion code, Consumer<String> log, RunGate gate) {
        List<Path> dirs = days.stream().map(d -> journal.dayDir(setup.plan().market(), d)).toList();
        RecordedMarketDataSet live = RecordedMarketDataSet.fromDirs(setup.plan().market(), dirs);
        MarketDataSet rec = new InMemoryMarketDataSet("REC-BOARDS-" + setup.plan().id() + "-" + days.get(0) + ".." + end,
                setup.plan().market(), recorded);
        log.accept("계좌 대조: 입력 기록 리플레이(이벤트 " + live.events().size() + ")");
        SimulationReport a = replay(setup, live, end, code, gate);
        log.accept("계좌 대조: 같은 날 녹화 리플레이(이벤트 " + rec.events().size() + ")");
        SimulationReport b = replay(setup, rec, end, code, gate);
        int diff = LiveReplayCheck.firstDifference(LiveReplayCheck.withoutCodeLine(ReportWriter.render(a)),
                LiveReplayCheck.withoutCodeLine(ReportWriter.render(b)));
        StringBuilder sb = new StringBuilder();
        sb.append("- 기간: ").append(setup.plan().from()).append(" ~ ").append(end).append(" (기록일 ").append(days.size())
                .append("일 누계)\n");
        sb.append("- 이벤트: 입력 기록 ").append(live.events().size()).append(", 녹화(boards) ").append(rec.events().size())
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

    private static SimulationReport replay(LiveSetup setup, MarketDataSet data, LocalDate end, CodeVersion code,
            RunGate gate) {
        return new SimulationExperimentRunner(data, setup.rules(), setup.cost(), setup.factory(), setup.risk(),
                setup.execution(), code, 1, setup.benchmark(), null, gate)
                .runDetailed(setup.spec(ClockMode.REPLAY, data.version(), end));
    }

    private static String status(RunResult r) {
        return r.failureReason().isEmpty() ? r.status().name() : r.status() + " " + r.failureReason();
    }

    static String render(LivePlan plan, CodeVersion code, LocalDate weekFrom, LocalDate end, List<DayRow> rows,
            boolean defect, String accounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.id()).append(" 주간 점검 ").append(weekFrom).append(" ~ ").append(end).append("\n\n");
        sb.append("- 실시간 모의 입력 기록과 같은 날 게이트웨이 녹화 대조(D-088). 점검 명령이 씀, 손으로 고치지 않는다.\n");
        sb.append("- code: ").append(code.commit()).append(code.dirty() ? " dirty" : "").append('\n');
        sb.append("- 판정: ").append(defect
                ? "**확인 필요**: 설명되지 않는 차이가 있다(아래 \"설명 없음\" 또는 \"녹화에 없는 기록 줄\"). 전달·기록 경로 결함일 수 있다"
                : "설명되지 않는 차이 없음").append("\n\n");

        List<DayRow> week = rows.stream().filter(r -> !r.date().isBefore(weekFrom)).toList();
        sb.append("## 1. 입력 대조(이번 주)\n\n");
        sb.append("녹화(boards) = 실험실이 받았어야 할 줄(녹화기 공백 제외). 빠짐 = 녹화에만 있는 줄, 원인은 그 이벤트의 수신 시각이 ")
                .append("든 실험실 공백 출처로 나눈다.\n\n");
        sb.append("| 날짜 | 기록 줄 | 녹화(boards) | 받음 | 빠짐 | 세션 밖 | 끊김 | 재기동 | 게이트웨이 큐 | 실험실 큐 | 설명 없음 "
                + "| 녹화에 없는 기록 줄(녹화 공백 안/설명 없음) | 기록·녹화 정상 종료 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
        for (DayRow r : week) {
            if (!r.recordingPresent()) {
                sb.append("| ").append(r.date()).append(" | ").append(r.journalLines())
                        .append(" | 녹화 없음 | - | - | - | - | - | - | - | - | - | ").append(yn(r.journalComplete()))
                        .append("/- |\n");
                continue;
            }
            sb.append("| ").append(r.date()).append(" | ").append(r.journalLines()).append(" | ").append(r.recordedEvents())
                    .append(" | ").append(r.matched()).append(" | ").append(r.missingTotal());
            for (String k : missingKeys()) {
                sb.append(" | ").append(r.missing().getOrDefault(k, 0L));
            }
            sb.append(" | ").append(r.extraInRecorderGap()).append('/').append(r.extraUnexplained()).append(" | ")
                    .append(yn(r.journalComplete())).append('/').append(yn(r.recordingComplete())).append(" |\n");
        }
        appendTotal(sb, "이번 주 합계", week);
        appendTotal(sb, "운용 시작부터 누계", rows);

        sb.append("\n## 2. 공백 출처(이번 주, 입력 기록의 공백 줄 수)\n\n");
        sb.append("공백 줄은 종목마다 하나씩이다. 재연결 수가 상태 화면의 `feedReconnects`, 실험실 큐 칸의 빠짐 수가 `inboxDropped`에 ")
                .append("해당한다(실험실을 다시 띄워도 남는 값).\n\n");
        sb.append("| 날짜 | 공백 줄 합계 | 끊김(FEED-CLIENT) | 재연결 수 | 재기동(RECORDER) | 게이트웨이 큐(FEED) | 실험실 큐(LAB-INBOX) "
                + "| 녹화에서 전달된 공백 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (DayRow r : week) {
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
