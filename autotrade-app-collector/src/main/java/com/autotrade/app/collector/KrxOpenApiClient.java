package com.autotrade.app.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * KRX Open API 일별매매정보(D-050 ①). 요청 {@code GET <base>/svc/apis/sto/<서비스>?basDd=yyyyMMdd}, 헤더 {@code AUTH_KEY},
 * 응답 {@code {"OutBlock_1":[{...}]}}. 휴장일은 빈 목록이다.
 *
 * <p>[가정] 기본 주소 {@code https://data-dbg.krx.co.kr}, 헤더 이름, 필드 이름(BAS_DD, ISU_CD, ISU_NM, MKT_NM, SECT_TP_NM,
 * TDD_OPNPRC, TDD_HGPRC, TDD_LWPRC, TDD_CLSPRC, CMPPREVDD_PRC, ACC_TRDVOL, LIST_SHRS)은 공개 클라이언트 예제 기준이고 이
 * 환경에서 원문을 받지 못했다. 키 발급 뒤 첫 호출로 확인한다(runbook). 응답 모양이 다르면 조용히 넘기지 않고 예외로 멈춘다.
 */
public final class KrxOpenApiClient {

    public static final String DEFAULT_BASE = "https://data-dbg.krx.co.kr";
    static final DateTimeFormatter BAS_DD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiCaller caller;
    private final String base;
    private final String authKey;

    public KrxOpenApiClient(ApiCaller caller, String base, String authKey) {
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalStateException("KRX Open API 인증키가 없습니다(collector-secrets.json krx.auth-key)");
        }
        this.caller = caller;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.authKey = authKey;
    }

    /** 원본 응답 본문. 캐시에 그대로 둔다. */
    public String fetchRaw(KrxBoard board, LocalDate date) throws IOException {
        URI uri = URI.create(base + "/svc/apis/sto/" + board.service + "?basDd=" + BAS_DD.format(date));
        return caller.get(uri, Map.of("AUTH_KEY", authKey));
    }

    /** 응답 본문을 행으로. 응답 날짜(BAS_DD)가 요청 날짜와 다르면 예외. */
    public static List<KrxDailyRow> parse(KrxBoard board, LocalDate date, String body) throws IOException {
        JsonNode root = JSON.readTree(body);
        JsonNode block = root == null ? null : root.get("OutBlock_1");
        if (block == null || !block.isArray()) {
            throw new IOException("KRX 응답에 OutBlock_1 목록이 없습니다: " + board + " " + date);
        }
        List<KrxDailyRow> out = new ArrayList<>(block.size());
        for (JsonNode n : block) {
            String basDd = text(n, "BAS_DD");
            if (!basDd.isEmpty() && !LocalDate.parse(basDd, BAS_DD).equals(date)) {
                throw new IOException("KRX 응답 날짜가 요청과 다릅니다: " + basDd + " != " + date);
            }
            out.add(new KrxDailyRow(date, board, shortCode(text(n, "ISU_CD")), text(n, "ISU_NM"), text(n, "SECT_TP_NM"),
                    decimal(n, "TDD_OPNPRC"), decimal(n, "TDD_HGPRC"), decimal(n, "TDD_LWPRC"), decimal(n, "TDD_CLSPRC"),
                    decimal(n, "CMPPREVDD_PRC"), whole(n, "ACC_TRDVOL"), whole(n, "LIST_SHRS")));
        }
        return out;
    }

    /** 6자리 단축코드. 표준코드(KR7005930003 형식)가 오면 4~9번째 자리를 쓴다. */
    static String shortCode(String isuCd) throws IOException {
        if (isuCd.matches("[0-9A-Z]{6}")) {
            return isuCd;
        }
        if (isuCd.matches("KR[0-9A-Z]{10}")) {
            return isuCd.substring(3, 9);
        }
        throw new IOException("종목 코드 형식을 모릅니다: " + isuCd);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText().strip();
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        String s = text(n, field).replace(",", "");
        return s.isEmpty() || s.equals("-") ? null : new BigDecimal(s);
    }

    private static long whole(JsonNode n, String field) {
        BigDecimal d = decimal(n, field);
        return d == null ? 0 : d.longValueExact();
    }
}
