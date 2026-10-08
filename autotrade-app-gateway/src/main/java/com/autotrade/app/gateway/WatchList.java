package com.autotrade.app.gateway;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 감시 종목(MD-04). {@code held}는 보유 종목이라 한도를 넘어도 빼지 않고, {@code candidates}는 우선순위 순서라
 * 한도를 넘으면 뒤에서부터 뺀다. 같은 종목이 둘 다에 있으면 보유로 본다.
 *
 * <p>2단계는 설정 폴더의 {@code watchlist.json}에서 기동 때 읽는다(ADR-002 2.2). 보유 종목은 실전 엔진, 후보는
 * 장전 후보 풀(Stage 0)이 채우는 연동은 3·7단계에서 붙인다.
 * <pre>{"held":["KRX:005930"],"candidates":["KRX:000660","US:AAPL"]}</pre>
 */
public record WatchList(List<Symbol> held, List<Symbol> candidates) {

    public static final String FILE_NAME = "watchlist.json";

    public WatchList {
        LinkedHashSet<Symbol> h = new LinkedHashSet<>(Objects.requireNonNull(held, "held"));
        LinkedHashSet<Symbol> c = new LinkedHashSet<>(Objects.requireNonNull(candidates, "candidates"));
        c.removeAll(h);
        held = List.copyOf(h);
        candidates = List.copyOf(c);
    }

    public static WatchList empty() {
        return new WatchList(List.of(), List.of());
    }

    /** 파일이 없으면 빈 목록. 형식이 틀리면 예외(조용히 비우지 않는다). */
    public static WatchList load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return empty();
        }
        ObjectMapper m = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        Raw raw = m.readValue(file.toFile(), Raw.class);
        return new WatchList(parse(raw.held()), parse(raw.candidates()));
    }

    private static List<Symbol> parse(List<String> texts) {
        List<Symbol> out = new ArrayList<>();
        if (texts == null) {
            return out;
        }
        for (String t : texts) {
            int i = t.indexOf(':');
            if (i <= 0) {
                throw new IllegalArgumentException("종목 표기는 MARKET:CODE 형식이어야 합니다: " + t);
            }
            out.add(new Symbol(Market.valueOf(t.substring(0, i)), t.substring(i + 1)));
        }
        return out;
    }

    /** 이 시장의 감시 종목. 보유 먼저, 그다음 후보 순서. */
    public WatchList forMarket(Market market) {
        return new WatchList(held.stream().filter(s -> s.market() == market).toList(),
                candidates.stream().filter(s -> s.market() == market).toList());
    }

    record Raw(List<String> held, List<String> candidates) {}
}
