package com.autotrade.sim.combo;

import com.autotrade.domain.market.Symbol;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.selection.calc.SnapshotSelectionData;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.daily.DailyBarMarketDataSet;
import com.autotrade.sim.daily.SnapshotTradingDays;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.CalculatedSelectionSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 조합 실험 결과 파일(실행 시각 같은 메타가 없어 같은 입력이면 바이트까지 같다, SIM-09).
 *
 * <ul>
 *   <li>{@code inputs.yaml}: 입력 스냅샷 ID·해시·기준일, 데이터셋 버전, 비용·체결 모델, 블랙리스트 지문. 수집기의
 *       {@code --keep-snapshots}는 {@code experiments/} 아래 파일에 이름이 나온 스냅샷을 지우지 않는다(ADR-008 2.4).</li>
 *   <li>{@code results.md}: 교차표(선정 × 지표), 계좌별 표, 선정 품질(조합 실험 절차 1장 7번).</li>
 *   <li>{@code runs.tsv}, {@code selection-days.tsv}: 분석·화면용 표.</li>
 *   <li>{@code detail.txt}: {@code ReportWriter} 정규 보고서(체결 원장까지). 결정론 확인에 쓴다.</li>
 * </ul>
 */
final class CombinationReport {

    private CombinationReport() {
    }

    record Context(CombinationPlan plan, DailyBarMarketDataSet data, SnapshotSelectionData selectionData,
            SnapshotTradingDays.Derived tradingDays, CostModel cost, ExecutionSettings execution, BlacklistSnapshot blacklist,
            CalculatedSelectionSource selections, String determinism) {

        Context {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(data, "data");
        }
    }

    static String inputs(Context c, SimulationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 실험 입력(실행기가 씀, D-061). 이 파일에 이름이 있는 스냅샷은 수집기 --keep-snapshots가 지우지 않는다.\n");
        sb.append("experiment: ").append(c.plan().id()).append('\n');
        sb.append("code: \"").append(report.result().codeCommit()).append(report.result().codeDirty() ? " dirty" : "")
                .append("\"\n");
        sb.append("data-set: \"").append(c.data().version()).append("\"\n");
        sb.append("path-model: ").append(DailyBarMarketDataSet.PATH_MODEL).append('\n');
        var sd = c.selectionData();
        sb.append("bars:\n");
        sb.append("  snapshot: ").append(sd.barsSnapshotId()).append('\n');
        sb.append("  sha256: ").append(sd.barsSha256()).append('\n');
        sb.append("  last-trading-date: ").append(sd.barsLastTradingDate()).append('\n');
        sb.append("  shares-as-of: ").append(sd.sharesAsOf().map(LocalDate::toString).orElse("none")).append('\n');
        sb.append("financials:\n");
        sd.financials().ifPresentOrElse(f -> {
            sb.append("  snapshot: ").append(f.snapshotId()).append('\n');
            sb.append("  sha256: ").append(f.sha256()).append('\n');
            sb.append("  last-collected-at: ").append(f.lastCollectedAt() == null ? "unknown" : f.lastCollectedAt())
                    .append('\n');
        }, () -> sb.append("  snapshot: none\n"));
        SnapshotTradingDays.Derived d = c.tradingDays();
        sb.append("trading-days:\n");
        sb.append("  source: \"스냅샷 일봉 날짜 + 캘린더 대조\"\n");
        sb.append("  range: \"").append(d.first()).append(" ~ ").append(d.last()).append("\"\n");
        sb.append("  count: ").append(d.count()).append('\n');
        sb.append("  digest: ").append(d.digest()).append('\n');
        sb.append("replay:\n");
        sb.append("  from: ").append(c.plan().from()).append('\n');
        sb.append("  to: ").append(c.plan().to()).append('\n');
        sb.append("  trading-days: ").append(c.data().tradingDays().size()).append('\n');
        sb.append("  symbols: ").append(c.data().symbols().size()).append('\n');
        sb.append("  events: ").append(c.data().events().size()).append('\n');
        sb.append("  halted-bars: ").append(c.data().haltedBars()).append('\n');
        sb.append("  adjusted-symbols: ").append(c.data().adjustedSymbols().size()).append('\n');
        if (!c.data().adjustedSymbols().isEmpty()) {
            sb.append("  adjusted-codes: \"").append(String.join(",",
                    c.data().adjustedSymbols().stream().map(Symbol::code).toList())).append("\"\n");
        }
        sb.append("cost-model: ").append(c.cost().id()).append('\n');
        sb.append("cost-table-hash: ").append(report.result().costTableHash()).append('\n');
        sb.append("execution-model: \"").append(c.execution().id()).append("\"\n");
        sb.append("blacklist:\n");
        sb.append("  fingerprint: ").append(c.blacklist().fingerprint()).append('\n');
        sb.append("  size: ").append(c.blacklist().size()).append('\n');
        sb.append("benchmark: ").append(c.plan().benchmark().orElse("none")).append('\n');
        sb.append("seed: ").append(c.plan().seed()).append('\n');
        sb.append("seed-capital: \"").append(c.plan().seedCapital()).append("\"\n");
        sb.append("determinism: \"").append(c.determinism()).append("\"\n");
        return sb.toString();
    }

    static String results(Context c, SimulationReport report) {
        CombinationPlan plan = c.plan();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.id()).append(" 결과\n\n");
        sb.append("- 질문: ").append(plan.question()).append('\n');
        sb.append("- 기간: ").append(plan.from()).append(" ~ ").append(plan.to()).append(" (거래일 ")
                .append(c.data().tradingDays().size()).append("일), 시드 ").append(plan.seed()).append(", 계좌별 시드머니 ")
                .append(plan.seedCapital()).append('\n');
        sb.append("- 데이터: `").append(c.data().version()).append("`, 재무 `")
                .append(plan.financialsSnapshot().orElse("none")).append("`, 종목 ").append(c.data().symbols().size())
                .append("(상장폐지 포함), 권리 변동으로 이은 종목 ").append(c.data().adjustedSymbols().size())
                .append(", 거래정지로 뺀 일봉 ").append(c.data().haltedBars()).append('\n');
        sb.append("- 비용 `").append(c.cost().id()).append("`, 체결 `").append(c.execution().id()).append("` + 일봉 경로 `")
                .append(DailyBarMarketDataSet.PATH_MODEL).append("`, 블랙리스트 ").append(c.blacklist().size())
                .append("종목, 벤치마크 ").append(plan.benchmark().orElse("없음")).append('\n');
        sb.append("- 결정론 확인: ").append(c.determinism()).append('\n');
        sb.append("- 비교한 조합 수 m = ").append(report.runs().size()).append('\n');
        sb.append('\n');
        sb.append("> 이 표는 같은 기간·시드·비용·체결 모델의 비용 차감 성과다. 다중 비교 보정(본페로니)과 무작위 대조군 분포 판정은 이 보고서에\n");
        sb.append("> 아직 없으므로 이 표만으로 \"대조군보다 낫다\"고 판단하지 않는다(조합 실험 절차 1장 5번). 일봉 지표의 예열 기간(이동평균 일수)도\n");
        sb.append("> 기간 안에 들어 있어 그동안 진입이 없다. 투자 자문이 아니다.\n\n");

        // 교차표: 선정(행) × 전략(열), 칸 = 총수익률(왕복 수)
        Set<String> selections = new LinkedHashSet<>();
        Set<String> strategies = new LinkedHashSet<>();
        Map<String, RunReport> cells = new LinkedHashMap<>();
        for (RunReport r : report.runs()) {
            String sel = r.combination().selectionKeyOrNone();
            String str = r.profile().id() + "@" + r.profile().version();
            selections.add(sel);
            strategies.add(str);
            cells.put(sel + "|" + str, r);
        }
        sb.append("## 교차표: 총수익률(비용 차감), 괄호는 왕복 거래 수\n\n");
        sb.append("| 선정 \\ 지표 |");
        strategies.forEach(s -> sb.append(" `").append(s).append("` |"));
        sb.append("\n|---|");
        strategies.forEach(s -> sb.append("---:|"));
        sb.append('\n');
        for (String sel : selections) {
            sb.append("| `").append(sel).append("` |");
            for (String str : strategies) {
                RunReport r = cells.get(sel + "|" + str);
                if (r == null) {
                    sb.append(" |");
                } else if (r.summary().metrics() == null) {
                    sb.append(" 실패 |");
                } else {
                    RunMetrics m = r.summary().metrics();
                    sb.append(' ').append(pct(m.totalReturn())).append(" (").append(m.roundTripCount()).append(") |");
                }
            }
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("## 계좌별\n\n");
        sb.append("| 계좌 | 선정 | 지표 | 상태 | 총수익률 | 벤치마크 초과 | MDD | 샤프 | 손익비 | 승률 | 왕복 | 비용 | 선정 밖 차단 | 선정 유효일 |\n");
        sb.append("|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (RunReport r : report.runs()) {
            RunResult s = r.summary();
            RunMetrics m = s.metrics();
            int blocked = r.selectionDays().stream().mapToInt(RunSelectionDay::blockedEntries).sum();
            long valid = r.selectionDays().stream().filter(d -> d.selection().isValid()).count();
            sb.append("| ").append(s.runId()).append(" | `").append(r.combination().selectionKeyOrNone()).append("` | `")
                    .append(r.profile().id()).append('@').append(r.profile().version()).append("` | ").append(s.status());
            if (m == null) {
                sb.append(" | - | - | - | - | - | - | - | - |");
            } else {
                sb.append(" | ").append(pct(m.totalReturn())).append(" | ").append(pct(m.excessReturn())).append(" | ")
                        .append(pct(m.maxDrawdown())).append(" | ").append(num(m.sharpe())).append(" | ")
                        .append(num(m.profitFactor())).append(" | ").append(pct(m.winRate())).append(" | ")
                        .append(m.roundTripCount()).append(" | ").append(m.totalCosts()).append(" |");
            }
            if (r.combination().hasSelection()) {
                sb.append(' ').append(blocked).append(" | ").append(valid).append('/').append(r.selectionDays().size())
                        .append(" |\n");
            } else {
                sb.append(" - | - |\n");
            }
        }
        sb.append('\n');
        for (RunReport r : report.runs()) {
            if (!r.summary().failureReason().isEmpty()) {
                sb.append("- ").append(r.summary().runId()).append(" 실패 사유: ").append(r.summary().failureReason())
                        .append('\n');
            }
        }
        sb.append('\n');

        sb.append("## 선정 품질\n\n");
        sb.append("| 선정 | 계산한 결과 | 평균 선정 종목 | 선정 0 결과 | 정정 대체 규칙 적용 | 결과 없는 거래일(사유) |\n");
        sb.append("|---|---:|---:|---:|---:|---|\n");
        Map<String, SelectionProfile> profiles = new TreeMap<>();
        for (RunReport r : report.runs()) {
            r.combination().selection().ifPresent(p -> profiles.putIfAbsent(p.key(), p));
        }
        for (SelectionProfile p : profiles.values()) {
            NavigableMap<LocalDate, SelectionResult> results = c.selections().results(p.selectionHash());
            int selected = 0;
            int empty = 0;
            int fallback = 0;
            for (SelectionResult r : results.values()) {
                selected += r.selected().size();
                empty += r.selected().isEmpty() ? 1 : 0;
                fallback += r.restatementFallbackCount();
            }
            Map<String, Integer> invalid = new TreeMap<>();
            report.runs().stream().filter(r -> r.combination().selection().map(s -> s.key().equals(p.key())).orElse(false))
                    .findFirst().ifPresent(r -> r.selectionDays().stream().filter(d -> !d.selection().isValid())
                            .forEach(d -> invalid.merge(d.selection().validity(), 1, Integer::sum)));
            sb.append("| `").append(p.key()).append("` | ").append(results.size()).append(" | ")
                    .append(results.isEmpty() ? "-" : BigDecimal.valueOf(selected)
                            .divide(BigDecimal.valueOf(results.size()), 1, RoundingMode.HALF_UP).toPlainString())
                    .append(" | ").append(empty).append(" | ").append(fallback).append(" | ")
                    .append(invalid.isEmpty() ? "없음" : invalid.toString()).append(" |\n");
        }
        if (profiles.isEmpty()) {
            sb.append("| (선정 있는 조합 없음) | | | | | |\n");
        }
        sb.append("\n탈락 사유별 건수는 `selection-days.tsv`, 체결 원장은 `detail.txt`.\n");
        return sb.toString();
    }

    static String runsTsv(SimulationReport report) {
        StringBuilder sb = new StringBuilder("run_id\tselection\tstrategy\tstatus\tfinal_equity\ttotal_return\texcess_return"
                + "\tmax_drawdown\tsharpe\tprofit_factor\twin_rate\tround_trips\tfills\ttotal_costs\tblocked_entries"
                + "\tcombination_hash\tfailure\n");
        for (RunReport r : report.runs()) {
            RunResult s = r.summary();
            RunMetrics m = s.metrics();
            int blocked = r.selectionDays().stream().mapToInt(RunSelectionDay::blockedEntries).sum();
            sb.append(s.runId()).append('\t').append(r.combination().selectionKeyOrNone()).append('\t')
                    .append(r.profile().id()).append('@').append(r.profile().version()).append('\t').append(s.status());
            if (m == null) {
                sb.append("\t\t\t\t\t\t\t\t\t\t\t");
            } else {
                sb.append('\t').append(m.finalEquity().amount().toPlainString()).append('\t').append(plain(m.totalReturn()))
                        .append('\t').append(plain(m.excessReturn())).append('\t').append(plain(m.maxDrawdown()))
                        .append('\t').append(plain(m.sharpe())).append('\t').append(plain(m.profitFactor()))
                        .append('\t').append(plain(m.winRate())).append('\t').append(m.roundTripCount())
                        .append('\t').append(m.fillCount()).append('\t').append(m.totalCosts().amount().toPlainString())
                        .append('\t');
            }
            sb.append(r.combination().hasSelection() ? Integer.toString(blocked) : "").append('\t')
                    .append(s.combinationHash()).append('\t').append(s.failureReason().replace('\t', ' ')).append('\n');
        }
        return sb.toString();
    }

    static String selectionDaysTsv(Context c, SimulationReport report) {
        StringBuilder sb = new StringBuilder(
                "selection\tapply_from\tapply_to\tselected\trestatement_fallback\treasons\tresult_hash\tcodes\n");
        Map<String, SelectionProfile> profiles = new TreeMap<>();
        for (RunReport r : report.runs()) {
            r.combination().selection().ifPresent(p -> profiles.putIfAbsent(p.key(), p));
        }
        for (SelectionProfile p : profiles.values()) {
            for (SelectionResult r : c.selections().results(p.selectionHash()).values()) {
                Map<String, Integer> reasons = new TreeMap<>();
                r.evaluations().forEach(e -> e.reasons().forEach(x -> reasons.merge(x, 1, Integer::sum)));
                sb.append(p.key()).append('\t').append(r.applyFrom()).append('\t').append(r.applyTo()).append('\t')
                        .append(r.selected().size()).append('\t').append(r.restatementFallbackCount()).append('\t')
                        .append(reasons.isEmpty() ? "" : reasons.toString()).append('\t').append(r.contentHash())
                        .append('\t').append(String.join(",", r.selected().stream().map(SelectionResult.Selected::code)
                                .toList())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String pct(BigDecimal v) {
        return v == null ? "-" : v.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
