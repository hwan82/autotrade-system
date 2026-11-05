package com.autotrade.marketdata.enginefeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.EngineHello;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.GatewayHello;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Heartbeat;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.HeartbeatLine;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Interest;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.ProtocolException;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Rejection;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.SourceState;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Status;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.StatusLine;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Unserved;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 엔진 전달 줄 형식(ADR-016 2.3). */
class EngineFeedProtocolTest {

    private static final Instant T = Instant.parse("2026-10-27T00:00:01Z");
    private static final Symbol A = Symbol.krx("005930");
    private static final Symbol B = Symbol.krx("000660");

    private static SortedSet<Symbol> set(Symbol... s) {
        return new TreeSet<>(java.util.List.of(s));
    }

    private static SortedSet<Symbol> many(int n, int from) {
        TreeSet<Symbol> s = new TreeSet<>();
        IntStream.range(from, from + n).forEach(i -> s.add(Symbol.krx(String.format("%06d", i))));
        return s;
    }

    @Test
    void 엔진_인사와_관심_줄은_되읽힌다() {
        String hello = EngineFeedProtocol.engineHelloLine("boot-7");
        assertThat(hello).isEqualTo("{\"hello\":\"autotrade-engine-feed\",\"formatVersion\":1,\"client\":\"engine\",\"boot\":\"boot-7\"}");
        assertThat(EngineFeedProtocol.parseEngineLine(hello)).isEqualTo(new EngineHello("boot-7"));

        Interest i = new Interest(12, set(A), set(B));
        String line = EngineFeedProtocol.interestLine(i);
        assertThat(line).isEqualTo("{\"interest\":{\"rev\":12,\"protect\":[\"KRX:005930\"],\"entry\":[\"KRX:000660\"]}}");
        assertThat(EngineFeedProtocol.parseEngineLine(line)).isEqualTo(i);
    }

    @Test
    void 관심_상한과_형식_오류는_이유를_붙여_거부한다() {
        assertThat(reason(() -> new Interest(1, many(65, 1), set()))).isEqualTo(Rejection.TOO_MANY_PROTECT);
        assertThat(new Interest(1, many(64, 1), many(64, 1000)).all()).hasSize(128);
        assertThat(reason(() -> new Interest(1, many(64, 1), many(65, 1000)))).isEqualTo(Rejection.TOO_MANY_SYMBOLS);
        // 겹치는 종목은 한 번만 센다
        assertThat(new Interest(1, many(64, 1), many(128, 1)).all()).hasSize(128);
        assertThat(reason(() -> EngineFeedProtocol.parseEngineLine("{\"interest\":{\"rev\":0,\"protect\":[],\"entry\":[]}}")))
                .isEqualTo(Rejection.BAD_FORMAT);
        assertThat(reason(() -> EngineFeedProtocol.parseEngineLine("{\"interest\":{\"rev\":1,\"protect\":[\"005930\"],\"entry\":[]}}")))
                .isEqualTo(Rejection.BAD_SYMBOL);
        assertThat(reason(() -> EngineFeedProtocol.parseEngineLine("{\"interest\":{\"rev\":1,\"protect\":[],\"entry\":[],\"qty\":1}}")))
                .isEqualTo(Rejection.BAD_FORMAT);
        assertThat(reason(() -> EngineFeedProtocol.parseEngineLine("{\"order\":1}"))).isEqualTo(Rejection.BAD_FORMAT);
        assertThat(reason(() -> EngineFeedProtocol.parseEngineLine("not json"))).isEqualTo(Rejection.BAD_FORMAT);
        assertThat(reason(() -> EngineFeedProtocol.parseEngineLine("{\"x\":\"" + "a".repeat(16 * 1024) + "\"}")))
                .isEqualTo(Rejection.LINE_TOO_LONG);
        assertThat(reason(() -> EngineFeedProtocol.parseEngineLine(
                "{\"hello\":\"autotrade-engine-feed\",\"formatVersion\":2,\"client\":\"engine\",\"boot\":\"b\"}")))
                .isEqualTo(Rejection.BAD_FORMAT);
        assertThat(reason(() -> EngineFeedProtocol.engineHelloLine("bad boot!"))).isEqualTo(Rejection.BAD_FORMAT);
        // 원문은 메시지에 넣지 않는다
        assertThatThrownBy(() -> EngineFeedProtocol.parseEngineLine("{\"secret-like\":1}")).hasMessage("BAD_FORMAT");
    }

    @Test
    void 상태_줄과_생존_줄은_sources를_같은_객체_모양으로_쓴다() {
        SortedMap<String, SourceState> sources = new TreeMap<>(Map.of(
                "KIWOOM-WS", new SourceState(ConnectionState.CONNECTED, T),
                "KIS-WS", new SourceState(ConnectionState.CONNECTING, null)));
        Status st = new Status(12, T, sources, new TreeMap<>(Map.of(A, "KIWOOM-WS")),
                new TreeMap<>(Map.of(B, Unserved.CAPACITY)), null);
        String status = EngineFeedProtocol.statusLine(st);
        assertThat(status).isEqualTo("{\"status\":{\"rev\":12,\"at\":\"2026-10-27T00:00:01Z\",\"sources\":{"
                + "\"KIS-WS\":{\"state\":\"CONNECTING\",\"lastRx\":null},"
                + "\"KIWOOM-WS\":{\"state\":\"CONNECTED\",\"lastRx\":\"2026-10-27T00:00:01Z\"}},"
                + "\"served\":{\"KRX:005930\":\"KIWOOM-WS\"},\"unserved\":{\"KRX:000660\":\"CAPACITY\"},\"rejected\":null}}");
        Heartbeat h = new Heartbeat(T, sources, 3);
        String hb = EngineFeedProtocol.heartbeatLine(h);
        assertThat(hb).isEqualTo("{\"hb\":{\"at\":\"2026-10-27T00:00:01Z\",\"sources\":{"
                + "\"KIS-WS\":{\"state\":\"CONNECTING\",\"lastRx\":null},"
                + "\"KIWOOM-WS\":{\"state\":\"CONNECTED\",\"lastRx\":\"2026-10-27T00:00:01Z\"}},\"dropped\":3}}");

        // 받는 쪽은 한 해석기로 두 줄의 sources를 읽는다(architect PR #245 N6)
        assertThat(EngineFeedProtocol.parseGatewayControl(tree(status))).isEqualTo(new StatusLine(st));
        assertThat(EngineFeedProtocol.parseGatewayControl(tree(hb))).isEqualTo(new HeartbeatLine(h));

        Status rejected = new Status(0, T, new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), Rejection.TOO_MANY_PROTECT);
        assertThat(EngineFeedProtocol.parseGatewayControl(tree(EngineFeedProtocol.statusLine(rejected))))
                .isEqualTo(new StatusLine(rejected));
    }

    @Test
    void 게이트웨이_인사는_두_버전을_모두_확인한다() {
        JsonNode hello = tree(EngineFeedProtocol.gatewayHelloLine());
        GatewayHello h = (GatewayHello) EngineFeedProtocol.parseGatewayControl(hello);
        assertThat(h).isEqualTo(new GatewayHello(1, RecordingCodec.FORMAT_VERSION));
        assertThat(EngineFeedProtocol.checkGatewayHello(h)).isEmpty();
        assertThat(EngineFeedProtocol.checkGatewayHello(new GatewayHello(2, RecordingCodec.FORMAT_VERSION)))
                .contains("엔진 전달 형식 버전");
        assertThat(EngineFeedProtocol.checkGatewayHello(new GatewayHello(1, RecordingCodec.FORMAT_VERSION + 1)))
                .contains("녹화 형식 버전");
        // 실험실 소켓 인사는 엔진 소켓 인사가 아니다
        assertThat(reason(() -> EngineFeedProtocol.parseGatewayControl(
                tree("{\"feed\":\"autotrade-market-feed\",\"formatVersion\":1}")))).isEqualTo(Rejection.BAD_FORMAT);
    }

    @Test
    void 이벤트_줄과_제어_줄은_t로_가른다() {
        assertThat(EngineFeedProtocol.isEventLine(tree("{\"seq\":1,\"t\":\"TRADE\"}"))).isTrue();
        assertThat(EngineFeedProtocol.isEventLine(tree(EngineFeedProtocol.gatewayHelloLine()))).isFalse();
        assertThat(reason(() -> EngineFeedProtocol.parseGatewayControl(tree("{\"unknown\":{}}")))).isEqualTo(Rejection.BAD_FORMAT);
        // 한 종목이 served와 unserved에 함께 있으면 형식 오류
        assertThat(reason(() -> EngineFeedProtocol.parseGatewayControl(tree("{\"status\":{\"rev\":1,\"at\":\"2026-10-27T00:00:01Z\","
                + "\"sources\":{},\"served\":{\"KRX:005930\":\"K\"},\"unserved\":{\"KRX:005930\":\"PENDING\"},\"rejected\":null}}"))))
                .isEqualTo(Rejection.BAD_FORMAT);
    }

    private static JsonNode tree(String line) {
        return EngineFeedProtocol.readTree(line);
    }

    private static Rejection reason(Runnable r) {
        try {
            r.run();
        } catch (ProtocolException e) {
            return e.reason();
        }
        throw new AssertionError("거부되지 않았습니다");
    }
}
