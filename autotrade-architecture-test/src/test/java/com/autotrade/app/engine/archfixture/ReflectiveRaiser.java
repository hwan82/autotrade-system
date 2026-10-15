package com.autotrade.app.engine.archfixture;

import java.io.InputStream;
import java.io.ObjectInputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ServiceLoader;
import java.util.function.BiFunction;

/**
 * 규칙 검증용 위반 코드(독립 QA PR #21 E8 형태): 문자열로 만든 이름과 리플렉션으로 Guard를 올리거나 출구를 여는 방법들.
 * 바이트코드에 {@code beginActivation} 호출이 드러나지 않아 기존 실전 경로 규칙은 보지 못한다. 운영 코드가 아니다.
 */
public final class ReflectiveRaiser {

    private ReflectiveRaiser() {}

    /** E8: Class.forName + getMethod + Method.invoke, Constructor.newInstance. */
    public static Object viaMethodInvoke(Object guard, Object[] args) throws Exception {
        Class<?> type = Class.forName("com.autotrade.risk.guard." + "SecondConfirmation");
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        Object confirmation = ctor.newInstance(args);
        Method begin = guard.getClass().getMethod("begin" + "Activation", type);
        return begin.invoke(guard, confirmation);
    }

    /** private 필드를 열고 상태를 바꾼다. */
    public static void viaField(Object guard, Object open) throws Exception {
        Field state = guard.getClass().getDeclaredField("state");
        state.setAccessible(true);
        state.set(guard, open);
    }

    /** MethodHandle 조회와 호출. */
    public static Object viaMethodHandle(Object plugin, Object account) throws Throwable {
        MethodHandle h = MethodHandles.lookup().findVirtual(plugin.getClass(), "order" + "Gateway",
                MethodType.methodType(java.util.Optional.class, account.getClass()));
        return h.invoke(plugin, account);
    }

    /** 메서드 참조로 넘긴 invoke(호출 규칙만으로는 놓친다). */
    public static BiFunction<Object, Object[], Object> viaMethodReference(Method m) {
        return (target, a) -> {
            try {
                return ((Invoker) m::invoke).call(target, a);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    interface Invoker {
        Object call(Object target, Object[] args) throws Exception;
    }

    /** 동적 프록시로 인터페이스 구현을 런타임에 만든다(RiskPolicy 위치 규칙 우회). */
    public static Object viaProxy(Class<?> iface) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, (p, m, a) -> null);
    }

    /** 허용 목록 밖의 ServiceLoader. */
    public static Object viaServiceLoader(Class<?> service) {
        return ServiceLoader.load(service).findFirst().orElse(null);
    }

    /** Java 직렬화는 생성자 검사 없이 객체를 만든다. */
    public static Object viaDeserialization(InputStream in) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(in)) {
            return ois.readObject();
        }
    }
}
