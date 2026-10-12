package com.autotrade.selection.result;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 선정 결과 내용 해시용 정규 JSON(ADR-007 2.6 "내용 해시"): 키 사전순 정렬, 공백 없음, UTF-8. 값은 {@code null},
 * {@link Boolean}, {@link Integer}·{@link Long}(정수만), {@link String}, {@link Map}(키 문자열), {@link List}뿐이다. 수치
 * 값은 문자열로 넘긴다(실수 표현 차이로 해시가 흔들리지 않게). 결과 파일 코덱(후속 PR)이 같은 규칙을 쓴다.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Boolean b -> sb.append(b);
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case String s -> string(sb, s);
            case Map<?, ?> m -> {
                SortedMap<String, Object> sorted = new TreeMap<>();
                m.forEach((k, x) -> sorted.put((String) k, x));
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, e.getKey());
                    sb.append(':');
                    append(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    append(sb, l.get(i));
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("정규 JSON에 쓸 수 없는 값: " + v.getClass().getName());
        }
    }

    /** RFC 8259 문자열. 따옴표·역슬래시·제어 문자만 이스케이프하고 나머지는 그대로 둔다. */
    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
