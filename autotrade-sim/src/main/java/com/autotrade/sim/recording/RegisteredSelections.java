package com.autotrade.sim.recording;

import com.autotrade.sim.run.RegistrationYaml;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 녹화 실험·실시간 모의 등록 파일의 선정 조합 계좌와 선정 입력(ADR-012 3.5, D-124). 두 등록 파일이 같은 키를 쓴다.
 *
 * <pre>
 * selections:                       # 선정 x 전략 조합 계좌. profiles 계좌 뒤에 이 순서로 계좌 번호를 매긴다
 *   - "kr-intraday-hot@1 x kr-volume-surge@1"
 * selection-input:
 *   daily: "latest"                 # 실시간만 latest. 녹화 실험은 스냅샷 ID
 *   financials: "latest"            # 재무 조건이 있는 선정만. 없으면 생략
 *   disclosures: "latest"           # 공시 조건(disclosure-count)이 있는 장전 선정이 있을 때만(F9, ADR-014 6장). 없으면 생략
 * </pre>
 *
 * <p>조합은 조합 실험 등록 파일({@code CombinationPlan})과 같은 {@code "선정 x 전략"} 한 줄 모양이다(좁은 YAML 모양을 지키려고
 * ADR-012 3.5 예시의 겹친 목록 대신 이것을 쓴다). 공시 입력({@code disclosures})은 {@code daily}와 같은 값 규칙이다(실시간만
 * {@value #LATEST}, 녹화 실험은 스냅샷 ID). 공시 조건이 있는 장전 선정이 있으면 꼭 있어야 하고, 없으면 쓰면 안 된다
 * ({@link #requireInputs}, ADR-014 6장). 공시 입력이 없는 등록 파일은 지금까지와 결과가 같다.
 *
 * @param entries 선정 x 전략 조합(파일 순서). 비어 있으면 선정 계좌가 없다(지금까지와 같음)
 * @param daily 일봉 스냅샷 ID 또는 {@value #LATEST}. 장전 선정 계좌가 있으면 필수({@link #requireInputs})
 * @param financials 재무 스냅샷 ID 또는 {@value #LATEST}
 * @param disclosures 공시 스냅샷 ID 또는 {@value #LATEST}(D-123, ADR-014 F9)
 */
public record RegisteredSelections(List<Entry> entries, Optional<String> daily, Optional<String> financials,
        Optional<String> disclosures) {

    /** 실시간 모의만 받는 "준비 시점에 가장 최근 스냅샷" 표시. */
    public static final String LATEST = "latest";
    public static final String LIST_KEY = "selections";
    public static final String INPUT_KEY = "selection-input";
    /** {@code selection-input} 하위 키. */
    public static final Set<String> INPUT_SUBKEYS = Set.of("daily", "financials", "disclosures");

    private static final Pattern ENTRY = Pattern.compile(
            "([a-z0-9][a-z0-9-]*)@([1-9][0-9]*) x ([a-z0-9][a-z0-9-]*)@([1-9][0-9]*)");
    private static final Pattern SNAPSHOT = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    /** 선정이 없는 등록 파일. */
    public static final RegisteredSelections NONE =
            new RegisteredSelections(List.of(), Optional.empty(), Optional.empty(), Optional.empty());

    /** 조합 하나. */
    public record Entry(RecordingPlan.ProfileRef selection, RecordingPlan.ProfileRef strategy) {

        public Entry {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(strategy, "strategy");
        }

        public String text() {
            return selection.text() + " x " + strategy.text();
        }
    }

    /** 공시 입력이 없는 등록(F9 전 모양). */
    public RegisteredSelections(List<Entry> entries, Optional<String> daily, Optional<String> financials) {
        this(entries, daily, financials, Optional.empty());
    }

    public RegisteredSelections {
        entries = List.copyOf(entries);
        Objects.requireNonNull(daily, "daily");
        Objects.requireNonNull(financials, "financials");
        Objects.requireNonNull(disclosures, "disclosures");
        // 일봉 스냅샷이 필요한지는 선정 프로필을 읽은 뒤 안다(장중 순위 선정만 있으면 필요 없음, D-127): requireInputs
        if (entries.isEmpty() && (daily.isPresent() || financials.isPresent() || disclosures.isPresent())) {
            throw new IllegalArgumentException("selection-input은 selections가 있을 때만 씁니다");
        }
    }

    /**
     * 읽은 선정 프로필로 입력을 확인한다. 장전 선정({@code PRE_OPEN_DAILY})이 있으면 {@code selection-input.daily}가 필요하고,
     * 장중 순위 선정({@code INTRADAY_AT})만 있으면 {@code selection-input}을 쓰지 않는다(입력 기록만 씀, D-127).
     *
     * <p>공시 입력은 장전 선정 가운데 공시 지표({@code Metric.Kind.DISCLOSURE}) 조건이 있는 것이 하나라도 있으면 필요하고, 하나도
     * 없으면 쓰면 안 된다(ADR-014 6장. 일봉 사전 검사 {@code SelectionEdgeStudy}의 일치 검사와 같은 판단). 쓰지 않는 입력을 적어
     * 두면 등록 파일만 보고 그 스냅샷이 결과에 들어갔다고 오해하게 된다.
     *
     * @param dailySelections 장전 선정이 하나 이상 있는지
     * @param disclosureSelections 공시 조건이 있는 장전 선정이 하나 이상 있는지
     */
    public void requireInputs(boolean dailySelections, boolean disclosureSelections) {
        if (dailySelections && daily.isEmpty()) {
            throw new IllegalArgumentException("장전 선정(PRE_OPEN_DAILY)이 있으면 selection-input.daily(일봉 스냅샷)가 필요합니다");
        }
        if (!dailySelections && (daily.isPresent() || financials.isPresent() || disclosures.isPresent())) {
            throw new IllegalArgumentException("장중 순위 선정(INTRADAY_AT)만 있는 등록은 selection-input을 쓰지 않습니다(입력 기록만 씀)");
        }
        if (disclosureSelections && disclosures.isEmpty()) {
            throw new IllegalArgumentException("공시 지표(disclosure-count)를 쓰는 장전 선정이 있으면 selection-input.disclosures"
                    + "(공시 스냅샷)가 필요합니다(ADR-014 6장)");
        }
        if (!disclosureSelections && disclosures.isPresent()) {
            throw new IllegalArgumentException("공시 지표를 쓰는 장전 선정이 없는데 selection-input.disclosures가 있습니다. 쓰지 않는 "
                    + "입력은 지우세요(ADR-014 6장)");
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 등록 파일에서 읽는다. @param allowLatest 실시간 모의면 true(녹화 실험은 스냅샷 ID를 고정해야 해서 false) */
    public static RegisteredSelections read(RegistrationYaml y, boolean allowLatest) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String text : y.list(LIST_KEY)) {
            Matcher m = ENTRY.matcher(text);
            if (!m.matches()) {
                throw new IllegalArgumentException("선정 조합은 \"선정 id@버전 x 전략 id@버전\" 형식이어야 합니다: " + text);
            }
            if (!seen.add(text)) {
                throw new IllegalArgumentException("같은 선정 조합이 두 번 있습니다: " + text);
            }
            entries.add(new Entry(new RecordingPlan.ProfileRef(m.group(1), Integer.parseInt(m.group(2))),
                    new RecordingPlan.ProfileRef(m.group(3), Integer.parseInt(m.group(4)))));
        }
        Map<String, String> input = y.map(INPUT_KEY);
        return new RegisteredSelections(entries, snapshot(input.get("daily"), "daily", allowLatest),
                snapshot(input.get("financials"), "financials", allowLatest),
                snapshot(input.get("disclosures"), "disclosures", allowLatest));
    }

    private static Optional<String> snapshot(String v, String key, boolean allowLatest) {
        if (v == null) {
            return Optional.empty();
        }
        if (v.equals(LATEST)) {
            if (!allowLatest) {
                throw new IllegalArgumentException("녹화 실험은 selection-input." + key + "에 latest를 쓸 수 없습니다. 스냅샷 ID를 "
                        + "적어 입력을 고정하세요(ADR-012 3.5)");
            }
            return Optional.of(v);
        }
        if (!SNAPSHOT.matcher(v).matches()) {
            throw new IllegalArgumentException("selection-input." + key + "가 스냅샷 ID 형식이 아닙니다: " + v);
        }
        return Optional.of(v);
    }
}
