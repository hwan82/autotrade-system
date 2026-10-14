package qa.probe;

import java.lang.reflect.Method;

/**
 * 규칙 검증용 위반 코드(risk-ops PR #108 R2): 실전 경로 모듈 안이지만 {@code com.autotrade} 밖 패키지의 리플렉션 호출.
 * 패키지 판정으로는 범위 밖이고 모듈 판정으로만 걸린다. 운영 코드가 아니다.
 */
public final class OutsideReflective {

    private OutsideReflective() {}

    public static Object open(Method m, Object target) throws Exception {
        return m.invoke(target);
    }
}
