package com.autotrade.marketdata.live;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 증권사 접근 토큰 캐시. 만료 {@code refreshMargin} 전에 새로 받는다. 토큰 값은 로그·예외 메시지에 넣지 않는다.
 * 증권사마다 토큰 재발급 제한이 있어(한국투자 1분당 1회, 토스는 새 토큰이 이전 토큰을 무효화) 필요할 때만 받는다.
 */
public final class TokenCache {

    @FunctionalInterface
    public interface Fetcher {
        Issued fetch() throws IOException, InterruptedException;
    }

    /** 발급된 토큰. {@link #toString()}은 값을 가린다. */
    public record Issued(String value, Instant expiresAt) {
        public Issued {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (value.isBlank()) {
                throw new IllegalArgumentException("빈 토큰");
            }
        }

        @Override
        public String toString() {
            return "Issued[****, expiresAt=" + expiresAt + "]";
        }
    }

    private final Fetcher fetcher;
    private final Clock clock;
    private final Duration refreshMargin;
    private Issued cached;

    public TokenCache(Fetcher fetcher, Clock clock, Duration refreshMargin) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshMargin = Objects.requireNonNull(refreshMargin, "refreshMargin");
    }

    public synchronized String get() throws IOException, InterruptedException {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(cached.expiresAt().minus(refreshMargin))) {
            cached = fetcher.fetch();
        }
        return cached.value();
    }

    public synchronized void invalidate() {
        cached = null;
    }
}
