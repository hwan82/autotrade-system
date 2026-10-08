package com.autotrade.broker.kiwoom;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

/**
 * 키움 접근토큰 발급(au10001, {@code POST /oauth2/token}). `[사실]` 요청 본문 grant_type·appkey·secretkey,
 * 응답 token·expires_dt(yyyyMMddHHmmss)·return_code (키움 공식 저장소 kiwoom_api_spec.json au10001, 2026-10-08 확인).
 * `[가정]` expires_dt는 한국 시간이다.
 */
final class KiwoomTokenClient implements TokenCache.Fetcher {

    static final URI TOKEN_URI = URI.create("https://api.kiwoom.com/oauth2/token");
    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HttpPort http;
    private final BrokerCredentials credentials;
    private final ObjectMapper mapper = new ObjectMapper();

    KiwoomTokenClient(HttpPort http, BrokerCredentials credentials) {
        this.http = Objects.requireNonNull(http, "http");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public TokenCache.Issued fetch() throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of(
                "grant_type", "client_credentials",
                "appkey", credentials.appKey(),
                "secretkey", credentials.appSecret()));
        HttpPort.Response r = http.post(TOKEN_URI,
                Map.of("Content-Type", "application/json;charset=UTF-8", "api-id", "au10001"), body);
        if (r.status() != 200) {
            throw new IOException("키움 토큰 발급 실패: HTTP " + r.status());
        }
        JsonNode n = mapper.readTree(r.body());
        int code = n.path("return_code").asInt(0);
        String token = n.path("token").asText("");
        if (code != 0 || token.isEmpty()) {
            throw new IOException("키움 토큰 발급 실패: return_code " + code);
        }
        String expires = n.path("expires_dt").asText("");
        if (expires.length() != 14) {
            throw new IOException("키움 토큰 만료 시각 형식 오류");
        }
        return new TokenCache.Issued(token, LocalDateTime.parse(expires, EXPIRES).atZone(SEOUL).toInstant());
    }
}
