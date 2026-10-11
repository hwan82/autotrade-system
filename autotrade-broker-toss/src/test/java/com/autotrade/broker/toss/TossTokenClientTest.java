package com.autotrade.broker.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.CredentialsRejectedException;
import com.autotrade.marketdata.live.TokenCache;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TossTokenClientTest {

    private static final Instant T0 = Instant.parse("2026-10-10T00:00:00Z");
    /** 자리표시자. 실제 키 형식과 무관하며, 인코딩 확인용 특수문자를 넣었다. */
    private static final BrokerCredentials CREDS = new BrokerCredentials("client-id-placeholder", "secret+&=placeholder");

    private final MutableClock clock = new MutableClock(T0);
    private final FakeHttp http = new FakeHttp();
    private final TossTokenClient client =
            new TossTokenClient(http, CREDS, clock, new TossRateLimiter(clock, clock::advance));

    @Test
    void 폼_본문으로_요청하고_만료_시각을_expires_in으로_계산한다() throws Exception {
        http.respond(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":86400}");

        TokenCache.Issued issued = client.fetch();

        assertThat(issued.value()).isEqualTo("tok-1");
        assertThat(issued.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(1)));
        FakeHttp.Call call = http.calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri()).hasToString("https://openapi.tossinvest.com/oauth2/token");
        assertThat(call.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded");
        assertThat(call.body()).isEqualTo(
                "grant_type=client_credentials&client_id=client-id-placeholder&client_secret=secret%2B%26%3Dplaceholder");
        assertThat(issued.toString()).doesNotContain("tok-1");
    }

    @Test
    void 자격_증명_거절은_400_401_403_모두_CredentialsRejected이고_비밀값을_싣지_않는다() {
        for (int status : new int[] {400, 401, 403}) {
            http.respond(status, "{\"error\":\"invalid_client\",\"error_description\":\"secret+&=placeholder\"}");
            assertThatThrownBy(client::fetch)
                    .isInstanceOf(CredentialsRejectedException.class)
                    .hasMessageContaining("HTTP " + status)
                    .hasMessageNotContaining("placeholder");
        }
    }

    @Test
    void 허용_IP_차단은_조치_방법을_안내한다() {
        http.respond(403, "{\"error\":\"access_denied\",\"error_description\":\"IP address not allowed\"}");

        assertThatThrownBy(client::fetch).hasMessageContaining("허용 IP");
    }

    @Test
    void 한도_초과는_재시도_권장_시간과_함께_알린다() {
        http.respond(429, "{\"error\":{\"code\":\"rate-limit-exceeded\"}}", Map.of("retry-after", "3"));

        assertThatThrownBy(client::fetch)
                .isInstanceOfSatisfying(TossApiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(TossApiException.Kind.RATE_LIMITED);
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));
                });
    }

    @Test
    void 응답에_토큰이나_만료가_없으면_본문을_싣지_않고_실패한다() {
        http.respond(200, "{\"access_token\":\"\",\"expires_in\":86400}");
        assertThatThrownBy(client::fetch).isInstanceOf(IOException.class)
                .isNotInstanceOf(CredentialsRejectedException.class);

        http.respond(200, "{\"access_token\":\"tok-x\"}");
        assertThatThrownBy(client::fetch).hasMessageNotContaining("tok-x");

        http.respond(200, "not json tok-y");
        assertThatThrownBy(client::fetch).hasMessageNotContaining("tok-y");
    }
}
