package com.autotrade.marketdata.financial;

import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * OpenDART 정기보고서 한 건의 표준 계정 값(MD-11, ADR-008 2.5). 같은 (종목, 사업연도, 보고서 종류)에 원 공시와 정정 공시가
 * 각각 한 행씩 있다. 시점 규칙(MD-10: 접수일 다음 거래일부터, 정정 처리)은 읽는 쪽(선정 계산)이 적용한다.
 *
 * <p>{@code valuesKnown}이 거짓이면 그 접수 번호의 값을 구하지 못한 것이다(OpenDART가 이미 정정 반영 값만 돌려준 경우).
 * 이때 금액 칸은 모두 {@code null}이고, 읽는 쪽은 MD-10 대체 규칙을 적용하고 편향 표시를 남긴다.
 * 금액 칸은 값이 있어도 그 계정이 보고서에 없으면 {@code null}이다. 단위는 원(KRW).
 *
 * @param reportCode OpenDART 보고서 코드(11011 사업보고서)
 * @param scope 재무제표 구분 {@code CFS}(연결) 또는 {@code OFS}(개별). 값을 모르면 빈 문자열
 */
public record FinancialFiling(
        Symbol symbol,
        String corpCode,
        int businessYear,
        String reportCode,
        String receiptNo,
        LocalDate receiptDate,
        boolean correction,
        boolean valuesKnown,
        String scope,
        BigDecimal revenue,
        BigDecimal operatingIncome,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity) {

    public FinancialFiling {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(corpCode, "corpCode");
        Objects.requireNonNull(reportCode, "reportCode");
        Objects.requireNonNull(receiptNo, "receiptNo");
        Objects.requireNonNull(receiptDate, "receiptDate");
        scope = scope == null ? "" : scope;
        if (!valuesKnown && (revenue != null || operatingIncome != null || totalAssets != null
                || totalLiabilities != null || totalEquity != null)) {
            throw new IllegalArgumentException("값을 모르는 공시에 금액이 있습니다: " + receiptNo);
        }
    }
}
