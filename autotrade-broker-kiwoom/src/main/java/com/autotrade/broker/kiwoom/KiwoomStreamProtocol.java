package com.autotrade.broker.kiwoom;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.live.Decoded;
import com.autotrade.marketdata.live.RawQuote;
import com.autotrade.marketdata.live.StreamProtocol;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 키움 REST API 국내주식 실시간 WebSocket.
 *
 * <p>접속 경로: 사용자 제공 명세 JSON(2026-10-10 수신)의 실시간 항목 31개는 모두 {@code wss://api.kiwoom.com:10000}
 * + {@code /api/websocket}이다 `[사실]`. 2026-10-08 조사(ADR-003, market-data-limits.md)는 {@code /api/dostk/websocket}이었다.
 * 어느 쪽이 실서버에서 열리는지 키 없이 확인할 수 없어, 명세 경로를 먼저 쓰고 접속이 실패하면 예전 경로를 번갈아 시도하며,
 * 한 번 LOGIN이 성공한 경로로 고정한다(추천안 채택(자율), ADR-003 5장. 실측 후 한 경로만 남긴다).
 *
 * <p>`[사실]` 근거: 키움 공식 저장소 Kiwoom-Securities/Kiwoom-REST-API (2026-10-08 확인), 사용자 제공 명세 JSON 0B·0D(2026-10-10)
 * <ul>
 *   <li>접속 뒤 {@code {"trnm":"LOGIN","token":…}}를 보내고 LOGIN 응답의 return_code 0을 기다린다.
 *       서버의 PING은 받은 그대로 돌려준다({@code kiwoom/core/ws_client.py}).</li>
 *   <li>등록 {@code {"trnm":"REG","grp_no":"1","refresh":"1","data":[{"item":[…],"type":[…]}]}}, 해지 {@code REMOVE}.
 *       refresh 1은 기존 등록 유지(0B·0D 명세의 요청 본문).</li>
 *   <li>실시간 수신 {@code {"trnm":"REAL","data":[{"type":"0B","item":"005930","values":{…}}]}}.
 *       0B 주식체결: 20 체결시간(HHmmss), 10 현재가(부호 포함), 15 거래량(+매수/-매도 체결).
 *       0D 주식호가잔량: 21 호가시간, 41~50 매도호가1~10, 61~70 매도잔량, 51~60 매수호가1~10, 71~80 매수잔량.</li>
 *   <li>한도: 세션당 실시간 200종목(docs/research/market-data-limits.md K1).</li>
 * </ul>
 * 종목코드는 6자리 그대로 보내 KRX 단독 시세를 받는다(NXT는 {@code _NX}, 통합 SOR은 {@code _AL} 접미사, 명세 0B item).
 * 0D의 61~80(호가잔량)은 등록한 거래소 기준이고 6044~6087(KRX·NXT별 잔량)은 통합 등록용이라 쓰지 않는다
 * (명세 0D 예시에서 6자리 등록 시 6044 이후는 0) `[추론]`.
 * `[가정]` 체결·호가를 함께 등록해도 종목 단위로 200을 센다. 한 REG에 담는 종목은 100개씩 나눈다(한도 미공개).
 * 체결시간에는 날짜가 없어 수신 시각의 한국 날짜를 붙인다(정규장은 자정을 넘지 않는다).
 * 부호는 전일 대비 방향이므로 가격·수량은 절댓값을 쓴다.
 * VI 발동·해제(1h)는 명세에 필드(9068 발동구분, 1223 발동시각, 1224 해제시각)가 있지만 발동과 해제 메시지를 가르는 값이 없고
 * 전 종목 등록({@code "item":[""]})이라 실측으로 메시지 모양을 본 뒤 붙인다(ADR-003 5장, docs/research/kiwoom-rest-api-spec.md 5장).
 */
final class KiwoomStreamProtocol implements StreamProtocol {

    /** 명세 경로(우선). */
    static final URI ENDPOINT = URI.create("wss://api.kiwoom.com:10000/api/websocket");
    /** 2026-10-08 조사 경로(대체). */
    static final URI LEGACY_ENDPOINT = URI.create("wss://api.kiwoom.com:10000/api/dostk/websocket");
    private static final List<URI> CANDIDATES = List.of(ENDPOINT, LEGACY_ENDPOINT);
    static final int MAX_SYMBOLS = 200;
    static final int SYMBOLS_PER_REG = 100;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final List<String> TYPES = List.of("0B", "0D");

    private static final System.Logger LOG = System.getLogger(KiwoomStreamProtocol.class.getName());

    private final TokenCache tokens;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 다음 접속 시도 번호. 접속 스레드에서만 바뀐다. */
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile URI current;
    private volatile URI pinned;

    KiwoomStreamProtocol(TokenCache tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @Override
    public URI endpoint() {
        URI p = pinned;
        if (p != null) {
            return p;
        }
        URI next = CANDIDATES.get(Math.floorMod(attempts.getAndIncrement(), CANDIDATES.size()));
        current = next;
        return next;
    }

    /** LOGIN이 성공해 고정된 경로. 아직 없으면 빈 값. */
    java.util.Optional<URI> pinnedEndpoint() {
        return java.util.Optional.ofNullable(pinned);
    }

    @Override
    public Market market() {
        return Market.KRX;
    }

    @Override
    public int maxSymbols() {
        return MAX_SYMBOLS;
    }

    @Override
    public List<String> openingMessages() throws Exception {
        ObjectNode login = mapper.createObjectNode();
        login.put("trnm", "LOGIN");
        login.put("token", tokens.get());
        return List.of(mapper.writeValueAsString(login));
    }

    @Override
    public boolean requiresLoginAck() {
        return true;
    }

    @Override
    public void invalidateCredentials() {
        tokens.invalidate();
    }

    @Override
    public List<String> subscribeMessages(SortedSet<Symbol> symbols) {
        return registration("REG", symbols);
    }

    @Override
    public List<String> unsubscribeMessages(SortedSet<Symbol> symbols) {
        return registration("REMOVE", symbols);
    }

    private List<String> registration(String trnm, SortedSet<Symbol> symbols) {
        List<String> out = new ArrayList<>();
        List<String> codes = symbols.stream().map(Symbol::code).toList();
        for (int i = 0; i < codes.size(); i += SYMBOLS_PER_REG) {
            ObjectNode m = mapper.createObjectNode();
            m.put("trnm", trnm);
            m.put("grp_no", "1");
            m.put("refresh", "1");
            ObjectNode d = m.putArray("data").addObject();
            ArrayNode items = d.putArray("item");
            codes.subList(i, Math.min(codes.size(), i + SYMBOLS_PER_REG)).forEach(items::add);
            ArrayNode types = d.putArray("type");
            TYPES.forEach(types::add);
            try {
                out.add(mapper.writeValueAsString(m));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    @Override
    public Decoded decode(String text, Instant receivedAt) {
        String trimmed = text.strip();
        if (trimmed.equalsIgnoreCase("PING")) {
            return Decoded.reply(text);
        }
        JsonNode n;
        try {
            n = mapper.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return Decoded.ignored("JSON 아님");
        }
        if (n == null || !n.isObject()) {
            return Decoded.ignored("JSON 객체 아님");
        }
        String trnm = n.path("trnm").asText("").toUpperCase(java.util.Locale.ROOT);
        KiwoomReturnCode code = KiwoomReturnCode.of(n);
        return switch (trnm) {
            case "PING" -> Decoded.reply(text);
            case "LOGIN" -> login(code);
            case "REG", "REMOVE" -> code.ok() ? Decoded.ignored(trnm + " 성공")
                    : Decoded.control(Decoded.Control.SUBSCRIBE_REJECTED, code.describe());
            case "REAL" -> Decoded.quotes(real(n.path("data"), receivedAt));
            default -> Decoded.ignored("trnm " + trnm);
        };
    }

    private Decoded login(KiwoomReturnCode code) {
        if (!code.ok()) {
            return Decoded.control(Decoded.Control.LOGIN_FAILED, code.describe());
        }
        if (pinned == null && current != null) {
            pinned = current;
            LOG.log(System.Logger.Level.INFO, "키움 실시간 접속 경로 확인: {0}", current); // 경로 실측 기록(ADR-003 5장)
        }
        return Decoded.control(Decoded.Control.LOGIN_OK, "");
    }

    private List<RawQuote> real(JsonNode data, Instant receivedAt) {
        List<RawQuote> out = new ArrayList<>();
        LocalDate today = receivedAt.atZone(SEOUL).toLocalDate();
        for (JsonNode d : data) {
            String type = d.path("type").asText("");
            String item = d.path("item").asText("");
            JsonNode v = d.path("values");
            try {
                Symbol symbol = Symbol.krx(item);
                switch (type) {
                    case "0B" -> trade(symbol, v, today).ifPresent(out::add);
                    case "0D" -> book(symbol, v, today).ifPresent(out::add);
                    default -> { }
                }
            } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException e) {
                // 한 항목 형식 오류(수량 소수·범위 초과 포함)는 그 항목만 버린다(다른 종목 시세는 살린다)
            }
        }
        return out;
    }

    private static java.util.Optional<RawQuote> trade(Symbol symbol, JsonNode v, LocalDate today) {
        String venue = v.path("9081").asText("").strip();
        if (!venue.isEmpty() && !venue.equals("KRX")) {
            return java.util.Optional.empty(); // 9081 거래소구분: KRX 단독 시세만 녹화한다(NXT 체결이 섞이지 않게)
        }
        BigDecimal price = unsigned(v.path("10").asText(""));
        BigDecimal qty = unsigned(v.path("15").asText(""));
        if (price == null || qty == null || price.signum() == 0 || qty.signum() == 0) {
            return java.util.Optional.empty();
        }
        Instant t = at(today, v.path("20").asText(""));
        return java.util.Optional.of(new RawQuote.Trade(symbol, t, price, qty.longValueExact()));
    }

    private static java.util.Optional<RawQuote> book(Symbol symbol, JsonNode v, LocalDate today) {
        List<PriceLevel> asks = new ArrayList<>();
        List<PriceLevel> bids = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            level(v, 40 + i, 60 + i, asks);
            level(v, 50 + i, 70 + i, bids);
        }
        Instant t = at(today, v.path("21").asText(""));
        return java.util.Optional.of(new RawQuote.Book(symbol, t, asks, bids));
    }

    private static void level(JsonNode v, int priceKey, int qtyKey, List<PriceLevel> out) {
        BigDecimal p = unsigned(v.path(Integer.toString(priceKey)).asText(""));
        BigDecimal q = unsigned(v.path(Integer.toString(qtyKey)).asText(""));
        if (p != null && q != null && p.signum() > 0) {
            out.add(new PriceLevel(p, q.longValueExact()));
        }
    }

    /** "+71500", "-20800", " 67300" → 절댓값. 빈 값이면 null. */
    static BigDecimal unsigned(String raw) {
        String s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            s = s.substring(1).strip();
        }
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    private static Instant at(LocalDate date, String hhmmss) {
        return date.atTime(LocalTime.parse(hhmmss, HHMMSS)).atZone(SEOUL).toInstant();
    }
}
