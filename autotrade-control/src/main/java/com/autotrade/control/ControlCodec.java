package com.autotrade.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * 메시지 한 줄 읽기·쓰기(UTF-8 JSON + 줄바꿈). 모르는 필드는 거부하고 한 줄은 {@link #MAX_BYTES}를 넘을 수 없다.
 * 파싱 실패 예외 메시지에는 원문을 넣지 않는다(비밀번호가 들어 있을 수 있음).
 */
public final class ControlCodec {

    /** 한 메시지 최대 크기. 요청은 짧고 응답도 계좌 몇 개분이다. */
    public static final int MAX_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private ControlCodec() {}

    public static void write(SocketChannel channel, Object message) throws IOException {
        byte[] body = JSON.writeValueAsBytes(message);
        if (body.length + 1 > MAX_BYTES) {
            throw new IOException("메시지가 너무 큽니다");
        }
        ByteBuffer buffer = ByteBuffer.allocate(body.length + 1);
        buffer.put(body).put((byte) '\n').flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    public static <T> T read(SocketChannel channel, Class<T> type) throws IOException {
        byte[] line = readLine(channel);
        try {
            return JSON.readValue(line, type);
        } catch (JsonProcessingException e) {
            throw new MalformedMessageException();
        }
    }

    private static byte[] readLine(SocketChannel channel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        ByteBuffer one = ByteBuffer.allocate(1024);
        while (true) {
            one.clear();
            int n = channel.read(one);
            if (n < 0) {
                throw new IOException("메시지 끝 전에 연결이 닫혔습니다");
            }
            for (int i = 0; i < n; i++) {
                byte b = one.get(i);
                if (b == '\n') {
                    return out.toByteArray();
                }
                out.write(b);
            }
            if (out.size() >= MAX_BYTES) {
                throw new MalformedMessageException();
            }
        }
    }

    static String toJson(Object message) throws IOException {
        return new String(JSON.writeValueAsBytes(message), StandardCharsets.UTF_8);
    }

    /** 형식이 틀렸거나 너무 큰 메시지. 원문은 담지 않는다. */
    public static final class MalformedMessageException extends IOException {
        private static final long serialVersionUID = 1L;

        public MalformedMessageException() {
            super("명령 형식이 올바르지 않습니다");
        }
    }
}
