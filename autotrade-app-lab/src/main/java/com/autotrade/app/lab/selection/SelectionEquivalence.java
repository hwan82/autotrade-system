package com.autotrade.app.lab.selection;

import com.autotrade.selection.calc.Condition;
import com.autotrade.selection.calc.SelectionCriteria;
import com.autotrade.selection.profile.SelectionProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 조건 편집기에서 저장하기 전에 "원본과 선정 결과가 같은 새 버전"인지 알려 준다(PR #49 후속, D-074). 편집기는 화면 순서대로
 * {@code gate.01…}을 다시 매기고 상품 유형을 체크 순서로 다시 쓰므로, 의미는 같은데 파라미터 글자가 달라 새 선정 해시가 생길 수
 * 있다. 같은 선정이 다른 해시로 나뉘면 교차표에서 따로 집계된다.
 *
 * <p>"선정 결과가 같다"는 선정 계산({@code SelectionCalculator})이 고르는 종목과 순위가 같다는 뜻이다: 갱신 주기, 최대 종목 수,
 * 상품 유형 집합, 최소 가격(값 비교), 최소 일봉 거래일, 자격 조건 집합(지표·연산·기준값(값 비교)·기간, 순서 무관: 모든 조건을
 * 평가해 AND로 묶음), 순위 지표·기간·방향. 가설 문구와 {@code notes.*}는 계산에 들어가지 않는다. 자격 조건 순서가 바뀌면
 * 탈락 사유 코드({@code GATE_nn})의 번호만 달라진다.
 */
public final class SelectionEquivalence {

    private SelectionEquivalence() {
    }

    /**
     * @param identical 파라미터가 글자까지 같음(저장하면 거절된다)
     * @param sameSelection 선정 결과(고르는 종목·순위)가 원본과 같음. 후보를 해석할 수 없으면 false
     * @param differences 선정 결과에 영향이 없는 차이(화면 문구). {@code sameSelection}일 때만 채운다
     * @param problem 후보를 해석할 수 없는 이유(저장할 때 같은 이유로 거절된다). 해석되면 null
     */
    public record Result(boolean identical, boolean sameSelection, List<String> differences, String problem) {

        public Result {
            differences = List.copyOf(differences);
        }
    }

    public static Result compare(SelectionProfile base, Map<String, String> candidateParameters) {
        Objects.requireNonNull(base, "base");
        TreeMap<String, String> params = new TreeMap<>(candidateParameters);
        if (params.equals(base.parameters())) {
            return new Result(true, true, List.of(), null);
        }
        SelectionProfile candidate;
        SelectionCriteria a;
        SelectionCriteria b;
        try {
            candidate = new SelectionProfile(base.id(), base.version(), base.market(), base.style(), params);
            b = SelectionCriteria.parse(candidate);
            a = SelectionCriteria.parse(base);
        } catch (IllegalArgumentException e) {
            return new Result(false, false, List.of(), e.getMessage());
        }
        boolean same = base.refresh() == candidate.refresh()
                && base.maxSymbols() == candidate.maxSymbols()
                && a.kinds().equals(b.kinds())
                && sameNumber(a.minPrice(), b.minPrice())
                && a.minBarDays() == b.minBarDays()
                && gateKeys(a).stream().sorted().toList().equals(gateKeys(b).stream().sorted().toList())
                && scoreKey(a.score()).equals(scoreKey(b.score()));
        if (!same) {
            return new Result(false, false, List.of(), null);
        }
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(base.parameters().get(SelectionProfile.HYPOTHESIS), params.get(SelectionProfile.HYPOTHESIS))) {
            diffs.add("가설 문구");
        }
        if (!notes(base.parameters()).equals(notes(params))) {
            diffs.add("notes.* 메모");
        }
        if (!gateKeys(a).equals(gateKeys(b))) {
            diffs.add("자격 조건 순서(탈락 사유 코드 GATE_nn 번호가 바뀜)");
        }
        if (diffs.isEmpty()) {
            diffs.add("값 표기(예: 3.0과 3, 상품 유형 순서)");
        }
        return new Result(false, true, diffs, null);
    }

    private static List<String> gateKeys(SelectionCriteria c) {
        List<String> out = new ArrayList<>();
        for (Condition g : c.gates()) {
            out.add(g.metric() + "|" + g.op() + "|" + plain(g.threshold()) + "|" + g.period());
        }
        return out;
    }

    private static String scoreKey(Optional<SelectionCriteria.Score> s) {
        return s.map(x -> x.metric() + "|" + x.period() + "|" + x.descending()).orElse("");
    }

    private static boolean sameNumber(BigDecimal x, BigDecimal y) {
        return x == null ? y == null : y != null && x.compareTo(y) == 0;
    }

    private static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static Map<String, String> notes(Map<String, String> p) {
        TreeMap<String, String> out = new TreeMap<>();
        p.forEach((k, v) -> {
            if (k.startsWith("notes.")) {
                out.put(k, v);
            }
        });
        return out;
    }
}
