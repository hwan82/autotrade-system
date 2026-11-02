package com.autotrade.realstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * 원장 한 줄의 형식(ADR-015 2.4). 칸 순서는 {@code v, stream, rseq, boot, at, type, body, crc}로 고정한다.
 *
 * <p>{@code crc}는 "{@code crc} 칸을 뺀 나머지를 위 순서로 직렬화한 UTF-8 바이트"의 CRC32C(8자리 소문자 16진수)다. 쓸 때는
 * {@code crc} 없는 객체를 직렬화해 그 바이트로 CRC를 계산하고, 끝의 닫는 중괄호 앞에 crc 칸을 붙인다. 읽을 때는 줄 끝의
 * 고정 길이 접미사를 떼어 같은 바이트를 되살려 대조한 뒤에야 JSON으로 읽는다. 다시 직렬화해 비교하지 않으므로 숫자·문자열 표기
 * 차이로 거짓 손상이 나지 않는다.
 *
 * <p>이름으로 타입을 고르는 바인딩은 쓰지 않는다(D-086). 트리({@link JsonNode})로만 읽고 쓴다.
 */
final class LedgerCodec {

    /** 형식 버전. 모르는 버전은 손상으로 본다(2.4). */
    static final int VERSION = 1;

    private static final String CRC_PREFIX = ",\"crc\":\"";
    /** 줄 끝 crc 접미사(쉼표, crc 이름, 8자리, 따옴표, 닫는 중괄호)의 길이. */
    private static final int SUFFIX_LENGTH = CRC_PREFIX.length() + 8 + 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private LedgerCodec() {}

    static ObjectNode body() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** 기록 하나를 줄바꿈 없는 한 줄로 만든다. */
    static String encode(String stream, long rseq, long boot, Instant at, String type, ObjectNode body) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("v", VERSION);
        node.put("stream", stream);
        node.put("rseq", rseq);
        node.put("boot", boot);
        node.put("at", at.toString());
        node.put("type", type);
        node.set("body", body == null ? body() : body);
        String withoutCrc;
        try {
            withoutCrc = MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("원장 기록을 직렬화하지 못했습니다(" + stream + " " + type + ")", e);
        }
        return withoutCrc.substring(0, withoutCrc.length() - 1) + CRC_PREFIX + crc(withoutCrc) + "\"}";
    }

    /** 한 줄(줄바꿈 제외)을 읽는다. CRC·형식·버전이 맞지 않으면 {@link Corrupt}. */
    static LedgerEntry decode(String line) throws Corrupt {
        Objects.requireNonNull(line, "line");
        if (line.length() <= SUFFIX_LENGTH || !line.endsWith("\"}")) {
            throw new Corrupt("줄 형식이 아님(crc 칸 없음)");
        }
        int prefixAt = line.length() - SUFFIX_LENGTH;
        if (!line.startsWith(CRC_PREFIX, prefixAt)) {
            throw new Corrupt("줄 형식이 아님(crc 칸 위치)");
        }
        String crc = line.substring(prefixAt + CRC_PREFIX.length(), line.length() - 2);
        String withoutCrc = line.substring(0, prefixAt) + "}";
        if (!crc.equals(crc(withoutCrc))) {
            throw new Corrupt("CRC 불일치");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(withoutCrc);
        } catch (JsonProcessingException e) {
            throw new Corrupt("JSON이 아님");
        }
        if (node == null || !node.isObject()) {
            throw new Corrupt("JSON 객체가 아님");
        }
        JsonNode v = node.get("v");
        if (v == null || !v.isInt() || v.intValue() != VERSION) {
            throw new Corrupt("모르는 형식 버전 " + (v == null ? "없음" : v.toString()));
        }
        JsonNode stream = node.get("stream");
        JsonNode rseq = node.get("rseq");
        JsonNode boot = node.get("boot");
        JsonNode at = node.get("at");
        JsonNode type = node.get("type");
        JsonNode body = node.get("body");
        if (stream == null || !stream.isTextual() || rseq == null || !rseq.canConvertToLong() || !rseq.isIntegralNumber()
                || boot == null || !boot.isIntegralNumber() || at == null || !at.isTextual() || type == null
                || !type.isTextual() || body == null || !body.isObject()) {
            throw new Corrupt("칸이 빠졌거나 형식이 다름");
        }
        Instant when;
        try {
            when = Instant.parse(at.textValue());
        } catch (DateTimeParseException e) {
            throw new Corrupt("시각 형식이 아님");
        }
        return new LedgerEntry(stream.textValue(), rseq.longValue(), boot.longValue(), when, type.textValue(), body, crc);
    }

    private static String crc(String text) {
        CRC32C crc = new CRC32C();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return HEX.toHexDigits((int) crc.getValue());
    }

    /** 줄 손상(2.4: 중간 줄 CRC 불일치, 모르는 버전 등). 메시지에 기록 내용은 싣지 않는다. */
    static final class Corrupt extends Exception {

        private static final long serialVersionUID = 1L;

        Corrupt(String reason) {
            super(reason);
        }
    }
}
