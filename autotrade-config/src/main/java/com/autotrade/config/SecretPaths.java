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
            JsonNode value = navigate(tree, parts).get(parts[parts.length - 1]);
            if (!value.isNull() && !value.asText().isBlank()) {
                found.add(path);
            }
        }
        return found;
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
