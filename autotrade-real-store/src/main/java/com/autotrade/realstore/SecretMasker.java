package com.autotrade.realstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 원장 기록 직전의 비밀값 가리기(ADR-015 2.4, 지침 4장 4번의 이중 장치). 증권사 응답 원문은 플러그인이 먼저 가리지만, 저장소는
 * 기록 직전에 엔진이 메모리에 가진 계좌번호·키 값이 문자열 칸 안에 있는지 한 번 더 보고, 있으면 그 부분을 가린다.
 */
final class SecretMasker {

    static final String MASK = "[가림]";
    /** 이보다 짧은 값은 보통 문자열과 겹쳐 가리기가 뜻이 없어 보지 않는다 {@code [가정]}. */
    static final int MIN_LENGTH = 4;

    private final Supplier<? extends Collection<String>> secrets;

    SecretMasker(Supplier<? extends Collection<String>> secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** 본문의 문자열 칸에서 비밀값을 가린다(본문을 바꾼다). 가린 칸이 있으면 true. */
    boolean mask(ObjectNode body) {
        List<String> values = new ArrayList<>();
        for (String s : secrets.get()) {
            if (s != null && s.length() >= MIN_LENGTH) {
                values.add(s);
            }
        }
        // 긴 값부터 바꾼다: 짧은 비밀값이 긴 비밀값의 앞부분이면 짧은 것을 먼저 바꿀 때 긴 값의 나머지가 남는다(PR #248 독립 QA Q6)
        values.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return !values.isEmpty() && maskObject(body, values);
    }

    private static boolean maskObject(ObjectNode node, List<String> values) {
        boolean masked = false;
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            names.add(e.getKey());
        }
        for (String name : names) {
            JsonNode child = node.get(name);
            if (child.isTextual()) {
                String replaced = replace(child.textValue(), values);
                if (replaced != null) {
                    node.put(name, replaced);
                    masked = true;
                }
            } else if (child.isObject()) {
                masked |= maskObject((ObjectNode) child, values);
            } else if (child.isArray()) {
                masked |= maskArray((ArrayNode) child, values);
            }
        }
        return masked;
    }

    private static boolean maskArray(ArrayNode node, List<String> values) {
        boolean masked = false;
        for (int i = 0; i < node.size(); i++) {
            JsonNode child = node.get(i);
            if (child.isTextual()) {
                String replaced = replace(child.textValue(), values);
                if (replaced != null) {
                    // textNode(String)는 실전 경로 허용 목록 밖 타입(TextNode)을 돌려주므로 빼고 넣는다(D-086)
                    node.remove(i);
                    node.insert(i, replaced);
                    masked = true;
                }
            } else if (child.isObject()) {
                masked |= maskObject((ObjectNode) child, values);
            } else if (child.isArray()) {
                masked |= maskArray((ArrayNode) child, values);
            }
        }
        return masked;
    }

    /** 바꿀 것이 없으면 null. */
    private static String replace(String text, List<String> values) {
        String out = text;
        for (String v : values) {
            if (out.contains(v)) {
                out = out.replace(v, MASK);
            }
        }
        return out.equals(text) ? null : out;
    }
}
