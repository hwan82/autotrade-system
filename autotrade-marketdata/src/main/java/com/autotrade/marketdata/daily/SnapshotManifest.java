package com.autotrade.marketdata.daily;

import java.util.List;
import java.util.Objects;

/**
 * 오프라인 수집 스냅샷의 manifest(D-019 언어 간 계약, ADR-003 2.6, 스키마 버전 {@value #SCHEMA_VERSION}).
 * 수집 일괄 명령({@code autotrade-app-collector}, D-051)이 쓰고 실험실·선정 일괄 명령은 읽기만 한다.
 *
 * <pre>
 * {"snapshotId":"krx-daily-2026-10-08-1a2b3c4d","schemaVersion":1,"createdAt":"2026-10-08T09:00:00Z","scriptVersion":"0.1.0",
 *  "sources":[{"name":"…","url":"…","license":"…","collectedAt":"…"}],
 *  "files":[{"name":"daily_bars.csv","sha256":"…","rows":123},{"name":"instrument_history.csv","sha256":"…","rows":4}],
 *  "coverage":{"firstDate":"2010-01-04","lastTradingDate":"2026-10-08","sharesAsOf":"2026-10-08","lastCollectedAt":"…"}}
 * </pre>
 *
 * <p>{@code coverage}는 ADR-008에서 더한 선택 칸이다(스키마 버전은 1 그대로, 없던 스냅샷은 {@code null}). 선정 결과의
 * 입력 데이터 기준일(STR-15, ADR-007 2.6 {@code inputs})이 이 값을 옮긴다.
 */
public record SnapshotManifest(
        String snapshotId, int schemaVersion, String createdAt, String scriptVersion, List<Source> sources, List<FileEntry> files,
        Coverage coverage) {

    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "manifest.json";
    public static final String DAILY_BARS = "daily_bars.csv";
    public static final String INSTRUMENT_HISTORY = "instrument_history.csv";
    /** 시점별 상장주식수(MD-07, ADR-008). 일봉 스냅샷의 선택 파일이다. */
    public static final String LISTED_SHARES = "listed_shares.csv";
    /** OpenDART 정기보고서 재무(MD-11, ADR-008). 재무 스냅샷의 파일이다. */
    public static final String FINANCIAL_FILINGS = "financial_filings.csv";

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

    /**
     * 데이터 기준일. 날짜는 거래소 현지 날짜(ISO), 시각은 UTC ISO 순간이다. 해당 없는 칸은 {@code null}.
     *
     * @param firstDate 첫 거래일(일봉) 또는 첫 공시 접수일(재무)
     * @param lastTradingDate 일봉 마지막 거래일
     * @param sharesAsOf 상장주식수 기준일(마지막 거래일 장 마감 시점)
     * @param lastCollectedAt 마지막 수집 시각(재무 신선도 판정, ADR-007 2.6)
     */
    public record Coverage(String firstDate, String lastTradingDate, String sharesAsOf, String lastCollectedAt) {}
}
