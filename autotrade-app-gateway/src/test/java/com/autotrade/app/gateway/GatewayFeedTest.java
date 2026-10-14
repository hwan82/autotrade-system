package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.MarketDataMode;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.feed.FeedSocket;
import com.autotrade.marketdata.recording.EventSequences;
import com.autotrade.marketdata.recording.RecordedDay;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 게이트웨이가 녹화하는 줄을 실험실 전달(ADR-010, D-076)에도 같은 순서·같은 소스로 넘긴다. 전달이 없어도 녹화는 그대로다. */
class GatewayFeedTest {

    @TempDir
    Path dir;

    final GatewayTest.Mutable clock = new GatewayTest.Mutable();
    final FakePlugin kiwoom = new FakePlugin(BrokerId.KIWOOM, 2);
    final FakePlugin kis = new FakePlugin(BrokerId.KIS, 2);

    private record Line(String sourceId, long sourceSequence, MarketEvent event) {}

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 조건이 맞지 않았습니다");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void 녹화하는_줄을_실험실에도_같은_순서로_넘긴다() throws Exception {
        FeedServer feed = FeedServer.open(FeedSocket.path(dir));
        List<Line> got = new CopyOnWriteArrayList<>();
        try (FeedClient lab = new FeedClient(feed.socket(), Set.of(Market.KRX), "lab",
                (src, e) -> got.add(new Line(src, e.sequence(), EventSequences.withSequence(e, 0))), Clock.systemUTC())) {
            lab.start();
            await(() -> feed.subscriberCount() == 1);

            Gateway g = Gateway.start(MarketDataMode.AUTO, List.of(kiwoom, kis),
                    new WatchList(List.of(GatewayTest.SAMSUNG), List.of(GatewayTest.HYNIX, GatewayTest.KAKAO)),
                    dir.resolve("recordings"), feed, clock);
            kiwoom.source.set(ConnectionState.CONNECTED);
            kis.source.set(ConnectionState.CONNECTED);
            kiwoom.source.emit(new TradeTick(GatewayTest.SAMSUNG, 11, clock.now, clock.now, new BigDecimal("70000"), 3));
            kis.source.emit(new TradeTick(GatewayTest.KAKAO, 5, clock.now, clock.now, new BigDecimal("41000"), 1));
            clock.now = clock.now.plusSeconds(5);
            kiwoom.source.set(ConnectionState.DISCONNECTED); // 전환 공백도 녹화와 같이 넘어간다
            kis.source.emit(new TradeTick(GatewayTest.SAMSUNG, 6, clock.now, clock.now, new BigDecimal("70100"), 2));

            RecordingLayout layout = new RecordingLayout(dir.resolve("recordings"));
            await(() -> got.size() >= 4);
            int seen;
            do { // 전달 중인 줄이 다 올 때까지
                seen = got.size();
                Thread.sleep(300);
            } while (got.size() != seen);
            g.close();
            assertThat(feed.socket()).doesNotExist(); // 게이트웨이가 닫을 때 전달도 닫는다

            List<Line> recorded = RecordedDay.open(layout.dayDir(Market.KRX, LocalDate.of(2026, 10, 8))).lines().stream()
                    .map(l -> new Line(l.sourceId(), l.sourceSequence(), EventSequences.withSequence(l.event(), 0)))
                    .toList();
            assertThat(got).containsExactlyElementsOf(recorded);
            assertThat(got).anySatisfy(l -> assertThat(l.event()).isInstanceOf(DataGap.class));
            assertThat(got).extracting(Line::sourceId).contains("KIWOOM-FAKE", "KIS-FAKE");
        }
    }

    @Test
    void 전달_소켓을_열지_못해도_게이트웨이는_녹화만_한다() throws Exception {
        Files.writeString(dir.resolve("run"), "소켓 폴더 자리에 파일"); // 폴더를 만들 수 없다
        assertThat(GatewayMain.openFeed(dir)).isNull();

        try (Gateway g = Gateway.start(MarketDataMode.AUTO, List.of(kiwoom, kis),
                new WatchList(List.of(GatewayTest.SAMSUNG), List.of()), dir.resolve("recordings"), null, clock)) {
            kiwoom.source.set(ConnectionState.CONNECTED);
            kiwoom.source.emit(new TradeTick(GatewayTest.SAMSUNG, 1, clock.now, clock.now, new BigDecimal("70000"), 1));
        }
        assertThat(RecordedDay.open(new RecordingLayout(dir.resolve("recordings")).dayDir(Market.KRX,
                LocalDate.of(2026, 10, 8))).lines()).extracting(RecordingCodec.Line::sourceId).contains("KIWOOM-FAKE");
    }
}
