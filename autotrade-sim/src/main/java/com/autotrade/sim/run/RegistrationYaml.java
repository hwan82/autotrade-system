package com.autotrade.sim.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실험 등록 파일({@code experiments/<실험ID>/experiment.yaml})의 좁은 YAML 모양 읽기. 조합 실험(D-061)과 녹화 실험(D-069)이
 * 같이 쓴다. 프로필 파일과 같은 이유로 YAML 전체가 아니라 이 모양만 읽고, 벗어나면 줄 번호와 함께 거절한다: 최상위
 * {@code 키: 값}, 목록 키 아래 공백 2칸 + {@code - 값}, 묶음 키 아래 공백 2칸 + {@code 하위키: 값}(D-124, 선정 입력
 * {@code selection-input}). 값은 큰따옴표로 감쌀 수 있고({@code \"}·{@code \\}만 이스케이프), 따옴표 없는 값은 공백 뒤
 * {@code #}부터 주석이다. 같은 키 두 번(묶음 안 하위 키 포함), 모르는 키는 거절한다.
 *
 * @param scalars 최상위 값(파일 순서)
 * @param lists 목록 키 → 항목(파일 순서). 키가 있으면 항목이 없어도 빈 목록으로 들어 있다
 * @param maps 묶음 키 → (하위 키 → 값)(파일 순서). 키가 있으면 하위 키가 없어도 빈 맵으로 들어 있다
 */
public record RegistrationYaml(Map<String, String> scalars, Map<String, List<String>> lists,
        Map<String, Map<String, String>> maps) {

    private static final Pattern TOP = Pattern.compile("([a-z][a-z-]*):(.*)");
    private static final Pattern ITEM = Pattern.compile(" {2}- (.*)");
    private static final Pattern SUB = Pattern.compile(" {2}([a-z][a-z-]*):(.*)");

    public RegistrationYaml {
        scalars = Map.copyOf(scalars);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        lists.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        lists = Map.copyOf(copy);
        Map<String, Map<String, String>> mapCopy = new LinkedHashMap<>();
        maps.forEach((k, v) -> mapCopy.put(k, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(v))));
        maps = Map.copyOf(mapCopy);
    }

    /** 묶음 키 없이 읽는다(기존 등록 파일 모양). */
    public static RegistrationYaml parse(String text, String source, Set<String> scalarKeys, Set<String> listKeys) {
        return parse(text, source, scalarKeys, listKeys, Map.of());
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일 경로)
     * @param scalarKeys 받는 최상위 값 키
     * @param listKeys 받는 목록 키
     * @param mapKeys 받는 묶음 키 → 그 묶음이 받는 하위 키
     * @throws IllegalArgumentException 형식이 틀렸을 때. 메시지에 {@code source:줄번호}가 있다
     */
    public static RegistrationYaml parse(String text, String source, Set<String> scalarKeys, Set<String> listKeys,
            Map<String, Set<String>> mapKeys) {
        Objects.requireNonNull(text, "text");
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        Map<String, Map<String, String>> maps = new LinkedHashMap<>();
        String currentList = null;
        String currentMap = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            int no = i + 1;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                if (currentList == null) {
                    throw fail(source, no, "목록 항목이 목록 키 아래에 있지 않습니다");
                }
                lists.get(currentList).add(value(item.group(1), source, no));
                continue;
            }
            Matcher sub = SUB.matcher(line);
            if (sub.matches()) {
                if (currentMap == null) {
                    throw fail(source, no, "하위 키가 묶음 키 아래에 있지 않습니다");
                }
                String subKey = sub.group(1);
                if (!mapKeys.get(currentMap).contains(subKey)) {
                    throw fail(source, no, currentMap + "의 모르는 하위 키입니다: " + subKey);
                }
                if (maps.get(currentMap).put(subKey, value(sub.group(2).strip(), source, no)) != null) {
                    throw fail(source, no, "같은 하위 키가 두 번 있습니다: " + currentMap + "." + subKey);
                }
                continue;
            }
            Matcher top = TOP.matcher(line);
            if (!top.matches()) {
                throw fail(source, no, "읽을 수 없는 줄입니다(최상위 키: 값, 공백 2칸 + '- 값', 또는 공백 2칸 + '하위키: 값')");
            }
            String key = top.group(1);
            if (scalars.containsKey(key) || lists.containsKey(key) || maps.containsKey(key)) {
                throw fail(source, no, "같은 키가 두 번 있습니다: " + key);
            }
            String rest = value(top.group(2).strip(), source, no);
            if (listKeys.contains(key)) {
                if (!rest.isEmpty()) {
                    throw fail(source, no, key + "는 다음 줄부터 '  - 값' 목록으로 씁니다");
                }
                lists.put(key, new ArrayList<>());
                currentList = key;
                currentMap = null;
            } else if (mapKeys.containsKey(key)) {
                if (!rest.isEmpty()) {
                    throw fail(source, no, key + "는 다음 줄부터 '  하위키: 값'으로 씁니다");
                }
                maps.put(key, new LinkedHashMap<>());
                currentMap = key;
                currentList = null;
            } else if (scalarKeys.contains(key)) {
                scalars.put(key, rest);
                currentList = null;
                currentMap = null;
            } else {
                throw fail(source, no, "모르는 키입니다: " + key);
            }
        }
        return new RegistrationYaml(scalars, lists, maps);
    }

    /** 값이 없거나 비면 예외. */
    public String required(String key) {
        String v = scalars.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("필수 키가 없습니다: " + key);
        }
        return v;
    }

    public List<String> list(String key) {
        return lists.getOrDefault(key, List.of());
    }

    /** 묶음 키의 하위 값. 키가 없으면 빈 맵. */
    public Map<String, String> map(String key) {
        return maps.getOrDefault(key, Map.of());
    }

    /** 큰따옴표 값(\" 이스케이프만) 또는 따옴표 없는 값(공백 뒤 # 주석 제거). */
    private static String value(String raw, String source, int line) {
        String v = raw.strip();
        if (v.startsWith("#")) {
            return "";
        }
        if (v.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length() && (v.charAt(i + 1) == '"' || v.charAt(i + 1) == '\\')) {
                    sb.append(v.charAt(++i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            if (i >= v.length()) {
                throw fail(source, line, "닫는 큰따옴표가 없습니다");
            }
            String after = v.substring(i + 1).strip();
            if (!after.isEmpty() && !after.startsWith("#")) {
                throw fail(source, line, "큰따옴표 값 뒤에 다른 글자가 있습니다");
            }
            return sb.toString();
        }
        int hash = v.indexOf(" #");
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
    }

    private static IllegalArgumentException fail(String source, int line, String message) {
        return new IllegalArgumentException(source + ":" + line + ": " + message);
    }
}
