package com.autotrade.app.lab.profile;

import java.util.regex.Pattern;

/** 프로필 ID 형식. {@link com.autotrade.strategy.StrategyProfile}과 같은 규칙이고, 폴더 이름으로 쓰므로 경로 문자를 막는다. */
final class StrategyProfileIds {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private StrategyProfileIds() {
    }

    static boolean isValid(String id) {
        return id != null && ID.matcher(id).matches();
    }
}
