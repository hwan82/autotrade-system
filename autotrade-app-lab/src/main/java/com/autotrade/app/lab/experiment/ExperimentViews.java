package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.Page;
import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.symbols.SymbolNames;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.account.VirtualAccount;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationReport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

/**
 * 실험 화면 REST 응답 모양(ADR-005 5장). 금액·비율은 정밀도를 잃지 않게 문자열(BigDecimal plain)로 내보내고
 * 통화 코드를 함께 둔다. 시각은 UTC ISO-8601이며 화면이 KST와 거래소 현지 시간으로 바꿔 보여 준다(UI-07).
 */
public final class ExperimentViews {

    private ExperimentViews() {
    }

    /** 실험 목록 한 줄. */
    public record Summary(
            String id, String market, String currency, String from, String to, long randomSeed, String seedCapital,
            String costModelId, String costTableHash, String executionModelId, String dataSnapshotVersion,
            String clockMode, String codeCommit, boolean codeDirty, int runCount, int completedRuns, int failedRuns) {

        static Summary of(ExperimentSpec s, SimulationReport report) {
            ExperimentResult r = report.result();
            int completed = (int) r.runs().stream().filter(x -> x.status() == RunStatus.COMPLETED).count();
            int failed = (int) r.runs().stream().filter(x -> x.status() == RunStatus.FAILED).count();
            return new Summary(s.id().value(), s.profiles().get(0).market().name(),
                    s.seedCapital().currency().getCurrencyCode(), s.from().toString(), s.to().toString(),
                    s.randomSeed(), plain(s.seedCapital()), s.costModelId(), r.costTableHash(), r.executionModelId(),
                    s.dataSnapshotVersion(), s.clockMode().name(), r.codeCommit(), r.codeDirty(), r.runs().size(),
                    completed, failed);
        }
    }

    /** 실험 상세: 요약 + 계좌(Run)별 리더보드 행. 같은 실험 안의 계좌만 비교한다(같은 기간·시드·비용, 지침 5장). */
    public record Detail(Summary summary, String benchmarkNote, List<RunRow> runs) {

        static Detail of(ExperimentSpec spec, SimulationReport report) {
            List<RunRow> rows = report.runs().stream().map(RunRow::of).toList();
            return new Detail(Summary.of(spec, report),
                    "같은 실험의 계좌들은 기간·시드·비용 모델·체결 모델이 같아 서로 비교할 수 있습니다. 다른 실험과는 비교하지 않습니다.",
                    rows);
        }
    }

    public record RunRow(
            String runId, String accountId, String profileId, int profileVersion, String profileHash, long runSeed,
            String status, String failureReason, String finalEquity, Metrics metrics, int rejectionCount) {

        static RunRow of(RunReport run) {
            RunResult s = run.summary();
            return new RunRow(s.runId(), s.accountId().value(), run.profile().id(), run.profile().version(),
                    s.profileHash(), s.runSeed(), s.status().name(), s.failureReason(),
                    s.finalEquity() == null ? null : plain(s.finalEquity()),
                    s.metrics() == null ? null : Metrics.of(s.metrics()), run.rejections().size());
        }
    }

    /** 성과 지표(비용 차감 후, ADR-004 2.5). 비율은 소수(0.05 = 5%). 정의할 수 없는 지표는 null. */
    public record Metrics(
            String totalReturn, String cagr, String maxDrawdown, String sharpe, String winRate, String profitFactor,
            String avgHoldingDays, int fillCount, int roundTripCount, String turnover, String totalCosts,
            String costShare, String benchmarkReturn, String excessReturn) {

        static Metrics of(RunMetrics m) {
            return new Metrics(d(m.totalReturn()), d(m.cagr()), d(m.maxDrawdown()), d(m.sharpe()), d(m.winRate()),
                    d(m.profitFactor()), d(m.avgHoldingDays()), m.fillCount(), m.roundTripCount(), d(m.turnover()),
                    plain(m.totalCosts()), d(m.costShare()), d(m.benchmarkReturn()), d(m.excessReturn()));
        }
    }

    /** 자산 곡선 겹쳐 보기. 계좌마다 거래일 마감 평가금액(비용 차감 후). */
    public record EquityOverlay(String currency, String seedCapital, List<EquitySeries> series) {

        static EquityOverlay of(ExperimentSpec spec, SimulationReport report) {
            List<EquitySeries> series = report.runs().stream()
                    .map(r -> new EquitySeries(r.summary().runId(), r.profile().id(), r.profile().version(),
                            r.equityCurve().stream().map(EquityPointView::of).toList()))
                    .toList();
            return new EquityOverlay(spec.seedCapital().currency().getCurrencyCode(),
                    plain(spec.seedCapital()), series);
        }
    }

    public record EquitySeries(String runId, String profileId, int profileVersion, List<EquityPointView> points) {
    }

    public record EquityPointView(String date, String equity, String cash) {
        static EquityPointView of(EquityPoint p) {
            return new EquityPointView(p.date().toString(), plain(p.equity()), plain(p.cash()));
        }
    }

    /** 체결 1건(virtual_trade_log 한 행). */
    public record FillRow(
            String key, int fillSeq, String symbol, String symbolName, String side, String intent, long quantity, String price,
            String commission, String tax, String slippage, String currency, String lotId, String realizedPnl,
            long signalEventSeq, long fillEventSeq, String signalTime, String fillTime, String exchangeDate) {

        static FillRow of(AppliedFill a, SymbolNames names) {
            Fill f = a.fill();
            return new FillRow(f.key().toString(), f.fillSeq(), f.symbol().code(), name(names, f.symbol()),
                    f.side().name(), f.intent().name(),
                    f.quantity(), f.price().toPlainString(), plain(f.fees().commission()), plain(f.fees().tax()),
                    plain(f.slippage()), f.fees().commission().currency().getCurrencyCode(), a.lotId(),
                    a.realizedPnl() == null ? null : plain(a.realizedPnl()), f.signalEventSeq(), f.fillEventSeq(),
                    f.signalTime().toString(), f.fillTime().toString(), f.exchangeDate().toString());
        }
    }

    public record RoundTripRow(
            String lotId, String symbol, String symbolName, String entryDate, String exitDate, String pnl) {
        static RoundTripRow of(RoundTrip t, SymbolNames names) {
            return new RoundTripRow(t.lotId(), t.symbol().code(), name(names, t.symbol()), t.entryDate().toString(),
                    t.exitDate().toString(), plain(t.pnl()));
        }
    }

    /** 체결되지 못한 신호·주문 1건(리스크 검증 거절, 블랙리스트 포함, 또는 거래소 거절·잔량 취소). */
    public record RejectionRow(
            long eventSeq, String key, String symbol, String symbolName, String stage, String code, String detail) {
        static RejectionRow of(OrderRejection j, SymbolNames names) {
            return new RejectionRow(j.eventSeq(), j.key().toString(), j.symbol().code(), name(names, j.symbol()),
                    j.stage(), j.code(), j.detail());
        }
    }

    /** 거절 로그 한 쪽과 거르기 전 전체의 사유별 건수. */
    public record RejectionPage(Page<RejectionRow> page, List<RejectionCount> counts) {
    }

    /** 거절 사유 분포 한 줄({@code stage:code}별 건수). */
    public record RejectionCount(String stage, String code, long count) {
    }

    /**
     * 계좌(Run) 상세 머리의 요약(ADR-005 5장 {@code .../runs/{runId}/summary}). 모든 값은 이 Run의 결과 기록에서 계산하며
     * 비용 차감 기준이다. 금액은 {@code currency} 한 통화다.
     *
     * <p>정의(코드 기준): 평가금액·현금은 자산 곡선 마지막 점, 총수익률은 {@code 평가금액 / 시드머니 − 1}(소수 6자리 HALF_EVEN,
     * {@link RunMetrics}와 같은 규칙), 누적 실현손익은 매도 체결의 {@code realizedPnl} 합(수수료·세금 차감 후,
     * {@link VirtualAccount#apply}의 정의), 비용은 체결의 수수료·세금·슬리피지 각각의 합이다.
     * 슬리피지는 체결가에 이미 반영된 기록용 금액이라 {@code feesTotal}(수수료+세금, {@code RunMetrics.totalCosts}와 같은
     * 정의)에 넣지 않고 따로 보여 준다.
     *
     * @param lastEquity 마지막 평가금액. 자산 곡선이 비어 있으면(첫 거래일 전에 실패) null
     * @param lastEquityDate 그 평가금액의 거래일. 곡선이 비어 있으면 null
     * @param totalReturn 평가금액 기준 총수익률(소수). 곡선이 비어 있으면 null
     * @param tradingDays 자산 곡선 점 수(거래일 수)
     * @param holdings 기간 끝(실패한 Run은 실패 직전) 보유 종목. 원장을 다시 계산하지 못하면 null
     * @param symbolNameCount 종목명 표에 든 종목 수. 0이면 이름표가 없는 것이다
     */
    public record RunSummary(
            String experimentId, String runId, String accountId, String profileId, int profileVersion,
            String profileHash, String status, String failureReason, String market, String currency, String from,
            String to, int tradingDays, String seedCapital, String lastEquityDate, String lastEquity, String lastCash,
            String totalReturn, String realizedPnl, int fillCount, int roundTripCount, Costs costs,
            List<HoldingRow> holdings, int symbolNameCount) {

        static RunSummary of(ExperimentSpec spec, RunReport run, SymbolNames names) {
            RunResult s = run.summary();
            Currency currency = spec.seedCapital().currency();
            Money zero = Money.zero(currency);
            Money realized = zero;
            Money commission = zero;
            Money tax = zero;
            Money slippage = zero;
            for (AppliedFill a : run.fills()) {
                Fill f = a.fill();
                commission = commission.plus(f.fees().commission());
                tax = tax.plus(f.fees().tax());
                slippage = slippage.plus(f.slippage());
                if (a.realizedPnl() != null) {
                    realized = realized.plus(a.realizedPnl());
                }
            }
            EquityPoint last = run.equityCurve().isEmpty() ? null : run.equityCurve().get(run.equityCurve().size() - 1);
            String totalReturn = last == null ? null : last.equity().amount()
                    .divide(spec.seedCapital().amount(), 6 + 2, RoundingMode.HALF_EVEN)
                    .subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
            return new RunSummary(spec.id().value(), s.runId(), s.accountId().value(), run.profile().id(),
                    run.profile().version(), s.profileHash(), s.status().name(), s.failureReason(),
                    spec.profiles().get(0).market().name(), currency.getCurrencyCode(), spec.from().toString(),
                    spec.to().toString(), run.equityCurve().size(), plain(spec.seedCapital()),
                    last == null ? null : last.date().toString(), last == null ? null : plain(last.equity()),
                    last == null ? null : plain(last.cash()), totalReturn, plain(realized), run.fills().size(),
                    run.roundTrips().size(),
                    new Costs(plain(commission), plain(tax), plain(commission.plus(tax)), plain(slippage)),
                    holdings(spec, run, names), names.size());
        }

        /**
         * 체결 원장을 {@link VirtualAccount}에 같은 순서로 다시 반영해 기간 끝 보유를 얻는다(계좌가 쓰는 평균 원가 규칙을
         * 그대로 쓰므로 따로 구현한 계산과 어긋나지 않는다). 평균 단가는 매수 비용을 포함한 원가 기준이다.
         */
        private static List<HoldingRow> holdings(ExperimentSpec spec, RunReport run, SymbolNames names) {
            try {
                VirtualAccount account = new VirtualAccount(run.summary().accountId(), spec.seedCapital());
                run.fills().forEach(a -> account.apply(a.fill()));
                return account.snapshot(run.equityCurve().isEmpty() ? Instant.EPOCH : run.equityCurve()
                                .get(run.equityCurve().size() - 1).ts()).positions().values().stream()
                        .map(p -> new HoldingRow(p.symbol().code(), name(names, p.symbol()), p.quantity(),
                                plain(p.averagePrice()), plain(account.costBasisOf(p.symbol()))))
                        .toList();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** 비용 합계. {@code feesTotal = commission + tax}. 슬리피지는 체결가에 이미 들어 있어 합계에 더하지 않는다. */
    public record Costs(String commission, String tax, String feesTotal, String slippage) {
    }

    /** 기간 끝 보유 종목 한 줄. 평균 단가·보유 원가는 매수 비용 포함. */
    public record HoldingRow(String symbol, String symbolName, long quantity, String averagePrice, String costBasis) {
    }

    private static String name(SymbolNames names, Symbol symbol) {
        return names.name(symbol).orElse(null);
    }

    static String plain(Money money) {
        return money.amount().toPlainString();
    }

    private static String d(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
