package com.autotrade.selection.calc;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.selection.profile.SelectionProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 선정 프로필 파라미터를 해석한 선정 조건(STR-10·11·15·16, 선정 조건 카탈로그). 예약 키({@code hypothesis}, {@code refresh},
 * {@code max-symbols})는 {@link SelectionProfile}이 검사하고, 여기서는 나머지 키를 모두 해석한다. 모르는 키는 거부한다(오타가
 * 해시에만 섞여 결과 추적이 어긋나는 것을 막음, 전략 프로필과 같은 원칙). 예외: {@code notes.*}.
 *
 * <pre>
 * filter.kinds: "STOCK"                 상품 유형(쉼표 목록, InstrumentKind)
 * filter.min-price: "1000"              직전 거래일 종가(원가격) ≥
 * filter.min-bar-days: "60"             직전 N거래일 일봉이 모두 있음(상장 기간·결측)
 * gate.01.metric: "volume-ratio"        지표(Metric)
 * gate.01.op: "GE"                      GE, GT, LE, LT
 * gate.01.value: "3.0"                  기준값(소수, 금액은 원)
 * gate.01.window: "20"                  거래일 기간(WINDOW 지표)
 * gate.01.years: "3"                    사업연도 기간(YEARS 지표)
 * score.metric / score.window / score.years / score.order(DESC|ASC)   선택: 순위 지표
 * </pre>
 *
 * @param kinds 비어 있으면 상품 유형 필터 없음
 * @param minPrice 없으면 null
 * @param minBarDays 0이면 없음
 * @param gates {@code gate.<nn>} 순번 오름차순
 */
public record SelectionCriteria(
        Set<InstrumentKind> kinds, BigDecimal minPrice, int minBarDays, List<Condition> gates, Optional<Score> score) {

    private static final Pattern GATE_KEY = Pattern.compile("gate\\.(\\d{2})\\.([a-z-]+)");
    private static final Set<String> RESERVED =
            Set.of(SelectionProfile.HYPOTHESIS, SelectionProfile.REFRESH, SelectionProfile.MAX_SYMBOLS);
    /** 기간 상한. 일봉 창은 1년(250거래일), 사업연도는 OpenDART 수록(2015년~) 안에서 10년 `[가정]`. */
    static final int MAX_WINDOW = 250;
    static final int MAX_YEARS = 10;

    public SelectionCriteria {
        kinds = Set.copyOf(kinds);
        gates = List.copyOf(gates);
        Objects.requireNonNull(score, "score");
    }

    /**
     * 순위 지표.
     *
     * @param descending 큰 값이 앞(DESC)
     */
    public record Score(Metric metric, int period, boolean descending) {
    }

    /** 이 선정이 읽는 입력 종류. */
    public Set<Metric.Kind> inputKinds() {
        Set<Metric.Kind> out = EnumSet.noneOf(Metric.Kind.class);
        out.add(Metric.Kind.BARS);
        gates.forEach(g -> out.add(g.metric().kind()));
        score.ifPresent(s -> out.add(s.metric().kind()));
        return out;
    }

    /** 계산에 필요한 직전 거래일 수(일봉). */
    int lookbackDays() {
        int n = Math.max(1, minBarDays);
        for (Condition g : gates) {
            n = Math.max(n, lookback(g.metric(), g.period()));
        }
        if (score.isPresent()) {
            n = Math.max(n, lookback(score.get().metric(), score.get().period()));
        }
        return n;
    }

    private static int lookback(Metric m, int period) {
        return switch (m) {
            case VOLUME_RATIO -> 2 * period;
            case RETURN -> period + 1;
            case AVG_TURNOVER -> period;
            case DAY_RANGE -> 2;
            case VOLUME_SPIKE -> period + 1;
            default -> 1;
        };
    }

    /**
     * @throws IllegalArgumentException 모르는 키, 형식·범위 위반, 재무 지표를 KRX 밖에서 씀
     */
    public static SelectionCriteria parse(SelectionProfile profile) {
        Map<String, String> p = profile.parameters();
        Set<String> used = new HashSet<>(RESERVED);
        Set<InstrumentKind> kinds = EnumSet.noneOf(InstrumentKind.class);
        if (p.containsKey("filter.kinds")) {
            used.add("filter.kinds");
            for (String k : p.get("filter.kinds").split(",")) {
                try {
                    kinds.add(InstrumentKind.valueOf(k.trim()));
                } catch (IllegalArgumentException e) {
                    throw fail(profile, "filter.kinds", "모르는 상품 유형입니다: " + k);
                }
            }
        }
        BigDecimal minPrice = null;
        if (p.containsKey("filter.min-price")) {
            used.add("filter.min-price");
            minPrice = decimal(profile, "filter.min-price");
            if (minPrice.signum() < 0) {
                throw fail(profile, "filter.min-price", "0 이상이어야 합니다");
            }
        }
        int minBarDays = 0;
        if (p.containsKey("filter.min-bar-days")) {
            used.add("filter.min-bar-days");
            minBarDays = count(profile, "filter.min-bar-days", 1, MAX_WINDOW);
        }
        SortedMap<String, Map<String, String>> gateKeys = new TreeMap<>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            Matcher m = GATE_KEY.matcher(e.getKey());
            if (m.matches()) {
                gateKeys.computeIfAbsent(m.group(1), x -> new TreeMap<>()).put(m.group(2), e.getValue());
                used.add(e.getKey());
            }
        }
        List<Condition> gates = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : gateKeys.entrySet()) {
            String id = "gate." + e.getKey();
            if (e.getKey().equals("00")) {
                throw fail(profile, id, "순번은 01부터입니다");
            }
            gates.add(condition(profile, id, e.getValue()));
        }
        Optional<Score> score = Optional.empty();
        if (p.containsKey("score.metric")) {
            Map<String, String> s = new TreeMap<>();
            for (String k : List.of("metric", "window", "years", "order")) {
                if (p.containsKey("score." + k)) {
                    s.put(k, p.get("score." + k));
                    used.add("score." + k);
                }
            }
            Metric metric = metric(profile, "score", s.get("metric"));
            int period = period(profile, "score", metric, s);
            String order = s.get("order");
            if (!"DESC".equals(order) && !"ASC".equals(order)) {
                throw fail(profile, "score.order", "DESC 또는 ASC가 필요합니다");
            }
            requireMarket(profile, "score", metric);
            score = Optional.of(new Score(metric, period, order.equals("DESC")));
        }
        for (String k : p.keySet()) {
            if (!used.contains(k) && !k.startsWith("notes.")) {
                throw fail(profile, k, "선정 조건 카탈로그에 없는 키입니다");
            }
        }
        if (gates.isEmpty() && kinds.isEmpty() && minPrice == null && minBarDays == 0) {
            throw fail(profile, "gate", "필터나 자격 조건이 하나 이상 필요합니다");
        }
        return new SelectionCriteria(kinds, minPrice, minBarDays, gates, score);
    }

    private static Condition condition(SelectionProfile profile, String id, Map<String, String> keys) {
        for (String k : keys.keySet()) {
            if (!Set.of("metric", "op", "value", "window", "years").contains(k)) {
                throw fail(profile, id + "." + k, "자격 조건에 없는 키입니다");
            }
        }
        Metric metric = metric(profile, id, keys.get("metric"));
        String op = keys.get("op");
        CompareOp compare;
        try {
            compare = CompareOp.valueOf(Objects.requireNonNull(op, "op"));
        } catch (RuntimeException e) {
            throw fail(profile, id + ".op", "GE, GT, LE, LT 중 하나가 필요합니다");
        }
        if (keys.get("value") == null) {
            throw fail(profile, id + ".value", "기준값이 필요합니다");
        }
        BigDecimal value = decimal(profile, id + ".value", keys.get("value"));
        requireMarket(profile, id, metric);
        return new Condition(id, metric, compare, value, period(profile, id, metric, keys));
    }

    private static Metric metric(SelectionProfile profile, String prefix, String value) {
        if (value == null) {
            throw fail(profile, prefix + ".metric", "지표가 필요합니다");
        }
        try {
            return Metric.fromKey(value);
        } catch (IllegalArgumentException e) {
            throw fail(profile, prefix + ".metric", e.getMessage());
        }
    }

    private static int period(SelectionProfile profile, String prefix, Metric metric, Map<String, String> keys) {
        String window = keys.get("window");
        String years = keys.get("years");
        return switch (metric.param()) {
            case NONE -> {
                if (window != null || years != null) {
                    throw fail(profile, prefix, metric.key() + "는 기간을 받지 않습니다");
                }
                yield 0;
            }
            case WINDOW -> {
                if (years != null) {
                    throw fail(profile, prefix + ".years", metric.key() + "는 window(거래일)를 씁니다");
                }
                yield parseCount(profile, prefix + ".window", window, 1, MAX_WINDOW);
            }
            case YEARS -> {
                if (window != null) {
                    throw fail(profile, prefix + ".window", metric.key() + "는 years(사업연도)를 씁니다");
                }
                yield parseCount(profile, prefix + ".years", years, 1, MAX_YEARS);
            }
        };
    }

    /** 재무 지표는 OpenDART(국내) 출처라 KRX 선정에만 쓴다(D-041 5장). */
    private static void requireMarket(SelectionProfile profile, String prefix, Metric metric) {
        if (metric.kind() == Metric.Kind.FINANCIAL && profile.market() != Market.KRX) {
            throw fail(profile, prefix + ".metric", "재무 지표는 KRX 선정에만 쓸 수 있습니다(미국 재무 출처 없음)");
        }
    }

    private static int count(SelectionProfile profile, String key, int min, int max) {
        return parseCount(profile, key, profile.parameters().get(key), min, max);
    }

    private static int parseCount(SelectionProfile profile, String key, String v, int min, int max) {
        if (v == null || !v.matches("[0-9]{1,6}")) {
            throw fail(profile, key, "정수가 필요합니다: " + v);
        }
        int n = Integer.parseInt(v);
        if (n < min || n > max) {
            throw fail(profile, key, min + "~" + max + " 범위여야 합니다: " + n);
        }
        return n;
    }

    private static BigDecimal decimal(SelectionProfile profile, String key) {
        return decimal(profile, key, profile.parameters().get(key));
    }

    private static BigDecimal decimal(SelectionProfile profile, String key, String v) {
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw fail(profile, key, "수가 아닙니다: " + v);
        }
    }

    private static IllegalArgumentException fail(SelectionProfile profile, String key, String message) {
        return new IllegalArgumentException(profile.key() + " " + key + ": " + message);
    }
}
