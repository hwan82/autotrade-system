package com.autotrade.sim.study;

import static com.autotrade.sim.study.ScreeningCriteria.num;
import static com.autotrade.sim.study.ScreeningCriteria.pct;
import static com.autotrade.sim.study.ScreeningCriteria.seconds;
import static com.autotrade.sim.study.ScreeningCriteria.won;

import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.strategy.StrategyProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 비교 실험 결과의 표(마크다운)와 Run별 지표(TSV). 실행 시각 같은 메타 정보가 없어서 같은 입력이면 바이트 단위로 같다.
 * 해석(결론, 다음 실험)은 사람이 쓰는 {@code report.md}에 두고 이 파일을 링크한다.
 */
public final class StudyReportWriter {

    private StudyReportWriter() {
    }

    /**
     * @param regimeCounts 구간 이름 → 국면 → 종목·일 수(합성 데이터의 심은 정답). 녹화 데이터면 빈 맵
     */
    public static String markdown(StudyResult r, String costModelId, String codeCommit, boolean codeDirty,
            Map<String, Map<String, Integer>> regimeCounts) {
        StudyConfig c = r.config();
        StringBuilder sb = new StringBuilder();
        ExperimentOutcome val = r.outcome("VALIDATION", c.baseLatency());
        ExperimentOutcome train = r.outcome("TRAIN", c.baseLatency());

        sb.append("# ").append(c.id()).append(" 결과 표\n\n");
        sb.append("`StrategyStudy`가 만든 표다. 해석은 같은 폴더의 report.md에 있다. 모든 성과는 비용(수수료·거래세) 차감 후이고, ")
                .append("슬리피지는 호가 기반 체결가에 들어 있다(D-025).\n\n");

        sb.append("## 1. 실험 조건\n\n");
        sb.append("| 항목 | 값 |\n|---|---|\n");
        row(sb, "데이터 스냅샷", "`" + r.dataVersion() + "`");
        row(sb, "학습 구간", train.from() + " ~ " + train.to() + " (" + train.tradingDays() + "거래일)");
        row(sb, "검증 구간(표본 외)", val.from() + " ~ " + val.to() + " (" + val.tradingDays() + "거래일)");
        row(sb, "실험 시드", Long.toString(c.randomSeed()));
        row(sb, "시드머니", c.seedCapital().toString() + " (계좌별, D-021)");
        row(sb, "비용 모델", "`" + costModelId + "`, 표 해시 `" + val.costTableHash() + "`");
        row(sb, "체결 모델(기본)", "`" + val.executionModelId() + "`");
        row(sb, "지연 민감도", String.join(", ", c.latencies().stream().map(d -> seconds(d) + "초").toList()));
        row(sb, "코드", "`" + codeCommit + "`" + (codeDirty ? " (커밋 안 된 변경 있음: 재현 보장 없음)" : ""));
        row(sb, "결정론 확인", r.deterministic() ? "같음(검증 구간을 병렬 수 " + c.parallelism() + "·1로 두 번, 정규 텍스트 바이트 일치)"
                : "**다름: 결과를 쓰지 말 것**");
        sb.append('\n');

        sb.append("프로필(계좌 번호 순)\n\n| 계좌 | 프로필 | 스타일 | 로직 | 해시(앞 12자) | 핵심 파라미터 |\n|---|---|---|---|---|---|\n");
        for (int i = 0; i < c.profiles().size(); i++) {
            StrategyProfile p = c.profiles().get(i);
            String role = p.id().equals(c.baselineProfileId()) ? " (기준선)" : p.id().equals(c.controlProfileId()) ? " (대조군)" : "";
            sb.append("| A").append(String.format("%02d", i + 1)).append(" | ").append(p.id()).append('@')
                    .append(p.version()).append(role).append(" | ").append(p.style()).append(" | ")
                    .append(p.parameters().getOrDefault("logic", "-")).append(" | `")
                    .append(p.profileHash(), 0, 12).append("` | ")
                    .append(p.parameters().getOrDefault("study.key-param", "-")).append(" |\n");
        }
        sb.append('\n');

        if (!regimeCounts.isEmpty()) {
            sb.append("합성 데이터에 심은 국면(종목·일 수). 전략은 이 값을 볼 수 없다.\n\n| 구간 | TREND_UP | TREND_DOWN | RANGE |\n|---|---|---|---|\n");
            regimeCounts.forEach((period, counts) -> sb.append("| ").append(period).append(" | ")
                    .append(counts.getOrDefault("TREND_UP", 0)).append(" | ")
                    .append(counts.getOrDefault("TREND_DOWN", 0)).append(" | ")
                    .append(counts.getOrDefault("RANGE", 0)).append(" |\n"));
            sb.append('\n');
        }

        sb.append("## 2. 검증 구간 리더보드 (지연 ").append(seconds(c.baseLatency())).append("초)\n\n");
        leaderboard(sb, val, c);
        sb.append("## 3. 학습 구간 리더보드 (지연 ").append(seconds(c.baseLatency())).append("초)\n\n");
        leaderboard(sb, train, c);

        sb.append("## 4. 주문 지연 민감도 (STR-13)\n\n");
        sb.append("총수익률 / Profit Factor. 지연이 길수록 신호 뒤 더 늦은 호가로 체결된다.\n\n");
        for (String period : List.of("VALIDATION", "TRAIN")) {
            sb.append("**").append(period.equals("VALIDATION") ? "검증" : "학습").append(" 구간**\n\n| 프로필 |");
            for (Duration d : c.latencies()) {
                sb.append(' ').append(seconds(d)).append("초 |");
            }
            sb.append("\n|---|");
            sb.append("---|".repeat(c.latencies().size())).append('\n');
            for (StrategyProfile p : c.profiles()) {
                sb.append("| ").append(p.id()).append(" |");
                for (Duration d : c.latencies()) {
                    RunSummary s = r.outcome(period, d).run(p.id());
                    sb.append(' ').append(s.completed() ? pct(s.metrics().totalReturn()) + " / " + num(s.metrics().profitFactor())
                            : "실패").append(" |");
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 5. 파라미터 ±20% 민감도 (학습 구간, 지연 ").append(seconds(c.baseLatency())).append("초)\n\n");
        if (r.perturbation() == null) {
            sb.append("변형할 프로필이 없다.\n\n");
        } else {
            sb.append("| 프로필 | 키 | ×0.8 값 → 수익률 / PF | 원래 수익률 / PF | ×1.2 값 → 수익률 / PF |\n|---|---|---|---|---|\n");
            for (StrategyProfile p : c.profiles()) {
                StrategyStudy.PerturbedPair pair = r.perturbations().get(p.id());
                if (pair == null) {
                    continue;
                }
                sb.append("| ").append(p.id()).append(" | ").append(pair.key()).append(" | ").append(pair.lowValue())
                        .append(" → ").append(brief(r.perturbation().run(pair.lowId()))).append(" | ")
                        .append(brief(train.run(p.id()))).append(" | ").append(pair.highValue()).append(" → ")
                        .append(brief(r.perturbation().run(pair.highId()))).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 6. 무작위 진입 대조군 분포 (검증 구간)\n\n");
        List<BigDecimal> nulls = ScreeningCriteria.nullReturns(r);
        List<BigDecimal> perTrip = ScreeningCriteria.nullPerTrip(r);
        sb.append("`").append(c.controlProfileId()).append("`를 실험 시드만 바꿔 ").append(r.nullRuns().size())
                .append("번 돌렸다.\n\n");
        sb.append("- 총수익률(오름차순): ").append(String.join(", ", nulls.stream().map(ScreeningCriteria::pct).toList()))
                .append('\n');
        sb.append("- 왕복당 순손익(원, 오름차순): ").append(String.join(", ", perTrip.stream().map(ScreeningCriteria::won).toList()))
                .append('\n');
        BigDecimal q = ScreeningCriteria.quantile(perTrip, c.thresholds().nullPercentile());
        sb.append("- C10 기준값(왕복당 순손익 ").append(c.thresholds().nullPercentile()).append(" 분위): ")
                .append(q == null ? "-" : won(q) + "원").append("\n\n");
        sb.append("검증 구간 프로필별 왕복당 순손익(원): ");
        List<String> rows = new java.util.ArrayList<>();
        for (StrategyProfile p : c.profiles()) {
            BigDecimal v = ScreeningCriteria.perTrip(val.run(p.id()), c.seedCapital().amount());
            rows.add(p.id() + " " + (v == null ? "-" : won(v)));
        }
        sb.append(String.join(", ", rows)).append("\n\n");

        sb.append("## 7. 선발 기준 판정 (STR-04)\n\n");
        sb.append("✓ 통과, ✗ 미달, – 판단 불가(통과로 치지 않음). 기준선·대조군은 후보 판정 대상이 아니다.\n\n");
        sb.append("| 프로필 | C1 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 | C10 | 판정 |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Screening s : r.screenings()) {
            sb.append("| ").append(s.profileId()).append(" |");
            for (Check ch : s.checks()) {
                sb.append(' ').append(symbol(ch.outcome())).append(" |");
            }
            for (int i = s.checks().size(); i < 10; i++) {
                sb.append(" |");
            }
            sb.append(' ').append(s.reference() ? "참고" : s.candidate() ? "**후보**" : "탈락 (" + s.passedCount() + "/10)")
                    .append(" |\n");
        }
        sb.append("\n항목 정의\n\n");
        if (!r.screenings().isEmpty()) {
            for (Check ch : r.screenings().get(r.screenings().size() - 1).checks()) {
                sb.append("- ").append(ch.code()).append(": ").append(ch.title()).append('\n');
            }
        }
        sb.append("\n판정 근거(값)\n\n");
        for (Screening s : r.screenings()) {
            sb.append("- **").append(s.profileId()).append("**: ");
            sb.append(String.join("; ", s.checks().stream().map(ch -> ch.code() + " " + ch.detail()).toList()));
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("## 8. 검증 구간 일별 평가금액 (지연 ").append(seconds(c.baseLatency())).append("초, 원)\n\n| 날짜 |");
        for (StrategyProfile p : c.profiles()) {
            sb.append(' ').append(p.id()).append(" |");
        }
        sb.append("\n|---|").append("---|".repeat(c.profiles().size())).append('\n');
        List<LocalDate> days = r.tradingDays().subList(c.trainDays(), r.tradingDays().size());
        for (LocalDate d : days) {
            sb.append("| ").append(d).append(" |");
            for (StrategyProfile p : c.profiles()) {
                sb.append(' ').append(equityOn(val.run(p.id()), d)).append(" |");
            }
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("## 9. 실패·거절\n\n");
        boolean any = false;
        for (ExperimentOutcome o : r.grid()) {
            for (RunSummary s : o.runs().values()) {
                if (!s.completed()) {
                    any = true;
                    sb.append("- ").append(o.experimentId()).append(' ').append(s.profileId()).append(": ")
                            .append(s.failureReason()).append('\n');
                }
            }
        }
        if (!any) {
            sb.append("실패한 Run 없음.\n");
        }
        sb.append("\n거절 사유 분포(검증 구간, 기본 지연)\n\n");
        for (RunSummary s : val.runs().values()) {
            sb.append("- ").append(s.profileId()).append(": ")
                    .append(s.rejectionsByCode().isEmpty() ? "없음" : s.rejectionsByCode().toString()).append('\n');
        }
        return sb.toString();
    }

    /** Run별 지표 한 줄씩(UI·추가 분석용). */
    public static String tsv(StudyResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", "experiment", "period", "latency_ms", "seed", "profile", "profile_hash", "status",
                "final_equity", "total_return", "mdd", "sharpe", "profit_factor", "win_rate", "round_trips", "fills",
                "avg_holding_days", "turnover", "total_costs", "cost_share", "stop_loss_sells")).append('\n');
        java.util.ArrayList<ExperimentOutcome> all = new java.util.ArrayList<>(r.grid());
        if (r.perturbation() != null) {
            all.add(r.perturbation());
        }
        all.addAll(r.nullRuns());
        for (ExperimentOutcome o : all) {
            for (RunSummary s : o.runs().values()) {
                RunMetrics m = s.metrics();
                sb.append(String.join("\t", o.experimentId(), o.period(), Long.toString(o.latency().toMillis()),
                        Long.toString(o.randomSeed()), s.profileId(), s.profileHash(), s.status().name(),
                        m == null ? "" : m.finalEquity().amount().toPlainString(),
                        plain(m, RunMetrics::totalReturn), plain(m, RunMetrics::maxDrawdown), plain(m, RunMetrics::sharpe),
                        plain(m, RunMetrics::profitFactor), plain(m, RunMetrics::winRate),
                        m == null ? "" : Integer.toString(m.roundTripCount()),
                        m == null ? "" : Integer.toString(m.fillCount()),
                        plain(m, RunMetrics::avgHoldingDays), plain(m, RunMetrics::turnover),
                        m == null ? "" : m.totalCosts().amount().toPlainString(), plain(m, RunMetrics::costShare),
                        Integer.toString(s.stopLossSells()))).append('\n');
            }
        }
        return sb.toString();
    }

    private static void leaderboard(StringBuilder sb, ExperimentOutcome o, StudyConfig c) {
        RunSummary base = o.run(c.baselineProfileId());
        BigDecimal baseReturn = base != null && base.completed() ? base.metrics().totalReturn() : null;
        sb.append("실험 `").append(o.experimentId()).append("`, 체결 모델 `").append(o.executionModelId()).append("`. 총수익률 순.\n\n");
        sb.append("| 순위 | 프로필 | 총수익률 | 기준선 대비 | MDD | 샤프 | PF | 승률 | 왕복 | 평균 보유일 | 회전율 | 비용(원) | 비용 비중 | 손절 매도 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<RunSummary> sorted = o.runs().values().stream()
                .sorted((a, b) -> {
                    if (!a.completed() || !b.completed()) {
                        return Boolean.compare(!a.completed(), !b.completed());
                    }
                    int cmp = b.metrics().totalReturn().compareTo(a.metrics().totalReturn());
                    return cmp != 0 ? cmp : a.profileId().compareTo(b.profileId());
                })
                .toList();
        int rank = 0;
        for (RunSummary s : sorted) {
            rank++;
            if (!s.completed()) {
                sb.append("| ").append(rank).append(" | ").append(s.profileId()).append(" | 실패 |").append(" |".repeat(11)).append('\n');
                continue;
            }
            RunMetrics m = s.metrics();
            sb.append("| ").append(rank).append(" | ").append(s.profileId())
                    .append(" | ").append(pct(m.totalReturn()))
                    .append(" | ").append(baseReturn == null ? "-" : pct(m.totalReturn().subtract(baseReturn)))
                    .append(" | ").append(pct(m.maxDrawdown()))
                    .append(" | ").append(num(m.sharpe()))
                    .append(" | ").append(num(m.profitFactor()))
                    .append(" | ").append(pct(m.winRate()))
                    .append(" | ").append(m.roundTripCount())
                    .append(" | ").append(num(m.avgHoldingDays()))
                    .append(" | ").append(num(m.turnover()))
                    .append(" | ").append(won(m.totalCosts().amount()))
                    .append(" | ").append(pct(m.costShare()))
                    .append(" | ").append(s.stopLossSells())
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private static String brief(RunSummary s) {
        return s == null || !s.completed() ? "실패" : pct(s.metrics().totalReturn()) + " / " + num(s.metrics().profitFactor());
    }

    private static String equityOn(RunSummary s, LocalDate d) {
        for (EquityPoint p : s.equity()) {
            if (p.date().equals(d)) {
                return won(p.equity().amount());
            }
        }
        return "-";
    }

    private static String symbol(Check.Outcome o) {
        return switch (o) {
            case PASS -> "✓";
            case FAIL -> "✗";
            case NOT_AVAILABLE -> "–";
        };
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }

    private static String plain(RunMetrics m, Function<RunMetrics, BigDecimal> f) {
        if (m == null) {
            return "";
        }
        BigDecimal v = f.apply(m);
        return v == null ? "" : v.setScale(6, RoundingMode.HALF_EVEN).toPlainString();
    }
}
