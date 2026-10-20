package com.autotrade.sim.run;

import com.autotrade.domain.market.DataGap;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.selection.SelectionDay;
import java.math.BigDecimal;

/**
 * 실험 결과의 정규 텍스트 표현. 실행 시각 같은 메타 필드가 없어서 같은 입력이면 바이트 단위로 같다(SIM-09 인수 조건).
 * 골든 테스트 스냅샷과 {@code experiments/<실험ID>/} 결과 요약에 쓴다. 형식을 바꾸면 골든 파일도 함께 갱신한다.
 */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static String render(SimulationReport report) {
        StringBuilder sb = new StringBuilder();
        ExperimentResult r = report.result();
        sb.append("experiment ").append(r.experimentId()).append('\n');
        sb.append("code ").append(r.codeCommit()).append(r.codeDirty() ? " dirty" : "").append('\n');
        sb.append("cost-table-hash ").append(r.costTableHash()).append('\n');
        sb.append("execution-model ").append(r.executionModelId()).append('\n');
        // 입력 시세 공백(D-068). 공백 없는 실험은 이전과 같은 보고서다
        for (DataGap g : report.dataGaps()) {
            sb.append("data-gap seq=").append(g.sequence()).append(' ').append(g.symbol())
                    .append(' ').append(g.reason()).append(' ').append(g.gapStart()).append("..").append(g.gapEnd())
                    .append('\n');
        }
        for (RunReport run : report.runs()) {
            RunResult s = run.summary();
            sb.append('\n').append("run ").append(s.runId())
                    .append(" status=").append(s.status())
                    .append(" profile=").append(run.profile().id()).append('@').append(run.profile().version())
                    .append(" hash=").append(s.profileHash())
                    .append(" seed=").append(s.runSeed());
            // 선정 없는 조합은 조합 도입 전과 같은 줄을 쓴다(ADR-007 5장 sim-engine 행: 기존 보고서·골든 그대로)
            run.combination().selection().ifPresent(sel -> sb
                    .append(" selection=").append(sel.key())
                    .append(" selection-hash=").append(s.selectionHash())
                    .append(" combination=").append(s.combinationHash())
                    .append(" seed-scheme=").append(s.runSeedScheme()));
            sb.append('\n');
            if (!s.failureReason().isEmpty()) {
                sb.append("  failure ").append(s.failureReason()).append('\n');
            }
            if (s.metrics() != null) {
                metrics(sb, s.metrics(), run.equityCurve().size());
            }
            for (AppliedFill a : run.fills()) {
                Fill f = a.fill();
                sb.append("  fill ").append(f.key()).append(" #").append(f.fillSeq())
                        .append(' ').append(f.intent())
                        .append(' ').append(f.quantity()).append(" @ ").append(f.price().toPlainString())
                        .append(" commission=").append(f.fees().commission())
                        .append(" tax=").append(f.fees().tax())
                        .append(" slippage=").append(f.slippage())
                        .append(" lot=").append(a.lotId())
                        .append(" realized=").append(a.realizedPnl() == null ? "-" : a.realizedPnl().toString())
                        .append(" signal-seq=").append(f.signalEventSeq())
                        .append(" fill-seq=").append(f.fillEventSeq())
                        .append(" date=").append(f.exchangeDate()).append('\n');
            }
            for (RoundTrip t : run.roundTrips()) {
                sb.append("  round-trip ").append(t.lotId()).append(' ').append(t.symbol())
                        .append(' ').append(t.entryDate()).append("..").append(t.exitDate())
                        .append(" pnl=").append(t.pnl())
                        .append(run.windowEndLots().contains(t.lotId()) ? " window-end" : "").append('\n');
            }
            for (EquityPoint p : run.equityCurve()) {
                sb.append("  equity ").append(p.date()).append(' ').append(p.equity())
                        .append(" cash=").append(p.cash()).append('\n');
            }
            for (OrderRejection j : run.rejections()) {
                sb.append("  rejected ").append(j.key()).append(" seq=").append(j.eventSeq())
                        .append(' ').append(j.stage()).append(':').append(j.code()).append('\n');
            }
            for (RunSelectionDay day : run.selectionDays()) {
                SelectionDay d = day.selection();
                sb.append("  selection-day ").append(d.date()).append(' ').append(d.validity());
                if (d.isValid()) {
                    sb.append(" result=").append(d.resultHash()).append(" symbols=").append(d.symbols().size());
                    if (d.activeFrom() > 0) {
                        sb.append(" from-seq=").append(d.activeFrom()); // 장중 순위 선정(D-127). 장전 선정 줄은 그대로
                    }
                }
                sb.append(" blocked-entries=").append(day.blockedEntries()).append('\n');
            }
        }
        return sb.toString();
    }

    /** @param observationDays 자산 곡선 점 수(관측 거래일). 이보다 짧은 기간의 샤프·CAGR은 "-"(판정 불가, D-069) */
    private static void metrics(StringBuilder sb, RunMetrics m, int observationDays) {
        sb.append("  metrics observation-days=").append(observationDays)
                .append(" final-equity=").append(m.finalEquity())
                .append(" total-return=").append(d(m.totalReturn()))
                .append(" cagr=").append(d(m.cagr()))
                .append(" mdd=").append(d(m.maxDrawdown()))
                .append(" sharpe=").append(d(m.sharpe()))
                .append('\n');
        sb.append("  metrics win-rate=").append(d(m.winRate()))
                .append(" profit-factor=").append(d(m.profitFactor()))
                .append(" avg-holding-days=").append(d(m.avgHoldingDays()))
                .append(" fills=").append(m.fillCount())
                .append(" round-trips=").append(m.roundTripCount())
                .append(" turnover=").append(d(m.turnover()))
                .append('\n');
        sb.append("  metrics costs=").append(m.totalCosts())
                .append(" cost-share=").append(d(m.costShare()))
                .append(" benchmark=").append(d(m.benchmarkReturn()))
                .append(" excess=").append(d(m.excessReturn()))
                .append('\n');
    }

    private static String d(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }
}
