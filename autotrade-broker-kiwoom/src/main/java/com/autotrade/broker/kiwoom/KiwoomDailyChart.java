package com.autotrade.broker.kiwoom;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * 키움 주식일봉차트정규장조회(ka11081, {@code POST /api/dostk/chart}). KRX 단독 정규장 일봉을 원가격(수정주가 미적용)으로 받는다.
 * 녹화일 원본 대조(runbook stage-2 8번, G-1)에 쓴다.
 *
 * <p>`[사실]` 근거: 사용자 제공 명세 JSON ka11081(2026-10-10). 요청 stk_cd·base_dt(YYYYMMDD)·upd_stkpc_tp(0|1),
 * 응답 {@code result_list[]}의 dt·open_pric·high_pric·low_pric·cur_prc(종가)·trde_qty. 목록은 base_dt부터 과거로 내려간다
 * (명세 예시 20261002, 20261001 순).
 * `[가정]` 가격 앞 부호(+/-)는 전일 대비 방향이라 절댓값을 쓴다(실시간 0B와 같은 형식, 예시의 pred_pre "+13000").
 * `[가정]` 6자리 종목코드는 KRX 단독이다(명세 stk_cd 설명 "KRX:039490,NXT:039490_NX,SOR:039490_AL").
 */
public final class KiwoomDailyChart {

    static final String API_ID = "ka11081";
    static final String PATH = "/api/dostk/chart";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KiwoomRestClient client;

    public KiwoomDailyChart(KiwoomRestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** 그날 정규장 일봉. 그날 거래가 없거나(휴장·거래정지) 목록에 없으면 빈 값. */
    public Optional<DailyBar> regularSession(Symbol symbol, LocalDate date) throws IOException, InterruptedException {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(date, "date");
        if (symbol.market() != Market.KRX) {
            throw new IllegalArgumentException("ka11081은 국내 종목만: " + symbol);
        }
        ObjectNode body = client.newBody();
        body.put("stk_cd", symbol.code());
        body.put("base_dt", date.format(YMD));
        body.put("upd_stkpc_tp", "0");
        JsonNode page = client.call(API_ID, PATH, body, Optional.empty()).body();
        return find(symbol, date, page);
    }

    static Optional<DailyBar> find(Symbol symbol, LocalDate date, JsonNode page) {
        String day = date.format(YMD);
        for (JsonNode row : page.path("result_list")) {
            if (!day.equals(row.path("dt").asText("").strip())) {
                continue;
            }
            try {
                BigDecimal open = KiwoomStreamProtocol.unsigned(row.path("open_pric").asText(""));
                BigDecimal high = KiwoomStreamProtocol.unsigned(row.path("high_pric").asText(""));
                BigDecimal low = KiwoomStreamProtocol.unsigned(row.path("low_pric").asText(""));
                BigDecimal close = KiwoomStreamProtocol.unsigned(row.path("cur_prc").asText(""));
                BigDecimal volume = KiwoomStreamProtocol.unsigned(row.path("trde_qty").asText(""));
                if (open == null || high == null || low == null || close == null || volume == null
                        || volume.signum() == 0) {
                    return Optional.empty(); // 거래 없는 날(명세 예시의 거래량 0 행)
                }
                return Optional.of(new DailyBar(symbol, date,
                        open, high, low, close, volume.longValueExact(), BigDecimal.ONE));
            } catch (NumberFormatException | ArithmeticException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
