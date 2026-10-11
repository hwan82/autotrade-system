package com.autotrade.app.lab.experiment;

import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.combo.CombinationPlan;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.ResultBundle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * 조합 실험 폴더 {@code experiments/<실험ID>/}의 결과를 실험 화면에 올린다(D-062). 실험을 다시 돌리지 않고, 실행 명령
 * ({@code runCombinationExperiment})이 쓴 결과 묶음({@value ResultBundle#FILE_NAME})을 읽는다.
 *
 * <p>올리기 전에 확인하는 것: 폴더 이름 = 실험 ID, 결과 묶음에서 되살린 결과를 {@link ReportWriter}로 다시 그린 글이 같은 폴더의
 * {@code detail.txt}(커밋된 정규 보고서)와 바이트까지 같음, 결과 묶음의 선정 결과 내용 해시 집합이 {@code selection-days.tsv}의
 * {@code result_hash} 집합과 같음. 하나라도 다르면 올리지 않는다(다른 실행의 묶음이거나 손상).
 *
 * <p>실험실 프로세스 메모리에만 올린다({@link InMemoryExperimentCatalog}). 실험실을 다시 띄우면 다시 불러온다.
 */
public final class ExperimentFolders {

    /** {@code CombinationMain}과 같은 실험 ID 모양. 경로 구분자와 {@code ..}가 들어갈 수 없다. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;
    private final ExperimentCatalog catalog;
    private final Map<String, String> lastErrors = new HashMap<>();

    public ExperimentFolders(Path root, ExperimentCatalog catalog) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Path root() {
        return root;
    }

    /**
     * 폴더 하나의 상태.
     *
     * @param state LOADED(화면에 있음), READY(불러올 수 있음), NO_BUNDLE(결과 묶음 없음: 실행 명령을 아직 안 돌렸거나 D-062 전
     *     실행), NOT_RUN(결과 파일 없음)
     * @param lastError 이 프로세스에서 마지막으로 불러오기에 실패한 사유. 없으면 null
     */
    public record Folder(String id, String question, String period, int combinations, String state, String lastError) {
    }

    /** 조합 실험 등록 파일이 있는 폴더(실험 ID 내림차순). 다른 모양의 {@code experiment.yaml}(합성 시세 실험 등)은 뺀다. */
    public synchronized List<Folder> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Folder> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                String id = dir.getFileName().toString();
                if (!ID.matcher(id).matches() || !Files.isRegularFile(dir.resolve("experiment.yaml"))) {
                    continue;
                }
                CombinationPlan plan;
                try {
                    plan = CombinationPlan.parse(Files.readString(dir.resolve("experiment.yaml"), StandardCharsets.UTF_8),
                            dir.resolve("experiment.yaml").toString());
                } catch (RuntimeException | IOException e) {
                    continue;
                }
                String state;
                if (catalog.detail(plan.id()).isPresent()) {
                    state = "LOADED";
                } else if (Files.isRegularFile(dir.resolve(ResultBundle.FILE_NAME))) {
                    state = "READY";
                } else if (Files.isRegularFile(dir.resolve("detail.txt"))) {
                    state = "NO_BUNDLE";
                } else {
                    state = "NOT_RUN";
                }
                out.add(new Folder(id, plan.question(), plan.from() + " ~ " + plan.to(), plan.combinations().size(), state,
                        lastErrors.get(id)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("실험 폴더를 읽을 수 없습니다: " + root, e);
        }
        return out;
    }

    /**
     * 결과 묶음을 읽어 확인하고 화면에 올린다.
     *
     * @throws java.util.NoSuchElementException 폴더나 결과 파일이 없을 때
     * @throws IllegalStateException 이미 올라가 있을 때
     * @throws IllegalArgumentException 형식이 어긋나거나 확인에 실패했을 때(올리지 않는다)
     */
    public synchronized ExperimentId load(String id) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("실험 ID 형식이 아닙니다");
        }
        Path dir = root.resolve(id).normalize();
        if (!dir.getParent().equals(root) || !Files.isRegularFile(dir.resolve("experiment.yaml"))) {
            throw new java.util.NoSuchElementException("실험 폴더가 없습니다: " + id);
        }
        Path bundle = dir.resolve(ResultBundle.FILE_NAME);
        Path detail = dir.resolve("detail.txt");
        if (!Files.isRegularFile(bundle) || !Files.isRegularFile(detail)) {
            throw new java.util.NoSuchElementException("결과 묶음이나 detail.txt가 없습니다. 실행 명령으로 다시 만드세요: " + id);
        }
        if (catalog.detail(new ExperimentId(id)).isPresent()) {
            throw new IllegalStateException("이미 불러온 실험입니다: " + id);
        }
        try {
            ResultBundleReader.Loaded loaded;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(bundle), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
                loaded = ResultBundleReader.read(in, ResultBundle.FILE_NAME);
            }
            if (!loaded.spec().id().value().equals(id)) {
                throw new IllegalArgumentException("결과 묶음의 실험 ID가 폴더 이름과 다릅니다: " + loaded.spec().id());
            }
            if (!ReportWriter.render(loaded.report()).equals(Files.readString(detail, StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("결과 묶음이 detail.txt와 다릅니다(다른 실행의 묶음이거나 결과 파일이 바뀜). "
                        + "실행 명령으로 다시 만드세요");
            }
            Set<String> expected = selectionDayHashes(dir.resolve("selection-days.tsv"));
            Set<String> actual = loaded.selectionResults().stream().map(SelectionResult::contentHash)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException("결과 묶음의 선정 결과가 selection-days.tsv와 다릅니다");
            }
            catalog.register(loaded.spec(), loaded.report(), loaded.selectionResults());
            lastErrors.remove(id);
            return loaded.spec().id();
        } catch (IOException e) {
            lastErrors.put(id, "결과 파일을 읽을 수 없습니다");
            throw new IllegalArgumentException("결과 파일을 읽을 수 없습니다: " + id, e);
        } catch (RuntimeException e) {
            lastErrors.put(id, e.getMessage());
            throw e;
        }
    }

    /** {@code selection-days.tsv}의 {@code result_hash} 열(선정 없는 실험이면 머리줄만 있어 빈 집합). 파일이 없으면 빈 집합. */
    static Set<String> selectionDayHashes(Path file) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return out;
        }
        int col = List.of(lines.get(0).split("\t", -1)).indexOf("result_hash");
        if (col < 0) {
            throw new IllegalArgumentException("selection-days.tsv에 result_hash 열이 없습니다");
        }
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isEmpty()) {
                out.add(line.split("\t", -1)[col]);
            }
        }
        return out;
    }
}
