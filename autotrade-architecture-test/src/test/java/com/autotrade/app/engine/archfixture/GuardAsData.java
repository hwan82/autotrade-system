package com.autotrade.app.engine.archfixture;

import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.RealTradingGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 규칙 검증용 위반 코드(risk-ops PR #108 F1): Guard 패키지 타입을 데이터 바인딩 대상으로 넘긴다. 클래스 객체 1건,
 * 제네릭 상위 타입 인자 1건(익명 {@code TypeReference}), 주석 값 1건. 운영 코드가 아니다.
 */
public final class GuardAsData {

    private GuardAsData() {}

    public static Object viaClassLiteral(ObjectMapper m, byte[] json) throws Exception {
        return m.readValue(json, RealTradingGuard.class);
    }

    public static Object viaTypeReference(ObjectMapper m, byte[] json) throws Exception {
        return m.readValue(json, new TypeReference<GuardState>() {});
    }

    public static class Holder {
        @JsonDeserialize(as = RealTradingGuard.class)
        public Object guard;
    }
}
