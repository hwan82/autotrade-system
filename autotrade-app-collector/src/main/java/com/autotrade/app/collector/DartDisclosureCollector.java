package com.autotrade.app.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OpenDART 공시 목록 수집(ADR-012 3.4, D-123). 선정 지표 {@code disclosure-count}의 입력이다.
 *
 * <ol>
 *   <li>공시 유형({@code pblntf_ty})마다 요청 범위 중 아직 훑지 않은 구간을 달력 월 단위 창으로 나눠, <b>최근 창부터</b> 회사 코드
 *       없이 공시검색을 부른다(한 쪽 100건, 정정 공시 포함). 최근 것부터 받아 과거분을 다 받기 전에도 최근 구간 선정을 쓸 수 있다.</li>
 *   <li>창 하나를 다 받으면 {@code <cache>/dart-disclosures/windows/<유형>/<시작>_<끝>.json}에 접수번호 순으로 쓰고 훑은 구간을
 *       {@code state.json}에 남긴다. 창 도중 상한에 닿으면 받은 쪽은 {@code partial/}에 남고 다음 실행이 이어 받는다. 이어 받을 때
 *       전체 건수가 바뀌었으면 그 창을 처음부터 한 번 다시 받는다.</li>
 *   <li>오늘은 공시가 더 들어오므로 어제까지만 받는다(재무 수집과 같음). 그래서 장 마감 날 저녁 수집에는 그날이 없다. 다음 거래일
 *       장 전(자정 뒤 08:00 전, 장 시간 보호 전)에 한 번 더 돌리면 그날까지 담긴다(runbook collector.md 4장).</li>
 * </ol>
 * 지난 날의 공시 목록은 바뀌지 않는다고 본다 `[가정]`(공시 철회·삭제가 지난 창에 반영되는지는 OpenDART 원문으로 확인하지 못함).
 */
public final class DartDisclosureCollector {

    /** OpenDART 공시 유형 코드(공시검색 {@code pblntf_ty}, 개발가이드 DS001 2019001). */
    public static final Set<String> TYPES = Set.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
    /** 캐시에 남기는 응답 칸. 원문·본문은 받지 않는다(D-050 ③). */
    static final List<String> FIELDS = List.of("rcept_no", "corp_code", "corp_name", "corp_cls", "stock_code", "rcept_dt",
            "report_nm");
    private static final Pattern RECEIPT_NO = Pattern.compile("\\d{14}");
    private static final Pattern YMD = Pattern.compile("\\d{8}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenDartClient client;
    private final Path dir;
    private final Clock clock;

    public DartDisclosureCollector(OpenDartClient client, Path cacheDir, Clock clock) {
        this.client = client;
        this.dir = cacheDir.resolve("dart-disclosures");
        this.clock = clock;
    }

    /** 공시 유형 하나의 창(달력 월 안, 양끝 포함). */
    public record Window(String type, LocalDate start, LocalDate end) {

        String fileName() {
            return start + "_" + end + ".json";
        }
    }

    /**
     * 이번 실행 결과.
     *
     * @param scanned 유형별 훑은 구간(합친 상태, 시작일 순)
     * @param stopped 호출 상한·한도 초과로 다 받기 전에 멈췄으면 그 사유, 다 받았으면 null. 멈춰도 그 전까지 다 받은 창은 남는다
     */
    public record Result(int windows, int pages, int rows, Map<String, List<LocalDate[]>> scanned, String stopped) {}

    /**
     * @param types 받을 공시 유형(쉼표 목록을 푼 것)
     * @throws IllegalArgumentException 모르는 유형, {@code to}가 오늘 이후
     */
    public Result collect(LocalDate from, LocalDate to, List<String> types) throws IOException {
        checkTypes(types);
        if (!to.isBefore(today())) {
            throw new IllegalArgumentException("공시 목록은 어제까지만 받습니다: " + to);
        }
        List<Window> todo = new ArrayList<>();
        for (String t : types) {
            pruneOrphans(t);
            for (LocalDate[] gap : DartFinancialCollector.gaps(scannedRanges(t), from, to)) {
                todo.addAll(monthWindows(t, gap[0], gap[1]));
            }
        }
        // 최근 창부터, 같은 창은 유형 순
        todo.sort(Comparator.comparing(Window::start).reversed().thenComparing(Window::type));
        int windows = 0;
        int pages = 0;
        int rows = 0;
        String stopped = null;
        for (Window w : todo) {
            int[] got;
            try {
                got = fetch(w);
            } catch (CallBudget.BudgetExhausted e) {
                stopped = e.getMessage(); // 최근 창부터 받았으므로 다 받은 창까지로 스냅샷을 만든다
                break;
            }
            pages += got[0];
            rows += got[1];
            windows++;
            markScanned(w);
        }
        Map<String, List<LocalDate[]>> scanned = new TreeMap<>();
        for (String t : types) {
            scanned.put(t, scannedRanges(t));
        }
        return new Result(windows, pages, rows, scanned, stopped);
    }

    static void checkTypes(List<String> types) {
        if (types.isEmpty()) {
            throw new IllegalArgumentException("공시 유형이 하나 이상 필요합니다");
        }
        for (String t : types) {
            if (!TYPES.contains(t)) {
                throw new IllegalArgumentException("모르는 공시 유형입니다: " + t + " (A~J 한 글자)");
            }
        }
        if (Set.copyOf(types).size() != types.size()) {
            throw new IllegalArgumentException("공시 유형이 겹칩니다: " + types);
        }
    }

    /** [start, end]를 달력 월 경계로 자른 창들(시작일 순). OpenDART 구간 상한 3개월 안이다. */
    static List<Window> monthWindows(String type, LocalDate start, LocalDate end) {
        List<Window> out = new ArrayList<>();
        LocalDate s = start;
        while (!s.isAfter(end)) {
            LocalDate monthEnd = s.withDayOfMonth(s.lengthOfMonth());
            LocalDate e = monthEnd.isAfter(end) ? end : monthEnd;
            out.add(new Window(type, s, e));
            s = e.plusDays(1);
        }
        return out;
    }

    /** 창 하나를 받는다. @return {이번에 부른 쪽 수, 창의 행 수} */
    private int[] fetch(Window w) throws IOException {
        Path part = dir.resolve("partial").resolve(w.type() + "_" + w.start() + "_" + w.end());
        int called = 0;
        boolean restarted = false;
        while (true) {
            Integer expected = Files.exists(part.resolve("meta.json"))
                    ? JSON.readTree(part.resolve("meta.json").toFile()).path("total_count").asInt() : null;
            int page = expected == null ? 1 : nextPage(part);
            int totalPages = expected == null ? 1 : JSON.readTree(part.resolve("meta.json").toFile()).path("total_page").asInt();
            boolean changed = false;
            while (page <= totalPages) {
                JsonNode body = client.disclosureList(w.type(), w.start(), w.end(), page);
                called++;
                boolean empty = "013".equals(body.path("status").asText());
                int count = empty ? 0 : body.path("total_count").asInt(-1);
                int pagesNow = empty ? 0 : body.path("total_page").asInt(-1);
                if (count < 0 || pagesNow < 0) {
                    throw new IOException("공시검색 응답에 total_count·total_page가 없습니다(" + w + ")");
                }
                if (expected == null) {
                    expected = count;
                    totalPages = pagesNow;
                    write(part.resolve("meta.json"),
                            JSON.createObjectNode().put("total_count", count).put("total_page", pagesNow));
                } else if (expected != count) {
                    changed = true;
                    break;
                }
                if (!empty) {
                    ArrayNode items = JSON.createArrayNode();
                    for (JsonNode item : body.path("list")) {
                        items.add(item(item, w));
                    }
                    write(part.resolve(String.format("page-%05d.json", page)), items);
                }
                page++;
            }
            if (changed) {
                if (restarted) {
                    throw new IOException("받는 동안 공시 목록 건수가 두 번 바뀌었습니다(" + w + "). 다음 실행에서 다시 받습니다");
                }
                restarted = true;
                deleteTree(part);
                continue;
            }
            List<ObjectNode> rows = assemble(part, w, expected);
            ArrayNode out = JSON.createArrayNode();
            rows.forEach(out::add);
            write(dir.resolve("windows").resolve(w.type()).resolve(w.fileName()), out);
            deleteTree(part);
            return new int[] {called, rows.size()};
        }
    }

    /** 받은 쪽들을 접수번호로 합쳐 건수를 맞춰 본다. 쪽이 밀려 같은 공시가 두 번 오면 하나로 센다. */
    private static List<ObjectNode> assemble(Path part, Window w, int expected) throws IOException {
        Map<String, ObjectNode> byNo = new TreeMap<>();
        if (Files.isDirectory(part)) {
            try (Stream<Path> files = Files.list(part)) {
                for (Path p : files.filter(p -> p.getFileName().toString().startsWith("page-")).sorted().toList()) {
                    for (JsonNode n : JSON.readTree(p.toFile())) {
                        byNo.put(n.path("rcept_no").asText(), (ObjectNode) n);
                    }
                }
            }
        }
        if (byNo.size() != expected) {
            throw new IOException("공시검색 건수가 맞지 않습니다(" + w + "): 응답 total_count " + expected + ", 받은 공시 " + byNo.size()
                    + "건. 다음 실행에서 다시 받습니다");
        }
        return new ArrayList<>(byNo.values());
    }

    private static int nextPage(Path part) throws IOException {
        int last = 0;
        try (Stream<Path> files = Files.list(part)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith("page-") && n.endsWith(".json")) {
                    last = Math.max(last, Integer.parseInt(n.substring(5, n.length() - 5)));
                }
            }
        }
        return last + 1;
    }

    /** 응답 한 행에서 남길 칸만. 접수번호·접수일이 형식에 맞고 창 안이어야 한다(아니면 응답을 믿을 수 없어 멈춤). */
    static ObjectNode item(JsonNode item, Window w) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        for (String f : FIELDS) {
            n.put(f, item.path(f).asText("").strip());
        }
        String no = n.path("rcept_no").asText();
        String dt = n.path("rcept_dt").asText();
        if (!RECEIPT_NO.matcher(no).matches() || !YMD.matcher(dt).matches()) {
            throw new IOException("공시검색 응답 행 형식이 틀립니다(" + w + "): 접수번호 " + no + ", 접수일 " + dt);
        }
        LocalDate d = LocalDate.parse(dt, OpenDartClient.YMD);
        if (d.isBefore(w.start()) || d.isAfter(w.end())) {
            throw new IOException("공시검색 응답 행 접수일이 요청 구간 밖입니다(" + w + "): " + dt);
        }
        return n;
    }

    /** 유형 하나의 훑은 구간(합친 상태, 시작일 순). */
    public List<LocalDate[]> scannedRanges(String type) throws IOException {
        Path f = dir.resolve("state.json");
        List<LocalDate[]> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
        for (JsonNode r : JSON.readTree(f.toFile()).path("scanned").path(type)) {
            out.add(new LocalDate[] {LocalDate.parse(r.path("from").asText()), LocalDate.parse(r.path("to").asText())});
        }
        return DartFinancialCollector.merge(out);
    }

    private void markScanned(Window w) throws IOException {
        Path f = dir.resolve("state.json");
        Map<String, List<LocalDate[]>> all = new TreeMap<>();
        if (Files.exists(f)) {
            JSON.readTree(f.toFile()).path("scanned").fieldNames().forEachRemaining(t -> all.put(t, null));
        }
        all.put(w.type(), null);
        ObjectNode state = JSON.createObjectNode();
        ObjectNode scanned = state.putObject("scanned");
        for (String t : all.keySet()) {
            List<LocalDate[]> ranges = scannedRanges(t);
            if (t.equals(w.type())) {
                ranges.add(new LocalDate[] {w.start(), w.end()});
                ranges = DartFinancialCollector.merge(ranges);
            }
            ArrayNode arr = scanned.putArray(t);
            for (LocalDate[] r : ranges) {
                arr.addObject().put("from", r[0].toString()).put("to", r[1].toString());
            }
        }
        write(f, state);
    }

    /**
     * 다 받은 창 파일(유형별, 시작일 순). 훑은 구간(state.json) 안의 창만 돌려준다. 창 파일을 쓴 뒤 훑은 구간을 적기 전에 끝난 실행이
     * 남긴 파일은 빼고, 남은 창끼리 날짜가 겹치면 같은 공시가 두 번 들어가므로 실패한다(PR #196 architect F1).
     */
    public Map<String, List<Path>> windowFiles(List<String> types) throws IOException {
        Map<String, List<Path>> out = new LinkedHashMap<>();
        for (String t : types) {
            List<LocalDate[]> scanned = scannedRanges(t);
            List<Path> files = new ArrayList<>();
            LocalDate lastEnd = null;
            for (Path f : listWindows(t)) {
                if (!inside(scanned, windowStart(f), windowEnd(f))) {
                    continue;
                }
                if (lastEnd != null && !windowStart(f).isAfter(lastEnd)) {
                    throw new IOException("공시 창 파일 날짜가 겹칩니다(같은 공시가 두 번 들어감): " + f
                            + ". 캐시 폴더 dart-disclosures/windows/" + t + "를 확인하세요");
                }
                lastEnd = windowEnd(f);
                files.add(f);
            }
            out.put(t, files);
        }
        return out;
    }

    /** 훑은 구간 밖 창 파일(창을 쓰고 훑은 구간을 적기 전에 끝난 실행이 남긴 것)을 지운다. 이 구간은 다시 받는다. */
    private void pruneOrphans(String type) throws IOException {
        List<LocalDate[]> scanned = scannedRanges(type);
        for (Path f : listWindows(type)) {
            if (!inside(scanned, windowStart(f), windowEnd(f))) {
                Files.delete(f);
            }
        }
    }

    private List<Path> listWindows(String type) throws IOException {
        Path d = dir.resolve("windows").resolve(type);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static boolean inside(List<LocalDate[]> ranges, LocalDate from, LocalDate to) {
        for (LocalDate[] r : ranges) {
            if (!from.isBefore(r[0]) && !to.isAfter(r[1])) {
                return true;
            }
        }
        return false;
    }

    /** 창 파일 하나의 행들(접수번호 순). */
    public static List<JsonNode> readWindow(Path file) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        JSON.readTree(file.toFile()).forEach(out::add);
        return out;
    }

    /** 창 파일 이름의 시작일. */
    static LocalDate windowStart(Path file) {
        return LocalDate.parse(file.getFileName().toString().substring(0, 10));
    }

    /** 창 파일 이름의 끝날({@code <시작>_<끝>.json}). */
    static LocalDate windowEnd(Path file) {
        return LocalDate.parse(file.getFileName().toString().substring(11, 21));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(CallBudget.SEOUL));
    }

    private static void write(Path f, JsonNode node) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), node);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
