package com.autotrade.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 이벤트 종류 → 등급 매핑표(MON-06, 기획서 원본 v2·v4, D-099).
 *
 * <table>
 *   <caption>기본 매핑</caption>
 *   <tr><th>종류</th><th>등급</th></tr>
 *   <tr><td>{@value #ERROR}</td><td>긴급</td></tr>
 *   <tr><td>{@value #STOP_LOSS}</td><td>긴급</td></tr>
 *   <tr><td>{@value #RISK_LIMIT}</td><td>긴급</td></tr>
 *   <tr><td>{@value #FORCED_SHUTDOWN}</td><td>긴급</td></tr>
 *   <tr><td>{@value #TAKE_PROFIT}</td><td>정보</td></tr>
 *   <tr><td>{@value #MARKET_OPEN_CASH}</td><td>정보</td></tr>
 *   <tr><td>{@value #MARKET_CLOSE_SUMMARY}</td><td>정보</td></tr>
 * </table>
 *
 * 표에 없는 종류는 긴급으로 보낸다. 새 종류를 표에 넣기 전에 보낸 알림이 조용히 묻히지 않게 하는 안전한 쪽 기본값이다.
 */
public final class EventRoutes {

    public static final String ERROR = "error";
    public static final String STOP_LOSS = "stop-loss";
    public static final String RISK_LIMIT = "risk-limit";
    public static final String FORCED_SHUTDOWN = "forced-shutdown";
    public static final String TAKE_PROFIT = "take-profit";
    public static final String MARKET_OPEN_CASH = "market-open-cash";
    public static final String MARKET_CLOSE_SUMMARY = "market-close-summary";

    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private static final EventRoutes DEFAULTS = new EventRoutes(Map.of(
            ERROR, Severity.URGENT,
            STOP_LOSS, Severity.URGENT,
            RISK_LIMIT, Severity.URGENT,
            FORCED_SHUTDOWN, Severity.URGENT,
            TAKE_PROFIT, Severity.INFO,
            MARKET_OPEN_CASH, Severity.INFO,
            MARKET_CLOSE_SUMMARY, Severity.INFO));

    private final Map<String, Severity> table;

    private EventRoutes(Map<String, Severity> table) {
        this.table = Map.copyOf(table);
    }

    /** 위 기본 매핑표. */
    public static EventRoutes defaults() {
        return DEFAULTS;
    }

    /** 기본 표에 종류를 더하거나 바꾼 표(재사용하는 쪽용). 종류 형식이 틀리면 거부한다. */
    public EventRoutes with(String kind, Severity severity) {
        Objects.requireNonNull(severity, "severity");
        if (!validKind(kind)) {
            throw new IllegalArgumentException("알림 종류는 소문자·숫자·-로 1~40자여야 합니다");
        }
        Map<String, Severity> copy = new java.util.HashMap<>(table);
        copy.put(kind, severity);
        return new EventRoutes(copy);
    }

    /** 종류의 등급. 표에 없으면 긴급. */
    public Severity severityOf(String kind) {
        Objects.requireNonNull(kind, "kind");
        return table.getOrDefault(kind, Severity.URGENT);
    }

    /** 표에 있는 종류인지. */
    public boolean known(String kind) {
        return table.containsKey(kind);
    }

    /** 종류로 등급을 정해 알림을 만든다. */
    public Notification notification(String kind, String title, String body, Instant occurredAt) {
        return new Notification(kind, severityOf(kind), title, body, occurredAt);
    }

    static boolean validKind(String kind) {
        return kind != null && KIND.matcher(kind).matches();
    }
}
