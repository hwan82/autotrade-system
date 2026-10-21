package com.autotrade.app.collector;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 뉴스 스냅샷 {@code kis-news-<마지막 구간 끝 날짜>-<해시 8자리>}(D-136, ADR-012 4.2) 내용을 만든다.
 *
 * <ul>
 *   <li>{@value #FILE}: 훑은 구간 안의 제목 중 <b>종목코드가 하나라도 붙은 행만</b>(지표는 종목별 건수만 쓴다). 게시 시각·일련번호
 *       순. 종목코드는 {@code ;}로 잇는다.</li>
 *   <li>{@value #COVERAGE}: 훑은 구간(양 끝 포함, 한국 시각). 지표는 이 구간이 기준 기간을 덮지 않으면 계산하지 않는다.</li>
 * </ul>
 *
 * <p>스냅샷 ID 해시는 두 파일 모두로 만든다(행이 같아도 구간이 다르면 다른 ID, D-123과 같은 규칙). 질의 규칙
 * {@value #QUERY_RULE}은 manifest 출처 이름에 남긴다(ADR-012 4.2 {@code news.query-rule}).
 */
final class KisNewsSnapshotBuilder {

    static final String FILE = "news_headlines.csv";
    static final List<String> HEADER = List.of("published_at", "serial", "provider_code", "provider", "category", "codes",
            "title");
    static final String COVERAGE = "news_coverage.csv";
    static final List<String> COVERAGE_HEADER = List.of("from", "to");
    /** 질의 규칙 버전: 시장 전체 제목 목록을 받아 한국투자가 붙인 종목코드({@code iscd1}~)로 센다(종목명 검색 아님). */
    static final String QUERY_RULE = "kis-news-title-iscd@1";

    /** 날짜별 집계(품질 리포트). */
    record DayStats(long headlines, long tagged) {}

    /** 만든 결과 집계. {@code outside}는 훑은 구간 밖이라 넣지 않은 행. */
    record Stats(long rows, long untagged, long outside, Map<LocalDate, DayStats> byDay, Map<String, Long> byProvider) {}

    @FunctionalInterface
    interface RowSink {
        void write(List<String> row) throws IOException;
    }

    private KisNewsSnapshotBuilder() {}

    static List<List<String>> coverageRows(List<KisNewsCollector.Interval> intervals) {
        List<List<String>> out = new ArrayList<>();
        for (KisNewsCollector.Interval i : KisNewsCollector.sortedAsc(intervals)) {
            out.add(List.of(KisNewsCollector.ISO.format(i.from()), KisNewsCollector.ISO.format(i.to())));
        }
        return out;
    }

    /** 캐시의 날짜 파일을 차례로 읽어 행을 쓴다(하루씩 메모리에 올림). */
    static Stats build(Path cacheDir, List<KisNewsCollector.Interval> intervals, RowSink sink) throws IOException {
        Path root = cacheDir.resolve(KisNewsCollector.DIR);
        long rows = 0;
        long untagged = 0;
        long outside = 0;
        Map<LocalDate, DayStats> byDay = new TreeMap<>();
        Map<String, Long> byProvider = new TreeMap<>();
        for (LocalDate d : KisNewsCollector.cachedDays(cacheDir)) {
            List<KisNewsClient.Headline> day = new ArrayList<>(KisNewsCollector.readDay(root, d));
            day.sort(Comparator.comparing(KisNewsClient.Headline::time).thenComparing(KisNewsClient.Headline::serial));
            long dayAll = 0;
            long dayTagged = 0;
            for (KisNewsClient.Headline h : day) {
                LocalDateTime at = KisNewsCollector.at(h);
                if (!covered(intervals, at)) {
                    outside++;
                    continue;
                }
                dayAll++;
                byProvider.merge(h.providerCode() + "|" + h.provider() + "|" + h.category(), 1L, Long::sum);
                if (h.codes().isEmpty()) {
                    untagged++;
                    continue;
                }
                dayTagged++;
                rows++;
                sink.write(List.of(KisNewsCollector.ISO.format(at), h.serial(), h.providerCode(), h.provider(), h.category(),
                        String.join(";", h.codes()), h.title()));
            }
            if (dayAll > 0) {
                byDay.put(d, new DayStats(dayAll, dayTagged));
            }
        }
        return new Stats(rows, untagged, outside, byDay, byProvider);
    }

    static boolean covered(List<KisNewsCollector.Interval> intervals, LocalDateTime at) {
        for (KisNewsCollector.Interval i : intervals) {
            if (!at.isBefore(i.from()) && !at.isAfter(i.to())) {
                return true;
            }
        }
        return false;
    }
}
