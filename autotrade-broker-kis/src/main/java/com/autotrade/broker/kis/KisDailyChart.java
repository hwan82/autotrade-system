package com.autotrade.broker.kis;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 한국투자 국내주식기간별시세(FHKST03010100, {@code inquire-daily-itemchartprice}). KRX 일봉을 원주가(수정주가 미적용)로 받는다.
 * 녹화일 원본 대조와 재연결 뒤 REST 보정의 입력으로 쓴다.
 *
 * <p>`[사실]` 근거: 공식 저장소 examples_llm/domestic_stock/inquire_daily_itemchartprice(2026-10-10 확인).
 * 입력 {@code FID_COND_MRKT_DIV_CODE}(J: KRX, NX: NXT, UN: 통합), {@code FID_INPUT_ISCD}, {@code FID_INPUT_DATE_1}·{@code _2}
 * (YYYYMMDD), {@code FID_PERIOD_DIV_CODE}(D 일봉), {@code FID_ORG_ADJ_PRC}(0 수정주가, 1 원주가). 한 번에 최대 100건.
 * 응답 {@code output2[]}의 {@code stck_bsop_date}·{@code stck_oprc}·{@code stck_hgpr}·{@code stck_lwpr}·{@code stck_clpr}·
 * {@code acml_vol}. 시장 구분은 녹화 시세(H0STCNT0, KRX)와 맞추려고 J로 고정한다.
 */
public final class KisDailyChart {

    static final String PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    static final String TR_ID = "FHKST03010100";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisRestClient client;

    public KisDailyChart(KisRestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** 그날 KRX 일봉(원주가). 거래가 없었거나(휴장·거래정지) 목록에 없으면 빈 값. */
    public Optional<DailyBar> krxDay(Symbol symbol, LocalDate date) throws IOException, InterruptedException {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(date, "date");
        if (symbol.market() != Market.KRX) {
            throw new IllegalArgumentException("FHKST03010100은 국내 종목만: " + symbol);
        }
        String day = date.format(YMD);
        JsonNode body = client.get(PATH, TR_ID, Map.of(
                "FID_COND_MRKT_DIV_CODE", "J",
                "FID_INPUT_ISCD", symbol.code(),
                "FID_INPUT_DATE_1", day,
                "FID_INPUT_DATE_2", day,
                "FID_PERIOD_DIV_CODE", "D",
                "FID_ORG_ADJ_PRC", "1"));
        return find(symbol, date, body);
    }

    static Optional<DailyBar> find(Symbol symbol, LocalDate date, JsonNode body) {
        String day = date.format(YMD);
        for (JsonNode row : body.path("output2")) {
            if (!day.equals(row.path("stck_bsop_date").asText("").strip())) {
                continue;
            }
            try {
                BigDecimal open = new BigDecimal(row.path("stck_oprc").asText("").strip());
                BigDecimal high = new BigDecimal(row.path("stck_hgpr").asText("").strip());
                BigDecimal low = new BigDecimal(row.path("stck_lwpr").asText("").strip());
                BigDecimal close = new BigDecimal(row.path("stck_clpr").asText("").strip());
                long volume = Long.parseLong(row.path("acml_vol").asText("").strip());
                if (volume <= 0 || open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0) {
                    return Optional.empty(); // 거래 없는 날
                }
                return Optional.of(new DailyBar(symbol, date, open, high, low, close, volume, BigDecimal.ONE));
            } catch (IllegalArgumentException | ArithmeticException e) {
                return Optional.empty(); // 숫자가 아니거나 고가·저가가 맞지 않는 행
            }
        }
        return Optional.empty();
    }
}
