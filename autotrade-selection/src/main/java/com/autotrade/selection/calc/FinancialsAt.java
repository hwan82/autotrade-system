package com.autotrade.selection.calc;

import com.autotrade.marketdata.financial.FinancialFiling;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 한 종목의 선정 기준일 T 시점 사업보고서 값(MD-10 시점 규칙, D-042 4번).
 *
 * <ul>
 *   <li><b>사용 가능</b>: 접수일이 T보다 앞선 공시만 쓴다. T가 거래일이므로 "접수일 다음 거래일부터"와 같다.</li>
 *   <li><b>기본 규칙</b>: 한 사업연도의 공시(원 공시·정정) 중 T 전에 접수된 가장 늦은 공시의 값을 쓴다. 그 공시의 값이
 *       있으면(원 공시 값 또는 그 정정의 값) 미래 정보 0건이다.</li>
 *   <li><b>대체 규칙</b>: 가장 늦은 공시의 값을 모르면(OpenDART가 정정 반영 값만 줘서 원 공시 값이 없는 경우) 그 사업연도는
 *       아직 없는 것으로 보고 직전 사업연도를 "최근 사업보고서"로 쓴다. 정정 존재를 미리 아는 편향이 있으므로
 *       {@link Latest#fallback()}으로 표시한다.</li>
 * </ul>
 * 비교 기준 연도(예: 3개 사업연도 전)의 값을 모르면 대체하지 않고 결측으로 본다.
 */
final class FinancialsAt {

    /** OpenDART 사업보고서(연간) 보고서 코드. */
    static final String ANNUAL_REPORT = "11011";

    private static final Comparator<FinancialFiling> RECEIPT_ORDER =
            Comparator.comparing(FinancialFiling::receiptDate).thenComparing(FinancialFiling::receiptNo);

    private final List<FinancialFiling> annual;

    /** @param applyFrom 선정 기준일 T(거래일) */
    FinancialsAt(List<FinancialFiling> filings, LocalDate applyFrom) {
        Objects.requireNonNull(applyFrom, "applyFrom");
        this.annual = filings.stream()
                .filter(f -> f.reportCode().equals(ANNUAL_REPORT) && f.receiptDate().isBefore(applyFrom))
                .sorted(RECEIPT_ORDER)
                .toList();
    }

    /**
     * 최근 사업보고서.
     *
     * @param filing 값이 있는 공시. 없으면 빈 값(그때 {@code year}는 의미 없음)
     * @param fallback 대체 규칙을 썼는지
     */
    record Latest(int year, Optional<FinancialFiling> filing, boolean fallback) {
    }

    Latest latest() {
        Optional<Integer> newest = annual.stream().map(FinancialFiling::businessYear).max(Integer::compare);
        if (newest.isEmpty()) {
            return new Latest(0, Optional.empty(), false);
        }
        int year = newest.get();
        Optional<FinancialFiling> f = year(year);
        if (f.isPresent()) {
            return new Latest(year, f, false);
        }
        return new Latest(year - 1, year(year - 1), true);
    }

    /** 그 사업연도에서 T 전에 접수된 가장 늦은 공시. 값을 모르면 빈 값. */
    Optional<FinancialFiling> year(int businessYear) {
        FinancialFiling last = null;
        for (FinancialFiling f : annual) {
            if (f.businessYear() == businessYear) {
                last = f;
            }
        }
        return last != null && last.valuesKnown() ? Optional.of(last) : Optional.empty();
    }
}
