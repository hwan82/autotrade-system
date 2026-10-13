package com.autotrade.sim.testing;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트 녹화 데이터 읽기. 한 줄에 이벤트 하나: {@code seq|TYPE|code|거래소 현지 시각|수신 지연 ms|내용}.
 * <ul>
 *   <li>BOOK: {@code A:가격x잔량,...;B:가격x잔량,...} (A는 낮은 가격부터, B는 높은 가격부터)</li>
 *   <li>TRADE: {@code 가격x수량}</li>
 *   <li>STATUS: {@link InstrumentStatus} 이름</li>
 * </ul>
 * {@code #}으로 시작하는 줄과 빈 줄은 건너뛴다. 2단계 녹화 형식(gzip JSON Lines)과는 다르며, 같은 데이터를
 * {@link FixtureRecordings}로 녹화 형식에 써서 녹화 어댑터로 다시 읽는다(ADR-004 4장, D-068).
 */
public final class FixtureEvents {

    private FixtureEvents() {
    }

    public static List<MarketEvent> load(String resource, Market market, ZoneId zone) {
        List<MarketEvent> out = new ArrayList<>();
        try (InputStream in = FixtureEvents.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("리소스가 없습니다: " + resource);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                out.add(parse(line, market, zone));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 순번만 바꾼 사본. */
    public static MarketEvent withSequence(MarketEvent e, long seq) {
        return switch (e) {
            case TradeTick t -> new TradeTick(t.symbol(), seq, t.exchangeTime(), t.receivedAt(), t.price(), t.quantity());
            case OrderBookSnapshot b -> new OrderBookSnapshot(b.symbol(), seq, b.exchangeTime(), b.receivedAt(), b.asks(), b.bids());
            case InstrumentStatusChanged s -> new InstrumentStatusChanged(s.symbol(), seq, s.exchangeTime(), s.receivedAt(), s.status());
            case DataGap g -> new DataGap(g.symbol(), seq, g.exchangeTime(), g.receivedAt(), g.gapStart(), g.gapEnd(), g.reason());
        };
    }

    private static MarketEvent parse(String line, Market market, ZoneId zone) {
        String[] f = line.split("\\|", -1);
        long seq = Long.parseLong(f[0]);
        Symbol symbol = new Symbol(market, f[2]);
        Instant exchange = LocalDateTime.parse(f[3]).atZone(zone).toInstant();
        Instant received = exchange.plusMillis(Long.parseLong(f[4]));
        return switch (f[1]) {
            case "BOOK" -> {
                String[] sides = f[5].split(";", -1);
                yield new OrderBookSnapshot(symbol, seq, exchange, received, levels(sides[0], "A:"), levels(sides[1], "B:"));
            }
            case "TRADE" -> {
                String[] pq = f[5].split("x", -1);
                yield new TradeTick(symbol, seq, exchange, received, new BigDecimal(pq[0]), Long.parseLong(pq[1]));
            }
            case "STATUS" -> new InstrumentStatusChanged(symbol, seq, exchange, received, InstrumentStatus.valueOf(f[5]));
            default -> throw new IllegalArgumentException("모르는 이벤트: " + line);
        };
    }

    private static List<PriceLevel> levels(String text, String prefix) {
        if (!text.startsWith(prefix)) {
            throw new IllegalArgumentException("호가 형식이 아닙니다: " + text);
        }
        String body = text.substring(prefix.length());
        List<PriceLevel> out = new ArrayList<>();
        if (body.isEmpty()) {
            return out;
        }
        for (String lv : body.split(",", -1)) {
            String[] pq = lv.split("x", -1);
            out.add(new PriceLevel(new BigDecimal(pq[0]), Long.parseLong(pq[1])));
        }
        return out;
    }
}
