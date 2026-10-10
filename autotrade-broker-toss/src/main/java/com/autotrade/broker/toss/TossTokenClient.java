package com.autotrade.broker.toss;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.CredentialsRejectedException;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 토스 액세스 토큰 발급({@code POST /oauth2/token}, Rate Limits Group {@code AUTH}).
 *
 * <p>`[사실]` 사용자 제공 토스 OpenAPI 명세 v1.2.24(2026-10-10 수신, docs/research/toss-openapi-spec.md 2장)
 * <ul>
 *   <li>본문 {@code application/x-www-form-urlencoded}: {@code grant_type=client_credentials}, {@code client_id},
 *       {@code client_secret}. 응답은 OAuth2 표준 {@code access_token}, {@code token_type}(Bearer),
 *       {@code expires_in}(초, 예시 86400). refresh token은 없다.</li>
 *   <li><b>클라이언트당 유효한 토큰은 1개이고, 다시 발급하면 이전 토큰은 즉시 무효가 된다.</b></li>
 *   <li>400 형식 오류({@code invalid_request}, {@code unsupported_grant_type}), 401 {@code invalid_client},
 *       403 {@code access_denied}(허용 IP 목록에 없는 IP), 429 한도 초과.</li>
 * </ul>
 * 400·401·403은 같은 키로 다시 해도 풀리지 않으므로 {@link CredentialsRejectedException}으로 알린다(ADR-003 4장 인증 실패
 * 규칙). 메시지에는 상태 코드와 고정 안내만 넣는다.
 */
final class TossTokenClient implements TokenCache.Fetcher {

    static final URI TOKEN_URI = URI.create("https://openapi.tossinvest.com/oauth2/token");
    static final String GROUP = "AUTH";

    private final HttpPort http;
    private final BrokerCredentials credentials;
    private final Clock clock;
    private final TossRateLimiter limiter;
    private final ObjectMapper mapper = new ObjectMapper();

    TossTokenClient(HttpPort http, BrokerCredentials credentials, Clock clock, TossRateLimiter limiter) {
        this.http = Objects.requireNonNull(http, "http");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    public TokenCache.Issued fetch() throws IOException, InterruptedException {
        String body = "grant_type=client_credentials"
                + "&client_id=" + form(credentials.appKey())
                + "&client_secret=" + form(credentials.appSecret());
        limiter.acquire(GROUP);
        HttpPort.Response r = http.post(TOKEN_URI,
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), body);
        limiter.onResponse(GROUP, r);
        switch (r.status()) {
            case 200 -> {
                return parse(r);
            }
            case 400 -> throw new CredentialsRejectedException(
                    "토스 토큰 발급 거절: HTTP 400(요청 형식 또는 client_id·client_secret 형식 확인)");
            case 401 -> throw new CredentialsRejectedException(
                    "토스 토큰 발급 거절: HTTP 401(client_id·client_secret 값 또는 클라이언트 비활성 확인)");
            case 403 -> throw new CredentialsRejectedException(
                    "토스 토큰 발급 거절: HTTP 403(토스증권 WTS 설정 > Open API > 허용 IP 관리에 이 PC의 IP 등록 확인)");
            case 429 -> throw new TossApiException("토큰 발급", TossApiException.Kind.RATE_LIMITED, 429, "", "",
                    TossRateLimiter.retryAfter(r));
            default -> throw new IOException("토스 토큰 발급 실패: HTTP " + r.status());
        }
    }

    private TokenCache.Issued parse(HttpPort.Response r) throws IOException {
        JsonNode n;
        try {
            n = mapper.readTree(r.body());
        } catch (IOException e) {
            throw new IOException("토스 토큰 응답 형식 오류"); // 원인 예외에 본문 조각이 들어갈 수 있어 붙이지 않는다
        }
        String token = n == null ? "" : n.path("access_token").asText("");
        long expiresIn = n == null ? 0 : n.path("expires_in").asLong(0);
        if (token.isBlank() || expiresIn <= 0) {
            throw new IOException("토스 토큰 응답 형식 오류");
        }
        return new TokenCache.Issued(token, clock.instant().plus(Duration.ofSeconds(expiresIn)));
    }

    private static String form(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
