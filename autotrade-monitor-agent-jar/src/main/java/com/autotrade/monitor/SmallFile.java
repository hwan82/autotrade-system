package com.autotrade.monitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 감시가 읽고 쓰는 작은 파일(하트비트, 정지 요청 파일, 감시 설정)을 다룬다. 크기 상한 없이 읽으면 비정상으로 커진 파일 하나가
 * 감시 힙을 채워 기동을 막을 수 있어 상한을 둔다(PR #116 독립 QA R1, PR #37 F1과 같은 원칙).
 */
public final class SmallFile {

    /** 감시 파일 읽기 상한. 하트비트·정지 요청 파일은 수백 바이트, 감시 설정은 몇 줄이다. */
    public static final long MAX_BYTES = 4 * 1024;

    private SmallFile() {}

    /** UTF-8로 읽는다. 상한보다 크면 읽지 않고 {@link IOException}을 던진다. */
    public static String read(Path file) throws IOException {
        // 크기를 따로 본 뒤 읽으면 그 사이 커진 파일을 다 읽는다. 상한+1바이트까지만 한 번에 읽는다(PR #118 architect N1·risk-ops R1)
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("파일이 너무 큽니다(상한 " + MAX_BYTES + "바이트 초과): " + file);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * {@link AtomicFile}이 쓰다 프로세스가 죽어 남은 임시 파일({@code <이름>.<pid>.tmp})을 지운다. 지운 개수를 돌려준다. 폴더가 없거나
     * 지우지 못한 파일은 건너뛴다(예외를 던지지 않는다).
     */
    public static int deleteLeftoverTemps(Path target) {
        int deleted = 0;
        try {
            // toAbsolutePath는 IOError를 낼 수 있어 쓰지 않는다(상대 경로도 부모 폴더로 충분하다). 이 정리가 실전 엔진 기동을 막지 않게
            // 한다(PR #118 architect N2)
            Path dir = target.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                return 0;
            }
            deleted = deleteTemps(dir, target.getFileName() + ".");
        } catch (RuntimeException e) {
            // 정리하지 않고 넘어간다
        }
        return deleted;
    }

    private static int deleteTemps(Path dir, String prefix) {
        int deleted = 0;
        try (DirectoryStream<Path> temps = Files.newDirectoryStream(dir, prefix + "*.tmp")) {
            for (Path tmp : temps) {
                String middle = tmp.getFileName().toString();
                middle = middle.substring(prefix.length(), middle.length() - ".tmp".length());
                if (!middle.isEmpty() && middle.chars().allMatch(Character::isDigit)) {
                    try {
                        if (Files.deleteIfExists(tmp)) {
                            deleted++;
                        }
                    } catch (IOException | RuntimeException e) {
                        // 다음 기동에 다시 시도한다
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 폴더를 읽지 못하면 정리하지 않는다
        }
        return deleted;
    }
}
