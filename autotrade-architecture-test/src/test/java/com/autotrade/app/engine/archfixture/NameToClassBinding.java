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

    /** F1: 정적 의존 없이 다른 클래스 객체에 닿는 {@code Class} 메서드. */
    public static Class<?>[] nested(Class<?> holder) {
        return holder.getDeclaredClasses();
    }
}
