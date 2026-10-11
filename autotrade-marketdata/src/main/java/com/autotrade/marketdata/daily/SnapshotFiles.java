package com.autotrade.marketdata.daily;

import com.autotrade.marketdata.recording.Sha256;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 스냅샷 폴더 공통 읽기(D-019 계약): manifest 읽기, 파일별 SHA-256·행 수 확인. 일봉·재무 스냅샷이 같이 쓴다. */
public final class SnapshotFiles {

    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SnapshotFiles() {}

    /** {@code dir/manifest.json}을 읽고 스키마 버전을 확인한다. */
    public static SnapshotManifest readManifest(Path dir) throws IOException {
        SnapshotManifest manifest = JSON.readValue(dir.resolve(SnapshotManifest.FILE_NAME).toFile(), SnapshotManifest.class);
        if (manifest.schemaVersion() != SnapshotManifest.SCHEMA_VERSION) {
            throw new IllegalStateException("지원하지 않는 스냅샷 스키마 버전: " + manifest.schemaVersion());
        }
        return manifest;
    }

    /** manifest에 있는 파일인지, SHA-256·행 수가 맞는지 확인하고 헤더를 뺀 행을 돌려준다. */
    public static List<List<String>> readVerified(Path dir, SnapshotManifest manifest, String name, List<String> header)
            throws IOException {
        SnapshotManifest.FileEntry entry = manifest.files().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest에 " + name + " 항목이 없습니다"));
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            throw new IllegalStateException("스냅샷 파일이 없습니다: " + name);
        }
        String sha = Sha256.ofFile(file);
        if (!sha.equalsIgnoreCase(entry.sha256())) {
            throw new IllegalStateException(name + " SHA-256이 manifest와 다릅니다");
        }
        List<List<String>> rows = Csv.read(file, header);
        if (rows.size() != entry.rows()) {
            throw new IllegalStateException(name + " 행 수가 manifest와 다릅니다: " + rows.size() + " != " + entry.rows());
        }
        return rows;
    }
}
