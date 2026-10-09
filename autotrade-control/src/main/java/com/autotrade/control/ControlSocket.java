package com.autotrade.control;

import com.autotrade.config.ConfigPaths;
import java.nio.file.Path;

/**
 * 명령 채널 소켓 위치(ADR-006 2.2). 설정 폴더 아래 {@code run/engine-control.sock}(Unix 도메인 소켓).
 * 네트워크 포트를 열지 않으므로 같은 PC의 다른 사용자나 원격에서는 접근할 수 없다. 폴더 권한은 엔진이 소유자 전용으로 만든다.
 */
public final class ControlSocket {

    public static final String DIR = "run";
    public static final String FILE = "engine-control.sock";

    private ControlSocket() {}

    public static Path path(ConfigPaths paths) {
        Path dir = paths.runtimeFile().toAbsolutePath().getParent();
        return dir.resolve(DIR).resolve(FILE);
    }
}
