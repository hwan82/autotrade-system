package com.autotrade.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 설정 파일 위치. 일반 설정({@code runtime-config.json})과 비밀값({@code secrets.json})을 나눠 둔다(CFG-03).
 * 둘 다 저장소 밖(기본 ~/.autotrade/)에 두고, 저장소의 .gitignore도 두 이름을 막는다.
 */
public record ConfigPaths(Path runtimeFile, Path secretsFile) {

    public static final String RUNTIME_FILE = "runtime-config.json";
    public static final String SECRETS_FILE = "secrets.json";

    public ConfigPaths {
        runtimeFile = Objects.requireNonNull(runtimeFile, "runtimeFile").toAbsolutePath();
        secretsFile = Objects.requireNonNull(secretsFile, "secretsFile").toAbsolutePath();
        if (runtimeFile.equals(secretsFile)) {
            throw new IllegalArgumentException("일반 설정과 비밀값은 다른 파일이어야 합니다");
        }
    }

    public static ConfigPaths in(Path dir) {
        return new ConfigPaths(dir.resolve(RUNTIME_FILE), dir.resolve(SECRETS_FILE));
    }

    /** 기본 폴더: 시스템 속성 {@code autotrade.config-dir} → 환경 변수 {@code AUTOTRADE_CONFIG_DIR} → ~/.autotrade. */
    public static ConfigPaths defaultLocation() {
        String prop = System.getProperty("autotrade.config-dir");
        if (prop != null && !prop.isBlank()) {
            return in(Path.of(prop));
        }
        String env = System.getenv("AUTOTRADE_CONFIG_DIR");
        if (env != null && !env.isBlank()) {
            return in(Path.of(env));
        }
        return in(Path.of(System.getProperty("user.home"), ".autotrade"));
    }
}
