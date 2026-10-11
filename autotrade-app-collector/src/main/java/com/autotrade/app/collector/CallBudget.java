package com.autotrade.app.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 소스별 하루 호출 상한(MD-11 "일일 상한 초과 0건", D-050 ① KRX 하루 10,000회). 하루는 Asia/Seoul 날짜다.
 * 호출 수를 {@code <설정 폴더>/collector-state/budget/<source>-<날짜>.json}에 호출마다 기록해 같은 날 여러 번 실행해도
 * (캐시 폴더를 바꿔도) 합산한다.
 * 한도 초과 응답(OpenDART 020)을 받으면 {@link #stopForToday()}로 그날 남은 실행을 모두 막는다.
 */
public final class CallBudget {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final String source;
    private final int dailyCap;
    private final Clock clock;

    public CallBudget(Path cacheDir, String source, int dailyCap, Clock clock) {
        if (dailyCap <= 0) {
            throw new IllegalArgumentException("하루 호출 상한은 1 이상이어야 합니다");
        }
        this.dir = cacheDir.resolve("budget");
        this.source = source;
        this.dailyCap = dailyCap;
        this.clock = clock;
    }

    /** 호출 한 번을 쓴다. 상한에 닿았거나 그날 멈춤이 기록돼 있으면 {@link BudgetExhausted}. */
    public void acquire() {
        locked(() -> {
            State s = read();
            if (s.stopped) {
                throw new BudgetExhausted(source + ": 오늘은 한도 초과 응답을 받아 수집을 멈췄습니다");
            }
            if (s.calls >= dailyCap) {
                throw new BudgetExhausted(source + ": 하루 호출 상한 " + dailyCap + "회에 닿았습니다");
            }
            write(new State(s.calls + 1, false));
        });
    }

    /** 한도 초과 응답을 받았을 때. 그날 이 소스 호출을 더 하지 않는다. */
    public void stopForToday() {
        locked(() -> write(new State(read().calls, true)));
    }

    /** 파일 잠금은 프로세스 단위라 같은 JVM 안의 스레드끼리는 이 잠금으로 순서를 맞춘다. */
    private static final Object IN_PROCESS = new Object();

    /** 같은 소스 명령을 동시에 돌려도 읽기-쓰기 사이에 합산이 빠지지 않게 파일 잠금 안에서 한다. */
    private void locked(Runnable action) {
        synchronized (IN_PROCESS) {
            lockedFile(action);
        }
    }

    private void lockedFile(Runnable action) {
        try {
            Files.createDirectories(dir);
            try (FileChannel ch = FileChannel.open(dir.resolve(source + ".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                FileLock lock = ch.lock();
                try {
                    action.run();
                } finally {
                    lock.release();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("호출 기록 잠금을 얻을 수 없습니다", e);
        }
    }

    public int usedToday() {
        return read().calls;
    }

    private Path file() {
        return dir.resolve(source + "-" + LocalDate.now(clock.withZone(SEOUL)) + ".json");
    }

    private State read() {
        Path f = file();
        if (!Files.exists(f)) {
            return new State(0, false);
        }
        try {
            var n = JSON.readTree(f.toFile());
            return new State(n.path("calls").asInt(0), n.path("stopped").asBoolean(false));
        } catch (IOException e) {
            throw new UncheckedIOException("호출 기록을 읽을 수 없습니다: " + f, e);
        }
    }

    private void write(State s) {
        try {
            Files.createDirectories(dir);
            ObjectNode n = JSON.createObjectNode().put("calls", s.calls).put("stopped", s.stopped);
            Path tmp = dir.resolve(file().getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), n);
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("호출 기록을 쓸 수 없습니다", e);
        }
    }

    private record State(int calls, boolean stopped) {}

    /** 상한 도달. 그날 수집을 멈추고 다음 날 이어 받는다. */
    public static final class BudgetExhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExhausted(String message) {
            super(message);
        }
    }
}
