package com.autotrade.broker.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 키움 REST·WebSocket 응답의 {@code return_code} 해석.
 *
 * <p>`[사실]` 근거: 사용자 제공 키움 REST API 명세 JSON {@code errorCodeList}(2026-10-10 수신, 요약은
 * docs/research/kiwoom-rest-api-spec.md 3장), 키움 공식 저장소 {@code kiwoom/core/errors.py}(2026-10-10 확인).
 * <ul>
 *   <li>값은 숫자 또는 숫자 문자열("0", "8005")로 온다. 실시간 등록 응답은 문자열이다(0B 명세 return_code String).</li>
 *   <li>최상위 코드가 일반 코드(예: 3)이고 구체 코드가 메시지 안에 {@code [8005:…]}(REST) 또는 {@code CODE=8005}(WebSocket)로
 *       들어 있는 경우가 있다.</li>
 * </ul>
 * 결과 코드 숫자와 고정 문구만 다루고, 응답 본문은 토큰이 섞일 수 있어 그대로 옮기지 않는다.
 */
final class KiwoomReturnCode {

    /** 처리 방식 분류. */
    enum Kind {
        OK,
        /** 키·시크릿·IP·투자구분 문제. 같은 키로 다시 시도해도 풀리지 않는다(사용자 조치 필요). */
        CREDENTIALS,
        /** 토큰 만료·무효. 토큰을 새로 받아 한 번 더 시도한다. */
        TOKEN_EXPIRED,
        /** 호출 한도 초과(1700 API별, 1701 전체, 1702 그룹). 쉬었다가 다시 시도한다. */
        RATE_LIMITED,
        /** 그 밖의 오류(입력값, 종목 없음, 서버 오류). */
        OTHER
    }

    /** `[사실]` 명세 errorCodeList 8001·8002·8010·8011·8012·8020·8030·8040·8050. */
    private static final Set<Integer> CREDENTIAL_CODES = Set.of(8001, 8002, 8010, 8011, 8012, 8020, 8030, 8040, 8050);
    /** `[사실]` 공식 저장소 AUTH_RETRY_RETURN_CODES = {8005, 8031, 8103}. */
    private static final Set<Integer> TOKEN_CODES = Set.of(8005, 8031, 8103);
    private static final Set<Integer> RATE_CODES = Set.of(1700, 1701, 1702);
    private static final Pattern EMBEDDED = Pattern.compile("\\[(\\d{3,5}):|CODE=(\\d{3,5})");

    private final int code;
    private final int specific;

    private KiwoomReturnCode(int code, int specific) {
        this.code = code;
        this.specific = specific;
    }

    /** 응답 JSON에서 읽는다. {@code return_code}가 없거나 숫자가 아니면 0(정상)으로 본다. */
    static KiwoomReturnCode of(JsonNode response) {
        int top = parse(response.path("return_code")).orElse(0);
        if (top == 0) {
            return new KiwoomReturnCode(0, 0);
        }
        int embedded = embedded(response.path("return_msg").asText("")).orElse(top);
        return new KiwoomReturnCode(top, embedded);
    }

    static OptionalInt parse(JsonNode v) {
        if (v.isIntegralNumber()) {
            return OptionalInt.of(v.asInt());
        }
        if (v.isTextual()) {
            String s = v.asText().strip();
            if (s.matches("-?\\d{1,9}")) {
                return OptionalInt.of(Integer.parseInt(s));
            }
        }
        return OptionalInt.empty();
    }

    static OptionalInt embedded(String msg) {
        Matcher m = EMBEDDED.matcher(msg);
        if (!m.find()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
    }

    boolean ok() {
        return code == 0;
    }

    /** 구체 코드(메시지에 들어 있으면 그 값, 없으면 최상위 코드). */
    int specific() {
        return specific;
    }

    Kind kind() {
        if (code == 0) {
            return Kind.OK;
        }
        if (TOKEN_CODES.contains(specific)) {
            return Kind.TOKEN_EXPIRED;
        }
        if (CREDENTIAL_CODES.contains(specific)) {
            return Kind.CREDENTIALS;
        }
        if (RATE_CODES.contains(specific)) {
            return Kind.RATE_LIMITED;
        }
        return Kind.OTHER;
    }

    /** 로그·예외에 넣는 설명. 코드와 고정 안내 문구만 담는다(응답 메시지 원문은 넣지 않는다). */
    String describe() {
        String hint = switch (specific) {
            case 8050 -> " IP가 등록되지 않음. 키움 REST API 홈페이지 > API 사용신청에서 이 PC의 공인 IP를 등록";
            case 8010 -> " 토큰을 발급받은 IP와 요청 IP가 다름";
            case 8030, 8031 -> " 실전/모의 투자구분이 다른 키·토큰. 실전 키를 넣었는지 확인(모의 키는 쓰지 않음, D-003)";
            case 8001, 8002 -> " App Key·Secret Key 검증 실패";
            case 8005 -> " 토큰이 유효하지 않음(만료·폐기)";
            case 1700, 1701, 1702 -> " 호출 한도 초과";
            case 1902, 1903 -> " 종목 정보 없음";
            default -> "";
        };
        String top = code == specific ? "" : " (최상위 " + code + ")";
        return "return_code " + specific + top + hint;
    }

    @Override
    public String toString() {
        return describe();
    }
}
