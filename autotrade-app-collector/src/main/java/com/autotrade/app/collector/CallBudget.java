package com.autotrade.app.collector;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import java.util.TreeMap;

/**
 * 소스별 하루 호출 상한(MD-11 "일일 상한 초과 0건", D-050 ① KRX 하루 10,000회). 하루는 Asia/Seoul 날짜다.
 * 호출 수를 {@code <설정 폴더>/collector-state/budget/<source>-<날짜>.json}에 호출마다 기록해 같은 날 여러 번 실행해도
 * (캐시 폴더를 바꿔도) 합산한다.
 * 한도 초과 응답(OpenDART 020)을 받으면 {@link #stopForToday()}로 그날 남은 실행을 모두 막는다.
 *
 * <p><b>몫(lane, D-123)</b>: 같은 키를 쓰는 단계가 둘 이상이면(OpenDART 재무 {@value #MAIN}, 공시 {@code disclosures}) 단계마다
 * 자기 몫 상한을 따로 세고, 키 전체 합은 키 상한으로 따로 막는다. 그래서 공시 단계가 먼저 돌거나 오래 돌아도 재무 단계의 몫을
 * 깎지 않는다. 몫을 적지 않은 예전 기록의 호출 수는 {@value #MAIN} 몫으로 본다.
 * <pre>{"calls":10500,"stopped":false,"lanes":{"main":10000,"disclosures":500}}</pre>
 */
public final class CallBudget {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 기본 몫. 몫을 나누지 않는 소스(KRX)와 OpenDART 재무 단계가 쓴다. */
    public static final String MAIN = "main";

    private final Path dir;
    private final String source;
    private final String lane;
    private final int laneCap;
    private final int keyCap;
    private final Clock clock;

    /** 몫을 나누지 않는 소스: 하루 합이 {@code dailyCap}에 닿으면 막는다. */
    public CallBudget(Path cacheDir, String source, int dailyCap, Clock clock) {
        this(cacheDir, source, MAIN, dailyCap, dailyCap, clock);
    }

    /**
     * @param lane 이 단계의 몫 이름
     * @param laneCap 이 몫의 하루 상한
     * @param keyCap 같은 키 모든 몫의 하루 합 상한
     */
    public CallBudget(Path cacheDir, String source, String lane, int laneCap, int keyCap, Clock clock) {
        if (laneCap <= 0 || keyCap <= 0) {
            throw new IllegalArgumentException("하루 호출 상한은 1 이상이어야 합니다");
        }
        if (lane == null || !lane.matches("[a-z]{1,20}")) {
            throw new IllegalArgumentException("몫 이름이 틀렸습니다: " + lane);
        }
        this.dir = cacheDir.resolve("budget");
        this.source = source;
        this.lane = lane;
        this.laneCap = laneCap;
        this.keyCap = keyCap;
        this.clock = clock;
    }

    /** 호출 한 번을 쓴다. 몫·키 상한에 닿았거나 그날 멈춤이 기록돼 있으면 {@link BudgetExhausted}. */
    public void acquire() {
        locked(() -> {
            State s = read();
            if (s.stopped) {
                throw new BudgetExhausted(source + ": 오늘은 한도 초과 응답을 받아 수집을 멈췄습니다");
            }
            int mine = s.lane(lane);
            if (mine >= laneCap) {
                throw new BudgetExhausted(source + (lane.equals(MAIN) ? "" : " " + lane) + ": 하루 호출 상한 " + laneCap
                        + "회에 닿았습니다");
            }
            if (s.calls >= keyCap) {
                throw new BudgetExhausted(source + ": 같은 키의 하루 호출 합 상한 " + keyCap + "회에 닿았습니다(오늘 합 " + s.calls
                        + "회)");
            }
            Map<String, Integer> lanes = new TreeMap<>(s.lanes);
            lanes.put(lane, mine + 1);
            write(new State(s.calls + 1, false, lanes));
        });
    }

    /** 한도 초과 응답을 받았을 때. 그날 이 소스(키) 호출을 몫과 상관없이 더 하지 않는다. */
    public void stopForToday() {
        locked(() -> {
            State s = read();
            write(new State(s.calls, true, s.lanes));
        });
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

    /** 오늘 이 키의 호출 합(모든 몫). */
    public int usedToday() {
        return read().calls;
    }

    /** 오늘 이 몫의 호출 수. */
    public int usedTodayByLane() {
        return read().lane(lane);
    }

    private Path file() {
        return dir.resolve(source + "-" + LocalDate.now(clock.withZone(SEOUL)) + ".json");
    }

    private State read() {
        Path f = file();
        if (!Files.exists(f)) {
            return new State(0, false, Map.of());
        }
        try {
            var n = JSON.readTree(f.toFile());
            Map<String, Integer> lanes = new TreeMap<>();
            JsonNode ln = n.path("lanes");
            ln.fieldNames().forEachRemaining(k -> lanes.put(k, ln.path(k).asInt(0)));
            return new State(n.path("calls").asInt(0), n.path("stopped").asBoolean(false), lanes);
        } catch (IOException e) {
            throw new UncheckedIOException("호출 기록을 읽을 수 없습니다: " + f, e);
        }
    }

    private void write(State s) {
        try {
            Files.createDirectories(dir);
            ObjectNode n = JSON.createObjectNode().put("calls", s.calls).put("stopped", s.stopped);
            ObjectNode lanes = n.putObject("lanes");
            s.lanes.forEach(lanes::put);
            Path tmp = dir.resolve(file().getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), n);
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("호출 기록을 쓸 수 없습니다", e);
        }
    }

    /** {@code lanes}에 적히지 않은 호출(몫을 적기 전 기록)은 {@link #MAIN} 몫으로 센다. */
    private record State(int calls, boolean stopped, Map<String, Integer> lanes) {

        State {
            lanes = Map.copyOf(lanes);
        }

        int lane(String name) {
            int listed = lanes.values().stream().mapToInt(Integer::intValue).sum();
            int unlisted = Math.max(0, calls - listed);
            return lanes.getOrDefault(name, 0) + (name.equals(MAIN) ? unlisted : 0);
        }
    }

    /** 상한 도달. 그날 수집을 멈추고 다음 날 이어 받는다. */
    public static final class BudgetExhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExhausted(String message) {
            super(message);
        }
    }
}
