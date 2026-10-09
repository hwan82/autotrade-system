package com.autotrade.sim.cost;

import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 비용 항목 하나(수수료, 거래세, 농특세, SEC fee, TAF). 체결 건 하나마다 따로 계산하고 따로 반올림한다(Q-004 조사 4.3).
 * 시장별 차이는 항목 구성으로만 나타나며, 체결 엔진은 항목 종류를 분기하지 않는다(OVS-02).
 */
public sealed interface Levy permits Levy.RateLevy, Levy.PerShareLevy {

    /** 항목 이름. 예: {@code commission}, {@code stt}, {@code rural}, {@code sec}, {@code taf}. */
    String name();

    CostCategory category();

    /** 부과 방향. null이면 매수·매도 모두. */
    Side appliesTo();

    RoundingMode rounding();

    PeriodTable<? extends CostValue> table();

    /** 반올림 전 금액. {@code segment}는 세그먼트 요율 항목만 쓴다. */
    BigDecimal rawAmount(LocalDate tradeDate, String segment, BigDecimal notional, long quantity);

    default boolean applies(Side side) {
        return appliesTo() == null || appliesTo() == side;
    }

    default String canonicalHeader() {
        return name() + "|" + category() + "|" + (appliesTo() == null ? "BOTH" : appliesTo()) + "|" + rounding();
    }

    /** 체결 금액 × 요율. */
    record RateLevy(
            String name, CostCategory category, Side appliesTo, RoundingMode rounding, PeriodTable<SegmentRates> table)
            implements Levy {

        public RateLevy {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(rounding, "rounding");
            Objects.requireNonNull(table, "table");
        }

        /** 기간 구분이 없는 단일 요율 항목(예: 위탁수수료). */
        public static RateLevy flat(
                String name, CostCategory category, Side appliesTo, RoundingMode rounding, LocalDate from, String rate) {
            return new RateLevy(name, category, appliesTo, rounding,
                    new PeriodTable<>(List.of(Period.open(from, SegmentRates.all(rate)))));
        }

        @Override
        public BigDecimal rawAmount(LocalDate tradeDate, String segment, BigDecimal notional, long quantity) {
            return notional.multiply(table.at(tradeDate).rateFor(segment));
        }
    }

    /** 수량 × 주당 부과액, 체결 건당 상한 적용. */
    record PerShareLevy(
            String name, CostCategory category, Side appliesTo, RoundingMode rounding, PeriodTable<PerShareFee> table)
            implements Levy {

        public PerShareLevy {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(rounding, "rounding");
            Objects.requireNonNull(table, "table");
        }

        @Override
        public BigDecimal rawAmount(LocalDate tradeDate, String segment, BigDecimal notional, long quantity) {
            PerShareFee fee = table.at(tradeDate);
            BigDecimal raw = fee.perShare().multiply(BigDecimal.valueOf(quantity));
            return fee.maxPerFill() != null && raw.compareTo(fee.maxPerFill()) > 0 ? fee.maxPerFill() : raw;
        }
    }
}
