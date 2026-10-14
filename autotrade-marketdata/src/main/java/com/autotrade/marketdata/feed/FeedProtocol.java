package com.autotrade.marketdata.feed;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 시세 전달 소켓의 줄 형식(ADR-010 2.2). 한 줄에 JSON 하나, UTF-8, {@code \n}으로 끝난다.
 *
 * <ol>
 *   <li>받는 쪽 → 게이트웨이: 구독 줄 하나 {@code {"subscribe":["KRX"],"client":"lab"}}. 이 뒤로 받는 쪽은 아무것도 보내지 않는다.</li>
 *   <li>게이트웨이 → 받는 쪽: 인사 줄 {@code {"feed":"autotrade-market-feed","formatVersion":1}}, 그 뒤로 이벤트 줄.</li>
 *   <li>이벤트 줄은 녹화 형식({@link RecordingCodec}) 그대로다. {@code seq}는 연결마다 1부터 빈틈 없이 매기는 전달 순번,
 *       {@code src}·{@code srcSeq}는 녹화와 같은 원래 소스 이름과 소스 순번이다.</li>
 * </ol>
 * 녹화 형식 버전({@link RecordingCodec#FORMAT_VERSION})이 다르면 받는 쪽이 연결을 끊는다.
 */
public final class FeedProtocol {

    public static final String FEED_NAME = "autotrade-market-feed";
    /** 한 줄 상한(바이트). 호가 10단계 이벤트 한 줄은 2KB 안팎이다 `[추론]`. */
    public static final int MAX_LINE = 64 * 1024;
    /** 구독 줄 상한(바이트). */
    public static final int MAX_SUBSCRIBE_LINE = 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CLIENT = Pattern.compile("[a-z0-9-]{1,32}");

    private FeedProtocol() {}

    /** 구독 요청. */
    public record Subscribe(Set<Market> markets, String client) {
        public Subscribe {
            markets = markets.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(markets));
            if (markets.isEmpty()) {
                throw new IllegalArgumentException("구독할 시장이 없습니다");
            }
            if (client == null || !CLIENT.matcher(client).matches()) {
                throw new IllegalArgumentException("받는 쪽 이름 형식이 아닙니다");
            }
        }
    }

    public static String subscribeLine(Subscribe s) {
        ObjectNode n = MAPPER.createObjectNode();
        ArrayNode a = n.putArray("subscribe");
        EnumSet.copyOf(s.markets()).forEach(m -> a.add(m.name()));
        n.put("client", s.client());
        return write(n);
    }

    /** @throws IllegalArgumentException 형식이 틀리면(원문은 메시지에 넣지 않는다) */
    public static Subscribe parseSubscribe(String line) {
        JsonNode n = read(line);
        JsonNode list = n.get("subscribe");
        JsonNode client = n.get("client");
        if (list == null || !list.isArray() || client == null || !client.isTextual() || n.size() != 2) {
            throw new IllegalArgumentException("구독 줄 형식 오류");
        }
        Set<Market> markets = EnumSet.noneOf(Market.class);
        for (Iterator<JsonNode> it = list.elements(); it.hasNext();) {
            JsonNode m = it.next();
            if (!m.isTextual()) {
                throw new IllegalArgumentException("구독 줄 형식 오류");
            }
            try {
                markets.add(Market.valueOf(m.asText()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("모르는 시장입니다", e);
            }
        }
        return new Subscribe(markets, client.asText());
    }

    public static String helloLine() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("feed", FEED_NAME);
        n.put("formatVersion", RecordingCodec.FORMAT_VERSION);
        return write(n);
    }

    /** 인사 줄이 이 프로그램과 맞는지. 다르면 이유, 맞으면 빈 문자열. */
    public static String checkHello(String line) {
        JsonNode n;
        try {
            n = read(line);
        } catch (IllegalArgumentException e) {
            return "인사 줄 형식 오류";
        }
        if (!FEED_NAME.equals(n.path("feed").asText())) {
            return "시세 전달 소켓이 아닙니다";
        }
        int version = n.path("formatVersion").asInt(-1);
        if (version != RecordingCodec.FORMAT_VERSION) {
            return "녹화 형식 버전이 다릅니다: 게이트웨이 " + version + ", 이쪽 " + RecordingCodec.FORMAT_VERSION;
        }
        return "";
    }

    private static JsonNode read(String line) {
        try {
            JsonNode n = MAPPER.readTree(line);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException("JSON 객체가 아닙니다");
            }
            return n;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 형식 오류", e);
        }
    }

    private static String write(ObjectNode n) {
        try {
            return MAPPER.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
