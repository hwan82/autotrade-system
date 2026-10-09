package com.autotrade.control;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 실험실 쪽 명령 채널 클라이언트. 연결 하나에 요청 하나를 보내고 응답을 기다린다.
 * 엔진이 꺼져 있거나 응답이 없으면 {@link EngineUnreachableException}. 이때 명령이 반영됐는지는 알 수 없으므로
 * 호출자는 상태 조회로 확인한다(재전송하지 않는다, 지침 4장 2번과 같은 원칙).
 */
public final class ControlClient implements AutoCloseable {

    private final Path socket;
    private final Duration timeout;
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();

    public ControlClient(Path socket, Duration timeout) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public ControlResponse send(ControlRequest request) throws EngineUnreachableException {
        Objects.requireNonNull(request, "request");
        if (!Files.exists(socket)) {
            throw new EngineUnreachableException("실전 엔진이 실행 중이 아닙니다");
        }
        SocketChannel[] holder = new SocketChannel[1];
        Future<ControlResponse> call = io.submit(() -> {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                holder[0] = channel;
                channel.connect(UnixDomainSocketAddress.of(socket));
                ControlCodec.write(channel, request);
                return ControlCodec.read(channel, ControlResponse.class);
            }
        });
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            closeQuietly(holder[0]);
            throw new EngineUnreachableException("실전 엔진이 제때 응답하지 않았습니다");
        } catch (ExecutionException e) {
            throw new EngineUnreachableException("실전 엔진에 연결할 수 없습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineUnreachableException("요청이 중단됐습니다");
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
    }

    @Override
    public void close() {
        io.shutdownNow();
    }

    /** 엔진에 닿지 못함. 명령 반영 여부는 모른다. */
    public static final class EngineUnreachableException extends Exception {
        private static final long serialVersionUID = 1L;

        public EngineUnreachableException(String message) {
            super(message);
        }
    }
}
