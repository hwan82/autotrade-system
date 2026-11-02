package com.autotrade.broker.kis.order;

import com.autotrade.marketdata.live.TokenCache;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 접근토큰 재발급 하한(D-054 R2). 직전 발급 시도(성공·실패 모두)에서 60초가 지나지 않았으면 발급을 부르지 않고 실패한다.
 * 한국투자 재발급은 1분당 1회이고 발급마다 알림톡이 가므로 `[사실]`(D-054, kis-open-api-spec.md 3장), 토큰 만료 응답이
 * 이어져도 발급 요청과 알림이 쌓이지 않게 한다.
 */
final class KisTokenGate implements TokenCache.Fetcher {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(60);

    private final TokenCache.Fetcher delegate;
    private final Clock clock;
    private Instant lastAttempt;

    KisTokenGate(TokenCache.Fetcher delegate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized TokenCache.Issued fetch() throws IOException, InterruptedException {
        Instant now = clock.instant();
        if (lastAttempt != null && now.isBefore(lastAttempt.plus(MIN_INTERVAL))) {
            throw new IOException("한국투자 접근토큰 재발급 대기(직전 시도 뒤 60초 안)");
        }
        lastAttempt = now;
        return delegate.fetch();
    }
}
