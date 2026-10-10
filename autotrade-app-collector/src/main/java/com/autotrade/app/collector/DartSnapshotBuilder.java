package com.autotrade.app.collector;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.financial.FinancialFiling;
import com.autotrade.marketdata.financial.SnapshotFinancials;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 저장된 공시·계정 응답을 재무 스냅샷 행으로 바꾼다(MD-11, ADR-008 2.5). 같은 캐시면 같은 행·같은 순서.
 *
 * <p>계정 표준화: 연결(CFS) 항목이 있으면 연결, 없으면 개별(OFS). 계정명은 아래 표로 맞추고 당기 금액({@code thstrm_amount})을
 * 쓴다. 표에 없는 이름은 버린다(결측으로 품질 리포트에 센다).
 */
public final class DartSnapshotBuilder {

    /** 표준 계정 → OpenDART 계정명(주요계정 응답 기준) `[가정]`: 업종별 변형은 첫 실수집 뒤 품질 리포트 결측 건수로 보강. */
    static final Map<String, Set<String>> ACCOUNTS = new LinkedHashMap<>();

    static {
        ACCOUNTS.put("revenue", Set.of("매출액", "수익(매출액)", "영업수익", "매출"));
        ACCOUNTS.put("operating_income", Set.of("영업이익", "영업이익(손실)"));
        ACCOUNTS.put("total_assets", Set.of("자산총계"));
        ACCOUNTS.put("total_liabilities", Set.of("부채총계"));
        ACCOUNTS.put("total_equity", Set.of("자본총계"));
    }

    /** 공시 하나와 그 계정 응답(아직 없으면 {@code null}). */
    public record Input(JsonNode filing, JsonNode accounts) {}

    /** 품질 리포트에 쓰는 건수. */
    public record Stats(long rows, long corrections, long valuesUnknown, long pending, long separateOnly,
            Map<String, Long> missingAccount, long nonPositiveEquity, long fallbackGroups, long noValueGroups,
            LocalDate firstReceipt) {}

    public record Result(List<FinancialFiling> filings, Stats stats) {}

    private DartSnapshotBuilder() {}

    public static Result build(List<Input> inputs) {
        List<FinancialFiling> out = new ArrayList<>();
        long pending = 0;
        long separate = 0;
        long equity = 0;
        Map<String, Long> missing = new LinkedHashMap<>();
        ACCOUNTS.keySet().forEach(k -> missing.put(k, 0L));
        for (Input in : inputs) {
            if (in.accounts() == null) {
                pending++;
                continue;
            }
            JsonNode f = in.filing();
            String no = f.path("rcept_no").asText();
            boolean known = no.equals(in.accounts().path("valuesFor").asText(""));
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            String scope = "";
            if (known) {
                List<JsonNode> items = new ArrayList<>();
                in.accounts().path("response").path("list").forEach(items::add);
                scope = items.stream().anyMatch(i -> "CFS".equals(i.path("fs_div").asText())) ? "CFS" : "OFS";
                separate += "OFS".equals(scope) ? 1 : 0;
                for (JsonNode i : items) {
                    if (!scope.equals(i.path("fs_div").asText())) {
                        continue;
                    }
                    String name = i.path("account_nm").asText("").strip();
                    for (Map.Entry<String, Set<String>> a : ACCOUNTS.entrySet()) {
                        if (a.getValue().contains(name) && !values.containsKey(a.getKey())) {
                            BigDecimal v = amount(i.path("thstrm_amount").asText(""));
                            if (v != null) {
                                values.put(a.getKey(), v);
                            }
                        }
                    }
                }
                for (String k : ACCOUNTS.keySet()) {
                    if (!values.containsKey(k)) {
                        missing.merge(k, 1L, Long::sum);
                    }
                }
                BigDecimal eq = values.get("total_equity");
                equity += eq != null && eq.signum() <= 0 ? 1 : 0;
            }
            String name = f.path("report_nm").asText("").strip();
            out.add(new FinancialFiling(new Symbol(Market.KRX, f.path("stock_code").asText().strip()),
                    f.path("corp_code").asText(), DartFinancialCollector.businessYear(name), OpenDartClient.ANNUAL_REPORT, no,
                    LocalDate.parse(f.path("rcept_dt").asText(), OpenDartClient.YMD), name.startsWith("["), known, scope,
                    values.get("revenue"), values.get("operating_income"), values.get("total_assets"),
                    values.get("total_liabilities"), values.get("total_equity")));
        }
        out.sort(SnapshotFinancials.ORDER);
        Map<String, List<FinancialFiling>> groups = new TreeMap<>();
        for (FinancialFiling ff : out) {
            groups.computeIfAbsent(ff.symbol().code() + "|" + ff.businessYear() + "|" + ff.reportCode(), k -> new ArrayList<>())
                    .add(ff);
        }
        long fallback = 0;
        long noValue = 0;
        for (List<FinancialFiling> g : groups.values()) {
            boolean anyKnown = g.stream().anyMatch(FinancialFiling::valuesKnown);
            if (!anyKnown) {
                noValue++;
            } else if (!g.get(0).valuesKnown()) {
                fallback++; // 원 공시 값이 없고 뒤 정정 값만 있다: MD-10 대체 규칙
            }
        }
        long corrections = out.stream().filter(FinancialFiling::correction).count();
        long unknown = out.stream().filter(ff -> !ff.valuesKnown()).count();
        LocalDate first = out.stream().map(FinancialFiling::receiptDate).min(LocalDate::compareTo).orElse(null);
        return new Result(List.copyOf(out), new Stats(out.size(), corrections, unknown, pending, separate, missing, equity,
                fallback, noValue, first));
    }

    /** 행 문자열(스냅샷 CSV 헤더 순서). */
    public static List<String> row(FinancialFiling f) {
        return List.of(f.symbol().market().name(), f.symbol().code(), f.corpCode(), Integer.toString(f.businessYear()),
                f.reportCode(), f.receiptNo(), f.receiptDate().toString(), f.correction() ? "Y" : "N",
                f.valuesKnown() ? "Y" : "N", f.scope(), plain(f.revenue()), plain(f.operatingIncome()), plain(f.totalAssets()),
                plain(f.totalLiabilities()), plain(f.totalEquity()));
    }

    static BigDecimal amount(String raw) {
        String s = raw.replace(",", "").strip();
        return s.isEmpty() || s.equals("-") ? null : new BigDecimal(s);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
