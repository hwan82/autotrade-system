package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.MarketDataMode;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.recording.RecordedDay;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayTest {

    static final Instant T0 = Instant.parse("2026-10-08T01:00:00Z"); // 10:00 KST
    static final Symbol SAMSUNG = new Symbol(Market.KRX, "005930");
    static final Symbol HYNIX = new Symbol(Market.KRX, "000660");
    static final Symbol KAKAO = new Symbol(Market.KRX, "035720");

    @TempDir
    Path dir;

    /** 시험 중 앞으로 옮길 수 있는 시계. */
    static final class Mutable extends Clock {
        Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final Mutable clock = new Mutable();
    final FakePlugin kiwoom = new FakePlugin(BrokerId.KIWOOM, 2);
    final FakePlugin kis = new FakePlugin(BrokerId.KIS, 2);

    Gateway start(WatchList watch) {
        Gateway g = Gateway.start(MarketDataMode.AUTO, List.of(kiwoom, kis), watch, dir, clock);
        kiwoom.source.set(ConnectionState.CONNECTED);
        kis.source.set(ConnectionState.CONNECTED);
        return g;
    }

    @Test
    void 기동하면_두_소스에_나눠_구독하고_연결을_시작한다() {
        try (Gateway g = start(new WatchList(List.of(SAMSUNG), List.of(HYNIX, KAKAO)))) {
            assertThat(kiwoom.source.started).isTrue();
            assertThat(kiwoom.source.registered()).containsExactlyInAnyOrder(SAMSUNG, HYNIX);
            assertThat(kis.source.registered()).containsExactly(KAKAO);
            assertThat(g.hubs()).containsOnlyKeys(Market.KRX);
        }
        assertThat(kiwoom.source.closed).isTrue();
    }

    @Test
    void 키움이_끊기면_보유_종목을_한투로_옮기고_전환_공백을_녹화한다() {
        Gateway g = start(new WatchList(List.of(SAMSUNG), List.of(HYNIX, KAKAO)));
        Instant down = T0.plusSeconds(5);
        clock.now = down;
        kiwoom.source.set(ConnectionState.DISCONNECTED);

        // 한투 한도 2: 보유(삼성) 먼저, 후보 1순위(하이닉스). 카카오는 제외
        assertThat(kis.source.registered()).containsExactly(HYNIX, SAMSUNG);
        assertThat(kiwoom.source.registered()).isEmpty();
        assertThat(kis.source.maxUnion).isLessThanOrEqualTo(2);
        SourcePlan plan = g.hubs().get(Market.KRX).currentPlan();
        assertThat(plan.mode()).isEqualTo(MarketDataMode.FAILOVER);
        assertThat(plan.excluded()).containsExactly(KAKAO);

        // 재접속 시도 중(CONNECTING)에는 아직 되돌리지 않는다
        clock.now = T0.plusSeconds(8);
        kiwoom.source.set(ConnectionState.CONNECTING);
        assertThat(kiwoom.source.registered()).isEmpty();

        kis.source.emit(new TradeTick(SAMSUNG, 1, clock.now, clock.now, new BigDecimal("70000"), 10));
        g.close();

        RecordedDay day = RecordedDay.open(new RecordingLayout(dir).dayDir(Market.KRX, LocalDate.of(2026, 10, 8)));
        List<DataGap> gaps = day.lines().stream().map(RecordingCodec.Line::event)
                .filter(DataGap.class::isInstance).map(DataGap.class::cast).toList();
        // 한도 밖으로 밀린 카카오는 받을 소스가 없어 밀린 시각부터 종료 시각까지 공백으로 남는다
        assertThat(gaps).filteredOn(gap -> gap.symbol().equals(KAKAO)).singleElement().satisfies(gap -> {
            assertThat(gap.gapStart()).isEqualTo(down);
            assertThat(gap.gapEnd()).isEqualTo(T0.plusSeconds(8));
        });
        gaps = gaps.stream().filter(gap -> !gap.symbol().equals(KAKAO)).toList();
        assertThat(gaps).extracting(DataGap::symbol).containsExactly(HYNIX, SAMSUNG);
        assertThat(gaps).allSatisfy(gap -> {
            assertThat(gap.reason()).isEqualTo(DataGap.GapReason.SOURCE_SWITCH);
            assertThat(gap.gapStart()).isEqualTo(down);
            assertThat(gap.gapEnd()).isEqualTo(down);
        });
        assertThat(day.lines()).anySatisfy(l -> {
            assertThat(l.sourceId()).isEqualTo("KIS-FAKE");
            assertThat(l.event()).isInstanceOf(TradeTick.class);
        });
    }

    @Test
    void 키움이_다시_연결되면_1순위로_되돌린다() {
        try (Gateway g = start(new WatchList(List.of(SAMSUNG), List.of(HYNIX, KAKAO)))) {
            kiwoom.source.set(ConnectionState.DISCONNECTED);
            kiwoom.source.set(ConnectionState.CONNECTED);

            assertThat(kiwoom.source.registered()).containsExactlyInAnyOrder(SAMSUNG, HYNIX);
            assertThat(kis.source.registered()).containsExactly(KAKAO);
            assertThat(g.hubs().get(Market.KRX).currentPlan().mode()).isEqualTo(MarketDataMode.SHARDED);
        }
    }

    @Test
    void 끊김과_재연결_알림의_순서가_뒤바뀌어도_소스의_현재_상태를_따른다() {
        try (Gateway g = start(new WatchList(List.of(SAMSUNG), List.of(HYNIX, KAKAO)))) {
            // 이미 재연결된 뒤에 늦게 도착한 끊김 알림
            kiwoom.source.state = ConnectionState.CONNECTED;
            kiwoom.source.set(ConnectionState.CONNECTED);
            kiwoom.source.state = ConnectionState.DISCONNECTED;
            kiwoom.source.set(ConnectionState.DISCONNECTED);
            kiwoom.source.state = ConnectionState.CONNECTED;
            kiwoom.source.fireOnly(ConnectionState.DISCONNECTED); // 늦은 알림, 실제 상태는 CONNECTED

            assertThat(kiwoom.source.registered()).containsExactlyInAnyOrder(SAMSUNG, HYNIX);
            assertThat(g.hubs().get(Market.KRX).currentPlan().mode()).isEqualTo(MarketDataMode.SHARDED);
        }
    }

    @Test
    void 기동부터_접속하지_못한_소스의_종목은_다음_소스가_받는다() {
        try (Gateway g = Gateway.start(MarketDataMode.AUTO, List.of(kiwoom, kis), new WatchList(List.of(SAMSUNG), List.of()),
                dir, clock)) {
            assertThat(kiwoom.source.registered()).containsExactly(SAMSUNG);
            kiwoom.source.set(ConnectionState.DISCONNECTED); // 첫 접속 실패
            assertThat(kis.source.registered()).containsExactly(SAMSUNG);
            assertThat(g.hubs().get(Market.KRX).currentPlan().mode()).isEqualTo(MarketDataMode.FAILOVER);
            assertThat(kiwoom.source.registered()).isEmpty();
        }
    }

    List<DataGap> gaps() {
        RecordedDay day = RecordedDay.open(new RecordingLayout(dir).dayDir(Market.KRX, LocalDate.of(2026, 10, 8)));
        return day.lines().stream().map(RecordingCodec.Line::event)
                .filter(DataGap.class::isInstance).map(DataGap.class::cast).toList();
    }

    @Test
    void 옮길_때_공백은_옛_소스에서_마지막으로_받은_시각부터다() {
        Gateway g = start(new WatchList(List.of(SAMSUNG), List.of()));
        kiwoom.source.lastReceived = T0.plusSeconds(3);
        clock.now = T0.plusSeconds(60); // 무응답으로 늦게 알아챔
        kiwoom.source.set(ConnectionState.DISCONNECTED);
        g.close();

        assertThat(gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.reason()).isEqualTo(DataGap.GapReason.SOURCE_SWITCH);
            assertThat(gap.gapStart()).isEqualTo(T0.plusSeconds(3));
            assertThat(gap.gapEnd()).isEqualTo(T0.plusSeconds(60));
        });
    }

    @Test
    void 모든_소스가_끊겼다가_돌아오면_받지_못한_구간을_남긴다() {
        Gateway g = start(new WatchList(List.of(SAMSUNG), List.of(KAKAO)));
        kiwoom.source.lastReceived = T0.plusSeconds(5);
        kis.source.lastReceived = T0.plusSeconds(7);
        clock.now = T0.plusSeconds(10);
        kiwoom.source.set(ConnectionState.DISCONNECTED); // 삼성·카카오가 한투로
        clock.now = T0.plusSeconds(20);
        kis.source.set(ConnectionState.DISCONNECTED);    // 받을 곳 없음
        assertThat(g.hubs().get(Market.KRX).currentPlan().unservedHeld()).containsExactly(SAMSUNG);
        clock.now = T0.plusSeconds(40);
        kiwoom.source.set(ConnectionState.CONNECTED);    // 다시 배정
        clock.now = T0.plusSeconds(50);
        g.close();

        List<DataGap> gaps = gaps();
        assertThat(gaps).filteredOn(gap -> gap.reason() == DataGap.GapReason.DISCONNECTED)
                .extracting(DataGap::symbol, DataGap::gapStart, DataGap::gapEnd)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SAMSUNG, T0.plusSeconds(7), T0.plusSeconds(40)),
                        org.assertj.core.groups.Tuple.tuple(KAKAO, T0.plusSeconds(7), T0.plusSeconds(40)));
    }

    @Test
    void 받을_소스_없이_끝나면_종료_시각까지_공백을_남긴다() {
        Gateway g = start(new WatchList(List.of(SAMSUNG), List.of()));
        kiwoom.source.lastReceived = T0.plusSeconds(2);
        kis.source.lastReceived = T0.plusSeconds(4);
        clock.now = T0.plusSeconds(10);
        kiwoom.source.set(ConnectionState.DISCONNECTED);
        kis.source.set(ConnectionState.DISCONNECTED);
        clock.now = T0.plusSeconds(30);
        g.close();

        assertThat(gaps()).filteredOn(gap -> gap.reason() == DataGap.GapReason.DISCONNECTED)
                .singleElement().satisfies(gap -> {
                    assertThat(gap.gapStart()).isEqualTo(T0.plusSeconds(4));
                    assertThat(gap.gapEnd()).isEqualTo(T0.plusSeconds(30));
                });
    }

    @Test
    void 감시_종목이_없으면_시장을_열지_않는다() {
        try (Gateway g = Gateway.start(MarketDataMode.AUTO, List.of(kiwoom, kis), WatchList.empty(), dir, clock)) {
            assertThat(g.hubs()).isEmpty();
            assertThat(kiwoom.source.started).isFalse();
        }
    }

    @Test
    void 감시_종목_파일을_읽는다() throws Exception {
        Path f = dir.resolve(WatchList.FILE_NAME);
        Files.writeString(f, "{\"held\":[\"KRX:005930\"],\"candidates\":[\"KRX:000660\",\"US:AAPL\"]}");
        WatchList w = WatchList.load(f);
        assertThat(w.held()).containsExactly(SAMSUNG);
        assertThat(w.forMarket(Market.US).candidates()).containsExactly(new Symbol(Market.US, "AAPL"));
        assertThat(WatchList.load(dir.resolve("none.json"))).isEqualTo(WatchList.empty());
    }
}
