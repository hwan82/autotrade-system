package com.autotrade.app.watchdog;

import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.Reading;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 드라이브 여유 공간(GB, 2^30바이트) 수집원(D-092). 감시 런타임은 OSHI를 쓰지 않고 {@code java.lang.management}는
 * {@link PlatformOsReadings}의 CPU·메모리 읽기만 허용되므로(D-086, D-090, D-095) JDK {@code FileStore}로 읽는다. 이름은 실험실 표준 수집원과 같은 {@code disk.free-gb:<경로>}다.
 *
 * <p>한 경로를 읽지 못하면 그 경로 값만 빠진다. 감시는 값이 빠진 주기를 "판단 못 함"으로 보고 정지 요청을 풀지 않는다. 마지막 읽기에서
 * 실패한 경로의 원인(예외 종류와 메시지)은 {@link #failures()}로 남겨, 감시의 결측 경고에 함께 적는다(PR #119 risk-ops K3, D-108).
 */
final class DiskFreeSource implements MetricSource {

    static final String METRIC = "disk.free-gb";
    static final double GIB = 1024.0 * 1024.0 * 1024.0;

    /** 경로가 있는 드라이브에서 쓸 수 있는 바이트. */
    @FunctionalInterface
    interface FreeSpace {
        long usableBytes(Path path) throws IOException;
    }

    static final FreeSpace FILE_STORE = path -> Files.getFileStore(path).getUsableSpace();

    private final List<Path> paths;
    private final FreeSpace freeSpace;
    /** 마지막 읽기에서 실패한 값 이름 → 원인. 읽을 때마다 통째로 바꾼다. */
    private volatile Map<String, String> failures = Map.of();

    DiskFreeSource(List<Path> paths, FreeSpace freeSpace) {
        this.paths = List.copyOf(paths);
        this.freeSpace = freeSpace;
    }

    static String readingName(Path path) {
        return METRIC + ":" + path;
    }

    @Override
    public String id() {
        return "disk";
    }

    @Override
    public List<Reading> read() {
        // 먼저 비운다. 읽기 중 Error가 나가면 이전 점검의 원인이 남아 틀린 원인을 보일 수 있다(PR #157 risk-ops K1)
        failures = Map.of();
        List<Reading> out = new ArrayList<>();
        Map<String, String> failed = new TreeMap<>();
        for (Path p : paths) {
            try {
                out.add(new Reading(readingName(p), freeSpace.usableBytes(p) / GIB, "GB"));
            } catch (IOException | RuntimeException e) {
                // 그 경로만 뺀다(판단 못 함으로 이어짐). 원인은 감시의 결측 경고에 적는다
                failed.put(readingName(p), cause(e));
            }
        }
        failures = Map.copyOf(failed);
        return out;
    }

    /** 원인 문구 최대 길이(문자). */
    static final int CAUSE_MAX = 160;

    /** 마지막 읽기에서 실패한 값 이름과 원인. */
    Map<String, String> failures() {
        return failures;
    }

    /** 예외 종류와 메시지 한 줄(메시지는 {@value #CAUSE_MAX}자까지). 메시지는 경로 같은 OS 문구라 비밀값이 없다. */
    static String cause(Exception e) {
        String type = e.getClass().getSimpleName().isEmpty() ? e.getClass().getName() : e.getClass().getSimpleName();
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        String oneLine = message.strip().replace('\r', ' ').replace('\n', ' ');
        if (oneLine.length() <= CAUSE_MAX) {
            return type + ": " + oneLine;
        }
        int end = Character.isHighSurrogate(oneLine.charAt(CAUSE_MAX - 1)) ? CAUSE_MAX - 1 : CAUSE_MAX; // 서로게이트 쌍을 가르지 않음
        return type + ": " + oneLine.substring(0, end) + "…";
    }

}
