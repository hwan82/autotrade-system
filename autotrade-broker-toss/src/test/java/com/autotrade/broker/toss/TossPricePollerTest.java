package com.autotrade.broker.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.polling.LastPrice;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TossPricePollerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T00:30:00Z");

    private static final class StepClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final String TOKEN = "{\"access_token\":\"TOSS-TOKEN\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    private static final String KR_PRICES = """
            {"result":[{"symbol":"005930","timestamp":"2026-10-08T09:30:00.123+09:00","lastPrice":"72000","currency":"KRW"},
                       {"symbol":"000660","timestamp":"2026-10-08T09:29:59.000+09:00","lastPrice":"bad","currency":"KRW"}]}
            """;

    private final StepClock clock = new StepClock();

    @Test
    void 키가_없으면_설정_안_됨이고_있으면_폴링_능력만_있다() {
        assertThat(TossPlugin.create(BrokerCredentials.none(), new FakeHttp(), clock).status())
                .isEqualTo(BrokerStatus.NOT_CONFIGURED);
        TossPlugin p = TossPlugin.create(new BrokerCredentials("ID", "SECRET"), new FakeHttp(), clock);
        assertThat(p.capabilities()).containsExactly(BrokerCapability.POLLING_QUOTE);
        assertThat(p.marketData(Market.KRX)).isEmpty();
        assertThat(p.pollingQuotes()).isPresent();
    }

    @Test
    void 현재가를_묻고_형식이_틀린_종목은_빼며_값을_지어내지_않는다() throws Exception {
        FakeHttp http = new FakeHttp().respond(200, TOKEN).respond(200, KR_PRICES);
        TossPlugin p = TossPlugin.create(new BrokerCredentials("ID", "SE CRET"), http, clock);
        Map<Symbol, LastPrice> r = p.pollingQuotes().orElseThrow()
                .poll(new TreeSet<>(Set.of(Symbol.krx("005930"), Symbol.krx("000660"), Symbol.krx("035720"))));
        assertThat(r).containsOnlyKeys(Symbol.krx("005930"));
        LastPrice lp = r.get(Symbol.krx("005930"));
        assertThat(lp.price()).isEqualByComparingTo("72000");
        assertThat(lp.priceTime()).isEqualTo(Instant.parse("2026-10-08T00:30:00.123Z"));
        assertThat(http.calls.get(0).body()).isEqualTo("grant_type=client_credentials&client_id=ID&client_secret=SE+CRET");
        assertThat(http.calls.get(1).uri()).hasToString("https://openapi.tossinvest.com/api/v1/prices?symbols=000660,005930,035720");
        assertThat(http.calls.get(1).headers()).containsEntry("Authorization", "Bearer TOSS-TOKEN");
    }

    @Test
    void 시장별로_나눠_묻는다() throws Exception {
        FakeHttp http = new FakeHttp().respond(200, TOKEN).respond(200, "{\"result\":[]}").respond(200, "{\"result\":[]}");
        TossPricePoller poller = (TossPricePoller) TossPlugin.create(new BrokerCredentials("ID", "S"), http, clock)
                .pollingQuotes().orElseThrow();
        poller.poll(new TreeSet<>(Set.of(Symbol.krx("005930"), Symbol.us("AAPL"))));
        assertThat(http.calls).extracting(c -> c.uri().toString()).containsExactly(
                "https://openapi.tossinvest.com/oauth2/token",
                "https://openapi.tossinvest.com/api/v1/prices?symbols=005930",
                "https://openapi.tossinvest.com/api/v1/prices?symbols=AAPL");
    }

    @Test
    void 평균_간격과_순간_허용량을_넘으면_기다리지_않고_거절한다() throws Exception {
        FakeHttp http = new FakeHttp().respond(200, TOKEN);
        for (int i = 0; i < 6; i++) {
            http.respond(200, "{\"result\":[]}");
        }
        TossPricePoller poller = (TossPricePoller) TossPlugin.create(new BrokerCredentials("ID", "S"), http, clock)
                .pollingQuotes().orElseThrow();
        TreeSet<Symbol> one = new TreeSet<>(Set.of(Symbol.krx("005930")));
        for (int i = 0; i < TossPricePoller.BURST; i++) {
            poller.poll(one);
        }
        assertThatThrownBy(() -> poller.poll(one)).isInstanceOf(TossPricePoller.RateLimitedException.class);
        clock.now = NOW.plus(TossPricePoller.MIN_INTERVAL);
        poller.poll(one);
    }

    @Test
    void 레이트리밋_429면_Retry_After까지_묻지_않는다() throws Exception {
        FakeHttp http = new FakeHttp().respond(200, TOKEN).respond(429, "{}", Map.of("retry-after", "3"))
                .respond(200, KR_PRICES);
        TossPricePoller poller = (TossPricePoller) TossPlugin.create(new BrokerCredentials("ID", "S"), http, clock)
                .pollingQuotes().orElseThrow();
        TreeSet<Symbol> one = new TreeSet<>(Set.of(Symbol.krx("005930")));
        assertThatThrownBy(() -> poller.poll(one)).isInstanceOf(TossPricePoller.RateLimitedException.class);
        clock.now = NOW.plusSeconds(2);
        assertThatThrownBy(() -> poller.poll(one)).isInstanceOf(TossPricePoller.RateLimitedException.class);
        assertThat(http.calls).hasSize(2);
        clock.now = NOW.plusSeconds(3);
        assertThat(poller.poll(one)).containsOnlyKeys(Symbol.krx("005930"));
    }

    @Test
    void 인증_실패면_토큰을_버리고_다음에_새로_받는다() throws Exception {
        FakeHttp http = new FakeHttp().respond(200, TOKEN).respond(401, "{}").respond(200, TOKEN).respond(200, KR_PRICES);
        TossPricePoller poller = (TossPricePoller) TossPlugin.create(new BrokerCredentials("ID", "S"), http, clock)
                .pollingQuotes().orElseThrow();
        TreeSet<Symbol> one = new TreeSet<>(Set.of(Symbol.krx("005930")));
        assertThatThrownBy(() -> poller.poll(one)).hasMessageContaining("401").hasMessageNotContaining("TOSS-TOKEN");
        clock.now = NOW.plusSeconds(1);
        poller.poll(one);
        assertThat(http.calls).extracting(FakeHttp.Call::method).containsExactly("POST", "GET", "POST", "GET");
    }
}
