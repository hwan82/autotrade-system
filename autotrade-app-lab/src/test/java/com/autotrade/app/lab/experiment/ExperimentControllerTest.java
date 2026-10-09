package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import com.autotrade.sim.ExperimentId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** UI-02: 목록, 리더보드, 자산 곡선, 체결·왕복·거절 사유 드릴다운, 페이징. 필드 계약 테스트를 겸한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ExperimentControllerTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.resolve("config").toString());
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("strategies").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ExperimentCatalog catalog;

    @Autowired
    com.autotrade.app.lab.web.LocalOnlyRequestFilter.CsrfToken token;

    @BeforeEach
    void register() {
        if (catalog.detail(new ExperimentId("EXP-20261008-01")).isPresent()) {
            return;
        }
        SampleReports.Sample older = SampleReports.krx("EXP-20260901-01", 5);
        SampleReports.Sample newer = SampleReports.krx("EXP-20261008-01", 20);
        catalog.register(older.spec(), older.report());
        catalog.register(newer.spec(), newer.report());
    }

    @Test
    void 목록은_최근_실험이_위이고_실험_조건을_담는다() throws Exception {
        mvc.perform(get("/api/experiments").param("size", "1"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value("EXP-20261008-01"))
                .andExpect(jsonPath("$.items[0].market").value("KRX"))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].seedCapital").value("10000000"))
                .andExpect(jsonPath("$.items[0].randomSeed").value(42))
                .andExpect(jsonPath("$.items[0].costModelId").value("KR-DEFAULT-2026"))
                .andExpect(jsonPath("$.items[0].executionModelId").value("BOOK-V1+500ms"))
                .andExpect(jsonPath("$.items[0].runCount").value(3))
                .andExpect(jsonPath("$.items[0].completedRuns").value(2))
                .andExpect(jsonPath("$.items[0].failedRuns").value(1));
    }

    @Test
    void 상세는_계좌별_지표를_문자열_소수로_준다() throws Exception {
        mvc.perform(get("/api/experiments/EXP-20261008-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs", hasSize(3)))
                .andExpect(jsonPath("$.runs[0].runId").value("EXP-20261008-01-A01"))
                .andExpect(jsonPath("$.runs[0].profileId").value("breakout"))
                .andExpect(jsonPath("$.runs[0].profileVersion").value(2))
                .andExpect(jsonPath("$.runs[0].metrics.maxDrawdown").value("0.012345"))
                .andExpect(jsonPath("$.runs[0].metrics.totalCosts").value("5000"))
                .andExpect(jsonPath("$.runs[0].rejectionCount").value(4))
                .andExpect(jsonPath("$.runs[1].metrics.sharpe").doesNotExist())
                .andExpect(jsonPath("$.runs[2].status").value("FAILED"))
                .andExpect(jsonPath("$.runs[2].failureReason").value("모르는 프로필: broken"))
                .andExpect(jsonPath("$.runs[2].metrics").doesNotExist());
    }

    @Test
    void 자산_곡선은_계좌마다_거래일_점을_준다() throws Exception {
        mvc.perform(get("/api/experiments/EXP-20261008-01/equity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.seedCapital").value("10000000"))
                .andExpect(jsonPath("$.series", hasSize(3)))
                .andExpect(jsonPath("$.series[0].points", hasSize(20)))
                .andExpect(jsonPath("$.series[0].points[0].date").value("2026-09-01"))
                .andExpect(jsonPath("$.series[0].points[0].equity").value("10025000"))
                .andExpect(jsonPath("$.series[2].points", hasSize(0)));
    }

    @Test
    void 체결_내역은_페이징한다() throws Exception {
        String url = "/api/experiments/EXP-20261008-01/runs/EXP-20261008-01-A01/fills";
        mvc.perform(get(url).param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.items[0].key").value("20260901-005930-BUY-1"))
                .andExpect(jsonPath("$.items[0].price").value("71200"))
                .andExpect(jsonPath("$.items[0].commission").value("106.8"))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].realizedPnl").doesNotExist())
                .andExpect(jsonPath("$.items[1].realizedPnl").value("2500"))
                .andExpect(jsonPath("$.items[0].fillTime").value("2026-09-01T00:10:00.500Z"));
        mvc.perform(get(url).param("page", "2").param("size", "5"))
                .andExpect(jsonPath("$.items", hasSize(2)));
        mvc.perform(get(url).param("page", "9").param("size", "5"))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get(url).param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("page", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"));
    }

    @Test
    void 왕복_거래를_준다() throws Exception {
        mvc.perform(get("/api/experiments/EXP-20261008-01/runs/EXP-20261008-01-A01/round-trips"))
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.items[0].pnl").value("2500"));
    }

    @Test
    void 거절_사유는_분포와_함께_걸러_볼_수_있다() throws Exception {
        String url = "/api/experiments/EXP-20261008-01/runs/EXP-20261008-01-A01/rejections";
        mvc.perform(get(url))
                .andExpect(jsonPath("$.page.total").value(4))
                .andExpect(jsonPath("$.counts", hasSize(2)))
                .andExpect(jsonPath("$.counts[0].stage").value("RISK"))
                .andExpect(jsonPath("$.counts[0].code").value("BLACKLIST"))
                .andExpect(jsonPath("$.counts[0].count").value(3))
                .andExpect(jsonPath("$.counts[1].code").value("INSUFFICIENT_CASH"));
        mvc.perform(get(url).param("stage", "RISK").param("code", "BLACKLIST"))
                .andExpect(jsonPath("$.page.total").value(3))
                .andExpect(jsonPath("$.page.items[0].symbol").value("000660"))
                .andExpect(jsonPath("$.counts", hasSize(2)));
    }

    @Test
    void 없는_실험과_계좌는_404이고_형식이_틀린_ID는_400이다() throws Exception {
        mvc.perform(get("/api/experiments/EXP-NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments/exp-lower")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/experiments/EXP-20261008-01/runs/OTHER/fills")).andExpect(status().isNotFound());
        mvc.perform(get("/api/nothing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/experiments")
                        .header(com.autotrade.app.lab.web.LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void 같은_실험_ID는_다시_등록할_수_없다() {
        SampleReports.Sample again = SampleReports.krx("EXP-20261008-01", 3);
        assertThatThrownBy(() -> catalog.register(again.spec(), again.report()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 명세와_결과의_ID가_다르면_등록하지_않는다() {
        SampleReports.Sample a = SampleReports.krx("EXP-A", 3);
        SampleReports.Sample b = SampleReports.krx("EXP-B", 3);
        assertThatThrownBy(() -> catalog.register(a.spec(), b.report())).isInstanceOf(IllegalArgumentException.class);
    }
}
