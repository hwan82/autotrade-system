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
 *       {@code <cache>/dart/filings/<접수번호>.json}에 둔다. 다 훑은 날짜를 {@code state.json}의 {@code scannedThrough}에
 *       기록해 다음 실행은 그 다음 날부터 훑는다(오늘은 공시가 더 들어올 수 있어 어제까지만).</li>
 *   <li><b>계정 받기</b>: 계정 응답이 없는 공시마다 주요계정을 불러 {@code accounts/<접수번호>.json}에 둔다. 응답의 접수번호가
 *       그 공시와 같을 때만 그 공시의 값으로 인정한다. 이렇게 매일 증분으로 받으면 원 공시 값을 정정 전에 확보한다(MD-10
 *       기본 규칙). 응답이 이미 뒤 정정 값을 담고 있으면 그 공시는 "값 모름"(대체 규칙 대상)으로 남긴다. 응답이 아직 앞
 *       공시를 가리키면(반영 지연) {@value #PENDING_DAYS}일 동안 다음 실행에서 다시 부르고, 그 뒤엔 "값 모름"으로 둔다.</li>
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

    /** 다 훑은 마지막 날. 아직 없으면 {@code null}. */
    public LocalDate scannedThrough() throws IOException {
        Path f = dir.resolve("state.json");
        if (!Files.exists(f)) {
            return null;
        }
        String v = JSON.readTree(f.toFile()).path("scannedThrough").asText("");
        return v.isEmpty() ? null : LocalDate.parse(v);
    }

    private int scan(LocalDate from, LocalDate to) throws IOException {
        LocalDate done = scannedThrough();
        LocalDate start = done == null || done.isBefore(from) ? from : done.plusDays(1);
        int added = 0;
        while (!start.isAfter(to)) {
            LocalDate end = start.plusMonths(3).minusDays(1);
            end = end.isAfter(to) ? to : end;
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
            ObjectNode state = JSON.createObjectNode().put("scannedThrough", end.toString());
            write(dir.resolve("state.json"), state);
            start = end.plusDays(1);
        }
        return added;
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
            if (!known && !answered.isEmpty() && answered.compareTo(no) < 0
                    && ChronoUnit.DAYS.between(filed, today) < PENDING_DAYS) {
                pending++; // 아직 앞 공시 값을 돌려준다: 다음 실행에서 다시
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
