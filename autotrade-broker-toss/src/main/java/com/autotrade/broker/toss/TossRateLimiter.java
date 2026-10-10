package com.autotrade.broker.toss;

import com.autotrade.marketdata.live.HttpPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 토스 Rate Limits Group별 호출 간격 제한. 한 프로세스 안의 모든 토스 호출이 이 객체 하나를 거친다.
 *
 * <p>`[사실]` 토스는 엔드포인트마다 그룹(MARKET_DATA, MARKET_DATA_CHART, AUTH 등 19개)을 두고, 응답 헤더
 * {@code X-RateLimit-Limit}(초당 허용 수), {@code X-RateLimit-Remaining}(남은 토큰), {@code X-RateLimit-Reset}(토큰 1개
 * 재충전까지 초), 429일 때 {@code Retry-After}(초)를 준다. 한도는 <b>클라이언트(Client ID) × 그룹</b> 단위이고 숫자는 토스
 * 개발자 문서 개요의 Rate Limits 표에 있다(2026-10-10 확인, docs/research/toss-openapi-spec.md 4장).
 *
 * <p>규칙(D-053, PR #29 architect R1 제안을 따름)
 * <ul>
 *   <li>처음에는 그룹마다 공식 한도와 초당 {@link #START_PER_SECOND}회 중 작은 값으로 부른다. 표에 없는 그룹은 초당 1회.</li>
 *   <li>응답의 {@code X-RateLimit-Limit}이 오면 그 값을 그룹 한도로 쓰되 공식 한도를 넘지 않는다.</li>
 *   <li>{@code X-RateLimit-Remaining}이 0이면 {@code X-RateLimit-Reset}초(최소 1초) 동안 그 그룹을 쉰다.</li>
 *   <li>429면 {@code Retry-After}초(없으면 1초, 최대 {@link #MAX_BLOCK}) 동안 그 그룹을 쉰다. 자동 재시도는 하지 않는다.</li>
 * </ul>
 */
final class TossRateLimiter {

    static final int START_PER_SECOND = 10;
    /** 표에 없는 그룹의 한도. */
    static final int UNKNOWN_GROUP_PER_SECOND = 1;
    /**
     * `[사실]` 공식 그룹별 초당 한도(토스 개발자 문서 overview.md Rate Limits, 2026-10-10 확인). 헤더 값이 커도 이 값을
     * 넘기지 않는다. ORDER_INFO의 09:00~09:10 피크 한도(3회)는 이 호출기가 그 그룹을 부르지 않아 담지 않았다.
     */
    static final Map<String, Integer> OFFICIAL_PER_SECOND = Map.ofEntries(
            Map.entry("AUTH", 5), Map.entry("ACCOUNT", 1), Map.entry("ASSET", 5), Map.entry("STOCK", 5),
            Map.entry("STOCK_ALL", 1), Map.entry("STOCK_TRADING_TREND", 10), Map.entry("MARKET_INFO", 3),
            Map.entry("MARKET_DATA", 15), Map.entry("MARKET_DATA_CHART", 20), Map.entry("RANKING", 5),
            Map.entry("MARKET_INDICATOR_PRICE", 10), Map.entry("MARKET_INDICATOR", 10),
            Map.entry("MARKET_INDICATOR_CHART", 5), Map.entry("SECTOR", 5), Map.entry("SECTOR_RANKING", 5),
            Map.entry("ORDER", 10), Map.entry("ORDER_HISTORY", 5), Map.entry("ORDER_INFO", 6),
            Map.entry("CONDITIONAL_ORDER", 5), Map.entry("CONDITIONAL_ORDER_HISTORY", 10));
    static final Duration MAX_BLOCK = Duration.ofSeconds(60);

    /** 대기. 운영은 {@link Thread#sleep}, 테스트는 기록만 한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private static final class Group {
        final int cap;
        int perSecond;
        Instant next = Instant.MIN;

        Group(int cap) {
            this.cap = cap;
            this.perSecond = Math.min(cap, START_PER_SECOND);
        }
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Group> groups = new HashMap<>();

    TossRateLimiter(Clock clock, Sleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * 그룹의 다음 호출 자리를 잡고 그 시각까지 기다린다. 자리 예약만 잠금 안에서 하고 기다리기는 잠금 밖에서 하므로,
     * 한 그룹이 쉬는 동안(예: 429로 60초) 다른 그룹 호출은 기다리지 않는다(PR #41 architect F2).
     */
    void acquire(String group) throws InterruptedException, TossApiException {
        acquire(group, null);
    }

    /**
     * {@link #acquire(String)}와 같되, 기다릴 시간이 {@code maxWait}를 넘으면 자리를 잡지 않고 바로
     * {@code RATE_LIMITED}로 실패한다. 보호 폴링이 한도 대기로 오래 멈추지 않게 한다(PR #41 risk-ops F1).
     *
     * @param maxWait 최대 대기. {@code null}이면 상한 없음
     */
    void acquire(String group, Duration maxWait) throws InterruptedException, TossApiException {
        Duration wait;
        synchronized (this) {
            Group g = group(group);
            Instant now = clock.instant();
            Instant slot = now.isBefore(g.next) ? g.next : now;
            wait = Duration.between(now, slot);
            if (maxWait != null && wait.compareTo(maxWait) > 0) {
                throw new TossApiException("한도 대기", TossApiException.Kind.RATE_LIMITED, 0, "local-wait-exceeded",
                        "", wait);
            }
            g.next = slot.plus(interval(g.perSecond));
        }
        if (!wait.isZero()) {
            sleeper.sleep(wait);
        }
    }

    /** 응답 헤더로 그룹 한도와 쉬는 시간을 갱신한다. 헤더 이름은 소문자로 온다(JdkHttpPort). */
    synchronized void onResponse(String group, HttpPort.Response r) {
        Group g = group(group);
        Instant now = clock.instant();
        intHeader(r, "x-ratelimit-limit").filter(v -> v > 0)
                .ifPresent(v -> g.perSecond = Math.min(v, g.cap));
        if (r.status() == 429) {
            Duration d = retryAfter(r);
            g.next = later(g.next, now.plus(d));
            return;
        }
        if (intHeader(r, "x-ratelimit-remaining").filter(v -> v == 0).isPresent()) {
            long reset = intHeader(r, "x-ratelimit-reset").filter(v -> v > 0).orElse(1);
            g.next = later(g.next, now.plus(clamp(Duration.ofSeconds(reset))));
        }
    }

    /** 429 응답의 재시도 권장 시간(없거나 형식이 틀리면 1초). */
    static Duration retryAfter(HttpPort.Response r) {
        return clamp(Duration.ofSeconds(intHeader(r, "retry-after").filter(v -> v > 0).orElse(1)));
    }

    /** 테스트·진단용: 그룹의 현재 초당 한도. */
    synchronized int perSecond(String group) {
        return group(group).perSecond;
    }

    private Group group(String name) {
        Objects.requireNonNull(name, "group");
        return groups.computeIfAbsent(name, k -> new Group(OFFICIAL_PER_SECOND.getOrDefault(k, UNKNOWN_GROUP_PER_SECOND)));
    }

    private static Duration interval(int perSecond) {
        return Duration.ofNanos((1_000_000_000L + perSecond - 1) / perSecond);
    }

    private static Duration clamp(Duration d) {
        return d.compareTo(MAX_BLOCK) > 0 ? MAX_BLOCK : d;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Optional<Integer> intHeader(HttpPort.Response r, String name) {
        String v = r.headers().get(name);
        if (v == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(v.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
