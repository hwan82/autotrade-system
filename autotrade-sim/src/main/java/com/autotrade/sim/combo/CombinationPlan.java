package com.autotrade.sim.combo;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.sim.ExperimentId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 조합 실험 등록 파일 {@code experiments/<실험ID>/experiment.yaml}(사전 등록, 조합 실험 절차 1장). 실행 전에 커밋하고 결과를
 * 본 뒤 고치지 않는다. 바꾸려면 새 실험 ID를 쓴다.
 *
 * <pre>
 * id: "EXP-20261012-CMB01"
 * question: "(나) 선정이 같은 스윙 지표의 선정 없음보다 비용 차감 후 나은가"
 * market: "KRX"
 * from: "2025-01-02"
 * to: "2026-10-08"
 * seed: "20261012"
 * seed-capital: "10000000"
 * bars-snapshot: "krx-daily-2026-10-08-1a2b3c4d"
 * financials-snapshot: "dart-financials-2026-10-08-5e6f7a8b"   # 재무 조건 선정이 없으면 생략
 * benchmark: "none"                                            # 또는 종목 코드
 * parallelism: "4"
 * combinations:
 *   - "kr-vol-surge-quiet@1 x kr-ma-trend@1"
 *   - "none x kr-ma-trend@1"
 * blacklist:                                                   # 선택. 실험 시작 시점 제외 종목(RSK-12)
 *   - "005930"
 * </pre>
 *
 * <p>프로필 파일과 같은 이유로 YAML 전체가 아니라 이 모양만 읽고, 벗어나면 줄 번호와 함께 거절한다: 최상위 {@code 키: 값},
 * {@code combinations:}·{@code blacklist:} 아래 공백 2칸 + {@code - 값} 목록. 값은 큰따옴표로 감쌀 수 있고, 따옴표 없는 값은
 * 공백 뒤 {@code #}부터 주석이다. 같은 키 두 번, 모르는 키, 같은 조합 두 번은 거절한다.
 *
 * @param combinations 조합 {@code 선정 id@버전 x 전략 id@버전}(선정 없으면 {@code none}). 이 순서로 계좌 번호를 매긴다
 * @param benchmark 매수 후 보유 비교 종목 코드. 없으면 빈 값
 * @param blacklist 제외 종목 코드(코드 순, 중복 없음)
 */
public record CombinationPlan(
        ExperimentId id,
        String question,
        Market market,
        LocalDate from,
        LocalDate to,
        long seed,
        Money seedCapital,
        String barsSnapshot,
        Optional<String> financialsSnapshot,
        Optional<String> benchmark,
        int parallelism,
        List<Entry> combinations,
        List<String> blacklist) {

    private static final Set<String> SCALARS = Set.of("id", "question", "market", "from", "to", "seed", "seed-capital",
            "bars-snapshot", "financials-snapshot", "benchmark", "parallelism");
    private static final Set<String> LISTS = Set.of("combinations", "blacklist");
    private static final Pattern TOP = Pattern.compile("([a-z][a-z-]*):(.*)");
    private static final Pattern ITEM = Pattern.compile(" {2}- (.*)");
    private static final Pattern REF = Pattern.compile("([a-z0-9][a-z0-9-]*)@([1-9][0-9]*)");
    private static final Pattern ENTRY = Pattern.compile("(none|[a-z0-9][a-z0-9-]*@[1-9][0-9]*) x ([a-z0-9][a-z0-9-]*@[1-9][0-9]*)");
    private static final Pattern SNAPSHOT = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{6}");

    /** 조합 하나. 선정이 없으면 {@code selection}이 빈 값. */
    public record Entry(Optional<Ref> selection, Ref strategy) {

        public Entry {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(strategy, "strategy");
        }

        public String text() {
            return selection.map(Ref::text).orElse("none") + " x " + strategy.text();
        }
    }

    /** 프로필 {@code id@버전}. */
    public record Ref(String id, int version) {

        public Ref {
            Objects.requireNonNull(id, "id");
        }

        static Ref parse(String text) {
            Matcher m = REF.matcher(text);
            if (!m.matches()) {
                throw new IllegalArgumentException("프로필은 id@버전 형식이어야 합니다: " + text);
            }
            return new Ref(m.group(1), Integer.parseInt(m.group(2)));
        }

        public String text() {
            return id + "@" + version;
        }
    }

    public CombinationPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(seedCapital, "seedCapital");
        Objects.requireNonNull(barsSnapshot, "barsSnapshot");
        Objects.requireNonNull(financialsSnapshot, "financialsSnapshot");
        Objects.requireNonNull(benchmark, "benchmark");
        combinations = List.copyOf(combinations);
        blacklist = List.copyOf(blacklist);
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("종료일이 시작일보다 앞섭니다: " + from + " ~ " + to);
        }
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("parallelism은 1~64여야 합니다: " + parallelism);
        }
        if (combinations.isEmpty()) {
            throw new IllegalArgumentException("조합이 하나 이상 필요합니다");
        }
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일 경로)
     * @throws IllegalArgumentException 형식이 틀렸거나 필수 키가 없을 때. 메시지에 줄 번호가 있다
     */
    public static CombinationPlan parse(String text, String source) {
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        String currentList = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            int no = i + 1;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                if (currentList == null) {
                    throw fail(source, no, "목록 항목이 목록 키 아래에 있지 않습니다");
                }
                lists.get(currentList).add(value(item.group(1), source, no));
                continue;
            }
            Matcher top = TOP.matcher(line);
            if (!top.matches()) {
                throw fail(source, no, "읽을 수 없는 줄입니다(최상위 키: 값, 또는 공백 2칸 + '- 값')");
            }
            String key = top.group(1);
            if (scalars.containsKey(key) || lists.containsKey(key)) {
                throw fail(source, no, "같은 키가 두 번 있습니다: " + key);
            }
            String rest = value(top.group(2).strip(), source, no);
            if (LISTS.contains(key)) {
                if (!rest.isEmpty()) {
                    throw fail(source, no, key + "는 다음 줄부터 '  - 값' 목록으로 씁니다");
                }
                lists.put(key, new ArrayList<>());
                currentList = key;
            } else if (SCALARS.contains(key)) {
                scalars.put(key, rest);
                currentList = null;
            } else {
                throw fail(source, no, "모르는 키입니다: " + key);
            }
        }
        try {
            return build(scalars, lists);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static CombinationPlan build(Map<String, String> s, Map<String, List<String>> l) {
        ExperimentId id = new ExperimentId(required(s, "id"));
        String barsSnapshot = snapshot(required(s, "bars-snapshot"));
        Optional<String> financials = Optional.ofNullable(s.get("financials-snapshot")).map(CombinationPlan::snapshot);
        String bench = s.getOrDefault("benchmark", "none");
        Optional<String> benchmark = bench.equals("none") ? Optional.empty() : Optional.of(code(bench));
        Market market = Market.valueOf(required(s, "market"));
        long capital = Long.parseLong(required(s, "seed-capital"));
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String c : l.getOrDefault("combinations", List.of())) {
            Matcher m = ENTRY.matcher(c);
            if (!m.matches()) {
                throw new IllegalArgumentException("조합은 \"선정 id@버전 x 전략 id@버전\"(선정 없으면 none) 형식이어야 합니다: " + c);
            }
            if (!seen.add(c)) {
                throw new IllegalArgumentException("같은 조합이 두 번 있습니다: " + c);
            }
            Optional<Ref> sel = m.group(1).equals("none") ? Optional.empty() : Optional.of(Ref.parse(m.group(1)));
            entries.add(new Entry(sel, Ref.parse(m.group(2))));
        }
        java.util.TreeSet<String> black = new java.util.TreeSet<>();
        for (String code : l.getOrDefault("blacklist", List.of())) {
            black.add(code(code));
        }
        return new CombinationPlan(id, required(s, "question"), market, LocalDate.parse(required(s, "from")),
                LocalDate.parse(required(s, "to")), Long.parseLong(required(s, "seed")),
                Money.of(capital, market.currency()), barsSnapshot, financials, benchmark,
                Integer.parseInt(s.getOrDefault("parallelism", "1")), entries, List.copyOf(black));
    }

    private static String required(Map<String, String> s, String key) {
        String v = s.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("필수 키가 없습니다: " + key);
        }
        return v;
    }

    private static String snapshot(String v) {
        if (!SNAPSHOT.matcher(v).matches()) {
            throw new IllegalArgumentException("스냅샷 ID 형식이 아닙니다: " + v);
        }
        return v;
    }

    private static String code(String v) {
        if (!CODE.matcher(v).matches()) {
            throw new IllegalArgumentException("종목 코드 형식이 아닙니다: " + v);
        }
        return v;
    }

    /** 큰따옴표 값(\" 이스케이프만) 또는 따옴표 없는 값(공백 뒤 # 주석 제거). */
    private static String value(String raw, String source, int line) {
        String v = raw.strip();
        if (v.startsWith("#")) {
            return "";
        }
        if (v.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length() && (v.charAt(i + 1) == '"' || v.charAt(i + 1) == '\\')) {
                    sb.append(v.charAt(++i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            if (i >= v.length()) {
                throw fail(source, line, "닫는 큰따옴표가 없습니다");
            }
            String after = v.substring(i + 1).strip();
            if (!after.isEmpty() && !after.startsWith("#")) {
                throw fail(source, line, "큰따옴표 값 뒤에 다른 글자가 있습니다");
            }
            return sb.toString();
        }
        int hash = v.indexOf(" #");
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
    }

    private static IllegalArgumentException fail(String source, int line, String message) {
        return new IllegalArgumentException(source + ":" + line + ": " + message);
    }
}
