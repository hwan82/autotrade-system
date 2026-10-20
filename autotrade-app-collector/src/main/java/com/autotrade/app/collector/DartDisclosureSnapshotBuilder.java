package com.autotrade.app.collector;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 공시 목록 캐시의 창 파일들로 공시 스냅샷 행을 만든다(ADR-012 3.4, D-123). 읽는 쪽은 선정 모듈의
 * {@code com.autotrade.selection.calc.SnapshotDisclosures}다(수집기는 선정 모듈에 의존하지 않으므로 헤더를 양쪽에 같게 둔다).
 *
 * <pre>
 * disclosures.csv:      market,code,code_source,corp_code,corp_cls,pblntf_ty,rcept_no,rcept_dt,report_nm
 * disclosure_types.csv: pblntf_ty,from,to      (유형별 훑은 구간. 한 유형에 구간이 여럿일 수 있다)
 * </pre>
 *
 * <p><b>종목 코드 이어 붙이기</b>(생존편향 방지, strategy-researcher PR #191 m4): 응답 행의 {@code stock_code}가 비면
 * (1) 같은 고유번호({@code corp_code})의 다른 행이 가진 종목 코드(그 날짜를 덮는 것, 없으면 가장 가까운 것), (2) 그날 KRX 종목
 * 이력에서 회사 이름이 정확히 같은(공백·"(주)"·"주식회사" 무시) 종목이 하나뿐이면 그 코드를 쓴다. 출처를 {@code code_source}
 * ({@code DART}, {@code CORP}, {@code NAME})에 남기고, 못 붙인 행은 코드를 비워 두고 manifest와 품질 리포트에 유형·법인 구분별
 * 건수를 남긴다. 상장폐지 회사 행의 {@code stock_code}가 실제로 비는지는 OpenDART 원문으로 확인하지 못했다 `[가정]`.
 *
 * <p>행 순서: 접수일, 접수번호, 유형. 정정 공시 판정(보고서명 대괄호 머리말)은 읽는 쪽이 한다(카탈로그 정의 한 곳).
 */
final class DartDisclosureSnapshotBuilder {

    static final String FILE = "disclosures.csv";
    static final String TYPES_FILE = "disclosure_types.csv";
    static final List<String> HEADER = List.of("market", "code", "code_source", "corp_code", "corp_cls", "pblntf_ty", "rcept_no",
            "rcept_dt", "report_nm");
    static final List<String> TYPES_HEADER = List.of("pblntf_ty", "from", "to");
    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{6}");

    private DartDisclosureSnapshotBuilder() {}

    /** KRX 종목 이력 한 구간(이름 대조용). {@code to}가 null이면 진행 중. */
    record Listing(String code, String name, LocalDate from, LocalDate to) {

        boolean covers(LocalDate d) {
            return !d.isBefore(from) && (to == null || !d.isAfter(to));
        }
    }

    /** 행 쓰기. */
    @FunctionalInterface
    interface Sink {
        void write(List<String> row) throws IOException;
    }

    /**
     * @param rowsByType 유형별 행 수
     * @param bySource 종목 코드 출처별 행 수({@code DART}, {@code CORP}, {@code NAME}, {@code NONE})
     * @param unmapped 못 붙인 행 수({@code <유형>|<corp_cls>} → 건수)
     * @param byYear 접수 연도 → {전체 행, 못 붙인 행}(법인 구분 E 포함, 생존편향 점검, PR #196 strategy-researcher M2)
     * @param prefixes 보고서명 대괄호 머리말(예 {@code [기재정정]}) → 건수. 선정에서 세지 않는 행이다(같은 리뷰 m1)
     * @param delisted 수집 구간 안에 상장폐지된 KRX 종목 이력 구간 수
     * @param delistedWithDisclosure 그중 상장 구간 안 접수 공시가 하나라도 그 종목 코드로 붙은 구간 수
     */
    record Stats(Map<String, Long> rowsByType, Map<String, Long> bySource, Map<String, Long> unmapped, long rows,
            Map<Integer, long[]> byYear, Map<String, Long> prefixes, long delisted, long delistedWithDisclosure) {}

    /**
     * @param windows 유형 → 창 파일(시작일 순, {@link DartDisclosureCollector#windowFiles})
     * @param krx 이름 대조에 쓸 KRX 종목 이력. 없으면 빈 목록(이름 대조를 하지 않음)
     */
    static Stats build(Map<String, List<Path>> windows, List<Listing> krx, Sink out) throws IOException {
        Map<String, Map<String, LocalDate[]>> corpSpans = corpSpans(windows);
        Map<String, List<Listing>> byName = new HashMap<>();
        for (Listing l : krx) {
            byName.computeIfAbsent(normalize(l.name()), k -> new ArrayList<>()).add(l);
        }
        // 창은 달력 월 안에 있으므로 달마다 모아 정렬하면 전체 순서가 된다(전 기간을 메모리에 올리지 않음)
        Map<String, List<Object[]>> byMonth = new TreeMap<>();
        windows.forEach((type, files) -> files.forEach(
                f -> byMonth.computeIfAbsent(DartDisclosureCollector.windowStart(f).toString().substring(0, 7),
                        k -> new ArrayList<>()).add(new Object[] {type, f})));
        Map<String, Long> rowsByType = new TreeMap<>();
        Map<String, Long> bySource = new TreeMap<>();
        Map<String, Long> unmapped = new TreeMap<>();
        Map<Integer, long[]> byYear = new TreeMap<>();
        Map<String, Long> prefixes = new TreeMap<>();
        Map<String, java.util.BitSet> mappedYears = new HashMap<>(); // 종목 코드 → 공시가 붙은 접수 연도(2000년 기준 비트)
        LocalDate first = null;
        for (List<Path> files : windows.values()) {
            for (Path f : files) {
                LocalDate st = DartDisclosureCollector.windowStart(f);
                first = first == null || st.isBefore(first) ? st : first;
            }
        }
        long total = 0;
        for (List<Object[]> month : byMonth.values()) {
            List<List<String>> rows = new ArrayList<>();
            for (Object[] tf : month) {
                String type = (String) tf[0];
                for (JsonNode n : DartDisclosureCollector.readWindow((Path) tf[1])) {
                    LocalDate date = LocalDate.parse(n.path("rcept_dt").asText(), OpenDartClient.YMD);
                    String[] mapped = map(n, date, corpSpans, byName);
                    String cls = n.path("corp_cls").asText();
                    rows.add(List.of(mapped[0].isEmpty() ? "" : "KRX", mapped[0], mapped[1], n.path("corp_code").asText(), cls, type,
                            n.path("rcept_no").asText(), date.toString(), n.path("report_nm").asText()));
                    rowsByType.merge(type, 1L, Long::sum);
                    bySource.merge(mapped[1].isEmpty() ? "NONE" : mapped[1], 1L, Long::sum);
                    long[] y = byYear.computeIfAbsent(date.getYear(), k -> new long[2]);
                    y[0]++;
                    if (mapped[0].isEmpty()) {
                        unmapped.merge(type + "|" + (cls.isEmpty() ? "-" : cls), 1L, Long::sum);
                        y[1]++;
                    } else {
                        mappedYears.computeIfAbsent(mapped[0], k -> new java.util.BitSet()).set(date.getYear() - 2000);
                    }
                    String name = n.path("report_nm").asText().strip();
                    int close = name.indexOf(']');
                    if (name.startsWith("[") && close > 1) {
                        prefixes.merge(name.substring(0, close + 1), 1L, Long::sum);
                    }
                }
            }
            rows.sort(Comparator.comparing((List<String> r) -> r.get(7)).thenComparing(r -> r.get(6))
                    .thenComparing(r -> r.get(5)));
            for (List<String> r : rows) {
                out.write(r);
                total++;
            }
        }
        long delisted = 0;
        long delistedWith = 0;
        for (Listing l : krx) {
            if (l.to() == null || first == null || l.to().isBefore(first)) {
                continue;
            }
            delisted++;
            java.util.BitSet years = mappedYears.get(l.code());
            int lo = Math.max(l.from().getYear(), first.getYear()) - 2000;
            if (years != null && lo >= 0 && years.nextSetBit(lo) >= 0 && years.nextSetBit(lo) <= l.to().getYear() - 2000) {
                delistedWith++;
            }
        }
        return new Stats(rowsByType, bySource, unmapped, total, byYear, prefixes, delisted, delistedWith);
    }

    /** 고유번호 → 종목 코드 → 그 코드가 나온 첫·마지막 접수일. */
    private static Map<String, Map<String, LocalDate[]>> corpSpans(Map<String, List<Path>> windows) throws IOException {
        Map<String, Map<String, LocalDate[]>> out = new HashMap<>();
        for (List<Path> files : windows.values()) {
            for (Path f : files) {
                for (JsonNode n : DartDisclosureCollector.readWindow(f)) {
                    String code = n.path("stock_code").asText();
                    if (!CODE.matcher(code).matches()) {
                        continue;
                    }
                    LocalDate d = LocalDate.parse(n.path("rcept_dt").asText(), OpenDartClient.YMD);
                    LocalDate[] span = out.computeIfAbsent(n.path("corp_code").asText(), k -> new TreeMap<>())
                            .computeIfAbsent(code, k -> new LocalDate[] {d, d});
                    span[0] = d.isBefore(span[0]) ? d : span[0];
                    span[1] = d.isAfter(span[1]) ? d : span[1];
                }
            }
        }
        return out;
    }

    /** @return {종목 코드(못 붙이면 ""), 출처(못 붙이면 "")} */
    static String[] map(JsonNode n, LocalDate date, Map<String, Map<String, LocalDate[]>> corpSpans,
            Map<String, List<Listing>> byName) {
        String own = n.path("stock_code").asText();
        if (CODE.matcher(own).matches()) {
            return new String[] {own, "DART"};
        }
        Map<String, LocalDate[]> spans = corpSpans.get(n.path("corp_code").asText());
        if (spans != null && !spans.isEmpty()) {
            String best = null;
            long bestGap = Long.MAX_VALUE;
            for (Map.Entry<String, LocalDate[]> e : spans.entrySet()) { // 코드 순이라 같은 거리면 작은 코드
                LocalDate[] s = e.getValue();
                long gap = date.isBefore(s[0]) ? s[0].toEpochDay() - date.toEpochDay()
                        : date.isAfter(s[1]) ? date.toEpochDay() - s[1].toEpochDay() : 0;
                if (gap < bestGap) {
                    best = e.getKey();
                    bestGap = gap;
                }
            }
            return new String[] {best, "CORP"};
        }
        List<Listing> named = byName.get(normalize(n.path("corp_name").asText()));
        if (named != null) {
            Set<String> codes = new TreeSet<>();
            for (Listing l : named) {
                if (l.covers(date)) {
                    codes.add(l.code());
                }
            }
            if (codes.size() == 1) {
                return new String[] {codes.iterator().next(), "NAME"};
            }
        }
        return new String[] {"", ""};
    }

    /** 이름 대조용: 공백, "(주)", "㈜", "주식회사"를 빼고 대문자로. */
    static String normalize(String name) {
        return name.replace("(주)", "").replace("㈜", "").replace("주식회사", "").replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    /** 유형별 훑은 구간 행(유형, 시작일 순). */
    static List<List<String>> typeRows(Map<String, List<LocalDate[]>> scanned) {
        List<List<String>> out = new ArrayList<>();
        new TreeMap<>(scanned).forEach((t, ranges) -> ranges.forEach(r -> out.add(List.of(t, r[0].toString(), r[1].toString()))));
        return out;
    }
}
