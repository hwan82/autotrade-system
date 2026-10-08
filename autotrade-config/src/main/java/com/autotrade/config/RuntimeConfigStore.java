package com.autotrade.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * UI 변경을 디스크에 남기는 저장소(write-through). 일반 설정은 runtime-config.json, 비밀값은 secrets.json으로 나눠 쓴다.
 * 각 파일은 같은 폴더에 임시 파일을 쓰고 원자적으로 바꿔, 저장 중 전원이 꺼져도 깨진 파일이 남지 않는다.
 * POSIX 파일 시스템이면 폴더는 700, 파일은 600이다.
 *
 * <p>두 파일을 한 번에 원자적으로 바꿀 수는 없다. 비밀값을 먼저 쓰고 일반 설정을 나중에 쓴다. 중간에 실패하면
 * 예외가 나고 {@link RuntimeConfigHolder}는 메모리를 바꾸지 않는다. 이때 디스크의 비밀 파일만 새 값일 수 있다.
 */
public final class RuntimeConfigStore {

    private final ConfigPaths paths;
    private final ObjectMapper mapper = ConfigJson.mapper();

    public RuntimeConfigStore(ConfigPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public ConfigPaths paths() {
        return paths;
    }

    public void save(RuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        ObjectNode full = mapper.valueToTree(config);
        ObjectNode secrets = SecretPaths.extract(full);
        write(paths.secretsFile(), secrets);
        write(paths.runtimeFile(), full);
    }

    private void write(Path file, JsonNode tree) {
        try {
            Path dir = file.getParent();
            createOwnerOnlyDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
            try {
                restrictToOwner(tmp);
                mapper.writeValue(tmp.toFile(), tree);
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

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void createOwnerOnlyDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        if (posix()) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            // Windows: 사용자 홈 아래 기본 ACL에 맡긴다. 암호화 저장(DPAPI 등)은 7단계 전 risk-ops 검토 항목(ADR-001 4장)
            Files.createDirectories(dir);
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        if (posix()) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
