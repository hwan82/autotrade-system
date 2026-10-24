package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.config.Secret;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiCallerTest {

    @TempDir
    Path dir;

    private final List<Long> slept = new ArrayList<>();
    private static final URI URL = URI.create("https://opendart.fss.or.kr/api/list.json?crtfc_key=TESTKEY&page_no=1");

    private ApiCaller caller(FakeHttp http, int cap) {
        CallBudget budget = new CallBudget(dir, "t", cap, Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"), ZoneOffset.UTC));
        return new ApiCaller(http, budget, new Redaction(List.of(Secret.of("TESTKEY"))), slept::add, 200);
    }

    @Test
    void 일시_오류는_두_번까지_다시_부르고_호출마다_간격을_둔다() throws IOException {
        FakeHttp http = new FakeHttp((u, n) -> n == 1 ? null : n == 2 ? new HttpGetter.Response(503, "") : FakeHttp.ok("{}"));
        ApiCaller c = caller(http, 10);
        assertThat(c.get(URL, Map.of())).isEqualTo("{}");
        assertThat(http.calls).hasSize(3);
        assertThat(slept).containsExactly(1000L, 2000L);
        c.get(URL, Map.of());
        assertThat(slept).containsExactly(1000L, 2000L, 200L);
    }

    @Test
    void 인증_오류는_다시_부르지_않고_메시지에_키가_없다() {
        FakeHttp http = new FakeHttp((u, n) -> new HttpGetter.Response(401, "bad key TESTKEY"));
        assertThatThrownBy(() -> caller(http, 10).get(URL, Map.of()))
                .hasMessageContaining("HTTP 401").hasMessageContaining("crtfc_key=****").hasMessageNotContaining("TESTKEY");
        assertThat(http.calls).hasSize(1);
    }

    @Test
    void 연결_실패_메시지에도_키가_없고_재시도도_상한에_든다() {
        FakeHttp http = new FakeHttp((u, n) -> null);
        assertThatThrownBy(() -> caller(http, 2).get(URL, Map.of())).isInstanceOf(CallBudget.BudgetExhausted.class);
        assertThat(http.calls).hasSize(2);
        FakeHttp down = new FakeHttp((u, n) -> null);
        assertThatThrownBy(() -> caller(down, 100).get(URL, Map.of()))
                .isInstanceOf(IOException.class).hasMessageNotContaining("TESTKEY");
    }

    @Test
    void 토큰_발급_POST도_보내기_전에_장_시간을_확인한다() {
        // PR #222 QA Q3(M1): 장 시간 보호 구간에 들어섰으면 POST를 보내지 않는다
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok("{}"));
        CallBudget budget = new CallBudget(dir, "t", 10, Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"), ZoneOffset.UTC));
        ApiCaller c = new ApiCaller(http, budget, new Redaction(List.of()), ms -> {}, 0, () -> {
            throw new IllegalStateException("장 시간 보호 구간");
        });
        assertThatThrownBy(() -> c.post(URI.create("https://kis.test/oauth2/tokenP"), Map.of(), "{}"))
                .hasMessageContaining("장 시간");
        assertThat(http.posts).isEmpty();
        assertThatThrownBy(() -> c.get(URL, Map.of())).hasMessageContaining("장 시간");
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 증권사가_결과_코드로_답한_5xx는_다시_부르지_않고_그대로_돌려준다() throws IOException {
        FakeHttp http = new FakeHttp((u, n) -> new HttpGetter.Response(500, "ANSWERED"));
        HttpGetter.Response r = caller(http, 10).getAnswered(URL, Map.of(), x -> x.body().equals("ANSWERED"));
        assertThat(r.status()).isEqualTo(500);
        assertThat(http.calls).hasSize(1);
        FakeHttp other = new FakeHttp((u, n) -> new HttpGetter.Response(500, "busy"));
        assertThatThrownBy(() -> caller(other, 10).getAnswered(URL, Map.of(), x -> x.body().equals("ANSWERED")))
                .hasMessageContaining("HTTP 500");
        assertThat(other.calls).hasSize(1 + ApiCaller.RETRIES);
    }

    @Test
    void 수집기가_읽은_키는_모두_가린다() {
        // PR #222 QA Q3(M10): 한국투자 앱키·시크릿도 가림 목록에 있다
        CollectorSecrets s = new CollectorSecrets(Secret.of("KRXKEYAAAAAAAAAA"), Secret.of("DARTKEYBBBBBBBB"),
                Secret.of("KISKEYCCCCCCCCCC"), Secret.of("KISSECRETDDDDDDDD"));
        assertThat(CollectorMain.redaction(s).clean("a KRXKEYAAAAAAAAAA b DARTKEYBBBBBBBB c KISKEYCCCCCCCCCC d KISSECRETDDDDDDDD"))
                .isEqualTo("a **** b **** c **** d ****");
    }

    @Test
    void postAnswered는_거절도_한_번만_보내고_돌려주며_post는_그대로_예외다() throws Exception {
        // 2026-10-24 토큰 발급 403: 결과 코드를 읽으려 거절 응답을 돌려받되, 재시도는 하지 않는다(1분 1회 발급)
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok("{}"));
        http.postAnswer = n -> new HttpGetter.Response(403, "{\"error_code\":\"EGW00133\"}");
        ApiCaller c = caller(http, 10);
        HttpGetter.Response r = c.postAnswered(URI.create("https://kis.test/oauth2/tokenP"), Map.of(), "{}");
        assertThat(r.status()).isEqualTo(403);
        assertThat(http.posts).hasSize(1);
        assertThatThrownBy(() -> c.post(URI.create("https://kis.test/oauth2/tokenP"), Map.of(), "{}"))
                .hasMessageContaining("HTTP 403").hasMessageNotContaining("EGW00133");
        assertThat(http.posts).hasSize(2);
    }
}
