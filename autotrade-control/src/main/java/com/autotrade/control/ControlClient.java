package com.autotrade.control;

import java.io.IOException;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 실험실 쪽 명령 채널 클라이언트. 연결 하나에 요청 하나를 보내고 응답을 기다린다.
 * 엔진에 닿지 못하면 {@link EngineUnreachableException}. 이유는 둘로 나눈다(ADR-006 2.6).
 * <ul>
 *   <li>{@link Reach#NOT_RUNNING}: 소켓이 없거나 연결이 거절됐고, 잠금 파일이 없거나 거기 적힌 엔진 PID의 프로세스가 없음
 *       (재기동하면 모든 계좌 OFF).</li>
 *   <li>{@link Reach#UNKNOWN}: 그 밖의 모든 경우. 연결은 됐지만 제때 답이 없거나 끊김, 소켓이 없는데 엔진 PID는 살아 있음,
 *       잠금 파일을 읽지 못함. 엔진은 살아 있을 수 있고 실전 계좌가 열려 있을 수 있다. 화면은 "가상"으로 바꾸지 않고
 *       마지막으로 확인한 상태를 경고와 함께 보여 줘야 한다.</li>
 * </ul>
 * 어느 쪽이든 명령이 반영됐는지는 알 수 없으므로 호출자는 상태 조회로 확인한다(재전송하지 않는다, 지침 4장 2번과 같은 원칙).
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
        return send(request, timeout);
    }

    /** 제한 시간을 따로 정해 보낸다(상태 조회는 짧게). */
    public ControlResponse send(ControlRequest request, Duration limit) throws EngineUnreachableException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(limit, "limit");
        if (!Files.exists(socket)) {
            throw notRunningOrUnknown();
        }
        AtomicReference<SocketChannel> holder = new AtomicReference<>();
        Future<ControlResponse> call = io.submit(() -> {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                holder.set(channel);
                channel.connect(UnixDomainSocketAddress.of(socket));
                ControlCodec.write(channel, request);
                return ControlCodec.readResponse(channel);
            }
        });
        try {
            return call.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            closeQuietly(holder.get());
            throw new EngineUnreachableException(Reach.UNKNOWN, "실전 엔진이 제때 응답하지 않았습니다");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException || cause instanceof NoSuchFileException) {
                // 소켓 파일은 있지만 듣는 쪽이 없다(비정상 종료로 남은 소켓, 또는 엔진은 살아 있는데 소켓이 바뀜)
                throw notRunningOrUnknown();
            }
            throw new EngineUnreachableException(Reach.UNKNOWN, "실전 엔진과 통신하지 못했습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineUnreachableException(Reach.UNKNOWN, "요청이 중단됐습니다");
        }
    }

    /**
     * 소켓에 닿지 못했을 때 엔진 프로세스가 정말 없는지 본다. 잠금 파일({@code engine.lock})에 적힌 PID의 프로세스가
     * 살아 있으면 엔진이 살아 있는데 소켓만 없어진 것일 수 있으므로 UNKNOWN으로 본다(가상으로 보이면 안 됨).
     * 잠금 파일이 없을 때만 NOT_RUNNING이다. 파일은 있는데 읽지 못하거나(Windows는 엔진이 잡은 잠금 때문에 읽기가 막힐 수
     * 있음) 내용이 PID가 아니면 판단할 수 없으므로 UNKNOWN이다. 다른 프로세스가 같은 PID를 다시 쓰면 UNKNOWN이 되는데,
     * 확인할 수 없을 때는 모두 안전한 쪽(가상으로 보이지 않음)으로 둔다.
     */
    private EngineUnreachableException notRunningOrUnknown() {
        Path lockFile = socket.toAbsolutePath().getParent().resolve(ControlServer.LOCK_FILE);
        long pid;
        try {
            pid = Long.parseLong(Files.readString(lockFile, StandardCharsets.US_ASCII).strip());
        } catch (NoSuchFileException e) {
            // 이 설정 폴더로 엔진이 한 번도 뜨지 않았다
            return new EngineUnreachableException(Reach.NOT_RUNNING, "실전 엔진이 실행 중이 아닙니다");
        } catch (IOException | NumberFormatException e) {
            return new EngineUnreachableException(Reach.UNKNOWN,
                    "실전 엔진 명령 채널에 닿지 못했고 엔진 프로세스가 있는지도 확인하지 못했습니다");
        }
        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            return new EngineUnreachableException(Reach.UNKNOWN,
                    "실전 엔진 프로세스는 살아 있지만 명령 채널에 닿지 못했습니다");
        }
        return new EngineUnreachableException(Reach.NOT_RUNNING, "실전 엔진이 실행 중이 아닙니다");
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

    /** 엔진에 닿지 못한 이유. */
    public enum Reach { NOT_RUNNING, UNKNOWN }

    /** 엔진에 닿지 못함. 명령 반영 여부는 모른다. */
    public static final class EngineUnreachableException extends Exception {
        private static final long serialVersionUID = 1L;

        private final Reach reach;

        public EngineUnreachableException(Reach reach, String message) {
            super(message);
            this.reach = Objects.requireNonNull(reach, "reach");
        }

        public Reach reach() {
            return reach;
        }
    }
}
