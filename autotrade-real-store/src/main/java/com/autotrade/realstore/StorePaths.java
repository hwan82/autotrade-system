package com.autotrade.realstore;

import com.autotrade.domain.account.AccountId;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 원장 폴더 구조(ADR-015 2.3). {@code <설정 폴더>/engine-store/} 아래에 둔다. 폴더는 POSIX에서 700, 파일은 600으로 만들고,
 * 폴더나 파일이 심볼릭 링크면 쓰지 않는다(ADR-006 2.2와 같은 규칙). Windows는 사용자 프로필 기본 ACL에 기댄다 {@code [가정]}.
 */
public final class StorePaths {

    public static final String ROOT = "engine-store";
    public static final String WRITER_LOCK = "writer.lock";
    public static final String DEGRADED = "degraded";

    static final String BOOT_STREAM = "boot";
    static final String COMMANDS_STREAM = "audit/commands";
    static final String BLACKLIST_STREAM = "blacklist";
    static final String AUDIT = "audit";
    static final String ORDERS = "orders";
    static final String RISK = "risk";

    private final Path root;

    private StorePaths(Path root) {
        this.root = root;
    }

    /** {@code configDir}는 {@code runtime-config.json}이 있는 설정 폴더. */
    public static StorePaths in(Path configDir) {
        return new StorePaths(configDir.resolve(ROOT));
    }

    public Path root() {
        return root;
    }

    Path writerLock() {
        return root.resolve(WRITER_LOCK);
    }

    Path bootFile() {
        return root.resolve("boot").resolve("boot.jsonl");
    }

    Path commandsDir() {
        return root.resolve("audit").resolve("commands");
    }

    Path blacklistFile() {
        return root.resolve("blacklist").resolve("blacklist.jsonl");
    }

    Path account(AccountId id) {
        return root.resolve("accounts").resolve(id.value());
    }

    Path auditDir(AccountId id) {
        return account(id).resolve(AUDIT);
    }

    Path ordersDir(AccountId id) {
        return account(id).resolve(ORDERS);
    }

    Path riskFile(AccountId id) {
        return account(id).resolve(RISK).resolve("risk.jsonl");
    }

    Path degraded(AccountId id) {
        return account(id).resolve(DEGRADED);
    }

    /** 계좌 원장 이름(줄의 {@code stream} 칸). */
    static String accountStream(AccountId id, String kind) {
        return "accounts/" + id.value() + "/" + kind;
    }

    /**
     * 폴더를 만들고(이미 있으면 그대로) 권한을 좁힌다. {@code root}부터 {@code dir}까지 어느 단계든 심볼릭 링크면 거부한다.
     *
     * @throws IOException 심볼릭 링크, 폴더가 아닌 것이 있음, 만들기 실패
     */
    static void prepareDir(Path root, Path dir) throws IOException {
        Path rel = root.relativize(dir);
        Path cur = root;
        checkDir(cur);
        for (Path part : rel) {
            if (part.toString().isEmpty()) {
                continue;
            }
            cur = cur.resolve(part);
            checkDir(cur);
        }
    }

    private static void checkDir(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("엔진 저장소 폴더가 심볼릭 링크라 쓰지 않습니다: " + dir.getFileName());
        }
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (posix) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(dir);
            }
        } else if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("엔진 저장소 경로가 폴더가 아닙니다: " + dir.getFileName());
        }
        if (posix) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    static void restrictFile(Path file) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
