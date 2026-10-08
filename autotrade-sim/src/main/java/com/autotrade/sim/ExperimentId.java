package com.autotrade.sim;

import java.util.Objects;
import java.util.regex.Pattern;

/** 실험 ID. 결과 폴더 {@code experiments/<실험ID>/}와 DB experiment_id에 같은 값을 쓴다. 예: {@code EXP-20261008-01}. */
public record ExperimentId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,47}");

    public ExperimentId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("실험 ID 형식이 아닙니다: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
