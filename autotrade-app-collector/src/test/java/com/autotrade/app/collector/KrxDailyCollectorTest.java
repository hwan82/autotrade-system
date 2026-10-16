package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.config.Secret;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KrxDailyCollectorTest {

    @TempDir
    Path cache;

    // 서울 2026-10-08(목) 19:00
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);

    private FakeHttp http() {
        return new FakeHttp((u, n) -> {
            LocalDate d = LocalDate.parse(FakeHttp.query(u, "basDd"), KrxOpenApiClient.BAS_DD);
            return FakeHttp.ok(KrxFixtures.body(d, List.of("005930;삼성전자;;1;1;1;1;0;1;10")));
        });
    }

    private KrxDailyCollector collector(FakeHttp http, int cap) {
        CallBudget budget = new CallBudget(cache, "krx", cap, clock);
        ApiCaller caller = new ApiCaller(http, budget, new Redaction(List.of(Secret.of("KEY"))), ms -> {}, 0);
        return new KrxDailyCollector(new KrxOpenApiClient(caller, "https://krx.test", "KEY"), cache, clock);
    }

    @Test
    void 주말은_건너뛰고_받은_날은_다시_부르지_않고_오늘은_캐시에_두지_않는다() throws IOException {
        FakeHttp http = http();
        // 10-02(금) ~ 10-08(목): 평일 5일 × 1시장
        var r = collector(http, 100).collect(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8), EnumSet.of(KrxBoard.KOSPI));
        assertThat(r.fetched()).isEqualTo(5);
        assertThat(http.calls).hasSize(5);
        assertThat(http.calls.get(0).toString()).isEqualTo("https://krx.test/svc/apis/sto/stk_bydd_trd?basDd=20261002");
        assertThat(http.headers.get(0)).containsEntry("AUTH_KEY", "KEY");
        assertThat(r.uncached()).containsOnlyKeys("KOSPI|2026-10-08");

        FakeHttp again = http();
        var r2 = collector(again, 100).collect(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8), EnumSet.of(KrxBoard.KOSPI));
        assertThat(again.calls).hasSize(1); // 오늘만 다시
        KrxDaySource src = collector(again, 100).source(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8),
                EnumSet.of(KrxBoard.KOSPI), r2.uncached());
        assertThat(src.dates()).hasSize(5);
        assertThat(src.rows(LocalDate.of(2026, 10, 8))).hasSize(1);
    }

    @Test
    void 상한에_닿으면_멈추고_받은_날은_남아_다음_실행이_이어_받는다() throws IOException {
        FakeHttp http = http();
        assertThatThrownBy(() -> collector(http, 3).collect(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7),
                EnumSet.allOf(KrxBoard.class))).isInstanceOf(CallBudget.BudgetExhausted.class);
        assertThat(http.calls).hasSize(3); // 10-05 세 시장
        Clock tomorrow = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp next = http();
        CallBudget budget = new CallBudget(cache, "krx", 100, tomorrow);
        ApiCaller caller = new ApiCaller(next, budget, new Redaction(List.of()), ms -> {}, 0);
        new KrxDailyCollector(new KrxOpenApiClient(caller, "https://krx.test", "KEY"), cache, tomorrow)
                .collect(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7), EnumSet.allOf(KrxBoard.class));
        assertThat(next.calls).hasSize(6);
    }

    @Test
    void 빠진_날이_있으면_스냅샷을_만들지_않는다() {
        assertThatThrownBy(() -> collector(http(), 100).source(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5),
                EnumSet.of(KrxBoard.KOSPI), java.util.Map.of())).hasMessageContaining("수집하지 않은 날");
    }

    @Test
    void 목록이_빠진_응답은_한_번만_다시_부르고_두_번째도_틀리면_멈춘다() throws IOException {
        LocalDate d = LocalDate.of(2026, 10, 7);
        FakeHttp flaky = new FakeHttp((u, n) -> n == 1 ? FakeHttp.ok("{}")
                : FakeHttp.ok(KrxFixtures.body(d, List.of("005930;삼성전자;;1;1;1;1;0;1;10"))));
        collector(flaky, 100).collect(d, d, EnumSet.of(KrxBoard.KOSPI));
        assertThat(flaky.calls).hasSize(2);
        assertThat(cache.resolve("krx/KOSPI/2026/20261007.json")).exists();

        FakeHttp broken = new FakeHttp((u, n) -> FakeHttp.ok("{\"respMsg\":\"x\"}"));
        assertThatThrownBy(() -> collector(broken, 100).collect(d.minusDays(1), d.minusDays(1), EnumSet.of(KrxBoard.KOSPI)))
                .hasMessageContaining("OutBlock_1").hasMessageContaining("respMsg=x");
        assertThat(broken.calls).hasSize(2);
    }
}
