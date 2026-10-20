package com.autotrade.sim.edge;

import com.autotrade.selection.profile.SelectionProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 선정 엣지 사전 검사 결과 파일(D-125 3장). 타임스탬프를 쓰지 않고 순서를 고정해 같은 입력이면 바이트까지 같다.
 */
final class EdgeReport {

    private EdgeReport() {
    }

    static String results(EdgePlan plan, SelectionProfile profile, EdgeJudge.Result r, List<SelectionEdgeStudy.Day> days) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.id()).append(" 선정 엣지 일봉 사전 검사 결과\n\n");
        sb.append("- 질문: ").append(plan.question()).append('\n');
        sb.append("- 선정: `").append(profile.key()).append("` (해시 ").append(profile.selectionHash()).append(")\n");
        sb.append("- 가설: ").append(profile.parameters().getOrDefault(SelectionProfile.HYPOTHESIS, "-")).append('\n');
        sb.append("- 주 통계: `").append(plan.test()).append("` ")
                .append(plan.test() == EdgePlan.Test.RANGE ? "T 장중 변동폭 (고가 − 저가) ÷ 시가"
                        : "T 시가 매수 → T 종가 매도 비용 차감 수익률").append('\n');
        sb.append("- 모델 `").append(SelectionEdgeStudy.MODEL).append("`, 대조군 시드 N = ").append(plan.nullSeeds())
                .append(", 본페로니 m = ").append(plan.familySize()).append(", 유의수준 0.05 ÷ m = ").append(p(r.alpha()))
                .append('\n');
        sb.append("- 입력 버전·해시·커밋은 `inputs.yaml`, 날마다 값은 `days.tsv`, 대조군 시드별 값은 `null.tsv`\n\n");

        sb.append("## 1. 판정\n\n");
        sb.append("**").append(r.verdict().label()).append("**\n\n");
        for (String reason : r.reasons()) {
            sb.append("- ").append(reason).append('\n');
        }
        sb.append('\n');
        EdgeJudge.Segment all = r.all();
        double[] primary = plan.test() == EdgePlan.Test.RANGE ? all.controlRange() : all.controlNet();
        sb.append("| 항목 | 후보 | 대조군 중앙값 | 대조군 1 − α 분위 | 위쪽 p |\n|---|---|---|---|---|\n");
        sb.append("| 주 통계 ").append(plan.test()).append(" | ").append(f(all.candidate(plan.test()).value()))
                .append(" | ").append(f(EdgeJudge.median(primary))).append(" | ")
                .append(f(EdgeJudge.quantile(primary, 1 - r.alpha()))).append(" | ").append(p(r.pHigh())).append(" |\n");
        EdgePlan.Test other = plan.test() == EdgePlan.Test.RANGE ? EdgePlan.Test.NET : EdgePlan.Test.RANGE;
        double[] secondary = other == EdgePlan.Test.RANGE ? all.controlRange() : all.controlNet();
        sb.append("| 참고 ").append(other).append(" | ").append(f(all.candidate(other).value())).append(" | ")
                .append(f(EdgeJudge.median(secondary))).append(" | ")
                .append(f(EdgeJudge.quantile(secondary, 1 - r.alpha()))).append(" | ").append(p(r.pOther()))
                .append(" |\n\n");
        sb.append("- 후보 왕복 ").append(all.range().n()).append("건(거래 불가로 뺀 선정 ").append(all.untradableSelected())
                .append("건), 왕복이 있는 대조군 시드 N′ = ").append(r.effectiveSeeds()).append(", net 아래쪽 p = ")
                .append(p(r.pNetLow())).append('\n');
        sb.append("- 대조군 분포 net: 1% ").append(f(EdgeJudge.quantile(all.controlNet(), 0.01))).append(", 5% ")
                .append(f(EdgeJudge.quantile(all.controlNet(), 0.05))).append(", 50% ")
                .append(f(EdgeJudge.median(all.controlNet()))).append(", 95% ")
                .append(f(EdgeJudge.quantile(all.controlNet(), 0.95))).append(", 99% ")
                .append(f(EdgeJudge.quantile(all.controlNet(), 0.99))).append('\n');
        sb.append("- 대조군 분포 range: 1% ").append(f(EdgeJudge.quantile(all.controlRange(), 0.01))).append(", 50% ")
                .append(f(EdgeJudge.median(all.controlRange()))).append(", 99% ")
                .append(f(EdgeJudge.quantile(all.controlRange(), 0.99))).append("\n\n");

        sb.append("## 2. 구간별\n\n");
        sb.append("| 구간 | 판정 | 거래일 | 선정 있는 날 | 후보 왕복 | 후보 range | 대조군 range 중앙값 | 후보 net | ")
                .append("대조군 net 중앙값 | 주 통계 대조군 초과 |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (EdgeJudge.Segment s : r.segments()) {
            sb.append("| ").append(s.label()).append(" | ").append(s.judged() ? "판정" : "기술 통계").append(" | ")
                    .append(s.tradingDays()).append(" | ").append(s.activeDays()).append(" | ").append(s.range().n())
                    .append(" | ").append(f(s.range().value())).append(" | ").append(f(EdgeJudge.median(s.controlRange())))
                    .append(" | ").append(f(s.net().value())).append(" | ").append(f(EdgeJudge.median(s.controlNet())))
                    .append(" | ").append(s.positive(plan.test()) ? "예" : "아니오").append(" |\n");
        }
        sb.append('\n');

        sb.append("## 3. 선정 기술 통계(판정에 쓰지 않음, PR #193 R3·M1)\n\n");
        sb.append("| 구간 | 하루 통과 종목 평균 | 최소·중앙·최대 | 상한(max-symbols)에 걸린 날 | 하드 필터 통과 평균 | ")
                .append("결측 하나로만 빠진 종목(통과 대비) | 후보 시가 = 고가 비율 | 대조군 시가 = 고가 중앙값 |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (EdgeJudge.Segment s : r.segments()) {
            long sole = s.soleNoData().values().stream().mapToLong(Integer::longValue).sum();
            sb.append("| ").append(s.label()).append(" | ").append(ratio(s.gatePassedSum(), s.tradingDays()))
                    .append(" | ").append(s.gatePassedMin()).append(" · ").append(s.gatePassedMedian()).append(" · ")
                    .append(s.gatePassedMax()).append(" | ").append(s.cappedDays()).append("/").append(s.tradingDays())
                    .append(" | ").append(ratio(s.hardPassSum(), s.tradingDays())).append(" | ").append(sole)
                    .append(" (").append(ratio(sole, s.gatePassedSum())).append(") ").append(soleText(s.soleNoData()))
                    .append(" | ").append(f(s.openAtHigh().value())).append(" | ")
                    .append(f(EdgeJudge.median(s.controlOpenAtHigh()))).append(" |\n");
        }
        sb.append('\n');

        EdgeSurvivorship.Result sv = r.survivorship();
        if (sv != null) {
            sb.append("## 3-1. 공시 생존편향 확인(D-125, PR #196 R3·n3·n4)\n\n");
            sb.append("| 구분 | 셀 수 있는 종목 | 셀 연도 없음 | 연 단위 덮임률 평균 |\n|---|---|---|---|\n");
            sb.append("| 상장폐지 | ").append(sv.delistedCounted()).append(" | ").append(sv.delistedSkipped()).append(" | ")
                    .append(f(sv.delistedMean())).append(" |\n");
            sb.append("| 상장 유지(기준선) | ").append(sv.listedCounted()).append(" | ").append(sv.listedSkipped())
                    .append(" | ").append(f(sv.listedMean())).append(" |\n\n");
            sb.append("- 유형 ").append(String.join(",", sv.types())).append(", 폐지 ÷ 유지 = ").append(f(sv.ratio()))
                    .append(", 기준 ≥ ").append(EdgeSurvivorship.MIN_RATIO).append("(폐지 종목 ")
                    .append(EdgeSurvivorship.MIN_DELISTED).append("개 이상): **").append(sv.passed() ? "통과" : "미확인")
                    .append("**\n");
            sb.append("- 공시 스냅샷의 코드 출처별 건수(DART·CORP·NAME·NONE)는 그 스냅샷 품질 리포트를 함께 본다\n\n");
        }
        sb.append("## 4. 해석 한계(결과를 보기 전에 고정한 문구, D-125 4장)\n\n");
        sb.append("""
                - 이 검사는 장중 체결을 재현하지 않는다. T 시가·종가 일봉으로 근사한 선정 수준 통계이며 승격 근거가 아니라 LIVE02 등록 전에 거르는 단계다(ADR-012 3.3).
                - 시가 매수는 시가 × (1 + 슬리피지)로 근사한다. 시가가 상한가인 날(시가 = 고가)은 실제로 사기 어렵다. 그 비율을 3장에 후보와 대조군으로 따로 적었다. 차이가 크면 net 결과를 낙관으로 읽는다.
                - 전일 거래대금은 c 종가 × 거래량 근사다(실제 거래대금 ACC_TRDVAL 아님). 고가 마감 종목 쪽으로 선정이 기울 수 있고, 대조군은 같은 하드 필터 통과 집합에서 뽑으므로 이 기울기는 선정 효과에 섞인다(PR #193 R2).
                - 명목 금액 기준(예: 100억)은 기간마다 뜻이 다르다. 구간별 하루 통과 종목 수를 3장에 남겼다. 이 수치를 보고 기준을 바꾸려면 새 버전·새 실험 ID로 한다(PR #193 R3).
                - 2015-06-15 전에는 가격제한폭이 ±15%라 상한 0.25 조건이 상한가 근접 제외가 아니다. 그 기간은 기술 통계 구간으로만 보고 판정에 넣지 않는다(PR #193 R1).
                - RANGE 통과는 "선정이 다음 날 변동이 큰 종목을 고른다"는 뜻일 뿐이다. 변동성 지속은 알려진 현상이라 통과가 쉬울 수 있다 `[추론]`. 단타 매매지표 성과가 무작위보다 높은지는 녹화 실험·LIVE02에서만 본다.
                - 호가 단위는 지금 표를 전 기간에 쓴다(2023-01-25 호가 단위 개편 전 기간 근사). 소속부는 구간 안 마지막 일봉일 기준이다(Q-012 단순화).
                - 상장폐지 종목은 c에 상장돼 있었으면 모집단에 들어간다. T에 일봉이 없으면 후보·대조군 모두 거래 불가로 같게 뺀다.
                - 공시 선정은 접수일만 있어 c 장중 공시(이미 c 가격에 반영)와 장 마감 뒤 공시를 가르지 못한다. 거래량 급증 순위와 겹치면 c에 이미 반응한 종목 쪽으로 쏠릴 수 있다(PR #196 m2). 우선주는 공시 코드가 보통주라 늘 0건이다.
                - 이 결과는 투자 자문이 아니다.
                """);
        return sb.toString();
    }

    static String windowsTsv(EdgePlan plan, EdgeJudge.Result r) {
        StringBuilder sb = new StringBuilder("segment\tjudged\ttrading_days\tactive_days\tcandidate_trades\tuntradable_selected"
                + "\tcandidate_range\tcontrol_range_median\tcandidate_net\tcontrol_net_median\tpositive\tgate_passed_mean"
                + "\tcapped_days\thard_pass_mean\tsole_no_data\n");
        for (EdgeJudge.Segment s : r.segments()) {
            row(sb, plan, s);
        }
        row(sb, plan, r.all());
        return sb.toString();
    }

    private static void row(StringBuilder sb, EdgePlan plan, EdgeJudge.Segment s) {
        long sole = s.soleNoData().values().stream().mapToLong(Integer::longValue).sum();
        sb.append(s.label()).append('\t').append(s.judged()).append('\t').append(s.tradingDays()).append('\t')
                .append(s.activeDays()).append('\t').append(s.range().n()).append('\t').append(s.untradableSelected())
                .append('\t').append(f(s.range().value())).append('\t').append(f(EdgeJudge.median(s.controlRange())))
                .append('\t').append(f(s.net().value())).append('\t').append(f(EdgeJudge.median(s.controlNet())))
                .append('\t').append(s.positive(plan.test())).append('\t').append(ratio(s.gatePassedSum(), s.tradingDays()))
                .append('\t').append(s.cappedDays()).append('\t').append(ratio(s.hardPassSum(), s.tradingDays()))
                .append('\t').append(sole).append('\n');
    }

    static String daysTsv(EdgePlan plan, List<SelectionEdgeStudy.Day> days) {
        StringBuilder sb = new StringBuilder("t\tc\tsegment\tgate_passed\tselected\thard_pass\tcandidate_trades"
                + "\tcandidate_range\tcandidate_net\tsole_no_data\n");
        for (SelectionEdgeStudy.Day d : days) {
            int n = 0;
            double range = 0;
            double net = 0;
            for (int idx : d.selected()) {
                if (!Double.isNaN(d.range()[idx])) {
                    n++;
                    range += d.range()[idx];
                    net += d.net()[idx];
                }
            }
            String segment = d.segment() < plan.windows().size() ? "W" + (d.segment() + 1)
                    : "D" + (d.segment() - plan.windows().size() + 1);
            sb.append(d.t()).append('\t').append(d.c()).append('\t').append(segment).append('\t').append(d.gatePassed())
                    .append('\t').append(d.selected().length).append('\t').append(d.hardPass()).append('\t').append(n)
                    .append('\t').append(n == 0 ? "-" : f(range / n)).append('\t').append(n == 0 ? "-" : f(net / n))
                    .append('\t').append(soleText(d.soleNoData())).append('\n');
        }
        return sb.toString();
    }

    static String nullTsv(EdgeJudge.Result r) {
        StringBuilder sb = new StringBuilder("seed_index\trange\tnet\n");
        double[] range = r.all().controlRange();
        double[] net = r.all().controlNet();
        for (int i = 0; i < range.length; i++) {
            sb.append(i).append('\t').append(f(range[i])).append('\t').append(f(net[i])).append('\n');
        }
        return sb.toString();
    }

    private static String soleText(Map<String, Integer> sole) {
        return sole.isEmpty() ? "-" : sole.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    /** 소수 6자리, NaN은 "-". 금액이 아닌 비율이라 실수 서식으로 쓴다(Locale.ROOT, 음의 0은 0으로). */
    static String f(double v) {
        return fixed(v, 6);
    }

    /** p 값 소수 4자리. */
    static String p(double v) {
        return fixed(v, 4);
    }

    private static String fixed(double v, int digits) {
        if (Double.isNaN(v)) {
            return "-";
        }
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        return s.startsWith("-") && s.chars().noneMatch(c -> c >= '1' && c <= '9') ? s.substring(1) : s;
    }

    private static String ratio(long a, long b) {
        return b == 0 ? "-" : BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
