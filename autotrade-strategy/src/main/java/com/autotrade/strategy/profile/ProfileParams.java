package com.autotrade.strategy.profile;

import com.autotrade.strategy.StrategyProfile;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

/**
 * 프로필 파라미터의 형 변환과 범위 검사. 읽은 키를 기억해 두었다가 {@link #requireAllUsed()}로 오타·안 쓰는 키를 찾는다
 * (값을 바꿔도 동작이 그대로인 키가 해시에만 섞이면 실험 추적이 어긋난다).
 *
 * <p>한 프로필 해석 전용이며 스레드 안전하지 않다.
 */
public final class ProfileParams {

    /** 동작에 쓰이지 않지만 프로필에 둘 수 있는 키(가설 문장, 실험 설계 메모). */
    private static final Set<String> DOCUMENTATION_PREFIXES = Set.of("hypothesis", "study.", "notes.");

    private final StrategyProfile profile;
    private final SortedMap<String, String> values;
    private final Set<String> used = new TreeSet<>();

    public ProfileParams(StrategyProfile profile) {
        this.profile = profile;
        this.values = profile.parameters();
    }

    public StrategyProfile profile() {
        return profile;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String text(String key) {
        used.add(key);
        String v = values.get(key);
        if (v == null || v.isBlank()) {
            throw fail(key, "값이 필요합니다");
        }
        return v;
    }

    public String text(String key, String fallback) {
        return has(key) ? text(key) : mark(key, fallback);
    }

    /** 0 이상 정수. */
    public int count(String key, int min, int max) {
        String v = text(key);
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                throw fail(key, min + "~" + max + " 범위가 아닙니다: " + v);
            }
            return n;
        } catch (NumberFormatException e) {
            throw fail(key, "정수가 아닙니다: " + v);
        }
    }

    public int count(String key, int min, int max, int fallback) {
        return has(key) ? count(key, min, max) : mark(key, fallback);
    }

    /** 백분율 값(예: {@code 1.5} = 1.5%)을 소수(0.015)로 돌려준다. */
    public BigDecimal percent(String key, String min, String max) {
        return decimal(key, min, max).movePointLeft(2);
    }

    public BigDecimal percent(String key, String min, String max, String fallback) {
        return has(key) ? percent(key, min, max) : mark(key, new BigDecimal(fallback).movePointLeft(2));
    }

    public BigDecimal decimal(String key, String min, String max) {
        String v = text(key);
        BigDecimal d;
        try {
            d = new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw fail(key, "숫자가 아닙니다: " + v);
        }
        if (d.compareTo(new BigDecimal(min)) < 0 || d.compareTo(new BigDecimal(max)) > 0) {
            throw fail(key, min + "~" + max + " 범위가 아닙니다: " + v);
        }
        return d;
    }

    public BigDecimal decimal(String key, String min, String max, String fallback) {
        return has(key) ? decimal(key, min, max) : mark(key, new BigDecimal(fallback));
    }

    /** 거래소 현지 시각 {@code HH:mm}. */
    public LocalTime time(String key) {
        String v = text(key);
        try {
            return LocalTime.parse(v);
        } catch (DateTimeParseException e) {
            throw fail(key, "HH:mm 시각이 아닙니다: " + v);
        }
    }

    public <E extends Enum<E>> E choice(String key, Class<E> type) {
        String v = text(key);
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            throw fail(key, "허용 값이 아닙니다: " + v);
        }
    }

    /** 읽지 않은 키가 있으면 거절한다(문서용 키 제외). */
    public void requireAllUsed() {
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = e.getKey();
            if (!used.contains(k) && DOCUMENTATION_PREFIXES.stream().noneMatch(k::startsWith)) {
                throw fail(k, "이 로직이 쓰지 않는 키입니다(오타 또는 다른 로직의 키)");
            }
        }
    }

    public IllegalArgumentException fail(String key, String message) {
        return new IllegalArgumentException(profile.id() + "@" + profile.version() + " " + key + ": " + message);
    }

    private <T> T mark(String key, T fallback) {
        used.add(key);
        return fallback;
    }
}
