package com.autotrade.marketdata.enginefeed;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 게이트웨이 → 실전 엔진 시세 전달 소켓의 줄 형식(ADR-016 2.3, 엔진 전달 형식 버전 {@value #FORMAT_VERSION}). 한 줄에 JSON
 * 하나, UTF-8, {@code \n}으로 끝난다.
 *
 * <p>이벤트 줄은 녹화 형식({@link RecordingCodec}) 그대로이고 최상위에 {@code t}가 있다. 제어 줄은 {@code t}가 없고 최상위 키
 * 하나로 종류를 가른다.
 *
 * <ul>
 *   <li>엔진 → 게이트웨이: 인사({@code hello}), 관심 종목({@code interest}, 전체 교체).</li>
 *   <li>게이트웨이 → 엔진: 인사({@code feed}), 상태({@code status}), 생존({@code hb}), 이벤트 줄.</li>
 * </ul>
 *
 * <p>소스 상태는 상태 줄과 생존 줄 모두 같은 객체 모양 {@code {"state":"CONNECTED","lastRx":"…Z"}}로 쓴다(architect PR #245
 * N6). {@code lastRx}는 그 소스에서 시세 메시지를 아직 받지 못했으면 {@code null}이다.
 *
 * <p>형식 오류는 {@link IllegalArgumentException}으로 알리며 받은 원문을 메시지에 넣지 않는다.
 */
public final class EngineFeedProtocol {

    /** 엔진 인사 줄의 {@code hello} 값과 게이트웨이 인사 줄의 {@code feed} 값. */
    public static final String NAME = "autotrade-engine-feed";
    public static final int FORMAT_VERSION = 1;
    /** 게이트웨이 → 엔진 한 줄 상한(바이트, ADR-010과 같음). */
    public static final int MAX_LINE = 64 * 1024;
    /** 엔진 → 게이트웨이 한 줄 상한(바이트). */
    public static final int MAX_ENGINE_LINE = 16 * 1024;
    /** {@code protect} 상한 `[가정]`. 게이트웨이 줄 형식 상한일 뿐 보호 가능 종목 수가 아니다(ADR-016 2.12 3번). */
    public static final int MAX_PROTECT = 64;
    /** {@code protect}∪{@code entry} 상한 `[가정]`. */
    public static final int MAX_INTEREST = 128;

    /** 관심 줄을 거부한 이유. 상태 줄 {@code rejected}에 이 이름이 실린다. */
    public enum Rejection {
        /** JSON·키·값 형식이 틀림. */
        BAD_FORMAT,
        /** 종목 표기가 틀림. */
        BAD_SYMBOL,
        /** {@code protect}가 64종목을 넘음. */
        TOO_MANY_PROTECT,
        /** {@code protect}∪{@code entry}가 128종목을 넘음. */
        TOO_MANY_SYMBOLS,
        /** 줄이 16KB를 넘음. */
        LINE_TOO_LONG,
        /** 인사 전에 관심이 왔거나 인사 뒤에 인사가 다시 옴. */
        UNEXPECTED_LINE
    }

    /** 미구독 이유(ADR-016 2.3). */
    public enum Unserved {
        /** 소스 한도. */
        CAPACITY,
        /** 쓸 수 있는 소스가 없음. */
        NO_SOURCE,
        /** 증권사가 구독을 거절. */
        REJECTED,
        /** 구독 요청 중(등록 응답이나 그 종목 이벤트를 아직 못 받음). */
        PENDING
    }

    /** 형식 오류. {@link #reason()}이 상태 줄에 실을 거부 이유다. */
    public static final class ProtocolException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final transient Rejection reason;

        ProtocolException(Rejection reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Rejection reason() {
            return reason;
        }
    }

    /** 엔진 → 게이트웨이 줄. */
    public sealed interface EngineLine permits EngineHello, Interest {}

    /** 엔진 인사. {@code boot}는 로그 대조용 부팅 표지({@code [A-Za-z0-9-]{1,40}}). */
    public record EngineHello(String boot) implements EngineLine {
        public EngineHello {
            if (boot == null || !BOOT.matcher(boot).matches()) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
        }
    }

    /**
     * 관심 종목(전체 교체, ADR-016 2.3). {@code rev}는 연결마다 1부터 늘어난다.
     *
     * @param protect 실전 보유 종목 ∪ 미체결 주문 종목(보호 대상)
     * @param entry 오늘 신규 진입을 볼 수 있는 종목
     */
    public record Interest(long rev, SortedSet<Symbol> protect, SortedSet<Symbol> entry) implements EngineLine {
        public Interest {
            if (rev < 1) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            protect = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(protect, "protect")));
            entry = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(entry, "entry")));
            if (protect.size() > MAX_PROTECT) {
                throw new ProtocolException(Rejection.TOO_MANY_PROTECT);
            }
            TreeSet<Symbol> all = new TreeSet<>(protect);
            all.addAll(entry);
            if (all.size() > MAX_INTEREST) {
                throw new ProtocolException(Rejection.TOO_MANY_SYMBOLS);
            }
        }

        /** {@code protect}∪{@code entry}. */
        public SortedSet<Symbol> all() {
            TreeSet<Symbol> all = new TreeSet<>(protect);
            all.addAll(entry);
            return Collections.unmodifiableSortedSet(all);
        }
    }

    /** 한 소스의 상태. {@code lastRx}는 시세 메시지를 마지막으로 받은 게이트웨이 시각(없으면 null). */
    public record SourceState(ConnectionState state, Instant lastRx) {
        public SourceState {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * 상태 줄(ADR-016 2.3). {@code rev}는 이 연결에서 마지막으로 적용한 관심의 {@code rev}(아직 없으면 0).
     *
     * @param served 종목 → 담당 소스 이름
     * @param rejected 이번 관심 줄을 거부했으면 그 이유, 아니면 null
     */
    public record Status(long rev, Instant at, SortedMap<String, SourceState> sources, SortedMap<Symbol, String> served,
            SortedMap<Symbol, Unserved> unserved, Rejection rejected) {
        public Status {
            if (rev < 0) {
                throw new IllegalArgumentException("rev는 0 이상");
            }
            Objects.requireNonNull(at, "at");
            sources = Collections.unmodifiableSortedMap(new TreeMap<>(sources));
            served = Collections.unmodifiableSortedMap(new TreeMap<>(served));
            unserved = Collections.unmodifiableSortedMap(new TreeMap<>(unserved));
            for (Symbol s : served.keySet()) {
                if (unserved.containsKey(s)) {
                    throw new IllegalArgumentException("한 종목이 served와 unserved에 함께 있습니다");
                }
            }
        }
    }

    /** 생존 줄(ADR-016 2.3). {@code dropped}는 이 연결에서 큐가 차서 버린 이벤트 누계. */
    public record Heartbeat(Instant at, SortedMap<String, SourceState> sources, long dropped) {
        public Heartbeat {
            Objects.requireNonNull(at, "at");
            sources = Collections.unmodifiableSortedMap(new TreeMap<>(sources));
            if (dropped < 0) {
                throw new IllegalArgumentException("dropped는 0 이상");
            }
        }
    }

    /** 게이트웨이 → 엔진 제어 줄. 이벤트 줄은 {@link RecordingCodec}으로 읽는다. */
    public sealed interface GatewayControl permits GatewayHello, StatusLine, HeartbeatLine {}

    public record GatewayHello(int formatVersion, int recordingFormat) implements GatewayControl {}

    public record StatusLine(Status status) implements GatewayControl {}

    public record HeartbeatLine(Heartbeat heartbeat) implements GatewayControl {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BOOT = Pattern.compile("[A-Za-z0-9-]{1,40}");
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private EngineFeedProtocol() {}

    // ---- 엔진 → 게이트웨이 ----

    public static String engineHelloLine(String boot) {
        EngineHello h = new EngineHello(boot);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("hello", NAME);
        n.put("formatVersion", FORMAT_VERSION);
        n.put("client", "engine");
        n.put("boot", h.boot());
        return write(n);
    }

    public static String interestLine(Interest i) {
        ObjectNode n = MAPPER.createObjectNode();
        ObjectNode body = n.putObject("interest");
        body.put("rev", i.rev());
        symbols(body.putArray("protect"), i.protect());
        symbols(body.putArray("entry"), i.entry());
        return write(n);
    }

    /**
     * 엔진이 보낸 줄 하나를 읽는다.
     *
     * @throws ProtocolException 형식이 틀리거나 상한을 넘으면(이유는 {@link ProtocolException#reason()})
     */
    public static EngineLine parseEngineLine(String line) {
        if (line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ENGINE_LINE) {
            throw new ProtocolException(Rejection.LINE_TOO_LONG);
        }
        JsonNode n = read(line);
        if (n.size() == 4 && n.has("hello")) {
            if (!NAME.equals(text(n, "hello")) || !"engine".equals(text(n, "client"))
                    || !n.path("formatVersion").isInt() || n.get("formatVersion").intValue() != FORMAT_VERSION) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            return new EngineHello(text(n, "boot"));
        }
        if (n.size() == 1 && n.has("interest")) {
            JsonNode body = n.get("interest");
            if (!body.isObject() || body.size() != 3 || !body.path("rev").isIntegralNumber()
                    || !body.path("rev").canConvertToLong()) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            return new Interest(body.get("rev").longValue(), parseSymbols(body.get("protect")),
                    parseSymbols(body.get("entry")));
        }
        throw new ProtocolException(Rejection.BAD_FORMAT);
    }

    // ---- 게이트웨이 → 엔진 ----

    public static String gatewayHelloLine() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("feed", NAME);
        n.put("formatVersion", FORMAT_VERSION);
        n.put("recordingFormat", RecordingCodec.FORMAT_VERSION);
        return write(n);
    }

    /** 게이트웨이 인사가 이 프로그램과 맞는지. 다르면 이유, 맞으면 빈 문자열. */
    public static String checkGatewayHello(GatewayHello h) {
        if (h.formatVersion() != FORMAT_VERSION) {
            return "엔진 전달 형식 버전이 다릅니다: 게이트웨이 " + h.formatVersion() + ", 엔진 " + FORMAT_VERSION;
        }
        if (h.recordingFormat() != RecordingCodec.FORMAT_VERSION) {
            return "녹화 형식 버전이 다릅니다: 게이트웨이 " + h.recordingFormat() + ", 엔진 " + RecordingCodec.FORMAT_VERSION;
        }
        return "";
    }

    public static String statusLine(Status s) {
        ObjectNode n = MAPPER.createObjectNode();
        ObjectNode body = n.putObject("status");
        body.put("rev", s.rev());
        body.put("at", s.at().toString());
        sources(body.putObject("sources"), s.sources());
        ObjectNode served = body.putObject("served");
        s.served().forEach((sym, src) -> served.put(sym.toString(), src));
        ObjectNode unserved = body.putObject("unserved");
        s.unserved().forEach((sym, why) -> unserved.put(sym.toString(), why.name()));
        if (s.rejected() == null) {
            body.putNull("rejected");
        } else {
            body.put("rejected", s.rejected().name());
        }
        return write(n);
    }

    public static String heartbeatLine(Heartbeat h) {
        ObjectNode n = MAPPER.createObjectNode();
        ObjectNode body = n.putObject("hb");
        body.put("at", h.at().toString());
        sources(body.putObject("sources"), h.sources());
        body.put("dropped", h.dropped());
        return write(n);
    }

    /** 이벤트 줄(녹화 형식)인지. 최상위에 {@code t}가 있으면 이벤트다. */
    public static boolean isEventLine(JsonNode n) {
        return n.has("t");
    }

    /** 줄을 JSON 객체로 읽는다(이벤트·제어 구분 전). */
    public static JsonNode readTree(String line) {
        return read(line);
    }

    /**
     * 게이트웨이 제어 줄을 읽는다.
     *
     * @throws IllegalArgumentException 모르는 줄이거나 형식이 틀리면
     */
    public static GatewayControl parseGatewayControl(JsonNode n) {
        if (n.size() != 1 && !n.has("feed")) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        if (n.has("feed")) {
            if (n.size() != 3 || !NAME.equals(text(n, "feed")) || !n.path("formatVersion").isInt()
                    || !n.path("recordingFormat").isInt()) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            return new GatewayHello(n.get("formatVersion").intValue(), n.get("recordingFormat").intValue());
        }
        if (n.has("status")) {
            JsonNode b = object(n.get("status"), 6);
            JsonNode rejected = b.get("rejected");
            Rejection why = null;
            if (rejected == null) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            if (!rejected.isNull()) {
                why = enumValue(Rejection.values(), rejected);
            }
            SortedMap<Symbol, String> served = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : object(b.get("served"), -1).properties()) {
                served.put(symbol(e.getKey()), sourceName(e.getValue()));
            }
            SortedMap<Symbol, Unserved> unserved = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : object(b.get("unserved"), -1).properties()) {
                unserved.put(symbol(e.getKey()), enumValue(Unserved.values(), e.getValue()));
            }
            try {
                return new StatusLine(new Status(nonNegative(b.get("rev")), instant(b.get("at")),
                        parseSources(b.get("sources")), served, unserved, why));
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
        }
        if (n.has("hb")) {
            JsonNode b = object(n.get("hb"), 3);
            return new HeartbeatLine(new Heartbeat(instant(b.get("at")), parseSources(b.get("sources")),
                    nonNegative(b.get("dropped"))));
        }
        throw new ProtocolException(Rejection.BAD_FORMAT);
    }

    // ---- 도움 ----

    private static void symbols(ArrayNode a, Set<Symbol> symbols) {
        symbols.forEach(s -> a.add(s.toString()));
    }

    private static void sources(ObjectNode o, SortedMap<String, SourceState> sources) {
        sources.forEach((name, st) -> {
            ObjectNode s = o.putObject(name);
            s.put("state", st.state().name());
            if (st.lastRx() == null) {
                s.putNull("lastRx");
            } else {
                s.put("lastRx", st.lastRx().toString());
            }
        });
    }

    private static SortedMap<String, SourceState> parseSources(JsonNode node) {
        SortedMap<String, SourceState> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : object(node, -1).properties()) {
            if (!SOURCE.matcher(e.getKey()).matches()) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            JsonNode s = object(e.getValue(), 2);
            JsonNode rx = s.get("lastRx");
            if (rx == null) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            out.put(e.getKey(), new SourceState(enumValue(ConnectionState.values(), s.get("state")),
                    rx.isNull() ? null : instant(rx)));
        }
        return out;
    }

    private static SortedSet<Symbol> parseSymbols(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        SortedSet<Symbol> out = new TreeSet<>();
        for (JsonNode s : node) {
            if (!s.isTextual()) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            out.add(symbol(s.asText()));
        }
        return out;
    }

    /** {@code KRX:005930} 표기를 읽는다. */
    public static Symbol symbol(String text) {
        int i = text.indexOf(':');
        if (i <= 0) {
            throw new ProtocolException(Rejection.BAD_SYMBOL);
        }
        try {
            return new Symbol(Market.valueOf(text.substring(0, i)), text.substring(i + 1));
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(Rejection.BAD_SYMBOL);
        }
    }

    private static String sourceName(JsonNode n) {
        if (n == null || !n.isTextual() || !SOURCE.matcher(n.asText()).matches()) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        return n.asText();
    }

    private static JsonNode object(JsonNode n, int size) {
        if (n == null || !n.isObject() || (size >= 0 && n.size() != size)) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        return n;
    }

    private static <E extends Enum<E>> E enumValue(E[] values, JsonNode n) {
        if (n == null || !n.isTextual()) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        for (E e : values) {
            if (e.name().equals(n.asText())) {
                return e;
            }
        }
        throw new ProtocolException(Rejection.BAD_FORMAT);
    }

    private static long nonNegative(JsonNode n) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 0) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        return n.longValue();
    }

    private static Instant instant(JsonNode n) {
        if (n == null || !n.isTextual()) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        try {
            return Instant.parse(n.asText());
        } catch (DateTimeParseException e) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual()) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
        }
        return v.asText();
    }

    private static JsonNode read(String line) {
        try {
            JsonNode n = MAPPER.readTree(line);
            if (n == null || !n.isObject()) {
                throw new ProtocolException(Rejection.BAD_FORMAT);
            }
            return n;
        } catch (JsonProcessingException e) {
            throw new ProtocolException(Rejection.BAD_FORMAT);
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
