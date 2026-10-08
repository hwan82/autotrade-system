package com.autotrade.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class SymbolTest {

    @Test
    void 하이픈이_든_코드는_거부한다() {
        assertThatThrownBy(() -> Symbol.us("BRK-B")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Symbol.us("BRK.B").code()).isEqualTo("BRK.B");
    }

    @Test
    void 정렬은_시장_다음_코드_순이다() {
        TreeSet<Symbol> set = new TreeSet<>(List.of(Symbol.us("AAPL"), Symbol.krx("005930"), Symbol.krx("000660")));
        assertThat(set).containsExactly(Symbol.krx("000660"), Symbol.krx("005930"), Symbol.us("AAPL"));
    }
}
