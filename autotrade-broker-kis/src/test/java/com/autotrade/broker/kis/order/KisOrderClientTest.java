package com.autotrade.broker.kis.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 한국투자 주문 클라이언트. 요청·응답 형식은 open-trading-api cd63cae examples_llm/domestic_stock/order_cash·order_rvsecncl을
 * 따른다. 핵심: 어떤 결과에서도 주문을 다시 보내지 않는다(지침 4장 2항).
 */
class KisOrderClientTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:30:00Z"); // 09:30 KST
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final String ORDER_OK = "{\"rt_cd\":\"0\",\"msg_cd\":\"APBK0013\",\"msg1\":\"주문 전송 완료 되었습니다.\","
            + "\"output\":{\"KRX_FWDG_ORD_ORGNO\":\"91252\",\"ODNO\":\"0000117057\",\"ORD_TMD\":\"093001\"}}";

    private final OrderFakes.MutableClock clock = new OrderFakes.MutableClock(NOW);
    private final List<Duration> slept = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private KisOrderClient client(OrderFakes.Http http) {
        return new KisOrderClient(OrderFakes.session(http, clock, slept), OrderFakes.ACCOUNT);
    }

    static OrderRequest limitBuy(long qty, String price) {
        return new OrderRequest(new IdempotencyKey(DAY, "005930", Side.BUY, 1), new AccountId("KIS-1"),
                Symbol.krx("005930"), Side.BUY, OrderIntent.ENTRY, OrderType.LIMIT, new BigDecimal(price), qty);
    }

    static OrderRequest marketSell(long qty) {
        return new OrderRequest(new IdempotencyKey(DAY, "005930", Side.SELL, 1), new AccountId("KIS-1"),
                Symbol.krx("005930"), Side.SELL, OrderIntent.STOP_LOSS, OrderType.MARKET, null, qty);
    }

    @Test
    void 지정가_매수는_TTTC0012U로_계좌를_8_2로_나눠_보내고_접수번호를_돌려준다() throws Exception {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK).respond(200, ORDER_OK);
        KisOrderResult r = client(http).submit(limitBuy(10, "70000.00"));

        assertThat(r).isEqualTo(new KisOrderResult.Accepted(new KisOrderNo("91252", "0000117057"), LocalTime.of(9, 30, 1)));
        OrderFakes.Http.Call call = http.calls.get(1);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri()).hasToString("https://openapi.koreainvestment.com:9443/uapi/domestic-stock/v1/trading/order-cash");
        assertThat(call.headers()).containsEntry("tr_id", "TTTC0012U").containsEntry("custtype", "P")
                .containsEntry("authorization", "Bearer TOKEN-ONE");
        JsonNode body = mapper.readTree(call.body());
        assertThat(body.path("CANO").asText()).isEqualTo("0".repeat(8));
        assertThat(body.path("ACNT_PRDT_CD").asText()).isEqualTo("01");
        assertThat(body.path("PDNO").asText()).isEqualTo("005930");
        assertThat(body.path("ORD_DVSN").asText()).isEqualTo("00");
        assertThat(body.path("ORD_QTY").asText()).isEqualTo("10");
        assertThat(body.path("ORD_UNPR").asText()).isEqualTo("70000");
        assertThat(body.path("EXCG_ID_DVSN_CD").asText()).isEqualTo("KRX");
        assertThat(body.path("SLL_TYPE").asText()).isEmpty();
        // 값은 모두 문자열(공식 주석 "String으로 전달")
        body.properties().forEach(e -> assertThat(e.getValue().isTextual()).as(e.getKey()).isTrue());
    }

    @Test
    void 시장가_매도는_TTTC0011U_주문구분_01_단가_0_일반매도다() throws Exception {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK).respond(200, ORDER_OK);
        client(http).submit(marketSell(3));
        OrderFakes.Http.Call call = http.calls.get(1);
        assertThat(call.headers()).containsEntry("tr_id", "TTTC0011U");
        JsonNode body = mapper.readTree(call.body());
        assertThat(body.path("ORD_DVSN").asText()).isEqualTo("01");
        assertThat(body.path("ORD_UNPR").asText()).isEqualTo("0");
        assertThat(body.path("SLL_TYPE").asText()).isEqualTo("01");
    }

    @Test
    void 응답_시간_초과면_모름이고_다시_보내지_않는다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .fail(new HttpTimeoutException("request timed out"));
        KisOrderResult r = client(http).submit(limitBuy(10, "70000"));
        assertThat(r).isInstanceOf(KisOrderResult.Unknown.class);
        assertThat(((KisOrderResult.Unknown) r).reason()).contains("TTTC0012U", "HttpTimeoutException");
        assertThat(http.count("order-cash")).isEqualTo(1);
    }

    @Test
    void 결과_코드_없는_응답과_주문번호_없는_성공은_모름이다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(502, "<html>bad gateway</html>")
                .respond(200, "{\"rt_cd\":\"0\",\"msg_cd\":\"APBK0013\",\"output\":{}}")
                .respond(500, "{\"rt_cd\":\"0\",\"output\":{\"ODNO\":\"1\"}}");
        KisOrderClient c = client(http);
        assertThat(c.submit(limitBuy(10, "70000"))).isInstanceOf(KisOrderResult.Unknown.class);
        assertThat(c.submit(limitBuy(10, "70000"))).isInstanceOf(KisOrderResult.Unknown.class);
        assertThat(c.submit(limitBuy(10, "70000"))).isInstanceOf(KisOrderResult.Unknown.class);
        assertThat(http.count("order-cash")).isEqualTo(3);
    }

    @Test
    void 결과_코드로_거절하면_접수_안_됨이고_응답_문구는_싣지_않는다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, "{\"rt_cd\":\"1\",\"msg_cd\":\"APBK0919\",\"msg1\":\"주문가능금액을 초과 했습니다\"}");
        KisOrderResult r = client(http).submit(limitBuy(10, "70000"));
        assertThat(r).isEqualTo(new KisOrderResult.NotAccepted(KisOrderResult.Reason.BROKER_REJECTED, "APBK0919"));
        assertThat(r.toString()).doesNotContain("초과");
        assertThat(http.count("order-cash")).isEqualTo(1);
    }

    @Test
    void 그_밖의_오류_코드가_HTTP_200이_아닌_응답에_실리면_모름이다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(502, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00203\"}");
        KisOrderResult r = client(http).submit(limitBuy(10, "70000"));
        assertThat(r).isInstanceOf(KisOrderResult.Unknown.class);
        assertThat(((KisOrderResult.Unknown) r).reason()).contains("EGW00203", "502");
        assertThat(http.count("order-cash")).isEqualTo(1);
    }

    @Test
    void 한도_초과는_HTTP_500이어도_결과_코드로_판단하고_다시_보내지_않는다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(500, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");
        assertThat(client(http).submit(limitBuy(10, "70000")))
                .isEqualTo(new KisOrderResult.NotAccepted(KisOrderResult.Reason.RATE_LIMITED, "EGW00201"));
        assertThat(http.count("order-cash")).isEqualTo(1);
    }

    @Test
    void 토큰_만료면_다시_보내지_않고_토큰을_버리며_새_토큰은_60초_뒤에만_받는다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}")
                .respond(200, OrderFakes.TOKEN_TWO).respond(200, ORDER_OK);
        KisOrderClient c = client(http);
        assertThat(c.submit(limitBuy(10, "70000")))
                .isEqualTo(new KisOrderResult.NotAccepted(KisOrderResult.Reason.TOKEN_EXPIRED, "EGW00123"));
        assertThat(http.count("order-cash")).isEqualTo(1);

        // 첫 발급 뒤 60초 안: 토큰을 받지 못해 보내지도 않는다
        clock.advance(Duration.ofSeconds(59));
        assertThat(c.submit(limitBuy(10, "70000")))
                .isEqualTo(new KisOrderResult.NotAccepted(KisOrderResult.Reason.NOT_SENT, ""));
        assertThat(http.count("tokenP")).isEqualTo(1);
        assertThat(http.count("order-cash")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(c.submit(limitBuy(10, "70000"))).isInstanceOf(KisOrderResult.Accepted.class);
        assertThat(http.count("tokenP")).isEqualTo(2);
        assertThat(http.calls.get(http.calls.size() - 1).headers()).containsEntry("authorization", "Bearer TOKEN-TWO");
    }

    @Test
    void 옛_토큰의_늦은_만료_응답은_그_사이_받은_새_토큰을_버리지_않아_손절_매도가_막히지_않는다() {
        // QA PR #247 Q1(S2): 조회가 새 토큰 T2를 받은 뒤, T1으로 보낸 주문의 만료 응답이 늦게 온다
        OrderFakes.Http http = new OrderFakes.Http();
        KisSession session = OrderFakes.session(http, clock, slept);
        KisOrderClient c = new KisOrderClient(session, OrderFakes.ACCOUNT);
        http.respond(200, OrderFakes.TOKEN_OK)
                .then(() -> {
                    clock.advance(Duration.ofSeconds(61));
                    session.invalidateToken("TOKEN-ONE");
                    try {
                        assertThat(session.token()).isEqualTo("TOKEN-TWO");
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .respond(200, OrderFakes.TOKEN_TWO)
                .respond(200, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\"}")
                .respond(200, ORDER_OK);
        assertThat(c.submit(limitBuy(10, "70000")))
                .isEqualTo(new KisOrderResult.NotAccepted(KisOrderResult.Reason.TOKEN_EXPIRED, "EGW00123"));

        clock.advance(Duration.ofSeconds(5));
        assertThat(c.submit(marketSell(3))).isInstanceOf(KisOrderResult.Accepted.class);
        assertThat(http.count("tokenP")).isEqualTo(2);
        assertThat(http.calls.get(http.calls.size() - 1).headers()).containsEntry("authorization", "Bearer TOKEN-TWO");
    }

    @Test
    void 모름_결과의_보내기_시작_시각은_토큰_발급_뒤_실제_POST_직전이다() {
        // QA PR #247 발견 1 S3: 호출자가 submit 직전(NOW)에 시각을 적어도 토큰 발급이 40초 걸리면 실제 POST는 NOW+40초다
        OrderFakes.Http http = new OrderFakes.Http()
                .then(() -> clock.advance(Duration.ofSeconds(40))).respond(200, OrderFakes.TOKEN_OK)
                .then(() -> clock.advance(Duration.ofSeconds(10))).fail(new HttpTimeoutException("request timed out"));
        OrderRequest req = limitBuy(10, "70000");
        KisOrderResult.Unknown u = (KisOrderResult.Unknown) client(http).submit(req);
        assertThat(u.sendStartedAt()).isEqualTo(NOW.plusSeconds(40));
        assertThat(u.sendEndedAt()).isEqualTo(NOW.plusSeconds(50));

        KisOrderReconciler.Fingerprint fp = KisOrderReconciler.Fingerprint.of(req, u);
        assertThat(fp.tradingDate()).isEqualTo(DAY);
        // 호출자 기준으로는 "NOW + 10초 + 반영 대기 30초"가 지났지만 실제 POST 기준으로는 반영 대기 전이다
        Instant callerSettled = NOW.plusSeconds(10).plus(KisOrderReconciler.SETTLE);
        Instant queried = NOW.plusSeconds(50);
        assertThat(queried).isAfter(callerSettled);
        assertThat(KisOrderReconciler.find(KisOrderReconcilerTest.day(queried, true), fp, java.util.Set.of()))
                .isInstanceOf(KisOrderReconciler.Indeterminate.class);
        Instant settled = u.sendEndedAt().plus(KisOrderReconciler.SETTLE);
        assertThat(u.sendStarted().monoUntil(u.sendEnded())).isEqualTo(Duration.ofSeconds(10));
        assertThat(KisOrderReconciler.find(KisOrderReconcilerTest.day(settled, true), fp, java.util.Set.of()))
                .isSameAs(KisOrderReconciler.NotFound.INSTANCE);
    }

    @Test
    void 토큰을_못_받으면_주문을_보내지_않는다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(500, "{}");
        assertThat(client(http).submit(limitBuy(10, "70000")))
                .isEqualTo(new KisOrderResult.NotAccepted(KisOrderResult.Reason.NOT_SENT, ""));
        assertThat(http.count("order-cash")).isZero();
    }

    @Test
    void KRX가_아니거나_원_단위가_아닌_주문은_보내기_전에_막는다() {
        OrderFakes.Http http = new OrderFakes.Http();
        KisOrderClient c = client(http);
        OrderRequest us = new OrderRequest(new IdempotencyKey(DAY, "AAPL", Side.BUY, 1), new AccountId("KIS-1"),
                Symbol.us("AAPL"), Side.BUY, OrderIntent.ENTRY, OrderType.LIMIT, new BigDecimal("190.5"), 1);
        assertThatThrownBy(() -> c.submit(us)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.submit(limitBuy(1, "70000.5"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 취소는_TTTC0013U_취소구분_02_잔량전부로_원주문번호를_보낸다() throws Exception {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, "{\"rt_cd\":\"0\",\"output\":{\"krx_fwdg_ord_orgno\":\"91252\",\"odno\":\"0000117060\","
                        + "\"ord_tmd\":\"093105\"}}");
        KisOrderResult r = client(http).cancel(new KisOrderNo("91252", "0000117057"));
        assertThat(r).isEqualTo(new KisOrderResult.Accepted(new KisOrderNo("91252", "0000117060"), LocalTime.of(9, 31, 5)));
        OrderFakes.Http.Call call = http.calls.get(1);
        assertThat(call.uri().getPath()).isEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl");
        assertThat(call.headers()).containsEntry("tr_id", "TTTC0013U");
        JsonNode body = mapper.readTree(call.body());
        assertThat(body.path("RVSE_CNCL_DVSN_CD").asText()).isEqualTo("02");
        assertThat(body.path("QTY_ALL_ORD_YN").asText()).isEqualTo("Y");
        assertThat(body.path("ORGN_ODNO").asText()).isEqualTo("0000117057");
        assertThat(body.path("KRX_FWDG_ORD_ORGNO").asText()).isEqualTo("91252");
        assertThat(body.path("ORD_QTY").asText()).isEqualTo("0");
    }

    @Test
    void 호출_사이_0_1초_간격을_지킨다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK).respond(200, ORDER_OK)
                .respond(200, ORDER_OK);
        KisOrderClient c = client(http);
        c.submit(limitBuy(10, "70000"));
        c.submit(limitBuy(10, "70000"));
        assertThat(slept).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void 결과와_계좌번호_문자열에_계좌번호와_키가_없다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .fail(new java.net.ConnectException("openapi.koreainvestment.com/1.2.3.4:9443"));
        KisOrderResult r = client(http).submit(limitBuy(10, "70000"));
        assertThat(r.toString()).doesNotContain("0".repeat(8), "APP-KEY", "APP-SECRET", "1.2.3.4");
        assertThat(OrderFakes.ACCOUNT.toString()).doesNotContain("0".repeat(8));
        assertThatThrownBy(() -> KisAccountNumber.parse("1234-5678901"))
                .hasMessageNotContaining("1234").hasMessageNotContaining("5678901");
    }

    @Test
    void 계좌번호는_대시가_있든_없든_8_2로_읽는다() {
        assertThat(KisAccountNumber.parse("0".repeat(9) + "1")).isEqualTo(OrderFakes.ACCOUNT);
        assertThat(OrderFakes.ACCOUNT.cano()).hasSize(8);
        assertThat(OrderFakes.ACCOUNT.productCode()).isEqualTo("01");
    }
}
