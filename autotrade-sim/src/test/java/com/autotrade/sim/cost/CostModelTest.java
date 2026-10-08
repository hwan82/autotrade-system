package com.autotrade.sim.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SIM-07 인수 조건: Q-004 조사 5장 손 계산 예시가 기대값이다. */
class CostModelTest {

    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol AAPL = Symbol.us("AAPL");
    private static final CostModel KR = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final CostModel US = CostModels.usDefault();

    @Test
    void 국내_코스피_2026_1000만원_왕복_명시비용은_23000원() {
        LocalDate d = LocalDate.of(2026, 3, 3);
        BigDecimal price = new BigDecimal("50000");
        FeeBreakdown buy = KR.fees(Side.BUY, SAMSUNG, d, price, 200);
        FeeBreakdown sell = KR.fees(Side.SELL, SAMSUNG, d, price, 200);

        assertThat(buy.commission()).isEqualTo(krw(1500));
        assertThat(buy.tax()).isEqualTo(krw(0));
        assertThat(sell.commission()).isEqualTo(krw(1500));
        assertThat(sell.items()).containsEntry("stt", krw(5000)).containsEntry("rural", krw(15000));
        assertThat(buy.total().plus(sell.total())).isEqualTo(krw(23000));
    }

    @Test
    void 국내_세율은_체결_거래일로_구간을_고른다() {
        BigDecimal price = new BigDecimal("50000");
        // 2025년 코스피: 거래세 0 + 농특세 0.15%
        FeeBreakdown y2025 = KR.fees(Side.SELL, SAMSUNG, LocalDate.of(2025, 12, 31), price, 200);
        assertThat(y2025.items()).containsEntry("stt", krw(0)).containsEntry("rural", krw(15000));
        // 코스닥 2026: 거래세 0.20%, 농특세 없음
        CostModel kosdaq = CostModels.krDefault(s -> KrxBoard.KOSDAQ);
        FeeBreakdown k = kosdaq.fees(Side.SELL, SAMSUNG, LocalDate.of(2026, 1, 1), price, 200);
        assertThat(k.items()).containsEntry("stt", krw(20000)).containsEntry("rural", krw(0));
    }

    @Test
    void 국내는_항목마다_원_미만_절사() {
        // 70,100 × 7 = 490,700. 수수료 73.605 → 73, 거래세 245.35 → 245, 농특세 736.05 → 736
        FeeBreakdown f = KR.fees(Side.SELL, SAMSUNG, LocalDate.of(2026, 3, 3), new BigDecimal("70100"), 7);
        assertThat(f.commission()).isEqualTo(krw(73));
        assertThat(f.items()).containsEntry("stt", krw(245)).containsEntry("rural", krw(736));
        assertThat(f.tax()).isEqualTo(krw(981));
    }

    @Test
    void 미국_2026_11_02_100달러_100주_왕복_명시비용은_50_21달러() {
        LocalDate d = LocalDate.of(2026, 11, 2);
        BigDecimal price = new BigDecimal("100");
        FeeBreakdown buy = US.fees(Side.BUY, AAPL, d, price, 100);
        FeeBreakdown sell = US.fees(Side.SELL, AAPL, d, price, 100);

        assertThat(buy.commission()).isEqualTo(usd("25.00"));
        assertThat(sell.items()).containsEntry("sec", usd("0.21")).containsEntry("taf", usd("0"));
        assertThat(buy.total().plus(sell.total())).isEqualTo(usd("50.21"));
    }

    @Test
    void TAF는_2026_09_30이면_2센트이고_체결_건당_상한이_있다() {
        FeeBreakdown sept = US.fees(Side.SELL, AAPL, LocalDate.of(2026, 9, 30), new BigDecimal("100"), 100);
        assertThat(sept.items()).containsEntry("taf", usd("0.02"));
        // 100,000주 × 0.000195 = 19.5달러 → 상한 9.79
        FeeBreakdown big = US.fees(Side.SELL, AAPL, LocalDate.of(2027, 1, 4), new BigDecimal("1"), 100_000);
        assertThat(big.items()).containsEntry("taf", usd("9.79"));
        // SEC fee는 2026-04-03까지 0
        FeeBreakdown april = US.fees(Side.SELL, AAPL, LocalDate.of(2026, 4, 3), new BigDecimal("100"), 100);
        assertThat(april.items()).containsEntry("sec", usd("0"));
    }

    @Test
    void 토스_프로필은_수수료만_다르다() {
        FeeBreakdown f = CostModels.usToss().fees(Side.BUY, AAPL, LocalDate.of(2026, 11, 2), new BigDecimal("100"), 100);
        assertThat(f.commission()).isEqualTo(usd("10.00"));
    }

    @Test
    void 표_구간이_겹치거나_비거나_열린_구간이_중간이면_로드_실패() {
        SegmentRates r = SegmentRates.all("0.001");
        LocalDate a = LocalDate.of(2026, 1, 1);
        assertThatThrownBy(() -> PeriodTable.of(Period.closed(a, a.plusDays(10), r), Period.open(a.plusDays(10), r)))
                .hasMessageContaining("겹칩니다");
        assertThatThrownBy(() -> PeriodTable.of(Period.closed(a, a.plusDays(10), r), Period.open(a.plusDays(12), r)))
                .hasMessageContaining("빈틈");
        assertThatThrownBy(() -> new PeriodTable<>(List.of(Period.open(a, r), Period.open(a.plusDays(1), r))))
                .hasMessageContaining("열린 구간");
        assertThatThrownBy(() -> SegmentRates.all("-0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 실험_기간이_표_밖이면_덮지_않는다() {
        assertThat(KR.covers(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(KR.covers(LocalDate.of(2024, 12, 31), LocalDate.of(2025, 1, 10))).isFalse();
        assertThat(US.covers(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 2, 1))).isFalse();
        // TAF 표는 Q-004에서 확인한 2028-12-31까지만 덮는다
        assertThat(US.covers(LocalDate.of(2028, 12, 1), LocalDate.of(2028, 12, 31))).isTrue();
        assertThat(US.covers(LocalDate.of(2028, 12, 1), LocalDate.of(2029, 1, 2))).isFalse();
        assertThatThrownBy(() -> KR.fees(Side.SELL, SAMSUNG, LocalDate.of(2024, 6, 3), BigDecimal.TEN, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 표_해시는_실험_기간에_쓰인_구간만_본다() {
        LocalDate from = LocalDate.of(2025, 3, 1);
        LocalDate to = LocalDate.of(2025, 6, 30);
        // 2025년 구간만 쓰는 실험: 2026년 구간 값이 달라도 해시가 같다
        Levy other2026 = new Levy.RateLevy("stt", CostCategory.TAX, Side.SELL, RoundingMode.FLOOR, PeriodTable.of(
                Period.closed(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                        SegmentRates.of("KOSPI", "0", "KOSDAQ", "0.0015", "KONEX", "0.0010")),
                Period.open(LocalDate.of(2026, 1, 1), SegmentRates.of("KOSPI", "0.0009", "KOSDAQ", "0.0020",
                        "KONEX", "0.0010"))));
        CostModel changed = new CostModel("KR-DEFAULT", KR.market(),
                List.of(KR.levies().get(0), other2026, KR.levies().get(2)), KR.slippageRate(), s -> "KOSPI");

        assertThat(changed.tableHash(from, to)).isEqualTo(KR.tableHash(from, to));
        assertThat(changed.tableHash(from, LocalDate.of(2026, 2, 1))).isNotEqualTo(KR.tableHash(from, LocalDate.of(2026, 2, 1)));
        // 내용이 바뀌면 비용 모델 ID도 바뀐다
        assertThat(changed.id()).isNotEqualTo(KR.id()).startsWith("KR-DEFAULT-");
        assertThat(KR.id()).isEqualTo(CostModels.krDefault(s -> KrxBoard.KOSDAQ).id());
        assertThat(KR.tableHash(from, to)).hasSize(64);
    }

    private static Money krw(long v) {
        return Money.of(v, Money.KRW);
    }

    private static Money usd(String v) {
        return Money.of(v, Money.USD);
    }
}
