package com.autotrade.app.collector;

import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 스냅샷 보관 규칙(risk-ops 검토 PR #39 F2, ADR-008 2.4). {@code --keep-snapshots K}를 줬을 때만 돈다.
 * 한 소스 폴더에서 manifest의 생성 시각 기준 최근 K개와 방금 만든 스냅샷, 그리고 실험 폴더({@code experiments/}) 아래
 * 파일에 ID를 적은 스냅샷은 남기고 나머지를 지운다. 수집기가 스냅샷마다 쓰는 품질 리포트 폴더는 참조로 보지 않는다
 * (모든 스냅샷이 자기 리포트 때문에 남는 것을 막음). 실험 폴더가 없으면 참조를 확인할 수 없어 아무것도 지우지 않는다.
 * 실험실 DB({@code experiment.data_snapshot_version})에만 적힌 참조는 보지 못한다(수집기는 DB를 읽지 않는다, runbook 5.1).
 * 일봉 스냅샷에만 쓴다: 지운 일봉 스냅샷은 원문 캐시가 남아 있으면 같은 기간으로 다시 만들어 같은 ID가 나온다(D-133 전
 * 형식은 {@code --without-turnover}로). 재무 스냅샷은
 * 지우면 재현할 수 없어 대상이 아니다(QA PR #39 F2, {@link CollectorMain}). 캐시는 지우지 않는다.
 */
final class SnapshotRetention {

    /** 실험 기록으로 볼 파일 크기 상한(설정·결과 요약·리포트). 더 큰 파일(원자료 등)은 읽지 않는다. */
    static final long MAX_SCAN_BYTES = 4L * 1024 * 1024;

    private SnapshotRetention() {}

    private static long size(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /** @return 지운 폴더 */
    static List<Path> prune(Path root, String prefix, int keep, Path current, Path experimentsDir, Path reportDir)
            throws IOException {
        if (experimentsDir == null || !Files.isDirectory(experimentsDir)) {
            return List.of();
        }
        List<Path> snapshots = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.filter(Files::isDirectory).toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(prefix + "-") && Files.exists(p.resolve(SnapshotManifest.FILE_NAME))) {
                    snapshots.add(p);
                }
            }
        }
        // 최근 순: manifest 생성 시각, 같으면 이름
        snapshots.sort(Comparator.comparing((Path p) -> createdAt(p)).thenComparing(p -> p.getFileName().toString())
                .reversed());
        Set<String> referenced = referencedIds(experimentsDir, reportDir, snapshots);
        List<Path> removed = new ArrayList<>();
        for (int i = keep; i < snapshots.size(); i++) {
            Path p = snapshots.get(i);
            if (p.equals(current) || referenced.contains(p.getFileName().toString())) {
                continue;
            }
            deleteTree(p);
            removed.add(p);
        }
        return removed;
    }

    private static String createdAt(Path dir) {
        try {
            return SnapshotFiles.readManifest(dir).createdAt();
        } catch (IOException | RuntimeException e) {
            return ""; // 읽을 수 없는 manifest는 가장 오래된 것으로 본다
        }
    }

    /** 실험 기록(텍스트 파일) 안에 이름이 나오는 스냅샷 ID. */
    private static Set<String> referencedIds(Path experimentsDir, Path reportDir, List<Path> snapshots) throws IOException {
        Set<String> found = new HashSet<>();
        Path reports = reportDir == null ? null : reportDir.toAbsolutePath().normalize();
        List<Path> files;
        try (Stream<Path> s = Files.walk(experimentsDir, 4)) {
            files = s.filter(Files::isRegularFile).filter(f -> size(f) <= MAX_SCAN_BYTES)
                    .filter(f -> reports == null || !f.toAbsolutePath().normalize().startsWith(reports)).toList();
        }
        for (Path f : files) {
            String text;
            try {
                text = Files.readString(f, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                continue; // 텍스트가 아닌 파일
            }
            for (Path p : snapshots) {
                String id = p.getFileName().toString();
                if (text.contains(id)) {
                    found.add(id);
                }
            }
        }
        return found;
    }

    private static void deleteTree(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(dir)) {
            all = s.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : all) {
            Files.delete(p);
        }
    }
}
