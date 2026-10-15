package com.autotrade.monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 읽는 쪽이 반쯤 쓴 내용을 보지 않도록 같은 폴더 임시 파일에 쓴 뒤 옮긴다(하트비트, 감시의 정지 요청 파일). */
public final class AtomicFile {

    private AtomicFile() {}

    /** 폴더가 없으면 만든다. 원자적 이동을 못 하는 파일 시스템이면 덮어쓰기 이동으로 대신한다. */
    public static void write(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        // 같은 대상을 두 프로세스가 쓰더라도 임시 파일이 겹치지 않게 PID를 붙인다(architect PR #116 R5)
        Path tmp = dir.resolve(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
