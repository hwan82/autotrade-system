package com.autotrade.broker.toss;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * 토스증권 Open API 토큰({@code POST /oauth2/token}, form, Client Credentials). `[사실]` client_id·client_secret,
 * 클라이언트당 유효 토큰은 하나이고 새 토큰이 이전 토큰을 무효화한다(https://openapi.tossinvest.com/openapi-docs/overview.md,
 * 2026-10-08 확인). 그래서 토스 토큰은 한 프로세스(실전 엔진 보호 감시)만 받아야 한다(ADR-003 2.1).
 * `[가정]` 응답은 OAuth2 표준 access_token·expires_in(초)이다.
 */
final class TossTokenClient implements TokenCache.Fetcher {

    static final URI TOKEN_URI = URI.create("https://openapi.tossinvest.com/oauth2/token");

    private final HttpPort http;
    private final BrokerCredentials credentials;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    TossTokenClient(HttpPort http, BrokerCredentials credentials, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TokenCache.Issued fetch() throws IOException, InterruptedException {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(credentials.appKey(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(credentials.appSecret(), StandardCharsets.UTF_8);
        HttpPort.Response r = http.post(TOKEN_URI, Map.of("Content-Type", "application/x-www-form-urlencoded"), form);
        if (r.status() != 200) {
            throw new IOException("토스 토큰 발급 실패: HTTP " + r.status());
        }
        JsonNode n = mapper.readTree(r.body());
        String token = n.path("access_token").asText("");
        long expiresIn = n.path("expires_in").asLong(0);
        if (token.isEmpty() || expiresIn <= 0) {
            throw new IOException("토스 토큰 응답 형식 오류");
        }
        return new TokenCache.Issued(token, clock.instant().plusSeconds(expiresIn));
    }
}
