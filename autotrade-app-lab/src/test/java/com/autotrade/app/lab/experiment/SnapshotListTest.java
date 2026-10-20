package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실험 생성 화면의 스냅샷 목록(PR #201 architect N2): 세 종류(일봉·재무·공시)의 manifest 기간, 깨진 manifest, 목록에 넣지 않는 폴더,
 * 모르는 종류.
 */
class SnapshotListTest {

    @TempDir
    Path root;

    private void manifest(String kind, String id, String coverage) throws Exception {
        Path dir = Files.createDirectories(root.resolve(kind).resolve(id));
        Files.writeString(dir.resolve("manifest.json"), "{\"snapshotId\":\"" + id + "\",\"schemaVersion\":1,"
                + "\"createdAt\":\"2026-10-19T09:00:00Z\",\"scriptVersion\":\"0.1.0\",\"sources\":[],\"files\":[]"
                + (coverage == null ? "" : ",\"coverage\":" + coverage) + "}", StandardCharsets.UTF_8);
    }

    @Test
    void dailyUsesLastTradingDateAndNewestFirst() throws Exception {
        manifest("krx-daily", "krx-daily-2026-10-16-0a1b2c3d",
                "{\"firstDate\":\"2010-01-04\",\"lastTradingDate\":\"2026-10-16\",\"lastCollectedAt\":\"2026-10-16T09:00:00Z\"}");
        manifest("krx-daily", "krx-daily-2026-10-19-0a1b2c3d",
                "{\"firstDate\":\"2010-01-04\",\"lastTradingDate\":\"2026-10-19\"}");

        assertThat(new SnapshotList(root).list("krx-daily")).containsExactly(
                new SnapshotList.Snapshot("krx-daily-2026-10-19-0a1b2c3d", "2010-01-04", "2026-10-19", null),
                new SnapshotList.Snapshot("krx-daily-2026-10-16-0a1b2c3d", "2010-01-04", "2026-10-16", null));
    }

    @Test
    void financialsAndDisclosuresUseLastCollectedAt() throws Exception {
        String cov = "{\"firstDate\":\"2015-01-02\",\"lastTradingDate\":\"2026-10-19\",\"lastCollectedAt\":\"2026-10-19T22:10:00Z\"}";
        manifest("dart-financials", "dart-financials-2026-10-19-0a1b2c3d", cov);
        manifest("dart-disclosures", "dart-disclosures-2026-10-19-0a1b2c3d", cov);

        SnapshotList list = new SnapshotList(root);
        assertThat(list.list("dart-financials")).containsExactly(new SnapshotList.Snapshot(
                "dart-financials-2026-10-19-0a1b2c3d", "2015-01-02", "2026-10-19T22:10:00Z", null));
        assertThat(list.list("dart-disclosures")).containsExactly(new SnapshotList.Snapshot(
                "dart-disclosures-2026-10-19-0a1b2c3d", "2015-01-02", "2026-10-19T22:10:00Z", null));
        // 종류끼리 섞이지 않는다
        assertThat(list.list("krx-daily")).isEmpty();
    }

    @Test
    void oldManifestWithoutCoverageHasNoPeriod() throws Exception {
        manifest("dart-disclosures", "dart-disclosures-old", null);

        assertThat(new SnapshotList(root).list("dart-disclosures"))
                .containsExactly(new SnapshotList.Snapshot("dart-disclosures-old", null, null, null));
    }

    @Test
    void brokenManifestIsListedWithProblem() throws Exception {
        Path dir = Files.createDirectories(root.resolve("dart-disclosures").resolve("dart-disclosures-broken"));
        Files.writeString(dir.resolve("manifest.json"), "{ not json", StandardCharsets.UTF_8);

        assertThat(new SnapshotList(root).list("dart-disclosures")).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("dart-disclosures-broken");
            assertThat(s.firstDate()).isNull();
            assertThat(s.lastDate()).isNull();
            assertThat(s.problem()).isEqualTo("manifest.json을 읽을 수 없습니다");
        });
    }

    @Test
    void skipsFoldersThatAreNotSnapshots() throws Exception {
        manifest("dart-disclosures", "dart-disclosures-ok", null);
        // manifest 없는 폴더(수집 중), ID 모양이 아닌 폴더(대문자·밑줄), 폴더가 아닌 파일
        Files.createDirectories(root.resolve("dart-disclosures").resolve("dart-disclosures-partial"));
        manifest("dart-disclosures", "Bad_Name", null);
        Files.writeString(root.resolve("dart-disclosures").resolve("notes.txt"), "x", StandardCharsets.UTF_8);

        assertThat(new SnapshotList(root).list("dart-disclosures")).extracting(SnapshotList.Snapshot::id)
                .containsExactly("dart-disclosures-ok");
    }

    @Test
    void missingKindFolderIsEmptyAndUnknownKindIsRefused() {
        SnapshotList list = new SnapshotList(root);
        assertThat(list.list("dart-disclosures")).isEmpty();
        assertThatThrownBy(() -> list.list("../krx-daily")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("모르는 스냅샷 종류");
        assertThatThrownBy(() -> list.list("krx-minute")).isInstanceOf(IllegalArgumentException.class);
    }
}
