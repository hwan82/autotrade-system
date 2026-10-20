package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.config.Secret;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DartDisclosureCollectorTest {

    @TempDir
    Path cache;

    /** 서울 정오. */
    static Clock seoulNoon(String date) {
        return Clock.fixed(Instant.parse(date + "T03:00:00Z"), ZoneOffset.UTC);
    }

    private DartDisclosureCollector collector(FakeHttp http, Clock clock, int cap) {
        CallBudget budget = new CallBudget(cache, "opendart", "disclosures", cap, 20_000, clock);
        ApiCaller caller = new ApiCaller(http, budget, new Redaction(List.of(Secret.of("DARTKEY"))), ms -> {}, 0);
        return new DartDisclosureCollector(new OpenDartClient(caller, budget, "https://dart.test/api", "DARTKEY"), cache, clock);
    }

    /** 합성 공시검색 응답. 행: corpCls;corpCode;stockCode;corpName;reportNm;rceptNo;rceptDt */
    static String page(int page, int totalCount, int totalPage, String... items) {
        if (totalCount == 0) {
            return "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}";
        }
        StringBuilder sb = new StringBuilder("{\"status\":\"000\",\"message\":\"정상\",\"page_no\":").append(page)
                .append(",\"page_count\":100,\"total_count\":").append(totalCount).append(",\"total_page\":").append(totalPage)
                .append(",\"list\":[");
        for (int i = 0; i < items.length; i++) {
            String[] f = items[i].split(";", -1);
            sb.append(i > 0 ? "," : "").append("{\"corp_cls\":\"").append(f[0]).append("\",\"corp_code\":\"").append(f[1])
                    .append("\",\"stock_code\":\"").append(f[2]).append("\",\"corp_name\":\"").append(f[3])
                    .append("\",\"report_nm\":\"").append(f[4]).append("\",\"rcept_no\":\"").append(f[5])
                    .append("\",\"flr_nm\":\"회사\",\"rcept_dt\":\"").append(f[6]).append("\",\"rm\":\"유\"}");
        }
        return sb.append("]}").toString();
    }

    static String row(String type, String date, int seq) {
        return "Y;0000000" + (seq % 10) + ";00000" + (seq % 10) + ";회사" + seq + ";" + (type.equals("B") ? "주요사항보고서(유상증자결정)"
                : "단일판매ㆍ공급계약체결") + ";" + date + String.format("%06d", seq) + ";" + date;
    }

    /** 월마다 유형별 한 건씩, 9월 B는 두 쪽(3건). */
    static BiFunction<URI, Integer, HttpGetter.Response> server() {
        return (u, n) -> {
            String type = FakeHttp.query(u, "pblntf_ty");
            String bgn = FakeHttp.query(u, "bgn_de");
            int pageNo = Integer.parseInt(FakeHttp.query(u, "page_no"));
            assertThat(FakeHttp.query(u, "last_reprt_at")).isEqualTo("N");
            assertThat(FakeHttp.query(u, "corp_code")).isNull();
            if (type.equals("B") && bgn.equals("20260901")) {
                return FakeHttp.ok(pageNo == 1 ? page(1, 3, 2, row("B", "20260902", 1), row("B", "20260903", 2))
                        : page(2, 3, 2, row("B", "20260910", 3)));
            }
            String day = bgn.substring(0, 6) + "05";
            if (bgn.compareTo(day) > 0) {
                return FakeHttp.ok(page(1, 0, 0));
            }
            return FakeHttp.ok(page(1, 1, 1, row(type, day, type.equals("B") ? 4 : 5)));
        };
    }

    @Test
    void 최근_창부터_유형별로_받고_다_받은_창은_다시_부르지_않는다() throws IOException {
        FakeHttp http = new FakeHttp(server());
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-16"), 100);
        DartDisclosureCollector.Result r = c.collect(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 15), List.of("B", "I"));
        assertThat(r.stopped()).isNull();
        assertThat(r.windows()).isEqualTo(4);
        List<String> order = new ArrayList<>();
        for (URI u : http.calls) {
            order.add(FakeHttp.query(u, "pblntf_ty") + FakeHttp.query(u, "bgn_de") + "-" + FakeHttp.query(u, "end_de") + "p"
                    + FakeHttp.query(u, "page_no"));
        }
        assertThat(order).containsExactly("B20260901-20260915p1", "B20260901-20260915p2", "I20260901-20260915p1",
                "B20260801-20260831p1", "I20260801-20260831p1");
        assertThat(r.scanned().get("B")).hasSize(1);
        assertThat(r.scanned().get("B").get(0)).containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 15));
        List<JsonNode> sep = DartDisclosureCollector.readWindow(c.windowFiles(List.of("B")).get("B").get(1));
        assertThat(sep).extracting(n -> n.path("rcept_no").asText())
                .containsExactly("20260902000001", "20260903000002", "20260910000003");
        assertThat(sep.get(0).has("rm")).isFalse(); // 남길 칸만
        assertThat(sep.get(0).path("corp_name").asText()).isEqualTo("회사1");

        http.calls.clear();
        DartDisclosureCollector next = collector(http, seoulNoon("2026-09-17"), 100);
        next.collect(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 16), List.of("B", "I"));
        assertThat(http.calls).hasSize(2); // 9-16 하루만 유형별로
        assertThat(http.calls).allSatisfy(u -> assertThat(FakeHttp.query(u, "bgn_de")).isEqualTo("20260916"));
    }

    /**
     * 창 파일을 쓴 뒤 훑은 구간을 적기 전에 끝난 실행이 남긴 파일(PR #196 architect F1): 다음 실행이 지우고 다시 받으므로 같은 공시가
     * 두 창에 들어가지 않는다. 훑은 구간 안 창끼리 겹치면 스냅샷을 만들지 않고 실패한다.
     */
    @Test
    void 훑은_구간을_적기_전에_끝난_창_파일은_두_번_세지_않는다() throws IOException {
        FakeHttp http = new FakeHttp(server());
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-16"), 100);
        c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B"));
        Path windows = cache.resolve("dart-disclosures").resolve("windows").resolve("B");
        // 다음 날 실행이 9-16 창 파일을 쓴 뒤 훑은 구간을 적기 전에 끝났다고 친다(이름만 다른 남은 파일)
        Files.copy(windows.resolve("2026-09-01_2026-09-15.json"), windows.resolve("2026-09-16_2026-09-16.json"));
        assertThat(c.windowFiles(List.of("B")).get("B")).as("훑은 구간 밖 창은 스냅샷에 넣지 않음")
                .extracting(f -> f.getFileName().toString()).containsExactly("2026-09-01_2026-09-15.json");
        DartDisclosureCollector next = collector(http, seoulNoon("2026-09-17"), 100);
        next.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16), List.of("B"));
        assertThat(next.windowFiles(List.of("B")).get("B")).extracting(f -> f.getFileName().toString())
                .containsExactly("2026-09-01_2026-09-15.json", "2026-09-16_2026-09-16.json");
        assertThat(DartDisclosureCollector.readWindow(windows.resolve("2026-09-16_2026-09-16.json"))).as("다시 받은 9-16 창").isEmpty();
        // state.json을 잃으면 훑은 구간이 없으므로 창 파일을 쓰지 않고, 다음 실행이 지우고 다시 받는다
        Files.delete(cache.resolve("dart-disclosures").resolve("state.json"));
        assertThat(next.windowFiles(List.of("B")).get("B")).isEmpty();
        next.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16), List.of("B"));
        try (var s = Files.list(windows)) {
            assertThat(s.map(f -> f.getFileName().toString()).toList()).containsExactly("2026-09-01_2026-09-16.json");
        }
        // 훑은 구간 안에서 창끼리 겹치면 실패
        Files.copy(windows.resolve("2026-09-01_2026-09-16.json"), windows.resolve("2026-09-10_2026-09-16.json"));
        assertThatThrownBy(() -> next.windowFiles(List.of("B"))).isInstanceOf(IOException.class)
                .hasMessageContaining("겹칩니다");
    }

    @Test
    void 창_도중_상한에_닿으면_멈춘_사유를_돌려주고_다음_실행이_남은_쪽만_받는다() throws IOException {
        FakeHttp http = new FakeHttp(server());
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-16"), 1);
        DartDisclosureCollector.Result r = c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B"));
        assertThat(r.stopped()).contains("상한");
        assertThat(r.scanned().get("B")).isEmpty();
        assertThat(cache.resolve("dart-disclosures/partial/B_2026-09-01_2026-09-15/page-00001.json")).exists();

        http.calls.clear();
        DartDisclosureCollector.Result r2 = collector(http, seoulNoon("2026-09-17"), 100)
                .collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B"));
        assertThat(r2.stopped()).isNull();
        assertThat(http.calls).hasSize(1);
        assertThat(FakeHttp.query(http.calls.get(0), "page_no")).isEqualTo("2");
        assertThat(cache.resolve("dart-disclosures/partial")).isEmptyDirectory();
        assertThat(DartDisclosureCollector.readWindow(c.windowFiles(List.of("B")).get("B").get(0))).hasSize(3);
    }

    @Test
    void 이어_받을_때_전체_건수가_바뀌었으면_창을_처음부터_한_번_다시_받는다() throws IOException {
        int[] round = {0};
        FakeHttp http = new FakeHttp((u, n) -> {
            int p = Integer.parseInt(FakeHttp.query(u, "page_no"));
            if (round[0] == 0) {
                return FakeHttp.ok(page(1, 2, 2, row("B", "20260902", 1)));
            }
            // 둘째 날: 건수 3건으로 바뀜
            return FakeHttp.ok(p == 1 ? page(1, 3, 2, row("B", "20260902", 1), row("B", "20260903", 2))
                    : page(2, 3, 2, row("B", "20260904", 3)));
        });
        collector(http, seoulNoon("2026-09-16"), 1).collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B"));
        round[0] = 1;
        http.calls.clear();
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-17"), 100);
        c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B"));
        assertThat(http.calls).extracting(u -> FakeHttp.query(u, "page_no")).containsExactly("2", "1", "2");
        assertThat(DartDisclosureCollector.readWindow(c.windowFiles(List.of("B")).get("B").get(0))).hasSize(3);
    }

    @Test
    void 받은_공시_수가_total_count와_다르면_창을_남기지_않고_실패한다() {
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok(page(1, 3, 1, row("B", "20260902", 1), row("B", "20260903", 2))));
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-16"), 100);
        assertThatThrownBy(() -> c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B")))
                .isInstanceOf(IOException.class).hasMessageContaining("건수가 맞지 않습니다");
        assertThat(Files.exists(cache.resolve("dart-disclosures/windows"))).isFalse();
    }

    @Test
    void 쪽이_밀려_같은_공시가_두_번_오면_하나로_센다() throws IOException {
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok(FakeHttp.query(u, "page_no").equals("1")
                ? page(1, 2, 2, row("B", "20260902", 1)) : page(2, 2, 2, row("B", "20260902", 1), row("B", "20260903", 2))));
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-16"), 100);
        c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B"));
        assertThat(DartDisclosureCollector.readWindow(c.windowFiles(List.of("B")).get("B").get(0))).hasSize(2);
    }

    @Test
    void 응답_행_접수일이_요청_구간_밖이면_실패한다() {
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok(page(1, 1, 1, row("B", "20260830", 1))));
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-16"), 100);
        assertThatThrownBy(() -> c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B")))
                .isInstanceOf(IOException.class).hasMessageContaining("구간 밖");
    }

    @Test
    void 오늘_이후와_모르는_유형은_거부한다() {
        FakeHttp http = new FakeHttp(server());
        DartDisclosureCollector c = collector(http, seoulNoon("2026-09-16"), 100);
        assertThatThrownBy(() -> c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16), List.of("B")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("어제까지");
        assertThatThrownBy(() -> c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("모르는 공시 유형");
        assertThatThrownBy(() -> c.collect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), List.of("B", "B")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("겹칩니다");
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 창은_달력_월_경계로_자른다() {
        assertThat(DartDisclosureCollector.monthWindows("B", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 2)))
                .containsExactly(new DartDisclosureCollector.Window("B", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 31)),
                        new DartDisclosureCollector.Window("B", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                        new DartDisclosureCollector.Window("B", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2)));
    }
}
