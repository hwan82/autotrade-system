package com.autotrade.sim.walkforward;

import com.autotrade.sim.daily.DailyBarMarketDataSet;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.study.Check;
import com.autotrade.sim.study.ScreeningCriteria;
import com.autotrade.sim.study.ScreeningThresholds;
import com.autotrade.sim.walkforward.WalkForwardJudge.Judgement;
import com.autotrade.sim.walkforward.WalkForwardResult.Candidate;
import com.autotrade.sim.walkforward.WalkForwardResult.NullSummary;
import com.autotrade.sim.walkforward.WalkForwardResult.WindowInfo;
import com.autotrade.sim.walkforward.WalkForwardWindows.Window;
import com.autotrade.strategy.catalog.EntryFunnel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 워크포워드 판정 결과 파일(실행 시각 같은 메타가 없어 같은 입력이면 바이트까지 같다, SIM-09).
 *
 * <ul>
 *   <li>{@code inputs.yaml}: 스냅샷 ID·해시(수집기 {@code --keep-snapshots}가 이 파일에 이름이 있는 스냅샷을 지우지 않는다,
 *       ADR-008 2.4), 구간, 데이터셋, 비용·체결 모델, 벤치마크 방식, 코드 커밋, 결정론 확인.</li>
 *   <li>{@code results.md}: 판정표, 조합별 근거, 검증 리더보드, 구간별 표, 대조군 분포, 한계.</li>
 *   <li>{@code runs.tsv}: 학습·검증·스트레스·변형·대조군 Run별 지표. {@code windows.tsv}: 구간별 날짜·지수.</li>
 *   <li>{@code detail.txt}: 검증 구간(기본 비용) 계좌의 체결 원장까지 담은 정규 보고서.</li>
 * </ul>
 */
final class WalkForwardReport {

    private WalkForwardReport() {
    }

    static String inputs(WalkForwardResult r, LocalDate spanFrom) {
        WalkForwardPlan plan = r.plan();
        var sd = r.selectionData();
        StringBuilder sb = new StringBuilder();
        sb.append("# 워크포워드 실험 입력(실행기가 씀). 이 파일에 이름이 있는 스냅샷은 수집기 --keep-snapshots가 지우지 않는다.\n");
        sb.append("experiment: ").append(plan.id()).append('\n');
        sb.append("code: \"").append(r.codeText()).append("\"\n");
        sb.append("path-model: ").append(DailyBarMarketDataSet.PATH_MODEL).append('\n');
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
        var d = r.tradingDays();
        sb.append("trading-days:\n");
        sb.append("  source: \"스냅샷 일봉 날짜 + 캘린더 대조\"\n");
        sb.append("  range: \"").append(d.first()).append(" ~ ").append(d.last()).append("\"\n");
        sb.append("  count: ").append(d.count()).append('\n');
        sb.append("  digest: ").append(d.digest()).append('\n');
        sb.append("walk-forward:\n");
        sb.append("  first-test-from: ").append(plan.firstTestFrom()).append('\n');
        sb.append("  train-years: ").append(plan.trainYears()).append('\n');
        sb.append("  test-years: ").append(plan.testYears()).append('\n');
        sb.append("  windows: ").append(plan.windows()).append('\n');
        sb.append("  warmup-days: ").append(plan.warmupDays()).append('\n');
        sb.append("  warmup-first-date: ").append(spanFrom).append('\n');
        sb.append("  null-seeds: ").append(plan.nullSeeds()).append('\n');
        sb.append("  combinations-m: ").append(plan.m()).append('\n');
        for (WindowInfo w : r.windowInfo()) {
            sb.append("  window-").append(w.window().number()).append(":\n");
            sb.append("    train-data-set: \"").append(w.trainDataSet()).append("\"\n");
            sb.append("    test-data-set: \"").append(w.testDataSet()).append("\"\n");
        }
        sb.append("cost-model: ").append(r.cost().id()).append('\n');
        sb.append("cost-model-slippage-x2: ").append(r.stressCost().id()).append('\n');
        sb.append("cost-table-hash: ").append(r.costTableHash()).append('\n');
        sb.append("execution-model: \"").append(r.execution().id()).append("\"\n");
        sb.append("benchmark: \"시총 가중 지수(전일 종가 x 전일 상장주식수 가중 일수익률, 가격 지수, 비용 없음, 상장폐지 포함)")
                .append(" + 동일가중 참고\"\n");
        sb.append("candidates:\n");
        for (WalkForwardResult.Candidate cand : r.candidates()) {
            sb.append("  - combination: \"").append(cand.text()).append("\"\n");
            sb.append("    combination-hash: ").append(cand.combination().combinationHash()).append('\n');
            if (cand.low() != null && cand.high() != null) {
                sb.append("    key-param: ").append(cand.perturbKey()).append('\n');
                sb.append("    variant-low-hash: ").append(cand.low().combinationHash()).append('\n');
                sb.append("    variant-high-hash: ").append(cand.high().combinationHash()).append('\n');
            }
        }
        sb.append("controls:\n");
        plan.controls().forEach((style, ref) -> sb.append("  ").append(style).append(": ").append(ref.text()).append('\n'));
        sb.append("blacklist:\n");
        sb.append("  fingerprint: ").append(r.blacklistFingerprint()).append('\n');
        sb.append("  size: ").append(r.blacklistSize()).append('\n');
        sb.append("seed: ").append(plan.seed()).append('\n');
        sb.append("seed-capital: \"").append(plan.seedCapital()).append("\"\n");
        sb.append("determinism: \"").append(WalkForwardStudy.DETERMINISM_PLACEHOLDER).append("\"\n");
        return sb.toString();
    }

    static String results(WalkForwardResult r, ScreeningThresholds t) {
        WalkForwardPlan plan = r.plan();
        int m = plan.m();
        long needNull = 20L * m;
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.id()).append(" 결과 (일봉 워크포워드 판정)\n\n");
        sb.append("- 질문: ").append(plan.question()).append('\n');
        sb.append("- 설계: 학습 ").append(plan.trainYears()).append("년 + 검증 ").append(plan.testYears()).append("년, ")
                .append(plan.windows()).append("구간(구간마다 새 계좌), 예열 ").append(plan.warmupDays())
                .append("거래일(시세만, 진입·체결·평가 없음), 시드 ").append(plan.seed()).append(", 계좌별 시드머니 ")
                .append(plan.seedCapital()).append('\n');
        sb.append("- 비교한 조합 수 m = ").append(m).append(" (본페로니: C10은 경험적 p ≤ 0.05/m = ")
                .append(new BigDecimal("0.05").divide(BigDecimal.valueOf(m), java.math.MathContext.DECIMAL64)
                        .stripTrailingZeros().toPlainString())
                .append("), 대조군 시드 ").append(plan.nullSeeds()).append("개(시드 = 실험 시드 + i, i=1..N. C10에는 완료된 N + 1 ≥ ")
                .append(needNull).append(" 필요)\n");
        sb.append("- 데이터: `").append(r.selectionData().barsSnapshotId()).append("`, 재무 `")
                .append(plan.financialsSnapshot().orElse("none")).append("`, 비용 `").append(r.cost().id())
                .append("`(스트레스 `").append(r.stressCost().id()).append("`), 체결 `").append(r.execution().id())
                .append("` + 일봉 경로 `").append(DailyBarMarketDataSet.PATH_MODEL).append("`, 블랙리스트 ")
                .append(r.blacklistSize()).append("종목\n");
        sb.append("- 결정론 확인: ").append(WalkForwardStudy.DETERMINISM_PLACEHOLDER).append('\n');
        sb.append("- 결과 해석: 통과는 C1~C10 모두 통과, 하나라도 탈락이면 탈락, 판정 불가가 있으면 보류. C5(검증 왕복 합 ≥ ")
                .append(t.minRoundTrips()).append(")에 못 미치면 다른 항목과 무관하게 보류(D-066 2번). 투자 자문이 아니다.\n\n");

        sb.append("## 판정표\n\n");
        sb.append("| 조합 | C1 | C1w | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9d | C10 | 판정 |\n");
        sb.append("|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|\n");
        for (int i = 0; i < r.candidates().size(); i++) {
            Judgement j = r.judgements().get(i);
            sb.append("| `").append(r.candidates().get(i).text()).append("` |");
            for (String code : List.of("C1", "C1w", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9d", "C10")) {
                sb.append(' ').append(cell(j, code)).append(" |");
            }
            sb.append(" **").append(j.verdict().label()).append("** |\n");
        }
        long pass = r.judgements().stream().filter(j -> j.verdict() == WalkForwardJudge.Verdict.PASS).count();
        long fail = r.judgements().stream().filter(j -> j.verdict() == WalkForwardJudge.Verdict.FAIL).count();
        long hold = r.judgements().size() - pass - fail;
        sb.append("\n통과 ").append(pass).append(", 탈락 ").append(fail).append(", 보류 ").append(hold)
                .append(" (칸: 통과 / 탈락 / 불가=판정 불가)\n\n");

        sb.append("## 조합별 근거\n\n");
        for (int i = 0; i < r.candidates().size(); i++) {
            Judgement j = r.judgements().get(i);
            sb.append("### `").append(r.candidates().get(i).text()).append("` — ").append(j.verdict().label()).append("\n\n");
            for (Check c : j.checks()) {
                sb.append("- ").append(c.code()).append(' ').append(label(c)).append(" — ").append(c.title()).append(": ")
                        .append(c.detail()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 검증 전체 리더보드 (구간 검증을 이은 곡선, 비용 차감 후)\n\n");
        sb.append("| 조합 | 총수익률 | 시총지수 초과 | 동일가중 초과(참고) | MDD | 샤프 | 손익비 | 승률 | 왕복 | 비용 | 비용 비중 | 평균 보유(일) | 회전율 | 왕복당 손익(원) | 판정 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:-:|\n");
        for (int i = 0; i < r.candidates().size(); i++) {
            Judgement j = r.judgements().get(i);
            sb.append("| `").append(r.candidates().get(i).text()).append("` |");
            if (!j.validation().complete()) {
                sb.append(" 실패 | - | - | - | - | - | - | - | - | - | - | - | - | ").append(j.verdict().label()).append(" |\n");
                continue;
            }
            RunMetrics m1 = j.validation().metrics();
            sb.append(' ').append(ScreeningCriteria.pct(m1.totalReturn())).append(" | ")
                    .append(ScreeningCriteria.pct(m1.totalReturn().subtract(r.idxCapPooled()))).append(" | ")
                    .append(ScreeningCriteria.pct(m1.totalReturn().subtract(r.idxEqualPooled()))).append(" | ")
                    .append(ScreeningCriteria.pct(m1.maxDrawdown())).append(" | ").append(ScreeningCriteria.num(m1.sharpe()))
                    .append(" | ").append(ScreeningCriteria.num(m1.profitFactor())).append(" | ")
                    .append(ScreeningCriteria.pct(m1.winRate())).append(" | ").append(m1.roundTripCount()).append(" | ")
                    .append(m1.totalCosts()).append(" | ")
                    .append(ScreeningCriteria.pct(Pooled.costShare(r.plan().seedCapital(), r.validationRuns().get(i))))
                    .append(" | ").append(ScreeningCriteria.num(m1.avgHoldingDays())).append(" | ")
                    .append(ScreeningCriteria.num(Pooled.turnover(r.validationRuns().get(i)))).append(" | ")
                    .append(j.perTrip() == null ? "-" : ScreeningCriteria.won(j.perTrip())).append(" | ")
                    .append(j.verdict().label()).append(" |\n");
        }
        sb.append("\n벤치마크(검증 전체를 이음): 시총 가중 ").append(ScreeningCriteria.pct(r.idxCapPooled())).append(", 동일가중 ")
                .append(ScreeningCriteria.pct(r.idxEqualPooled())).append("\n\n");
        sb.append("비용 비중 = 구간 비용 합 ÷ 구간 비용 전 손익 합(구간마다 시드에서 시작한 원 금액, 합이 0 이하면 -).")
                .append(" 평균 보유 = 왕복의 진입일~청산일 달력일 평균. 회전율 = 구간별 (매매 대금 ÷ 2 ÷ 구간 평균 평가금액)의 합")
                .append("(연율 아님). 판정에 쓰지 않는다.\n\n");

        sb.append("## 구간별 검증 총수익률 (괄호는 시총 가중 지수 초과)\n\n");
        sb.append("| 조합 |");
        for (Window w : r.windows()) {
            sb.append(" W").append(w.number()).append(' ').append(w.testFrom()).append(" |");
        }
        sb.append("\n|---|");
        r.windows().forEach(w -> sb.append("---:|"));
        sb.append("\n| 시총 가중 지수 |");
        r.idxCapWindows().forEach(v -> sb.append(' ').append(ScreeningCriteria.pct(v)).append(" |"));
        sb.append("\n| 동일가중 지수(참고) |");
        r.idxEqualWindows().forEach(v -> sb.append(' ').append(ScreeningCriteria.pct(v)).append(" |"));
        sb.append('\n');
        for (int i = 0; i < r.candidates().size(); i++) {
            Judgement j = r.judgements().get(i);
            sb.append("| `").append(r.candidates().get(i).text()).append("` |");
            for (int k = 0; k < r.windows().size(); k++) {
                if (!j.validation().complete()) {
                    sb.append(" 실패 |");
                } else {
                    BigDecimal ret = j.validation().windowReturns().get(k);
                    sb.append(' ').append(ScreeningCriteria.pct(ret)).append(" (")
                            .append(ScreeningCriteria.pct(ret.subtract(r.idxCapWindows().get(k)))).append(") |");
                }
            }
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("## 대조군 분포 (같은 선정 + 그 스타일 무작위 대조군, 검증 전체 왕복당 순손익, 원. C10 p = (k+1)/(N+1))\n\n");
        sb.append("| 선정 x 대조군 | 완료 표본 N | 실패 시드 | 왕복 0건이라 뺀 시드 | 최소 | 중앙 | 최대 | 평균 보유(일) |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (NullSummary n : r.nulls().values()) {
            sb.append("| `").append(n.pairKey()).append("` | ").append(n.usable()).append(" | ").append(n.failed())
                    .append(" | ").append(n.zeroTrip()).append(" | ").append(won(n.min())).append(" | ").append(won(n.median())).append(" | ")
                    .append(won(n.max())).append(" | ")
                    .append(ScreeningCriteria.num(n.avgHoldingDays())).append(" |\n");
        }
        sb.append("\n대조군은 스타일마다 하나라 청산 규칙과 보유 기간이 후보와 다르다. 왕복당 순손익은 보유가 길수록 상승장에서 커지므로")
                .append(" C10은 \"후보의 진입 + 청산\" 대 \"무작위 진입 + 대조군 청산\"의 비교다. 후보의 평균 보유(위 리더보드)와")
                .append(" 이 표의 평균 보유를 같이 읽는다(사전 등록 문구, PR #79 strategy-researcher 권고 1).\n\n");

        sb.append("## 구간 끝 정리(WINDOW_END) 왕복: 구간별 수 / 손익 합(원)\n\n");
        sb.append("구간 마지막 거래일 종가에 팔았다고 보고 비용을 뺀 왕복이다. 전체 왕복 수·PF·C10에 포함돼 있다.\n\n");
        sb.append("| 조합 |");
        r.windows().forEach(w -> sb.append(" W").append(w.number()).append(" |"));
        sb.append(" 합 |\n|---|");
        r.windows().forEach(w -> sb.append("---:|"));
        sb.append("---:|\n");
        for (int i = 0; i < r.candidates().size(); i++) {
            sb.append("| `").append(r.candidates().get(i).text()).append("` |");
            int tripSum = 0;
            BigDecimal pnlSum = BigDecimal.ZERO;
            for (WindowRun wr : r.validationRuns().get(i)) {
                sb.append(' ').append(wr.windowEndTrips()).append(" / ").append(ScreeningCriteria.won(wr.windowEndPnl()))
                        .append(" |");
                tripSum += wr.windowEndTrips();
                pnlSum = pnlSum.add(wr.windowEndPnl());
            }
            sb.append(' ').append(tripSum).append(" / ").append(ScreeningCriteria.won(pnlSum)).append(" |\n");
        }
        sb.append('\n');

        sb.append("## 진입 한도 영향 (검증 구간 측정, 판정에 쓰지 않음)\n\n");
        sb.append("일봉 경로는 그날 칸마다 종목 코드 순으로 이벤트를 준다. 하루 진입 신호가 한도(하루 진입 수·동시 보유 수 등)를 넘으면")
                .append(" 코드가 작은 종목부터 들어가고 나머지 신호는 버려진다. 한도에 걸린 날의 비율이 높을수록 성과에 코드 순 표본 추출이")
                .append(" 섞여 있고, 같은 한도를 쓰는 무작위 대조군도 같은 방식으로 쏠린다. 그래서 C10은 \"같은 선정·같은 한도에서 무작위")
                .append(" 진입보다 나은가\"로만 읽고 신호 전체의 엣지로 읽지 않는다. 신호 순서를 시드로 섞는 방법은 실행기 계약 변경이라")
                .append(" 후속 과제다(사전 등록 문구, PR #79 strategy-researcher 검토 M3). 한도에 걸린 날이 관측일의 50%를 넘는 후보는")
                .append(" 승격 제안 전에 진입 순서를 섞은 재현 결과를 붙인다(D-083).\n\n");
        sb.append("| 조합 | 진입 시도(확인 통과) | 진입 | 한도로 막힘 | 수량 0으로 막힘 | 한도에 걸린 날 / 관측일 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|\n");
        for (int i = 0; i < r.candidates().size(); i++) {
            EntryFunnel.Totals f = EntryFunnel.Totals.ZERO;
            for (WindowRun wr : r.validationRuns().get(i)) {
                f = f.plus(wr.funnel());
            }
            funnelRow(sb, "`" + r.candidates().get(i).text() + "`", f);
        }
        for (NullSummary n : r.nulls().values()) {
            funnelRow(sb, "`" + n.pairKey() + "` (대조군, 모든 시드 합)", n.funnel());
        }
        sb.append('\n');

        sb.append("## 계산량 추정\n\n");
        int pairs = r.nulls().size();
        long variants = r.candidates().stream().filter(c -> c.low() != null).count() * 2;
        int nw = r.windows().size();
        long trainRuns = (long) nw * (m + variants);
        long nullRuns = (long) nw * plan.nullSeeds() * pairs;
        long total = trainRuns + (long) nw * m * 2 + nullRuns;
        int symbols = r.windowInfo().stream().mapToInt(WindowInfo::symbols).max().orElse(0);
        int testDays = r.windowInfo().stream().mapToInt(WindowInfo::testDays).max().orElse(0);
        long eventsPerRun = (long) symbols * testDays * 6;
        sb.append("- Run 수: 학습 ").append(trainRuns).append("(후보 ").append(m).append(" + 변형 ").append(variants)
                .append(") + 검증·스트레스 ").append((long) nw * m * 2).append(" + 대조군 ").append(nullRuns).append("(구간 ")
                .append(nw).append(" x 시드 ").append(plan.nullSeeds()).append(" x 쌍 ").append(pairs).append(") = ")
                .append(total).append("개. 결정론 확인(-PverifyDeterminism)은 전체를 병렬 1로 한 번 더 돈다.\n");
        sb.append("- 검증 1년 Run당 이벤트 약 ").append(eventsPerRun).append("개(종목 ").append(symbols).append(" x 거래일 ")
                .append(testDays).append(" x 6칸, 학습은 3배). `[가정]` 1스레드 초당 100만 이벤트이면 검증 Run당 약 ")
                .append(eventsPerRun / 1_000_000L).append("초, 대조군 ").append(nullRuns).append("개를 병렬 ")
                .append(plan.parallelism()).append("로 돌리면 대략 ").append(nullRuns * eventsPerRun / 1_000_000L / Math.max(1, plan.parallelism()) / 60)
                .append("분. 실측이 아니라 규모 감을 위한 추정이다(위 가정이 틀리면 배수로 달라진다).\n");
        sb.append("- 기준 규모(대조군 400 x 조합 10 x 4구간, 쌍 4): 대조군 Run ").append(4L * 400 * 4)
                .append("개 + 나머지 약 200개.\n\n");

        sb.append("## 구간\n\n");
        sb.append("| 구간 | 학습 | 검증 | 학습 거래일 | 검증 거래일 | 종목 수(검증 데이터셋) |\n|---|---|---|---:|---:|---:|\n");
        for (WindowInfo w : r.windowInfo()) {
            sb.append("| W").append(w.window().number()).append(" | ").append(w.window().trainFrom()).append(" ~ ")
                    .append(w.window().trainTo()).append(" | ").append(w.window().testFrom()).append(" ~ ")
                    .append(w.window().testTo()).append(" | ").append(w.trainDays()).append(" | ").append(w.testDays())
                    .append(" | ").append(w.symbols()).append(" |\n");
        }
        sb.append('\n');

        sb.append("## 벤치마크 계산 방식과 데이터 한계\n\n");
        sb.append("- 시총 가중 지수: 일수익률 = Σ(전일 원종가 x 전일 장 마감 상장주식수 가중) x (수정 종가 비율 − 1). 지수는 일수익률을 이어 붙인다.")
                .append(" 권리 변동은 수정 계수 비율로 이어 반영한다. 상장주식수는 스냅샷의 시점별 값만 쓰고 미래 값은 쓰지 않는다.\n");
        sb.append("- 지수 비교 기준일: 구간 시작 직전 거래일 종가에서 구간 마지막 거래일 종가까지(계좌는 구간 첫날 장 시작에서 시작하므로 첫날 수익을 지수가 전부 가진다).\n");
        sb.append("- 평균 구성 종목 수 ").append(r.index().averageConstituents().toPlainString())
                .append(", 상장주식수를 몰라 시총 가중에서 뺀 (종목, 거래일) ").append(r.index().capExcluded()).append("건.\n");
        sb.append("- 한계: 가격 지수라 배당이 없다(계좌도 같음). 지수에는 비용이 없다. **상장폐지 종목은 마지막 일봉 이후의 손실(정리매매·청산)이")
                .append(" 일봉에 없어 지수와 계좌 모두 반영하지 못한다** — 둘 다 낙관적인 방향으로 틀리며, 후보 계좌가 폐지 직전 종목을 보유했다면")
                .append(" 실제 손실은 더 크다. 가격 기준은 데이터셋마다 첫 일봉이라(D-061) 구간 사이 가격 단위가 다르다.\n");
        sb.append("- 구간 끝 정리(WINDOW_END): 학습·검증 구간 마지막 거래일 마감에 든 포지션은 그날 마지막 체결가에 슬리피지를 더해 판 것으로 보고")
                .append(" 수수료·거래세·슬리피지를 빼 왕복으로 센다(PF·왕복 수·C10·자산 곡선이 같은 대상을 센다). 구간 중 상장폐지된 종목은")
                .append(" 마지막 거래일 종가로 같은 방식으로 정리한다(구현은 구간 끝에 정리하며 현금에 이자가 없어 최종 평가는 같다).")
                .append(" 구간 경계마다 전량 현금화한다고 가정하므로 구간을 넘어 이어지는 보유의 효과는 보지 않는다. 폐지 뒤 손실은 반영되지 않는다.\n");
        sb.append("- 예열 구간은 시세만 전략에 주므로 비용 표 시작(2010-01-01) 이전이어도 되고, 거래 구간만 비용 표 검사를 받는다.\n");
        sb.append("- 대조군 `random-daily` 계열은 예열 구간에서도 난수를 쓰므로 예열 일수가 바뀌면 같은 시드의 진입 일이 달라진다(결정론에는 영향 없음).\n");
        sb.append("- 이 표만으로 승격을 결정하지 않는다. 승격은 사용자 결정이다.\n");
        return sb.toString();
    }

    private static void funnelRow(StringBuilder sb, String name, EntryFunnel.Totals f) {
        String ratio = f.days() == 0 ? "-" : ScreeningCriteria.pct(BigDecimal.valueOf(f.limitDays())
                .divide(BigDecimal.valueOf(f.days()), 6, java.math.RoundingMode.HALF_EVEN));
        sb.append("| ").append(name).append(" | ").append(f.signals()).append(" | ").append(f.entries()).append(" | ")
                .append(f.limitBlocked()).append(" | ").append(f.sizeBlocked()).append(" | ").append(f.limitDays())
                .append(" / ").append(f.days()).append(" (").append(ratio).append(") |\n");
    }

    private static String won(BigDecimal v) {
        return v == null ? "-" : ScreeningCriteria.won(v);
    }

    private static String cell(Judgement j, String code) {
        return j.checks().stream().filter(c -> c.code().equals(code)).findFirst().map(WalkForwardReport::label).orElse("-");
    }

    private static String label(Check c) {
        return switch (c.outcome()) {
            case PASS -> "통과";
            case FAIL -> "탈락";
            case NOT_AVAILABLE -> "불가";
        };
    }

    static String windowsTsv(WalkForwardResult r) {
        StringBuilder sb = new StringBuilder("window\ttrain_from\ttrain_to\ttest_from\ttest_to\ttrain_warmup_start"
                + "\ttest_warmup_start\ttrain_days\ttest_days\tfirst_test_day\tlast_test_day\tindex_cap_return"
                + "\tindex_equal_return\n");
        for (int k = 0; k < r.windowInfo().size(); k++) {
            WindowInfo w = r.windowInfo().get(k);
            sb.append(w.window().number()).append('\t').append(w.window().trainFrom()).append('\t')
                    .append(w.window().trainTo()).append('\t').append(w.window().testFrom()).append('\t')
                    .append(w.window().testTo()).append('\t').append(w.trainWarmupStart()).append('\t')
                    .append(w.testWarmupStart()).append('\t').append(w.trainDays()).append('\t').append(w.testDays())
                    .append('\t').append(w.firstTestDay()).append('\t').append(w.lastTestDay()).append('\t')
                    .append(r.idxCapWindows().get(k).setScale(6, java.math.RoundingMode.HALF_EVEN).toPlainString())
                    .append('\t')
                    .append(r.idxEqualWindows().get(k).setScale(6, java.math.RoundingMode.HALF_EVEN).toPlainString())
                    .append('\n');
        }
        return sb.toString();
    }

    static String detail(WalkForwardResult r, String validationLedger) {
        return "walk-forward " + r.plan().id() + " (검증 구간 기본 비용 계좌의 정규 보고서. 학습·변형·스트레스·대조군은 runs.tsv)\n\n"
                + validationLedger;
    }
}
