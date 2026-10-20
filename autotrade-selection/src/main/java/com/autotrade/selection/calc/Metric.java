package com.autotrade.selection.calc;

import java.util.Arrays;
import java.util.List;

/**
 * 선정 조건 카탈로그의 지표(STR-16). 프로필 키 {@code gate.<nn>.metric}·{@code score.metric}에 {@link #key()}를 쓴다.
 * 비율·증가율 값은 소수(0.5 = 50%), 금액은 원이다. 계산 정의는 docs/strategy/selection-catalog.md에 손 계산 예와 함께 둔다.
 */
public enum Metric {
    /** 최근 사업보고서 영업이익률 ÷ {@code years}개 사업연도 전 영업이익률 − 1. 두 해 모두 0보다 커야 함(아니면 DEFICIT). */
    OP_MARGIN_GROWTH("op-margin-growth", Kind.FINANCIAL, Param.YEARS),
    /** 최근 사업보고서 자산총계 ÷ {@code years}개 사업연도 전 자산총계 − 1. */
    TOTAL_ASSETS_GROWTH("total-assets-growth", Kind.FINANCIAL, Param.YEARS),
    /** 최근 사업보고서 매출액 ÷ {@code years}개 사업연도 전 매출액 − 1. */
    REVENUE_GROWTH("revenue-growth", Kind.FINANCIAL, Param.YEARS),
    /** 최근 사업보고서 영업이익 ÷ 매출액. */
    OP_MARGIN("op-margin", Kind.FINANCIAL, Param.NONE),
    /** 최근 사업보고서 부채총계 ÷ 자본총계. 자본총계 ≤ 0이면 CAPITAL_IMPAIRED. */
    DEBT_RATIO("debt-ratio", Kind.FINANCIAL, Param.NONE),
    /** 직전 거래일 종가(원가격) × 직전 거래일 장 마감 시점 상장주식수(MD-07). */
    MARKET_CAP("market-cap", Kind.SHARES, Param.NONE),
    /** 직전 {@code window}거래일 수정 거래량 합 ÷ 그 앞 {@code window}거래일 합(= 평균의 비). */
    VOLUME_RATIO("volume-ratio", Kind.BARS, Param.WINDOW),
    /** 직전 {@code window}거래일 수익률: 마지막 수정 종가 ÷ 구간 시작 전날 수정 종가 − 1. */
    RETURN("return", Kind.BARS, Param.WINDOW),
    /** 직전 {@code window}거래일 평균 거래대금(원가격 종가 × 거래량). */
    AVG_TURNOVER("avg-turnover", Kind.BARS, Param.WINDOW),
    /** 직전 거래일 변동폭: (c 수정 고가 − c 수정 저가) ÷ c−1 수정 종가(ADR-012 3.2, 단타). */
    DAY_RANGE("day-range", Kind.BARS, Param.NONE),
    /** 직전 거래일 종가 강도: (c 종가 − c 저가) ÷ (c 고가 − c 저가). 1이면 고가 마감(ADR-012 3.2, 단타). */
    CLOSE_STRENGTH("close-strength", Kind.BARS, Param.NONE),
    /** 직전 거래일 거래량 급증: c 수정 거래량 ÷ c 앞 {@code window}거래일 수정 거래량 평균(ADR-012 3.2, 단타). */
    VOLUME_SPIKE("volume-spike", Kind.BARS, Param.WINDOW),
    /**
     * 접수일이 c에서 끝나는 {@code window}거래일의 첫날 ~ T 전날(달력일, c와 T 사이 휴장일 포함)인
     * 그 종목 공시 건수. 유형은 {@code types}(OpenDART 공시 유형 코드)로 정하고 정정 공시는 세지 않는다(ADR-012 3.2, 단타).
     */
    DISCLOSURE_COUNT("disclosure-count", Kind.DISCLOSURE, Param.WINDOW),
    /**
     * 장중 누적 거래대금: 그날 정규장 시작부터 순위 시각 {@code at} 전까지 받은 체결의 가격 × 수량 합(원가격, 원). 체결이 없으면
     * NO_DATA({@code INTRADAY_AT} 선정만, ADR-012 5.2, D-127).
     */
    SESSION_TURNOVER("session-turnover", Kind.SESSION, Param.NONE),
    /** 장중 수익률: 구간 마지막 체결가 ÷ 구간 첫 체결가(보통 시가) − 1. 체결이 없으면 NO_DATA({@code INTRADAY_AT}만). */
    SESSION_RETURN("session-return", Kind.SESSION, Param.NONE),
    /** 장중 변동폭: (구간 최고 체결가 − 최저 체결가) ÷ 구간 첫 체결가. 체결이 없으면 NO_DATA({@code INTRADAY_AT}만). */
    SESSION_RANGE("session-range", Kind.SESSION, Param.NONE);

    /** 지표가 읽는 입력. {@link #SESSION}은 일봉 스냅샷이 아니라 그날 입력 기록의 체결(장중 순위 선정 전용)이다. */
    public enum Kind { BARS, SHARES, FINANCIAL, DISCLOSURE, SESSION }

    /** 지표의 기간 파라미터. */
    public enum Param { NONE, WINDOW, YEARS }

    private final String key;
    private final Kind kind;
    private final Param param;

    Metric(String key, Kind kind, Param param) {
        this.key = key;
        this.kind = kind;
        this.param = param;
    }

    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    public Param param() {
        return param;
    }

    public static Metric fromKey(String key) {
        return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst().orElseThrow(
                () -> new IllegalArgumentException("모르는 지표입니다: " + key + ". 지원: " + keys()));
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(Metric::key).toList();
    }
}
