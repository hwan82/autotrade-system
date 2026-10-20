package com.autotrade.sim.live;

import com.autotrade.domain.market.Symbol;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.recording.RecordedMarketDataSet;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.SelectionSource;
import com.autotrade.sim.selection.SessionSelections;
import java.time.LocalDate;
import java.util.ArrayList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 실시간 모의 리플레이 확인(ADR-010 2.5, D-076, SIM-13): 끝난 세션의 입력 기록을 리플레이 실행기로 다시 돌려 실시간 세션이 남긴
 * 상세 보고서({@value LiveSession#LIVE_DETAIL_FILE})와 바이트로 같은지 본다. 같은 입력·같은 코드라 같아야 하고, 다르면 실시간
 * 경로의 결함이다(실시간 대 리플레이 차이 0이 이 단계의 합격선). 결과는 녹화 실험(D-069)과 같은 세 파일로 쓴다.
 *
 * <p>입력 기록을 날마다 디스크에서 다시 읽어 돌린다(D-115: 메모리는 기간 길이와 상관없고, 대신 기록을 세 번 푼다). 그래도 무거워서
 * 실험실이 아니라 실험 실행 명령
 * ({@code runLiveReplayCheck}, 장 시간 보호·정지 요청 확인 D-063)으로만 돌린다.
 *
 * <p>선정 조합 계좌가 있으면(D-124) 실시간 세션이 남긴 적용 기록과 결과 파일을 그대로 읽어 같은 판정으로 돌린다. 기록일마다 적용
 * 기록이 가리키는 결과 파일이 있고 내용 해시가 맞아야 한다(맞지 않으면 확인하지 않고 멈춤, architect PR #191 N3). 계좌는 고정
 * 파일로 돌리고, 고정 파일의 입력 스냅샷이 PC에 남아 있으면 따로 다시 계산해 내용 해시가 같은지 본다(ADR-012 3.6, 계산 코드가
 * 바뀐 경우를 잡음). 스냅샷이 없거나 힙이 모자라면 "재계산 생략"으로 적는다. 다르면 실시간 대 리플레이가 같아도 {@code same}은
 * false다.
 */
public final class LiveReplayCheck {

    /** 비교에서 빼는 줄(코드 커밋). 실험실과 명령이 커밋을 다르게 알 수 있어 따로 적고 비교하지 않는다. */
    static final String CODE_LINE_PREFIX = "code ";

    private LiveReplayCheck() {
    }

    /** 확인 결과와 쓸 파일(이름 → 내용). {@code same}이 false면 실시간 경로 결함. */
    /**
     * @param same 실시간 대 리플레이 상세 보고서가 같은지(false면 실시간 경로 결함)
     * @param selectionsRecomputed 선정 재계산에서 다른 결과가 없었는지(생략은 다름이 아님, D-124). 선정 계좌가 없으면 true
     */
    public record Outcome(boolean same, boolean selectionsRecomputed, SimulationReport replay, Map<String, String> files) {
        public Outcome {
            Objects.requireNonNull(replay, "replay");
            files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }

        public Outcome(boolean same, SimulationReport replay, Map<String, String> files) {
            this(same, true, replay, files);
        }
    }

    /** 선정 재계산 없이(스냅샷 폴더를 주지 않음). */
    public static Outcome run(Path experimentDir, Path repoRoot, Path sessionsRoot, CodeVersion code,
            Consumer<String> log, RunGate gate) {
        return run(experimentDir, repoRoot, sessionsRoot, null, code, log, gate);
    }

    /**
     * @param experimentDir {@code experiments/<실험ID>}(experiment.yaml, live-detail.txt가 있는 곳)
     * @param sessionsRoot 세션 폴더들의 부모(설정 폴더 아래 {@code live-sessions})
     * @param snapshotsRoot 수집 스냅샷 폴더(선정 재계산용). null이면 재계산 생략
     * @throws IllegalArgumentException 입력이 맞지 않을 때(세션이 열려 있음, 실시간 보고서 없음 등)
     * @throws ExperimentStoppedException 정지 확인에서 멈췄을 때(결과 파일 없음)
     */
    public static Outcome run(Path experimentDir, Path repoRoot, Path sessionsRoot, Path snapshotsRoot, CodeVersion code,
            Consumer<String> log, RunGate gate) {
        Path file = experimentDir.resolve("experiment.yaml");
        LivePlan plan;
        String liveDetail;
        try {
            plan = LivePlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            Path live = experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE);
            if (!Files.isRegularFile(live)) {
                throw new IllegalArgumentException("실시간 세션 보고서가 없습니다: " + live
                        + " (실험실에서 세션을 끝내면 생깁니다)");
            }
            liveDetail = Files.readString(live, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일이나 실시간 보고서를 읽을 수 없습니다: " + experimentDir, e);
        }
        if (!plan.id().value().equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + plan.id());
        }
        gate.check();
        Path sessionDir = sessionsRoot.toAbsolutePath().resolve(plan.id().value());
        ensureSessionClosed(sessionDir);
        LiveSetup setup = LiveSetup.of(plan, repoRoot);
        List<Path> days = journalDays(setup, sessionDir);
        if (days.isEmpty()) {
            throw new IllegalArgumentException("입력 기록이 없습니다: " + sessionDir.resolve(LiveSession.JOURNAL_DIR));
        }
        // 날마다 디스크에서 다시 읽는다(D-115). 기간 전체를 메모리에 올리지 않는다. 호가 단위 검사와 종목 모으기는 첫 읽기에서 한다
        TreeSet<Symbol> symbols = new TreeSet<>();
        StreamedRecordingDataSet data = StreamedRecordingDataSet.open(plan.market(), days, gate::check, e -> {
            RecordingExperiment.offStockTick(setup.rules(), e).ifPresent(price -> {
                throw new IllegalArgumentException("입력 기록에 주식 호가 단위에 맞지 않는 가격이 있습니다: " + e.symbol().code() + " "
                        + price.toPlainString() + " (sequence " + e.sequence() + ")");
            });
            symbols.add(e.symbol());
        });
        for (RecordedMarketDataSet.DayInfo d : data.days()) {
            log.accept("기록일 " + d.date() + ": 줄 " + d.recordedEvents() + ", 정상 종료 " + d.complete() + ", 구간 "
                    + d.segments());
        }
        gate.check();
        List<SessionSelections.Applied> applied = verifiedSelections(setup, sessionDir, log);
        requireApplied(setup, sessionDir, data.days().stream().filter(d -> d.recordedEvents() > 0)
                .map(RecordedMarketDataSet.DayInfo::date).toList());
        ExperimentSpec spec = setup.spec(ClockMode.REPLAY, data.version());
        SimulationReport replay = new SimulationExperimentRunner(data, setup.rules(), setup.cost(), setup.factory(),
                setup.risk(), setup.execution(), code, 1, setup.benchmark(), selectionSource(setup, sessionDir), gate)
                .intradayUniverse(setup.intradayUniverse()).runDetailed(spec);
        String detail = ReportWriter.render(replay);
        int diff = firstDifference(withoutCodeLine(liveDetail), withoutCodeLine(detail));
        boolean same = diff < 0;
        String determinism = same
                ? "같음(실시간 세션 대 입력 기록 리플레이, 상세 보고서 바이트 일치, code 줄 제외. 실시간 "
                        + codeLine(liveDetail) + ")"
                : "다름(code 줄 뺀 " + (diff + 1) + "번째 줄부터. 실시간 " + codeLine(liveDetail) + ")";
        log.accept("실시간 대 리플레이: " + determinism);
        String recompute = "";
        boolean recomputed = true;
        if (setup.hasSelections()) {
            Recompute r = recomputeSelections(setup, sessionDir, applied, snapshotsRoot,
                    rows -> LiveSelectionPrep.heapFits(rows, Runtime.getRuntime().maxMemory()), log, gate);
            log.accept("선정 재계산: " + r.line());
            recompute = "selection-recompute: \"" + r.line() + "\"\n";
            recomputed = r.different() == 0;
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put("inputs.yaml", "# 실시간 모의(D-076). 입력은 실험실 세션의 입력 기록(설정 폴더, 저장소 밖)\nclock-mode: REALTIME\n"
                + recompute + RecordingExperiment.inputs(setup.asRecordingPlan(), data, symbols, setup.cost(), setup.execution(),
                        setup.blacklist(), replay, determinism, detail, applied));
        files.put("results.md", RecordingExperiment.results(setup.asRecordingPlan(), data, setup.cost(),
                setup.execution(), setup.blacklist(), replay, determinism, applied));
        files.put("detail.txt", detail);
        return new Outcome(same, recomputed, replay, files);
    }

    /** {@link #run} 후 결과 파일을 등록 파일 옆에 쓴다. 멈추면 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path sessionsRoot, CodeVersion code,
            Consumer<String> log, RunGate gate) {
        return runAndWrite(experimentDir, repoRoot, sessionsRoot, null, code, log, gate);
    }

    /** {@link #run}(선정 재계산 포함) 후 결과 파일을 등록 파일 옆에 쓴다. 멈추면 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path sessionsRoot, Path snapshotsRoot,
            CodeVersion code, Consumer<String> log, RunGate gate) {
        Outcome out = run(experimentDir, repoRoot, sessionsRoot, snapshotsRoot, code, log, gate);
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            Path target = experimentDir.resolve(f.getKey());
            try {
                Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
            }
            log.accept("작성: " + target);
        }
        return out;
    }

    /** 선정 계좌가 있으면 세션 폴더의 적용 기록을 읽는 공급자, 없으면 null(지금까지와 같은 실행기 입력). */
    static SelectionSource selectionSource(LiveSetup setup, Path sessionDir) {
        return setup.hasSelections() ? setup.selectionFiles(sessionDir).source() : null;
    }

    /**
     * 운용 기간의 거래일마다 적용 기록과 결과 파일이 맞는지 보고 적용 기록을 날짜순으로 모은다. 적용 기록은 거래일 기준이라 입력 기록
     * 폴더(수신 날짜)가 아니라 기간 전체를 본다. 적용 기록이 없는 날은 빠진다(그날 선정 계좌는 {@code SELECTION_MISSING}).
     * 선정 계좌가 없으면 빈 목록.
     *
     * @throws IllegalStateException 적용 기록이 가리키는 결과 파일이 없거나 바뀌었을 때
     */
    static List<SessionSelections.Applied> verifiedSelections(LiveSetup setup, Path sessionDir, Consumer<String> log) {
        if (!setup.hasSelections()) {
            return List.of();
        }
        SessionSelections files = setup.selectionFiles(sessionDir);
        List<SessionSelections.Applied> out = new ArrayList<>();
        for (LocalDate d = setup.plan().from(); !d.isAfter(setup.plan().to()); d = d.plusDays(1)) {
            files.verify(d);
            files.readApplied(d).ifPresent(out::add);
        }
        log.accept("선정 적용 기록 " + out.size() + "일 확인(결과 파일·내용 해시·후보 일치)");
        return List.copyOf(out);
    }

    /**
     * 입력 기록이 있는 날마다 선정 적용 기록이 있는지 본다(architect PR #197 N1). 날짜 전환 지점은 그날 첫 줄을 기록하기 전에 적용
     * 기록을 쓰므로, 줄이 있는데 기록이 없으면 누가 지웠거나 옮긴 것이다. 그대로 두면 되살리기·리플레이는 그날을 조용히
     * {@code SELECTION_MISSING}으로 보고, 같은 날 이어 받으면 늦게 온 결과로 새 기록을 써서 실시간과 리플레이가 다른 선정을 본다.
     *
     * @param recordedDays 입력 기록에 줄이 있는 거래일
     * @throws IllegalStateException 적용 기록이 없는 기록일이 있을 때
     */
    static void requireApplied(LiveSetup setup, Path sessionDir, Collection<LocalDate> recordedDays) {
        if (!setup.hasSelections()) {
            return;
        }
        SessionSelections files = setup.selectionFiles(sessionDir);
        for (LocalDate d : recordedDays) {
            if (files.readApplied(d).isEmpty()) {
                throw new IllegalStateException("입력 기록이 있는 " + d + "의 선정 적용 기록(" + SessionSelections.DIR + "/" + d
                        + "/" + SessionSelections.APPLIED_FILE + ")이 없습니다. 그날 계좌가 본 선정을 알 수 없습니다(D-124)");
            }
        }
    }

    /**
     * 선정 재계산 결과(ADR-012 3.6).
     *
     * @param same 다시 계산한 내용 해시가 적용 기록과 같은 결과 수
     * @param different 다른 결과 수(계산 코드나 스냅샷이 바뀜)
     * @param skipped 입력 스냅샷이 없거나 힙이 모자라 다시 계산하지 않은 결과 수
     */
    record Recompute(int same, int different, int skipped, List<String> notes) {
        Recompute {
            notes = List.copyOf(notes);
        }

        String line() {
            if (same + different + skipped == 0) {
                return "적용한 결과 없음";
            }
            return "같음 " + same + ", 다름 " + different + ", 재계산 생략 " + skipped
                    + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
        }
    }

    /**
     * 적용한 결과 파일마다 같은 입력 스냅샷으로 다시 계산해 내용 해시를 비교한다. 스냅샷은 한 번에 하나만 올리고, 같은 스냅샷을 쓰는
     * 날이 이어지면 다시 읽지 않는다.
     *
     * @param heapFits 일봉 행 수 → 이 프로세스 힙으로 읽어도 되는지
     */
    static Recompute recomputeSelections(LiveSetup setup, Path sessionDir, List<SessionSelections.Applied> applied,
            Path snapshotsRoot, java.util.function.LongPredicate heapFits, Consumer<String> log, RunGate gate) {
        SessionSelections files = setup.selectionFiles(sessionDir);
        Map<String, com.autotrade.selection.profile.SelectionProfile> profiles = new LinkedHashMap<>();
        setup.selectionProfiles().forEach(p -> profiles.put(p.selectionHash(), p));
        int same = 0;
        int different = 0;
        int skipped = 0;
        java.util.TreeSet<String> notes = new java.util.TreeSet<>();
        String loadedKey = null;
        com.autotrade.selection.calc.SelectionCalculator calc = null;
        for (SessionSelections.Applied a : applied) {
            for (SessionSelections.Item it : a.items()) {
                if (!it.applied()) {
                    continue;
                }
                com.autotrade.selection.result.SelectionResult fixed;
                try {
                    fixed = com.autotrade.sim.selection.SelectionResultFile.read(Files.readString(
                            files.resultFile(a.date(), it.selectionHash()), StandardCharsets.UTF_8)).result();
                } catch (IOException e) {
                    throw new UncheckedIOException("선정 결과 파일을 읽을 수 없습니다: " + a.date(), e);
                }
                if (snapshotsRoot == null) {
                    skipped++;
                    notes.add("스냅샷 폴더를 주지 않음");
                    continue;
                }
                String bars = fixed.inputs().barsSnapshotId();
                java.util.Optional<String> fin = java.util.Optional.ofNullable(fixed.inputs().financialsSnapshotId());
                Path barsDir = snapshotsRoot.resolve(CollectionFreshness.KRX_DAILY).resolve(bars);
                boolean present = Files.isDirectory(barsDir) && fin.map(f -> Files.isDirectory(snapshotsRoot
                        .resolve(LiveSelectionPrep.DART_FINANCIALS).resolve(f))).orElse(true);
                if (!present) {
                    skipped++;
                    notes.add("입력 스냅샷이 PC에 없음");
                    continue;
                }
                if (!heapFits.test(LiveSelectionPrep.dailyBarRows(snapshotsRoot, bars))) {
                    skipped++;
                    notes.add("힙이 모자람(-Pheap으로 늘림)");
                    continue;
                }
                String key = bars + "|" + fin.orElse("");
                if (!key.equals(loadedKey)) {
                    calc = null; // 앞 스냅샷을 놓고 읽는다
                    gate.check();
                    com.autotrade.sim.combo.SnapshotInputs in = com.autotrade.sim.combo.SnapshotInputs.load(
                            setup.plan().market(), bars, fin, snapshotsRoot, setup.calendar(), gate, log,
                            (first, last) -> { });
                    calc = new com.autotrade.selection.calc.SelectionCalculator(in.selectionData());
                    loadedKey = key;
                }
                String again;
                try {
                    again = calc.calculate(profiles.get(it.selectionHash()), a.date()).contentHash();
                } catch (RuntimeException e) {
                    again = "계산 실패(" + e.getClass().getSimpleName() + ")";
                }
                if (again.equals(it.contentHash())) {
                    same++;
                } else {
                    different++;
                    notes.add("다름: " + a.date() + " " + it.selection());
                }
            }
        }
        return new Recompute(same, different, skipped, List.copyOf(notes));
    }

    static List<Path> journalDays(LiveSetup setup, Path sessionDir) {
        com.autotrade.marketdata.recording.RecordingLayout layout =
                new com.autotrade.marketdata.recording.RecordingLayout(sessionDir.resolve(LiveSession.JOURNAL_DIR));
        java.util.ArrayList<Path> out = new java.util.ArrayList<>();
        for (java.time.LocalDate d = setup.plan().from(); !d.isAfter(setup.plan().to()); d = d.plusDays(1)) {
            Path day = layout.dayDir(setup.plan().market(), d);
            if (Files.isRegularFile(day.resolve(com.autotrade.marketdata.recording.RecordingLayout.EVENTS_FILE))) {
                out.add(day);
            }
        }
        return out;
    }

    /** 실험실이 세션을 열고 있으면(기록이 아직 쓰이는 중) 거부한다. */
    static void ensureSessionClosed(Path sessionDir) {
        Path lockFile = sessionDir.resolve(LiveSession.LOCK_FILE);
        if (!Files.exists(lockFile)) {
            return;
        }
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                throw new IllegalArgumentException("실험실이 이 세션을 열고 있습니다. 세션을 끝낸 뒤 확인하세요: " + sessionDir);
            }
            lock.release();
        } catch (IOException e) {
            throw new UncheckedIOException("세션 잠금 파일을 확인할 수 없습니다: " + lockFile, e);
        }
    }

    static String withoutCodeLine(String detail) {
        StringBuilder sb = new StringBuilder();
        for (String line : detail.split("\n", -1)) {
            if (!line.startsWith(CODE_LINE_PREFIX)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String codeLine(String detail) {
        for (String line : detail.split("\n", -1)) {
            if (line.startsWith(CODE_LINE_PREFIX)) {
                return line;
            }
        }
        return "code 없음";
    }

    /** 첫 번째로 다른 줄 번호(0부터), 같으면 -1. */
    static int firstDifference(String a, String b) {
        if (a.equals(b)) {
            return -1;
        }
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            if (!x[i].equals(y[i])) {
                return i;
            }
        }
        return n;
    }
}
