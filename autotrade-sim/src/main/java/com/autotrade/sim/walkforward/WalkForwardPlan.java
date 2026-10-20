package com.autotrade.sim.walkforward;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.combo.CombinationPlan;
import com.autotrade.sim.run.RegistrationYaml;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 워크포워드 판정 실험 등록 파일 {@code experiments/<실험ID>/study.yaml}(4단계 계획 3장, 사전 등록). 실행 전에 커밋하고 결과를
 * 본 뒤 고치지 않는다. 바꾸려면 새 실험 ID를 쓴다.
 *
 * <pre>
 * id: "EXP-20261020-WF01"
 * question: "..."
 * market: "KRX"
 * bars-snapshot: "krx-daily-..."
 * financials-snapshot: "dart-financials-..."   # 재무 선정이 없으면 생략
 * disclosures-snapshot: "dart-disclosures-..." # 공시 조건 선정이 없으면 생략(D-122)
 * seed: "20261020"
 * seed-capital: "10000000"
 * parallelism: "4"
 * first-test-from: "2022-04-01"   # 첫 검증 구간 시작. 학습은 그 train-years년 전부터
 * train-years: "3"
 * test-years: "1"
 * windows: "4"                    # 3 이상(D-066)
 * warmup-days: "300"              # 각 구간 앞에 시세만 주고 진입은 막는 거래일 수(후보·±20% 변형 예열 필요량 이상, 모자라면 시작 거부)
 * null-seeds: "400"               # 조합마다 무작위 대조군 시드 수. C10은 N + 1 &ge; 20m 필요
 * combinations:                   # 후보(선정 x 전략), CombinationPlan과 같은 형식
 *   - "none x kr-ma-trend@1"
 * controls:                       # 스타일별 무작위 대조군 전략. 후보와 같은 선정으로 묶는다
 *   - "SWING kr-random-daily-swing@1"
 *   - "POSITION kr-random-daily-position@1"
 * blacklist:                      # 선택. 실험 시작 시점 제외 종목
 *   - "005930"
 * </pre>
 *
 * <p>읽는 모양은 {@link RegistrationYaml}이고 모르는 키, 같은 키 두 번, 같은 조합·같은 스타일 대조군 두 번은 거절한다.
 *
 * @param combinations 후보. 이 순서가 결과 표 순서다. 개수가 판정의 m(본페로니)이다
 * @param controls 스타일 → 무작위 대조군 전략
 */
public record WalkForwardPlan(
        ExperimentId id,
        String question,
        Market market,
        String barsSnapshot,
        Optional<String> financialsSnapshot,
        Optional<String> disclosuresSnapshot,
        long seed,
        Money seedCapital,
        int parallelism,
        LocalDate firstTestFrom,
        int trainYears,
        int testYears,
        int windows,
        int warmupDays,
        int nullSeeds,
        List<CombinationPlan.Entry> combinations,
        Map<TradingStyle, CombinationPlan.Ref> controls,
        List<String> blacklist) {

    /** 구간 수 하한(D-066 "최소 3구간"). */
    public static final int MIN_WINDOWS = 3;
    /** 조합 수 상한. {@code SimulationExperimentRunner.MAX_COMBINATIONS}와 같은 128. */
    public static final int MAX_COMBINATIONS = 128;

    private static final Set<String> SCALARS = Set.of("id", "question", "market", "bars-snapshot", "financials-snapshot",
            "disclosures-snapshot", "seed", "seed-capital", "parallelism", "first-test-from", "train-years", "test-years", "windows",
            "warmup-days", "null-seeds");
    private static final Set<String> LISTS = Set.of("combinations", "controls", "blacklist");
    private static final Pattern ENTRY = Pattern.compile(
            "(none|[a-z0-9][a-z0-9-]*@[1-9][0-9]*) x ([a-z0-9][a-z0-9-]*@[1-9][0-9]*)");
    private static final Pattern CONTROL = Pattern.compile("(SWING|POSITION) ([a-z0-9][a-z0-9-]*@[1-9][0-9]*)");
    private static final Pattern SNAPSHOT = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{6}");
    /** 구간·시드별 실험 ID 접미어({@code -W12-N1234}) 때문에 실험 ID는 짧아야 한다(ExperimentId 48자). */
    private static final int MAX_ID_LENGTH = 30;

    public WalkForwardPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(barsSnapshot, "barsSnapshot");
        Objects.requireNonNull(financialsSnapshot, "financialsSnapshot");
        Objects.requireNonNull(disclosuresSnapshot, "disclosuresSnapshot");
        Objects.requireNonNull(seedCapital, "seedCapital");
        Objects.requireNonNull(firstTestFrom, "firstTestFrom");
        combinations = List.copyOf(combinations);
        controls = Map.copyOf(controls);
        blacklist = List.copyOf(blacklist);
        if (id.value().length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("실험 ID는 " + MAX_ID_LENGTH + "자 이하여야 합니다(구간·시드별 하위 ID 때문): " + id);
        }
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("parallelism은 1~64여야 합니다: " + parallelism);
        }
        if (trainYears < 1 || trainYears > 10) {
            throw new IllegalArgumentException("train-years는 1~10이어야 합니다: " + trainYears);
        }
        if (testYears < 1 || testYears > 5) {
            throw new IllegalArgumentException("test-years는 1~5여야 합니다: " + testYears);
        }
        if (windows < MIN_WINDOWS || windows > 20) {
            throw new IllegalArgumentException("windows는 " + MIN_WINDOWS + "~20이어야 합니다(D-066 최소 3구간): " + windows);
        }
        if (warmupDays < 0 || warmupDays > 1000) {
            throw new IllegalArgumentException("warmup-days는 0~1000이어야 합니다: " + warmupDays);
        }
        if (nullSeeds < 1 || nullSeeds > 5000) {
            throw new IllegalArgumentException("null-seeds는 1~5000이어야 합니다: " + nullSeeds);
        }
        if (combinations.isEmpty()) {
            throw new IllegalArgumentException("후보 조합이 하나 이상 필요합니다");
        }
        if (combinations.size() > MAX_COMBINATIONS) {
            throw new IllegalArgumentException("후보 조합 수가 상한을 넘습니다: " + combinations.size() + " > " + MAX_COMBINATIONS);
        }
    }

    /** 본페로니 보정에 쓰는 비교 조합 수 m. */
    public int m() {
        return combinations.size();
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일 경로)
     * @throws IllegalArgumentException 형식이 틀렸거나 필수 키가 없을 때. 메시지에 줄 번호가 있다
     */
    public static WalkForwardPlan parse(String text, String source) {
        RegistrationYaml raw = RegistrationYaml.parse(text, source, SCALARS, LISTS);
        try {
            return build(raw);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static WalkForwardPlan build(RegistrationYaml raw) {
        Map<String, String> s = raw.scalars();
        Market market = Market.valueOf(raw.required("market"));
        List<CombinationPlan.Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String c : raw.list("combinations")) {
            Matcher m = ENTRY.matcher(c);
            if (!m.matches()) {
                throw new IllegalArgumentException("조합은 \"선정 id@버전 x 전략 id@버전\"(선정 없으면 none) 형식이어야 합니다: " + c);
            }
            if (!seen.add(c)) {
                throw new IllegalArgumentException("같은 조합이 두 번 있습니다: " + c);
            }
            Optional<CombinationPlan.Ref> sel = m.group(1).equals("none") ? Optional.empty()
                    : Optional.of(CombinationPlan.Ref.parse(m.group(1)));
            entries.add(new CombinationPlan.Entry(sel, CombinationPlan.Ref.parse(m.group(2))));
        }
        Map<TradingStyle, CombinationPlan.Ref> controls = new EnumMap<>(TradingStyle.class);
        for (String c : raw.list("controls")) {
            Matcher m = CONTROL.matcher(c);
            if (!m.matches()) {
                throw new IllegalArgumentException("대조군은 \"STYLE id@버전\"(STYLE은 SWING 또는 POSITION) 형식이어야 합니다: " + c);
            }
            TradingStyle style = TradingStyle.valueOf(m.group(1));
            if (controls.put(style, CombinationPlan.Ref.parse(m.group(2))) != null) {
                throw new IllegalArgumentException("같은 스타일의 대조군이 두 번 있습니다: " + style);
            }
        }
        TreeSet<String> black = new TreeSet<>();
        for (String code : raw.list("blacklist")) {
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("종목 코드 형식이 아닙니다: " + code);
            }
            black.add(code);
        }
        Optional<String> financials = Optional.ofNullable(s.get("financials-snapshot")).map(WalkForwardPlan::snapshot);
        Optional<String> disclosures = Optional.ofNullable(s.get("disclosures-snapshot")).map(WalkForwardPlan::snapshot);
        return new WalkForwardPlan(new ExperimentId(raw.required("id")), raw.required("question"), market,
                snapshot(raw.required("bars-snapshot")), financials, disclosures, Long.parseLong(raw.required("seed")),
                Money.of(Long.parseLong(raw.required("seed-capital")), market.currency()),
                Integer.parseInt(s.getOrDefault("parallelism", "1")), LocalDate.parse(raw.required("first-test-from")),
                Integer.parseInt(raw.required("train-years")), Integer.parseInt(raw.required("test-years")),
                Integer.parseInt(raw.required("windows")), Integer.parseInt(raw.required("warmup-days")),
                Integer.parseInt(raw.required("null-seeds")), entries, controls, List.copyOf(black));
    }

    private static String snapshot(String v) {
        if (!SNAPSHOT.matcher(v).matches()) {
            throw new IllegalArgumentException("스냅샷 ID 형식이 아닙니다: " + v);
        }
        return v;
    }
}
