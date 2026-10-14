package com.autotrade.app.engine.archfixture;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.naming.InitialContext;
import javax.tools.ToolProvider;

/**
 * 규칙 검증용 위반 코드(risk-ops PR #108 F2·R1): 리플렉션 API 밖의 동적 코드·간접 호출 수단. 네이티브 적재 2건,
 * native 선언 1건, MBean, 실행 중 컴파일, JNDI, 필드 이름 문자열 갱신기, 모듈 열기, 클래스 로더 정적 팩토리와 로더를 받는
 * ResourceBundle(risk-ops PR #108 재검토 F3), 로더 꺼내기. 운영 코드가 아니다.
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

    /** 정적 팩토리로 빌드 밖 위치의 로더를 만들고, 이름으로 클래스를 찾아 만드는 API에 넘긴다. */
    public static Object outsideCode(java.net.URL[] where) {
        ClassLoader loader = java.net.URLClassLoader.newInstance(where);
        return java.util.ResourceBundle.getBundle("x.Bundle", java.util.Locale.ROOT, loader);
    }

    public static Object contextLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    /** 자기 JVM에 에이전트를 붙이는 외부 명령(architect PR #108 재검토 AR-4). */
    public static Object attachSelf(long pid) throws Exception {
        new ProcessBuilder("jcmd", Long.toString(pid), "JVMTI.agent_load", "/x.jar").start();
        return Runtime.getRuntime().exec(new String[] {"jcmd", Long.toString(pid), "JVMTI.agent_load", "/x.jar"});
    }

    /** 보안 제공자에 클래스 이름을 등록하고 서비스로 만든다(Class.forName + newInstance와 같은 일, architect 2차 재검토 AR-6). */
    public static Object viaProvider(String className) throws Exception {
        java.security.Provider p = new java.security.Provider("X", "1", "x") {
            private static final long serialVersionUID = 1L;
        };
        p.put("MessageDigest.Y", className);
        java.security.Security.addProvider(p);
        return p.getService("MessageDigest", "Y").newInstance(null);
    }

    /** 시스템 속성으로 JDK가 만들 클래스 이름을 정한다(architect 3차 재검토 AR-7). */
    public static Object viaSystemProperty(String className) {
        System.setProperty("java.util.logging.config.class", className);
        System.setProperties(System.getProperties());
        System.clearProperty("x");
        return System.getLogger("x");
    }
}
