package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlacklistSnapshotTest {

    private static final Symbol HYNIX = Symbol.krx("000660");
    private static final Symbol SAMSUNG = Symbol.krx("005930");

    @Test
    void 지문은_입력_순서와_사유에_영향받지_않는다() {
        BlacklistSnapshot a = BlacklistSnapshot.of(List.of(
                new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "가"),
                new BlacklistEntry(SAMSUNG, BlacklistType.AUTO_ASSET, "나")));
        BlacklistSnapshot b = BlacklistSnapshot.of(List.of(
                new BlacklistEntry(SAMSUNG, BlacklistType.AUTO_ASSET, "다른 사유"),
                new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "또 다른 사유")));

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).hasSize(64);
        assertThat(a.entries().keySet()).containsExactly(HYNIX, SAMSUNG);
    }

    @Test
    void 종목이나_유형이_다르면_지문이_다르다() {
        BlacklistSnapshot manual = BlacklistSnapshot.of(List.of(new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "")));
        BlacklistSnapshot auto = BlacklistSnapshot.of(List.of(new BlacklistEntry(HYNIX, BlacklistType.AUTO_ASSET, "")));
        // 시장이 다르면 같은 코드라도 다른 종목이다
        BlacklistSnapshot us = BlacklistSnapshot.of(List.of(
                new BlacklistEntry(Symbol.us("000660"), BlacklistType.MANUAL, "")));

        assertThat(manual.fingerprint()).isNotEqualTo(auto.fingerprint()).isNotEqualTo(us.fingerprint())
                .isNotEqualTo(BlacklistSnapshot.EMPTY.fingerprint());
        assertThat(us.contains(HYNIX)).isFalse();
    }

    @Test
    void 같은_종목이_두_유형이면_수동_보유를_남긴다() {
        BlacklistSnapshot s = BlacklistSnapshot.of(List.of(
                new BlacklistEntry(HYNIX, BlacklistType.AUTO_ASSET, "감지"),
                new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "지정")));
        BlacklistSnapshot reversed = BlacklistSnapshot.of(List.of(
                new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "지정"),
                new BlacklistEntry(HYNIX, BlacklistType.AUTO_ASSET, "감지")));

        assertThat(s.size()).isEqualTo(1);
        assertThat(s.find(HYNIX).type()).isEqualTo(BlacklistType.AUTO_ASSET);
        assertThat(reversed.find(HYNIX).type()).isEqualTo(BlacklistType.AUTO_ASSET);
    }

    @Test
    void 만든_뒤_입력을_바꿔도_스냅샷은_그대로다() {
        List<BlacklistEntry> input = new ArrayList<>(List.of(new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "")));
        BlacklistSnapshot s = BlacklistSnapshot.of(input);
        input.add(new BlacklistEntry(SAMSUNG, BlacklistType.MANUAL, ""));

        assertThat(s.contains(SAMSUNG)).isFalse();
        assertThatThrownBy(() -> s.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 빈_스냅샷은_아무것도_막지_않는다() {
        assertThat(BlacklistSnapshot.EMPTY.size()).isZero();
        assertThat(BlacklistSnapshot.EMPTY.contains(HYNIX)).isFalse();
        assertThat(BlacklistSnapshot.of(List.of())).isEqualTo(BlacklistSnapshot.EMPTY);
    }
}
