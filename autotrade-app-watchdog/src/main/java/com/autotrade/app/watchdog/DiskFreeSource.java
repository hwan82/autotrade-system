package com.autotrade.app.watchdog;

import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.Reading;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 드라이브 여유 공간(GB, 2^30바이트) 수집원(D-092). 감시 런타임은 OSHI·{@code java.lang.management}를 쓰지 않으므로(D-086, D-090)
 * JDK {@code FileStore}로 읽는다. 이름은 실험실 표준 수집원과 같은 {@code disk.free-gb:<경로>}다.
 *
 * <p>한 경로를 읽지 못하면 그 경로 값만 빠진다. 감시는 값이 빠진 주기를 "판단 못 함"으로 보고 정지 요청을 풀지 않는다.
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
        List<Reading> out = new ArrayList<>();
        for (Path p : paths) {
            try {
                out.add(new Reading(readingName(p), freeSpace.usableBytes(p) / GIB, "GB"));
            } catch (IOException | RuntimeException e) {
                // 그 경로만 뺀다(판단 못 함으로 이어짐)
            }
        }
        return out;
    }
}
