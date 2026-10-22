package com.autotrade.app.collector;

import com.autotrade.marketdata.daily.Csv;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 한국투자 뉴스 제목 수집(D-136). 지금부터 거슬러 올라가며 한 쪽씩 받고(커서 = 그 쪽의 가장 옛 행 날짜·시각), 받은 제목을 날짜별
 * 캐시 파일에 쌓는다. 어디부터 어디까지 빠짐없이 훑었는지를 <b>훑은 구간</b>({@code coverage.json})으로 남겨, 지표가 구간 밖 날을
 * "기사 0건"으로 오해하지 않게 한다(ADR-012 4.2 "coverage가 덮지 않으면 계산 실패").
 *
 * <p>한 번 실행 순서: ① 지금부터 마지막 구간 끝 {@value #OVERLAP_MINUTES}분 전까지(늦게 올라온 기사를 다시 훑음) ② 구간 사이 빈틈을
 * 최근 것부터 ③ {@code --from}이 가장 옛 구간보다 앞이면 그 날 00:00까지 거슬러(백필). 호출 상한에 닿으면 그때까지 받은 것과
 * 구간을 저장하고 멈춘다(다음 실행이 빈틈을 메운다).
 *
 * <p>시각은 한국투자 응답의 {@code data_dt}·{@code data_tm}(한국 시각 `[가정]`)이다. 행 식별은 날짜와 일련번호
 * ({@code cntt_usiq_srno}) 쌍이다.
 */
final class KisNewsCollector {

    static final String DIR = "kis-news";
    static final String COVERAGE_FILE = "coverage.json";
    static final List<String> DAY_HEADER = List.of("serial", "date", "time", "provider_code", "provider", "category", "codes",
            "title");
    /** 늦게 올라오는 기사(게시 시각보다 늦게 목록에 실림)를 다시 훑는 폭 `[가정]`. */
    static final int OVERLAP_MINUTES = 10;
    /** 새 행이 없는 쪽이 이만큼 이어지면 커서가 걸린 것으로 보고 그 걸음을 멈춘다(2차 자료와 같은 값). */
    static final int MAX_EMPTY_PAGES = 5;
    /** 빈 쪽을 받아 전날로 넘어가기를 이만큼 이어서 하면 더 옛 제목이 없는 것으로 본다(연휴 길이보다 길게) `[가정]`. */
    static final int MAX_EMPTY_DAYS = 7;
    static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");
    /** 파일에 쓰는 시각 형식(초까지 늘 씀, 한국 시각). */
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 빠짐없이 훑은 구간(양 끝 포함, 한국 시각). */
    record Interval(LocalDateTime from, LocalDateTime to) {}

    /**
     * 걸음 하나의 결과. {@code complete}가 거짓이면 {@code reason}에 까닭. {@code crowdedSeconds}는 한 쪽이 모두 같은 초의 행이라
     * 그 초의 나머지 행을 받았는지 알 수 없이 1초 건너뛴 횟수(품질 리포트에 적는다, 0이 정상).
     */
    record Walk(String kind, LocalDateTime top, LocalDateTime reached, boolean complete, String reason, int pages,
            int fresh, int crowdedSeconds, String stopped) {}

    /** 실행 결과. {@code stopped}는 호출 상한으로 멈췄을 때의 메시지(아니면 null). */
    record Result(List<Walk> walks, List<Interval> coverage, LocalDateTime floor, String stopped) {
        int pages() {
            return walks.stream().mapToInt(Walk::pages).sum();
        }

        int fresh() {
            return walks.stream().mapToInt(Walk::fresh).sum();
        }
    }

    private final KisNewsClient client;
    private final Path root;
    private final Clock clock;
    private final Map<LocalDate, Map<String, KisNewsClient.Headline>> days = new TreeMap<>();
    private final Map<LocalDate, Boolean> dirty = new HashMap<>();

    KisNewsCollector(KisNewsClient client, Path cacheDir, Clock clock) {
        this.client = client;
        this.root = cacheDir.resolve(DIR);
        this.clock = clock;
    }

    /**
     * @param from 백필 시작일(그 날 00:00까지 거슬러 받음). null이면 처음 실행은 오늘 00:00까지, 그 뒤로는 훑은 구간 이어 붙이기만
     */
    Result collect(LocalDate from) throws IOException {
        LocalDateTime now = LocalDateTime.now(clock.withZone(CallBudget.SEOUL)).withNano(0);
        Coverage cov = readCoverage();
        List<Walk> walks = new ArrayList<>();
        String stopped = null;
        try {
            // ① 지금부터(처음이면 오늘 00:00까지, --from 앞은 ③ 백필이 받는다)
            LocalDateTime liveStop = cov.intervals.isEmpty() ? now.toLocalDate().atStartOfDay()
                    : latestEnd(cov.intervals).minusMinutes(OVERLAP_MINUTES);
            Walk live = walk("지금", null, now, liveStop, cov);
            walks.add(live);
            if (live.stopped() != null) {
                return result(walks, cov, live.stopped());
            }
            // ② 빈틈(최근 것부터). ③ 백필
            LocalDateTime horizon = from != null ? from.atStartOfDay() : null;
            while (true) {
                List<Interval> sorted = sortedDesc(cov.intervals);
                Interval newer = null;
                Interval older = null;
                for (int i = 0; i + 1 < sorted.size(); i++) {
                    if (horizon == null || sorted.get(i).from().isAfter(horizon)) {
                        newer = sorted.get(i);
                        older = sorted.get(i + 1);
                        break;
                    }
                }
                if (newer == null) {
                    break;
                }
                Walk w = walk("빈틈", newer.from(), newer.from(), older.to(), cov);
                walks.add(w);
                if (w.stopped() != null) {
                    return result(walks, cov, w.stopped());
                }
                if (!w.complete()) {
                    break; // 같은 빈틈에 다시 걸리지 않게 이번 실행에서는 그만 둔다
                }
            }
            if (horizon != null) {
                LocalDateTime earliest = sortedDesc(cov.intervals).getLast().from();
                if (earliest.isAfter(horizon) && (cov.floor == null || cov.floor.isBefore(earliest))) {
                    Walk w = walk("백필", earliest, earliest, horizon, cov);
                    walks.add(w);
                    stopped = w.stopped();
                }
            }
        } finally {
            flush();
            writeCoverage(cov);
        }
        return result(walks, cov, stopped);
    }

    private static Result result(List<Walk> walks, Coverage cov, String stopped) {
        return new Result(List.copyOf(walks), List.copyOf(sortedAsc(cov.intervals)), cov.floor, stopped);
    }

    /**
     * 커서({@code start}, null이면 지금)부터 {@code stopAt}에 닿을 때까지 거슬러 받는다. 호출 상한에 닿으면 {@link Walk#stopped()}에
     * 적고 돌아온다. 그 밖의 예외가 나도 그때까지 확실히 훑은 구간을 {@code cov}에 더한 뒤 다시 던진다.
     */
    private Walk walk(String kind, LocalDateTime start, LocalDateTime top, LocalDateTime stopAt, Coverage cov)
            throws IOException {
        String date = start == null ? YMD.format(top) : YMD.format(start);
        String time = start == null ? "" : HMS.format(start);
        LocalDateTime oldest = null;
        int pages = 0;
        int fresh = 0;
        int emptyRun = 0;
        int emptyDays = 0;
        int crowded = 0;
        boolean complete = false;
        String reason = null;
        String stopped = null;
        try {
            while (true) {
                List<KisNewsClient.Headline> page = client.page(date, time);
                pages++;
                if (page.isEmpty()) {
                    // 그날 커서 앞에 제목이 없으면 빈 쪽이 올 수 있다 `[가정]`: 전날 23:59:59로 넘어간다. 며칠 이어지면 끝으로 본다
                    LocalDate d = LocalDate.parse(date, YMD);
                    if (!d.isAfter(stopAt.toLocalDate())) {
                        complete = true; // 멈출 날의 커서 앞에 제목이 없다: 멈출 시각까지 더 받을 것이 없다
                        break;
                    }
                    if (++emptyDays < MAX_EMPTY_DAYS) {
                        date = YMD.format(d.minusDays(1));
                        time = "235959";
                        continue;
                    }
                    reason = "빈 쪽(한국투자가 더 옛 제목을 주지 않음)";
                    // 다음 실행이 같은 곳을 다시 거슬러 가지 않게 이번에 훑은 구간의 시작을 바닥으로 적는다
                    cov.floor = oldest != null ? oldest.plusSeconds(1) : (start != null ? start : top);
                    break;
                }
                emptyDays = 0;
                int added = 0;
                LocalDateTime pageOldest = null;
                for (KisNewsClient.Headline h : page) {
                    LocalDateTime at = at(h);
                    if (pageOldest == null || at.isBefore(pageOldest)) {
                        pageOldest = at;
                    }
                    if (!at.isAfter(top) && put(h)) {
                        added++;
                    }
                }
                fresh += added;
                oldest = oldest == null || pageOldest.isBefore(oldest) ? pageOldest : oldest;
                if (!pageOldest.isAfter(stopAt)) {
                    complete = true;
                    break;
                }
                LocalDateTime next;
                if (added == 0) {
                    if (++emptyRun >= MAX_EMPTY_PAGES) {
                        reason = "새 제목 없는 쪽이 " + MAX_EMPTY_PAGES + "번 이어짐(커서가 움직이지 않음)";
                        break;
                    }
                    LocalDateTime first = at(page.getFirst());
                    if (page.size() > 1 && page.stream().allMatch(h -> at(h).equals(first))) {
                        crowded++;
                    }
                    next = pageOldest.minusSeconds(1);
                } else {
                    emptyRun = 0;
                    next = pageOldest;
                }
                LocalDateTime current = LocalDateTime.of(LocalDate.parse(date, YMD),
                        time.isEmpty() ? LocalTime.MAX.withNano(0) : LocalTime.parse(time, HMS));
                if (!next.isBefore(current)) {
                    next = current.minusSeconds(1);
                }
                if (next.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                    next = next.minusSeconds(1); // 시각 000000은 "시각 없음"(= 그날 지금)으로 읽힌다 `[가정]` 2차 자료
                }
                date = YMD.format(next);
                time = HMS.format(next);
            }
        } catch (CallBudget.BudgetExhausted e) {
            stopped = e.getMessage();
        } finally {
            if (complete) {
                add(cov, new Interval(stopAt, top));
            } else if (oldest != null && oldest.isBefore(top)) {
                // 가장 옛 행과 같은 초의 행이 다음 쪽에 더 있을 수 있어 그 다음 초부터만 훑은 것으로 본다
                add(cov, new Interval(oldest.plusSeconds(1), top));
            }
        }
        return new Walk(kind, top, complete ? stopAt : oldest, complete, reason, pages, fresh, crowded, stopped);
    }

    static LocalDateTime at(KisNewsClient.Headline h) {
        return LocalDateTime.of(LocalDate.parse(h.date(), YMD), LocalTime.parse(h.time(), HMS));
    }

    /** 새 행이면 true. 같은 날짜·일련번호가 이미 있으면 처음 받은 것을 둔다. */
    private boolean put(KisNewsClient.Headline h) throws IOException {
        LocalDate d = LocalDate.parse(h.date(), YMD);
        Map<String, KisNewsClient.Headline> m = day(d);
        if (m.containsKey(h.serial())) {
            return false;
        }
        m.put(h.serial(), h);
        dirty.put(d, true);
        return true;
    }

    private Map<String, KisNewsClient.Headline> day(LocalDate d) throws IOException {
        Map<String, KisNewsClient.Headline> m = days.get(d);
        if (m == null) {
            m = new LinkedHashMap<>();
            for (KisNewsClient.Headline h : readDay(root, d)) {
                m.put(h.serial(), h);
            }
            days.put(d, m);
        }
        return m;
    }

    /** 날짜 캐시 파일 하나(없으면 빈 목록). */
    static List<KisNewsClient.Headline> readDay(Path root, LocalDate d) throws IOException {
        Path f = dayFile(root, d);
        if (!Files.exists(f)) {
            return List.of();
        }
        List<KisNewsClient.Headline> out = new ArrayList<>();
        for (List<String> r : Csv.read(f, DAY_HEADER)) {
            out.add(new KisNewsClient.Headline(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5),
                    r.get(6).isEmpty() ? List.of() : List.of(r.get(6).split(";")), r.get(7)));
        }
        return out;
    }

    static Path dayFile(Path root, LocalDate d) {
        return root.resolve("days").resolve(d + ".csv");
    }

    /** 캐시에 있는 날짜(오름차순). */
    static List<LocalDate> cachedDays(Path cacheDir) throws IOException {
        Path dir = cacheDir.resolve(DIR).resolve("days");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d{4}-\\d{2}-\\d{2}\\.csv"))
                    .map(n -> LocalDate.parse(n.substring(0, 10))).sorted().toList();
        }
    }

    /** 바뀐 날짜 파일을 시각·일련번호 순으로 다시 쓴다(임시 파일에 쓰고 옮김). */
    private void flush() throws IOException {
        for (Map.Entry<LocalDate, Map<String, KisNewsClient.Headline>> e : days.entrySet()) {
            if (!dirty.getOrDefault(e.getKey(), false)) {
                continue;
            }
            Path f = dayFile(root, e.getKey());
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            List<KisNewsClient.Headline> rows = new ArrayList<>(e.getValue().values());
            rows.sort(Comparator.comparing(KisNewsClient.Headline::time).thenComparing(KisNewsClient.Headline::serial));
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(Csv.join(DAY_HEADER));
                w.write('\n');
                for (KisNewsClient.Headline h : rows) {
                    w.write(Csv.join(List.of(h.serial(), h.date(), h.time(), h.providerCode(), h.provider(), h.category(),
                            String.join(";", h.codes()), h.title())));
                    w.write('\n');
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty.put(e.getKey(), false);
        }
    }

    /** 훑은 구간 상태. {@code floor}는 백필이 빈 쪽을 받아 더 거슬러 갈 수 없음을 안 시각(그 앞은 다시 시도하지 않음). */
    static final class Coverage {
        final List<Interval> intervals = new ArrayList<>();
        LocalDateTime floor;
    }

    Coverage readCoverage() throws IOException {
        return readCoverage(root);
    }

    static Coverage readCoverage(Path root) throws IOException {
        Coverage c = new Coverage();
        Path f = root.resolve(COVERAGE_FILE);
        if (!Files.exists(f)) {
            return c;
        }
        JsonNode n = JSON.readTree(f.toFile());
        for (JsonNode i : n.path("intervals")) {
            c.intervals.add(new Interval(LocalDateTime.parse(i.path("from").asText()), LocalDateTime.parse(i.path("to").asText())));
        }
        if (n.hasNonNull("floor")) {
            c.floor = LocalDateTime.parse(n.path("floor").asText());
        }
        return c;
    }

    private void writeCoverage(Coverage c) throws IOException {
        Files.createDirectories(root);
        ObjectNode n = JSON.createObjectNode();
        ArrayNode a = n.putArray("intervals");
        for (Interval i : sortedAsc(c.intervals)) {
            a.addObject().put("from", ISO.format(i.from())).put("to", ISO.format(i.to()));
        }
        if (c.floor != null) {
            n.put("floor", ISO.format(c.floor));
        } else {
            n.putNull("floor");
        }
        Path f = root.resolve(COVERAGE_FILE);
        Path tmp = root.resolve(COVERAGE_FILE + ".tmp");
        JSON.writeValue(tmp.toFile(), n);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 구간을 더하고 겹치거나 1초 안으로 붙는 구간을 합친다. */
    static void add(Coverage c, Interval in) {
        if (in.to().isBefore(in.from())) {
            return;
        }
        List<Interval> all = new ArrayList<>(c.intervals);
        all.add(in);
        all.sort(Comparator.comparing(Interval::from));
        List<Interval> merged = new ArrayList<>();
        for (Interval i : all) {
            if (!merged.isEmpty() && !i.from().isAfter(merged.getLast().to().plusSeconds(1))) {
                Interval last = merged.removeLast();
                merged.add(new Interval(last.from(), i.to().isAfter(last.to()) ? i.to() : last.to()));
            } else {
                merged.add(i);
            }
        }
        c.intervals.clear();
        c.intervals.addAll(merged);
    }

    private static LocalDateTime latestEnd(List<Interval> intervals) {
        return intervals.stream().map(Interval::to).max(Comparator.naturalOrder()).orElseThrow();
    }

    static List<Interval> sortedAsc(List<Interval> in) {
        List<Interval> s = new ArrayList<>(in);
        s.sort(Comparator.comparing(Interval::from));
        return s;
    }

    private static List<Interval> sortedDesc(List<Interval> in) {
        List<Interval> s = sortedAsc(in);
        java.util.Collections.reverse(s);
        return s;
    }
}
