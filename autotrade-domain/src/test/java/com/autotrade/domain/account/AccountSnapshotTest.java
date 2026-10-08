package com.autotrade.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class AccountSnapshotTest {

    private static final AccountId ACC = new AccountId("EXP-1-A01");
    private static final Instant T = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void 포지션은_종목순으로_정렬되고_원본_변경에_영향받지_않는다() {
        Map<Symbol, Position> source = new HashMap<>();
        source.put(Symbol.krx("005930"), pos("005930", 10));
        source.put(Symbol.krx("000660"), pos("000660", 5));
        AccountSnapshot snapshot = new AccountSnapshot(ACC, T, Money.of(1_000_000, Money.KRW), new TreeMap<>(source));
        source.clear();

        assertThat(snapshot.positions().keySet()).containsExactly(Symbol.krx("000660"), Symbol.krx("005930"));
        assertThat(snapshot.quantityOf(Symbol.krx("005930"))).isEqualTo(10);
        assertThat(snapshot.quantityOf(Symbol.krx("035420"))).isZero();
        assertThatThrownBy(() -> snapshot.positions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 계좌_통화와_다른_포지션은_거부한다() {
        TreeMap<Symbol, Position> positions = new TreeMap<>();
        positions.put(Symbol.us("AAPL"), new Position(Symbol.us("AAPL"), 1, Money.of("200", Money.USD)));
        assertThatThrownBy(() -> new AccountSnapshot(ACC, T, Money.zero(Money.KRW), positions))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Position pos(String code, long qty) {
        return new Position(Symbol.krx(code), qty, Money.of(70_000, Money.KRW));
    }
}
