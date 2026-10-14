package com.autotrade.app.engine.archfixture;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.naming.InitialContext;
import javax.tools.ToolProvider;

/**
 * 규칙 검증용 위반 코드(risk-ops PR #108 F2·R1): 리플렉션 API 밖의 동적 코드·간접 호출 수단. 네이티브 적재 2건,
 * native 선언 1건, MBean, 실행 중 컴파일, JNDI, 필드 이름 문자열 갱신기, 모듈 열기. 운영 코드가 아니다.
 */
public final class DynamicCode {

    volatile Object state;

    private DynamicCode() {}

    public static void nativeCode() {
        System.loadLibrary("x");
        Runtime.getRuntime().load("/x.so");
    }

    static native void raise();

    public static Object mbean() {
        return ManagementFactory.getPlatformMBeanServer();
    }

    public static Object compiler() {
        return ToolProvider.getSystemJavaCompiler();
    }

    public static Object jndi(String name) throws Exception {
        return new InitialContext().lookup(name);
    }

    public static Object updater() {
        return AtomicReferenceFieldUpdater.newUpdater(DynamicCode.class, Object.class, "state");
    }

    public static void openModule(Module target) {
        DynamicCode.class.getModule().addOpens("com.autotrade.app.engine.archfixture", target);
    }
}
