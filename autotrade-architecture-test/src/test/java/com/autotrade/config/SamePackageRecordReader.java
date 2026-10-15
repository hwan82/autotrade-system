package com.autotrade.config;

import java.lang.reflect.RecordComponent;

/**
 * 규칙 검증용 위반 예제(PR #109 독립 QA Q2): {@code SecretPaths}와 같은 패키지의 다른 클래스도 레코드 구성 요소를 읽을 수
 * 없어야 한다. 허용 판정을 "같은 패키지면 허용"으로 약하게 바꾸면 이 예제가 통과해 시험이 실패한다.
 */
public final class SamePackageRecordReader {

    private SamePackageRecordReader() {}

    /** 같은 패키지에서 구성 요소 읽기. */
    public static RecordComponent first(Class<?> record) {
        return record.getRecordComponents()[0];
    }

    /**
     * risk-ops PR #110 F-2(P4): 서명은 깨끗하지만 private 메서드에서 클래스 객체를 다른 곳(JSON 노드, 목록 밖 컬렉션 타입)에
     * 담아 넘긴다.
     */
    private static void stash(Class<?> record, java.util.concurrent.ConcurrentLinkedQueue<Object> out) {
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().putPOJO("type", record);
        out.add(record.getRecordComponents()[0].getType());
    }

    /** PR #110 독립 QA Q1(M8): 공개 필드 서명에 리플렉션 타입. */
    public static java.lang.reflect.Type lastType;

    /** PR #110 독립 QA R1: 와일드카드로 감싸 {@code Object}가 서명에 드러나지 않는 반환. */
    static java.util.List<?> leak() {
        return Sink.TYPES;
    }

    /** PR #110 독립 QA R2(architect AR-5, risk-ops R-3): 중첩 클래스에 칸을 두고 클래스 객체를 담는다. */
    static final class Sink {
        static final java.util.concurrent.CopyOnWriteArrayList<Object> TYPES = new java.util.concurrent.CopyOnWriteArrayList<>();

        private Sink() {}
    }

    /** architect PR #112 AR-2(MM3): 매개변수의 와일드카드. */
    static int drain(java.util.List<? super Object> out) {
        return out.size();
    }

    /** 독립 QA PR #112 R2(N3): 제네릭 배열 안의 와일드카드. */
    static int drainAll(java.util.List<?>[] all) {
        return all.length;
    }

    /** 독립 QA PR #112 R2: 나머지 리플렉션 타입의 메서드. */
    public static Object wildcardBound(Object type) {
        return ((java.lang.reflect.WildcardType) type).getUpperBounds()[0];
    }

    public static Object variableBound(Object type) {
        return ((java.lang.reflect.TypeVariable<?>) type).getBounds()[0];
    }

    public static Object arrayComponent(Object type) {
        return ((java.lang.reflect.GenericArrayType) type).getGenericComponentType();
    }

    /** architect PR #112 AR-2(MM4): {@code Type} 자체의 메서드. */
    public static String plainTypeName(Object type) {
        return ((java.lang.reflect.Type) type).getTypeName();
    }

    /** PR #110 risk-ops R-2: 허용 클래스 밖에서 제네릭 타입 인자 읽기. */
    public static String typeArgument(Object type) {
        return ((java.lang.reflect.ParameterizedType) type).getActualTypeArguments()[0].getTypeName();
    }

    /** PR #109 QA Q1: 허용 클래스가 {@code Object}로 넘긴 구성 요소에서 타입을 꺼낸다. */
    public static String typeName(Object component) {
        return ((RecordComponent) component).getType().getSimpleName();
    }
}
