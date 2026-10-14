package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.app.lab.web.LocalOnlyRequestFilter;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.run.CodeVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 5단계 완료 기준 "실험을 UI로 만들고 비교 가능"(UI-02, D-074)을 끝까지 잇는다: 실험 생성 API로 등록 파일을 쓰고 → 실행 명령과 같은
 * 본체({@link CombinationExperiment#runAndWrite})로 수집 스냅샷 위에서 돌리고 → 실험 폴더에서 불러와 → 리더보드·교차표 API로
 * 비교한다. 프로필은 저장소의 실제 파일({@code kr-ma-trend@1}, (나) {@code kr-vol-surge-quiet@1})을 쓰고, 일봉 스냅샷은
 * {@code CombinationExperimentTest}와 같은 손 계산용 2종목 80거래일이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExperimentCreationEndToEndTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<LocalDate> DAYS =
            KRX.tradingDays(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31)).subList(0, 80);
    private static final String SNAPSHOT = "krx-daily-test-0001";
    private static final String ID = "EXP-20261014-CMB01";
    /** 테스트는 모듈 폴더에서 돈다. 저장소 루트의 strategies/·selections/를 읽기만 한다. */
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.resolve("config").toString());
        registry.add("autotrade.lab.strategies-dir", () -> REPO.resolve("strategies").toString());
        registry.add("autotrade.lab.selections-dir", () -> REPO.resolve("selections").toString());
        registry.add("autotrade.lab.experiments-dir", () -> dir.resolve("experiments").toString());
    }

    @BeforeAll
    static void snapshot() throws IOException {
        Path snap = Files.createDirectories(dir.resolve("config").resolve("snapshots").resolve("krx-daily").resolve(SNAPSHOT));
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        for (int i = 0; i < DAYS.size(); i++) {
            LocalDate d = DAYS.get(i);
            int bc = 10_000 + 10 * i;
            bars.append("KRX,000010,").append(d).append(',').append(bc - 5).append(',').append(bc + 5).append(',')
                    .append(bc - 10).append(',').append(bc).append(',').append(i < 20 ? 100 : 400).append(",1\n");
            int ac = 10_000 + 100 * i;
            bars.append("KRX,000020,").append(d).append(',').append(ac - 50).append(',').append(ac + 50).append(',')
                    .append(ac - 100).append(',').append(ac).append(",1000,1\n");
        }
        Path b = write(snap, SnapshotManifest.DAILY_BARS, bars.toString());
        Path h = write(snap, SnapshotManifest.INSTRUMENT_HISTORY, """
                market,code,name,kind,board,valid_from,valid_to
                KRX,000010,나,STOCK,KOSDAQ/우량기업부,2000-01-04,
                KRX,000020,가,STOCK,KOSPI,2000-01-04,
                """);
        write(snap, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":2}],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                """.formatted(SNAPSHOT, Sha256.ofFile(b), DAYS.size() * 2, Sha256.ofFile(h), DAYS.get(0),
                DAYS.get(DAYS.size() - 1)));
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LocalOnlyRequestFilter.CsrfToken token;

    @Autowired
    ObjectMapper json;

    private ResultActions postJson(String url, Object body, boolean csrf) throws Exception {
        var req = post(url).header("Host", "127.0.0.1").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        return mvc.perform(csrf ? req.header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()) : req);
    }

    private static Map<String, Object> request(String id, String style, List<String> combinations) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", id);
        r.put("question", "(나) 선정이 같은 스윙 지표의 \"선정 없음\"보다 비용 차감 후 나은가");
        r.put("market", "KRX");
        r.put("style", style);
        r.put("from", DAYS.get(0).toString());
        r.put("to", DAYS.get(DAYS.size() - 1).toString());
        r.put("seed", "7");
        r.put("seedCapital", "10000000");
        r.put("barsSnapshot", SNAPSHOT);
        r.put("benchmark", "none");
        r.put("parallelism", "2");
        r.put("combinations", combinations);
        return r;
    }

    @Test
    void 화면으로_등록한_실험을_실행하고_불러와_조합끼리_비교한다() throws Exception {
        mvc.perform(get("/api/experiment-folders/snapshots").param("kind", "krx-daily").header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(SNAPSHOT))
                .andExpect(jsonPath("$.items[0].firstDate").value(DAYS.get(0).toString()))
                .andExpect(jsonPath("$.items[0].lastDate").value(DAYS.get(DAYS.size() - 1).toString()));
        // 시장·스타일 필터(STR-02): 스윙 전략 프로필만
        mvc.perform(get("/api/profiles").param("market", "KRX").param("style", "SWING").param("size", "200")
                        .header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].style", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("SWING"))))
                .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem("kr-ma-trend")));
        mvc.perform(get("/api/selections").param("market", "KRX").param("style", "SWING").header("Host", "127.0.0.1"))
                .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.contains("kr-vol-surge-quiet")));

        List<String> combos = List.of("kr-vol-surge-quiet@1 x kr-ma-trend@1", "none x kr-ma-trend@1");
        Map<String, Object> body = request(ID, "SWING", combos);

        postJson("/api/experiment-folders/preview", body, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(false))
                .andExpect(jsonPath("$.yaml", containsString("  - \"kr-vol-surge-quiet@1 x kr-ma-trend@1\"")));
        assertThat(dir.resolve("experiments").resolve(ID)).doesNotExist();

        postJson("/api/experiment-folders", body, false).andExpect(status().isForbidden());
        postJson("/api/experiment-folders", body, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.written").value(true))
                .andExpect(jsonPath("$.command").value(
                        "./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=" + ID + " -PverifyDeterminism"));
        // 사전 등록은 덮어쓰지 않는다
        postJson("/api/experiment-folders", body, true).andExpect(status().isConflict());
        mvc.perform(get("/api/experiment-folders").header("Host", "127.0.0.1"))
                .andExpect(jsonPath("$.items[0].id").value(ID))
                .andExpect(jsonPath("$.items[0].state").value("NOT_RUN"))
                .andExpect(jsonPath("$.items[0].combinations").value(2));

        // 사용자가 터미널에서 돌리는 실행 명령의 본체(정지 확인 문 없이)
        Path experimentDir = dir.resolve("experiments").resolve(ID);
        List<String> log = new ArrayList<>();
        CombinationExperiment.runAndWrite(experimentDir, REPO, dir.resolve("config").resolve("snapshots"),
                new CodeVersion("test", false), true, log::add);
        assertThat(Files.readString(experimentDir.resolve("results.md"))).contains("같음(병렬 2 vs 1");

        mvc.perform(get("/api/experiment-folders").header("Host", "127.0.0.1"))
                .andExpect(jsonPath("$.items[0].state").value("READY"));
        postJson("/api/experiment-folders/" + ID + "/load", Map.of(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID));

        // 비교: 같은 실험의 두 조합 계좌가 리더보드와 교차표에 함께 보인다
        mvc.perform(get("/api/experiments/{id}", ID).header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs", hasSize(2)));
        mvc.perform(get("/api/experiments/{id}/cross-table", ID).header("Host", "127.0.0.1"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/experiments/{id}/equity", ID).header("Host", "127.0.0.1"))
                .andExpect(status().isOk());
    }

    @Test
    void 시장_스타일이_다르거나_입력이_틀리면_쓰지_않는다() throws Exception {
        // 중기 선정 × 스윙 전략(STR-02)
        postJson("/api/experiment-folders", request("EXP-BAD-01", "SWING",
                List.of("kr-fin-quality-growth@1 x kr-ma-trend@1")), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("STR-02")));
        // 재무 조건 선정인데 재무 스냅샷 없음
        postJson("/api/experiment-folders", request("EXP-BAD-02", "POSITION",
                List.of("kr-fin-quality-growth@1 x kr-ma-trend-position@1")), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("재무 스냅샷")));
        // 없는 프로필 버전
        postJson("/api/experiment-folders", request("EXP-BAD-03", "SWING", List.of("none x kr-ma-trend@99")), true)
                .andExpect(status().isBadRequest());
        // 경로를 벗어나는 ID
        postJson("/api/experiment-folders", request("../escape", "SWING", List.of("none x kr-ma-trend@1")), true)
                .andExpect(status().isBadRequest());
        // ExperimentId 모양이 아닌 ID(소문자)
        postJson("/api/experiment-folders/preview", request("exp-lower", "SWING", List.of("none x kr-ma-trend@1")), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("대문자")));
        // 미국 시장(조합 실험 실행 명령이 아직 국내만)
        Map<String, Object> us = request("EXP-BAD-04", "SWING", List.of("none x kr-ma-trend@1"));
        us.put("market", "US");
        postJson("/api/experiment-folders", us, true).andExpect(status().isBadRequest());
        // 조합 없음
        postJson("/api/experiment-folders", request("EXP-BAD-05", "SWING", List.of()), true)
                .andExpect(status().isBadRequest());

        for (String id : List.of("EXP-BAD-01", "EXP-BAD-02", "EXP-BAD-03", "EXP-BAD-04", "EXP-BAD-05")) {
            assertThat(dir.resolve("experiments").resolve(id)).doesNotExist();
        }
        assertThat(dir.resolve("escape")).doesNotExist();
    }

    @Test
    void 선정_프로필_저장_전_의미_동일_확인_API() throws Exception {
        // 저장소의 kr-vol-surge-quiet@1에서 자격 조건 두 줄의 순서만 바꾼 편집(조건 편집기가 다시 번호를 매긴 경우)
        Map<String, String> p = new HashMap<>(com.autotrade.sim.combo.CombinationExperiment
                .loadSelection(REPO, new com.autotrade.sim.combo.CombinationPlan.Ref("kr-vol-surge-quiet", 1)).parameters());
        Map<String, String> swapped = new HashMap<>(p);
        for (String k : List.of("metric", "window", "op", "value")) {
            swapped.put("gate.01." + k, p.get("gate.02." + k));
            swapped.put("gate.02." + k, p.get("gate.01." + k));
        }
        postJson("/api/selections/kr-vol-surge-quiet/versions/1/compare", Map.of("parameters", swapped), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identical").value(false))
                .andExpect(jsonPath("$.sameSelection").value(true))
                .andExpect(jsonPath("$.differences[0]", containsString("자격 조건 순서")));
        postJson("/api/selections/kr-vol-surge-quiet/versions/1/compare", Map.of("parameters", p), true)
                .andExpect(jsonPath("$.identical").value(true));
        postJson("/api/selections/kr-vol-surge-quiet/versions/9/compare", Map.of("parameters", p), true)
                .andExpect(status().isNotFound());
    }
}
