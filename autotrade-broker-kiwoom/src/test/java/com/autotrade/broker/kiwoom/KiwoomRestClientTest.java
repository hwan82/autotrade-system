package com.autotrade.broker.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 응답 예시는 사용자 제공 키움 REST API 명세 JSON ka11081 responseExample(2026-10-10)에서 옮겼다. */
class KiwoomRestClientTest {

    static final String KA11081 = """
            {"stk_cd":"005930","result_list":[
             {"cur_prc":"230500","trde_qty":"0","trde_prica":"0","dt":"20261002","open_pric":"230500","high_pric":"230500",
              "low_pric":"230500","pred_pre":"0","pred_pre_sig":"0","trde_tern_rt":"0.00","upd_stkpc_tp":"","upd_rt":""},
             {"cur_prc":"230500","trde_qty":"48733","trde_prica":"11383","dt":"20261001","open_pric":"177000",
              "high_pric":"280000","low_pric":"176000","pred_pre":"+13000","pred_pre_sig":"2","trde_tern_rt":"+0.00",
              "upd_stkpc_tp":"","upd_rt":""}],
             "return_code":0,"return_msg":"정상적으로 처리되었습니다"}
            """;

    /** 호출마다 앞으로 가는 시계. */
    private static final class StepClock extends Clock {
        Instant now = Instant.parse("2026-10-08T07:52:09Z"); // 토큰 예시 만료(2026-10-09 08:37 KST) 전

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final StepClock clock = new StepClock();
    private final List<Duration> slept = new ArrayList<>();
    private final FakeHttp http = new FakeHttp().respond(200, KiwoomStreamProtocolTest.TOKEN_OK);

    private KiwoomRestClient client() {
        TokenCache tokens = new TokenCache(new KiwoomTokenClient(http, new BrokerCredentials("APP-KEY", "SECRET-KEY")),
                clock, Duration.ofMinutes(10));
        return new KiwoomRestClient(http, tokens, clock, d -> {
            slept.add(d);
            clock.now = clock.now.plus(d);
        }, Duration.ofMillis(250));
    }

    @Test
    void 조회_TR은_api_id와_Bearer_토큰을_헤더에_넣어_POST한다() throws Exception {
        http.respond(200, KA11081);
        KiwoomRestClient c = client();
        KiwoomRestClient.Page p = c.call("ka11081", "/api/dostk/chart", c.newBody().put("stk_cd", "005930"), Optional.empty());

        FakeHttp.Call call = http.calls.get(1);
        assertThat(call.uri()).hasToString("https://api.kiwoom.com/api/dostk/chart");
        assertThat(call.headers()).containsEntry("api-id", "ka11081")
                .containsEntry("authorization", "Bearer TEST-TOKEN-VALUE")
                .containsEntry("Content-Type", "application/json;charset=UTF-8")
                .doesNotContainKeys("cont-yn", "next-key");
        assertThat(call.body()).isEqualTo("{\"stk_cd\":\"005930\"}");
        assertThat(p.nextKey()).isEmpty();
    }

    @Test
    void 연속조회는_응답_헤더의_next_key를_다음_요청에_넣는다() throws Exception {
        http.respond(200, "{\"return_code\":0,\"n\":1}", Map.of("cont-yn", "Y", "next-key", "K2"))
                .respond(200, "{\"return_code\":0,\"n\":2}", Map.of("cont-yn", "N", "next-key", ""));
        KiwoomRestClient c = client();
        List<JsonNode> pages = c.callAll("ka10081", "/api/dostk/chart", c.newBody(), 10);

        assertThat(pages).extracting(n -> n.path("n").asInt()).containsExactly(1, 2);
        assertThat(http.calls.get(1).headers()).doesNotContainKey("next-key");
        assertThat(http.calls.get(2).headers()).containsEntry("cont-yn", "Y").containsEntry("next-key", "K2");
    }

    @Test
    void 연속조회는_최대_쪽수에서_멈춘다() throws Exception {
        http.respond(200, "{\"return_code\":0}", Map.of("cont-yn", "Y", "next-key", "K2"))
                .respond(200, "{\"return_code\":0}", Map.of("cont-yn", "Y", "next-key", "K3"));
        KiwoomRestClient c = client();
        assertThat(c.callAll("ka10081", "/api/dostk/chart", c.newBody(), 2)).hasSize(2);
        assertThat(http.calls).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource({
        "kt10000, /api/dostk/ordr",       // 주식 매수주문
        "kt10002, /api/dostk/ordr",       // 정정
        "kt10006, /api/dostk/crdordr",    // 신용 매수주문
        "kt00018, /api/dostk/acnt",       // 계좌평가잔고
        "ka01690, /api/dostk/acnt",       // 일별잔고수익률(ka지만 계좌)
        "ust10000, /api/us/ordr",         // 미국 주문
        "ust31302, /api/us/exchange",     // 환전 신청
        "ka01300, /api/dostk/watchlist",  // 관심종목(계정)
        "kt20016, /api/dostk/stkinfo",    // 허용 경로라도 kt TR은 거절
        "ka10081, /api/dostk/ordr",       // 조회 TR 이름으로 주문 경로를 부르는 경우
        "au10002, /oauth2/revoke"
    })
    void 주문_계좌_환전_경로와_kt_ust_TR은_보내기_전에_거절한다(String apiId, String path) {
        KiwoomRestClient c = client();
        assertThatThrownBy(() -> c.call(apiId, path, c.newBody(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(http.calls).isEmpty(); // 토큰 발급조차 하지 않는다
    }

    @Test
    void 호출_간격을_지킨다() throws Exception {
        http.respond(200, "{\"return_code\":0}").respond(200, "{\"return_code\":0}");
        KiwoomRestClient c = client();
        c.call("ka10001", "/api/dostk/stkinfo", c.newBody(), Optional.empty());
        c.call("ka10001", "/api/dostk/stkinfo", c.newBody(), Optional.empty());
        assertThat(slept).containsExactly(Duration.ofMillis(250));
    }

    @Test
    void 토큰_만료_코드면_토큰을_새로_받아_한_번만_다시_부른다() throws Exception {
        http.respond(200, "{\"return_code\":3,\"return_msg\":\"[8005:Token이 유효하지 않습니다]\"}")
                .respond(200, KiwoomStreamProtocolTest.TOKEN_OK.replace("TEST-TOKEN-VALUE", "NEW-TOKEN"))
                .respond(200, "{\"return_code\":0}");
        KiwoomRestClient c = client();
        c.call("ka10001", "/api/dostk/stkinfo", c.newBody(), Optional.empty());

        assertThat(http.calls).hasSize(4);
        assertThat(http.calls.get(3).headers()).containsEntry("authorization", "Bearer NEW-TOKEN");
    }

    @Test
    void 호출_한도_초과는_재시도_가능한_오류로_알리고_본문을_옮기지_않는다() {
        http.respond(200, "{\"return_code\":1700,\"return_msg\":\"TEST-TOKEN-VALUE 초과\"}");
        KiwoomRestClient c = client();
        assertThatThrownBy(() -> c.call("ka10001", "/api/dostk/stkinfo", c.newBody(), Optional.empty()))
                .isInstanceOfSatisfying(KiwoomRestClient.KiwoomApiException.class, e -> {
                    assertThat(e.rateLimited()).isTrue();
                    assertThat(e.returnCode()).isEqualTo(1700);
                })
                .hasMessageNotContaining("TEST-TOKEN-VALUE");
    }

    @Test
    void 정규장_일봉을_원가격으로_읽는다() throws Exception {
        http.respond(200, KA11081);
        KiwoomDailyChart chart = new KiwoomDailyChart(client());
        Optional<DailyBar> bar = chart.regularSession(Symbol.krx("005930"), LocalDate.of(2026, 10, 1));

        assertThat(http.calls.get(1).body())
                .isEqualTo("{\"stk_cd\":\"005930\",\"base_dt\":\"20261001\",\"upd_stkpc_tp\":\"0\"}");
        assertThat(http.calls.get(1).headers()).containsEntry("api-id", "ka11081");
        assertThat(bar).hasValueSatisfying(b -> {
            assertThat(b.open()).isEqualByComparingTo("177000");
            assertThat(b.high()).isEqualByComparingTo("280000");
            assertThat(b.low()).isEqualByComparingTo("176000");
            assertThat(b.close()).isEqualByComparingTo("230500");
            assertThat(b.volume()).isEqualTo(48733);
            assertThat(b.adjFactor()).isEqualByComparingTo(BigDecimal.ONE);
        });
    }

    @Test
    void 거래가_없거나_목록에_없는_날은_빈_값() throws Exception {
        JsonNode page = new com.fasterxml.jackson.databind.ObjectMapper().readTree(KA11081);
        assertThat(KiwoomDailyChart.find(Symbol.krx("005930"), LocalDate.of(2026, 10, 2), page)).isEmpty();
        assertThat(KiwoomDailyChart.find(Symbol.krx("005930"), LocalDate.of(2026, 9, 30), page)).isEmpty();
    }
}
