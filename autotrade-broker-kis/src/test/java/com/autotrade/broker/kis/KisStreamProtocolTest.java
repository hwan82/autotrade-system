package com.autotrade.broker.kis;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.live.Decoded;
import com.autotrade.marketdata.live.LiveRuntime;
import com.autotrade.marketdata.live.RawQuote;
import com.autotrade.marketdata.live.SerialDispatcher;
import com.autotrade.marketdata.live.TokenCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 필드 배치는 한국투자 공식 예제(domestic_stock_functions_ws.py ccnl_krx·asking_price_krx)의 컬럼 목록을 따른다. */
class KisStreamProtocolTest {

    private static final Instant RX = Instant.parse("2026-10-08T00:33:55Z"); // 09:33:55 KST
    private static final Clock CLOCK = Clock.fixed(RX, ZoneOffset.UTC);

    private final FakeHttp http = new FakeHttp().respond(200, "{\"approval_key\":\"APPROVAL-KEY-VALUE\"}");
    private final KisStreamProtocol protocol = new KisStreamProtocol(new TokenCache(
            new KisApprovalClient(http, new BrokerCredentials("APP-KEY", "SECRET-KEY"), CLOCK), CLOCK, Duration.ofHours(1)));

    /** H0STCNT0 한 레코드(46필드). */
    static String tradeRecord(String code, String hhmmss, String price, String qty) {
        List<String> f = new ArrayList<>(Collections.nCopies(46, "0"));
        f.set(0, code);
        f.set(1, hhmmss);
        f.set(2, price);
        f.set(12, qty);
        f.set(33, "20261008");
        return String.join("^", f);
    }

    /** H0STASP0 한 레코드(59필드, 컬럼 목록 뒤쪽이 늘어나도 앞 43개 위치는 같다). */
    static String bookRecord(String code) {
        List<String> f = new ArrayList<>(Collections.nCopies(59, "0"));
        f.set(0, code);
        f.set(1, "093354");
        for (int i = 0; i < 10; i++) {
            f.set(3 + i, Integer.toString(71100 + i * 100));   // 매도호가 오름차순
            f.set(13 + i, Integer.toString(71000 - i * 100));  // 매수호가 내림차순
            f.set(23 + i, Integer.toString(10 + i));
            f.set(33 + i, Integer.toString(20 + i));
        }
        f.set(12, "0"); // 매도 10호가 비어 있음
        return String.join("^", f);
    }

    @Test
    void 접속키를_받아_구독마다_싣고_종목당_체결과_호가_두_건을_등록한다() throws Exception {
        assertThat(protocol.openingMessages()).isEmpty();
        assertThat(http.calls.get(0).uri()).hasToString("https://openapi.koreainvestment.com:9443/oauth2/Approval");
        assertThat(http.calls.get(0).body()).contains("\"secretkey\":\"SECRET-KEY\"");
        var msgs = protocol.subscribeMessages(new TreeSet<>(Set.of(Symbol.krx("005930"))));
        assertThat(msgs).containsExactly(
                "{\"header\":{\"approval_key\":\"APPROVAL-KEY-VALUE\",\"custtype\":\"P\",\"tr_type\":\"1\",\"content-type\":\"utf-8\"},"
                        + "\"body\":{\"input\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"005930\"}}}",
                "{\"header\":{\"approval_key\":\"APPROVAL-KEY-VALUE\",\"custtype\":\"P\",\"tr_type\":\"1\",\"content-type\":\"utf-8\"},"
                        + "\"body\":{\"input\":{\"tr_id\":\"H0STASP0\",\"tr_key\":\"005930\"}}}");
        assertThat(protocol.unsubscribeMessages(new TreeSet<>(Set.of(Symbol.krx("005930")))))
                .allSatisfy(m -> assertThat(m).contains("\"tr_type\":\"2\""));
        assertThat(protocol.maxSymbols()).isEqualTo(20);
        assertThat(protocol.requiresLoginAck()).isFalse();
    }

    @Test
    void 여러_건이_이어_붙은_체결_프레임을_나눠_읽는다() {
        String frame = "0|H0STCNT0|002|" + tradeRecord("005930", "093354", "71900", "5") + "^"
                + tradeRecord("000660", "093355", "120000", "12");
        Decoded d = protocol.decode(frame, RX);
        assertThat(d.quotes()).hasSize(2);
        RawQuote.Trade t = (RawQuote.Trade) d.quotes().get(1);
        assertThat(t.symbol()).isEqualTo(Symbol.krx("000660"));
        assertThat(t.price()).isEqualByComparingTo("120000");
        assertThat(t.quantity()).isEqualTo(12);
        assertThat(t.exchangeTime()).isEqualTo(Instant.parse("2026-10-08T00:33:55Z"));
    }

    @Test
    void 호가_프레임을_읽고_빈_호가는_뺀다() {
        Decoded d = protocol.decode("0|H0STASP0|001|" + bookRecord("005930"), RX);
        RawQuote.Book b = (RawQuote.Book) d.quotes().get(0);
        assertThat(b.asks()).hasSize(9);
        assertThat(b.bids()).hasSize(10);
        assertThat(b.asks().get(0).price()).isEqualByComparingTo("71100");
        assertThat(b.bids().get(0).quantity()).isEqualTo(20);
        assertThat(b.toEvent(1, RX)).isNotNull();
    }

    @Test
    void PINGPONG은_그대로_돌려주고_거절과_암호화_프레임을_구분한다() {
        String ping = "{\"header\":{\"tr_id\":\"PINGPONG\",\"datetime\":\"20261008093355\"}}";
        assertThat(protocol.decode(ping, RX).replies()).containsExactly(ping);
        Decoded reject = protocol.decode("{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"005930\",\"encrypt\":\"N\"},"
                + "\"body\":{\"rt_cd\":\"1\",\"msg_cd\":\"OPSP0008\",\"msg1\":\"MAX SUBSCRIBE OVER\"}}", RX);
        assertThat(reject.control()).isEqualTo(Decoded.Control.SUBSCRIBE_REJECTED);
        assertThat(reject.detail()).contains("MAX SUBSCRIBE OVER");
        Decoded ok = protocol.decode("{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"005930\",\"encrypt\":\"N\"},"
                + "\"body\":{\"rt_cd\":\"0\",\"msg_cd\":\"OPSP0000\",\"msg1\":\"SUBSCRIBE SUCCESS\"}}", RX);
        assertThat(ok.control()).isEqualTo(Decoded.Control.IGNORED);
        assertThat(protocol.decode("1|H0STCNI0|001|abcdef", RX).quotes()).isEmpty();
        assertThat(protocol.decode("0|H0STCNT0|003|a^b", RX).control()).isEqualTo(Decoded.Control.IGNORED);
    }

    @Test
    void 플러그인은_시세_능력만_있고_평문_ws_주소로_접속한다() {
        List<java.net.URI> endpoints = new ArrayList<>();
        LiveRuntime rt = new LiveRuntime((endpoint, h) -> {
            endpoints.add(endpoint);
            return new CompletableFuture<>();
        }, new FakeHttp().respond(200, "{\"approval_key\":\"K\"}"), CLOCK, (delay, task) -> {
            if (delay.isZero()) {
                task.run();
            }
            return () -> {};
        }, id -> SerialDispatcher.direct());
        KisPlugin p = KisPlugin.create(new BrokerCredentials("A", "B"), rt);
        assertThat(p.capabilities()).containsExactly(BrokerCapability.REALTIME_QUOTE);
        assertThat(p.marketData(Market.KRX)).isPresent();
        assertThat(p.status()).isEqualTo(BrokerStatus.CONFIGURED);
        p.liveSource().orElseThrow().start();
        assertThat(endpoints).containsExactly(java.net.URI.create("ws://ops.koreainvestment.com:21000/tryitout"));
        assertThat(KisPlugin.create(BrokerCredentials.none(), rt).status()).isEqualTo(BrokerStatus.NOT_CONFIGURED);
    }
}
