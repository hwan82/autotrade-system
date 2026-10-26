package com.autotrade.broker.kis.order;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.kis.KisRestClient;
import com.autotrade.broker.kis.KisTokenClient;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * 한국투자 앱키 하나의 주문·계좌 조회 세션. 같은 앱키를 쓰는 주문({@link KisOrderClient})과 조회({@link KisAccountQueries})가
 * 토큰 하나와 호출 간격 하나를 함께 쓴다. 토큰을 따로 받으면 재발급 1분당 1회 제한에 서로 걸리기 때문이다.
 *
 * <p>`[사실]` 근거: koreainvestment/open-trading-api 커밋 cd63cae(2026-10-23) examples_user/kis_auth.py {@code _url_fetch}:
 * 헤더 {@code authorization: Bearer}, {@code appkey}, {@code appsecret}, {@code tr_id}, {@code custtype: P}, {@code tr_cont}.
 * POST는 본문 JSON(키 대문자), 해시키는 쓰지 않는다(주석 "현재 필수 아님").
 *
 * <p>호출 간격은 0.1초(초당 10건, 실전 한도 초당 20건의 절반, D-054 6장). 자동 재시도는 하지 않는다. 요청 헤더·본문·URI에는
 * 앱 시크릿과 계좌번호가 실리므로 로그나 예외 메시지에 넣지 않는다(안전 불변식 9).
 */
public final class KisSession {

    static final URI BASE = URI.create("https://openapi.koreainvestment.com:9443");
    static final Duration DEFAULT_INTERVAL = Duration.ofMillis(100);

    private final HttpPort http;
    private final TokenCache tokens;
    private final BrokerCredentials credentials;
    private final Clock clock;
    private final KisRestClient.Sleeper sleeper;
    private final Duration interval;
    private Instant lastCall;

    KisSession(HttpPort http, TokenCache tokens, BrokerCredentials credentials, Clock clock,
            KisRestClient.Sleeper sleeper, Duration interval) {
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    /** 실전 키로 세션을 만든다. 토큰은 처음 호출할 때 받는다(발급마다 알림톡이 가므로 미리 받지 않는다). */
    public static KisSession create(BrokerCredentials credentials, HttpPort http, Clock clock) {
        Objects.requireNonNull(credentials, "credentials");
        if (!credentials.isConfigured()) {
            throw new IllegalArgumentException("한국투자 키가 설정되지 않았습니다");
        }
        return new KisSession(http, tokenCache(credentials, http, clock), credentials, clock, Thread::sleep,
                DEFAULT_INTERVAL);
    }

    static TokenCache tokenCache(BrokerCredentials credentials, HttpPort http, Clock clock) {
        return new TokenCache(new KisTokenGate(KisTokenClient.fetcher(http, credentials, clock), clock), clock,
                Duration.ofHours(1));
    }

    Clock clock() {
        return clock;
    }

    /** 토큰을 받는다. 실패하면 요청을 보내지 않은 것이다. */
    String token() throws IOException, InterruptedException {
        return tokens.get();
    }

    void invalidateToken() {
        tokens.invalidate();
    }

    HttpPort.Response get(String path, String trId, String trCont, Map<String, String> params, String token)
            throws IOException, InterruptedException {
        URI uri = BASE.resolve(path + query(params));
        Map<String, String> headers = headers(trId, token);
        headers.put("tr_cont", trCont);
        synchronized (this) {
            pace();
            return http.get(uri, headers);
        }
    }

    HttpPort.Response post(String path, String trId, String jsonBody, String token)
            throws IOException, InterruptedException {
        URI uri = BASE.resolve(path);
        Map<String, String> headers = headers(trId, token);
        synchronized (this) {
            pace();
            return http.post(uri, headers, jsonBody);
        }
    }

    private Map<String, String> headers(String trId, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json; charset=utf-8");
        headers.put("authorization", "Bearer " + token);
        headers.put("appkey", credentials.appKey());
        headers.put("appsecret", credentials.appSecret());
        headers.put("tr_id", trId);
        headers.put("custtype", "P");
        return headers;
    }

    static String query(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringJoiner q = new StringJoiner("&", "?", "");
        new TreeMap<>(params).forEach((k, v) -> q.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return q.toString();
    }

    private void pace() throws InterruptedException {
        Instant now = clock.instant();
        if (lastCall != null) {
            Duration wait = Duration.between(now, lastCall.plus(interval));
            if (!wait.isNegative() && !wait.isZero()) {
                sleeper.sleep(wait);
                now = now.plus(wait);
            }
        }
        lastCall = now;
    }
}
