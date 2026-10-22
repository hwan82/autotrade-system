package com.autotrade.app.collector;

import static com.autotrade.app.collector.KisNewsFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.config.Secret;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KisNewsClientTest {

    @TempDir
    Path cache;

    static final Secret KEY = Secret.of("KISKEYAAAAAAAAAA");
    static final Secret SECRET = Secret.of("KISSECRETBBBBBBBB");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T22:00:00Z"), ZoneOffset.UTC);

    private KisNewsClient client(FakeHttp http, Redaction redaction) {
        CallBudget budget = new CallBudget(cache, "kis-news", 100, clock);
        return new KisNewsClient(new ApiCaller(http, budget, redaction, ms -> {}, 0), "https://kis.test:9443", KEY, SECRET,
                redaction);
    }

    private static Redaction redaction() {
        return new Redaction(List.of(KEY, SECRET));
    }

    @Test
    void 뉴스_제목_조회와_토큰_발급_두_주소만_부르고_키는_주소에_싣지_않는다() throws Exception {
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(List.of(row("20261021", "065900", "S1", "005930"),
                row("20261021", "065800", "S2", "")), 40));
        KisNewsClient c = client(http, redaction());
        List<KisNewsClient.Headline> page = c.page("20261021", "");
        c.page("20261021", "065800");

        assertThat(page).extracting(KisNewsClient.Headline::serial).containsExactly("S1", "S2");
        assertThat(http.posts).containsExactly(URI.create("https://kis.test:9443/oauth2/tokenP"));
        assertThat(c.tokensIssued()).isEqualTo(1); // 쪽마다 토큰을 새로 받지 않는다(발급마다 알림톡)
        Set<String> paths = http.calls.stream().map(URI::getPath).collect(Collectors.toSet());
        assertThat(paths).containsExactly("/uapi/domestic-stock/v1/quotations/news-title");
        for (int i = 0; i < http.calls.size(); i++) {
            assertThat(http.calls.get(i).toString()).doesNotContain("KISKEY").doesNotContain("KISSECRET").doesNotContain("TOKEN");
            assertThat(http.headers.get(i)).containsEntry("tr_id", "FHKST01011800").containsEntry("custtype", "P")
                    .containsEntry("appkey", KEY.reveal()).containsEntry("authorization", "Bearer TOKEN-1")
                    .doesNotContainKey("CANO"); // 계좌번호 헤더·입력 없음
        }
        assertThat(http.calls.getFirst().getRawQuery()).isEqualTo("FID_COND_MRKT_CLS_CODE=&FID_INPUT_DATE_1=20261021"
                + "&FID_INPUT_HOUR_1=&FID_INPUT_ISCD=&FID_INPUT_SRNO=&FID_NEWS_OFER_ENTP_CODE=&FID_RANK_SORT_CLS_CODE="
                + "&FID_TITL_CNTT=");
        var tokenBody = new ObjectMapper().readTree(http.postBodies.getFirst());
        assertThat(tokenBody.path("grant_type").asText()).isEqualTo("client_credentials");
    }

    @Test
    void 토큰_만료_응답이면_한_번만_다시_받아_다시_부른다() throws Exception {
        String expired = "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}";
        FakeHttp http = new FakeHttp((u, n) -> n == 1 ? FakeHttp.ok(expired)
                : FakeHttp.ok(KisNewsFixtures.body(List.of(row("20261021", "065900", "S1", "005930")))));
        KisNewsClient c = client(http, redaction());
        assertThat(c.page("20261021", "")).hasSize(1);
        assertThat(c.tokensIssued()).isEqualTo(2);
        assertThat(http.headers.get(1)).containsEntry("authorization", "Bearer TOKEN-2");

        FakeHttp always = new FakeHttp((u, n) -> FakeHttp.ok(expired));
        assertThatThrownBy(() -> client(always, redaction()).page("20261021", ""))
                .isInstanceOf(KisNewsClient.KisRejected.class).hasMessageContaining("EGW00123");
        assertThat(always.calls).hasSize(2);
    }

    @Test
    void 거절은_결과_코드만_남기고_응답_문구와_토큰은_메시지에_없다() {
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok(
                "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다. TOKEN-1\"}"));
        assertThatThrownBy(() -> client(http, redaction()).page("20261021", ""))
                .isInstanceOf(KisNewsClient.KisRejected.class).hasMessageContaining("EGW00201")
                .hasMessageContaining("--interval-ms").hasMessageNotContaining("초과하였습니다").hasMessageNotContaining("TOKEN");
        FakeHttp weird = new FakeHttp((u, n) -> FakeHttp.ok("{\"rt_cd\":\"1\",\"msg_cd\":\"<script>KIS-APPKEY\"}"));
        assertThatThrownBy(() -> client(weird, redaction()).page("20261021", ""))
                .hasMessageContaining("결과 코드 없음").hasMessageNotContaining("script");
    }

    @Test
    void 토큰_발급_실패는_재시도하지_않고_1분_뒤_안내를_한다() {
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok("{}"));
        http.postAnswer = n -> new HttpGetter.Response(403, "{\"error_code\":\"EGW00133\",\"access_token\":\"X\"}");
        assertThatThrownBy(() -> client(http, redaction()).page("20261021", ""))
                .hasMessageContaining("1분 뒤").hasMessageContaining("HTTP 403").hasMessageNotContaining("KISKEY").hasMessageNotContaining("KISSECRET");
        assertThat(http.posts).hasSize(1);
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 받은_토큰도_오류_메시지에서_가린다() throws Exception {
        Redaction r = redaction();
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok(KisNewsFixtures.body(List.of())));
        client(http, r).page("20261021", "");
        assertThat(r.clean("x TOKEN-1 y KISSECRETBBBBBBBB")).isEqualTo("x **** y ****");
    }

    @Test
    void 행_해석_시각_여섯_자리_종목코드_중복과_빈_값_필수_칸() throws Exception {
        var json = new ObjectMapper().readTree("""
                {"cntt_usiq_srno":"9","data_dt":"20261021","data_tm":"093010","hts_pbnt_titl_cntt":" 제목 ",
                 "news_ofer_entp_code":"2","dorg":"통신","news_lrdv_code":"01",
                 "iscd1":"005930","iscd2":"","iscd3":"005930","iscd4":"000660","iscd7":"035420"}""");
        KisNewsClient.Headline h = KisNewsClient.headline(json);
        assertThat(h.time()).isEqualTo("093010");
        assertThat(h.codes()).containsExactly("005930", "000660", "035420");
        assertThat(h.title()).isEqualTo("제목");
        assertThat(KisNewsClient.headline(new ObjectMapper().readTree("{\"cntt_usiq_srno\":\"9\",\"data_dt\":\"2026102\","
                + "\"data_tm\":\"0930\",\"hts_pbnt_titl_cntt\":\"t\"}"))).isNull();
        assertThat(KisNewsClient.headline(new ObjectMapper().readTree("{\"cntt_usiq_srno\":\"9\",\"data_dt\":\"20261021\","
                + "\"data_tm\":\"0930\",\"hts_pbnt_titl_cntt\":\"\"}"))).isNull();
    }

    @Test
    void 여섯_자리가_아니거나_없는_시각_날짜는_채우지_않고_거르고_센다() throws Exception {
        // 5자리 "93010"을 채우면 "930100"(93시)이 되어 실행이 매번 같은 곳에서 멈춘다(PR #222 QA Q2)
        for (String bad : List.of("93010", "9301", "930", "250000", "096100", "093060", "0930100", "09301a")) {
            assertThat(KisNewsClient.headline(new ObjectMapper().readTree("{\"cntt_usiq_srno\":\"9\",\"data_dt\":\"20261021\","
                    + "\"data_tm\":\"" + bad + "\",\"hts_pbnt_titl_cntt\":\"t\"}"))).as(bad).isNull();
        }
        assertThat(KisNewsClient.headline(new ObjectMapper().readTree("{\"cntt_usiq_srno\":\"9\",\"data_dt\":\"20261332\","
                + "\"data_tm\":\"093010\",\"hts_pbnt_titl_cntt\":\"t\"}"))).isNull();
        assertThat(KisNewsClient.headline(new ObjectMapper().readTree("{\"cntt_usiq_srno\":\"9\",\"data_dt\":\"20261021\","
                + "\"data_tm\":\"000000\",\"hts_pbnt_titl_cntt\":\"t\"}"))).isNotNull();

        String body = "{\"rt_cd\":\"0\",\"output\":[{\"cntt_usiq_srno\":\"1\",\"data_dt\":\"20261021\",\"data_tm\":\"93010\","
                + "\"hts_pbnt_titl_cntt\":\"t\"},{\"cntt_usiq_srno\":\"2\",\"data_dt\":\"20261021\",\"data_tm\":\"093010\","
                + "\"hts_pbnt_titl_cntt\":\"t\"}]}";
        KisNewsClient c = client(new FakeHttp((u, n) -> FakeHttp.ok(body)), redaction());
        assertThat(c.page("20261021", "")).extracting(KisNewsClient.Headline::serial).containsExactly("2");
        assertThat(c.badRows()).isEqualTo(1);
    }

    @Test
    void 초당_한도_초과가_HTTP_500으로_와도_다시_부르지_않고_결과_코드로_멈춘다() {
        // PR #222 QA Q1: 상태보다 본문 결과 코드를 먼저 본다(D-054 4번 자동 재시도 안 함)
        FakeHttp http = new FakeHttp((u, n) -> new HttpGetter.Response(500,
                "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}"));
        assertThatThrownBy(() -> client(http, redaction()).page("20261021", ""))
                .isInstanceOf(KisNewsClient.KisRejected.class).hasMessageContaining("EGW00201").hasMessageContaining("--interval-ms");
        assertThat(http.calls).hasSize(1);
    }

    @Test
    void 토큰_만료가_HTTP_500으로_와도_한_번만_다시_받는다() throws Exception {
        String expired = "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}";
        FakeHttp http = new FakeHttp((u, n) -> n == 1 ? new HttpGetter.Response(500, expired)
                : FakeHttp.ok(KisNewsFixtures.body(List.of(row("20261021", "065900", "S1", "005930")))));
        KisNewsClient c = client(http, redaction());
        assertThat(c.page("20261021", "")).hasSize(1);
        assertThat(c.tokensIssued()).isEqualTo(2);
        assertThat(http.calls).hasSize(2);
    }

    @Test
    void 결과_코드_없는_5xx는_전처럼_두_번_다시_부르고_성공_코드인데_200이_아니면_믿지_않는다() {
        FakeHttp plain = new FakeHttp((u, n) -> new HttpGetter.Response(503, "<html>busy</html>"));
        assertThatThrownBy(() -> client(plain, redaction()).page("20261021", "")).hasMessageContaining("HTTP 503");
        assertThat(plain.calls).hasSize(1 + ApiCaller.RETRIES);
        FakeHttp odd = new FakeHttp((u, n) -> new HttpGetter.Response(500, KisNewsFixtures.body(List.of())));
        assertThatThrownBy(() -> client(odd, redaction()).page("20261021", ""))
                .isInstanceOf(KisNewsClient.KisRejected.class).hasMessageContaining("HTTP 500");
        assertThat(odd.calls).hasSize(1);
    }

    @Test
    void 토큰_응답에_access_token이_없으면_본문을_메시지에_싣지_않는다() {
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok("{}"));
        http.postAnswer = n -> new HttpGetter.Response(200, "{\"error_description\":\"MARKERBODYTEXT\"}");
        assertThatThrownBy(() -> client(http, redaction()).page("20261021", ""))
                .hasMessageContaining("access_token").hasMessageNotContaining("MARKERBODYTEXT");
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 키가_없거나_https가_아니면_만들지_않는다() {
        CallBudget budget = new CallBudget(cache, "kis-news", 100, clock);
        ApiCaller caller = new ApiCaller(new FakeHttp((u, n) -> null), budget, redaction(), ms -> {}, 0);
        assertThatThrownBy(() -> new KisNewsClient(caller, "https://kis.test", Secret.empty(), SECRET, redaction()))
                .hasMessageContaining("kis-news.app-key");
        assertThatThrownBy(() -> new KisNewsClient(caller, "http://kis.test", KEY, SECRET, redaction()))
                .hasMessageContaining("https://");
        KisNewsClient c = new KisNewsClient(caller, "https://kis.test", KEY, SECRET, redaction());
        assertThatThrownBy(() -> c.page("2026-10-21", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.page("20261021", "0930")).isInstanceOf(IllegalArgumentException.class);
    }
}
