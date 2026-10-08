package com.autotrade.strategy.profile;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.util.Set;
import java.util.TreeMap;

/**
 * 전략 프로필 버전 파일({@code strategies/<프로필ID>/v<버전>.yaml}, ADR-005 4장, STR-01) 읽기.
 *
 * <p>파일 모양은 실험실 UI의 프로필 저장소(app-lab {@code StrategyProfileStore})가 쓰는 것과 같다.
 * <pre>
 * id: "kr-orb-30"
 * version: 1
 * market: "KRX"
 * style: "INTRADAY"
 * created-at: "2026-10-08T00:00:00Z"
 * parameters:
 *   exit.stop-loss-pct: "1.5"
 * </pre>
 * 순수 모듈이라 Jackson YAML 대신 이 모양에 필요한 부분집합만 읽고, 이 밖의 문법은 조용히 다르게 읽히지 않도록 거절한다.
 * <ul>
 *   <li>최상위 키는 {@code id}, {@code version}, {@code market}, {@code style}, {@code based-on}, {@code created-at},
 *       {@code note}, {@code parameters}뿐이다. 앞의 넷은 필수이고, {@code based-on}·{@code created-at}·{@code note}는
 *       해시에 들어가지 않는 기록용이다.</li>
 *   <li>{@code parameters} 아래는 공백 2칸 들여쓰기의 평평한 맵이다. 키는 점으로 이은 소문자 키({@code exit.stop-loss-pct}),
 *       값은 스칼라 문자열이다. 목록·앵커·흐름 표기·블록 문자열({@code |}, {@code >})은 쓰지 않는다.</li>
 *   <li>값은 큰따옴표로 감쌀 수 있고, 저장소가 쓴 대로 여러 줄로 접힌 큰따옴표 문자열과 YAML 이스케이프를 읽는다.
 *       따옴표가 없는 값은 앞뒤 공백을 자르고, 공백 뒤 {@code #}부터는 주석이다.</li>
 *   <li>{@code #}으로 시작하는 줄과 빈 줄은 건너뛴다. 같은 키가 두 번 나오면 거절한다.</li>
 * </ul>
 * 값은 문자열 그대로 보관한다(숫자도 문자열이라 {@code 1.5}와 {@code 1.50}은 다른 해시다). 해석과 검증은
 * {@link com.autotrade.strategy.catalog.CatalogStrategyFactory#validate}가 한다.
 *
 * <p>대안: Jackson YAML을 쓰면 문법 전체를 받지만 순수 모듈에 의존이 늘고, 따옴표 없는 값의 타입 추론(예: {@code on} → true)
 * 때문에 손으로 쓴 파일이 다른 파라미터 문자열로 읽힐 수 있다. 저장소가 쓴 파일과 같은 해시로 읽히는지는
 * app-lab 테스트가 저장소 프로필 전부로 확인한다.
 */
public final class ProfileYaml {

    private static final int INDENT = 2;
    private static final String PARAMETERS = "parameters";
    private static final Set<String> TOP_KEYS =
            Set.of("id", "version", "market", "style", "based-on", "created-at", "note", PARAMETERS);
    private static final String TOP_KEY = "[a-z][a-z-]*";
    private static final String PARAM_KEY = "[a-z0-9][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)*";

    private ProfileYaml() {
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일명)
     * @throws IllegalArgumentException 부분집합을 벗어나거나 필수 필드가 없을 때. 메시지에 줄 번호가 있다
     */
    public static StrategyProfile parse(String text, String source) {
        TreeMap<String, String> top = new TreeMap<>();
        TreeMap<String, String> params = new TreeMap<>();
        String[] lines = text.split("\r?\n", -1);
        boolean inParameters = false;
        boolean expectChild = false;
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            int lineNo = i + 1;
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
                i++;
                continue;
            }
            if (raw.indexOf('\t') >= 0) {
                throw error(source, lineNo, "탭 문자는 쓸 수 없습니다");
            }
            int spaces = raw.length() - raw.stripLeading().length();
            if (spaces != 0 && spaces != INDENT) {
                throw error(source, lineNo, "들여쓰기는 최상위 0칸, parameters 아래 " + INDENT + "칸입니다");
            }
            boolean child = spaces == INDENT;
            if (child && !inParameters) {
                throw error(source, lineNo, "parameters 아래가 아닌 곳에 들여쓴 줄이 있습니다");
            }
            if (!child) {
                if (expectChild) {
                    throw error(source, lineNo, "parameters에 하위 키가 필요합니다");
                }
                inParameters = false;
            }
            Cursor c = new Cursor(lines, i, spaces, source);
            String key = c.key(child ? PARAM_KEY : TOP_KEY);
            String value = c.value();
            i = c.nextLine();
            if (child) {
                if (value == null) {
                    throw error(source, lineNo, "parameters 아래 값은 스칼라 문자열입니다(중첩 맵은 점 키로 씁니다): " + key);
                }
                if (params.put(key, value) != null) {
                    throw error(source, lineNo, "같은 키가 두 번 나옵니다: " + key);
                }
                expectChild = false;
                continue;
            }
            if (!TOP_KEYS.contains(key)) {
                throw error(source, lineNo, "알 수 없는 최상위 키입니다(파라미터는 parameters 아래에 씁니다): " + key);
            }
            if (top.containsKey(key)) {
                throw error(source, lineNo, "같은 키가 두 번 나옵니다: " + key);
            }
            top.put(key, value == null ? "" : value);
            if (key.equals(PARAMETERS)) {
                if (value != null) {
                    throw error(source, lineNo, "parameters는 하위 맵입니다");
                }
                inParameters = true;
                expectChild = true;
            } else if (value == null) {
                throw error(source, lineNo, "값이 없습니다: " + key);
            }
        }
        if (expectChild) {
            throw new IllegalArgumentException(source + ": parameters에 하위 키가 없습니다");
        }
        String id = take(top, "id", source);
        String version = take(top, "version", source);
        String market = take(top, "market", source);
        String style = take(top, "style", source);
        try {
            return new StrategyProfile(id, Integer.parseInt(version), Market.valueOf(market), TradingStyle.valueOf(style),
                    params);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static String take(TreeMap<String, String> top, String key, String source) {
        String v = top.get(key);
        if (v == null) {
            throw new IllegalArgumentException(source + ": 필수 필드가 없습니다: " + key);
        }
        return v;
    }

    private static IllegalArgumentException error(String source, int line, String message) {
        return new IllegalArgumentException(source + ":" + line + ": " + message);
    }

    /** 한 항목(키와 값, 값이 여러 줄로 접혔으면 이어진 줄까지)을 읽는다. */
    private static final class Cursor {
        private final String[] lines;
        private final int indent;
        private final String source;
        private int line;
        private String text;
        private int pos;

        Cursor(String[] lines, int line, int indent, String source) {
            this.lines = lines;
            this.line = line;
            this.indent = indent;
            this.source = source;
            this.text = lines[line];
            this.pos = indent;
        }

        int nextLine() {
            return line + 1;
        }

        String key(String pattern) {
            String key;
            if (text.charAt(pos) == '"') {
                key = quoted();
                if (pos >= text.length() || text.charAt(pos) != ':') {
                    throw fail("'키: 값' 형식이 아닙니다");
                }
            } else {
                int colon = text.indexOf(':', pos);
                if (colon <= pos) {
                    throw fail("'키: 값' 형식이 아닙니다");
                }
                key = text.substring(pos, colon);
                pos = colon;
            }
            if (!key.matches(pattern)) {
                throw fail("키는 소문자·숫자·하이픈(파라미터는 점으로 이은 키)만 씁니다: " + key);
            }
            pos++;
            if (pos < text.length() && text.charAt(pos) != ' ') {
                throw fail("콜론 뒤에는 공백이 필요합니다");
            }
            return key;
        }

        /** 값이 없으면(하위 맵 시작) null. */
        String value() {
            String rest = text.substring(pos).strip();
            if (rest.isEmpty() || rest.startsWith("#")) {
                return null;
            }
            while (text.charAt(pos) == ' ') {
                pos++;
            }
            if (text.charAt(pos) == '"') {
                String v = quoted();
                String tail = text.substring(pos).strip();
                if (!tail.isEmpty() && !tail.startsWith("#")) {
                    throw fail("따옴표 뒤에 값이 더 있습니다");
                }
                return v;
            }
            int comment = rest.indexOf(" #");
            String v = (comment >= 0 ? rest.substring(0, comment) : rest).strip();
            if ("-[{&*!|>'%@`".indexOf(v.charAt(0)) >= 0) {
                throw fail("지원하지 않는 YAML 문법입니다(목록·흐름 표기·앵커·블록 문자열·작은따옴표). "
                        + "음수 등은 큰따옴표로 감쌉니다: " + v);
            }
            return v;
        }

        /**
         * 큰따옴표 문자열(YAML 1.1 double-quoted). {@code pos}는 여는 따옴표, 끝나면 닫는 따옴표 다음.
         * 줄 끝 {@code \}는 줄바꿈 없이 잇고, 그냥 줄바꿈은 공백 하나(빈 줄은 줄바꿈)로 접는다. 이어진 줄의 앞 공백은 버린다.
         */
        private String quoted() {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= text.length()) {
                    // 줄바꿈 접기: 끝 공백을 버리고, 빈 줄 수만큼 줄바꿈, 없으면 공백 하나
                    int trimmed = sb.length();
                    while (trimmed > 0 && sb.charAt(trimmed - 1) == ' ') {
                        trimmed--;
                    }
                    sb.setLength(trimmed);
                    int empty = 0;
                    advance();
                    while (text.isBlank()) {
                        empty++;
                        advance();
                    }
                    sb.append(empty == 0 ? " " : "\n".repeat(empty));
                    continue;
                }
                char ch = text.charAt(pos);
                if (ch == '"') {
                    pos++;
                    return sb.toString();
                }
                if (ch != '\\') {
                    sb.append(ch);
                    pos++;
                    continue;
                }
                if (pos + 1 >= text.length()) {
                    advance(); // 줄 끝 \: 줄바꿈 없이 잇는다
                    continue;
                }
                char e = text.charAt(pos + 1);
                pos += 2;
                switch (e) {
                    case '"', '\\', '/', ' ' -> sb.append(e);
                    case '0' -> sb.append('\0');
                    case 'a' -> sb.append('\u0007');
                    case 'b' -> sb.append('\b');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'v' -> sb.append('\u000B');
                    case 'f' -> sb.append('\f');
                    case 'r' -> sb.append('\r');
                    case 'e' -> sb.append('\u001B');
                    case 'N' -> sb.append('\u0085');
                    case '_' -> sb.append(' ');
                    case 'L' -> sb.append(' ');
                    case 'P' -> sb.append(' ');
                    case 'x' -> sb.appendCodePoint(hex(2));
                    case 'u' -> sb.appendCodePoint(hex(4));
                    case 'U' -> sb.appendCodePoint(hex(8));
                    default -> throw fail("지원하지 않는 이스케이프입니다: \\" + e);
                }
            }
        }

        /** 접힌 문자열의 다음 줄로 간다. 이어진 줄은 항목보다 깊게 들여써야 한다. */
        private void advance() {
            line++;
            if (line >= lines.length) {
                throw fail("닫는 따옴표가 없습니다");
            }
            text = lines[line];
            if (text.indexOf('\t') >= 0) {
                throw fail("탭 문자는 쓸 수 없습니다");
            }
            int spaces = text.length() - text.stripLeading().length();
            if (!text.isBlank() && spaces <= indent) {
                throw fail("여러 줄 문자열의 이어진 줄은 키보다 깊게 들여씁니다");
            }
            pos = text.isBlank() ? text.length() : spaces;
        }

        private int hex(int digits) {
            if (pos + digits > text.length()) {
                throw fail("16진 이스케이프가 짧습니다");
            }
            try {
                int cp = Integer.parseInt(text.substring(pos, pos + digits), 16);
                pos += digits;
                return cp;
            } catch (NumberFormatException ex) {
                throw fail("16진 이스케이프가 아닙니다");
            }
        }

        private IllegalArgumentException fail(String message) {
            return error(source, line + 1, message);
        }
    }
}
