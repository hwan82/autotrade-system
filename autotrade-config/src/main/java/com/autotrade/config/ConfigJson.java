package com.autotrade.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

/** 설정 파일 전용 Jackson 설정. 키는 kebab-case, 모르는 키는 오류(오타가 조용히 무시되지 않게). */
final class ConfigJson {

    private ConfigJson() {}

    static ObjectMapper mapper() {
        SimpleModule secrets = new SimpleModule("autotrade-secret-store");
        secrets.addSerializer(Secret.class, new PlainSecretSerializer());
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                // 오류 메시지에 원문 일부가 실려 비밀값이 로그로 새지 않게 한다
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .addModule(secrets)
                .build();
    }

    /** 예외에서 비밀값이 섞일 수 있는 메시지를 버리고 위치(JSON 경로)만 남긴다. */
    static String describe(Exception e) {
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
        if (e instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            sb.append(" at ").append(maskedPath(jme));
        }
        // 설정 레코드의 검증 메시지는 키 이름과 범위만 담으므로 그대로 보여 준다
        if (e.getCause() instanceof IllegalArgumentException iae && isOwnValidation(iae)) {
            sb.append(": ").append(iae.getMessage());
        }
        return sb.toString();
    }

    /**
     * JSON 경로를 "real-trading/accounts/0/id" 꼴로 쓴다. 키 이름 모양(소문자·하이픈)이 아닌 키는 {@code *}로 바꾼다.
     * 모르는 키 자리에 계좌번호를 적었을 수 있어서다(PR #101 architect 발견 1, PR #100 QA R4와 같은 규칙).
     */
    private static String maskedPath(JsonMappingException e) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            String part = ref.getFieldName() != null
                    ? (SecretPaths.isKeyLike(ref.getFieldName()) ? ref.getFieldName() : SecretPaths.ANY_KEY)
                    : ref.getIndex() >= 0 ? String.valueOf(ref.getIndex()) : "?";
            sb.append(sb.isEmpty() ? "" : "/").append(part);
        }
        return sb.toString();
    }

    private static boolean isOwnValidation(IllegalArgumentException e) {
        StackTraceElement[] trace = e.getStackTrace();
        return trace.length > 0 && trace[0].getClassName().startsWith(RuntimeConfig.class.getName());
    }

    /** 저장 파일에만 평문을 쓴다. 이 모듈 밖으로 이 mapper를 내보내지 않는다. */
    private static final class PlainSecretSerializer extends StdSerializer<Secret> {
        private static final long serialVersionUID = 1L;

        PlainSecretSerializer() {
            super(Secret.class);
        }

        @Override
        public void serialize(Secret value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.reveal());
        }
    }
}
