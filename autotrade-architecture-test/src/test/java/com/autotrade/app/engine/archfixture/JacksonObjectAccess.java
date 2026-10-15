package com.autotrade.app.engine.archfixture;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

/**
 * 규칙 검증용 위반 코드(risk-ops PR #108 F1): 허용 목록의 Jackson으로 이미 있는 객체의 필드를 쓰거나, private 필드를 열거나,
 * 믹스인으로 주석을 붙이거나, 문자열로 클래스를 찾는다. 우리 바이트코드에 {@code java.lang.reflect}가 남지 않는다.
 * 위반: readerForUpdating, updateValue, setVisibility, addMixIn, findClass, findAndRegisterModules. 운영 코드가 아니다.
 */
public final class JacksonObjectAccess {

    private JacksonObjectAccess() {}

    /** 넘겨받은 객체(Guard일 수도 있다)를 JSON 내용으로 덮어쓴다. 타입 의존이 없다. */
    public static Object overwrite(ObjectMapper m, Object target, byte[] json) throws Exception {
        return m.readerForUpdating(target).readValue(json);
    }

    public static Object overwriteFromMap(ObjectMapper m, Object target, Object values) throws Exception {
        return m.updateValue(target, values);
    }

    public static ObjectMapper openPrivateFields(ObjectMapper m) {
        return m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    public static ObjectMapper attachAnnotations(ObjectMapper m, Class<?> target, Class<?> mixin) {
        return m.addMixIn(target, mixin);
    }

    public static Class<?> lookupClass(String name) throws Exception {
        return TypeFactory.defaultInstance().findClass(name);
    }

    /** 클래스패스에서 Jackson 모듈을 찾아 등록한다(ServiceLoader와 같은 일). */
    public static ObjectMapper discoverModules(ObjectMapper m) {
        return m.findAndRegisterModules();
    }
}
