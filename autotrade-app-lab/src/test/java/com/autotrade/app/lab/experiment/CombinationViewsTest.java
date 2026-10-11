package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ExperimentId;
import com.autotrade.strategy.TradingStyle;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 조합 실험 화면 API(UI-02 교차표, UI-03·08 선정 결과 조회, ADR-007): 리더보드 조합 칸, 교차표 대조군 차이(손 계산),
 * 날짜별 선정 기록, 선정 결과 본문과 정정 대체 규칙 표시. 필드 계약 테스트를 겸한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CombinationViewsTest {

    private static final String EXP = "EXP-20261011-01";

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.resolve("config").toString());
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("strategies").toString());
        registry.add("autotrade.lab.selections-dir", () -> dir.resolve("selections").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ExperimentCatalog catalog;

    static SampleReports.ComboSample sample;

    @BeforeEach
    void register() {
        if (catalog.detail(new ExperimentId(EXP)).isPresent()) {
            return;
        }
        sample = SampleReports.combos(EXP, 20);
        catalog.register(sample.spec(), sample.report(), sample.results());
    }

    @Test
    void 리더보드_행은_조합_해시와_선정_기록_요약을_담는다() throws Exception {
        Combination selTrend = sample.spec().combinations().get(0);
        Combination noneTrend = sample.spec().combinations().get(2);
        mvc.perform(get("/api/experiments/" + EXP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs", hasSize(4)))
                .andExpect(jsonPath("$.runs[0].selectionId").value("kr-fin-quality-growth"))
                .andExpect(jsonPath("$.runs[0].selectionVersion").value(1))
                .andExpect(jsonPath("$.runs[0].selectionHash").value(selTrend.selectionHashOrNone()))
                .andExpect(jsonPath("$.runs[0].combinationHash").value(selTrend.combinationHash()))
                // 20거래일: 첫날 결과 없음, 나머지 19일 결과 있음. 결과는 ISO 주마다 하나(9/1~9/28 → 5주)
                .andExpect(jsonPath("$.runs[0].selection.validDays").value(19))
                .andExpect(jsonPath("$.runs[0].selection.invalidDays").value(1))
                .andExpect(jsonPath("$.runs[0].selection.invalidReasons[0].code").value("MISSING"))
                .andExpect(jsonPath("$.runs[0].selection.results").value(5))
                .andExpect(jsonPath("$.runs[0].selection.unknownResults").value(3))
                .andExpect(jsonPath("$.runs[0].selection.restatementFallback").value(1))
                // 막은 신규 진입: 첫날 2건 + i%4==0인 날(4,8,12,16) 1건씩
                .andExpect(jsonPath("$.runs[0].selection.blockedEntries").value(6))
                .andExpect(jsonPath("$.runs[2].selectionId").value(nullValue()))
                .andExpect(jsonPath("$.runs[2].selectionHash").value("none"))
                .andExpect(jsonPath("$.runs[2].combinationHash").value(noneTrend.combinationHash()))
                .andExpect(jsonPath("$.runs[2].selection").value(nullValue()));
        mvc.perform(get("/api/experiments/" + EXP + "/equity"))
                .andExpect(jsonPath("$.series[0].selectionId").value("kr-fin-quality-growth"))
                .andExpect(jsonPath("$.series[3].selectionId").value(nullValue()));
        mvc.perform(get("/api/experiments/" + EXP + "/runs/" + EXP + "-A01/summary"))
                .andExpect(jsonPath("$.selectionId").value("kr-fin-quality-growth"))
                .andExpect(jsonPath("$.combinationHash").value(selTrend.combinationHash()));
    }

    @Test
    void 교차표는_선정_없음_행을_맨_아래에_두고_대조군_차이를_계산한다() throws Exception {
        // 손 계산: 총수익률 A01 0.033, A02 0.011, A03 0.022, A04 -0.0055
        mvc.perform(get("/api/experiments/" + EXP + "/cross-table")
                        .param("rowControl", "kr-random-daily-position@1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("totalReturn"))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].key").value("kr-fin-quality-growth@1"))
                .andExpect(jsonPath("$.rows[1].none").value(true))
                .andExpect(jsonPath("$.rows[1].key").value("none"))
                .andExpect(jsonPath("$.columns[0].key").value("kr-ma-trend-position@1"))
                .andExpect(jsonPath("$.columns[1].key").value("kr-random-daily-position@1"))
                .andExpect(jsonPath("$.cells", hasSize(4)))
                .andExpect(jsonPath("$.cells[0].runId").value(EXP + "-A01"))
                .andExpect(jsonPath("$.cells[0].value").value("0.033000"))
                .andExpect(jsonPath("$.cells[0].deltaVsNoSelection").value("0.011000"))
                .andExpect(jsonPath("$.cells[0].deltaVsRowControl").value("0.022000"))
                .andExpect(jsonPath("$.cells[1].runId").value(EXP + "-A02"))
                .andExpect(jsonPath("$.cells[1].deltaVsNoSelection").value("0.016500"))
                .andExpect(jsonPath("$.cells[1].deltaVsRowControl").value(nullValue()))
                .andExpect(jsonPath("$.cells[2].runId").value(EXP + "-A03"))
                .andExpect(jsonPath("$.cells[2].deltaVsNoSelection").value(nullValue()))
                .andExpect(jsonPath("$.cells[2].deltaVsRowControl").value("0.027500"));
        mvc.perform(get("/api/experiments/" + EXP + "/cross-table"))
                .andExpect(jsonPath("$.rowControl").value(nullValue()))
                .andExpect(jsonPath("$.cells[0].deltaVsRowControl").value(nullValue()));
        mvc.perform(get("/api/experiments/" + EXP + "/cross-table").param("metric", "sharpe"))
                .andExpect(jsonPath("$.cells[0].value").value("1.8"))
                .andExpect(jsonPath("$.cells[0].deltaVsNoSelection").value("0.0"));
        mvc.perform(get("/api/experiments/" + EXP + "/cross-table").param("metric", "finalEquity"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/experiments/" + EXP + "/cross-table").param("rowControl", "nope@1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 선정_없는_계좌의_교차표는_선정_없음_행만_있다() throws Exception {
        SampleReports.Sample plain = SampleReports.krx("EXP-20261011-02", 5);
        catalog.register(plain.spec(), plain.report());
        mvc.perform(get("/api/experiments/EXP-20261011-02/cross-table"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].none").value(true))
                // 실패 Run은 값과 차이가 없다
                .andExpect(jsonPath("$.cells[2].status").value("FAILED"))
                .andExpect(jsonPath("$.cells[2].value").value(nullValue()));
        mvc.perform(get("/api/experiments/EXP-20261011-02/runs/EXP-20261011-02-A01/selection-days"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 날짜별_선정_기록은_판정과_결과_본문_유무를_보인다() throws Exception {
        String week1 = sample.results().get(0).contentHash();
        mvc.perform(get("/api/experiments/" + EXP + "/runs/" + EXP + "-A01/selection-days").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(20))
                .andExpect(jsonPath("$.items[0].date").value("2026-09-01"))
                .andExpect(jsonPath("$.items[0].validity").value("MISSING"))
                .andExpect(jsonPath("$.items[0].resultHash").value(nullValue()))
                .andExpect(jsonPath("$.items[0].blockedEntries").value(2))
                .andExpect(jsonPath("$.items[0].resultAvailable").value(false))
                .andExpect(jsonPath("$.items[1].validity").value("VALID"))
                .andExpect(jsonPath("$.items[1].resultHash").value(week1))
                .andExpect(jsonPath("$.items[1].symbolCount").value(2))
                .andExpect(jsonPath("$.items[1].resultAvailable").value(true))
                .andExpect(jsonPath("$.items[1].restatementFallback").value(1));
        // 셋째 주부터는 본문이 없다
        mvc.perform(get("/api/experiments/" + EXP + "/runs/" + EXP + "-A01/selection-days")
                        .param("page", "1").param("size", "10"))
                .andExpect(jsonPath("$.items[9].resultAvailable").value(false))
                .andExpect(jsonPath("$.items[9].restatementFallback").value(nullValue()));
    }

    @Test
    void 선정_결과는_입력_기준일_선정_종목_사유별_건수_정정_대체_수를_준다() throws Exception {
        SelectionResult r = sample.results().get(0);
        String base = "/api/experiments/" + EXP + "/selection-results/" + r.contentHash();
        mvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentHash").value(r.contentHash()))
                .andExpect(jsonPath("$.selection.id").value("kr-fin-quality-growth"))
                .andExpect(jsonPath("$.selection.refresh").value("POST_CLOSE_WEEKLY"))
                .andExpect(jsonPath("$.applyFrom").value("2026-09-02"))
                .andExpect(jsonPath("$.applyTo").value("2026-09-04"))
                .andExpect(jsonPath("$.inputs.barsLastTradingDate").value("2026-09-01"))
                .andExpect(jsonPath("$.inputs.financialsLastCollectedAt").value("2026-08-31T09:00:00Z"))
                .andExpect(jsonPath("$.universeCount").value(5))
                .andExpect(jsonPath("$.selectedCount").value(2))
                .andExpect(jsonPath("$.restatementFallback").value(1))
                .andExpect(jsonPath("$.reasonCounts", hasSize(3)))
                .andExpect(jsonPath("$.reasonCounts[0].code").value("GATE_01"))
                .andExpect(jsonPath("$.reasonCounts[0].count").value(2))
                .andExpect(jsonPath("$.selected[0].rank").value(1))
                .andExpect(jsonPath("$.selected[0].code").value("005930"));

        mvc.perform(get(base + "/evaluations"))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[0].code").value("000660"))
                .andExpect(jsonPath("$.items[0].rank").value(2))
                .andExpect(jsonPath("$.items[0].restatementFallback").value(true))
                .andExpect(jsonPath("$.items[0].values['gate.01']").value("0.6"));
        mvc.perform(get(base + "/evaluations").param("filter", "fallback"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].code").value("000660"));
        mvc.perform(get(base + "/evaluations").param("filter", "failed").param("reason", "GATE_02"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].code").value("051910"))
                .andExpect(jsonPath("$.items[0].rank").value(nullValue()));
        mvc.perform(get(base + "/evaluations").param("filter", "weird")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/experiments/" + EXP + "/selection-results/" + "f".repeat(64)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments/" + EXP + "/selection-results/not-a-hash"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 실험_조합에_없는_선정의_결과는_등록하지_않는다() {
        SampleReports.ComboSample other = SampleReports.combos("EXP-20261011-03", 5);
        TreeMap<String, String> p = new TreeMap<>(SampleReports.finSelection().parameters());
        p.put("max-symbols", "10");
        SelectionProfile foreign = new SelectionProfile("kr-other", 1, Market.KRX, TradingStyle.POSITION, p);
        SelectionResult r = other.results().get(0);
        SelectionResult foreignResult = new SelectionResult(SelectionResult.Selection.of(foreign), r.applyFrom(),
                r.applyTo(), r.inputs(), r.selected(), r.evaluations());

        assertThatThrownBy(() -> catalog.register(other.spec(), other.report(), List.of(foreignResult)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kr-other@1");
        assertThat(catalog.detail(new ExperimentId("EXP-20261011-03"))).isEmpty();
    }
}
