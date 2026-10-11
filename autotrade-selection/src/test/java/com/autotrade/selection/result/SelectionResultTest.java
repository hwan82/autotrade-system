package com.autotrade.selection.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 선정 결과 정규 JSON과 내용 해시(ADR-007 2.6). 결과 파일 코덱(후속)과 다른 언어 구현이 같은 바이트를 내야 한다. */
class SelectionResultTest {

    static SelectionResult sample(List<SelectionResult.Evaluation> evals) {
        return new SelectionResult(
                new SelectionResult.Selection("kr-x", 1, "d".repeat(64), "KRX", "SWING", "POST_CLOSE_DAILY"),
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 13),
                new SelectionResult.Inputs("snap", "a".repeat(64), LocalDate.of(2026, 10, 12), null, null, null, null, null,
                        "c".repeat(64)),
                List.of(new SelectionResult.Selected("005930", 1)), evals);
    }

    static final List<SelectionResult.Evaluation> EVALS = List.of(
            new SelectionResult.Evaluation("005930", true, List.of(), new TreeMap<>(Map.of("gate.01", "3")), false),
            new SelectionResult.Evaluation("000020", false, List.of("GATE_01"), new TreeMap<>(Map.of("gate.01", "1.42")),
                    false));

    @Test
    void 정규_JSON은_키_사전순이고_평가는_코드순이다() {
        assertThat(sample(EVALS).canonicalJson()).isEqualTo("{\"apply_from\":\"2026-10-13\",\"apply_to\":\"2026-10-13\","
                + "\"evaluations\":[{\"code\":\"000020\",\"passed\":false,\"reasons\":[\"GATE_01\"],\"restatement_fallback\":false,"
                + "\"values\":{\"gate.01\":\"1.42\"}},{\"code\":\"005930\",\"passed\":true,\"reasons\":[],"
                + "\"restatement_fallback\":false,\"values\":{\"gate.01\":\"3\"}}],"
                + "\"inputs\":{\"bars\":{\"last_trading_date\":\"2026-10-12\",\"sha256\":\"" + "a".repeat(64)
                + "\",\"snapshot_id\":\"snap\"},\"calendar\":{\"sources_digest\":\"" + "c".repeat(64) + "\"},"
                + "\"financials\":null,\"shares\":null},\"kind\":\"selection-result\",\"restatement_fallback_count\":0,"
                + "\"schema\":1,\"selected\":[{\"code\":\"005930\",\"rank\":1}],\"selection\":{\"hash\":\"" + "d".repeat(64)
                + "\",\"id\":\"kr-x\",\"market\":\"KRX\",\"refresh\":\"POST_CLOSE_DAILY\",\"style\":\"SWING\",\"version\":1}}");
        assertThat(sample(EVALS).contentHash()).isEqualTo(sample(EVALS.reversed()).contentHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void 형식_위반을_거부한다() {
        assertThatThrownBy(() -> new SelectionResult.Evaluation("1", true, List.of("X"), new TreeMap<>(), false))
                .hasMessageContaining("사유");
        assertThatThrownBy(() -> sample(List.of(EVALS.get(0), EVALS.get(0)))).hasMessageContaining("두 번");
    }
}
