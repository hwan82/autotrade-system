package com.autotrade.archfixture;

import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlServer;
import com.autotrade.control.ControlResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 규칙 위반 픽스처: 실전 엔진이 아닌 곳에서 명령 채널 서버를 열고, 실험실이 아닌 곳에서 클라이언트로 실전 엔진에 명령을 보낸다.
 * 프로덕션 코드가 아니며 {@code ArchitectureTest}가 규칙이 이를 잡는지 확인하는 데만 쓴다.
 */
public final class MisplacedControlChannel {

    private MisplacedControlChannel() {}

    static ControlServer openServer(Path socket) throws java.io.IOException {
        return ControlServer.start(socket, request -> ControlResponse.rejected("X", "", null));
    }

    static ControlResponse sendPanic(Path socket) throws ControlClient.EngineUnreachableException {
        try (ControlClient client = new ControlClient(socket, Duration.ofSeconds(1))) {
            return client.send(ControlRequest.panicAll("x", "y"));
        }
    }
}
