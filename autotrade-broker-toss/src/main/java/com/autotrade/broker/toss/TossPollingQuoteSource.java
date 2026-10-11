package com.autotrade.broker.toss;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.polling.LastPrice;
import com.autotrade.marketdata.polling.PollingQuoteSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 토스 현재가 폴링({@code GET /api/v1/prices}, 능력 POLLING_QUOTE, D-024 보유 종목 보호용).
 *
 * <p>`[사실]` 한 번에 최대 200종목을 콤마로 묻는다. 응답 항목은 {@code symbol}, {@code timestamp}(체결이 없으면 null),
 * {@code lastPrice}(문자열 decimal), {@code currency}(KRW·USD, 모르는 값이 올 수 있음). Rate Limits Group은
 * {@code MARKET_DATA}다(docs/research/toss-openapi-spec.md 5장).
 *
 * <p>계약({@link PollingQuoteSource}): 값을 지어내지 않는다. 시각이 없거나, 통화가 시장 통화와 다르거나, 가격이 0 이하이거나,
 * 묻지 않은 종목이면 결과에서 뺀다. 국내·미국 종목은 시장별로 나눠 묻는다(코드만으로 시장을 되짚지 않기 위해).
 * 주문 API를 부르지 않는다(호출기가 허용 목록으로 막는다).
 */
public final class TossPollingQuoteSource implements PollingQuoteSource {

    public static final String SOURCE_ID = "TOSS-POLL";
    static final int MAX_SYMBOLS = 200;
    static final String PATH = "/api/v1/prices";
    /**
     * 그룹 호출 한도 때문에 이보다 오래 기다려야 하면 이번 폴링은 바로 실패로 돌린다. 호출자가 다른 출처로 넘어가거나
     * 다음 주기에 다시 묻게 한다(장애표 #2·#15, PR #41 risk-ops F1).
     */
    static final Duration MAX_RATE_WAIT = Duration.ofSeconds(1);

    private final TossRestClient client;
    private final Clock clock;

    public TossPollingQuoteSource(TossRestClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public int maxSymbolsPerCall() {
        return MAX_SYMBOLS;
    }

    @Override
    public Map<Symbol, LastPrice> poll(SortedSet<Symbol> symbols) throws IOException, InterruptedException {
        Objects.requireNonNull(symbols, "symbols");
        if (symbols.size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException("한 번에 물을 수 있는 종목 수 초과: " + symbols.size());
        }
        Map<Market, SortedSet<Symbol>> byMarket = new EnumMap<>(Market.class);
        for (Symbol s : symbols) {
            byMarket.computeIfAbsent(s.market(), m -> new TreeSet<>()).add(s);
        }
        Map<Symbol, LastPrice> out = new TreeMap<>();
        for (Map.Entry<Market, SortedSet<Symbol>> e : byMarket.entrySet()) {
            out.putAll(pollMarket(e.getKey(), e.getValue()));
        }
        return out;
    }

    private Map<Symbol, LastPrice> pollMarket(Market market, SortedSet<Symbol> symbols)
            throws IOException, InterruptedException {
        Map<String, Symbol> asked = new TreeMap<>();
        StringJoiner codes = new StringJoiner(",");
        for (Symbol s : symbols) {
            asked.put(s.code(), s);
            codes.add(s.code());
        }
        JsonNode result = client.get(PATH, Map.of("symbols", codes.toString()), MAX_RATE_WAIT);
        Instant receivedAt = clock.instant();
        Map<Symbol, LastPrice> out = new TreeMap<>();
        if (!result.isArray()) {
            throw new IOException("토스 현재가 응답 형식 오류: result가 배열이 아님");
        }
        for (JsonNode item : result) {
            Symbol symbol = asked.get(item.path("symbol").asText("").toUpperCase(Locale.ROOT));
            if (symbol == null || out.containsKey(symbol)) {
                continue;
            }
            if (!market.currency().getCurrencyCode().equals(item.path("currency").asText(""))) {
                continue;
            }
            Instant priceTime = instant(item.path("timestamp"));
            BigDecimal price = decimal(item.path("lastPrice"));
            if (priceTime == null || price == null || price.signum() <= 0) {
                continue;
            }
            out.put(symbol, new LastPrice(symbol, price, priceTime, receivedAt, SOURCE_ID));
        }
        return out;
    }

    private static Instant instant(JsonNode n) {
        if (!n.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(n.asText()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode n) {
        if (!n.isTextual()) {
            return null; // 명세는 문자열 decimal이다. 숫자 노드는 double을 거칠 수 있어 받지 않는다
        }
        try {
            return new BigDecimal(n.asText().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
