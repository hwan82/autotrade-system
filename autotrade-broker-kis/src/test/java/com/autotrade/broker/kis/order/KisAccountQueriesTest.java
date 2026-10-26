package com.autotrade.broker.kis.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.order.Side;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 일별 주문체결·잔고 조회. 필드 이름은 open-trading-api cd63cae chk_inquire_daily_ccld.py·chk_inquire_balance.py. */
class KisAccountQueriesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z"); // 10:00 KST
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private final OrderFakes.MutableClock clock = new OrderFakes.MutableClock(NOW);
    private final List<Duration> slept = new ArrayList<>();

    private KisAccountQueries queries(OrderFakes.Http http) {
        return new KisAccountQueries(OrderFakes.session(http, clock, slept), OrderFakes.ACCOUNT);
    }

    static String row(String odno, String orgnOdno, String side, String pdno, String qty, String price, String tmd,
            String filled, String cancelled, String remaining, String rejected) {
        return "{\"ord_dt\":\"20261102\",\"ord_gno_brno\":\"91252\",\"odno\":\"" + odno + "\",\"orgn_odno\":\"" + orgnOdno
                + "\",\"sll_buy_dvsn_cd\":\"" + side + "\",\"pdno\":\"" + pdno + "\",\"ord_qty\":\"" + qty
                + "\",\"ord_unpr\":\"" + price + "\",\"ord_tmd\":\"" + tmd + "\",\"tot_ccld_qty\":\"" + filled
                + "\",\"avg_prvs\":\"70000\",\"cnc_cfrm_qty\":\"" + cancelled + "\",\"rmn_qty\":\"" + remaining
                + "\",\"rjct_qty\":\"" + rejected + "\",\"ord_dvsn_cd\":\"00\"}";
    }

    static String page(String... rows) {
        return "{\"rt_cd\":\"0\",\"msg_cd\":\"KIOK0000\",\"ctx_area_fk100\":\"FK\",\"ctx_area_nk100\":\"NK-NEXT\","
                + "\"output1\":[" + String.join(",", rows) + "],\"output2\":{}}";
    }

    @Test
    void 일별_체결은_TTTC0081R로_그날_하루를_조회하고_행을_읽는다() throws Exception {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, page(row("0000117057", "", "02", "005930", "10", "70000", "093001", "4", "0", "6", "0")),
                        Map.of("tr_cont", "D"));
        KisDailyOrders day = queries(http).dailyOrders(DAY);

        OrderFakes.Http.Call call = http.calls.get(1);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.uri().getPath()).isEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld");
        assertThat(call.uri().getQuery()).contains("INQR_STRT_DT=20261102", "INQR_END_DT=20261102", "CCLD_DVSN=00",
                "SLL_BUY_DVSN_CD=00", "EXCG_ID_DVSN_CD=KRX", "ACNT_PRDT_CD=01");
        assertThat(call.headers()).containsEntry("tr_id", "TTTC0081R").containsEntry("tr_cont", "");
        assertThat(day.complete()).isTrue();
        assertThat(day.queriedFrom()).isEqualTo(NOW);
        KisDailyOrder r = day.rows().get(0);
        assertThat(r.side()).isEqualTo(Side.BUY);
        assertThat(r.orderTime()).isEqualTo(LocalTime.of(9, 30, 1));
        assertThat(r.state()).isEqualTo(OrderState.PARTIALLY_FILLED);
        assertThat(r.averagePrice()).isEqualByComparingTo("70000");
    }

    @Test
    void 다음_쪽이_있으면_tr_cont_N과_연속키로_이어_받는다() throws Exception {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, page(row("1", "", "02", "005930", "1", "70000", "090000", "1", "0", "0", "0")),
                        Map.of("tr_cont", "M"))
                .respond(200, page(row("2", "", "01", "000660", "1", "200000", "090100", "0", "0", "1", "0")),
                        Map.of("tr_cont", "D"));
        KisDailyOrders day = queries(http).dailyOrders(DAY);
        assertThat(day.rows()).hasSize(2);
        assertThat(day.complete()).isTrue();
        OrderFakes.Http.Call second = http.calls.get(2);
        assertThat(second.headers()).containsEntry("tr_cont", "N");
        assertThat(second.uri().getQuery()).contains("CTX_AREA_NK100=NK-NEXT", "CTX_AREA_FK100=FK");
    }

    @Test
    void 쪽_상한을_넘거나_읽지_못한_행이_있으면_불완전이다() throws Exception {
        OrderFakes.Http many = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK);
        for (int i = 0; i < KisAccountQueries.MAX_PAGES; i++) {
            many.respond(200, page(), Map.of("tr_cont", "F"));
        }
        assertThat(queries(many).dailyOrders(DAY).complete()).isFalse();
        assertThat(many.count("inquire-daily-ccld")).isEqualTo(KisAccountQueries.MAX_PAGES);

        OrderFakes.Http bad = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, page(row("1", "", "09", "005930", "1", "70000", "090000", "0", "0", "1", "0"),
                        row("2", "", "02", "005930", "1", "70000", "0900", "0", "0", "1", "0"),
                        row("3", "", "02", "005930", "1", "70000", "090000", "2", "0", "0", "0")));
        KisDailyOrders day = queries(bad).dailyOrders(DAY);
        assertThat(day.complete()).isFalse();
        assertThat(day.unreadableRows()).isEqualTo(3); // 매도매수구분 09, 시각 4자리, 체결 > 주문
        assertThat(day.rows()).isEmpty();
    }

    @Test
    void 응답에_행_칸이_없으면_0행_완전으로_읽지_않는다() throws Exception {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, "{\"rt_cd\":\"0\",\"output\":[]}")
                .respond(200, "{\"rt_cd\":\"0\",\"output2\":[{\"dnca_tot_amt\":\"1\",\"prvs_rcdl_excc_amt\":\"1\","
                        + "\"tot_evlu_amt\":\"1\"}]}");
        KisAccountQueries q = queries(http);
        KisDailyOrders day = q.dailyOrders(DAY);
        assertThat(day.complete()).isFalse();
        assertThat(day.unreadableRows()).isEqualTo(1);
        KisBalance b = q.balance();
        assertThat(b.complete()).isFalse();
        assertThat(b.holdings()).isEmpty();
    }

    @Test
    void 토큰_만료면_한_번만_다시_부르고_새_토큰은_60초_하한을_지킨다() throws Exception {
        String expired = "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\"}";
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK).respond(200, page())
                .respond(200, expired).respond(200, OrderFakes.TOKEN_TWO).respond(200, page());
        KisAccountQueries q = queries(http);
        q.dailyOrders(DAY);
        clock.advance(Duration.ofMinutes(5));
        assertThat(q.dailyOrders(DAY).complete()).isTrue();
        assertThat(http.count("tokenP")).isEqualTo(2);
        assertThat(http.calls.get(4).headers()).containsEntry("authorization", "Bearer TOKEN-TWO");

        // 첫 발급 직후 만료 응답: 60초 안이라 새로 받지 않고 실패한다(발급 요청이 쌓이지 않음)
        OrderFakes.Http fast = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK).respond(200, expired);
        assertThatThrownBy(() -> queries(fast).dailyOrders(DAY)).isInstanceOf(IOException.class)
                .hasMessageContaining("60초");
        assertThat(fast.count("tokenP")).isEqualTo(1);
    }

    @Test
    void 한도_초과는_다시_부르지_않고_표시해_알린다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(500, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00215\",\"msg1\":\"원장 초당 거래건수 초과\"}");
        assertThatThrownBy(() -> queries(http).dailyOrders(DAY))
                .isInstanceOfSatisfying(KisQueryException.class, e -> assertThat(e.rateLimited()).isTrue())
                .hasMessageContaining("EGW00215").hasMessageNotContaining("원장");
        assertThat(http.count("inquire-daily-ccld")).isEqualTo(1);
    }

    @Test
    void 결과_코드_없는_응답은_실패이고_메시지에_계좌번호가_없다() {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK).respond(503, "");
        assertThatThrownBy(() -> queries(http).balance())
                .isInstanceOfSatisfying(KisQueryException.class, e -> assertThat(e.rateLimited()).isFalse())
                .hasMessageContaining("TTTC8434R").hasMessageNotContaining("0".repeat(8));
    }

    @Test
    void 잔고는_TTTC8434R로_종목별_보유와_예수금을_읽고_보유_0은_뺀다() throws Exception {
        String body = "{\"rt_cd\":\"0\",\"output1\":["
                + "{\"pdno\":\"005930\",\"hldg_qty\":\"10\",\"ord_psbl_qty\":\"7\",\"pchs_avg_pric\":\"70100.5000\"},"
                + "{\"pdno\":\"000660\",\"hldg_qty\":\"0\",\"ord_psbl_qty\":\"0\",\"pchs_avg_pric\":\"0\"}],"
                + "\"output2\":[{\"dnca_tot_amt\":\"1000000\",\"prvs_rcdl_excc_amt\":\"299000\","
                + "\"tot_evlu_amt\":\"1700000\"}]}";
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK).respond(200, body);
        KisBalance b = queries(http).balance();

        assertThat(http.calls.get(1).headers()).containsEntry("tr_id", "TTTC8434R");
        assertThat(http.calls.get(1).uri().getQuery()).contains("INQR_DVSN=02", "PRCS_DVSN=00", "AFHR_FLPR_YN=N");
        assertThat(b.holdings()).containsExactly(new KisBalance.Holding("005930", 10, 7, new BigDecimal("70100.5000")));
        assertThat(b.depositTotal()).isEqualByComparingTo("1000000");
        assertThat(b.settledDeposit()).isEqualByComparingTo("299000");
        assertThat(b.totalEvaluation()).isEqualByComparingTo("1700000");
        assertThat(b.complete()).isTrue();
    }

    @Test
    void 잔고_요약이_없으면_불완전이다() throws Exception {
        OrderFakes.Http http = new OrderFakes.Http().respond(200, OrderFakes.TOKEN_OK)
                .respond(200, "{\"rt_cd\":\"0\",\"output1\":[]}");
        KisBalance b = queries(http).balance();
        assertThat(b.complete()).isFalse();
        assertThat(b.holdings()).isEmpty();
    }
}
