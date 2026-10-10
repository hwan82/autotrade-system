package com.autotrade.broker.kiwoom;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.CredentialsRejectedException;
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
 * 응답 token·expires_dt(yyyyMMddHHmmss)·return_code (키움 공식 저장소 kiwoom_api_spec.json au10001, 2026-10-08 확인;
 * 사용자 제공 명세 JSON au10001, 2026-10-10 재확인). 오류는 HTTP 상태와 상관없이 본문 return_code로 먼저 판단한다
 * (명세 errorCodeList, {@link KiwoomReturnCode}). `[가정]` expires_dt는 한국 시간이다.
 */
final class KiwoomTokenClient implements TokenCache.Fetcher {

    static final URI TOKEN_URI = URI.create("https://api.kiwoom.com/oauth2/token");
    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final System.Logger LOG = System.getLogger(KiwoomTokenClient.class.getName());

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
        JsonNode n = json(r.body());
        KiwoomReturnCode code = n == null ? null : KiwoomReturnCode.of(n);
        if (code != null && !code.ok()) {
            // 게이트웨이는 예외 종류만 로그에 남기므로 사용자 조치(IP 등록 등)가 보이게 코드·고정 안내만 따로 남긴다
            LOG.log(System.Logger.Level.WARNING, "키움 토큰 발급 실패(HTTP {0}): {1}", r.status(), code.describe());
            // 호출 한도·토큰 계열·서버 오류(1999)는 재시도로 풀릴 수 있다. 나머지(키·IP·투자구분·입력 오류)는 거절로 본다.
            // 코드별 안내는 KiwoomReturnCode.describe(). 응답 메시지 원문은 키가 섞일 수 있어 옮기지 않는다.
            switch (code.kind()) {
                case RATE_LIMITED, TOKEN_EXPIRED -> throw new IOException("키움 토큰 발급 보류: " + code.describe());
                default -> {
                    if (code.specific() == 1999) {
                        throw new IOException("키움 토큰 발급 실패: " + code.describe());
                    }
                    throw new CredentialsRejectedException("키움 토큰 발급 거절: " + code.describe());
                }
            }
        }
        if (r.status() == 401 || r.status() == 403) {
            throw new CredentialsRejectedException("키움 토큰 발급 거절: HTTP " + r.status());
        }
        if (r.status() != 200 || n == null) {
            throw new IOException("키움 토큰 발급 실패: HTTP " + r.status());
        }
        String token = n.path("token").asText("");
        if (token.isEmpty()) {
            throw new CredentialsRejectedException("키움 토큰 발급 거절: 토큰 없음");
        }
        String expires = n.path("expires_dt").asText("");
        if (expires.length() != 14) {
            throw new IOException("키움 토큰 만료 시각 형식 오류");
        }
        return new TokenCache.Issued(token, LocalDateTime.parse(expires, EXPIRES).atZone(SEOUL).toInstant());
    }

    /** JSON 객체가 아니면 null. 본문은 예외 메시지에 넣지 않는다. */
    private JsonNode json(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            return n != null && n.isObject() ? n : null;
        } catch (IOException e) {
            return null;
        }
    }
}
