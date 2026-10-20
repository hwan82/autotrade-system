package com.autotrade.app.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OpenDART 사업보고서 재무 증분 수집(MD-10·11, ADR-008 2.5, PR #34 후속 과제).
 *
 * <ol>
 *   <li><b>공시 훑기</b>: 공시검색으로 사업보고서(정정 포함)를 3개월 창씩 접수일 순으로 훑어
 *       {@code <cache>/dart/filings/<접수번호>.json}에 둔다. 훑은 구간들을 {@code state.json}의 {@code scanned}에
 *       기록해 다음 실행은 요청 범위 중 아직 훑지 않은 구간만 훑는다(오늘은 공시가 더 들어올 수 있어 어제까지만).</li>
 *   <li><b>계정 받기</b>: 계정 응답이 없는 공시마다 주요계정을 불러 {@code accounts/<접수번호>.json}에 둔다. 응답의 접수번호가
 *       그 공시와 같을 때만 그 공시의 값으로 인정한다. 이렇게 매일 증분으로 받으면 원 공시 값을 정정 전에 확보한다(MD-10
 *       기본 규칙). 응답이 이미 뒤 정정 값을 담고 있으면 그 공시는 "값 모름"(대체 규칙 대상)으로 남긴다. 응답이 아직 앞
 *       공시를 가리키거나 자료 없음(013)이면(반영 지연) {@value #PENDING_DAYS}일 동안 다음 실행에서 다시 부르고, 그 뒤엔 "값 모름"으로 둔다.</li>
 * </ol>
 * 호출 상한·020 응답이면 그 자리에서 멈추고, 받은 것은 캐시에 남아 다음 날 이어 받는다.
 */
public final class DartFinancialCollector {

    static final int PENDING_DAYS = 14;
    static final Set<String> LISTED_CLASSES = Set.of("Y", "K", "N");
    private static final Pattern PERIOD = Pattern.compile("\\((\\d{4})\\.(\\d{2})\\)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenDartClient client;
    private final Path dir;
    private final Clock clock;

    public DartFinancialCollector(OpenDartClient client, Path cacheDir, Clock clock) {
        this.client = client;
        this.dir = cacheDir.resolve("dart");
        this.clock = clock;
    }

    /** 이번 실행 결과. */
    public record Result(int newFilings, int fetchedAccounts, int pending, LocalDate scannedThrough) {}

    public Result collect(LocalDate from, LocalDate to) throws IOException {
        LocalDate today = today();
        if (!to.isBefore(today)) {
            throw new IllegalArgumentException("공시 훑기는 어제까지만 합니다: " + to);
        }
        int newFilings = scan(from, to);
        int[] fetched = fetchAccounts();
        return new Result(newFilings, fetched[0], fetched[1], scannedThrough());
    }

    /** 다 훑은 마지막 날(훑은 구간들의 끝 중 가장 늦은 날). 아직 없으면 {@code null}. */
    public LocalDate scannedThrough() throws IOException {
        List<LocalDate[]> ranges = scannedRanges();
        return ranges.isEmpty() ? null : ranges.get(ranges.size() - 1)[1];
    }

    /** 훑은 구간들(시작일 순, 겹치거나 이어지는 구간은 합친 상태). */
    List<LocalDate[]> scannedRanges() throws IOException {
        Path f = dir.resolve("state.json");
        List<LocalDate[]> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
        for (JsonNode r : JSON.readTree(f.toFile()).path("scanned")) {
            out.add(new LocalDate[] {LocalDate.parse(r.path("from").asText()), LocalDate.parse(r.path("to").asText())});
        }
        return merge(out);
    }

    /** 요청 범위 중 아직 훑지 않은 구간만 훑는다(F1: --from을 앞당기면 그 앞 구간도 훑는다). */
    private int scan(LocalDate from, LocalDate to) throws IOException {
        List<LocalDate[]> scanned = scannedRanges();
        int added = 0;
        for (LocalDate[] gap : gaps(scanned, from, to)) {
            LocalDate start = gap[0];
            while (!start.isAfter(gap[1])) {
                LocalDate end = start.plusMonths(3).minusDays(1);
                end = end.isAfter(gap[1]) ? gap[1] : end;
                added += scanWindow(start, end);
                scanned.add(new LocalDate[] {start, end});
                scanned = merge(scanned);
                writeState(scanned);
                start = end.plusDays(1);
            }
        }
        return added;
    }

    private int scanWindow(LocalDate start, LocalDate end) throws IOException {
        int added = 0;
        int page = 1;
        int totalPages;
        do {
            JsonNode body = client.annualReportList(start, end, page);
            totalPages = body.path("total_page").asInt(0);
            for (JsonNode item : body.path("list")) {
                if (accept(item)) {
                    Path f = dir.resolve("filings").resolve(item.path("rcept_no").asText() + ".json");
                    if (!Files.exists(f)) {
                        write(f, item);
                        added++;
                    }
                }
            }
            page++;
        } while (page <= totalPages);
        return added;
    }

    /** [from, to] 중 {@code scanned}(합쳐진 구간, 시작일 순)에 없는 구간들. */
    static List<LocalDate[]> gaps(List<LocalDate[]> scanned, LocalDate from, LocalDate to) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate cursor = from;
        for (LocalDate[] r : scanned) {
            if (cursor.isAfter(to)) {
                break;
            }
            if (r[1].isBefore(cursor)) {
                continue;
            }
            if (r[0].isAfter(cursor)) {
                LocalDate end = r[0].minusDays(1);
                out.add(new LocalDate[] {cursor, end.isAfter(to) ? to : end});
            }
            cursor = r[1].plusDays(1);
        }
        if (!cursor.isAfter(to)) {
            out.add(new LocalDate[] {cursor, to});
        }
        return out;
    }

    static List<LocalDate[]> merge(List<LocalDate[]> ranges) {
        List<LocalDate[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing((LocalDate[] r) -> r[0]));
        List<LocalDate[]> out = new ArrayList<>();
        for (LocalDate[] r : sorted) {
            LocalDate[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && !r[0].isAfter(last[1].plusDays(1))) {
                if (r[1].isAfter(last[1])) {
                    last[1] = r[1];
                }
            } else {
                out.add(new LocalDate[] {r[0], r[1]});
            }
        }
        return out;
    }

    private void writeState(List<LocalDate[]> scanned) throws IOException {
        ObjectNode state = JSON.createObjectNode();
        var arr = state.putArray("scanned");
        for (LocalDate[] r : scanned) {
            arr.addObject().put("from", r[0].toString()).put("to", r[1].toString());
        }
        write(dir.resolve("state.json"), state);
    }

    static boolean accept(JsonNode item) {
        return LISTED_CLASSES.contains(item.path("corp_cls").asText())
                && item.path("stock_code").asText("").strip().matches("[0-9A-Z]{6}")
                && item.path("rcept_no").asText("").matches("\\d{14}")
                && item.path("rcept_dt").asText("").matches("\\d{8}")
                && businessYear(item.path("report_nm").asText("")) > 0;
    }

    /** 보고서명 "사업보고서 (2024.12)"의 연도. 없으면 0. 3월 결산 등도 괄호 안 연도를 사업연도로 본다 `[가정]`. */
    static int businessYear(String reportName) {
        Matcher m = PERIOD.matcher(reportName);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 공시 목록(접수일, 접수번호 순). */
    public List<JsonNode> filings() throws IOException {
        Path f = dir.resolve("filings");
        if (!Files.exists(f)) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(f)) {
            for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                out.add(JSON.readTree(p.toFile()));
            }
        }
        out.sort(Comparator.comparing((JsonNode n) -> n.path("rcept_dt").asText()).thenComparing(n -> n.path("rcept_no").asText()));
        return out;
    }

    /** 저장된 계정 응답. 없으면(아직 안 받았거나 대기) {@code null}. */
    public JsonNode accountsOf(String receiptNo) throws IOException {
        Path f = dir.resolve("accounts").resolve(receiptNo + ".json");
        return Files.exists(f) ? JSON.readTree(f.toFile()) : null;
    }

    private int[] fetchAccounts() throws IOException {
        int fetched = 0;
        int pending = 0;
        LocalDate today = today();
        for (JsonNode filing : filings()) {
            String no = filing.path("rcept_no").asText();
            if (accountsOf(no) != null) {
                continue;
            }
            JsonNode body = client.accounts(filing.path("corp_code").asText(), businessYear(filing.path("report_nm").asText()));
            fetched++;
            String answered = body.path("list").path(0).path("rcept_no").asText("");
            boolean known = "000".equals(body.path("status").asText()) && answered.equals(no);
            LocalDate filed = LocalDate.parse(filing.path("rcept_dt").asText(), OpenDartClient.YMD);
            // 아직 이 공시 값이 반영되지 않았다(앞 공시 값을 돌려주거나 013 자료 없음): 기한 안이면 다음 실행에서 다시
            boolean notYet = answered.isEmpty() || answered.compareTo(no) < 0;
            if (!known && notYet && ChronoUnit.DAYS.between(filed, today) < PENDING_DAYS) {
                pending++;
                continue;
            }
            ObjectNode rec = JSON.createObjectNode();
            rec.put("fetchedAt", clock.instant().toString());
            rec.put("valuesFor", known ? no : "");
            rec.set("response", body);
            write(dir.resolve("accounts").resolve(no + ".json"), rec);
        }
        return new int[] {fetched, pending};
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
}
