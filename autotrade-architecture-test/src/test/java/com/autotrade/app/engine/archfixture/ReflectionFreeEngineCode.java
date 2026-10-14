package com.autotrade.app.engine.archfixture;

import java.util.List;
import java.util.function.Function;

/** 규칙 검증용 허용 코드: 람다, 레코드, enum, 클래스 이름 읽기는 리플렉션 금지 규칙에 걸리지 않아야 한다. */
public final class ReflectionFreeEngineCode {

    private ReflectionFreeEngineCode() {}

    enum Side { BUY, SELL }

    record Pair(String a, int b) {}

    public static String ordinary(Object o) {
        Function<String, Side> parse = Side::valueOf;
        Pair p = new Pair(o.getClass().getSimpleName(), List.of(1, 2).size());
        return p + " " + parse.apply("BUY") + " " + switch (Side.SELL) { case BUY -> 1; case SELL -> 2; };
    }
}
