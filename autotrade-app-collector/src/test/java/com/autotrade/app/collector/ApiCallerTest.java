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
    private static final URI URL = URI.create("https://opendart.fss.or.kr/api/list.json?crtfc_key=SECRETKEY&page_no=1");

    private ApiCaller caller(FakeHttp http, int cap) {
        CallBudget budget = new CallBudget(dir, "t", cap, Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"), ZoneOffset.UTC));
        return new ApiCaller(http, budget, new Redaction(List.of(Secret.of("SECRETKEY"))), slept::add, 200);
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
        FakeHttp http = new FakeHttp((u, n) -> new HttpGetter.Response(401, "bad key SECRETKEY"));
        assertThatThrownBy(() -> caller(http, 10).get(URL, Map.of()))
                .hasMessageContaining("HTTP 401").hasMessageContaining("crtfc_key=****").hasMessageNotContaining("SECRETKEY");
        assertThat(http.calls).hasSize(1);
    }

    @Test
    void 연결_실패_메시지에도_키가_없고_재시도도_상한에_든다() {
        FakeHttp http = new FakeHttp((u, n) -> null);
        assertThatThrownBy(() -> caller(http, 2).get(URL, Map.of())).isInstanceOf(CallBudget.BudgetExhausted.class);
        assertThat(http.calls).hasSize(2);
        FakeHttp down = new FakeHttp((u, n) -> null);
        assertThatThrownBy(() -> caller(down, 100).get(URL, Map.of()))
                .isInstanceOf(IOException.class).hasMessageNotContaining("SECRETKEY");
    }
}
