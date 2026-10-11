package com.autotrade.app.collector;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KRX Open API 일별매매정보 한 행(원시가). 가격 칸이 비거나 {@code -}면 {@code null}.
 *
 * @param change 전일 대비({@code CMPPREVDD_PRC}). 기준가 = 종가 − 전일 대비. 수정 계수 계산에 쓴다
 * @param section 소속부({@code SECT_TP_NM}, 코스닥 우량기업부 등). 없으면 빈 문자열
 */
public record KrxDailyRow(
        LocalDate date,
        KrxBoard board,
        String code,
        String name,
        String section,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal change,
        long volume,
        long listedShares) {}
