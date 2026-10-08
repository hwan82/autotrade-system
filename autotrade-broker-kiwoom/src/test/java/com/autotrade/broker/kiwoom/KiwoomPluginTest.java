package com.autotrade.broker.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.live.LiveRuntime;
import com.autotrade.marketdata.live.Scheduler;
import com.autotrade.marketdata.live.SerialDispatcher;
import com.autotrade.marketdata.live.WsConnection;
import com.autotrade.marketdata.live.WsHandler;
import com.autotrade.marketdata.live.WsTransport;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class KiwoomPluginTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T07:52:09Z"), ZoneOffset.UTC);

    /** 예약 작업을 바로 실행하는 스케줄러(지연 0인 첫 접속만 실행하고 나머지는 무시). */
    private static final Scheduler NOW = (delay, task) -> {
        if (delay.isZero()) {
            task.run();
        }
        return () -> {};
    };

    private final List<String> sent = new ArrayList<>();
    private WsHandler handler;
    private URI connectedTo;

    private final WsTransport transport = (endpoint, h) -> {
        connectedTo = endpoint;
        handler = h;
        return CompletableFuture.completedFuture(new WsConnection() {
            @Override
            public void send(String text) {
                sent.add(text);
            }

            @Override
            public void close() {}
        });
    };

    private LiveRuntime runtime(FakeHttp http) {
        return new LiveRuntime(transport, http, CLOCK, NOW, id -> SerialDispatcher.direct());
    }

    @Test
    void 키가_없으면_설정_안_됨이고_소스를_만들지_않는다() {
        KiwoomPlugin p = KiwoomPlugin.create(BrokerCredentials.none(), runtime(new FakeHttp()));
        assertThat(p.status()).isEqualTo(BrokerStatus.NOT_CONFIGURED);
        assertThat(p.capabilities()).isEmpty();
        assertThat(p.marketData(Market.KRX)).isEmpty();
    }

    @Test
    void 시세_능력만_선언하고_주문_출구는_없다() {
        KiwoomPlugin p = KiwoomPlugin.create(new BrokerCredentials("A", "B"), runtime(new FakeHttp()));
        assertThat(p.capabilities()).containsExactly(BrokerCapability.REALTIME_QUOTE).doesNotContain(BrokerCapability.ORDER);
        assertThat(p.orderGateway(new AccountRef(new AccountId("KIWOOM-KRX-1"), BrokerId.KIWOOM, Market.KRX))).isEmpty();
        assertThat(p.marketData(Market.US)).isEmpty();
        assertThat(p.status()).isEqualTo(BrokerStatus.CONFIGURED);
    }

    @Test
    void 접속_인증_구독_수신까지_이어진다() {
        FakeHttp http = new FakeHttp().respond(200, KiwoomStreamProtocolTest.TOKEN_OK);
        KiwoomPlugin p = KiwoomPlugin.create(new BrokerCredentials("A", "B"), runtime(http));
        List<MarketEvent> events = new ArrayList<>();
        p.marketData(Market.KRX).orElseThrow().subscribe(Set.of(Symbol.krx("005930")), events::add);
        p.liveSource().orElseThrow().start();

        assertThat(connectedTo).hasToString("wss://api.kiwoom.com:10000/api/dostk/websocket");
        handler.onText("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        assertThat(p.status()).isEqualTo(BrokerStatus.ACTIVE);
        assertThat(sent.get(1)).contains("\"trnm\":\"REG\"").contains("005930");

        handler.onText(KiwoomStreamProtocolTest.REAL_0B);
        assertThat(events).singleElement().isInstanceOf(TradeTick.class);

        handler.onClosed(1006, "");
        assertThat(p.status()).isEqualTo(BrokerStatus.DEGRADED);
        p.liveSource().orElseThrow().close();
    }
}
