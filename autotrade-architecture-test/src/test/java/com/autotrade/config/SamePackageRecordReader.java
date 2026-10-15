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

    /** PR #109 QA Q1: 허용 클래스가 {@code Object}로 넘긴 구성 요소에서 타입을 꺼낸다. */
    public static String typeName(Object component) {
        return ((RecordComponent) component).getType().getSimpleName();
    }
}
