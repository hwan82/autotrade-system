package com.autotrade.broker.kis;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 한국투자 실시간 접속키 발급({@code POST /oauth2/Approval}). `[사실]` 본문 grant_type·appkey·secretkey, 응답 approval_key,
 * 공식 예제는 24시간(86400초)마다 다시 받는다(koreainvestment/open-trading-api examples_user/kis_auth.py, 2026-10-08 확인).
 * `[가정]` 응답에 만료 시각이 없어 발급 후 24시간을 만료로 본다.
 * 발급 실패 해석은 {@link KisTokenClient#issueFailure}와 같다(401·403은 자격 증명 거절, 단 재발급 제한 코드는 제외).
 */
final class KisApprovalClient implements TokenCache.Fetcher {

    static final URI APPROVAL_URI = URI.create("https://openapi.koreainvestment.com:9443/oauth2/Approval");
    static final Duration VALIDITY = Duration.ofHours(24);

    private final HttpPort http;
    private final BrokerCredentials credentials;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    KisApprovalClient(HttpPort http, BrokerCredentials credentials, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TokenCache.Issued fetch() throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of(
                "grant_type", "client_credentials",
                "appkey", credentials.appKey(),
                "secretkey", credentials.appSecret()));
        HttpPort.Response r = http.post(APPROVAL_URI, Map.of("Content-Type", "application/json; charset=utf-8"), body);
        if (r.status() != 200) {
            throw KisTokenClient.issueFailure("한국투자 접속키 발급", r, mapper);
        }
        JsonNode n = mapper.readTree(r.body());
        String key = n.path("approval_key").asText("");
        if (key.isEmpty()) {
            throw new IOException("한국투자 접속키 발급 실패: approval_key 없음");
        }
        return new TokenCache.Issued(key, clock.instant().plus(VALIDITY));
    }
}
