package com.autotrade.app.lab.experiment;

import com.autotrade.domain.Money;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.RunReport;
import java.math.BigDecimal;
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

        static Summary of(ExperimentCatalog.Entry entry) {
            ExperimentSpec s = entry.spec();
            ExperimentResult r = entry.report().result();
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

        static Detail of(ExperimentCatalog.Entry entry) {
            List<RunRow> rows = entry.report().runs().stream().map(RunRow::of).toList();
            return new Detail(Summary.of(entry),
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

        static EquityOverlay of(ExperimentCatalog.Entry entry) {
            List<EquitySeries> series = entry.report().runs().stream()
                    .map(r -> new EquitySeries(r.summary().runId(), r.profile().id(), r.profile().version(),
                            r.equityCurve().stream().map(EquityPointView::of).toList()))
                    .toList();
            return new EquityOverlay(entry.spec().seedCapital().currency().getCurrencyCode(),
                    plain(entry.spec().seedCapital()), series);
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
            String key, int fillSeq, String symbol, String side, String intent, long quantity, String price,
            String commission, String tax, String slippage, String currency, String lotId, String realizedPnl,
            long signalEventSeq, long fillEventSeq, String signalTime, String fillTime, String exchangeDate) {

        static FillRow of(AppliedFill a) {
            Fill f = a.fill();
            return new FillRow(f.key().toString(), f.fillSeq(), f.symbol().code(), f.side().name(), f.intent().name(),
                    f.quantity(), f.price().toPlainString(), plain(f.fees().commission()), plain(f.fees().tax()),
                    plain(f.slippage()), f.fees().commission().currency().getCurrencyCode(), a.lotId(),
                    a.realizedPnl() == null ? null : plain(a.realizedPnl()), f.signalEventSeq(), f.fillEventSeq(),
                    f.signalTime().toString(), f.fillTime().toString(), f.exchangeDate().toString());
        }
    }

    public record RoundTripRow(String lotId, String symbol, String entryDate, String exitDate, String pnl) {
        static RoundTripRow of(RoundTrip t) {
            return new RoundTripRow(t.lotId(), t.symbol().code(), t.entryDate().toString(), t.exitDate().toString(),
                    plain(t.pnl()));
        }
    }

    /** 체결되지 못한 신호·주문 1건(리스크 검증 거절, 블랙리스트 포함, 또는 거래소 거절·잔량 취소). */
    public record RejectionRow(long eventSeq, String key, String symbol, String stage, String code, String detail) {
        static RejectionRow of(OrderRejection j) {
            return new RejectionRow(j.eventSeq(), j.key().toString(), j.symbol().code(), j.stage(), j.code(),
                    j.detail());
        }
    }

    /** 거절 사유 분포 한 줄({@code stage:code}별 건수). */
    public record RejectionCount(String stage, String code, long count) {
    }

    static String plain(Money money) {
        return money.amount().toPlainString();
    }

    private static String d(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
