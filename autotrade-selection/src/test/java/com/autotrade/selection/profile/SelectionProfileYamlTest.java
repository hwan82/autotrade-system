package com.autotrade.selection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import org.junit.jupiter.api.Test;

class SelectionProfileYamlTest {

    @Test
    void ADR_예시_모양을_읽고_기록용_필드는_해시에_넣지_않는다() {
        String text = """
                # 선정 프로필(ADR-007 2.1). 저장된 버전 파일은 고치지 않는다.
                id: "kr-vol-surge-quiet"
                version: 1
                market: "KRX"
                style: "SWING"
                created-at: "2026-10-10T00:00:00Z"
                parameters:
                  hypothesis: "거래량이 3배 늘었는데 가격이 3%도 오르지 못한 종목은 ..."
                  refresh: "POST_CLOSE_DAILY"
                  max-symbols: "30"
                  gate.01.metric: "volume-ratio"      # 카탈로그 키
                  gate.01.window: "20"
                """;
        SelectionProfile s = SelectionProfileYaml.parse(text, "v1.yaml");
        assertThat(s.id()).isEqualTo("kr-vol-surge-quiet");
        assertThat(s.market()).isEqualTo(Market.KRX);
        assertThat(s.style()).isEqualTo(TradingStyle.SWING);
        assertThat(s.parameters()).containsEntry("gate.01.metric", "volume-ratio").hasSize(5);
        String noted = text.replace("created-at: \"2026-10-10T00:00:00Z\"", "note: \"메모\"\nbased-on: \"x@1\"");
        assertThat(SelectionProfileYaml.parse(noted, "v1.yaml").selectionHash()).isEqualTo(s.selectionHash());
    }

    @Test
    void 필수_필드와_예약_키_오류에_파일_이름이_붙는다() {
        assertThatThrownBy(() -> SelectionProfileYaml.parse("id: s\nversion: 1\nmarket: KRX\n", "v1.yaml"))
                .hasMessageContaining("v1.yaml").hasMessageContaining("style");
        assertThatThrownBy(() -> SelectionProfileYaml.parse(
                "id: s\nversion: 1\nmarket: KRX\nstyle: SWING\nparameters:\n  refresh: POST_CLOSE_DAILY\n", "v1.yaml"))
                .hasMessageContaining("v1.yaml").hasMessageContaining("hypothesis");
        assertThatThrownBy(() -> SelectionProfileYaml.parse("id: s\nlist:\n  - a\n", "v1.yaml"))
                .hasMessageContaining("v1.yaml");
    }
}
