package com.autotrade.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 갱신한 카카오 토큰을 설정 폴더의 파일 하나에 남긴다(D-100). 같은 설정 폴더를 쓰는 실전 엔진과 감시가 이 파일을 함께 읽고 쓴다.
 *
 * <ul>
 *   <li><b>출처</b>: 파일에 사용자가 {@code secrets.json}에 넣은 리프레시 토큰의 SHA-256을 함께 적는다. 사용자가 다시 동의해 새
 *       리프레시 토큰을 넣으면 출처가 달라지므로 옛 파일은 무시한다(거절된 옛 토큰을 계속 쓰지 않게).</li>
 *   <li><b>쓰기</b>: 같은 폴더의 임시 파일에 쓰고 원자적 이동으로 바꾼다. 두 프로세스가 동시에 쓰면 나중 것이 남는다. 둘 다 유효한
 *       토큰이라 문제 삼지 않는다. POSIX 파일 시스템이면 소유자만 읽고 쓰게 한다.</li>
 *   <li><b>읽기</b>: {@value #MAX_BYTES}바이트까지만 읽고, 없거나 틀리거나 출처가 다르면 빈 값이다(예외를 던지지 않는다).</li>
 * </ul>
 *
 * <p>파일에는 액세스·리프레시 토큰이 들어 있으므로 {@code secrets.json}과 같은 비밀값이다. 값은 로그·예외 문구·{@code toString}에
 * 나오지 않는다.
 */
public final class FileKakaoTokenStore implements KakaoTokenStore {

    /** 설정 폴더 안 기본 위치. */
    public static final String DEFAULT_FILE = "notification-state/kakao-token.json";
    static final int MAX_BYTES = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final String source;

    /**
     * @param file 토큰 파일
     * @param configRefreshToken {@code secrets.json}의 리프레시 토큰(출처 판정용, 값은 남기지 않고 해시만 쓴다)
     */
    public FileKakaoTokenStore(Path file, String configRefreshToken) {
        this.file = Objects.requireNonNull(file, "file");
        Objects.requireNonNull(configRefreshToken, "configRefreshToken");
        this.source = sha256(configRefreshToken.strip());
    }

    /** 처음 쓸 토큰: 같은 출처의 파일이 있으면 그 토큰, 없으면 설정의 토큰. */
    public KakaoToken initial(KakaoToken fromConfig) {
        Objects.requireNonNull(fromConfig, "fromConfig");
        return latest().orElse(fromConfig);
    }

    @Override
    public void save(KakaoToken token) throws IOException {
        Objects.requireNonNull(token, "token");
        ObjectNode n = JSON.createObjectNode();
        n.put("source", source);
        n.put("access-token", token.accessToken());
        n.put("refresh-token", token.refreshToken());
        n.put("access-expires-at", token.accessExpiresAt().toString());
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        boolean moved = false;
        try {
            Files.deleteIfExists(tmp);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            Files.writeString(tmp, n.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Override
    public Optional<KakaoToken> latest() {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (bytes.length > MAX_BYTES) {
            return Optional.empty();
        }
        try {
            JsonNode n = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (n == null || !source.equals(n.path("source").asText(""))) {
                return Optional.empty();
            }
            return Optional.of(new KakaoToken(n.path("access-token").asText(""), n.path("refresh-token").asText(""),
                    Instant.parse(n.path("access-expires-at").asText(""))));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음");
        }
    }

    @Override
    public String toString() {
        return "FileKakaoTokenStore[" + file.getFileName() + "]";
    }
}
