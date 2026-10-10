package com.autotrade.app.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * OpenDART 공시검색({@code list.json})과 단일회사 주요계정({@code fnlttSinglAcnt.json}) 호출(MD-11, D-050 ③).
 * [사실] 요청·응답 항목과 상태 코드는 OpenDART 개발가이드(DS001 2019001, DS003 2019016, 2026-10-10 확인): 회사 코드 없이
 * 공시검색하면 기간은 3개월까지, 한 쪽 최대 100건, 020은 요청 제한 초과(일반적으로 20,000건 이상).
 *
 * <p>인증키는 쿼리 {@code crtfc_key}로 가므로 URL은 {@link Redaction}을 거쳐서만 남긴다.
 */
public final class OpenDartClient {

    public static final String BASE = "https://opendart.fss.or.kr/api";
    public static final String ANNUAL_REPORT = "11011";
    static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiCaller caller;
    private final CallBudget budget;
    private final String base;
    private final String key;

    public OpenDartClient(ApiCaller caller, CallBudget budget, String base, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("OpenDART 인증키가 없습니다(collector-secrets.json opendart.crtfc-key)");
        }
        this.caller = caller;
        this.budget = budget;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
    }

    /** 사업보고서(A001) 공시검색 한 쪽. 정정 공시 포함({@code last_reprt_at=N}), 접수일 오름차순. */
    public JsonNode annualReportList(LocalDate bgn, LocalDate end, int page) throws IOException {
        return call("list.json", Map.of("bgn_de", YMD.format(bgn), "end_de", YMD.format(end), "last_reprt_at", "N",
                "pblntf_ty", "A", "pblntf_detail_ty", "A001", "sort", "date", "sort_mth", "asc",
                "page_no", Integer.toString(page), "page_count", "100"));
    }

    /** 단일회사 주요계정(사업보고서). 응답 원문 그대로. */
    public JsonNode accounts(String corpCode, int businessYear) throws IOException {
        return call("fnlttSinglAcnt.json", Map.of("corp_code", corpCode, "bsns_year", Integer.toString(businessYear),
                "reprt_code", ANNUAL_REPORT));
    }

    private JsonNode call(String path, Map<String, String> params) throws IOException {
        StringBuilder q = new StringBuilder("crtfc_key=").append(enc(key));
        params.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> q.append('&').append(e.getKey()).append('=').append(enc(e.getValue())));
        JsonNode body = JSON.readTree(caller.get(URI.create(base + "/" + path + "?" + q), Map.of()));
        String status = body == null ? "" : body.path("status").asText("");
        switch (status) {
            case "000", "013" -> {
                return body;
            }
            case "020" -> {
                budget.stopForToday();
                throw new CallBudget.BudgetExhausted("OpenDART 요청 제한 초과(020): 오늘 수집을 멈춥니다");
            }
            case "010", "011", "012", "901" ->
                throw new IllegalStateException("OpenDART 인증키를 쓸 수 없습니다(상태 " + status + ")");
            default -> throw new IOException("OpenDART 오류 상태 " + status + " (" + path + ")");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
