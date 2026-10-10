package com.autotrade.marketdata.daily;

import java.util.List;
import java.util.Objects;

/**
 * 오프라인 수집 스냅샷의 manifest(D-019 언어 간 계약, ADR-003 2.6, 스키마 버전 {@value #SCHEMA_VERSION}).
 * Python 수집기가 쓰고 Java는 읽기만 한다.
 *
 * <pre>
 * {"snapshotId":"krx-daily-2026-10-08-1","schemaVersion":1,"createdAt":"2026-10-08T09:00:00Z","scriptVersion":"0.1.0",
 *  "sources":[{"name":"…","url":"…","license":"…","collectedAt":"…"}],
 *  "files":[{"name":"daily_bars.csv","sha256":"…","rows":123},{"name":"instrument_history.csv","sha256":"…","rows":4}]}
 * </pre>
 */
public record SnapshotManifest(
        String snapshotId, int schemaVersion, String createdAt, String scriptVersion, List<Source> sources, List<FileEntry> files) {

    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "manifest.json";
    public static final String DAILY_BARS = "daily_bars.csv";
    public static final String INSTRUMENT_HISTORY = "instrument_history.csv";

    public SnapshotManifest {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(createdAt, "createdAt");
        scriptVersion = scriptVersion == null ? "" : scriptVersion;
        sources = List.copyOf(sources);
        files = List.copyOf(files);
    }

    /** 데이터 출처. 소스별 라이선스·약관은 D-050(docs/research/q-007-q-026-collector-licenses.md)을 따른다. */
    public record Source(String name, String url, String license, String collectedAt) {}

    public record FileEntry(String name, String sha256, long rows) {}
}
