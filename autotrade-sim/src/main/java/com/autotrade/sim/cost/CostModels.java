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

    /** 표의 시작일. 이 날 이전 데이터로는 실험을 시작할 수 없다(SIM-07). 2024년 이전은 세율 연혁 확인 후 추가. */
    static final LocalDate KR_TABLE_START = LocalDate.of(2025, 1, 1);
    /** 미국 표의 시작일. TAF 구간이 2026-01-01부터 확인되어 있다(Q-004 조사 2.1). */
    static final LocalDate US_TABLE_START = LocalDate.of(2026, 1, 1);

    /** D-021 슬리피지 기본값 0.15%(체결가 기반 체결에만 쓴다). */
    public static final BigDecimal DEFAULT_SLIPPAGE = new BigDecimal("0.0015");

    private CostModels() {
    }

    /**
     * 국내 기본: 위탁수수료 0.015% `[가정]`, 매도 시 증권거래세·농특세(2025년, 2026-01-01~). 각각 원 미만 절사.
     *
     * @param boardOf 종목 → 체결 당시 소속부. 시점별 이력은 Q-012(data-engineer)
     */
    public static CostModel krDefault(Function<Symbol, KrxBoard> boardOf) {
        LocalDate y2026 = LocalDate.of(2026, 1, 1);
        LocalDate end2025 = LocalDate.of(2025, 12, 31);
        Levy commission = Levy.RateLevy.flat(
                "commission", CostCategory.COMMISSION, null, RoundingMode.FLOOR, KR_TABLE_START, "0.00015");
        Levy stt = new Levy.RateLevy("stt", CostCategory.TAX, Side.SELL, RoundingMode.FLOOR, PeriodTable.of(
                Period.closed(KR_TABLE_START, end2025,
                        SegmentRates.of("KOSPI", "0", "KOSDAQ", "0.0015", "KONEX", "0.0010")),
                Period.open(y2026, SegmentRates.of("KOSPI", "0.0005", "KOSDAQ", "0.0020", "KONEX", "0.0010"))));
        Levy rural = new Levy.RateLevy("rural", CostCategory.TAX, Side.SELL, RoundingMode.FLOOR, PeriodTable.of(
                Period.closed(KR_TABLE_START, end2025, SegmentRates.of("KOSPI", "0.0015", "KOSDAQ", "0", "KONEX", "0")),
                Period.open(y2026, SegmentRates.of("KOSPI", "0.0015", "KOSDAQ", "0", "KONEX", "0"))));
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
