package com.autotrade.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/**
 * UI 변경을 디스크에 남기는 저장소(write-through). 같은 폴더에 임시 파일을 쓰고 원자적으로 바꿔
 * 저장 중 전원이 꺼져도 깨진 파일이 남지 않게 한다. 파일에는 비밀값이 평문으로 들어가므로
 * 저장소 밖(사용자 홈 아래)에 두고, POSIX 파일 시스템이면 소유자만 읽게 한다.
 */
public final class RuntimeConfigStore {

    private final Path file;
    private final ObjectMapper mapper = ConfigJson.mapper();

    public RuntimeConfigStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
    }

    /** 기본 위치: 시스템 속성 {@code autotrade.config} → 환경 변수 {@code AUTOTRADE_CONFIG} → ~/.autotrade/runtime-config.json. */
    public static Path defaultLocation() {
        String prop = System.getProperty("autotrade.config");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("AUTOTRADE_CONFIG");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".autotrade", "runtime-config.json");
    }

    public Path file() {
        return file;
    }

    public void save(RuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        try {
            Path dir = file.getParent();
            createOwnerOnlyDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".runtime-config", ".tmp");
            try {
                restrictToOwner(tmp);
                mapper.writeValue(tmp.toFile(), config);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("설정 저장 실패: " + file.getFileName(), e);
        }
    }

    private static void createOwnerOnlyDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            // Windows: 사용자 홈 아래 기본 ACL에 맡긴다. 암호화 저장(DPAPI 등)은 7단계 전 risk-ops 검토 항목(ADR-001 4장)
            Files.createDirectories(dir);
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
