package com.autotrade.app.collector;

import com.autotrade.config.Secret;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 한국투자 국내주식 종합 시황/공시(제목) 조회 호출기(D-136, Q-055). <b>이 클래스가 부르는 주소는 두 개뿐이다</b>: 접근토큰 발급
 * {@code POST /oauth2/tokenP}와 뉴스 제목 조회 {@code GET /uapi/domestic-stock/v1/quotations/news-title}(TR
 * {@value #TR_ID}). 경로·TR을 인자로 받지 않으므로 주문·잔고·계좌 조회를 부를 수 없다. 계좌번호도 받지 않는다.
 *
 * <p>`[사실]` 경로·TR·입력 칸 8개·출력 칸({@code cntt_usiq_srno}, {@code news_ofer_entp_code}, {@code data_dt},
 * {@code data_tm}, {@code hts_pbnt_titl_cntt}, {@code news_lrdv_code}, {@code dorg}, {@code iscd1}~): 한국투자 공식 GitHub
 * {@code koreainvestment/open-trading-api} {@code examples_llm/domestic_stock/news_title}(2026-10-21 조회). 헤더 규칙은
 * {@code docs/research/kis-open-api-spec.md} 3장(기존 조회 호출기와 같음).
 * <br>`[가정]` 날짜 {@code YYYYMMDD}·시각 {@code HHMMSS} 입력, 빈 시각 = 지금, 응답 배열 {@code output}, 한 쪽 약 40건, 커서 행이
 * 다음 쪽 첫머리에 다시 나옴: 2차 자료(dragon1086/prism-insight {@code cores/market_data/kis_source.py}·
 * {@code tools/collect_kr_news_titles.py}, 2026-10-21 조회). 첫 실행 품질 리포트로 확인한다(runbook collector.md 3.9).
 *
 * <p>앱키·시크릿·토큰은 헤더로만 나가고 로그·예외 메시지에는 {@link Redaction}을 거친 URL만 남는다.
 */
public final class KisNewsClient {

    public static final String DEFAULT_BASE = "https://openapi.koreainvestment.com:9443";
    static final String TOKEN_PATH = "/oauth2/tokenP";
    static final String NEWS_PATH = "/uapi/domestic-stock/v1/quotations/news-title";
    static final String TR_ID = "FHKST01011800";
    /** 응답의 종목코드 칸 {@code iscd1}~{@code iscd10}. 공식 예제는 5개까지 적고 2차 자료는 10개까지 읽는다. 없는 칸은 건너뛴다. */
    static final int MAX_TAGS = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 제목 한 줄. {@code time}은 6자리 {@code HHMMSS}로 맞춘 값, {@code codes}는 빈 값을 뺀 종목코드(응답 순서). */
    public record Headline(String serial, String date, String time, String providerCode, String provider, String category,
            List<String> codes, String title) {
        public Headline {
            codes = List.copyOf(codes);
        }
    }

    /** 한국투자가 결과 코드로 거절했다. 메시지에는 결과 코드만(응답 본문은 넣지 않는다). */
    public static final class KisRejected extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;

        KisRejected(String code) {
            super("한국투자 " + TR_ID + " 거절: " + code
                    + ("EGW00201".equals(code) ? " (초당 호출 한도 초과, --interval-ms를 늘려 다시 실행)" : ""));
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final ApiCaller caller;
    private final String base;
    private final Secret appKey;
    private final Secret appSecret;
    private final Redaction redaction;
    private Secret token = Secret.empty();
    private int tokensIssued;
    private int badRows;

    public KisNewsClient(ApiCaller caller, String base, Secret appKey, Secret appSecret, Redaction redaction) {
        if (!appKey.isPresent() || !appSecret.isPresent()) {
            throw new IllegalStateException("한국투자 뉴스 조회 키가 없습니다(collector-secrets.json kis-news.app-key·app-secret)");
        }
        if (!base.startsWith("https://")) {
            throw new IllegalArgumentException("한국투자 주소는 https://로 시작해야 합니다");
        }
        this.caller = caller;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.redaction = redaction;
    }

    /**
     * 커서({@code date}·{@code time}) 시점부터 거슬러 올라가는 제목 한 쪽(시장 전체, 종목코드 빈 값). 토큰 만료 응답이면 토큰을 한 번만
     * 다시 받아 다시 부른다.
     *
     * @param date {@code YYYYMMDD}
     * @param time {@code HHMMSS}, 빈 값이면 지금
     */
    public List<Headline> page(String date, String time) throws IOException {
        if (!date.matches("\\d{8}") || !(time.isEmpty() || time.matches("\\d{6}"))) {
            throw new IllegalArgumentException("커서 형식이 틀렸습니다: " + date + " " + time);
        }
        Map<String, String> params = new TreeMap<>();
        params.put("FID_NEWS_OFER_ENTP_CODE", "");
        params.put("FID_COND_MRKT_CLS_CODE", "");
        params.put("FID_INPUT_ISCD", "");
        params.put("FID_TITL_CNTT", "");
        params.put("FID_INPUT_DATE_1", date);
        params.put("FID_INPUT_HOUR_1", time);
        params.put("FID_RANK_SORT_CLS_CODE", "");
        params.put("FID_INPUT_SRNO", "");
        StringBuilder q = new StringBuilder();
        params.forEach((k, v) -> q.append(q.isEmpty() ? '?' : '&').append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        URI uri = URI.create(base + NEWS_PATH + q);
        JsonNode body = getNews(uri);
        String code = resultCode(body);
        if (isTokenExpired(body)) {
            token = Secret.empty();
            body = getNews(uri);
            code = resultCode(body);
        }
        if (!"0".equals(body.path("rt_cd").asText(""))) {
            throw new KisRejected(code.isEmpty() ? "(결과 코드 없음)" : code);
        }
        List<Headline> out = new ArrayList<>();
        for (JsonNode row : body.path("output")) {
            Headline h = headline(row);
            if (h != null) {
                out.add(h);
            } else {
                badRows++;
            }
        }
        return out;
    }

    /** 이번 실행에서 필수 칸이 빠졌거나 날짜·시각을 읽을 수 없어 거른 행 수(품질 리포트에 남긴다, PR #222 QA Q2). */
    public int badRows() {
        return badRows;
    }

    /** 이번 실행에서 받은 접근토큰 수(발급마다 사용자에게 알림톡이 간다). */
    public int tokensIssued() {
        return tokensIssued;
    }

    /**
     * 필수 칸(일련번호, 날짜 {@code YYYYMMDD}, 시각 {@code HHMMSS}, 제목)이 빠졌거나 날짜·시각이 실제로 있는 값이 아니면 null(거름).
     * 자릿수가 모자란 시각은 채우지 않는다: 앞 0이 빠진 것인지 뒤가 잘린 것인지 알 수 없고, 잘못 채우면 없는 시각이 되어 실행이 매번
     * 같은 곳에서 멈춘다(PR #222 QA Q2). 거른 행 수는 {@link #badRows()}로 리포트에 남는다.
     */
    static Headline headline(JsonNode row) {
        String serial = row.path("cntt_usiq_srno").asText("").strip();
        String date = row.path("data_dt").asText("").strip();
        String time = row.path("data_tm").asText("").strip();
        String title = row.path("hts_pbnt_titl_cntt").asText("").strip();
        if (serial.isEmpty() || !date.matches("\\d{8}") || !time.matches("\\d{6}") || title.isEmpty()) {
            return null;
        }
        try {
            LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE); // 엄격 해석(BASIC_ISO_DATE는 STRICT)
            LocalTime.parse(time, KisNewsCollector.HMS); // 엄격 해석: "240000"도 거른다
        } catch (DateTimeParseException e) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= MAX_TAGS; i++) {
            String c = row.path("iscd" + i).asText("").strip();
            if (!c.isEmpty() && !codes.contains(c)) {
                codes.add(c);
            }
        }
        return new Headline(serial, date, time, row.path("news_ofer_entp_code").asText("").strip(),
                row.path("dorg").asText("").strip(), row.path("news_lrdv_code").asText("").strip(), codes, title);
    }

    private JsonNode getNews(URI uri) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json; charset=utf-8");
        headers.put("authorization", "Bearer " + token().reveal());
        headers.put("appkey", appKey.reveal());
        headers.put("appsecret", appSecret.reveal());
        headers.put("tr_id", TR_ID);
        headers.put("custtype", "P");
        HttpGetter.Response r = caller.getAnswered(uri, headers, KisNewsClient::answered);
        JsonNode body = read(r.body());
        if (r.status() != 200 && "0".equals(body.path("rt_cd").asText(""))) {
            throw new KisRejected("(HTTP " + r.status() + ")"); // 성공 코드인데 상태가 200이 아니면 믿지 않는다
        }
        return body;
    }

    /** 본문에 결과 코드({@code rt_cd})가 있는 응답이면 한국투자가 답한 것으로 보고 재시도하지 않는다. 읽을 수 없으면 거짓. */
    static boolean answered(HttpGetter.Response r) {
        try {
            JsonNode n = JSON.readTree(r.body() == null ? "" : r.body());
            return n != null && n.isObject() && !n.path("rt_cd").asText("").isBlank();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Secret token() throws IOException {
        if (token.isPresent()) {
            return token;
        }
        String body = JSON.createObjectNode().put("grant_type", "client_credentials").put("appkey", appKey.reveal())
                .put("appsecret", appSecret.reveal()).toString();
        String answer;
        try {
            answer = caller.post(URI.create(base + TOKEN_PATH), Map.of("content-type", "application/json; charset=utf-8"),
                    body);
        } catch (IOException e) {
            throw new IOException("한국투자 접근토큰 발급 실패(1분에 1번만 발급되므로 1분 뒤 다시 실행): "
                    + redaction.clean(e.getMessage()));
        }
        String t = read(answer).path("access_token").asText("");
        if (t.isBlank()) {
            throw new IOException("한국투자 접근토큰 응답에 access_token이 없습니다");
        }
        tokensIssued++;
        token = Secret.of(t);
        redaction.add(token);
        return token;
    }

    private static JsonNode read(String body) throws IOException {
        try {
            JsonNode n = JSON.readTree(body);
            if (n == null || !n.isObject()) {
                throw new IOException("한국투자 응답이 JSON 객체가 아닙니다");
            }
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 파서 메시지에 본문 일부가 실릴 수 있어 옮기지 않는다
            throw new IOException("한국투자 응답을 JSON으로 읽을 수 없습니다");
        }
    }

    /** 결과 코드. 코드 형식(영숫자 20자 이하)이 아니면 옮기지 않는다(kis-open-api-spec.md 5장, msg1 원문은 쓰지 않음). */
    static String resultCode(JsonNode body) {
        String c = body.path("msg_cd").asText("").strip();
        return c.matches("[A-Za-z0-9]{1,20}") ? c : "";
    }

    /** 토큰 만료·무효·없음(EGW00123 `[사실]`, EGW00121·EGW00122 `[가정]`, kis-open-api-spec.md 5장). */
    private static boolean isTokenExpired(JsonNode body) {
        String code = resultCode(body);
        return !"0".equals(body.path("rt_cd").asText(""))
                && (code.equals("EGW00123") || code.equals("EGW00121") || code.equals("EGW00122"));
    }
}
