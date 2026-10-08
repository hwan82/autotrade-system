package com.autotrade.strategy.profile;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 전략 프로필 파일({@code strategies/<프로필ID>.yaml}, STR-01) 읽기.
 *
 * <p>YAML 전체가 아니라 아래 부분집합만 받는다. 이 밖의 문법은 조용히 다르게 읽히지 않도록 예외로 거절한다.
 * <ul>
 *   <li>들여쓰기는 공백 2칸 단위의 중첩 맵, 값은 스칼라 문자열뿐이다(목록·앵커·여러 줄 문자열·흐름 표기 없음).
 *       목록이 필요하면 쉼표로 구분한 문자열로 쓴다.</li>
 *   <li>값은 큰따옴표로 감쌀 수 있다. 따옴표 안에서는 {@code \"}와 {@code \\}만 이스케이프로 본다.
 *       따옴표가 없는 값은 앞뒤 공백을 자르고, 공백 뒤 {@code #}부터는 주석이다.</li>
 *   <li>{@code #}으로 시작하는 줄과 빈 줄은 건너뛴다.</li>
 *   <li>최상위 {@code id}, {@code version}, {@code market}, {@code style}은 {@link StrategyProfile} 필드로 가고,
 *       나머지는 점으로 이은 키({@code exit.stop-loss-pct})로 펼쳐 파라미터가 된다. 같은 키가 두 번 나오면 거절한다.</li>
 * </ul>
 * 값은 문자열 그대로 보관한다(숫자도 문자열이라 {@code 1.5}와 {@code 1.50}은 다른 해시다). 해석과 검증은
 * {@link com.autotrade.strategy.catalog.CatalogStrategyFactory#validate}가 한다.
 *
 * <p>대안: Jackson YAML을 쓰면 문법 전체를 받지만 순수 모듈에 의존이 늘고, 목록·타입 추론(예: {@code on} → true) 때문에
 * 같은 파일이 다른 파라미터 문자열로 읽힐 수 있어 해시 안정성이 떨어진다. 프로필은 평평한 키-값이라 부분집합으로 충분하다.
 */
public final class ProfileYaml {

    private static final int INDENT = 2;

    private ProfileYaml() {
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일명)
     * @throws IllegalArgumentException 부분집합을 벗어나거나 필수 필드가 없을 때. 메시지에 줄 번호가 있다
     */
    public static StrategyProfile parse(String text, String source) {
        TreeMap<String, String> flat = new TreeMap<>();
        List<String> path = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        boolean expectChild = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            int lineNo = i + 1;
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
                continue;
            }
            if (raw.indexOf('\t') >= 0) {
                throw error(source, lineNo, "탭 문자는 쓸 수 없습니다");
            }
            int spaces = raw.length() - raw.stripLeading().length();
            if (spaces % INDENT != 0) {
                throw error(source, lineNo, "들여쓰기는 공백 " + INDENT + "칸 단위여야 합니다");
            }
            int depth = spaces / INDENT;
            if (depth > path.size() || (expectChild && depth != path.size())) {
                throw error(source, lineNo, expectChild ? "하위 키가 필요합니다" : "들여쓰기가 맞지 않습니다");
            }
            while (path.size() > depth) {
                path.remove(path.size() - 1);
            }
            String body = raw.substring(spaces);
            int colon = body.indexOf(':');
            if (colon <= 0) {
                throw error(source, lineNo, "'키: 값' 형식이 아닙니다");
            }
            String key = body.substring(0, colon);
            if (!key.matches("[a-z0-9][a-z0-9-]*")) {
                throw error(source, lineNo, "키는 소문자·숫자·하이픈만 씁니다: " + key);
            }
            String rest = body.substring(colon + 1);
            if (!rest.isEmpty() && rest.charAt(0) != ' ') {
                throw error(source, lineNo, "콜론 뒤에는 공백이 필요합니다");
            }
            String value = scalar(rest.strip(), source, lineNo);
            String fullKey = path.isEmpty() ? key : String.join(".", path) + "." + key;
            if (value == null) {
                if (flat.containsKey(fullKey) || flat.keySet().stream().anyMatch(k -> k.startsWith(fullKey + "."))) {
                    throw error(source, lineNo, "같은 키가 두 번 나옵니다: " + fullKey);
                }
                path.add(key);
                expectChild = true;
            } else {
                if (flat.keySet().stream().anyMatch(k -> k.startsWith(fullKey + "."))
                        || flat.put(fullKey, value) != null) {
                    throw error(source, lineNo, "같은 키가 두 번 나옵니다: " + fullKey);
                }
                expectChild = false;
            }
        }
        if (expectChild) {
            throw new IllegalArgumentException(source + ": 마지막 키에 하위 키가 없습니다");
        }
        String id = take(flat, "id", source);
        String version = take(flat, "version", source);
        String market = take(flat, "market", source);
        String style = take(flat, "style", source);
        try {
            return new StrategyProfile(id, Integer.parseInt(version), Market.valueOf(market), TradingStyle.valueOf(style),
                    flat);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    /** 값 부분을 해석한다. 하위 맵이 시작되면(값 없음) null. */
    private static String scalar(String text, String source, int lineNo) {
        if (text.isEmpty() || text.startsWith("#")) {
            return null;
        }
        if (text.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    if (i + 1 >= text.length() || (text.charAt(i + 1) != '"' && text.charAt(i + 1) != '\\')) {
                        throw error(source, lineNo, "지원하지 않는 이스케이프입니다");
                    }
                    sb.append(text.charAt(++i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            if (i >= text.length()) {
                throw error(source, lineNo, "닫는 따옴표가 없습니다");
            }
            String tail = text.substring(i + 1).strip();
            if (!tail.isEmpty() && !tail.startsWith("#")) {
                throw error(source, lineNo, "따옴표 뒤에 값이 더 있습니다");
            }
            return sb.toString();
        }
        int comment = text.indexOf(" #");
        String value = (comment >= 0 ? text.substring(0, comment) : text).strip();
        char first = value.charAt(0);
        if ("-[{&*!|>'%@`".indexOf(first) >= 0) {
            throw error(source, lineNo,
                    "지원하지 않는 YAML 문법입니다(목록·흐름 표기·앵커·여러 줄). 음수는 따옴표로 감쌉니다: " + value);
        }
        return value;
    }

    private static String take(TreeMap<String, String> flat, String key, String source) {
        String v = flat.remove(key);
        if (v == null) {
            throw new IllegalArgumentException(source + ": 필수 필드가 없습니다: " + key);
        }
        return v;
    }

    private static IllegalArgumentException error(String source, int line, String message) {
        return new IllegalArgumentException(source + ":" + line + ": " + message);
    }
}
