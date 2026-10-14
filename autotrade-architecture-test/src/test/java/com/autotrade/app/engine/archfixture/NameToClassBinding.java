package com.autotrade.app.engine.archfixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;

/**
 * 규칙 검증용 위반 예제(독립 QA PR #108 Q16). 리플렉션 API 없이 Jackson의 {@code Class} 역직렬화로 이름 문자열에서 클래스를
 * 얻고(F1), 허용 목록 밖 Jackson 타입을 형변환·메서드 인자로만 쓴다(F2). 클래스 이름은 문자열을 이어 붙여 정적 의존을 없앴다.
 */
public final class NameToClassBinding {

    private static final ObjectMapper M = new ObjectMapper();

    private NameToClassBinding() {}

    /** F1: {@code readValue(.., Class.class)}는 {@code Class.forName}과 같은 일을 한다. */
    public static Object byName(String name, Map<String, Object> fields) throws JsonProcessingException {
        Class<?> k = M.readValue("\"" + name + "\"", Class.class);
        return M.convertValue(fields, k);
    }

    /** F1 변형(architect 재검토 AR-8): 배열 {@code Class[].class}도 원소마다 이름으로 클래스를 만든다. */
    public static Class<?>[] byNames(String name) throws JsonProcessingException {
        return M.readValue("[\"" + name + "\"]", Class[].class);
    }

    /** F1 변형(architect 재검토 AR-9): 클래스 객체의 {@code getClass()}는 리터럴 없이 {@code Class.class}를 준다. */
    public static Object viaGetClass(String name) throws JsonProcessingException {
        Class<?> classOfClass = String.class.getClass();
        return M.readValue("\"" + name + "\"", classOfClass);
    }

    /** F1 변형: {@code Object::getClass} 메서드 참조. */
    public static java.util.function.Function<Object, Class<?>> classOf() {
        return Object::getClass;
    }

    /** F2: 목록 밖 {@code MapperFeature}를 형변환하고 {@code configure(MapperFeature, boolean)}에 넘긴다. */
    public static ObjectMapper relaxed(Object feature) {
        return JsonMapper.builder().configure((MapperFeature) feature, false).build();
    }

    /** F2: 형변환만(호출 없이) 해도 클래스 파일 상수로 잡힌다. */
    public static boolean castOnly(Object feature) {
        Object f = (MapperFeature) feature;
        return f != null;
    }

    /** F1: 바인딩 대상의 {@code Class} 속성(레코드 구성 요소 = 필드·생성자 매개변수). */
    public record TypedCommand(Class<?> type, Map<String, Object> fields) {}

    /** F1: 설정자의 {@code Class} 매개변수, 제네릭 인자의 {@code Class}. */
    public static final class Bean {
        private String note;

        public void setTarget(Class<?> target) {
            note = String.valueOf(target != null);
        }

        public static int count(List<Class<?>> types) {
            return types.size();
        }

        public String note() {
            return note;
        }
    }

    /** F1 변형(architect 재검토 AR-10 P4): 상위 클래스의 제네릭 인자. */
    public static final class SuperProbe extends java.util.ArrayList<Class<?>> {
        private static final long serialVersionUID = 1L;
    }

    /** 타입 변수만 가진 바인딩 대상(그 자체는 허용). */
    public static class Holder<T> {
        public T value;
    }

    /** F1 변형(AR-10 P5): 상속한 타입 변수에 Class를 넣는다. */
    public static final class TypeVarProbe extends Holder<Class<?>> {}

    /** F1 변형(AR-10 P6): 설정자 없는 컬렉션 게터(Jackson USE_GETTERS_AS_SETTERS). */
    public static final class GetterProbe {
        private final List<Object> types = new java.util.ArrayList<>();

        @SuppressWarnings("unchecked")
        public List<Class<?>> getTypes() {
            return (List<Class<?>>) (List<?>) types;
        }
    }

    /** F1: 정적 의존 없이 다른 클래스 객체에 닿는 {@code Class} 메서드. */
    public static Class<?>[] nested(Class<?> holder) {
        return holder.getDeclaredClasses();
    }
}
