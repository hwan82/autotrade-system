package com.autotrade.app.lab.experiment;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.symbols.SymbolNames;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.strategy.StrategyProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 조합 실험 화면 응답 모양(UI-02 교차표, UI-03·08 선정 결과 조회, ADR-007, ADR-005 5장). 값은 결과 기록에서 읽기만 하고
 * 선정을 다시 계산하지 않는다(조합 실험 절차 1장 7번). 차이 값은 서버가 {@link BigDecimal}로 빼서 문자열로 준다.
 */
public final class SelectionViews {

    private SelectionViews() {
    }

    // ---- 교차표 ----

    /** 교차표가 받는 지표 이름과 값. 이름은 리더보드 {@code metrics} 칸 이름과 같다. */
    static final Map<String, Function<RunMetrics, BigDecimal>> CROSS_METRICS;

    static {
        LinkedHashMap<String, Function<RunMetrics, BigDecimal>> m = new LinkedHashMap<>();
        m.put("totalReturn", RunMetrics::totalReturn);
        m.put("excessReturn", RunMetrics::excessReturn);
        m.put("cagr", RunMetrics::cagr);
        m.put("maxDrawdown", RunMetrics::maxDrawdown);
        m.put("sharpe", RunMetrics::sharpe);
        m.put("profitFactor", RunMetrics::profitFactor);
        m.put("winRate", RunMetrics::winRate);
        m.put("avgHoldingDays", RunMetrics::avgHoldingDays);
        m.put("turnover", RunMetrics::turnover);
        m.put("costShare", RunMetrics::costShare);
        CROSS_METRICS = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 선정 × 매매지표 교차표(UI-02, 조합 실험 절차 1장 4번). 행은 선정(선정 없음 행 포함), 열은 전략 프로필 버전이고 칸 하나가
     * 계좌 하나다. 같은 실험의 조합은 중복될 수 없어(ExperimentSpec) 칸은 많아야 하나다.
     *
     * <p>대조군 두 축(조합 실험 절차 1장 3번)
     * <ul>
     *   <li>{@code deltaVsNoSelection}: 같은 열(같은 매매지표)의 "선정 없음" 칸 대비 차이. 선정 효과.</li>
     *   <li>{@code deltaVsRowControl}: 같은 행(같은 선정)의 대조 열({@code rowControl}, 보통 무작위 진입 프로필) 칸 대비
     *       차이. 매매지표 효과.</li>
     * </ul>
     * 차이는 {@code 칸 값 − 대조 값}이다. 어느 쪽이든 값이 없으면(실패 Run, 정의 안 되는 지표) null이다.
     *
     * @param rowControl 행 대조 열의 키({@code id@version}). 고르지 않았으면 null
     */
    public record CrossTable(
            String metric, List<String> metrics, List<Axis> rows, List<Axis> columns, String rowControl,
            List<Cell> cells) {

        static CrossTable of(List<RunReport> runs, String metric, String rowControl) {
            Function<RunMetrics, BigDecimal> get = CROSS_METRICS.get(metric);
            if (get == null) {
                throw new IllegalArgumentException("교차표 지표가 아닙니다: " + metric + ". 지원: " + CROSS_METRICS.keySet());
            }
            LinkedHashMap<String, Axis> rows = new LinkedHashMap<>();
            LinkedHashMap<String, Axis> cols = new LinkedHashMap<>();
            Map<String, RunReport> byCell = new HashMap<>();
            Axis none = null;
            for (RunReport r : runs) {
                Combination c = r.combination();
                Axis row = c.selection().map(Axis::of).orElseGet(Axis::noSelection);
                if (!c.hasSelection()) {
                    none = row;
                } else {
                    rows.putIfAbsent(row.key(), row);
                }
                Axis col = Axis.of(c.profile());
                cols.putIfAbsent(col.key(), col);
                byCell.put(row.key() + "|" + col.key(), r);
            }
            if (rowControl != null && !cols.containsKey(rowControl)) {
                throw new IllegalArgumentException("이 실험에 없는 전략 프로필입니다: " + rowControl);
            }
            List<Axis> rowList = new ArrayList<>(rows.values());
            if (none != null) {
                rowList.add(none); // 선정 없음(대조군) 행은 맨 아래
            }
            List<Cell> cells = new ArrayList<>();
            for (Axis row : rowList) {
                for (Axis col : cols.values()) {
                    RunReport r = byCell.get(row.key() + "|" + col.key());
                    if (r == null) {
                        continue;
                    }
                    BigDecimal value = value(r, get);
                    BigDecimal noSel = row.none() ? null : value(byCell.get(Combination.NONE + "|" + col.key()), get);
                    BigDecimal rowCtl = rowControl == null || rowControl.equals(col.key()) ? null
                            : value(byCell.get(row.key() + "|" + rowControl), get);
                    cells.add(new Cell(row.key(), col.key(), r.summary().runId(), r.combination().combinationHash(),
                            r.summary().status().name(), plain(value), diff(value, noSel), diff(value, rowCtl)));
                }
            }
            return new CrossTable(metric, List.copyOf(CROSS_METRICS.keySet()), rowList, List.copyOf(cols.values()),
                    rowControl, cells);
        }

        private static BigDecimal value(RunReport r, Function<RunMetrics, BigDecimal> get) {
            if (r == null || r.summary().status() != RunStatus.COMPLETED || r.summary().metrics() == null) {
                return null;
            }
            return get.apply(r.summary().metrics());
        }

        private static String diff(BigDecimal a, BigDecimal b) {
            return a == null || b == null ? null : a.subtract(b).toPlainString();
        }

        private static String plain(BigDecimal v) {
            return v == null ? null : v.toPlainString();
        }
    }

    /**
     * 교차표 축 하나. 행이면 선정 프로필(또는 선정 없음), 열이면 전략 프로필이다.
     *
     * @param key {@code id@version}, 선정 없음은 {@code "none"}
     * @param id 선정 없음이면 null
     * @param version 선정 없음이면 null
     */
    public record Axis(String key, String id, Integer version, String hash, boolean none) {

        static Axis of(SelectionProfile s) {
            return new Axis(s.key(), s.id(), s.version(), s.selectionHash(), false);
        }

        static Axis of(StrategyProfile p) {
            return new Axis(p.id() + "@" + p.version(), p.id(), p.version(), p.profileHash(), false);
        }

        static Axis noSelection() {
            return new Axis(Combination.NONE, null, null, Combination.NONE, true);
        }
    }

    /** 교차표 칸 하나(계좌 하나). 값·차이는 지표 정의대로 소수(비율) 또는 수(샤프 등)다. */
    public record Cell(
            String row, String column, String runId, String combinationHash, String status, String value,
            String deltaVsNoSelection, String deltaVsRowControl) {
    }

    // ---- 날짜별 선정 기록 ----

    /**
     * 선정 있는 계좌의 거래일 하나({@code run_selection_day}).
     *
     * @param validity {@code VALID} 또는 결과가 없는 사유(ADR-007 2.6)
     * @param resultHash 쓴 결과의 내용 해시. 사유가 있으면 null
     * @param resultAvailable 결과 본문이 실험실에 등록돼 있어 조회할 수 있는지
     * @param restatementFallback 그 결과의 정정 대체 규칙 적용 평가 수. 본문이 없으면 null
     */
    public record SelectionDayRow(
            String date, String validity, String resultHash, int symbolCount, int blockedEntries,
            boolean resultAvailable, Integer restatementFallback) {

        static SelectionDayRow of(RunSelectionDay d, Map<String, SelectionResult> results) {
            String hash = d.selection().resultHash();
            SelectionResult r = hash == null ? null : results.get(hash);
            return new SelectionDayRow(d.selection().date().toString(), d.selection().validity(), hash,
                    d.selection().symbols().size(), d.blockedEntries(), r != null,
                    r == null ? null : r.restatementFallbackCount());
        }
    }

    // ---- 선정 결과 본문 ----

    /**
     * 선정 결과 하나(ADR-007 2.6 결과 파일 내용). 평가 행은 {@code .../evaluations}로 페이징해 읽는다.
     *
     * @param restatementFallback 정정 대체 규칙(MD-10 (2), "정정 존재를 미리 아는 편향")을 쓴 평가 수
     * @param reasonCounts 탈락 사유별 종목 수(사유 코드 이름순). 한 종목이 여러 사유면 사유마다 센다
     */
    public record SelectionResultView(
            String contentHash, SelectionMeta selection, String applyFrom, String applyTo, InputsView inputs,
            int universeCount, int selectedCount, int restatementFallback, List<ExperimentViews.ReasonCount> reasonCounts,
            List<SelectedRow> selected, int symbolNameCount) {

        static SelectionResultView of(SelectionResult r, SymbolNames names) {
            TreeMap<String, Long> reasons = new TreeMap<>();
            r.evaluations().forEach(e -> e.reasons().forEach(code -> reasons.merge(code, 1L, Long::sum)));
            Market market = Market.valueOf(r.selection().market());
            SelectionResult.Selection s = r.selection();
            return new SelectionResultView(r.contentHash(),
                    new SelectionMeta(s.id(), s.version(), s.hash(), s.market(), s.style(), s.refresh()),
                    r.applyFrom().toString(), r.applyTo().toString(), InputsView.of(r.inputs()),
                    r.evaluations().size(), r.selected().size(), r.restatementFallbackCount(),
                    ExperimentViews.ReasonCount.of(reasons),
                    r.selected().stream().map(x -> new SelectedRow(x.rank(), x.code(),
                            name(names, market, x.code()))).toList(),
                    names.size());
        }
    }

    public record SelectionMeta(String id, int version, String hash, String market, String style, String refresh) {
    }

    /** 입력 데이터 기준(STR-15 "입력 데이터 기준일"). 쓰지 않은 입력은 null. */
    public record InputsView(
            String barsSnapshotId, String barsLastTradingDate, String sharesSnapshotId, String sharesAsOfDate,
            String financialsSnapshotId, String financialsLastCollectedAt) {

        static InputsView of(SelectionResult.Inputs i) {
            return new InputsView(i.barsSnapshotId(), i.barsLastTradingDate().toString(), i.sharesSnapshotId(),
                    i.sharesAsOfDate() == null ? null : i.sharesAsOfDate().toString(), i.financialsSnapshotId(),
                    i.financialsLastCollectedAt());
        }
    }

    public record SelectedRow(int rank, String code, String name) {
    }

    /**
     * 종목 하나의 평가(통과·탈락 사유·조건별 값).
     *
     * @param rank 선정됐으면 순위, 아니면 null
     * @param values 조건별 값({@code gate.01}, {@code score} 등). 값은 소수 문자열 그대로
     * @param restatementFallback 재무 값에 정정 대체 규칙을 썼는지(MD-10 (2) 편향 표시)
     */
    public record EvaluationRow(
            String code, String name, boolean passed, Integer rank, List<String> reasons,
            SortedMap<String, String> values, boolean restatementFallback) {
    }

    /** 평가 행 거르기. */
    enum EvaluationFilter {
        ALL, PASSED, FAILED, FALLBACK;

        static EvaluationFilter parse(String value) {
            if (value == null || value.isBlank()) {
                return ALL;
            }
            try {
                return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("filter는 all, passed, failed, fallback 중 하나입니다");
            }
        }

        boolean test(SelectionResult.Evaluation e) {
            return switch (this) {
                case ALL -> true;
                case PASSED -> e.passed();
                case FAILED -> !e.passed();
                case FALLBACK -> e.restatementFallback();
            };
        }
    }

    /** 평가 행(종목 코드순) 중 거르기와 사유에 맞는 것. */
    static List<EvaluationRow> evaluations(SelectionResult r, SymbolNames names, EvaluationFilter filter,
            String reason) {
        Market market = Market.valueOf(r.selection().market());
        Map<String, Integer> ranks = new HashMap<>();
        r.selected().forEach(x -> ranks.put(x.code(), x.rank()));
        return r.evaluations().stream()
                .filter(filter::test)
                .filter(e -> reason == null || reason.isBlank() || e.reasons().contains(reason))
                .map(e -> new EvaluationRow(e.code(), name(names, market, e.code()), e.passed(),
                        ranks.get(e.code()), e.reasons(), e.values(), e.restatementFallback()))
                .toList();
    }

    private static String name(SymbolNames names, Market market, String code) {
        try {
            return names.name(new Symbol(market, code)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null; // 종목 코드 형식이 그 시장 규칙과 다르면 이름 없이 둔다
        }
    }

    /** 결과 해시 형식(소문자 hex 64자). */
    static Optional<String> checkedHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}") ? Optional.of(hash) : Optional.empty();
    }
}
