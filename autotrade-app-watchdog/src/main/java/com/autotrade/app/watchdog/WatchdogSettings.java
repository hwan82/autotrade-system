package com.autotrade.app.watchdog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 감시 프로세스 설정(D-092). {@code <설정 폴더>/watchdog.properties}의 {@code 키=값} 줄({@code #} 주석)에서 읽고, 파일이 없으면
 * 기본값을 쓴다.
 *
 * <p><b>틀린 설정은 기동을 거부한다</b>(PR #115 risk-ops K2): 모르는 키(오타), 중복 키, 형식·범위 오류, 없는 드라이브 경로가
 * 하나라도 있으면 문제를 모두 모아 {@link IllegalArgumentException}을 던지고, 진입점은 종료 코드 2로 끝난다. 실험실 모니터링처럼
 * "사용 불가"로 두고 뜨면 감시가 도는 것처럼 보이면서 아무것도 지키지 않게 되기 때문이다. 일반 설정({@code runtime-config.json})은
 * 틀리면 경고 뒤 기본값으로 뜨는 체계라 감시 설정을 그 파일에 두지 않았다.
 *
 * @param interval 점검 주기
 * @param heartbeatStale 하트비트가 이보다 오래되면 "멈춤"
 * @param processes 하트비트를 볼 프로세스
 * @param disks 여유 공간을 볼 경로(드라이브마다 하나면 된다)
 * @param diskWarnGb 여유 GB 경고 경계(이하)
 * @param diskCriticalGb 여유 GB 위험 경계(이하). 위험이면 정지 요청 파일을 만든다
 * @param diskSustain 수준을 바꾸는 데 필요한 연속 판정 수
 * @param releaseHold 정지 요청 파일을 지우려면 모든 드라이브가 경고 경계 위로 이만큼 이어져야 함
 */
record WatchdogSettings(Duration interval, Duration heartbeatStale, List<String> processes, List<Path> disks,
        double diskWarnGb, double diskCriticalGb, int diskSustain, Duration releaseHold) {

    static final String FILE = "watchdog.properties";

    static final String INTERVAL = "interval-seconds";
    static final String HEARTBEAT_STALE = "heartbeat-stale-seconds";
    static final String PROCESSES = "processes";
    static final String DISKS = "disks";
    static final String DISK_FREE_GB = "disk.free-gb";
    static final String RELEASE_HOLD = "release-hold-seconds";
    static final Set<String> KEYS = Set.of(INTERVAL, HEARTBEAT_STALE, PROCESSES, DISKS, DISK_FREE_GB, RELEASE_HOLD);

    /** 하트비트를 볼 수 있는 프로세스(감시 자신은 빼고). */
    static final List<String> KNOWN_PROCESSES = List.of("engine", "gateway", "lab");

    static final int DEFAULT_INTERVAL_SECONDS = 5;
    static final int DEFAULT_HEARTBEAT_STALE_SECONDS = 15;
    /** 기본 임계 [가정]: 실험실 모니터링 기본(D-090 5번)과 같은 50/20GB, 1회. 20GB는 실시간 모의 입력 기록 하한(D-076)과 같다. */
    static final String DEFAULT_DISK_FREE_GB = "50:20:1";
    /** 해제 유지 시간 [가정]: ADR-007 2.7의 5분. */
    static final int DEFAULT_RELEASE_HOLD_SECONDS = 300;

    WatchdogSettings {
        processes = List.copyOf(processes);
        disks = List.copyOf(disks);
    }

    /** 설정 폴더의 {@value #FILE}를 읽는다. 없으면 기본값(드라이브는 설정 폴더 하나). */
    static WatchdogSettings load(Path configDir) {
        Path file = configDir.resolve(FILE);
        if (!Files.exists(file)) {
            return parse("", configDir);
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), configDir);
        } catch (IOException e) {
            throw new IllegalArgumentException("감시 설정 파일을 읽지 못했습니다: " + file + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    static WatchdogSettings parse(String text, Path configDir) {
        List<String> problems = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                problems.add((i + 1) + "번째 줄: 키=값 형식이 아닙니다");
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (!KEYS.contains(key)) {
                problems.add((i + 1) + "번째 줄: 모르는 키입니다(오타?): " + key);
            } else if (values.containsKey(key)) {
                problems.add((i + 1) + "번째 줄: 같은 키가 두 번 있습니다: " + key);
            } else {
                values.put(key, value);
            }
        }

        int interval = integer(values, INTERVAL, DEFAULT_INTERVAL_SECONDS, 1, 60, problems);
        int stale = integer(values, HEARTBEAT_STALE, DEFAULT_HEARTBEAT_STALE_SECONDS, 10, 600, problems);
        if (stale < interval) {
            problems.add(HEARTBEAT_STALE + "(" + stale + ")는 " + INTERVAL + "(" + interval + ") 이상이어야 합니다");
        }
        int hold = integer(values, RELEASE_HOLD, DEFAULT_RELEASE_HOLD_SECONDS, 0, 3600, problems);
        List<String> processes = processes(values.getOrDefault(PROCESSES, String.join(",", KNOWN_PROCESSES)), problems);
        List<Path> disks = disks(values.get(DISKS), configDir, problems);

        double warn = 0;
        double critical = 0;
        int sustain = 1;
        String[] parts = values.getOrDefault(DISK_FREE_GB, DEFAULT_DISK_FREE_GB).split(":", -1);
        if (parts.length != 3) {
            problems.add(DISK_FREE_GB + "는 경고GB:위험GB:연속 형식입니다(예: " + DEFAULT_DISK_FREE_GB + ")");
        } else {
            try {
                warn = new BigDecimal(parts[0].strip()).doubleValue();
                critical = new BigDecimal(parts[1].strip()).doubleValue();
                sustain = Integer.parseInt(parts[2].strip());
                if (critical <= 0 || warn < critical || warn > 100_000) {
                    problems.add(DISK_FREE_GB + ": 0 < 위험 ≤ 경고 ≤ 100000이어야 합니다");
                }
                if (sustain < 1 || sustain > 10) {
                    problems.add(DISK_FREE_GB + ": 연속은 1~10입니다");
                }
            } catch (NumberFormatException e) {
                problems.add(DISK_FREE_GB + ": 숫자가 아닙니다: " + values.get(DISK_FREE_GB));
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("감시 설정 오류(" + configDir.resolve(FILE) + "): " + String.join("; ", problems));
        }
        return new WatchdogSettings(Duration.ofSeconds(interval), Duration.ofSeconds(stale), processes, disks, warn, critical,
                sustain, Duration.ofSeconds(hold));
    }

    private static int integer(Map<String, String> values, String key, int fallback, int min, int max, List<String> problems) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw);
            if (v < min || v > max) {
                problems.add(key + "는 " + min + "~" + max + "입니다: " + raw);
                return fallback;
            }
            return v;
        } catch (NumberFormatException e) {
            problems.add(key + "가 정수가 아닙니다: " + raw);
            return fallback;
        }
    }

    private static List<String> processes(String raw, List<String> problems) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String p : raw.split(",")) {
            String name = p.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!KNOWN_PROCESSES.contains(name)) {
                problems.add(PROCESSES + ": 모르는 프로세스입니다(" + String.join(",", KNOWN_PROCESSES) + " 중에서): " + name);
            } else if (seen.add(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static List<Path> disks(String raw, Path configDir, List<String> problems) {
        List<Path> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            out.add(configDir.toAbsolutePath());
        } else {
            for (String p : raw.split(",")) {
                String s = p.strip();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    out.add(Path.of(s).toAbsolutePath());
                } catch (InvalidPathException e) {
                    problems.add(DISKS + ": 경로 형식이 틀렸습니다: " + s);
                }
            }
            if (out.isEmpty()) {
                problems.add(DISKS + "가 비었습니다(빼면 설정 폴더 드라이브)");
            }
        }
        for (Path d : out) {
            if (!Files.isDirectory(d)) {
                problems.add(DISKS + ": 없는 폴더입니다: " + d);
            }
        }
        return out;
    }
}
