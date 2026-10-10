package com.autotrade.app.collector;

import com.autotrade.config.Secret;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 로그·예외 메시지에서 인증키를 가린다(지침 4장 4번, MD-11). URL 쿼리의 {@code crtfc_key=}와 알고 있는 키 값을 모두 바꾼다.
 * 요청 URL은 이 클래스를 거친 문자열로만 남긴다.
 */
public final class Redaction {

    private static final Pattern QUERY_KEY = Pattern.compile("(?i)(crtfc_key=)[^&\\s\"']*");
    private static final String MASK = "****";

    private final List<String> values;

    public Redaction(List<Secret> secrets) {
        this.values = secrets.stream().filter(Secret::isPresent).map(Secret::reveal).toList();
    }

    public String clean(String text) {
        if (text == null) {
            return "";
        }
        String out = QUERY_KEY.matcher(text).replaceAll("$1" + MASK);
        for (String v : values) {
            out = out.replace(v, MASK);
        }
        return out;
    }
}
