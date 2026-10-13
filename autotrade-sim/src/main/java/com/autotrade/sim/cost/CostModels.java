package com.autotrade.sim.cost;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * D-021 기본 비용 프로필(KR-DEFAULT, US-DEFAULT, US-TOSS). 수치의 출처와 상태는 D-021 표와
 * docs/research/q-004-trading-costs.md 1·2장을 따른다. 세율·수수료가 바뀌면 여기 표에 구간을 추가하고
 * 이전 구간은 지우지 않는다(D-021 되돌릴 조건).
 *
 * <p>설정 파일(runtime-config.json)에서 표를 읽는 키(cost.profiles.*)는 architect 검토 후 후속 작업에서 더한다(ADR-004 5장).
 */
public final class CostModels {

    /**
     * 표의 시작일. 이 날 이전 데이터로는 실험을 시작할 수 없다(SIM-07). 수집기 전체 수집이 2010년부터라(collector runbook 3장)
     * 그 해부터 둔다(D-071).
     */
    static final LocalDate KR_TABLE_START = LocalDate.of(2010, 1, 1);
    /** 미국 표의 시작일. TAF 구간이 2026-01-01부터 확인되어 있다(Q-004 조사 2.1). */
    static final LocalDate US_TABLE_START = LocalDate.of(2026, 1, 1);

    /** D-021 슬리피지 기본값 0.15%(체결가 기반 체결에만 쓴다). */
    public static final BigDecimal DEFAULT_SLIPPAGE = new BigDecimal("0.0015");

    private CostModels() {
    }

    /**
     * 국내 기본: 위탁수수료 0.015% `[가정]`, 매도 시 증권거래세·농특세. 각각 원 미만 절사.
     *
     * <p>세율은 "그날 이후 <b>양도분</b>부터"로 바뀌고 양도일은 결제일(체결 거래일 T+2, KRX 휴장일 제외)이다. 그래서 구간 경계는
     * 결제일이 새 해(또는 시행일)에 들어가는 첫 <b>체결 거래일</b>이다(예: 2025년 세율은 2024-12-27 체결분부터, 증권사 공지
     * D-071 표). 2024년까지의 세율은 공식 법령 원문(국가법령정보센터 증권거래세법 시행령 제5조 연혁)을 이 환경에서 열 수 없어
     * 여러 2차 자료로 맞춰 본 `[가정]`이다. 수치와 출처는 D-071 표와 docs/research/q-004-trading-costs.md 1.3절.
     *
     * @param boardOf 종목 → 체결 당시 소속부. 시점별 이력은 Q-012(data-engineer)
     */
    public static CostModel krDefault(Function<Symbol, KrxBoard> boardOf) {
        Levy commission = Levy.RateLevy.flat(
                "commission", CostCategory.COMMISSION, null, RoundingMode.FLOOR, KR_TABLE_START, "0.00015");
        // 경계 체결일(결제일 T+2가 시행일 이후가 되는 첫 거래일). 연말 휴장일(12-31 또는 그 주 금요일)은 결제도 없다 `[추론]`
        LocalDate c2019 = LocalDate.of(2019, 5, 30);  // 2019-06-03 양도분부터
        LocalDate c2021 = LocalDate.of(2020, 12, 29); // 2021-01-04 결제
        LocalDate c2023 = LocalDate.of(2022, 12, 28); // 2023-01-02 결제
        LocalDate c2024 = LocalDate.of(2023, 12, 27); // 2024-01-02 결제
        LocalDate c2025 = LocalDate.of(2024, 12, 27); // 2025-01-02 결제(증권사 공지로 확인)
        LocalDate c2026 = LocalDate.of(2025, 12, 29); // 2026-01-02 결제
        Levy stt = new Levy.RateLevy("stt", CostCategory.TAX, Side.SELL, RoundingMode.FLOOR, PeriodTable.of(
                Period.closed(KR_TABLE_START, c2019.minusDays(1),
                        SegmentRates.of("KOSPI", "0.0015", "KOSDAQ", "0.0030", "KONEX", "0.0030")),
                Period.closed(c2019, c2021.minusDays(1),
                        SegmentRates.of("KOSPI", "0.0010", "KOSDAQ", "0.0025", "KONEX", "0.0010")),
                Period.closed(c2021, c2023.minusDays(1),
                        SegmentRates.of("KOSPI", "0.0008", "KOSDAQ", "0.0023", "KONEX", "0.0010")),
                Period.closed(c2023, c2024.minusDays(1),
                        SegmentRates.of("KOSPI", "0.0005", "KOSDAQ", "0.0020", "KONEX", "0.0010")),
                Period.closed(c2024, c2025.minusDays(1),
                        SegmentRates.of("KOSPI", "0.0003", "KOSDAQ", "0.0018", "KONEX", "0.0010")),
                Period.closed(c2025, c2026.minusDays(1),
                        SegmentRates.of("KOSPI", "0", "KOSDAQ", "0.0015", "KONEX", "0.0010")),
                Period.open(c2026, SegmentRates.of("KOSPI", "0.0005", "KOSDAQ", "0.0020", "KONEX", "0.0010"))));
        // 농어촌특별세는 코스피 양도대금의 0.15%로 이 기간 내내 같다
        Levy rural = new Levy.RateLevy("rural", CostCategory.TAX, Side.SELL, RoundingMode.FLOOR, PeriodTable.of(
                Period.open(KR_TABLE_START, SegmentRates.of("KOSPI", "0.0015", "KOSDAQ", "0", "KONEX", "0"))));
        return new CostModel("KR-DEFAULT", Market.KRX, List.of(commission, stt, rural), DEFAULT_SLIPPAGE,
                s -> boardOf.apply(s).name());
    }

    /** 미국 기본: 위탁수수료 0.25% `[가정]`, 매도 시 SEC fee(센트 올림)·FINRA TAF(센트 반올림, 건당 상한). */
    public static CostModel usDefault() {
        return us("US-DEFAULT", "0.0025");
    }

    /** 토스 계좌용: 위탁수수료 0.1% `[가정]`. 나머지는 US-DEFAULT와 같다. */
    public static CostModel usToss() {
        return us("US-TOSS", "0.001");
    }

    private static CostModel us(String profileId, String commissionRate) {
        Levy commission = Levy.RateLevy.flat(
                "commission", CostCategory.COMMISSION, null, RoundingMode.HALF_UP, US_TABLE_START, commissionRate);
        Levy sec = new Levy.RateLevy("sec", CostCategory.TAX, Side.SELL, RoundingMode.CEILING, PeriodTable.of(
                Period.closed(US_TABLE_START, LocalDate.of(2026, 4, 3), SegmentRates.all("0")),
                // 100만 달러당 20.60달러
                Period.open(LocalDate.of(2026, 4, 4), SegmentRates.all("0.0000206"))));
        Levy taf = new Levy.PerShareLevy("taf", CostCategory.TAX, Side.SELL, RoundingMode.HALF_UP, PeriodTable.of(
                Period.closed(US_TABLE_START, LocalDate.of(2026, 9, 30), PerShareFee.of("0.000195", "9.79")),
                Period.closed(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), PerShareFee.of("0", "0")),
                // Q-004 2.1에서 확인한 구간까지만 둔다. 그 뒤 기간 실험은 비용 표 없음으로 거부된다
                Period.closed(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 12, 31), PerShareFee.of("0.000195", "9.79"))));
        return new CostModel(profileId, Market.US, List.of(commission, sec, taf), DEFAULT_SLIPPAGE,
                s -> SegmentRates.ALL);
    }
}
