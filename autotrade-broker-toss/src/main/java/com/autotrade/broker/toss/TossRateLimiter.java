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
 * 재충전까지 초), 429일 때 {@code Retry-After}(초)를 준다. 명세에는 한도 숫자가 없다(docs/research/toss-openapi-spec.md 4장).
 *
 * <p>규칙(D-053, PR #29 architect R1 제안을 따름)
 * <ul>
 *   <li>처음에는 그룹마다 초당 {@link #START_PER_SECOND}회 간격으로 부른다.</li>
 *   <li>응답의 {@code X-RateLimit-Limit}이 오면 그 값을 그룹 한도로 쓴다({@link #MAX_PER_SECOND}을 넘지 않음).</li>
 *   <li>{@code X-RateLimit-Remaining}이 0이면 {@code X-RateLimit-Reset}초(최소 1초) 동안 그 그룹을 쉰다.</li>
 *   <li>429면 {@code Retry-After}초(없으면 1초, 최대 {@link #MAX_BLOCK}) 동안 그 그룹을 쉰다. 자동 재시도는 하지 않는다.</li>
 * </ul>
 */
final class TossRateLimiter {

    static final int START_PER_SECOND = 10;
    /** 헤더가 커도 이 이상으로는 올리지 않는다 `[추론]`(T1 공식 문서의 시세 15회에 여유). */
    static final int MAX_PER_SECOND = 20;
    static final Duration MAX_BLOCK = Duration.ofSeconds(60);

    /** 대기. 운영은 {@link Thread#sleep}, 테스트는 기록만 한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private static final class Group {
        int perSecond = START_PER_SECOND;
        Instant next = Instant.MIN;
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Group> groups = new HashMap<>();

    TossRateLimiter(Clock clock, Sleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** 그룹의 다음 호출 시각까지 기다린 뒤 자리를 잡는다. */
    synchronized void acquire(String group) throws InterruptedException {
        Group g = group(group);
        Instant now = clock.instant();
        if (now.isBefore(g.next)) {
            Duration wait = Duration.between(now, g.next);
            sleeper.sleep(wait);
            now = g.next;
        }
        g.next = now.plus(interval(g.perSecond));
    }

    /** 응답 헤더로 그룹 한도와 쉬는 시간을 갱신한다. 헤더 이름은 소문자로 온다(JdkHttpPort). */
    synchronized void onResponse(String group, HttpPort.Response r) {
        Group g = group(group);
        Instant now = clock.instant();
        intHeader(r, "x-ratelimit-limit").filter(v -> v > 0)
                .ifPresent(v -> g.perSecond = Math.min(v, MAX_PER_SECOND));
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
        return groups.computeIfAbsent(name, k -> new Group());
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
