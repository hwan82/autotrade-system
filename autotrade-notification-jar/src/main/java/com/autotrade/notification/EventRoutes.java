package com.autotrade.notification;

import java.time.Instant;
import java.time.ZoneId;
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
 * <p>운영 이벤트(감시·엔진 연결, D-100): 긴급 = {@value #PROCESS_STALE}·{@value #HEARTBEAT_UNREADABLE}·{@value #PROCESS_RESTARTED}·
 * {@value #STOP_REQUEST_ON}·{@value #READING_MISSING}·{@value #CONFIG_WARNING}·{@value #REAL_TRADING_COMMAND}·
 * {@value #NOTIFICATION_CHANNEL}·{@value #CONTROL_CHANNEL}·{@value #REAL_CONFIG_CHANGED}·{@value #ENGINE_STARTED_REAL}·
 * {@value #OPERATOR_LOCKED}(D-142)·{@value #ENGINE_STORE}(D-146), 정보 = {@value #PROCESS_RECOVERED}·{@value #PROCESS_STOPPED}·{@value #STOP_REQUEST_OFF}·
 * {@value #READING_RECOVERED}·{@value #WATCHDOG_STARTED}·{@value #ENGINE_STARTED}.
 *
 * <p>표에 없는 종류는 긴급으로 보낸다. 새 종류를 표에 넣기 전에 보낸 알림이 조용히 묻히지 않게 하는 안전한 쪽 기본값이다.
 */
public final class EventRoutes {

    public static final String ERROR = "error";
    public static final String STOP_LOSS = "stop-loss";
    public static final String RISK_LIMIT = "risk-limit";
    public static final String FORCED_SHUTDOWN = "forced-shutdown";
    public static final String TAKE_PROFIT = "take-profit";
    public static final String MARKET_OPEN_CASH = "market-open-cash";
    public static final String MARKET_CLOSE_SUMMARY = "market-close-summary";

    /** 상주 프로세스 하트비트가 기준보다 오래됨(장애표 #8·#9). */
    public static final String PROCESS_STALE = "process-stale";
    /** 하트비트 파일을 읽거나 해석하지 못함. */
    public static final String HEARTBEAT_UNREADABLE = "heartbeat-unreadable";
    /** 점검 사이에 프로세스가 다시 뜸(죽었다 살아남). */
    public static final String PROCESS_RESTARTED = "process-restarted";
    /** 멈춤·읽기 실패였던 프로세스 하트비트가 돌아옴. */
    public static final String PROCESS_RECOVERED = "process-recovered";
    /** 살아 있던 프로세스가 정상 종료(하트비트 파일 지움). */
    public static final String PROCESS_STOPPED = "process-stopped";
    /** 자원 위험으로 단발 일괄 명령 정지 요청 파일을 만듦(5.2 0번). */
    public static final String STOP_REQUEST_ON = "stop-request-on";
    /** 정지 요청 파일을 지움. */
    public static final String STOP_REQUEST_OFF = "stop-request-off";
    /** 감시가 자원 값을 읽지 못함(D-096). */
    public static final String READING_MISSING = "reading-missing";
    /** 빠졌던 자원 값을 다시 읽음. */
    public static final String READING_RECOVERED = "reading-recovered";
    /** 감시 시작. */
    public static final String WATCHDOG_STARTED = "watchdog-started";
    /** 실전 엔진 시작. */
    public static final String ENGINE_STARTED = "engine-started";
    /** 기동 때 설정 파일 일부를 쓰지 못함. */
    public static final String CONFIG_WARNING = "config-warning";
    /** 실전 제어 명령(실전 활성화·비활성화·PANIC SELL)의 시도와 결과. */
    public static final String REAL_TRADING_COMMAND = "real-trading-command";
    /** 알림 채널 자체의 문제(예: 카카오 재동의 필요). */
    public static final String NOTIFICATION_CHANNEL = "notification-channel";
    /** 실전 제어 명령 채널 문제: 소켓·잠금 파일을 다시 만듦, 다른 엔진이 잡음, 연결 상한, 두 엔진이 같이 돎(D-142 C5·C6). */
    public static final String CONTROL_CHANNEL = "control-channel";
    /** 실행 중 실전 설정(계좌, 계좌번호, 운영자 비밀번호 해시)이 바뀌거나 읽히지 않음(D-142 C11). */
    public static final String REAL_CONFIG_CHANGED = "real-config-changed";
    /** 실전 계좌가 있는 엔진이 시작함(재기동 알림, D-142 C11). 실전 계좌가 없으면 {@link #ENGINE_STARTED}. */
    public static final String ENGINE_STARTED_REAL = "engine-started-real";
    /** 2차 확인이 연속 실패로 잠김(D-142 C10). */
    public static final String OPERATOR_LOCKED = "operator-locked";
    /** 실전 엔진 저장소 문제: 원장 손상·쓰기 실패·쓰기 잠금 교체로 인한 전면 차단, 감사 기록 실패(ADR-015, D-146). */
    public static final String ENGINE_STORE = "engine-store";

    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private static final EventRoutes DEFAULTS = new EventRoutes(Map.of(
            ERROR, Severity.URGENT,
            STOP_LOSS, Severity.URGENT,
            RISK_LIMIT, Severity.URGENT,
            FORCED_SHUTDOWN, Severity.URGENT,
            TAKE_PROFIT, Severity.INFO,
            MARKET_OPEN_CASH, Severity.INFO,
            MARKET_CLOSE_SUMMARY, Severity.INFO))
            .with(PROCESS_STALE, Severity.URGENT)
            .with(HEARTBEAT_UNREADABLE, Severity.URGENT)
            .with(PROCESS_RESTARTED, Severity.URGENT)
            .with(STOP_REQUEST_ON, Severity.URGENT)
            .with(READING_MISSING, Severity.URGENT)
            .with(CONFIG_WARNING, Severity.URGENT)
            .with(REAL_TRADING_COMMAND, Severity.URGENT)
            .with(NOTIFICATION_CHANNEL, Severity.URGENT)
            .with(CONTROL_CHANNEL, Severity.URGENT)
            .with(REAL_CONFIG_CHANGED, Severity.URGENT)
            .with(ENGINE_STARTED_REAL, Severity.URGENT)
            .with(OPERATOR_LOCKED, Severity.URGENT)
            .with(ENGINE_STORE, Severity.URGENT)
            .with(PROCESS_RECOVERED, Severity.INFO)
            .with(PROCESS_STOPPED, Severity.INFO)
            .with(STOP_REQUEST_OFF, Severity.INFO)
            .with(READING_RECOVERED, Severity.INFO)
            .with(WATCHDOG_STARTED, Severity.INFO)
            .with(ENGINE_STARTED, Severity.INFO);

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

    /** 종류로 등급을 정해 알림을 만든다(시각은 {@code zone}으로 보인다). */
    public Notification notification(String kind, String title, String body, Instant occurredAt, ZoneId zone) {
        return new Notification(kind, severityOf(kind), title, body, occurredAt, zone);
    }

    static boolean validKind(String kind) {
        return kind != null && KIND.matcher(kind).matches();
    }
}
