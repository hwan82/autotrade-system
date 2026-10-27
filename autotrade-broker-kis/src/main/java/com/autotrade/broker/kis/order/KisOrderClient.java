package com.autotrade.broker.kis.order;

import com.autotrade.broker.kis.KisErrorCode;
import com.autotrade.broker.kis.KisTokenClient;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.live.HttpPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 한국투자 국내주식 현금 주문과 취소(계좌 하나). 주문을 보내는 유일한 클래스다.
 *
 * <p>`[사실]` 근거: koreainvestment/open-trading-api 커밋 cd63cae(2026-10-23) examples_llm/domestic_stock
 * <ul>
 *   <li>{@code order_cash}: {@code POST /uapi/domestic-stock/v1/trading/order-cash}, 실전 매수 {@code TTTC0012U}, 매도
 *       {@code TTTC0011U}. 본문 {@code CANO, ACNT_PRDT_CD, PDNO, ORD_DVSN, ORD_QTY, ORD_UNPR, EXCG_ID_DVSN_CD, SLL_TYPE,
 *       CNDT_PRIC}(값은 모두 문자열). 응답 {@code output.KRX_FWDG_ORD_ORGNO, ODNO, ORD_TMD}.</li>
 *   <li>주문구분 {@code 00} 지정가, {@code 01} 시장가(backtester constants.py {@code OrderDivision}). 단가 없는 주문은 상한가로
 *       주문금액을 잡는다(order_cash 주석). 매도유형 {@code 01} 일반매도.</li>
 *   <li>{@code order_rvsecncl}: {@code POST …/order-rvsecncl}, 실전 {@code TTTC0013U}, 정정취소구분 {@code 02} 취소,
 *       잔량전부 {@code Y}.</li>
 *   <li>이전 TR({@code TTTC0802U·0801U·0803U}, backtester constants.py)은 쓰지 않는다. 같은 저장소의 최신 예제가 새 TR이다.</li>
 * </ul>
 *
 * <p>안전 경계
 * <ul>
 *   <li>KRX 종목만, 이 클라이언트의 계좌로만 보낸다(D-010). 정정은 하지 않는다(취소만).</li>
 *   <li>어떤 결과에서도 스스로 다시 보내지 않는다. 응답을 모르면 {@link KisOrderResult.Unknown}이다(지침 4장 2항).
 *       한도 초과·토큰 오류 코드는 HTTP 상태와 상관없이 "접수 안 됨"이다(게이트웨이가 원장 앞에서 막음). 그 밖의 오류 코드는
 *       HTTP 200일 때만 원장 거절로 확정하고, 200이 아니면 모름이다.</li>
 *   <li>요청·응답 원문, 계좌번호, 키는 결과·예외 메시지에 넣지 않는다(안전 불변식 9).</li>
 * </ul>
 */
public final class KisOrderClient {

    static final String ORDER_PATH = "/uapi/domestic-stock/v1/trading/order-cash";
    static final String CANCEL_PATH = "/uapi/domestic-stock/v1/trading/order-rvsecncl";
    static final String TR_BUY = "TTTC0012U";
    static final String TR_SELL = "TTTC0011U";
    static final String TR_CANCEL = "TTTC0013U";
    static final String EXCHANGE = "KRX";

    /** 국내 종목코드 6자리(ETN 7자리), 영문 대문자·숫자. */
    private static final Pattern KRX_CODE = Pattern.compile("[0-9A-Z]{6,7}");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private final KisSession session;
    private final KisAccountNumber account;
    private final ObjectMapper mapper = new ObjectMapper();

    public KisOrderClient(KisSession session, KisAccountNumber account) {
        this.session = Objects.requireNonNull(session, "session");
        this.account = Objects.requireNonNull(account, "account");
    }

    /** 새 주문을 보낸다. 요청 형식이 틀리면(시장, 종목코드, 원 단위가 아닌 가격) 보내기 전에 {@link IllegalArgumentException}. */
    public synchronized KisOrderResult submit(OrderRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", account.cano());
        body.put("ACNT_PRDT_CD", account.productCode());
        body.put("PDNO", krxCode(request));
        body.put("ORD_DVSN", request.type() == OrderType.LIMIT ? "00" : "01");
        body.put("ORD_QTY", Long.toString(request.quantity()));
        body.put("ORD_UNPR", request.type() == OrderType.LIMIT ? won(request.limitPrice()) : "0");
        body.put("EXCG_ID_DVSN_CD", EXCHANGE);
        body.put("SLL_TYPE", request.side() == Side.SELL ? "01" : "");
        body.put("CNDT_PRIC", "");
        return send(ORDER_PATH, request.side() == Side.BUY ? TR_BUY : TR_SELL, body);
    }

    /**
     * 주문 잔량 전부를 취소한다. 취소가 받아들여져도 그 사이 체결이 날 수 있으니 결과는 일별 체결 조회의
     * 취소확인수량·잔여수량으로 확인한다. 같은 주문 취소를 두 번 보내면 두 번째는 한국투자가 거절한다 `[추론]`.
     */
    public synchronized KisOrderResult cancel(KisOrderNo original) {
        Objects.requireNonNull(original, "original");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", account.cano());
        body.put("ACNT_PRDT_CD", account.productCode());
        body.put("KRX_FWDG_ORD_ORGNO", original.orgNo());
        body.put("ORGN_ODNO", original.odno());
        body.put("ORD_DVSN", "00");
        body.put("RVSE_CNCL_DVSN_CD", "02");
        body.put("ORD_QTY", "0");
        body.put("ORD_UNPR", "0");
        body.put("QTY_ALL_ORD_YN", "Y");
        body.put("EXCG_ID_DVSN_CD", EXCHANGE);
        return send(CANCEL_PATH, TR_CANCEL, body);
    }

    static String krxCode(OrderRequest request) {
        if (request.symbol().market() != Market.KRX) {
            throw new IllegalArgumentException("한국투자 국내 주문 클라이언트는 KRX 종목만 보냅니다: " + request.symbol());
        }
        String code = request.symbol().code();
        if (!KRX_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("국내 종목코드 형식이 아닙니다: " + code);
        }
        return code;
    }

    /** 원 단위 정수 가격. 소수가 남으면 보내지 않는다(호가 단위 맞춤은 호출자, MarketRules). */
    static String won(BigDecimal price) {
        BigDecimal p = price.stripTrailingZeros();
        if (p.signum() <= 0 || p.scale() > 0) {
            throw new IllegalArgumentException("국내 주문 가격은 0보다 큰 원 단위 정수여야 합니다: " + price.toPlainString());
        }
        return p.toPlainString();
    }

    private KisOrderResult send(String path, String trId, Map<String, String> body) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 본문을 만들 수 없습니다");
        }
        String token;
        try {
            token = session.token();
        } catch (IOException e) {
            return new KisOrderResult.NotAccepted(KisOrderResult.Reason.NOT_SENT, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new KisOrderResult.NotAccepted(KisOrderResult.Reason.NOT_SENT, "");
        }
        Stamp[] started = {null};
        HttpPort.Response r;
        try {
            r = session.post(path, trId, json, token, at -> started[0] = at);
        } catch (IOException e) {
            if (started[0] == null) {
                // 간격 대기 중에 실패해 보내지 않았다
                return new KisOrderResult.NotAccepted(KisOrderResult.Reason.NOT_SENT, "");
            }
            // 시간 초과, 연결 끊김. 요청이 한국투자에 닿았는지 모른다. 원인 메시지에는 주소가 실릴 수 있어 종류만 남긴다
            return unknown(trId + " 응답 없음(" + e.getClass().getSimpleName() + ")", started[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (started[0] == null) {
                return new KisOrderResult.NotAccepted(KisOrderResult.Reason.NOT_SENT, "");
            }
            return unknown(trId + " 응답 기다리다 중단됨", started[0]);
        }
        return interpret(trId, r, token, started[0]);
    }

    private KisOrderResult.Unknown unknown(String reason, Stamp startedAt) {
        return new KisOrderResult.Unknown(reason, startedAt, session.stamp());
    }

    KisOrderResult interpret(String trId, HttpPort.Response r, String token, Stamp startedAt) {
        JsonNode n = KisTokenClient.readOrNull(mapper, r.body());
        if (n == null || n.path("rt_cd").asText("").isBlank()) {
            return unknown(trId + " 결과 코드 없는 응답(HTTP " + r.status() + ")", startedAt);
        }
        KisErrorCode code = KisErrorCode.of(n);
        if (!code.ok()) {
            return switch (code.kind()) {
                case TOKEN_EXPIRED -> {
                    session.invalidateToken(token);
                    yield new KisOrderResult.NotAccepted(KisOrderResult.Reason.TOKEN_EXPIRED, code.code());
                }
                case RATE_LIMITED -> new KisOrderResult.NotAccepted(KisOrderResult.Reason.RATE_LIMITED, code.code());
                // 그 밖의 오류 코드는 HTTP 200일 때만 원장 거절로 확정한다. 5xx 등에 실린 게이트웨이 오류는 원장에 닿은 뒤일 수
                // 있어 모름이다(risk-ops PR #247 R3)
                default -> r.status() == 200
                        ? new KisOrderResult.NotAccepted(KisOrderResult.Reason.BROKER_REJECTED, code.code())
                        : unknown(trId + " 오류 코드 " + code.code() + "(HTTP " + r.status() + ")", startedAt);
            };
        }
        if (r.status() != 200) {
            return unknown(trId + " 성공 코드인데 HTTP " + r.status(), startedAt);
        }
        JsonNode out = n.path("output");
        String odno = field(out, "ODNO");
        String org = field(out, "KRX_FWDG_ORD_ORGNO");
        KisOrderNo no;
        try {
            no = new KisOrderNo(org, odno);
        } catch (IllegalArgumentException e) {
            return unknown(trId + " 접수 응답에 주문번호 없음", startedAt);
        }
        return new KisOrderResult.Accepted(no, time(field(out, "ORD_TMD")));
    }

    /** 응답 필드 이름의 대소문자가 예제마다 달라(order_cash 대문자, order_rvsecncl 소문자) 둘 다 본다. */
    static String field(JsonNode out, String upper) {
        JsonNode v = out.get(upper);
        if (v == null) {
            v = out.get(upper.toLowerCase(java.util.Locale.ROOT));
        }
        return v == null ? "" : v.asText("").strip();
    }

    static LocalTime time(String hhmmss) {
        try {
            return hhmmss.length() == 6 ? LocalTime.parse(hhmmss, HHMMSS) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
