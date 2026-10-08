package com.autotrade.broker.kis;

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

/**
 * 한국투자 KIS Developers 국내주식 실시간 WebSocket({@code ws://ops.koreainvestment.com:21000/tryitout}).
 *
 * <p>`[사실]` 근거: koreainvestment/open-trading-api (kis_devlp.yaml, examples_user/kis_auth.py,
 * examples_user/domestic_stock/domestic_stock_functions_ws.py, 2026-10-08 확인)
 * <ul>
 *   <li>구독 {@code {"header":{"approval_key":…,"custtype":"P","tr_type":"1","content-type":"utf-8"},
 *       "body":{"input":{"tr_id":"H0STCNT0","tr_key":"005930"}}}}. 해지는 tr_type "2"(kis_auth.py).</li>
 *   <li>시세 프레임 {@code 0|H0STCNT0|003|필드^필드^…}: 암호화 여부|TR|건수|데이터. 건수만큼 레코드가 이어 붙는다.
 *       제어 프레임은 JSON이며 {@code header.tr_id == "PINGPONG"}이면 받은 그대로 돌려준다.</li>
 *   <li>H0STCNT0 체결: 0 종목코드, 1 체결시각(HHmmss), 2 현재가, 12 체결량, 33 영업일자(yyyyMMdd).
 *       H0STASP0 호가: 0 종목코드, 1 영업시각, 3~12 매도호가1~10, 13~22 매수호가1~10, 23~32 매도잔량, 33~42 매수잔량.</li>
 *   <li>한도: appkey당 41건, 체결과 호가를 따로 센다(docs/research/market-data-limits.md H1·H2). 그래서 20종목.</li>
 * </ul>
 * 접속 주소가 평문 ws다(공식 주소). 접속키가 평문으로 오간다는 위험은 ADR-002 4장에 적었다.
 * 암호화 프레임(첫 값 1)은 체결통보용이라 시세 소스에서는 쓰지 않고 버린다.
 */
final class KisStreamProtocol implements StreamProtocol {

    static final URI ENDPOINT = URI.create("ws://ops.koreainvestment.com:21000/tryitout");
    static final int MAX_ENTRIES = 41;
    static final int MAX_SYMBOLS = MAX_ENTRIES / 2;
    static final String TRADE = "H0STCNT0";
    static final String BOOK = "H0STASP0";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int TRADE_MIN_FIELDS = 34;
    private static final int BOOK_MIN_FIELDS = 43;

    private final TokenCache approvals;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String approvalKey = "";

    KisStreamProtocol(TokenCache approvals) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override
    public URI endpoint() {
        return ENDPOINT;
    }

    @Override
    public Market market() {
        return Market.KRX;
    }

    @Override
    public int maxSymbols() {
        return MAX_SYMBOLS;
    }

    /** 로그인 메시지는 없고, 구독 메시지마다 접속키를 싣는다. 여기서 접속키를 받아 둔다. */
    @Override
    public List<String> openingMessages() throws Exception {
        approvalKey = approvals.get();
        return List.of();
    }

    @Override
    public boolean requiresLoginAck() {
        return false;
    }

    @Override
    public void invalidateCredentials() {
        approvals.invalidate();
    }

    @Override
    public List<String> subscribeMessages(SortedSet<Symbol> symbols) {
        return messages("1", symbols);
    }

    @Override
    public List<String> unsubscribeMessages(SortedSet<Symbol> symbols) {
        return messages("2", symbols);
    }

    private List<String> messages(String trType, SortedSet<Symbol> symbols) {
        List<String> out = new ArrayList<>();
        for (Symbol s : symbols) {
            out.add(message(trType, TRADE, s.code()));
            out.add(message(trType, BOOK, s.code()));
        }
        return out;
    }

    private String message(String trType, String trId, String code) {
        ObjectNode m = mapper.createObjectNode();
        ObjectNode h = m.putObject("header");
        h.put("approval_key", approvalKey);
        h.put("custtype", "P");
        h.put("tr_type", trType);
        h.put("content-type", "utf-8");
        ObjectNode in = m.putObject("body").putObject("input");
        in.put("tr_id", trId);
        in.put("tr_key", code);
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Decoded decode(String text, Instant receivedAt) {
        if (text.isEmpty()) {
            return Decoded.ignored("빈 메시지");
        }
        char first = text.charAt(0);
        if (first == '0' || first == '1') {
            return frame(text, receivedAt);
        }
        JsonNode n;
        try {
            n = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            return Decoded.ignored("JSON 아님");
        }
        if (n == null || !n.isObject()) {
            return Decoded.ignored("JSON 객체 아님");
        }
        String trId = n.path("header").path("tr_id").asText("");
        if (trId.equals("PINGPONG")) {
            return Decoded.reply(text);
        }
        JsonNode body = n.path("body");
        String rt = body.path("rt_cd").asText("0");
        if (!rt.equals("0")) {
            return Decoded.control(Decoded.Control.SUBSCRIBE_REJECTED,
                    trId + " " + body.path("msg_cd").asText("") + " " + body.path("msg1").asText(""));
        }
        return Decoded.ignored(trId + " " + body.path("msg1").asText(""));
    }

    private Decoded frame(String text, Instant receivedAt) {
        String[] head = text.split("\\|", 4);
        if (head.length < 4) {
            return Decoded.ignored("프레임 형식 오류");
        }
        if (head[0].equals("1")) {
            return Decoded.ignored("암호화 프레임");
        }
        int count;
        try {
            count = Integer.parseInt(head[2]);
        } catch (NumberFormatException e) {
            return Decoded.ignored("건수 형식 오류");
        }
        String[] f = head[3].split("\\^", -1);
        if (count <= 0 || f.length % count != 0) {
            return Decoded.ignored("건수와 필드 수 불일치");
        }
        int width = f.length / count;
        LocalDate today = receivedAt.atZone(SEOUL).toLocalDate();
        List<RawQuote> out = new ArrayList<>();
        for (int r = 0; r < count; r++) {
            int base = r * width;
            try {
                switch (head[1]) {
                    case TRADE -> {
                        if (width >= TRADE_MIN_FIELDS) {
                            trade(f, base).ifPresent(out::add);
                        }
                    }
                    case BOOK -> {
                        if (width >= BOOK_MIN_FIELDS) {
                            out.add(book(f, base, today));
                        }
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException e) {
                // 형식이 틀린 레코드만 버린다
            }
        }
        return out.isEmpty() ? Decoded.ignored(head[1] + " 해석 결과 없음") : Decoded.quotes(out);
    }

    private static java.util.Optional<RawQuote> trade(String[] f, int b) {
        BigDecimal price = new BigDecimal(f[b + 2].strip());
        long qty = Long.parseLong(f[b + 12].strip());
        if (price.signum() <= 0 || qty <= 0) {
            return java.util.Optional.empty();
        }
        LocalDate date = LocalDate.parse(f[b + 33].strip(), YYYYMMDD);
        Instant t = date.atTime(LocalTime.parse(f[b + 1].strip(), HHMMSS)).atZone(SEOUL).toInstant();
        return java.util.Optional.of(new RawQuote.Trade(Symbol.krx(f[b].strip()), t, price, qty));
    }

    private static RawQuote book(String[] f, int b, LocalDate today) {
        List<PriceLevel> asks = new ArrayList<>();
        List<PriceLevel> bids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            level(f[b + 3 + i], f[b + 23 + i], asks);
            level(f[b + 13 + i], f[b + 33 + i], bids);
        }
        Instant t = today.atTime(LocalTime.parse(f[b + 1].strip(), HHMMSS)).atZone(SEOUL).toInstant();
        return new RawQuote.Book(Symbol.krx(f[b].strip()), t, asks, bids);
    }

    private static void level(String price, String qty, List<PriceLevel> out) {
        BigDecimal p = new BigDecimal(price.strip());
        if (p.signum() > 0) {
            out.add(new PriceLevel(p, Long.parseLong(qty.strip())));
        }
    }
}
