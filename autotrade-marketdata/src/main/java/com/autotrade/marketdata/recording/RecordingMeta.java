package com.autotrade.marketdata.recording;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 녹화일 메타데이터({@code meta.json}). 출처·데이터 범위·라이선스·수집 시각을 남긴다(MD-09, role-guides.md 3장 완료 기준).
 *
 * @param formatVersion {@link RecordingCodec#FORMAT_VERSION}
 * @param market 시장 이름
 * @param date 거래소 현지 날짜(yyyy-MM-dd)
 * @param zone 거래소 시간대
 * @param sources 소스 이름 → 데이터 범위(예: {@code KRX_REALTIME}, {@code NASDAQ_TOTALVIEW}, {@code PARTIAL_US}).
 *        소스가 바뀐 날끼리 성과를 섞어 비교하지 않게 한다(market-data-limits.md 2장). 이름순으로 고정해 {@code meta.json}이
 *        실행마다 같은 바이트로 나온다({@code Map.copyOf}는 JVM마다 순서가 달라진다, PR #206에서 발견)
 * @param license 이용 조건 메모. 녹화 파일은 본인 매매·연구 목적이며 외부 공유하지 않는다
 * @param segments 녹화 구간(프로세스가 다시 뜰 때마다 하나씩). 구간 사이는 녹화되지 않은 시간이다
 * @param lastSeq 마지막 녹화 순번
 * @param eventCount 이벤트 줄 수
 * @param gapCount 누락 구간 표시 줄 수
 * @param recorderDropped 녹화 큐가 넘쳐 버린 이벤트 수
 * @param complete 정상 종료로 닫혔는지. false면 마지막 줄이 잘렸을 수 있다
 * @param eventsSha256 {@code complete}일 때 events 파일 전체의 SHA-256(16진수). 아니면 빈 문자열
 */
public record RecordingMeta(
        int formatVersion,
        String market,
        String date,
        String zone,
        Map<String, String> sources,
        String license,
        List<Segment> segments,
        long lastSeq,
        long eventCount,
        long gapCount,
        long recorderDropped,
        boolean complete,
        String eventsSha256) {

    public RecordingMeta {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(zone, "zone");
        sources.forEach((k, v) -> {
            Objects.requireNonNull(k, "sources 키");
            Objects.requireNonNull(v, "sources 값");
        });
        sources = Collections.unmodifiableMap(new TreeMap<>(sources));
        license = license == null ? "" : license;
        segments = List.copyOf(segments);
        eventsSha256 = eventsSha256 == null ? "" : eventsSha256;
    }

    /** 녹화 구간. {@code endedAt}은 열린 구간이면 빈 문자열. 시각은 ISO-8601 UTC. */
    public record Segment(String startedAt, String endedAt, long firstSeq) {
        public Segment {
            Objects.requireNonNull(startedAt, "startedAt");
            endedAt = endedAt == null ? "" : endedAt;
        }
    }
}
