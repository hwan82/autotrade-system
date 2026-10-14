package com.autotrade.app.engine.archfixture;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * 규칙 검증용 위반 코드: JSON이 만들 클래스를 고르게 하는 Jackson 설정(리플렉션 생성을 데이터가 정함). 운영 코드가 아니다.
 * 기본 타입 지정 2건(ObjectMapper, 빌더), {@code Id.CLASS} 클래스 주석 1건, {@code Id.MINIMAL_CLASS} 필드 주석 1건, 주석 해석기를
 * 바꿔 주석 없이 {@code Id.CLASS}를 돌려주는 경로(architect PR #108 AR-3).
 * {@link ByName}의 {@code Id.NAME}은 허용(이름표가 미리 정한 하위 타입만 고른다).
 */
public final class PolymorphicJson {

    private PolymorphicJson() {}

    public static ObjectMapper mapperWithDefaultTyping() {
        ObjectMapper m = new ObjectMapper();
        m.activateDefaultTyping(BasicPolymorphicTypeValidator.builder().build());
        return m;
    }

    public static ObjectMapper builderWithDefaultTyping() {
        return JsonMapper.builder().activateDefaultTyping(BasicPolymorphicTypeValidator.builder().build()).build();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public static class ByClass {}

    public static class HoldsMinimalClass {
        @JsonTypeInfo(use = JsonTypeInfo.Id.MINIMAL_CLASS)
        public Object payload;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    @JsonSubTypes({@JsonSubTypes.Type(value = ByName.Only.class, name = "only")})
    public static class ByName {
        public static class Only extends ByName {}
    }

    /** 주석 해석기를 바꿔 주석 없이 클래스 이름 타입 지정을 켠다. */
    public static class ClassIdIntrospector extends JacksonAnnotationIntrospector {
        private static final long serialVersionUID = 1L;

        public Object classId() {
            return JsonTypeInfo.Id.CLASS;
        }
    }
}
