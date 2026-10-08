package com.autotrade.strategy;

import com.autotrade.domain.market.Market;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 전략 프로필: 매매 기법 하나를 정의하는 불변 데이터(종목 선정, 진입, 비중, 청산, 한도 파라미터).
 * 실행 로직(신호 생성)은 프로필을 입력으로 받는 별도 인터페이스이며 3·4단계에서 정의한다.
 *
 * <p>{@link #profileHash()}는 내용 기반 SHA-256이다. 실험 결과(virtual_trade_log.profile_hash)에 남겨
 * "어떤 설정으로 나온 결과인지"를 추적한다. 파라미터 입력 순서와 무관하고, 값 하나만 달라도 바뀐다.
 *
 * @param parameters 키는 소문자·숫자·점·하이픈, 값은 문자열(숫자도 문자열로 저장해 표현이 흔들리지 않게 한다)
 */
public record StrategyProfile(
        String id, int version, Market market, TradingStyle style, SortedMap<String, String> parameters) {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern PARAM_KEY = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

    public StrategyProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(style, "style");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("프로필 ID 형식이 아닙니다: " + id);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version은 1 이상이어야 합니다: " + version);
        }
        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            if (!PARAM_KEY.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("파라미터 키 형식이 아닙니다: " + e.getKey());
            }
            copy.put(e.getKey(), Objects.requireNonNull(e.getValue(), e.getKey()));
        }
        parameters = Collections.unmodifiableSortedMap(copy);
    }

    /** 내용 기반 해시(소문자 hex 64자). */
    public String profileHash() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "id", id);
        append(canonical, "version", Integer.toString(version));
        append(canonical, "market", market.name());
        append(canonical, "style", style.name());
        parameters.forEach((k, v) -> append(canonical, "p." + k, v));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    /** 길이 접두 인코딩. 구분자를 값에 넣어 서로 다른 프로필이 같은 문자열이 되는 일을 막는다. */
    private static void append(StringBuilder sb, String key, String value) {
        sb.append(key.length()).append(':').append(key).append('=')
                .append(value.length()).append(':').append(value).append('\n');
    }
}
