package com.autotrade.selection.profile;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 선정 프로필: "어떤 종목을 매매 대상 후보로 고를지"를 정의하는 불변 데이터(STR-15, ADR-007 2.1). 매매 지표(진입·청산)는
 * {@link com.autotrade.strategy.StrategyProfile}이고, 실험 계좌 하나는 둘의 조합({@link Combination})이다(D-041).
 *
 * <p>예약 키(이 클래스가 검사한다. 나머지 {@code filter.*}·{@code gate.<nn>.*}·{@code score.*} 키의 이름과 값은 선정 조건
 * 카탈로그가 정하고 검사한다)
 * <ul>
 *   <li>{@code hypothesis}(필수): 가설 한 문장(STR-02)</li>
 *   <li>{@code refresh}(필수): {@link SelectionRefresh} 이름. 스타일과 맞아야 한다</li>
 *   <li>{@code max-symbols}(필수): 1 이상 정수. 선정 결과 종목 수 상한</li>
 *   <li>{@code at}({@link SelectionRefresh#INTRADAY_AT}만 필수, 다른 갱신 주기에는 거부): 장중 순위 시각 {@code HH:mm}(거래소 현지).
 *       정규장 시작 뒤 ~ 접속 매매 끝 이내(KRX 09:01~14:30, 미국 09:31~15:00)여야 한다(D-127)</li>
 * </ul>
 *
 * <p>{@link #selectionHash()}는 전략 프로필 해시와 같은 길이 접두 인코딩의 SHA-256이고, 첫 줄에
 * {@code kind=selection-profile}을 넣어 같은 ID·파라미터의 전략 프로필과 해시가 겹치지 않게 한다. 해시에 {@code Market}·
 * {@link TradingStyle}의 enum 이름이 들어가므로 이 이름들은 바꾸지 않는다(골든 테스트가 지킴).
 *
 * @param parameters 키는 소문자·숫자·점·하이픈, 값은 문자열(숫자도 문자열)
 */
public record SelectionProfile(
        String id, int version, Market market, TradingStyle style, SortedMap<String, String> parameters) {

    public static final String HYPOTHESIS = "hypothesis";
    public static final String REFRESH = "refresh";
    public static final String MAX_SYMBOLS = "max-symbols";
    public static final String AT = "at";

    // 형식은 StrategyProfile과 같다(ADR-007 2.1)
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern PARAM_KEY = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

    public SelectionProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(style, "style");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("선정 프로필 ID 형식이 아닙니다: " + id);
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
        String hypothesis = parameters.get(HYPOTHESIS);
        if (hypothesis == null || hypothesis.isBlank()) {
            throw new IllegalArgumentException("선정 프로필에 가설(hypothesis)이 필요합니다: " + id);
        }
        SelectionRefresh refresh = parseRefresh(parameters.get(REFRESH), id);
        if (!refresh.allows(style)) {
            throw new IllegalArgumentException("갱신 주기 " + refresh + "는 " + style + " 선정에 쓸 수 없습니다"
                    + "(단타는 PRE_OPEN_DAILY·PRE_OPEN_DAILY_LAG1·INTRADAY_AT만, 스윙·중기는 장 마감 뒤 갱신만): " + id);
        }
        parseMaxSymbols(parameters.get(MAX_SYMBOLS), id);
        parseAt(parameters.get(AT), refresh, market, id);
    }

    public SelectionRefresh refresh() {
        return parseRefresh(parameters.get(REFRESH), id);
    }

    /** 장중 순위 시각({@link SelectionRefresh#INTRADAY_AT}만). 다른 갱신 주기면 비어 있다. */
    public Optional<LocalTime> at() {
        return Optional.ofNullable(parseAt(parameters.get(AT), refresh(), market, id));
    }

    public int maxSymbols() {
        return parseMaxSymbols(parameters.get(MAX_SYMBOLS), id);
    }

    /** 내용 기반 해시(소문자 hex 64자). {@code based-on}·{@code created-at}·{@code note}는 넣지 않는다. */
    public String selectionHash() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "kind", "selection-profile");
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

    /** 결과·보고서에 쓰는 {@code id@version}. */
    public String key() {
        return id + "@" + version;
    }

    private static SelectionRefresh parseRefresh(String value, String id) {
        if (value == null) {
            throw new IllegalArgumentException("선정 프로필에 갱신 주기(refresh)가 필요합니다: " + id);
        }
        try {
            return SelectionRefresh.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 갱신 주기입니다: " + value + " (" + id + ")", e);
        }
    }

    private static int parseMaxSymbols(String value, String id) {
        if (value == null || !value.matches("[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("max-symbols는 1 이상 정수여야 합니다: " + value + " (" + id + ")");
        }
        return Integer.parseInt(value);
    }

    /**
     * 장중 순위 시각 {@code at}의 허용 범위(거래소 현지 시각, 양 끝 포함).
     *
     * @param first 정규장 시작 + 1분(그 전에는 모은 체결이 없음)
     * @param last 순위 뒤 신규 진입할 구간을 남기는 끝. 예시 단타 전략의 장 마감 청산 {@code exit.eod-time} 15:10보다 40분 넘게
     *     앞이다(strategy-researcher PR #200 R1)
     */
    public record AtRange(LocalTime first, LocalTime last) {
        public AtRange {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(last, "last");
            if (last.isBefore(first)) {
                throw new IllegalArgumentException("at 범위 끝이 시작보다 앞입니다: " + first + " ~ " + last);
            }
        }

        public boolean contains(LocalTime at) {
            return !at.isBefore(first) && !at.isAfter(last);
        }
    }

    /**
     * 시장마다 {@code at} 허용 범위. 시장 분기는 이 표 한 곳에만 둔다(architect PR #200 R2). 정규장 시작과 맞는지는 시험이 거래소
     * 캘린더({@code ExchangeCalendar}, MarketRules가 쓰는 정규장)와 묶어 본다. 특수일 개장 지연(예: 수능일 10:00)은 여기서 다루지
     * 않는다: 그날 {@code at} 전 정규장이 없으면 실행 때 후보 0이 된다(D-127 결정 5).
     */
    public static final Map<Market, AtRange> AT_RANGES = Map.of(
            Market.KRX, new AtRange(LocalTime.of(9, 1), LocalTime.of(14, 30)),
            Market.US, new AtRange(LocalTime.of(9, 31), LocalTime.of(15, 0)));

    private static LocalTime parseAt(String value, SelectionRefresh refresh, Market market, String id) {
        if (refresh != SelectionRefresh.INTRADAY_AT) {
            if (value != null) {
                throw new IllegalArgumentException("at(장중 순위 시각)은 INTRADAY_AT 선정에만 씁니다: " + id);
            }
            return null;
        }
        if (value == null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw new IllegalArgumentException("INTRADAY_AT 선정에는 at(HH:mm, 거래소 현지 시각)이 필요합니다: " + value + " (" + id + ")");
        }
        LocalTime at = LocalTime.parse(value);
        AtRange range = AT_RANGES.get(market);
        if (range == null) {
            throw new IllegalArgumentException("이 시장은 장중 순위 시각 범위가 정해지지 않았습니다: " + market + " (" + id + ")");
        }
        if (!range.contains(at)) {
            throw new IllegalArgumentException("at은 " + range.first() + " ~ " + range.last() + " 사이여야 합니다: " + value + " ("
                    + id + ")");
        }
        return at;
    }

    /** 길이 접두 인코딩. {@code StrategyProfile.profileHash()}와 같은 방식이다. */
    private static void append(StringBuilder sb, String key, String value) {
        sb.append(key.length()).append(':').append(key).append('=')
                .append(value.length()).append(':').append(value).append('\n');
    }
}
