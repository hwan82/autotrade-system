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
 */
public final class LiveReplayCheck {

    /** 비교에서 빼는 줄(코드 커밋). 실험실과 명령이 커밋을 다르게 알 수 있어 따로 적고 비교하지 않는다. */
    static final String CODE_LINE_PREFIX = "code ";

    private LiveReplayCheck() {
    }

    /** 확인 결과와 쓸 파일(이름 → 내용). {@code same}이 false면 실시간 경로 결함. */
    public record Outcome(boolean same, SimulationReport replay, Map<String, String> files) {
        public Outcome {
            Objects.requireNonNull(replay, "replay");
            files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
    }

    /**
     * @param experimentDir {@code experiments/<실험ID>}(experiment.yaml, live-detail.txt가 있는 곳)
     * @param sessionsRoot 세션 폴더들의 부모(설정 폴더 아래 {@code live-sessions})
     * @throws IllegalArgumentException 입력이 맞지 않을 때(세션이 열려 있음, 실시간 보고서 없음 등)
     * @throws ExperimentStoppedException 정지 확인에서 멈췄을 때(결과 파일 없음)
     */
    public static Outcome run(Path experimentDir, Path repoRoot, Path sessionsRoot, CodeVersion code,
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
        ExperimentSpec spec = setup.spec(ClockMode.REPLAY, data.version());
        SimulationReport replay = new SimulationExperimentRunner(data, setup.rules(), setup.cost(), setup.factory(),
                setup.risk(), setup.execution(), code, 1, setup.benchmark(), null, gate).runDetailed(spec);
        String detail = ReportWriter.render(replay);
        int diff = firstDifference(withoutCodeLine(liveDetail), withoutCodeLine(detail));
        boolean same = diff < 0;
        String determinism = same
                ? "같음(실시간 세션 대 입력 기록 리플레이, 상세 보고서 바이트 일치, code 줄 제외. 실시간 "
                        + codeLine(liveDetail) + ")"
                : "다름(code 줄 뺀 " + (diff + 1) + "번째 줄부터. 실시간 " + codeLine(liveDetail) + ")";
        log.accept("실시간 대 리플레이: " + determinism);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("inputs.yaml", "# 실시간 모의(D-076). 입력은 실험실 세션의 입력 기록(설정 폴더, 저장소 밖)\nclock-mode: REALTIME\n"
                + RecordingExperiment.inputs(setup.asRecordingPlan(), data, symbols, setup.cost(), setup.execution(),
                        setup.blacklist(), replay, determinism, detail));
        files.put("results.md", RecordingExperiment.results(setup.asRecordingPlan(), data, setup.cost(),
                setup.execution(), setup.blacklist(), replay, determinism));
        files.put("detail.txt", detail);
        return new Outcome(same, replay, files);
    }

    /** {@link #run} 후 결과 파일을 등록 파일 옆에 쓴다. 멈추면 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path sessionsRoot, CodeVersion code,
            Consumer<String> log, RunGate gate) {
        Outcome out = run(experimentDir, repoRoot, sessionsRoot, code, log, gate);
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

    private static List<Path> journalDays(LiveSetup setup, Path sessionDir) {
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
    private static void ensureSessionClosed(Path sessionDir) {
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
