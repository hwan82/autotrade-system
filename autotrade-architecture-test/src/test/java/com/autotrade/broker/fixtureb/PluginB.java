package com.autotrade.broker.fixtureb;

/** ArchUnit 위반 픽스처: 다른 플러그인이 끌어다 쓰는 대상. */
public final class PluginB {
    private PluginB() {}

    public static String helper() {
        return "b";
    }
}
