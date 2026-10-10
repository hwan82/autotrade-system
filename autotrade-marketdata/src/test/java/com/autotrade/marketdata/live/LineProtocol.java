package com.autotrade.marketdata.live;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.SortedSet;
import java.util.stream.Collectors;

/**
 * 테스트용 간단한 프로토콜. 서버 메시지: {@code LOGIN OK}, {@code LOGIN FAIL}, {@code REJECT x},
 * {@code T <code> <price> <qty> <epochSec>}, {@code B <code> <ask> <bid> <epochSec>}, {@code PING}, {@code HEARTBEAT <내용>}(PONG 프레임으로 되돌림).
 */
final class LineProtocol implements StreamProtocol {

    int opened;
    int invalidated;
    boolean failPrepare;
    /** true면 발급 자체가 자격 증명 거절로 실패한다(HTTP 401·403과 같은 경우). */
    boolean rejectPrepare;
    int prepareAttempts;
    boolean loginAck = true;
    final int max;

    LineProtocol(int max) {
        this.max = max;
    }

    @Override
    public URI endpoint() {
        return URI.create("wss://example.invalid/ws");
    }

    @Override
    public Market market() {
        return Market.KRX;
    }

    @Override
    public int maxSymbols() {
        return max;
    }

    @Override
    public List<String> openingMessages() throws Exception {
        prepareAttempts++;
        if (rejectPrepare) {
            throw new CredentialsRejectedException("HTTP 401");
        }
        if (failPrepare) {
            throw new IllegalStateException("token");
        }
        opened++;
        return List.of("LOGIN");
    }

    @Override
    public boolean requiresLoginAck() {
        return loginAck;
    }

    @Override
    public List<String> subscribeMessages(SortedSet<Symbol> symbols) {
        return symbols.isEmpty() ? List.of() : List.of("SUB " + codes(symbols));
    }

    @Override
    public List<String> unsubscribeMessages(SortedSet<Symbol> symbols) {
        return symbols.isEmpty() ? List.of() : List.of("UNSUB " + codes(symbols));
    }

    private static String codes(SortedSet<Symbol> s) {
        return s.stream().map(Symbol::code).collect(Collectors.joining(","));
    }

    @Override
    public Decoded decode(String text, Instant receivedAt) {
        String[] p = text.split(" ");
        return switch (p[0]) {
            case "LOGIN" -> p[1].equals("OK") ? Decoded.control(Decoded.Control.LOGIN_OK, "")
                    : Decoded.control(Decoded.Control.LOGIN_FAILED, "8005");
            case "REJECT" -> Decoded.control(Decoded.Control.SUBSCRIBE_REJECTED, p[1]);
            case "PING" -> Decoded.reply("PONG");
            case "HEARTBEAT" -> Decoded.pong(text);
            case "T" -> Decoded.quotes(List.of(new RawQuote.Trade(Symbol.krx(p[1]), Instant.ofEpochSecond(Long.parseLong(p[4])),
                    new BigDecimal(p[2]), Long.parseLong(p[3]))));
            case "B" -> Decoded.quotes(List.of(new RawQuote.Book(Symbol.krx(p[1]), Instant.ofEpochSecond(Long.parseLong(p[4])),
                    List.of(new PriceLevel(new BigDecimal(p[2]), 1)), List.of(new PriceLevel(new BigDecimal(p[3]), 1)))));
            default -> Decoded.ignored(p[0]);
        };
    }

    @Override
    public void invalidateCredentials() {
        invalidated++;
    }
}
