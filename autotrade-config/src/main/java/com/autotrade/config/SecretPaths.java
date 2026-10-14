package com.autotrade.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 비밀값이 들어가는 JSON 경로 목록. {@link RuntimeConfig}에서 {@link Secret} 타입 필드를 찾아 만들므로
 * 새 비밀값 키를 추가해도 목록을 따로 고칠 필요가 없다.
 *
 * <p>{@code Map<String, Secret>} 필드는 경로 끝이 {@code *}(아무 키)다. 예: {@code real-trading/account-numbers/*}.
 * 목록(List) 안에는 비밀값을 두지 않는다.
 */
final class SecretPaths {

    static final String ANY_KEY = "*";

    private static final PropertyNamingStrategies.KebabCaseStrategy KEBAB = new PropertyNamingStrategies.KebabCaseStrategy();
    private static final Set<String> PATHS = Collections.unmodifiableSet(collect(RuntimeConfig.class, ""));
    /** 설정 구조의 모든 경로(중간 노드 포함). 맵 키 자리는 {@code *}. 경고에 키 이름을 내보내도 되는지 가리는 데 쓴다. */
    private static final Set<String> SCHEMA = Collections.unmodifiableSet(schema(RuntimeConfig.class, ""));
    /** 설정 키 이름 모양(소문자와 하이픈). 숫자가 든 이름은 계좌번호일 수 있어 경고에 내보내지 않는다. */
    private static final java.util.regex.Pattern KEY_LIKE = java.util.regex.Pattern.compile("[a-z][a-z-]*");

    private SecretPaths() {}

    /** "brokers/kis/app-key" 형식. 맵 비밀값은 "real-trading/account-numbers/*". */
    static Set<String> all() {
        return PATHS;
    }

    /** 값 경로가 비밀값 경로인지. */
    static boolean isSecret(String path) {
        String[] parts = path.split("/");
        for (String pattern : PATHS) {
            String[] p = pattern.split("/");
            if (p.length != parts.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < p.length && match; i++) {
                match = p[i].equals(ANY_KEY) || p[i].equals(parts[i]);
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** 트리에서 비밀값 경로를 떼어 낸다. 원본 트리는 비밀값이 빠진 상태가 되고, 떼어 낸 값은 새 트리로 돌려준다. */
    static ObjectNode extract(ObjectNode tree) {
        ObjectNode secrets = JsonNodeFactory.instance.objectNode();
        for (String path : secretLeaves(tree)) {
            String[] parts = path.split("/");
            ObjectNode parent = navigate(tree, parts);
            String leaf = parts[parts.length - 1];
            if (parent == null || !parent.has(leaf)) {
                continue; // 키 이름에 '/'가 들어 있어 경로로 찾을 수 없다. 그런 키는 바인딩에서 모르는 키로 거부된다
            }
            JsonNode value = parent.remove(leaf);
            if (value.isTextual() && !value.asText().isBlank()) {
                ensure(secrets, parts).set(leaf, value);
            }
        }
        return secrets;
    }

    /** 트리 안의 비밀값 경로(값이 비어 있지 않은 것). runtime-config.json 검사용. */
    static List<String> presentIn(ObjectNode tree) {
        List<String> found = new ArrayList<>();
        for (String path : secretLeaves(tree)) {
            String[] parts = path.split("/");
            ObjectNode parent = navigate(tree, parts);
            JsonNode value = parent == null ? null : parent.get(parts[parts.length - 1]);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                found.add(path);
            }
        }
        return found;
    }

    /**
     * 경고에 쓸 비밀값 경로. 맵 비밀값(경로 끝 {@code *})은 사용자가 정한 키 이름 대신 패턴과 개수로 바꾼다. 별칭 자리에
     * 계좌번호를 잘못 적었을 수 있어 키 이름도 화면·로그에 내보내지 않는다(PR #100 QA R2). 순서는 처음 나온 순서.
     */
    static List<String> forWarning(List<String> secretPaths) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String path : secretPaths) {
            counts.merge(patternOf(path), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        counts.forEach((pattern, n) -> out.add(pattern.endsWith("/" + ANY_KEY) ? pattern + " " + n + "개" : pattern));
        return out;
    }

    /** 값 경로와 맞는 비밀값 패턴. 맞는 패턴이 없으면 경로 그대로. */
    private static String patternOf(String path) {
        String[] parts = path.split("/");
        for (String pattern : PATHS) {
            String[] p = pattern.split("/");
            if (p.length != parts.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < p.length && match; i++) {
                match = p[i].equals(ANY_KEY) || p[i].equals(parts[i]);
            }
            if (match) {
                return pattern;
            }
        }
        return path;
    }

    /**
     * 경고에 쓸 secrets.json의 비밀값 아닌 키 경로. 설정 구조에 있는 키 이름은 그대로 두고, 맵 키 자리와 구조에 없는 키 중
     * 키 이름 모양이 아닌 것은 {@code *}로 바꾼다. 구조에 없는 키 아래는 {@code /*}로 줄인다. {@code *}가 든 경로는
     * 개수를 붙인다. 위치가 틀린 키 이름 자리에 계좌번호를 적었을 수 있어서다(PR #100 QA R4). 순서는 처음 나온 순서.
     * 예: {@code real-trading/acount-numbers/99990000-01} → {@code real-trading/acount-numbers/* 1개}.
     */
    static List<String> forNonSecretWarning(List<String> paths) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String path : paths) {
            counts.merge(maskedPath(path), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        counts.forEach((masked, n) -> out.add(masked.contains(ANY_KEY) ? masked + " " + n + "개" : masked));
        return out;
    }

    private static String maskedPath(String path) {
        String[] parts = path.split("/", -1);
        List<String> shown = new ArrayList<>();
        String schemaPrefix = "";
        for (int i = 0; i < parts.length; i++) {
            String literal = schemaPrefix + parts[i];
            String any = schemaPrefix + ANY_KEY;
            if (SCHEMA.contains(literal)) {
                shown.add(parts[i]);
                schemaPrefix = literal + "/";
            } else if (SCHEMA.contains(any)) {
                shown.add(ANY_KEY);
                schemaPrefix = any + "/";
            } else {
                // 구조에 없는 키: 오타로 보이는 키 이름만 보이고 그 아래는 줄인다
                shown.add(KEY_LIKE.matcher(parts[i]).matches() ? parts[i] : ANY_KEY);
                if (i < parts.length - 1) {
                    shown.add(ANY_KEY);
                }
                break;
            }
        }
        return String.join("/", shown);
    }

    /** 비밀값 경로가 아닌 값의 경로. secrets.json 검사용. */
    static List<String> nonSecretLeaves(JsonNode tree) {
        List<String> leaves = new ArrayList<>();
        collectLeaves(tree, "", leaves);
        leaves.removeIf(SecretPaths::isSecret);
        return leaves;
    }

    private static List<String> secretLeaves(JsonNode tree) {
        List<String> leaves = new ArrayList<>();
        collectLeaves(tree, "", leaves);
        leaves.removeIf(path -> !isSecret(path));
        return leaves;
    }

    private static void collectLeaves(JsonNode node, String prefix, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                collectLeaves(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "/" + e.getKey(), out);
            }
        } else {
            out.add(prefix);
        }
    }

    private static ObjectNode navigate(ObjectNode root, String[] parts) {
        JsonNode node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            node = node.get(parts[i]);
            if (!(node instanceof ObjectNode)) {
                return null;
            }
        }
        return (ObjectNode) node;
    }

    private static ObjectNode ensure(ObjectNode root, String[] parts) {
        ObjectNode node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            node = node.has(parts[i]) ? (ObjectNode) node.get(parts[i]) : node.putObject(parts[i]);
        }
        return node;
    }

    private static Set<String> schema(Class<?> type, String prefix) {
        Set<String> paths = new TreeSet<>();
        for (RecordComponent c : type.getRecordComponents()) {
            String path = prefix + KEBAB.translate(c.getName());
            paths.add(path);
            Class<?> t = c.getType();
            if (t.isRecord()) {
                paths.addAll(schema(t, path + "/"));
            } else if (Map.class.isAssignableFrom(t) && c.getGenericType() instanceof ParameterizedType pt) {
                paths.add(path + "/" + ANY_KEY);
                if (pt.getActualTypeArguments()[1] instanceof Class<?> v && v.isRecord()) {
                    paths.addAll(schema(v, path + "/" + ANY_KEY + "/"));
                }
            }
            // 목록 안의 키는 경로로 내려가지 않는다(collectLeaves도 목록을 값 하나로 본다)
        }
        return paths;
    }

    private static Set<String> collect(Class<?> type, String prefix) {
        Set<String> paths = new TreeSet<>();
        for (RecordComponent c : type.getRecordComponents()) {
            String path = prefix + KEBAB.translate(c.getName());
            Class<?> t = c.getType();
            if (t == Secret.class) {
                paths.add(path);
            } else if (t.isRecord()) {
                paths.addAll(collect(t, path + "/"));
            } else if (Map.class.isAssignableFrom(t) && c.getGenericType() instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args[0] == String.class && args[1] == Secret.class) {
                    paths.add(path + "/" + ANY_KEY);
                }
            }
            // 목록 안에는 비밀값을 두지 않는다
        }
        return paths;
    }
}
