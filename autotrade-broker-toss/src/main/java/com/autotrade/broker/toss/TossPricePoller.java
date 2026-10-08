package com.autotrade.broker.toss;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.autotrade.marketdata.polling.LastPrice;
import com.autotrade.marketdata.polling.PollingQuoteSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 토스 현재가 폴링({@code GET /api/v1/prices?symbols=…}, D-008 보유 종목 보호용).
 *
 * <p>`[사실]` symbols는 쉼표로 최대 200개, 응답 {@code result[]}의 symbol·timestamp(ISO-8601, 오프셋 포함)·lastPrice(문자열),
 * 레이트리밋 MARKET_DATA 그룹 초당 15회, 초과 시 HTTP 429와 Retry-After
 * (https://openapi.tossinvest.com/openapi-docs/latest/openapi.json, overview.md, 2026-10-08 확인).
 * `[가정]` 국내 6자리 코드와 미국 티커를 한 요청에 섞을 수 있는지 확인하지 못해 시장별로 나눠 묻는다.
 *
 * <p>레이트리밋: 평균 호출 간격 {@link #MIN_INTERVAL}, 순간 {@link #BURST}회까지 허용하는 GCRA(토큰 버킷과 같은 효과)로 지키고,
 * 429면 Retry-After까지 호출하지 않는다.
 * 지킬 수 없는 호출은 기다리지 않고 {@link RateLimitedException}을 던진다(보호 감시 루프가 다음 주기에 다시 묻는다).
 */
public final class TossPricePoller implements PollingQuoteSource {

    public static final String SOURCE_ID = "TOSS-POLL";
    static final URI PRICES_URI = URI.create("https://openapi.tossinvest.com/api/v1/prices");
    static final int MAX_PER_CALL = 200;
    /** 초당 15회의 절반 이하로 쓴다(같은 그룹을 다른 조회와 나눠 쓰므로) `[추론]`. */
    static final Duration MIN_INTERVAL = Duration.ofMillis(150);
    /** 한 번의 poll이 시장별로 나눠 묻는 정도의 순간 호출 허용량. */
    static final int BURST = 4;

    private final HttpPort http;
    private final TokenCache tokens;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    /** GCRA 이론 도착 시각. */
    private Instant tat = Instant.EPOCH;
    private Instant blockedUntil = Instant.EPOCH;

    TossPricePoller(HttpPort http, TokenCache tokens, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 레이트리밋 때문에 이번에는 묻지 않았다. */
    public static final class RateLimitedException extends IOException {
        private static final long serialVersionUID = 1L;

        RateLimitedException(Instant until) {
            super("토스 시세 조회 레이트리밋: " + until + "까지 대기");
        }
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public int maxSymbolsPerCall() {
        return MAX_PER_CALL;
    }

    @Override
    public synchronized Map<Symbol, LastPrice> poll(SortedSet<Symbol> symbols) throws IOException, InterruptedException {
        Map<Symbol, LastPrice> out = new TreeMap<>();
        for (Market market : Market.values()) {
            List<Symbol> group = symbols.stream().filter(s -> s.market() == market).toList();
            for (int i = 0; i < group.size(); i += MAX_PER_CALL) {
                out.putAll(call(market, group.subList(i, Math.min(group.size(), i + MAX_PER_CALL))));
            }
        }
        return out;
    }

    private Map<Symbol, LastPrice> call(Market market, List<Symbol> symbols) throws IOException, InterruptedException {
        Instant now = clock.instant();
        if (now.isBefore(blockedUntil)) {
            throw new RateLimitedException(blockedUntil);
        }
        Instant earliest = tat.minus(MIN_INTERVAL.multipliedBy(BURST - 1));
        if (now.isBefore(earliest)) {
            throw new RateLimitedException(earliest);
        }
        tat = (tat.isAfter(now) ? tat : now).plus(MIN_INTERVAL);
        String codes = symbols.stream().map(Symbol::code).collect(Collectors.joining(","));
        URI uri = URI.create(PRICES_URI + "?symbols=" + codes);
        HttpPort.Response r = http.get(uri, Map.of("Authorization", "Bearer " + tokens.get()));
        if (r.status() == 401) {
            tokens.invalidate();
            throw new IOException("토스 시세 조회 인증 실패: HTTP 401");
        }
        if (r.status() == 429) {
            long wait = parseRetryAfter(r.headers().get("retry-after"));
            blockedUntil = clock.instant().plusSeconds(wait);
            throw new RateLimitedException(blockedUntil);
        }
        if (r.status() != 200) {
            throw new IOException("토스 시세 조회 실패: HTTP " + r.status());
        }
        Instant receivedAt = clock.instant();
        Map<String, Symbol> wanted = new TreeMap<>();
        symbols.forEach(s -> wanted.put(s.code(), s));
        Map<Symbol, LastPrice> out = new TreeMap<>();
        for (JsonNode p : mapper.readTree(r.body()).path("result")) {
            Symbol s = wanted.get(p.path("symbol").asText("").toUpperCase(java.util.Locale.ROOT));
            if (s == null) {
                continue;
            }
            try {
                BigDecimal price = new BigDecimal(p.path("lastPrice").asText(""));
                Instant t = OffsetDateTime.parse(p.path("timestamp").asText("")).toInstant();
                if (price.signum() > 0) {
                    out.put(s, new LastPrice(s, price, t, receivedAt, SOURCE_ID));
                }
            } catch (NumberFormatException | DateTimeParseException e) {
                // 그 종목만 버린다(값을 지어내지 않는다)
            }
        }
        return out;
    }

    private static long parseRetryAfter(String v) {
        if (v == null) {
            return 1;
        }
        try {
            return Math.max(1, Long.parseLong(v.strip()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
