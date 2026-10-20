package com.autotrade.app.lab.experiment;

import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 실험 생성 화면(UI-02, D-074)의 스냅샷 고르기용 목록. 수집기가 쓴 {@code <설정 폴더>/snapshots/krx-daily/<ID>/},
 * {@code dart-financials/<ID>/}, {@code dart-disclosures/<ID>/}(ADR-008 2.4, D-123)의 manifest만 읽는다(파일 해시는 실행 명령이 확인). 실행 명령의 스냅샷 폴더 기본값과
 * 같은 곳이다({@code CombinationMain}).
 */
public final class SnapshotList {

    /** {@code CombinationPlan}이 받는 스냅샷 ID 모양. */
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    private final Path root;

    public SnapshotList(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * 스냅샷 하나. manifest를 읽지 못하면 기간이 null이고 {@code problem}에 이유가 있다.
     *
     * @param firstDate manifest {@code coverage.firstDate}(예전 스냅샷은 null)
     * @param lastDate 일봉은 {@code coverage.lastTradingDate}, 재무·공시는 {@code coverage.lastCollectedAt}
     */
    public record Snapshot(String id, String firstDate, String lastDate, String problem) {
    }

    /** @param kind {@code krx-daily}, {@code dart-financials} 또는 {@code dart-disclosures}(D-123). ID 내림차순(최근 수집이 앞) */
    public List<Snapshot> list(String kind) {
        if (!kind.equals("krx-daily") && !kind.equals("dart-financials") && !kind.equals("dart-disclosures")) {
            throw new IllegalArgumentException("모르는 스냅샷 종류입니다: " + kind);
        }
        Path dir = root.resolve(kind);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Snapshot> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(dir)) {
            for (Path d : dirs.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                String id = d.getFileName().toString();
                if (!ID.matcher(id).matches() || !Files.isRegularFile(d.resolve(SnapshotManifest.FILE_NAME))) {
                    continue;
                }
                try {
                    SnapshotManifest m = SnapshotFiles.readManifest(d);
                    SnapshotManifest.Coverage c = m.coverage();
                    String last = c == null ? null : kind.equals("krx-daily") ? c.lastTradingDate() : c.lastCollectedAt();
                    out.add(new Snapshot(id, c == null ? null : c.firstDate(), last, null));
                } catch (IOException | RuntimeException e) {
                    out.add(new Snapshot(id, null, null, "manifest.json을 읽을 수 없습니다"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("스냅샷 폴더를 읽을 수 없습니다: " + dir, e);
        }
        return out;
    }
}
