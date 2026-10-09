package com.autotrade.risk;

import com.autotrade.domain.market.Symbol;
import java.util.Objects;

/**
 * 블랙리스트 항목 하나(RSK-12). 종목은 시장을 포함하므로 국내와 미국의 같은 코드는 다른 항목이다.
 *
 * @param reason 사람이 읽는 등록 사유. 비밀값을 넣지 않는다. 스냅샷 지문에는 들어가지 않는다
 */
public record BlacklistEntry(Symbol symbol, BlacklistType type, String reason) {

    public BlacklistEntry {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
    }
}
