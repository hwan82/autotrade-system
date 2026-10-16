package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class KrxOpenApiClientTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 8);

    @Test
    void 쉼표와_빈_가격과_표준코드를_읽는다() throws IOException {
        String body = KrxFixtures.body(D, List.of(
                "005930;삼성전자;;61,000;62,000;60,500;61,500;500;1,234,567;5,969,782,550",
                "KR7000660001;SK하이닉스;;-;;-;180000;0;0;728002365"));
        List<KrxDailyRow> rows = KrxOpenApiClient.parse(KrxBoard.KOSPI, D, body);
        assertThat(rows).hasSize(2);
        KrxDailyRow a = rows.get(0);
        assertThat(a.code()).isEqualTo("005930");
        assertThat(a.open()).isEqualByComparingTo("61000");
        assertThat(a.change()).isEqualByComparingTo("500");
        assertThat(a.volume()).isEqualTo(1_234_567L);
        assertThat(a.listedShares()).isEqualTo(5_969_782_550L);
        KrxDailyRow b = rows.get(1);
        assertThat(b.code()).isEqualTo("000660");
        assertThat(b.open()).isNull();
        assertThat(b.high()).isNull();
        assertThat(b.close()).isEqualTo(new BigDecimal("180000"));
    }

    @Test
    void 휴장일은_빈_목록이다() throws IOException {
        assertThat(KrxOpenApiClient.parse(KrxBoard.KOSDAQ, D, "{\"OutBlock_1\":[]}")).isEmpty();
    }

    @Test
    void 모양이_다른_응답은_멈춘다() {
        assertThatThrownBy(() -> KrxOpenApiClient.parse(KrxBoard.KOSPI, D, "{\"respCode\":\"401\"}"))
                .hasMessageContaining("OutBlock_1");
        assertThatThrownBy(() -> KrxOpenApiClient.parse(KrxBoard.KOSDAQ, D,
                "{\"respMsg\":\"Unauthorized API Call\",\"respCode\":\"401\"}"))
                .hasMessageContaining("응답 항목: respMsg, respCode")
                .hasMessageContaining("respCode=401, respMsg=Unauthorized API Call");
        assertThatThrownBy(() -> KrxOpenApiClient.parse(KrxBoard.KOSDAQ, D, "[]"))
                .hasMessageContaining("JSON 객체가 아닙니다");
        String other = KrxFixtures.body(D.minusDays(1), List.of("005930;A;;1;1;1;1;0;1;1"));
        assertThatThrownBy(() -> KrxOpenApiClient.parse(KrxBoard.KOSPI, D, other)).hasMessageContaining("날짜");
        String bad = KrxFixtures.body(D, List.of("59;A;;1;1;1;1;0;1;1"));
        assertThatThrownBy(() -> KrxOpenApiClient.parse(KrxBoard.KOSPI, D, bad)).hasMessageContaining("코드");
    }
}
