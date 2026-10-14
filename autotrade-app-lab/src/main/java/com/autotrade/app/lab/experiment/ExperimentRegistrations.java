package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.profile.SelectionProfileStore;
import com.autotrade.app.lab.profile.StoredProfile;
import com.autotrade.app.lab.profile.StoredSelection;
import com.autotrade.app.lab.profile.StrategyProfileStore;
import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.selection.calc.Metric;
import com.autotrade.selection.calc.SelectionCriteria;
import com.autotrade.sim.combo.CombinationPlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 실험 생성 화면(UI-02)이 조합 실험 등록 파일 {@code experiments/<실험ID>/experiment.yaml}을 쓴다(D-074). 실험실은 실험을
 * 돌리지 않는다(D-062): 등록 파일을 쓰고 실행 명령을 알려 줄 뿐이고, 사용자가 등록 파일을 커밋한 뒤 실행 명령으로 돌리면
 * 실험 폴더 목록에서 결과를 불러온다.
 *
 * <p>쓰기 전에 확인하는 것: 실험 ID 모양과 새 폴더(있으면 거절, 등록 파일은 사전 등록이라 덮어쓰지 않음), 프로필 버전이 있음,
 * 조합마다 시장·스타일이 실험과 같음(STR-02, 화면 필터와 같은 규칙), 재무 지표를 쓰는 선정이 있으면 재무 스냅샷 필수, 쓴 글을
 * {@link CombinationPlan#parse}로 다시 읽어 요청과 같음. 조합 실험 실행 명령은 국내(KRX)만 돌리므로 다른 시장은 거절한다.
 */
public final class ExperimentRegistrations {

    /** 조합 수 상한(SIM-14 {@code SimulationExperimentRunner.MAX_COMBINATIONS}와 같은 값). */
    static final int MAX_COMBINATIONS = 128;
    /** {@code ExperimentFolders}·{@code CombinationMain}과 같은 실험 ID 모양. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_QUESTION = 500;
    private static final String COMMAND = "./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=%s"
            + " -PverifyDeterminism";

    private final Path root;
    private final StrategyProfileStore strategies;
    private final SelectionProfileStore selections;

    public ExperimentRegistrations(Path root, StrategyProfileStore strategies, SelectionProfileStore selections) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.strategies = Objects.requireNonNull(strategies, "strategies");
        this.selections = Objects.requireNonNull(selections, "selections");
    }

    /**
     * 화면 입력. 문자열은 화면이 보낸 그대로이고 여기서 검사한다.
     *
     * @param style 이 실험의 매매 스타일. 모든 조합의 선정·전략 프로필이 이 스타일이어야 한다
     * @param combinations {@code 선정 id@버전 x 전략 id@버전}(선정 없으면 {@code none})
     */
    public record Request(String id, String question, Market market, TradingStyle style, String from, String to,
            String seed, String seedCapital, String barsSnapshot, String financialsSnapshot, String benchmark,
            String parallelism, List<String> combinations, List<String> blacklist) {
    }

    /**
     * @param yaml 쓴(또는 미리 보기면 쓸) 등록 파일 본문
     * @param command 실행 명령
     * @param written 파일을 썼으면 true(미리 보기면 false)
     */
    public record Registration(String id, String yaml, String command, boolean written) {
    }

    /** 검사하고 등록 파일 본문을 만든다. 파일은 쓰지 않는다. */
    public Registration preview(Request request) {
        String yaml = render(request);
        return new Registration(request.id().strip(), yaml, COMMAND.formatted(request.id().strip()), false);
    }

    /**
     * 검사하고 새 폴더에 등록 파일을 쓴다.
     *
     * @throws IllegalArgumentException 입력이 틀렸을 때(메시지에 이유)
     * @throws IllegalStateException 같은 실험 ID 폴더가 이미 있을 때
     */
    public Registration create(Request request) {
        String yaml = render(request);
        String id = request.id().strip();
        Path dir = root.resolve(id).normalize();
        try {
            Files.createDirectories(root);
            // 폴더 만들기가 원자적 "새로 만들기"다. 있으면 실패해 앞선 등록(사전 등록)을 덮어쓰지 않는다
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("같은 실험 ID 폴더가 이미 있습니다. 등록 파일은 고치지 않고 새 실험 ID를 씁니다: " + id);
        } catch (IOException e) {
            throw new UncheckedIOException("실험 폴더를 만들 수 없습니다: " + id, e);
        }
        Path tmp = dir.resolve("experiment.yaml.tmp");
        try {
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("experiment.yaml"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 쓸 수 없습니다: " + id, e);
        }
        return new Registration(id, yaml, COMMAND.formatted(id), true);
    }

    private String render(Request r) {
        if (r == null) {
            throw new IllegalArgumentException("요청이 비었습니다");
        }
        String id = required(r.id(), "실험 ID");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("실험 ID는 영문·숫자로 시작하고 영문·숫자·.·_·- 128자 이내여야 합니다");
        }
        String question = required(r.question(), "질문");
        if (question.length() > MAX_QUESTION || question.chars().anyMatch(c -> c == '\n' || c == '\r')) {
            throw new IllegalArgumentException("질문은 한 줄, " + MAX_QUESTION + "자 이내여야 합니다");
        }
        if (r.market() == null || r.style() == null) {
            throw new IllegalArgumentException("시장과 스타일은 필수입니다");
        }
        if (r.market() != Market.KRX) {
            throw new IllegalArgumentException("조합 실험 실행 명령은 아직 국내(KRX)만 돌립니다");
        }
        LocalDate from = date(r.from(), "시작일");
        LocalDate to = date(r.to(), "종료일");
        long seed = number(r.seed(), "시드", 0, Long.MAX_VALUE);
        long capital = number(r.seedCapital(), "시드머니", 1, Long.MAX_VALUE);
        int parallelism = (int) number(r.parallelism() == null || r.parallelism().isBlank() ? "1" : r.parallelism(),
                "병렬 수", 1, 64);
        String bars = required(r.barsSnapshot(), "일봉 스냅샷");
        String financials = r.financialsSnapshot() == null ? "" : r.financialsSnapshot().strip();
        String benchmark = r.benchmark() == null || r.benchmark().isBlank() ? "none" : r.benchmark().strip();

        List<String> combos = new ArrayList<>();
        boolean needsFinancials = false;
        Set<String> seen = new LinkedHashSet<>();
        for (String c : r.combinations() == null ? List.<String>of() : r.combinations()) {
            String text = c == null ? "" : c.strip();
            if (!seen.add(text)) {
                throw new IllegalArgumentException("같은 조합이 두 번 있습니다: " + text);
            }
            needsFinancials |= checkCombination(text, r.market(), r.style());
            combos.add(text);
        }
        if (combos.isEmpty()) {
            throw new IllegalArgumentException("조합을 하나 이상 고르세요");
        }
        if (combos.size() > MAX_COMBINATIONS) {
            throw new IllegalArgumentException("조합은 " + MAX_COMBINATIONS + "개까지입니다(SIM-14): " + combos.size());
        }
        if (needsFinancials && financials.isEmpty()) {
            throw new IllegalArgumentException("재무 지표를 쓰는 선정 프로필이 있어 재무 스냅샷이 필요합니다");
        }
        Set<String> black = new TreeSet<>();
        for (String code : r.blacklist() == null ? List.<String>of() : r.blacklist()) {
            if (code != null && !code.isBlank()) {
                black.add(code.strip());
            }
        }

        StringBuilder y = new StringBuilder();
        y.append("# 조합 실험 등록 파일(사전 등록, 조합 실험 절차 1장). 실험실 실험 생성 화면이 썼다(D-074).\n");
        y.append("# 실행 전에 커밋하고 결과를 본 뒤 고치지 않는다. 바꾸려면 새 실험 ID를 쓴다.\n");
        y.append("# 실행: ").append(COMMAND.formatted(id)).append('\n');
        y.append("id: ").append(quote(id)).append('\n');
        y.append("question: ").append(quote(question)).append('\n');
        y.append("market: ").append(quote(r.market().name())).append('\n');
        y.append("from: ").append(quote(from.toString())).append('\n');
        y.append("to: ").append(quote(to.toString())).append('\n');
        y.append("seed: ").append(quote(Long.toString(seed))).append('\n');
        y.append("seed-capital: ").append(quote(Long.toString(capital))).append('\n');
        y.append("bars-snapshot: ").append(quote(bars)).append('\n');
        if (!financials.isEmpty()) {
            y.append("financials-snapshot: ").append(quote(financials)).append('\n');
        }
        y.append("benchmark: ").append(quote(benchmark)).append('\n');
        y.append("parallelism: ").append(quote(Integer.toString(parallelism))).append('\n');
        y.append("combinations:\n");
        combos.forEach(c -> y.append("  - ").append(quote(c)).append('\n'));
        if (!black.isEmpty()) {
            y.append("blacklist:\n");
            black.forEach(c -> y.append("  - ").append(quote(c)).append('\n'));
        }
        String yaml = y.toString();

        // 실행 명령이 읽는 것과 같은 해석기로 다시 읽어, 실행 때 거절될 글을 쓰지 않는다
        CombinationPlan plan;
        try {
            plan = CombinationPlan.parse(yaml, "experiment.yaml");
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (!plan.id().value().equals(id) || !plan.question().equals(question)
                || !plan.combinations().stream().map(CombinationPlan.Entry::text).toList().equals(combos)) {
            throw new IllegalStateException("등록 파일을 다시 읽은 값이 입력과 다릅니다");
        }
        return yaml;
    }

    /** 조합 한 줄을 검사한다. 선정이 재무 지표를 쓰면 true. */
    private boolean checkCombination(String text, Market market, TradingStyle style) {
        String[] parts = text.split(" x ", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("조합은 \"선정 id@버전 x 전략 id@버전\"(선정 없으면 none) 형식이어야 합니다: " + text);
        }
        CombinationPlan.Ref strategyRef = ref(parts[1], text);
        StoredProfile strategy = strategies.find(strategyRef.id(), strategyRef.version())
                .orElseThrow(() -> new IllegalArgumentException("전략 프로필 버전이 없습니다: " + strategyRef.text()));
        if (strategy.profile().market() != market || strategy.profile().style() != style) {
            throw new IllegalArgumentException("전략 프로필의 시장·스타일이 실험과 다릅니다(STR-02): " + strategyRef.text());
        }
        if (parts[0].equals("none")) {
            return false;
        }
        CombinationPlan.Ref selectionRef = ref(parts[0], text);
        StoredSelection selection = selections.find(selectionRef.id(), selectionRef.version())
                .orElseThrow(() -> new IllegalArgumentException("선정 프로필 버전이 없습니다: " + selectionRef.text()));
        if (selection.profile().market() != market || selection.profile().style() != style) {
            throw new IllegalArgumentException("선정 프로필의 시장·스타일이 실험과 다릅니다(STR-02): " + selectionRef.text());
        }
        Optional<String> problem = SelectionProfileStore.catalogProblem(selection.profile());
        if (problem.isPresent()) {
            throw new IllegalArgumentException("지금 카탈로그로 해석되지 않는 선정 프로필입니다: " + selectionRef.text());
        }
        return SelectionCriteria.parse(selection.profile()).inputKinds().contains(Metric.Kind.FINANCIAL);
    }

    private static CombinationPlan.Ref ref(String text, String combination) {
        try {
            return CombinationPlan.Ref.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("조합의 프로필은 id@버전 형식이어야 합니다: " + combination, e);
        }
    }

    private static String required(String v, String label) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(label + "은(는) 필수입니다");
        }
        return v.strip();
    }

    private static LocalDate date(String v, String label) {
        try {
            return LocalDate.parse(required(v, label));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + "은(는) YYYY-MM-DD 형식이어야 합니다");
        }
    }

    private static long number(String v, String label, long min, long max) {
        long n;
        try {
            n = Long.parseLong(required(v, label));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "은(는) 정수여야 합니다");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(label + "은(는) " + min + " 이상" + (max == Long.MAX_VALUE ? "" : ", " + max
                    + " 이하") + "여야 합니다");
        }
        return n;
    }

    /** {@code RegistrationYaml}이 읽는 큰따옴표 값({@code \"}·{@code \\}만 이스케이프). */
    static String quote(String v) {
        return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
