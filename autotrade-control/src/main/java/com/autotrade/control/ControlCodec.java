package com.autotrade.control;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * 메시지 한 줄 읽기·쓰기(UTF-8 JSON + 줄바꿈). 한 줄은 줄바꿈을 포함해 {@link #MAX_BYTES}를 넘을 수 없다.
 * 파싱 실패 예외 메시지에는 원문을 넣지 않는다(비밀번호가 들어 있을 수 있음).
 *
 * <p>요청(엔진이 읽음)은 엄격하게 읽는다: 모르는 필드, 중복 필드, 숫자 enum, 문자열 자리의 숫자·불리언, 줄 뒤의 남은 바이트를
 * 모두 거부한다. 응답(실험실이 읽음)은 모르는 필드를 무시해 엔진을 먼저 올려도 실험실이 깨지지 않게 한다(ADR-006 2.3).
 */
public final class ControlCodec {

    /** 한 메시지 최대 크기(줄바꿈 포함). 요청은 짧고 응답도 계좌 몇 개분이다. */
    public static final int MAX_BYTES = 64 * 1024;

    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual, c -> c
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Enum, c -> c
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
            .build();

    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ControlCodec() {}

    public static void write(SocketChannel channel, Object message) throws IOException {
        byte[] body = STRICT.writeValueAsBytes(message);
        if (body.length + 1 > MAX_BYTES) {
            throw new IOException("메시지가 너무 큽니다");
        }
        ByteBuffer buffer = ByteBuffer.allocate(body.length + 1);
        buffer.put(body).put((byte) '\n').flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** 요청 읽기(엄격). */
    public static ControlRequest readRequest(SocketChannel channel) throws IOException {
        return parse(STRICT, readLine(channel), ControlRequest.class);
    }

    /** 응답 읽기(모르는 필드 무시). */
    public static ControlResponse readResponse(SocketChannel channel) throws IOException {
        return parse(LENIENT, readLine(channel), ControlResponse.class);
    }

    static ControlRequest parseRequest(byte[] line) throws MalformedMessageException {
        return parse(STRICT, line, ControlRequest.class);
    }

    private static <T> T parse(ObjectMapper mapper, byte[] line, Class<T> type) throws MalformedMessageException {
        try {
            T value = mapper.readValue(line, type);
            if (value == null) {
                throw new MalformedMessageException();
            }
            return value;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new MalformedMessageException();
        } catch (IOException e) {
            throw new MalformedMessageException();
        }
    }

    private static byte[] readLine(SocketChannel channel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        ByteBuffer chunk = ByteBuffer.allocate(1024);
        while (true) {
            chunk.clear();
            int n = channel.read(chunk);
            if (n < 0) {
                throw new IOException("메시지 끝 전에 연결이 닫혔습니다");
            }
            for (int i = 0; i < n; i++) {
                byte b = chunk.get(i);
                if (b == '\n') {
                    if (i != n - 1) {
                        // 연결 하나에 메시지 하나. 줄 뒤에 더 보낸 것은 형식 오류다
                        throw new MalformedMessageException();
                    }
                    return out.toByteArray();
                }
                if (out.size() + 1 >= MAX_BYTES) {
                    throw new MalformedMessageException();
                }
                out.write(b);
            }
        }
    }

    static String toJson(Object message) throws IOException {
        return new String(STRICT.writeValueAsBytes(message), StandardCharsets.UTF_8);
    }

    /** 형식이 틀렸거나 너무 큰 메시지. 원문은 담지 않는다. */
    public static final class MalformedMessageException extends IOException {
        private static final long serialVersionUID = 1L;

        public MalformedMessageException() {
            super("명령 형식이 올바르지 않습니다");
        }
    }
}
