package com.autotrade.sim.cost;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 주당 부과액과 체결 건당 상한(FINRA TAF). 0이면 면제 구간이다.
 *
 * @param maxPerFill 체결 건 하나에 붙는 상한. 상한이 없으면 null
 */
public record PerShareFee(BigDecimal perShare, BigDecimal maxPerFill) implements CostValue {

    public PerShareFee {
        Objects.requireNonNull(perShare, "perShare");
        if (perShare.signum() < 0 || (maxPerFill != null && maxPerFill.signum() < 0)) {
            throw new IllegalArgumentException("주당 부과액과 상한은 0 이상이어야 합니다");
        }
        perShare = perShare.signum() == 0 ? BigDecimal.ZERO : perShare.stripTrailingZeros();
        if (maxPerFill != null) {
            maxPerFill = maxPerFill.signum() == 0 ? BigDecimal.ZERO : maxPerFill.stripTrailingZeros();
        }
    }

    public static PerShareFee of(String perShare, String maxPerFill) {
        return new PerShareFee(new BigDecimal(perShare), maxPerFill == null ? null : new BigDecimal(maxPerFill));
    }

    @Override
    public String canonical() {
        return "{perShare:" + perShare.toPlainString() + ";max:" + (maxPerFill == null ? "" : maxPerFill.toPlainString())
                + "}";
    }
}
