package com.autotrade.broker.toss;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.live.TokenCache;
import java.time.Duration;
import java.time.Instant;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TossPluginTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T00:30:00Z"));
    private final FakeHttp http = new FakeHttp();

    @Test
    void 키가_없으면_아무것도_만들지_않는다() {
        TossPlugin p = TossPlugin.create(BrokerCredentials.none(), http, clock);

        assertThat(p.brokerId()).isEqualTo(BrokerId.TOSS);
        assertThat(p.status()).isEqualTo(BrokerStatus.NOT_CONFIGURED);
        assertThat(p.capabilities()).isEmpty();
        assertThat(p.pollingQuotes()).isEmpty();
        assertThat(p.restClient()).isEmpty();
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 키가_있으면_폴링만_선언하고_실시간과_주문_출구는_없다() {
        TossPlugin p = TossPlugin.create(new BrokerCredentials("id-placeholder", "secret-placeholder"), http, clock);

        assertThat(p.status()).isEqualTo(BrokerStatus.CONFIGURED);
        assertThat(p.capabilities()).containsExactly(BrokerCapability.POLLING_QUOTE);
        assertThat(p.supportedMarkets()).containsExactlyInAnyOrder(Market.KRX, Market.US);
        assertThat(p.marketData(Market.KRX)).isEmpty();
        assertThat(p.marketData(Market.US)).isEmpty();
        assertThat(p.pollingQuotes()).get().extracting(s -> s.sourceId()).isEqualTo("TOSS-POLL");
        assertThat(p.orderGateway(new AccountRef(new AccountId("TOSS-KRX-1"), BrokerId.TOSS, Market.KRX))).isEmpty();
        assertThat(p.orderGateway(new AccountRef(new AccountId("TOSS-US-1"), BrokerId.TOSS, Market.US))).isEmpty();
        assertThat(http.calls).isEmpty(); // 토큰은 처음 호출할 때 받는다
    }

    @Test
    void 상태는_호출기_결과를_따른다() throws Exception {
        TossRestClient client = new TossRestClient(http,
                () -> new TokenCache.Issued("tok", clock.instant().plus(Duration.ofDays(1))), clock,
                new TossRateLimiter(clock, clock::advance));
        TossPlugin p = TossPlugin.of(client, clock);
        assertThat(p.status()).isEqualTo(BrokerStatus.CONFIGURED);

        http.respond(200, "{\"result\":[]}");
        p.pollingQuotes().orElseThrow().poll(new TreeSet<>(java.util.List.of(Symbol.krx("005930"))));
        assertThat(p.status()).isEqualTo(BrokerStatus.ACTIVE);

        http.respond(401, "{\"error\":{\"code\":\"token-revoked\"}}");
        try {
            p.pollingQuotes().orElseThrow().poll(new TreeSet<>(java.util.List.of(Symbol.krx("005930"))));
        } catch (TossApiException expected) {
            // 다른 곳에서 토큰을 다시 받음
        }
        assertThat(p.status()).isEqualTo(BrokerStatus.DEGRADED);
    }
}
