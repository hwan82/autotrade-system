package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 녹화 한 줄(JSON Lines)의 형식(ADR-002 2.3, 형식 버전 {@value #FORMAT_VERSION}). 키 이름은 짧게 두고 바꾸지 않는다.
 *
 * <pre>
 * {"seq":1,"src":"KIWOOM-WS","srcSeq":7,"t":"TRADE","sym":"KRX:005930","ex":"…Z","rx":"…Z","px":"71500","qty":10}
 * {"seq":2,…,"t":"BOOK","asks":[["71600",120]],"bids":[["71500",80]]}
 * {"seq":3,…,"t":"STATUS","st":"VOLATILITY_INTERRUPTION"}
 * {"seq":4,…,"t":"GAP","gs":"…Z","ge":"…Z","why":"DISCONNECTED"}
 * </pre>
 * 가격은 문자열(BigDecimal 평문)로 둔다. double 왕복 오차를 막는다.
 */
public final class RecordingCodec {

    public static final int FORMAT_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 녹화된 이벤트 한 건. {@code event.sequence()}는 녹화 순번({@code seq})이다. */
    public record Line(long seq, String sourceId, long sourceSequence, MarketEvent event) {}

    public String encode(long seq, String sourceId, MarketEvent e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("seq", seq);
        n.put("src", sourceId);
        n.put("srcSeq", e.sequence());
        n.put("t", typeOf(e));
        n.put("sym", e.symbol().toString());
        n.put("ex", e.exchangeTime().toString());
        n.put("rx", e.receivedAt().toString());
        switch (e) {
            case TradeTick t -> {
                n.put("px", t.price().toPlainString());
                n.put("qty", t.quantity());
            }
            case OrderBookSnapshot b -> {
                n.set("asks", levels(b.asks()));
                n.set("bids", levels(b.bids()));
            }
            case InstrumentStatusChanged s -> n.put("st", s.status().name());
            case DataGap g -> {
                n.put("gs", g.gapStart().toString());
                n.put("ge", g.gapEnd().toString());
                n.put("why", g.reason().name());
            }
        }
        try {
            return mapper.writeValueAsString(n);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("녹화 인코딩 실패", ex);
        }
    }

    /** 한 줄을 읽는다. 형식이 틀리면 {@link IllegalArgumentException}. */
    public Line decode(String line) {
        JsonNode n;
        try {
            n = mapper.readTree(line);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("녹화 줄 형식 오류", ex);
        }
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("녹화 줄 형식 오류");
        }
        long seq = req(n, "seq").asLong();
        Symbol sym = parseSymbol(req(n, "sym").asText());
        Instant ex = Instant.parse(req(n, "ex").asText());
        Instant rx = Instant.parse(req(n, "rx").asText());
        MarketEvent event = switch (req(n, "t").asText()) {
            case "TRADE" -> new TradeTick(sym, seq, ex, rx, new BigDecimal(req(n, "px").asText()), req(n, "qty").asLong());
            case "BOOK" -> new OrderBookSnapshot(sym, seq, ex, rx, parseLevels(req(n, "asks")), parseLevels(req(n, "bids")));
            case "STATUS" -> new InstrumentStatusChanged(sym, seq, ex, rx, InstrumentStatus.valueOf(req(n, "st").asText()));
            case "GAP" -> new DataGap(sym, seq, ex, rx, Instant.parse(req(n, "gs").asText()),
                    Instant.parse(req(n, "ge").asText()), DataGap.GapReason.valueOf(req(n, "why").asText()));
            default -> throw new IllegalArgumentException("모르는 이벤트 종류: " + n.get("t").asText());
        };
        return new Line(seq, req(n, "src").asText(), req(n, "srcSeq").asLong(), event);
    }

    static Symbol parseSymbol(String text) {
        int i = text.indexOf(':');
        if (i <= 0) {
            throw new IllegalArgumentException("종목 표기 오류: " + text);
        }
        return new Symbol(Market.valueOf(text.substring(0, i)), text.substring(i + 1));
    }

    private static String typeOf(MarketEvent e) {
        return switch (e) {
            case TradeTick t -> "TRADE";
            case OrderBookSnapshot b -> "BOOK";
            case InstrumentStatusChanged s -> "STATUS";
            case DataGap g -> "GAP";
        };
    }

    private ArrayNode levels(List<PriceLevel> levels) {
        ArrayNode a = mapper.createArrayNode();
        for (PriceLevel l : levels) {
            a.addArray().add(l.price().toPlainString()).add(l.quantity());
        }
        return a;
    }

    private static List<PriceLevel> parseLevels(JsonNode a) {
        List<PriceLevel> out = new ArrayList<>(a.size());
        for (JsonNode l : a) {
            out.add(new PriceLevel(new BigDecimal(l.get(0).asText()), l.get(1).asLong()));
        }
        return out;
    }

    private static JsonNode req(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("녹화 줄에 " + key + " 키가 없습니다");
        }
        return v;
    }
}
