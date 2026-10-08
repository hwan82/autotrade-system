package com.autotrade.sim.cost;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 세그먼트별 요율. 국내 거래세는 소속부(KOSPI·KOSDAQ·KONEX)마다 다르고, 미국 수수료처럼 하나뿐이면 {@link #ALL}을 쓴다.
 * 요율은 문자열로 받아 BigDecimal로 둔다(double을 거치지 않음).
 */
public record SegmentRates(SortedMap<String, BigDecimal> rates) implements CostValue {

    /** 세그먼트 구분이 없는 항목의 키. */
    public static final String ALL = "ALL";

    public SegmentRates {
        TreeMap<String, BigDecimal> copy = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> e : rates.entrySet()) {
            BigDecimal r = Objects.requireNonNull(e.getValue(), e.getKey());
            if (r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("요율은 0 이상 1 이하여야 합니다: " + e.getKey() + "=" + r);
            }
            copy.put(e.getKey(), r.signum() == 0 ? BigDecimal.ZERO : r.stripTrailingZeros());
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("요율이 하나 이상 필요합니다");
        }
        rates = Collections.unmodifiableSortedMap(copy);
    }

    public static SegmentRates all(String rate) {
        return new SegmentRates(new TreeMap<>(Map.of(ALL, new BigDecimal(rate))));
    }

    /** 키·값을 번갈아 받는다. 예: {@code of("KOSPI", "0.0005", "KOSDAQ", "0.0020")}. */
    public static SegmentRates of(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("키와 값의 짝이 맞지 않습니다");
        }
        TreeMap<String, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], new BigDecimal(keyValues[i + 1]));
        }
        return new SegmentRates(m);
    }

    /** 세그먼트의 요율. 표에 없으면 {@link #ALL}, 그것도 없으면 설정 오류로 예외. */
    public BigDecimal rateFor(String segment) {
        BigDecimal r = rates.get(segment);
        if (r == null) {
            r = rates.get(ALL);
        }
        if (r == null) {
            throw new IllegalStateException("비용 표에 이 세그먼트의 요율이 없습니다: " + segment);
        }
        return r;
    }

    @Override
    public String canonical() {
        StringBuilder sb = new StringBuilder("{");
        rates.forEach((k, v) -> sb.append(k).append(':').append(v.toPlainString()).append(';'));
        return sb.append('}').toString();
    }
}
