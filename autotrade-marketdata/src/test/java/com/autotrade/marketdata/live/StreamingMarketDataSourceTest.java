package com.autotrade.marketdata.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamingMarketDataSourceTest {

    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");

    private MutableClock clock;
    private ManualScheduler scheduler;
    private FakeTransport transport;
    private LineProtocol protocol;
    private final List<MarketEvent> received = new ArrayList<>();
    private final List<ConnectionState> states = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        scheduler = new ManualScheduler(clock);
        transport = new FakeTransport();
        protocol = new LineProtocol(3);
    }

    private StreamingMarketDataSource source(SerialDispatcher dispatcher) {
        StreamingMarketDataSource s = new StreamingMarketDataSource("TEST-WS", protocol, transport, clock, scheduler,
                dispatcher, new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(8)), Duration.ofSeconds(60));
        s.addStateListener((id, st) -> states.add(st));
        return s;
    }

    private StreamingMarketDataSource connected() {
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        s.subscribe(Set.of(SAMSUNG, HYNIX), received::add);
        s.start();
        scheduler.runDue();
        transport.last().push("LOGIN OK");
        return s;
    }

    @Test
    void 인증_응답_뒤에_구독을_등록하고_순번을_매긴다() {
        StreamingMarketDataSource s = connected();
        FakeTransport.Conn c = transport.last();
        assertThat(c.sent).containsExactly("LOGIN", "SUB 000660,005930");
        assertThat(s.state()).isEqualTo(ConnectionState.CONNECTED);

        clock.set(T0.plusSeconds(1));
        c.push("T 005930 71500 10 1791446400");
        c.push("B 000660 120500 120000 1791446401");
        c.push("T 035720 50000 1 1791446401"); // 구독하지 않은 종목은 버린다

        assertThat(received).hasSize(2);
        assertThat(received).extracting(MarketEvent::sequence).containsExactly(1L, 2L);
        assertThat(received.get(0)).isInstanceOf(TradeTick.class);
        assertThat(received.get(0).receivedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(s.stats().unsubscribed()).isEqualTo(1);
    }

    @Test
    void 해석_못_한_메시지는_버리고_연결을_유지한다() {
        StreamingMarketDataSource s = connected();
        FakeTransport.Conn c = transport.last();
        c.push("T 005930 not-a-price 10 1791446400");
        c.push("T 005930 71500 10 1791446400");

        assertThat(s.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(s.stats().malformed()).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).sequence()).isEqualTo(1L);
    }

    @Test
    void 로그인_응답이_없는_프로토콜에서_인증_실패가_이어져도_재발급은_1분에_한_번이고_3회면_거부로_본다() {
        protocol.loginAck = false;
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        s.subscribe(Set.of(SAMSUNG), received::add);
        s.start();
        scheduler.runDue();
        // 10분 동안 접속마다 인증 실패
        for (int sec = 0; sec < 600; sec++) {
            if (s.state() == ConnectionState.CONNECTED) {
                transport.last().push("LOGIN FAIL");
            }
            scheduler.advance(Duration.ofSeconds(1));
        }
        assertThat(protocol.opened).isBetween(10, 11);      // 접속(=자격 증명 준비)은 60초에 한 번 꼴
        assertThat(protocol.invalidated).isBetween(protocol.opened - 1, protocol.opened); // 마지막 접속은 아직 실패 전일 수 있다
        assertThat(s.credentialsRejected()).isTrue();
        assertThat(s.stats().authFailures()).isGreaterThanOrEqualTo(3);

        // 시세가 다시 오면 인증이 확인돼 풀린다
        scheduler.advance(Duration.ofSeconds(61));
        assertThat(s.state()).isEqualTo(ConnectionState.CONNECTED);
        transport.last().push("T 005930 71500 1 1791446400");
        assertThat(s.credentialsRejected()).isFalse();
    }

    @Test
    void PING에_응답한다() {
        connected();
        transport.last().push("PING");
        assertThat(transport.last().sent).endsWith("PONG");
    }

    @Test
    void 끊기면_재연결하고_누락_구간을_종목마다_표시하고_구독을_복구한다() {
        StreamingMarketDataSource s = connected();
        clock.set(T0.plusSeconds(10));
        transport.last().push("T 005930 71500 10 1791446400");
        clock.set(T0.plusSeconds(12));
        transport.last().serverClose();
        assertThat(s.state()).isEqualTo(ConnectionState.DISCONNECTED);

        scheduler.advance(Duration.ofSeconds(1)); // 1초 뒤 재연결
        assertThat(transport.connections).hasSize(2);
        transport.last().push("LOGIN OK");

        assertThat(transport.last().sent).containsExactly("LOGIN", "SUB 000660,005930");
        List<DataGap> gaps = received.stream().filter(DataGap.class::isInstance).map(DataGap.class::cast).toList();
        assertThat(gaps).extracting(DataGap::symbol).containsExactly(HYNIX, SAMSUNG);
        assertThat(gaps).allSatisfy(g -> {
            assertThat(g.gapStart()).isEqualTo(T0.plusSeconds(10)); // 마지막으로 메시지를 받은 시각
            assertThat(g.gapEnd()).isEqualTo(T0.plusSeconds(13));
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.DISCONNECTED);
        });
        assertThat(received).extracting(MarketEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(states).containsExactly(ConnectionState.CONNECTING, ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED, ConnectionState.CONNECTING, ConnectionState.CONNECTED);
    }

    @Test
    void 접속_실패가_이어지면_대기를_두_배씩_늘린다() {
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        transport.failNext(3);
        s.start();
        scheduler.runDue();                       // 1회 실패, 1초 대기
        scheduler.advance(Duration.ofSeconds(1)); // 2회 실패, 2초 대기
        scheduler.advance(Duration.ofSeconds(1));
        assertThat(transport.connections).isEmpty();
        scheduler.advance(Duration.ofSeconds(1)); // 3회 실패, 4초 대기
        scheduler.advance(Duration.ofSeconds(3));
        assertThat(transport.connections).isEmpty();
        scheduler.advance(Duration.ofSeconds(1)); // 4회 성공
        assertThat(transport.connections).hasSize(1);
        assertThat(s.stats().connectFailures()).isEqualTo(3);
    }

    @Test
    void 기동_직후_접속_실패도_끊김으로_알린다() {
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        transport.failNext(1);
        s.start();
        scheduler.runDue();

        assertThat(s.state()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(states).containsExactly(ConnectionState.CONNECTING, ConnectionState.DISCONNECTED);
    }

    @Test
    void 인증_준비_실패도_재연결_대기로_넘긴다() {
        protocol.failPrepare = true;
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        s.start();
        scheduler.runDue();
        assertThat(transport.connections).isEmpty();
        protocol.failPrepare = false;
        scheduler.advance(Duration.ofSeconds(1));
        assertThat(transport.connections).hasSize(1);
        assertThat(s.state()).isEqualTo(ConnectionState.CONNECTING);
    }

    @Test
    void 인증이_실패하면_토큰을_버리고_다시_연결한다() {
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        s.start();
        scheduler.runDue();
        transport.last().push("LOGIN FAIL");
        assertThat(protocol.invalidated).isEqualTo(1);
        assertThat(transport.connections.get(0).closed).isTrue();
        scheduler.advance(Duration.ofSeconds(1));
        assertThat(transport.connections).hasSize(1); // 인증 실패 뒤에는 최소 60초 기다린다(발급 API 보호)
        scheduler.advance(Duration.ofSeconds(59));
        assertThat(transport.connections).hasSize(2);
        assertThat(s.state()).isEqualTo(ConnectionState.CONNECTING);
    }

    @Test
    void 무응답이_이어지면_끊고_다시_연결한다() {
        StreamingMarketDataSource s = connected();
        scheduler.advance(Duration.ofSeconds(91)); // 60초 무응답 감지(30초 간격 점검) 후 1초 대기 뒤 재연결
        assertThat(transport.connections.get(0).closed).isTrue();
        assertThat(transport.connections).hasSize(2);
        assertThat(s.stats().disconnects()).isEqualTo(1);
    }

    @Test
    void 구독_한도를_넘으면_거절한다() {
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        s.subscribe(Set.of(SAMSUNG, HYNIX), e -> {});
        assertThatThrownBy(() -> s.subscribe(Set.of(Symbol.krx("035720"), Symbol.krx("035420")), e -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("한도 3");
        // 이미 구독한 종목은 합집합에 다시 세지 않는다
        s.subscribe(Set.of(SAMSUNG, Symbol.krx("035720")), e -> {});
    }

    @Test
    void 다른_시장_종목은_거절한다() {
        StreamingMarketDataSource s = source(SerialDispatcher.direct());
        assertThatThrownBy(() -> s.subscribe(Set.of(Symbol.us("AAPL")), e -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 구독을_닫으면_다른_구독이_안_쓰는_종목만_해지한다() {
        StreamingMarketDataSource s = connected();
        Subscription only = s.subscribe(Set.of(SAMSUNG, Symbol.krx("035720")), e -> {});
        assertThat(transport.last().sent).endsWith("SUB 035720");
        only.close();
        assertThat(transport.last().sent).endsWith("UNSUB 035720");
        only.close(); // 여러 번 닫아도 안전
        assertThat(transport.last().sent).filteredOn(m -> m.startsWith("UNSUB")).hasSize(1);
    }

    @Test
    void 수신자_큐가_넘치면_버리고_회복_뒤_누락을_표시한다() {
        List<Runnable> queue = new ArrayList<>();
        boolean[] full = {false};
        SerialDispatcher bounded = new SerialDispatcher() {
            @Override
            public boolean offer(Runnable task) {
                if (full[0]) {
                    return false;
                }
                queue.add(task);
                return true;
            }

            @Override
            public void close() {}
        };
        StreamingMarketDataSource s = source(bounded);
        s.subscribe(Set.of(SAMSUNG), received::add);
        s.start();
        scheduler.runDue();
        FakeTransport.Conn c = transport.last();
        c.push("LOGIN OK");
        c.push("T 005930 71500 1 1791446400");
        full[0] = true;
        clock.set(T0.plusSeconds(5));
        c.push("T 005930 71600 1 1791446405");
        full[0] = false;
        clock.set(T0.plusSeconds(6));
        c.push("T 005930 71700 1 1791446406");
        queue.forEach(Runnable::run);

        assertThat(received).hasSize(3);
        assertThat(received.get(1)).isInstanceOfSatisfying(DataGap.class, g -> {
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.CONSUMER_OVERFLOW);
            assertThat(g.gapStart()).isEqualTo(T0.plusSeconds(5));
        });
        assertThat(received).extracting(MarketEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(s.stats().overflowDropped()).isEqualTo(1);
    }

    @Test
    void 재연결_때_큐가_차서_끊김_표시를_못_넘기면_다음_체결_앞에_끊긴_시각부터_표시한다() {
        List<Runnable> queue = new ArrayList<>();
        boolean[] full = {false};
        SerialDispatcher bounded = new SerialDispatcher() {
            @Override
            public boolean offer(Runnable task) {
                if (full[0]) {
                    return false;
                }
                queue.add(task);
                return true;
            }

            @Override
            public void close() {}
        };
        StreamingMarketDataSource s = source(bounded);
        s.subscribe(Set.of(SAMSUNG), received::add);
        s.start();
        scheduler.runDue();
        transport.last().push("LOGIN OK");
        clock.set(T0.plusSeconds(10));
        transport.last().push("T 005930 71500 1 1791446400");
        transport.last().serverClose();
        scheduler.advance(Duration.ofSeconds(1));
        full[0] = true;
        transport.last().push("LOGIN OK"); // 끊김 공백을 넘기지 못함
        full[0] = false;
        clock.set(T0.plusSeconds(20));
        transport.last().push("T 005930 71600 1 1791446420");
        queue.forEach(Runnable::run);

        assertThat(received).hasSize(3);
        assertThat(received.get(1)).isInstanceOfSatisfying(DataGap.class, g -> {
            assertThat(g.gapStart()).isEqualTo(T0.plusSeconds(10));
            assertThat(g.gapEnd()).isEqualTo(T0.plusSeconds(20));
        });
        assertThat(received.get(2)).isInstanceOf(TradeTick.class);
    }

    @Test
    void 닫으면_재연결하지_않는다() {
        StreamingMarketDataSource s = connected();
        s.close();
        assertThat(s.state()).isEqualTo(ConnectionState.CLOSED);
        scheduler.advance(Duration.ofMinutes(5));
        assertThat(transport.connections).hasSize(1);
        assertThatThrownBy(s::start).isInstanceOf(IllegalStateException.class);
    }
}
