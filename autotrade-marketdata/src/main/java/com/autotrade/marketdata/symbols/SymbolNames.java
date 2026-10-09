package com.autotrade.marketdata.symbols;

import com.autotrade.domain.market.Symbol;
import java.util.Map;
import java.util.Optional;

/**
 * 종목 → 종목명 표. <b>화면·보고서 표시용</b>이며 계산·식별·주문·리스크 판단에 쓰지 않는다.
 * 이름이 있든 없든, 바뀌든 말든 시뮬레이션 결과와 해시는 달라지지 않는다(결정론 영향 없음).
 * 식별은 항상 {@link Symbol}(시장 + 코드)이다.
 *
 * <p>불변이다. 파일에서 읽는 일은 {@link SymbolNameFiles}가 한다.
 */
public final class SymbolNames {

    private static final SymbolNames EMPTY = new SymbolNames(Map.of());

    private final Map<Symbol, String> names;

    SymbolNames(Map<Symbol, String> names) {
        this.names = Map.copyOf(names);
    }

    /** 이름을 하나도 모르는 표. 파일이 없을 때의 기본값이다. */
    public static SymbolNames empty() {
        return EMPTY;
    }

    /** 종목명. 표에 없으면 빈 Optional. */
    public Optional<String> name(Symbol symbol) {
        return Optional.ofNullable(names.get(symbol));
    }

    public int size() {
        return names.size();
    }
}
