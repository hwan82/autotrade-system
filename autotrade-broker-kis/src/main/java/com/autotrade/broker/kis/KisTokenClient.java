package com.autotrade.broker.kis;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.CredentialsRejectedException;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * 한국투자 REST 접근토큰 발급({@code POST /oauth2/tokenP}). 조회 호출기({@link KisRestClient})가 쓴다.
 *
 * <p>`[사실]` 근거: koreainvestment/open-trading-api(커밋 2082791, 2026-10-10 확인)
 * <ul>
 *   <li>본문 {@code {"grant_type":"client_credentials","appkey":…,"appsecret":…}}(examples_user/auth/auth_functions.py).
 *       실시간 접속키 발급과 달리 시크릿 이름이 {@code appsecret}이다.</li>
 *   <li>응답 {@code access_token}, {@code token_type}("Bearer"), {@code expires_in}(초),
 *       {@code access_token_token_expired}(일시, "yyyy-MM-dd HH:mm:ss")(examples_llm/auth/auth_token/auth_token.py).</li>
 *   <li>유효기간 1일. 6시간 안에 다시 신청하면 같은 토큰을 준다. 발급할 때마다 알림톡이 간다(examples_user/kis_auth.py 주석).
 *       재발급은 1분당 1회(README 5장).</li>
 * </ul>
 * 그래서 필요할 때만 받고 만료 1시간 전까지 다시 쓴다. `[가정]` 만료 일시는 한국 시간이다.
 */
public final class KisTokenClient implements TokenCache.Fetcher {

    static final URI TOKEN_URI = URI.create("https://openapi.koreainvestment.com:9443/oauth2/tokenP");
    /** 응답에 만료 정보가 없을 때 쓰는 유효기간(공식 주석 "유효기간 1일"). */
    static final Duration DEFAULT_VALIDITY = Duration.ofDays(1);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EXPIRED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpPort http;
    private final BrokerCredentials credentials;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    KisTokenClient(HttpPort http, BrokerCredentials credentials, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 접근토큰 발급기. 주문·계좌 조회 세션({@code com.autotrade.broker.kis.order})이 재발급 하한을 씌워 쓴다. */
    public static TokenCache.Fetcher fetcher(HttpPort http, BrokerCredentials credentials, Clock clock) {
        return new KisTokenClient(http, credentials, clock);
    }

    @Override
    public TokenCache.Issued fetch() throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of(
                "grant_type", "client_credentials",
                "appkey", credentials.appKey(),
                "appsecret", credentials.appSecret()));
        HttpPort.Response r = http.post(TOKEN_URI, Map.of("Content-Type", "application/json; charset=utf-8"), body);
        if (r.status() != 200) {
            throw issueFailure("한국투자 접근토큰 발급", r, mapper);
        }
        JsonNode n = readOrNull(mapper, r.body());
        String token = n == null ? "" : n.path("access_token").asText("");
        if (token.isEmpty()) {
            throw new IOException("한국투자 접근토큰 발급 실패: access_token 없음");
        }
        return new TokenCache.Issued(token, expiresAt(n, clock.instant()));
    }

    /** {@code expires_in}과 만료 일시 중 이른 쪽. 둘 다 없거나 읽을 수 없으면 발급 후 1일. */
    static Instant expiresAt(JsonNode n, Instant now) {
        Instant byDefault = now.plus(DEFAULT_VALIDITY);
        Instant out = byDefault;
        long seconds = n.path("expires_in").asLong(0);
        if (seconds > 0) {
            out = now.plusSeconds(seconds);
        }
        String text = n.path("access_token_token_expired").asText("").strip();
        if (!text.isEmpty()) {
            try {
                Instant at = LocalDateTime.parse(text, EXPIRED).atZone(SEOUL).toInstant();
                if (at.isAfter(now) && at.isBefore(out)) {
                    out = at;
                }
            } catch (DateTimeParseException e) {
                // 형식이 다르면 expires_in(또는 1일)을 쓴다
            }
        }
        return out;
    }

    /**
     * 토큰·접속키 발급 실패 해석. HTTP 401·403은 자격 증명 거절({@link CredentialsRejectedException}, ADR-003 4장
     * 인증 실패 횟수에 들어감)이다. 단 본문 결과 코드가 재발급 제한이면 키 문제가 아니므로 일반 실패로 둔다
     * (다음 발급은 어차피 60초 뒤). 메시지에는 상태와 결과 코드만 넣는다.
     */
    static IOException issueFailure(String what, HttpPort.Response r, ObjectMapper mapper) {
        JsonNode n = readOrNull(mapper, r.body());
        KisErrorCode code = n == null ? null : KisErrorCode.of(n);
        String detail = "HTTP " + r.status() + (code == null || code.code().isEmpty() ? "" : ", " + code.describe());
        if (code != null && code.kind() == KisErrorCode.Kind.ISSUE_THROTTLED) {
            return new IOException(what + " 보류: " + detail);
        }
        if (r.status() == 401 || r.status() == 403) {
            return new CredentialsRejectedException(what + " 거절: " + detail);
        }
        return new IOException(what + " 실패: " + detail);
    }

    public static JsonNode readOrNull(ObjectMapper mapper, String body) {
        try {
            JsonNode n = mapper.readTree(body);
            return n != null && n.isObject() ? n : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
