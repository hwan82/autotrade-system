package com.autotrade.broker.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.live.CredentialsRejectedException;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 응답 형식은 한국투자 공식 저장소 예제(auth_token, inquire_price, inquire_daily_itemchartprice)의 필드 이름을 따른다. */
class KisRestClientTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z"); // 09:00 KST
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final BrokerCredentials KEYS = new BrokerCredentials("APP-KEY", "SECRET-KEY");
    private static final String TOKEN_OK = "{\"access_token\":\"TOKEN-ONE\",\"token_type\":\"Bearer\",\"expires_in\":86400,"
            + "\"access_token_token_expired\":\"2026-10-11 09:00:00\"}";
    private static final String PRICE_OK = "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"정상처리 되었습니다.\","
            + "\"output\":{\"stck_prpr\":\"71900\",\"acml_vol\":\"1234567\"}}";
    private static final Map<String, String> PRICE_IN = Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", "005930");
    private static final String PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";

    private final List<Duration> slept = new ArrayList<>();

    private KisRestClient client(FakeHttp http) {
        TokenCache tokens = new TokenCache(new KisTokenClient(http, KEYS, CLOCK), CLOCK, Duration.ofHours(1));
        return new KisRestClient(http, tokens, KEYS, CLOCK, slept::add, KisRestClient.DEFAULT_INTERVAL);
    }

    @Test
    void 토큰을_받아_조회_헤더에_싣고_입력값을_이름순_쿼리로_보낸다() throws Exception {
        FakeHttp http = new FakeHttp().respond(200, TOKEN_OK).respond(200, PRICE_OK);
        var body = client(http).get(PRICE_PATH, "FHKST01010100", PRICE_IN);
        assertThat(body.path("output").path("stck_prpr").asText()).isEqualTo("71900");
        FakeHttp.Call token = http.calls.get(0);
        assertThat(token.uri()).hasToString("https://openapi.koreainvestment.com:9443/oauth2/tokenP");
        assertThat(new ObjectMapper().readTree(token.body()).path("appsecret").asText()).isEqualTo("SECRET-KEY");
        FakeHttp.Call get = http.calls.get(1);
        assertThat(get.method()).isEqualTo("GET");
        assertThat(get.uri()).hasToString("https://openapi.koreainvestment.com:9443" + PRICE_PATH
                + "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930");
        assertThat(get.headers()).containsEntry("authorization", "Bearer TOKEN-ONE").containsEntry("tr_id", "FHKST01010100")
                .containsEntry("custtype", "P").containsEntry("appkey", "APP-KEY");
    }

    @Test
    void 허용_목록_밖의_경로와_TR은_HTTP_요청_전에_거절한다() {
        FakeHttp http = new FakeHttp();
        KisRestClient c = client(http);
        for (String[] bad : new String[][] {
                {"/uapi/domestic-stock/v1/trading/order-cash", "TTTC0802U"},
                {"/uapi/domestic-stock/v1/trading/inquire-balance", "TTTC8434R"},
                {"/uapi/domestic-stock/v1/quotations/chk-holiday", "CTCA0903R"},
                {"/uapi/domestic-stock/v1/quotations/inquire-price/../../trading/order-cash", "FHKST01010100"},
                {PRICE_PATH, "TTTC0802U"},
                {PRICE_PATH, "FHKST03010100"}}) {
            assertThatThrownBy(() -> c.get(bad[0], bad[1], Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 허용_목록은_시세_조회_TR만_담는다() {
        assertThat(KisRestClient.READ_ONLY.keySet()).allSatisfy(p -> assertThat(p).contains("/quotations/"));
        assertThat(KisRestClient.READ_ONLY.values()).allSatisfy(tr -> assertThat(tr).matches("(FHKST|HHDFS)\\d{8}"));
    }

    @Test
    void 토큰_만료_응답이면_토큰을_새로_받아_한_번만_다시_부른다() throws Exception {
        String expired = "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}";
        FakeHttp http = new FakeHttp().respond(200, TOKEN_OK).respond(200, expired)
                .respond(200, TOKEN_OK.replace("TOKEN-ONE", "TOKEN-TWO")).respond(200, PRICE_OK);
        client(http).get(PRICE_PATH, "FHKST01010100", PRICE_IN);
        assertThat(http.calls).hasSize(4);
        assertThat(http.calls.get(3).headers()).containsEntry("authorization", "Bearer TOKEN-TWO");

        FakeHttp twice = new FakeHttp().respond(200, TOKEN_OK).respond(200, expired).respond(200, TOKEN_OK)
                .respond(200, expired);
        assertThatThrownBy(() -> client(twice).get(PRICE_PATH, "FHKST01010100", PRICE_IN))
                .isInstanceOf(KisRestClient.KisApiException.class).hasMessageContaining("EGW00123");
        assertThat(twice.calls).hasSize(4);
    }

    @Test
    void 초당_한도_초과는_한도_초과로_알리고_메시지에_비밀값과_응답_원문이_없다() {
        FakeHttp http = new FakeHttp().respond(200, TOKEN_OK)
                .respond(500, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");
        assertThatThrownBy(() -> client(http).get(PRICE_PATH, "FHKST01010100", PRICE_IN))
                .isInstanceOfSatisfying(KisRestClient.KisApiException.class, e -> {
                    assertThat(e.rateLimited()).isTrue();
                    assertThat(e.resultCode()).isEqualTo("EGW00201");
                })
                .hasMessageNotContaining("SECRET-KEY").hasMessageNotContaining("TOKEN-ONE")
                .hasMessageNotContaining("초과하였습니다");
    }

    @Test
    void 연달아_부르면_호출_간격을_지킨다() throws Exception {
        FakeHttp http = new FakeHttp().respond(200, TOKEN_OK).respond(200, PRICE_OK).respond(200, PRICE_OK);
        KisRestClient c = client(http);
        c.get(PRICE_PATH, "FHKST01010100", PRICE_IN);
        c.get(PRICE_PATH, "FHKST01010100", PRICE_IN);
        assertThat(slept).containsExactly(Duration.ofMillis(100)); // 고정 시계라 두 번째 호출이 100ms 기다린다
    }

    @Test
    void HTTP_오류와_JSON이_아닌_응답은_일반_실패다() {
        FakeHttp http = new FakeHttp().respond(200, TOKEN_OK).respond(502, "<html>bad gateway</html>");
        assertThatThrownBy(() -> client(http).get(PRICE_PATH, "FHKST01010100", PRICE_IN))
                .isInstanceOf(IOException.class).isNotInstanceOf(KisRestClient.KisApiException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    void 토큰_만료는_expires_in과_만료_일시_중_이른_쪽이고_없으면_하루다() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertThat(KisTokenClient.expiresAt(m.readTree(TOKEN_OK), NOW)).isEqualTo(NOW.plus(Duration.ofDays(1)));
        assertThat(KisTokenClient.expiresAt(m.readTree(
                "{\"expires_in\":86400,\"access_token_token_expired\":\"2026-10-10 20:00:00\"}"), NOW))
                .isEqualTo(Instant.parse("2026-10-10T11:00:00Z"));
        assertThat(KisTokenClient.expiresAt(m.readTree("{\"expires_in\":3600}"), NOW)).isEqualTo(NOW.plusSeconds(3600));
        assertThat(KisTokenClient.expiresAt(m.readTree("{\"access_token_token_expired\":\"모름\"}"), NOW))
                .isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void 토큰_발급_실패는_재발급_제한과_자격_증명_거절과_그_밖을_구분한다() {
        for (int status : new int[] {401, 403}) {
            KisTokenClient c = new KisTokenClient(new FakeHttp().respond(status,
                    "{\"error_description\":\"유효하지 않은 AppKey입니다.\",\"error_code\":\"EGW00103\"}"), KEYS, CLOCK);
            assertThatThrownBy(c::fetch).isInstanceOf(CredentialsRejectedException.class)
                    .hasMessageNotContaining("SECRET-KEY").hasMessageNotContaining("APP-KEY");
        }
        KisTokenClient throttled = new KisTokenClient(new FakeHttp().respond(403,
                "{\"error_description\":\"접근토큰 발급 잠시 후 다시 시도하세요(1분당 1회)\",\"error_code\":\"EGW00133\"}"),
                KEYS, CLOCK);
        assertThatThrownBy(throttled::fetch).isInstanceOf(IOException.class)
                .isNotInstanceOf(CredentialsRejectedException.class);
        KisTokenClient server = new KisTokenClient(new FakeHttp().respond(500, "SECRET-KEY"), KEYS, CLOCK);
        assertThatThrownBy(server::fetch).isInstanceOf(IOException.class)
                .isNotInstanceOf(CredentialsRejectedException.class).hasMessageNotContaining("SECRET-KEY");
        KisTokenClient empty = new KisTokenClient(new FakeHttp().respond(200, "{}"), KEYS, CLOCK);
        assertThatThrownBy(empty::fetch).isInstanceOf(IOException.class).hasMessageContaining("access_token 없음");
    }

    @Test
    void 키가_없으면_조회_호출기를_만들지_않는다() {
        assertThatThrownBy(() -> KisRestClient.create(BrokerCredentials.none(), new FakeHttp(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 일봉은_KRX_원주가로_그날_하루만_묻고_그날_행을_읽는다() throws Exception {
        String chart = "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"output1\":{},\"output2\":["
                + "{\"stck_bsop_date\":\"20261008\",\"stck_oprc\":\"71000\",\"stck_hgpr\":\"72500\",\"stck_lwpr\":\"70800\","
                + "\"stck_clpr\":\"71900\",\"acml_vol\":\"15000000\"}]}";
        FakeHttp http = new FakeHttp().respond(200, TOKEN_OK).respond(200, chart);
        Optional<DailyBar> bar = new KisDailyChart(client(http)).krxDay(Symbol.krx("005930"), LocalDate.of(2026, 10, 8));
        assertThat(bar).hasValueSatisfying(b -> {
            assertThat(b.close()).isEqualByComparingTo("71900");
            assertThat(b.high()).isEqualByComparingTo("72500");
            assertThat(b.volume()).isEqualTo(15_000_000L);
        });
        assertThat(http.calls.get(1).uri().getQuery()).contains("FID_COND_MRKT_DIV_CODE=J", "FID_ORG_ADJ_PRC=1",
                "FID_INPUT_DATE_1=20261008", "FID_INPUT_DATE_2=20261008", "FID_PERIOD_DIV_CODE=D");
    }

    @Test
    void 거래_없는_날과_다른_날_행과_깨진_행은_빈_값이다() throws Exception {
        ObjectMapper m = new ObjectMapper();
        LocalDate day = LocalDate.of(2026, 10, 8);
        Symbol s = Symbol.krx("005930");
        assertThat(KisDailyChart.find(s, day, m.readTree("{\"output2\":[{\"stck_bsop_date\":\"20261008\","
                + "\"stck_oprc\":\"0\",\"stck_hgpr\":\"0\",\"stck_lwpr\":\"0\",\"stck_clpr\":\"71900\",\"acml_vol\":\"0\"}]}")))
                .isEmpty();
        assertThat(KisDailyChart.find(s, day, m.readTree("{\"output2\":[{\"stck_bsop_date\":\"20261007\","
                + "\"stck_oprc\":\"1\",\"stck_hgpr\":\"1\",\"stck_lwpr\":\"1\",\"stck_clpr\":\"1\",\"acml_vol\":\"1\"}]}")))
                .isEmpty();
        assertThat(KisDailyChart.find(s, day, m.readTree("{\"output2\":[{\"stck_bsop_date\":\"20261008\","
                + "\"stck_oprc\":\"x\"}]}"))).isEmpty();
        assertThatThrownBy(() -> new KisDailyChart(client(new FakeHttp())).krxDay(Symbol.us("AAPL"), day))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 결과_코드_분류() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertThat(KisErrorCode.of(m.readTree("{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\"}")).kind())
                .isEqualTo(KisErrorCode.Kind.OK);
        assertThat(KisErrorCode.of(m.readTree("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00121\"}")).kind())
                .isEqualTo(KisErrorCode.Kind.TOKEN_EXPIRED);
        assertThat(KisErrorCode.of(m.readTree("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\"}")).kind())
                .isEqualTo(KisErrorCode.Kind.RATE_LIMITED);
        assertThat(KisErrorCode.of(m.readTree("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00215\"}")).kind())
                .isEqualTo(KisErrorCode.Kind.RATE_LIMITED);
        assertThat(KisErrorCode.of(m.readTree("{\"error_code\":\"EGW00133\"}")).kind())
                .isEqualTo(KisErrorCode.Kind.ISSUE_THROTTLED);
        assertThat(KisErrorCode.of(m.readTree("{\"rt_cd\":\"1\",\"msg_cd\":\"APBK0919\"}")).kind())
                .isEqualTo(KisErrorCode.Kind.OTHER);
        KisErrorCode odd = KisErrorCode.of(m.readTree("{\"rt_cd\":\"1\",\"msg_cd\":\"Bearer abc.def ghi\"}"));
        assertThat(odd.code()).isEmpty(); // 코드 형식이 아니면 옮기지 않는다
    }
}
