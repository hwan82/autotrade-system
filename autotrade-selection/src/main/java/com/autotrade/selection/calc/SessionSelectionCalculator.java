package com.autotrade.selection.calc;

import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.selection.result.SessionSelectionResult;
import com.autotrade.selection.result.SelectionResult;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 장중 순위 선정 계산(ADR-012 5.2 2차-a, D-125). 순수 함수다: 같은 선정 프로필·날짜·유니버스·체결 요약이면 같은
 * {@link SessionSelectionResult}(같은 내용 해시)를 낸다. 실험실(녹화 실험·실시간 모의와 그 리플레이 확인)이 쓰고, 실전 엔진은 쓰지
 * 않는다.
 *
 * <p>입력을 모으는 쪽(실험실 {@code IntradayTracker})이 지키는 시점 규칙(미래 정보 0건): 체결 요약은 그날 정규장 체결 중 거래소 시각이
 * {@code at} 전이고 순위를 매기는 순간(거래소 시각이 {@code at} 이상인 첫 이벤트) 전에 받은 것만 담는다. 그 뒤에 도착한 {@code at}
 * 전 체결은 넣지 않는다.
 *
 * <p>순서는 일봉 계산기({@link SelectionCalculator})와 같다: 유니버스(등록 파일 boards) 종목마다 자격 조건(모두 AND)을 계산해 값과
 * 사유를 남기고, 통과 종목을 점수 순(없으면 종목 코드 순)으로 세워 {@code max-symbols}까지 고른다. 다른 점
 * <ul>
 *   <li>하드 필터가 없다(유니버스를 등록 때 고름). 구간 체결이 없는 종목은 조건마다 {@code GATE_<nn>_NO_DATA}</li>
 *   <li>구간 안에서 끝난 시세 누락(정규장 중 끝난 {@code DataGap})이 있던 종목은 값을 믿을 수 없어 {@value #SESSION_GAP}으로
 *       떨어뜨린다(누락 구간이 순위를 매기는 순간 뒤에 끝나면 그 순간에는 알 수 없으므로 반영하지 않는다)</li>
 * </ul>
 */
public final class SessionSelectionCalculator {

    /** 순위 구간에 시세 누락이 있던 종목. */
    public static final String SESSION_GAP = "SESSION_GAP";

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final MathContext WIDE = MathContext.DECIMAL128;

    private SessionSelectionCalculator() {
    }

    /**
     * @param universe 순위를 매길 종목 코드(등록 파일 boards). 블랙리스트는 여기서 빼지 않는다(후보로 바꿀 때 빼고 메우지 않음, D-124)
     * @param stats 종목 코드 → 구간 체결 요약. 체결이 없는 종목은 없다
     * @param gapped 구간에 시세 누락이 있던 종목 코드
     * @throws IllegalArgumentException 장중 순위 선정 프로필이 아니거나 카탈로그에 맞지 않을 때
     */
    public static SessionSelectionResult calculate(SelectionProfile profile, LocalDate date, Collection<String> universe,
            Map<String, SessionStats> stats, Set<String> gapped) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(date, "date");
        if (profile.refresh() != SelectionRefresh.INTRADAY_AT) {
            throw new IllegalArgumentException("장중 순위 선정(INTRADAY_AT) 프로필이 아닙니다: " + profile.key() + " = "
                    + profile.refresh());
        }
        SelectionCriteria c = SelectionCriteria.parse(profile);
        TreeSet<String> codes = new TreeSet<>(universe);
        List<Scored> passed = new ArrayList<>();
        Map<String, SelectionResult.Evaluation> evaluations = new TreeMap<>();
        for (String code : codes) {
            Eval e = evaluate(c, stats.get(code), gapped.contains(code));
            if (e.reasons.isEmpty()) {
                passed.add(new Scored(code, e));
            } else {
                evaluations.put(code, e.toEvaluation(code, false));
            }
        }
        Comparator<Scored> order = Comparator.comparing(Scored::code);
        if (c.score().isPresent()) {
            Comparator<BigDecimal> byScore = c.score().get().descending() ? Comparator.reverseOrder() : Comparator.naturalOrder();
            order = Comparator.comparing((Scored s) -> s.eval.score, byScore).thenComparing(order);
        }
        passed.sort(order);
        List<SelectionResult.Selected> selected = new ArrayList<>();
        for (Scored s : passed) {
            if (selected.size() < profile.maxSymbols()) {
                selected.add(new SelectionResult.Selected(s.code, selected.size() + 1));
                evaluations.put(s.code, s.eval.toEvaluation(s.code, true));
            } else {
                s.eval.reasons.add(SelectionCalculator.RANK_CUTOFF);
                evaluations.put(s.code, s.eval.toEvaluation(s.code, false));
            }
        }
        return new SessionSelectionResult(SelectionResult.Selection.of(profile), date, profile.at().orElseThrow(),
                codes.size(), selected, List.copyOf(evaluations.values()));
    }

    private static Eval evaluate(SelectionCriteria c, SessionStats s, boolean gap) {
        Eval e = new Eval();
        if (gap) {
            e.reasons.add(SESSION_GAP);
            return e;
        }
        for (Condition g : c.gates()) {
            BigDecimal v = value(g.metric(), s);
            if (v == null) {
                e.reasons.add(g.reasonCode() + "_" + SelectionCalculator.NO_DATA);
            } else {
                e.values.put(g.id(), v.toPlainString());
                if (!g.op().test(v, g.threshold())) {
                    e.reasons.add(g.reasonCode());
                }
            }
        }
        if (e.reasons.isEmpty() && c.score().isPresent()) {
            BigDecimal v = value(c.score().get().metric(), s);
            if (v == null) {
                e.reasons.add(SelectionCalculator.SCORE_NO_DATA);
            } else {
                e.score = v;
                e.values.put("score", v.toPlainString());
            }
        }
        return e;
    }

    /** 장중 지표 값. 구간 체결이 없으면 null. 중간 계산은 넓게, 값은 한 번만 줄인다(일봉 계산기와 같음). */
    static BigDecimal value(Metric metric, SessionStats s) {
        if (s == null) {
            return null;
        }
        BigDecimal v = switch (metric) {
            case SESSION_TURNOVER -> s.turnover();
            case SESSION_RETURN -> s.last().divide(s.first(), WIDE).subtract(BigDecimal.ONE, WIDE);
            case SESSION_RANGE -> s.high().subtract(s.low(), WIDE).divide(s.first(), WIDE);
            default -> throw new IllegalArgumentException("장중 지표가 아닙니다: " + metric.key());
        };
        return v.round(MC).stripTrailingZeros();
    }

    private record Scored(String code, Eval eval) {
    }

    private static final class Eval {
        final List<String> reasons = new ArrayList<>();
        final Map<String, String> values = new TreeMap<>();
        BigDecimal score;

        SelectionResult.Evaluation toEvaluation(String code, boolean selected) {
            return new SelectionResult.Evaluation(code, selected, selected ? List.of() : reasons, new TreeMap<>(values),
                    false);
        }
    }
}
