package com.autotrade.broker.kis.order;

import com.autotrade.broker.kis.KisErrorCode;
import com.autotrade.broker.kis.KisTokenClient;
import com.autotrade.marketdata.live.HttpPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 한국투자 계좌 조회(계좌 하나): 일별 주문체결, 주식잔고. GET만 부른다.
 *
 * <p>`[사실]` 근거: open-trading-api cd63cae examples_llm/domestic_stock
 * <ul>
 *   <li>{@code inquire_daily_ccld}: {@code GET /uapi/domestic-stock/v1/trading/inquire-daily-ccld}, 실전 3개월 이내
 *       {@code TTTC0081R}. 입력 {@code CANO, ACNT_PRDT_CD, INQR_STRT_DT, INQR_END_DT, SLL_BUY_DVSN_CD(00 전체), PDNO,
 *       CCLD_DVSN(00 전체), INQR_DVSN(00 역순), INQR_DVSN_3(00 전체), ORD_GNO_BRNO, ODNO, INQR_DVSN_1, CTX_AREA_FK100,
 *       CTX_AREA_NK100, EXCG_ID_DVSN_CD}.</li>
 *   <li>{@code inquire_balance}: {@code GET …/inquire-balance}, 실전 {@code TTTC8434R}. 입력 {@code AFHR_FLPR_YN(N),
 *       OFL_YN, INQR_DVSN(02 종목별), UNPR_DVSN(01), FUND_STTL_ICLD_YN(N), FNCG_AMT_AUTO_RDPT_YN(N), PRCS_DVSN(00)}.</li>
 *   <li>연속 조회: 응답 헤더 {@code tr_cont}가 {@code M}·{@code F}면 다음 쪽이 있다. 다음 요청은 헤더 {@code tr_cont: N}과
 *       응답 본문 {@code ctx_area_fk100}·{@code ctx_area_nk100}을 넣는다. 공식 예제의 쪽 상한은 10.</li>
 * </ul>
 *
 * <p>토큰 만료 응답이면 토큰을 버리고 한 번만 다시 부른다(조회라 안전, 새 토큰 발급은 60초 하한). 한도 초과는
 * {@link KisQueryException#rateLimited()}로 알리고 다시 부르지 않는다. 쪽 상한을 넘거나 읽지 못한 행이 있으면 결과의
 * {@code complete}가 false다.
 */
public final class KisAccountQueries {

    static final String DAILY_PATH = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld";
    static final String BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance";
    static final String TR_DAILY = "TTTC0081R";
    static final String TR_BALANCE = "TTTC8434R";
    static final int MAX_PAGES = 10;
    private static final Set<String> MORE = Set.of("M", "F");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisSession session;
    private final KisAccountNumber account;
    private final ObjectMapper mapper = new ObjectMapper();

    public KisAccountQueries(KisSession session, KisAccountNumber account) {
        this.session = Objects.requireNonNull(session, "session");
        this.account = Objects.requireNonNull(account, "account");
    }

    /** 그날(한국 날짜) 이 계좌의 모든 주문(원주문과 취소 주문 행). */
    public KisDailyOrders dailyOrders(LocalDate date) throws IOException, InterruptedException {
        Objects.requireNonNull(date, "date");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", account.cano());
        params.put("ACNT_PRDT_CD", account.productCode());
        params.put("INQR_STRT_DT", YYYYMMDD.format(date));
        params.put("INQR_END_DT", YYYYMMDD.format(date));
        params.put("SLL_BUY_DVSN_CD", "00");
        params.put("PDNO", "");
        params.put("CCLD_DVSN", "00");
        params.put("INQR_DVSN", "00");
        params.put("INQR_DVSN_3", "00");
        params.put("ORD_GNO_BRNO", "");
        params.put("ODNO", "");
        params.put("INQR_DVSN_1", "");
        params.put("EXCG_ID_DVSN_CD", KisOrderClient.EXCHANGE);
        Instant from = session.clock().instant();
        List<KisDailyOrder> rows = new ArrayList<>();
        int[] unreadable = {0};
        boolean complete = pages(DAILY_PATH, TR_DAILY, params, body -> {
            for (JsonNode row : body.path("output1")) {
                Optional<KisDailyOrder> r = KisDailyOrder.parse(row);
                r.ifPresentOrElse(rows::add, () -> unreadable[0]++);
            }
        });
        return new KisDailyOrders(date, from, rows, complete && unreadable[0] == 0, unreadable[0]);
    }

    /** 주식잔고. 보유 0인 행은 뺀다. 금액 칸은 마지막 쪽의 output2를 쓴다. */
    public KisBalance balance() throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", account.cano());
        params.put("ACNT_PRDT_CD", account.productCode());
        params.put("AFHR_FLPR_YN", "N");
        params.put("OFL_YN", "");
        params.put("INQR_DVSN", "02");
        params.put("UNPR_DVSN", "01");
        params.put("FUND_STTL_ICLD_YN", "N");
        params.put("FNCG_AMT_AUTO_RDPT_YN", "N");
        params.put("PRCS_DVSN", "00");
        List<KisBalance.Holding> holdings = new ArrayList<>();
        int[] unreadable = {0};
        JsonNode[] summary = {null};
        boolean complete = pages(BALANCE_PATH, TR_BALANCE, params, body -> {
            for (JsonNode row : body.path("output1")) {
                try {
                    long qty = KisDailyOrder.quantity(row, "hldg_qty");
                    if (qty > 0) {
                        holdings.add(new KisBalance.Holding(KisDailyOrder.text(row, "pdno"), qty,
                                KisDailyOrder.quantity(row, "ord_psbl_qty"), KisDailyOrder.amount(row, "pchs_avg_pric")));
                    }
                } catch (RuntimeException e) {
                    unreadable[0]++;
                }
            }
            JsonNode o2 = body.path("output2");
            JsonNode s = o2.isArray() ? o2.path(0) : o2;
            if (s.isObject()) {
                summary[0] = s;
            }
        });
        BigDecimal deposit = BigDecimal.ZERO;
        BigDecimal settled = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        if (summary[0] == null) {
            unreadable[0]++;
        } else {
            try {
                deposit = KisDailyOrder.amount(summary[0], "dnca_tot_amt");
                settled = KisDailyOrder.amount(summary[0], "prvs_rcdl_excc_amt");
                total = KisDailyOrder.amount(summary[0], "tot_evlu_amt");
            } catch (RuntimeException e) {
                unreadable[0]++;
            }
        }
        return new KisBalance(holdings, deposit, settled, total, complete && unreadable[0] == 0, unreadable[0]);
    }

    /** 쪽을 차례로 받는다. 상한 안에서 마지막 쪽까지 받았으면 true. */
    private boolean pages(String path, String trId, Map<String, String> base, Consumer<JsonNode> sink)
            throws IOException, InterruptedException {
        String fk = "";
        String nk = "";
        String trCont = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> params = new LinkedHashMap<>(base);
            params.put("CTX_AREA_FK100", fk);
            params.put("CTX_AREA_NK100", nk);
            HttpPort.Response r = call(path, trId, trCont, params);
            JsonNode body = KisTokenClient.readOrNull(mapper, r.body());
            sink.accept(body);
            if (!MORE.contains(r.headers().getOrDefault("tr_cont", "").strip())) {
                return true;
            }
            fk = body.path("ctx_area_fk100").asText("").strip();
            nk = body.path("ctx_area_nk100").asText("").strip();
            trCont = "N";
        }
        return false;
    }

    /** 한 쪽을 부르고 결과 코드를 확인한다. 성공이면 본문이 있는 응답을 돌려준다. */
    private HttpPort.Response call(String path, String trId, String trCont, Map<String, String> params)
            throws IOException, InterruptedException {
        HttpPort.Response r = session.get(path, trId, trCont, params, session.token());
        JsonNode n = KisTokenClient.readOrNull(mapper, r.body());
        KisErrorCode code = n == null ? null : KisErrorCode.of(n);
        if (code != null && code.kind() == KisErrorCode.Kind.TOKEN_EXPIRED) {
            session.invalidateToken();
            r = session.get(path, trId, trCont, params, session.token());
            n = KisTokenClient.readOrNull(mapper, r.body());
            code = n == null ? null : KisErrorCode.of(n);
        }
        if (code != null && !code.ok()) {
            throw new KisQueryException("한국투자 " + trId + " 실패: " + code.describe(),
                    code.kind() == KisErrorCode.Kind.RATE_LIMITED);
        }
        if (r.status() != 200 || n == null || n.path("rt_cd").asText("").isBlank()) {
            throw new KisQueryException("한국투자 " + trId + " 실패: HTTP " + r.status(), false);
        }
        return r;
    }
}
