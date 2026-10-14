package com.autotrade.app.engine.archfixture;

import com.autotrade.risk.guard.SecondConfirmation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 규칙 검증용 위반 코드: 2차 확인을 검증기 밖에서 데이터로 만들어지게 하는 방법(Jackson이 리플렉션으로 생성자를 부른다).
 * {@code new SecondConfirmation(...)}이 바이트코드에 없어 생성 규칙은 보지 못한다. 운영 코드가 아니다.
 */
public final class DataBoundConfirmation {

    private DataBoundConfirmation() {}

    /** 명령 DTO 필드로 받기. */
    public record CommandDto(String account, SecondConfirmation confirmation) {}

    /** 클래스 객체를 넘겨 역직렬화. */
    public static Object viaClassLiteral(ObjectMapper m, byte[] json) throws Exception {
        return m.readValue(json, SecondConfirmation.class);
    }

    /** 제네릭 타입 참조로 역직렬화. */
    public static Object viaTypeReference(ObjectMapper m, byte[] json) throws Exception {
        return m.readValue(json, new TypeReference<SecondConfirmation>() {});
    }
}
