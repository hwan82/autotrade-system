package com.autotrade.broker.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.live.CredentialsRejectedException;
import com.autotrade.marketdata.live.Decoded;
import com.autotrade.marketdata.live.RawQuote;
import com.autotrade.marketdata.live.TokenCache;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 메시지 예시는 키움 공식 명세(kiwoom_api_spec.json 0B·0D·au10001 response_example)에서 옮겼다. */
class KiwoomStreamProtocolTest {

    private static final Instant RX = Instant.parse("2026-10-08T07:52:09Z"); // 16:52:09 KST
    private static final Clock CLOCK = Clock.fixed(RX, ZoneOffset.UTC);

    static final String TOKEN_OK = """
            {"expires_dt":"20261009083713","token_type":"bearer","token":"TEST-TOKEN-VALUE","return_code":0,"return_msg":"정상적으로 처리되었습니다"}
            """;

    static final String REAL_0B = """
            {"trnm":"REAL","data":[{"type":"0B","name":"주식체결","item":"005930","values":{
            "20":"165208","10":"-20800","11":"-50","12":"-0.24","27":"-20800","28":"-20700","15":"+82",
            "13":"30379732","14":"632640","16":"20850","17":"+21150","18":"-20450","25":"5","290":"2"}}]}
            """;

    static final String REAL_0D = """
            {"trnm":"REAL","data":[{"type":"0D","name":"주식호가잔량","item":"005930","values":{
            "21":"165207",
            "41":"-20800","61":"82","51":"-20700","71":"23847",
            "42":"+20900","62":"393","52":"-20650","72":"834748",
            "43":"+21000","63":"674739","53":"-20600","73":"2145799",
            "44":"+21050","64":"2055055","54":"-20550","74":"2134689",
            "45":"+21100","65":"1998461","55":"-20500","75":"1982298",
            "46":"+21150","66":"1932347","56":"-20450","76":"1853788",
            "47":"+21200","67":"1723333","57":"-20400","77":"1687992",
            "48":"+21250","68":"1621835","58":"-20350","78":"1467869",
            "49":"+21300","69":"1373291","59":"-20300","79":"1259995",
            "50":"+21350","70":"1242991","60":"-20250","80":"1062405"}}]}
            """;

    private final FakeHttp http = new FakeHttp().respond(200, TOKEN_OK);
    private final KiwoomStreamProtocol protocol = new KiwoomStreamProtocol(new TokenCache(
            new KiwoomTokenClient(http, new BrokerCredentials("APP-KEY", "SECRET-KEY")), CLOCK, Duration.ofMinutes(10)));

    @Test
    void 접속하면_토큰으로_LOGIN을_보낸다() throws Exception {
        assertThat(protocol.openingMessages()).containsExactly("{\"trnm\":\"LOGIN\",\"token\":\"TEST-TOKEN-VALUE\"}");
        assertThat(http.calls.get(0).uri()).hasToString("https://api.kiwoom.com/oauth2/token");
        assertThat(http.calls.get(0).body()).contains("\"appkey\":\"APP-KEY\"").contains("\"secretkey\":\"SECRET-KEY\"");
        // 토큰은 만료 전까지 다시 받지 않는다
        protocol.openingMessages();
        assertThat(http.calls).hasSize(1);
        assertThat(protocol.requiresLoginAck()).isTrue();
    }

    @Test
    void LOGIN_응답으로_인증_결과를_판단한다() {
        assertThat(protocol.decode("{\"trnm\":\"LOGIN\",\"return_code\":0,\"return_msg\":\"\"}", RX).control())
                .isEqualTo(Decoded.Control.LOGIN_OK);
        Decoded fail = protocol.decode("{\"trnm\":\"LOGIN\",\"return_code\":8005,\"return_msg\":\"Token이 유효하지 않습니다\"}", RX);
        assertThat(fail.control()).isEqualTo(Decoded.Control.LOGIN_FAILED);
        assertThat(fail.detail()).contains("8005");
    }

    @Test
    void PING은_받은_그대로_돌려준다() {
        String ping = "{\"trnm\":\"PING\"}";
        assertThat(protocol.decode(ping, RX).replies()).containsExactly(ping);
    }

    @Test
    void 체결과_호가를_함께_등록하고_100종목씩_나눈다() {
        TreeSet<Symbol> symbols = new TreeSet<>();
        IntStream.range(0, 150).forEach(i -> symbols.add(Symbol.krx(String.format("%06d", i))));
        var msgs = protocol.subscribeMessages(symbols);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0)).startsWith("{\"trnm\":\"REG\",\"grp_no\":\"1\",\"refresh\":\"1\",\"data\":[{\"item\":[\"000000\"")
                .endsWith("\"type\":[\"0B\",\"0D\"]}]}");
        assertThat(protocol.unsubscribeMessages(new TreeSet<>(java.util.Set.of(Symbol.krx("005930")))))
                .containsExactly("{\"trnm\":\"REMOVE\",\"grp_no\":\"1\",\"refresh\":\"1\",\"data\":[{\"item\":[\"005930\"],\"type\":[\"0B\",\"0D\"]}]}");
        assertThat(protocol.subscribeMessages(new TreeSet<>())).isEmpty();
    }

    @Test
    void 등록_거절을_알린다() {
        Decoded d = protocol.decode("{\"trnm\":\"REG\",\"return_code\":1702,\"return_msg\":\"그룹 요청 개수 초과\"}", RX);
        assertThat(d.control()).isEqualTo(Decoded.Control.SUBSCRIBE_REJECTED);
        assertThat(d.detail()).contains("1702");
        assertThat(protocol.decode("{\"trnm\":\"REG\",\"return_code\":0,\"return_msg\":\"\"}", RX).control())
                .isEqualTo(Decoded.Control.IGNORED);
    }

    @Test
    void 주식체결_0B를_체결로_바꾼다() {
        Decoded d = protocol.decode(REAL_0B, RX);
        assertThat(d.quotes()).singleElement().isInstanceOfSatisfying(RawQuote.Trade.class, t -> {
            assertThat(t.symbol()).isEqualTo(Symbol.krx("005930"));
            assertThat(t.price()).isEqualByComparingTo("20800");
            assertThat(t.quantity()).isEqualTo(82);
            assertThat(t.exchangeTime()).isEqualTo(Instant.parse("2026-10-08T07:52:08Z"));
        });
    }

    @Test
    void 주식호가잔량_0D를_10단계_호가로_바꾼다() {
        Decoded d = protocol.decode(REAL_0D, RX);
        RawQuote.Book b = (RawQuote.Book) d.quotes().get(0);
        assertThat(b.asks()).hasSize(10).first().isEqualTo(new PriceLevel(new BigDecimal("20800"), 82));
        assertThat(b.bids()).hasSize(10).first().isEqualTo(new PriceLevel(new BigDecimal("20700"), 23847));
        assertThat(b.asks().get(9).price()).isEqualByComparingTo("21350");
        assertThat(b.bids().get(9).price()).isEqualByComparingTo("20250");
        // 도메인 이벤트로 바꿔도 정렬 검사를 통과한다
        assertThat(b.toEvent(1, RX)).isNotNull();
    }

    @Test
    void 형식이_틀린_항목만_버린다() {
        String mixed = """
                {"trnm":"REAL","data":[
                 {"type":"0B","item":"BAD-CODE","values":{"20":"090000","10":"+100","15":"+1"}},
                 {"type":"0B","item":"000660","values":{"20":"090001","10":"+120000","15":"-3"}},
                 {"type":"0B","item":"000660","values":{"20":"","10":"+120000","15":"-3"}}]}
                """;
        assertThat(protocol.decode(mixed, RX).quotes()).hasSize(1);
        assertThat(protocol.decode("not json", RX).control()).isEqualTo(Decoded.Control.IGNORED);
    }

    @Test
    void 토큰_발급_실패_메시지에_비밀값이_없다() {
        FakeHttp bad = new FakeHttp().respond(200, "{\"return_code\":3,\"return_msg\":\"appkey SECRET-KEY 오류\"}");
        KiwoomTokenClient client = new KiwoomTokenClient(bad, new BrokerCredentials("APP-KEY", "SECRET-KEY"));
        assertThatThrownBy(client::fetch).isInstanceOf(CredentialsRejectedException.class)
                .hasMessageNotContaining("SECRET-KEY").hasMessageNotContaining("APP-KEY");
        FakeHttp http500 = new FakeHttp().respond(500, "SECRET-KEY");
        assertThatThrownBy(new KiwoomTokenClient(http500, new BrokerCredentials("APP-KEY", "SECRET-KEY"))::fetch)
                .isInstanceOf(IOException.class).isNotInstanceOf(CredentialsRejectedException.class)
                .hasMessageNotContaining("SECRET-KEY");
        FakeHttp http401 = new FakeHttp().respond(401, "SECRET-KEY");
        assertThatThrownBy(new KiwoomTokenClient(http401, new BrokerCredentials("APP-KEY", "SECRET-KEY"))::fetch)
                .isInstanceOf(CredentialsRejectedException.class).hasMessageNotContaining("SECRET-KEY");
    }

    @Test
    void 토큰_만료_시각은_한국_시간으로_읽는다() throws Exception {
        TokenCache.Issued issued = new KiwoomTokenClient(new FakeHttp().respond(200, TOKEN_OK),
                new BrokerCredentials("A", "B")).fetch();
        assertThat(issued.expiresAt()).isEqualTo(Instant.parse("2026-10-08T23:37:13Z"));
        assertThat(issued.toString()).doesNotContain("TEST-TOKEN-VALUE");
    }
    @Test
    void 명세_경로를_먼저_쓰고_실패하면_예전_경로와_번갈아_시도한다() {
        assertThat(protocol.endpoint()).hasToString("wss://api.kiwoom.com:10000/api/websocket");
        assertThat(protocol.endpoint()).hasToString("wss://api.kiwoom.com:10000/api/dostk/websocket");
        assertThat(protocol.endpoint()).isEqualTo(KiwoomStreamProtocol.ENDPOINT);
        assertThat(protocol.pinnedEndpoint()).isEmpty();
    }

    @Test
    void LOGIN이_성공한_경로로_고정한다() {
        protocol.endpoint();
        URI legacy = protocol.endpoint();
        protocol.decode("{\"trnm\":\"LOGIN\",\"return_code\":\"8005\",\"return_msg\":\"\"}", RX);
        assertThat(protocol.pinnedEndpoint()).isEmpty();
        protocol.decode("{\"trnm\":\"LOGIN\",\"return_code\":0}", RX);
        assertThat(protocol.pinnedEndpoint()).contains(legacy);
        assertThat(protocol.endpoint()).isEqualTo(legacy);
        assertThat(protocol.endpoint()).isEqualTo(legacy);
    }

    @Test
    void 결과코드가_문자열이거나_메시지에_박혀_있어도_읽는다() {
        assertThat(protocol.decode("{\"trnm\":\"REG\",\"return_code\":\"0\",\"return_msg\":\"\"}", RX).control())
                .isEqualTo(Decoded.Control.IGNORED);
        Decoded rejected = protocol.decode("{\"trnm\":\"REG\",\"return_code\":\"1\",\"return_msg\":\"CODE=1702 초과\"}", RX);
        assertThat(rejected.control()).isEqualTo(Decoded.Control.SUBSCRIBE_REJECTED);
        assertThat(rejected.detail()).contains("1702").contains("호출 한도 초과");
        Decoded login = protocol.decode("{\"trnm\":\"LOGIN\",\"return_code\":3,\"return_msg\":\"[8050:IP 미등록]\"}", RX);
        assertThat(login.control()).isEqualTo(Decoded.Control.LOGIN_FAILED);
        assertThat(login.detail()).contains("8050").contains("IP");
    }

    @Test
    void 거래소구분이_KRX가_아닌_체결은_버린다() {
        String nxt = REAL_0B.replace("\"290\":\"2\"", "\"290\":\"2\",\"9081\":\"NXT\"");
        assertThat(protocol.decode(nxt, RX).quotes()).isEmpty();
        String krx = REAL_0B.replace("\"290\":\"2\"", "\"290\":\"2\",\"9081\":\"KRX\"");
        assertThat(protocol.decode(krx, RX).quotes()).hasSize(1);
    }

    @Test
    void 토큰_발급_오류코드를_재시도와_거절로_나눈다() {
        BrokerCredentials creds = new BrokerCredentials("APP-KEY", "SECRET-KEY");
        FakeHttp ip = new FakeHttp().respond(200, "{\"return_code\":8050,\"return_msg\":\"IP가 등록되지 않았습니다\"}");
        assertThatThrownBy(new KiwoomTokenClient(ip, creds)::fetch).isInstanceOf(CredentialsRejectedException.class)
                .hasMessageContaining("8050").hasMessageContaining("API 사용신청");
        FakeHttp mock = new FakeHttp().respond(400, "{\"return_code\":8030,\"return_msg\":\"투자구분\"}");
        assertThatThrownBy(new KiwoomTokenClient(mock, creds)::fetch).isInstanceOf(CredentialsRejectedException.class)
                .hasMessageContaining("8030");
        FakeHttp rate = new FakeHttp().respond(429, "{\"return_code\":1700,\"return_msg\":\"초과\"}");
        assertThatThrownBy(new KiwoomTokenClient(rate, creds)::fetch).isInstanceOf(IOException.class)
                .isNotInstanceOf(CredentialsRejectedException.class).hasMessageContaining("1700");
        FakeHttp server = new FakeHttp().respond(200, "{\"return_code\":1999,\"return_msg\":\"SECRET-KEY\"}");
        assertThatThrownBy(new KiwoomTokenClient(server, creds)::fetch).isInstanceOf(IOException.class)
                .isNotInstanceOf(CredentialsRejectedException.class).hasMessageNotContaining("SECRET-KEY");
    }
}
