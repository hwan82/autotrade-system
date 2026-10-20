package com.autotrade.selection.calc;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.Csv;
import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 공시 스냅샷 폴더(수집 명령 {@code dart-disclosures}, ADR-012 3.4, D-123)를 읽는다. 지표 {@code disclosure-count}의 입력이다.
 *
 * <pre>
 * disclosures.csv:      market,code,code_source,corp_code,corp_cls,pblntf_ty,rcept_no,rcept_dt,report_nm
 * disclosure_types.csv: pblntf_ty,from,to
 * </pre>
 *
 * 두 파일 모두 manifest의 SHA-256·행 수와 맞아야 하고, 접수일이 없거나 형식이 틀린 행이 하나라도 있으면 스냅샷 전체를 거부한다.
 * 전 기간 행(백만 건대)을 다 올리지 않도록 한 번 훑으며 세는 행만 종목·유형별 접수일로 남긴다. 세지 않는 행:
 * 종목 코드를 못 붙인 행(코드 빈칸), 정정 공시({@link #isCorrection}).
 */
public final class SnapshotDisclosures implements SelectionData.DisclosureInput {

    public static final String FILE = "disclosures.csv";
    public static final String TYPES_FILE = "disclosure_types.csv";
    public static final List<String> HEADER = List.of("market", "code", "code_source", "corp_code", "corp_cls", "pblntf_ty",
            "rcept_no", "rcept_dt", "report_nm");
    public static final List<String> TYPES_HEADER = List.of("pblntf_ty", "from", "to");

    private final SnapshotManifest manifest;
    private final String sha256;
    private final Map<String, List<LocalDate[]>> ranges;
    /** 종목 → 유형 → 접수일(에포크 일, 오름차순, 같은 날 여러 건이면 여러 번). */
    private final Map<Symbol, Map<String, int[]>> dates;

    private SnapshotDisclosures(SnapshotManifest manifest, String sha256, Map<String, List<LocalDate[]>> ranges,
            Map<Symbol, Map<String, int[]>> dates) {
        this.manifest = manifest;
        this.sha256 = sha256;
        this.ranges = ranges;
        this.dates = dates;
    }

    /**
     * 정정·첨부 공시 판정(ADR-012 3.2 정정 공시 규칙): 보고서명이 {@code [기재정정]}·{@code [첨부정정]}·{@code [첨부추가]}·
     * {@code [변경등록]}·{@code [연장결정]}처럼 대괄호 머리말로 시작하면 세지 않는다. 원공시 연결 필드가 없어 원공시로 묶지 않는다.
     */
    public static boolean isCorrection(String reportName) {
        String n = reportName.strip();
        return n.startsWith("[") && n.indexOf(']') > 1;
    }

    public static SnapshotDisclosures load(Path dir) {
        try {
            SnapshotManifest manifest = SnapshotFiles.readManifest(dir);
            Map<String, List<LocalDate[]>> ranges = new TreeMap<>();
            String[] typesSha = {null};
            long typeRows = stream(dir, manifest, TYPES_FILE, TYPES_HEADER, typesSha, (r, line) -> {
                String t = r.get(0);
                if (!t.matches("[A-J]")) {
                    throw new IllegalStateException(TYPES_FILE + " " + line + "번째 줄: 모르는 공시 유형 " + t);
                }
                LocalDate from = date(r.get(1), TYPES_FILE, line);
                LocalDate to = date(r.get(2), TYPES_FILE, line);
                if (to.isBefore(from)) {
                    throw new IllegalStateException(TYPES_FILE + " " + line + "번째 줄: 구간이 뒤집혔습니다");
                }
                ranges.computeIfAbsent(t, k -> new ArrayList<>()).add(new LocalDate[] {from, to});
            });
            if (typeRows == 0) {
                throw new IllegalStateException("공시 스냅샷에 수집 유형이 없습니다: " + manifest.snapshotId());
            }
            ranges.replaceAll((t, l) -> {
                l.sort((a, b) -> a[0].compareTo(b[0]));
                for (int i = 1; i < l.size(); i++) {
                    if (!l.get(i)[0].isAfter(l.get(i - 1)[1])) { // 수집기는 합친 구간만 쓴다(PR #196 architect R1)
                        throw new IllegalStateException(TYPES_FILE + ": 유형 " + t + "의 수집 구간이 겹칩니다");
                    }
                }
                return List.copyOf(l);
            });
            Map<Symbol, Map<String, List<Integer>>> raw = new HashMap<>();
            String[] rowsSha = {null};
            stream(dir, manifest, FILE, HEADER, rowsSha, (r, line) -> {
                LocalDate d = date(r.get(7), FILE, line);
                String type = r.get(5);
                if (!ranges.containsKey(type)) {
                    throw new IllegalStateException(FILE + " " + line + "번째 줄: 수집 유형 목록에 없는 유형 " + type);
                }
                if (r.get(1).isEmpty() || isCorrection(r.get(8))) {
                    return;
                }
                Symbol s = new Symbol(Market.valueOf(r.get(0)), r.get(1));
                raw.computeIfAbsent(s, k -> new TreeMap<>()).computeIfAbsent(type, k -> new ArrayList<>())
                        .add((int) d.toEpochDay());
            });
            Map<Symbol, Map<String, int[]>> dates = new HashMap<>();
            raw.forEach((s, byType) -> {
                Map<String, int[]> m = new TreeMap<>();
                byType.forEach((t, l) -> {
                    int[] a = l.stream().mapToInt(Integer::intValue).toArray();
                    Arrays.sort(a);
                    m.put(t, a);
                });
                dates.put(s, Collections.unmodifiableMap(m));
            });
            return new SnapshotDisclosures(manifest, rowsSha[0], Collections.unmodifiableMap(ranges),
                    Collections.unmodifiableMap(dates));
        } catch (IOException e) {
            throw new UncheckedIOException("공시 스냅샷을 읽을 수 없습니다: " + dir, e);
        }
    }

    @FunctionalInterface
    private interface RowHandler {
        void accept(List<String> row, long line);
    }

    /** 한 번 훑으며 SHA-256을 계산하고 행을 넘긴다. 다 읽은 뒤 해시·행 수가 manifest와 다르면 실패. */
    private static long stream(Path dir, SnapshotManifest manifest, String name, List<String> header, String[] shaOut,
            RowHandler handler) throws IOException {
        SnapshotManifest.FileEntry entry = manifest.files().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest에 " + name + " 항목이 없습니다"));
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            throw new IllegalStateException("스냅샷 파일이 없습니다: " + name);
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
        long rows = 0;
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), md);
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String head = r.readLine();
            if (head == null || !Csv.split(head).equals(header)) {
                throw new IllegalStateException(name + " 헤더가 스키마와 다릅니다: " + head);
            }
            String line;
            long n = 1;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isEmpty()) {
                    continue;
                }
                List<String> f = Csv.split(line);
                if (f.size() != header.size()) {
                    throw new IllegalStateException(name + " " + n + "번째 줄 열 수가 다릅니다");
                }
                rows++;
                handler.accept(f, n);
            }
        }
        String sha = HexFormat.of().formatHex(md.digest());
        if (!sha.equalsIgnoreCase(entry.sha256())) {
            throw new IllegalStateException(name + " SHA-256이 manifest와 다릅니다");
        }
        if (rows != entry.rows()) {
            throw new IllegalStateException(name + " 행 수가 manifest와 다릅니다: " + rows + " != " + entry.rows());
        }
        shaOut[0] = sha;
        return rows;
    }

    private static LocalDate date(String v, String file, long line) {
        if (v.isBlank()) {
            throw new IllegalStateException(file + " " + line + "번째 줄: 날짜가 없습니다");
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(file + " " + line + "번째 줄: 날짜 형식이 틀립니다", e);
        }
    }

    public SnapshotManifest manifest() {
        return manifest;
    }

    @Override
    public String snapshotId() {
        return manifest.snapshotId();
    }

    @Override
    public String sha256() {
        return sha256;
    }

    @Override
    public SortedSet<String> types() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(ranges.keySet()));
    }

    @Override
    public boolean covers(String type, LocalDate from, LocalDate to) {
        for (LocalDate[] r : ranges.getOrDefault(type, List.of())) {
            if (!from.isBefore(r[0]) && !to.isAfter(r[1])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public LocalDate coveredThrough(String type, LocalDate date) {
        for (LocalDate[] r : ranges.getOrDefault(type, List.of())) {
            if (!date.isBefore(r[0]) && !date.isAfter(r[1])) {
                return r[1];
            }
        }
        return null;
    }

    @Override
    public int count(Symbol symbol, Set<String> types, LocalDate from, LocalDate to) {
        Objects.requireNonNull(symbol, "symbol");
        Map<String, int[]> byType = dates.get(symbol);
        if (byType == null) {
            return 0;
        }
        int lo = (int) from.toEpochDay();
        int hi = (int) to.toEpochDay();
        int n = 0;
        for (String t : types) {
            int[] a = byType.get(t);
            if (a != null) {
                n += upper(a, hi) - lower(a, lo);
            }
        }
        return n;
    }

    /** 첫 {@code >= v} 위치. */
    private static int lower(int[] a, int v) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 첫 {@code > v} 위치. */
    private static int upper(int[] a, int v) {
        return lower(a, v + 1);
    }
}
