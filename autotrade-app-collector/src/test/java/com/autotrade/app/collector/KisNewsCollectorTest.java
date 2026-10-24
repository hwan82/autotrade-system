package com.autotrade.app.collector;

import static com.autotrade.app.collector.KisNewsFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.Secret;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KisNewsCollectorTest {

    @TempDir
    Path cache;

    /** 서울 시각(2026-10-21 hh:mm). */
    static Clock seoul(String dateTime) {
        return Clock.fixed(LocalDateTime.parse(dateTime).atZone(CallBudget.SEOUL).toInstant(), ZoneOffset.UTC);
    }

    private KisNewsCollector collector(FakeHttp http, Clock clock, int cap) {
        CallBudget budget = new CallBudget(cache.resolve("state"), "kis-news", cap, clock);
        Redaction r = new Redaction(List.of());
        KisNewsClient c = new KisNewsClient(new ApiCaller(http, budget, r, ms -> {}, 0), "https://kis.test",
                Secret.of("K"), Secret.of("S"), r);
        return new KisNewsCollector(c, cache, clock);
    }

    /** 2026-10-20 하루 동안 1시간마다 두 건, 2026-10-21 00:30~06:30 30분마다 한 건. */
    static List<KisNewsFixtures.Row> rows() {
        List<KisNewsFixtures.Row> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            out.add(row("20261020", String.format("%02d0000", h), "A" + h, h % 2 == 0 ? "005930" : ""));
            out.add(row("20261020", String.format("%02d3000", h), "B" + h, "000660;005930"));
        }
        for (int m = 30; m <= 390; m += 30) {
            out.add(row("20261021", String.format("%02d%02d00", m / 60, m % 60), "C" + m, "035420"));
        }
        return out;
    }

    @Test
    void 처음_실행은_오늘_00시까지_거슬러_받고_겹친_커서_행은_한_번만_쌓는다() throws IOException {
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows(), 4));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T07:00:00"), 100).collect(null);

        assertThat(r.coverage()).containsExactly(new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T00:00:00"),
                LocalDateTime.parse("2026-10-21T07:00:00")));
        assertThat(r.walks()).singleElement().satisfies(w -> {
            assertThat(w.complete()).isTrue();
            assertThat(w.fresh()).isEqualTo(13 + 3); // 오늘 13건 + 멈출 시각 앞 쪽에 같이 온 어제 세 건
        });
        List<KisNewsClient.Headline> today = KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 21));
        assertThat(today).hasSize(13).extracting(KisNewsClient.Headline::serial).doesNotHaveDuplicates();
        assertThat(today.getFirst().time()).isEqualTo("003000"); // 시각 순
        assertThat(http.calls.getFirst().getRawQuery()).contains("FID_INPUT_DATE_1=20261021&FID_INPUT_HOUR_1=&");
        // 다음 쪽 커서 = 앞 쪽의 가장 옛 행 시각(겹침은 일련번호로 거름)
        assertThat(KisNewsFixtures.param(http.calls.get(1), "FID_INPUT_HOUR_1")).isEqualTo("050000");
        assertThat(http.calls).hasSize(5); // 걸음의 호출 수: 06:30·05:00·03:30·02:00·00:30에서 시작하는 다섯 쪽(PR #225 QA Q3)
    }

    @Test
    void 다음_실행은_마지막_구간_끝_10분_전까지만_받고_구간을_하나로_잇는다() throws IOException {
        List<KisNewsFixtures.Row> rows = new ArrayList<>(rows());
        collector(new FakeHttp(KisNewsFixtures.server(rows, 4)), seoul("2026-10-21T07:00:00"), 100).collect(null);
        rows.add(row("20261021", "065500", "LATE", "005930")); // 07:00 실행 뒤에 목록에 실린 06:55 기사
        rows.add(row("20261021", "170000", "D1", "005930"));
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows, 4));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T18:00:00"), 100).collect(null);

        assertThat(r.coverage()).containsExactly(new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T00:00:00"),
                LocalDateTime.parse("2026-10-21T18:00:00")));
        assertThat(http.calls).hasSize(1);
        assertThat(KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 21)))
                .extracting(KisNewsClient.Headline::serial).contains("LATE", "D1").doesNotHaveDuplicates();
    }

    @Test
    void 호출_상한에_닿으면_훑은_데까지만_구간으로_남기고_다음_실행이_빈틈을_메운다() throws IOException {
        Clock morning = seoul("2026-10-21T07:00:00");
        KisNewsCollector.Result first = collector(new FakeHttp(KisNewsFixtures.server(rows(), 2)), morning, 3)
                .collect(LocalDate.of(2026, 10, 20));
        assertThat(first.stopped()).contains("상한");
        assertThat(first.walks()).singleElement().satisfies(w -> assertThat(w.complete()).isFalse());
        // 토큰 1 + 쪽 2(06:30·06:00, 06:00·05:30), 가장 옛 행 05:30과 같은 초는 다음 쪽에 더 있을 수 있어 05:30:01부터
        assertThat(first.coverage()).containsExactly(new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T05:30:01"),
                LocalDateTime.parse("2026-10-21T07:00:00")));

        // 다음 날 저녁: 지금 → 07:00 앞 10분, 그 뒤 백필(--from 2026-10-20)로 05:30:01 아래를 2026-10-20 00:00까지
        KisNewsCollector.Result next = collector(new FakeHttp(KisNewsFixtures.server(rows(), 4)), seoul("2026-10-22T18:00:00"),
                200).collect(LocalDate.of(2026, 10, 20));
        assertThat(next.stopped()).isNull();
        assertThat(next.walks()).extracting(KisNewsCollector.Walk::kind).containsExactly("지금", "백필");
        assertThat(next.coverage()).containsExactly(new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-20T00:00:00"),
                LocalDateTime.parse("2026-10-22T18:00:00")));
        assertThat(KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 20))).hasSize(48);
    }

    @Test
    void 구간_사이_빈틈은_최근_것부터_메운다() throws IOException {
        Path root = cache.resolve("kis-news");
        Files.createDirectories(root);
        Files.writeString(root.resolve(KisNewsCollector.COVERAGE_FILE), """
                {"intervals":[{"from":"2026-10-20T00:00:00","to":"2026-10-20T06:00:00"},
                              {"from":"2026-10-20T20:00:00","to":"2026-10-21T07:00:00"}],"floor":null}""");
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows(), 4));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T07:05:00"), 100).collect(null);
        assertThat(r.walks()).extracting(KisNewsCollector.Walk::kind).containsExactly("지금", "빈틈");
        assertThat(r.coverage()).containsExactly(new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-20T00:00:00"),
                LocalDateTime.parse("2026-10-21T07:05:00")));
        assertThat(KisNewsFixtures.param(http.calls.get(1), "FID_INPUT_HOUR_1")).isEqualTo("200000"); // 빈틈 위 끝부터
    }

    @Test
    void 백필이_보관_한도에_닿으면_바닥을_적고_다시_시도하지_않는다() throws IOException {
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows(), 10));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T07:00:00"), 200).collect(LocalDate.of(2026, 10, 1));
        assertThat(r.floor()).isEqualTo(LocalDateTime.parse("2026-10-20T00:00:01"));
        KisNewsCollector.Walk backfill = r.walks().getLast();
        assertThat(backfill.kind()).isEqualTo("백필");
        assertThat(backfill.complete()).isFalse();
        assertThat(backfill.reason()).contains("빈 쪽");
        // 백필 시작(10-21 00:00:00)은 000000을 보낼 수 없어 10-20 23:59:59부터(1번), 10-20 00:00:00 행에서 전날 23:59:59로(1번),
        // 빈 쪽이면 전날로 넘어가기를 6번 더 하고 7번째 빈 쪽에서 끝으로 본다
        assertThat(http.calls.stream().filter(u -> "235959".equals(KisNewsFixtures.param(u, "FID_INPUT_HOUR_1")))).hasSize(8);
        assertThat(http.calls).noneMatch(u -> "000000".equals(KisNewsFixtures.param(u, "FID_INPUT_HOUR_1")));
        assertThat(backfill.midnightCuts()).isEqualTo(1); // 10-20 00:00:00 행으로 쪽이 끝나 그 초의 나머지는 확인 못 함

        FakeHttp again = new FakeHttp(KisNewsFixtures.server(rows(), 10));
        KisNewsCollector.Result r2 = collector(again, seoul("2026-10-21T07:30:00"), 200).collect(LocalDate.of(2026, 10, 1));
        assertThat(r2.walks()).extracting(KisNewsCollector.Walk::kind).containsExactly("지금");
    }

    @Test
    void 멈출_날에_빈_쪽이면_더_받을_것이_없으므로_끝까지_간_것이다() throws IOException {
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(List.of(row("20261020", "230000", "Y", "005930")), 4));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T00:40:00"), 100).collect(null);
        // 오늘 행이 없어 첫 쪽에 어제 23:00 행이 오고, 그 시각이 멈출 시각(오늘 00:00) 앞이라 끝
        assertThat(r.walks().getFirst().complete()).isTrue();
        FakeHttp empty = new FakeHttp(KisNewsFixtures.server(List.of(), 4));
        Path other = cache.resolve("other");
        CallBudget budget = new CallBudget(other.resolve("state"), "kis-news", 100, seoul("2026-10-21T00:40:00"));
        Redaction red = new Redaction(List.of());
        KisNewsCollector c = new KisNewsCollector(new KisNewsClient(new ApiCaller(empty, budget, red, ms -> {}, 0),
                "https://kis.test", Secret.of("K"), Secret.of("S"), red), other, seoul("2026-10-21T00:40:00"));
        KisNewsCollector.Result e = c.collect(null);
        assertThat(e.walks().getFirst().complete()).isTrue();
        assertThat(e.coverage()).hasSize(1);
    }

    @Test
    void 같은_초로_가득_찬_쪽은_세어_알린다() throws IOException {
        List<KisNewsFixtures.Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("20261021", "063000", "S" + i, "005930"));
        }
        rows.add(row("20261021", "010000", "OLD", "005930"));
        KisNewsCollector.Result r = collector(new FakeHttp(KisNewsFixtures.server(rows, 3)), seoul("2026-10-21T07:00:00"), 100)
                .collect(null);
        assertThat(r.walks().getFirst().crowdedSeconds()).isEqualTo(1);
        assertThat(r.walks().getFirst().complete()).isTrue();
    }

    @Test
    void 구간_합치기는_겹치거나_1초_안으로_붙은_것만_잇는다() {
        KisNewsCollector.Coverage c = new KisNewsCollector.Coverage();
        KisNewsCollector.add(c, new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T01:00:00"),
                LocalDateTime.parse("2026-10-21T02:00:00")));
        KisNewsCollector.add(c, new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T02:00:01"),
                LocalDateTime.parse("2026-10-21T03:00:00")));
        KisNewsCollector.add(c, new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T03:00:02"),
                LocalDateTime.parse("2026-10-21T04:00:00")));
        assertThat(c.intervals).containsExactly(
                new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T01:00:00"), LocalDateTime.parse("2026-10-21T03:00:00")),
                new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T03:00:02"), LocalDateTime.parse("2026-10-21T04:00:00")));
    }

    @Test
    void 실행_시각은_서울_기준이다() {
        assertThat(LocalDateTime.ofInstant(Instant.now(seoul("2026-10-21T07:00:00")), CallBudget.SEOUL))
                .isEqualTo(LocalDateTime.parse("2026-10-21T07:00:00"));
    }

    @Test
    void 실행_시각보다_뒤로_찍힌_행은_쌓지_않는다() throws IOException {
        // PR #222 QA Q3(M5): 시계가 어긋나 지금보다 뒤 시각 행이 오면 그 구간은 아직 훑지 않은 것이라 쌓지 않는다
        List<KisNewsFixtures.Row> rows = new ArrayList<>(rows());
        rows.add(row("20261021", "073000", "FUTURE", "005930"));
        KisNewsCollector.Result r = collector(new FakeHttp(KisNewsFixtures.server(rows, 4)), seoul("2026-10-21T07:00:00"), 100)
                .collect(null);
        assertThat(r.coverage().getLast().to()).isEqualTo(LocalDateTime.parse("2026-10-21T07:00:00"));
        assertThat(KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 21)))
                .extracting(KisNewsClient.Headline::serial).doesNotContain("FUTURE");
    }

    @Test
    void 늦게_실린_기사는_마지막_구간_끝_10분_안이면_다음_실행이_받는다() throws IOException {
        // PR #222 QA Q3(M6): 앞 실행(07:00) 뒤에 06:55·06:58 기사가 실렸다. 첫 쪽이 06:58에서 끝나도 06:50까지 더 거슬러 받는다
        List<KisNewsFixtures.Row> rows = new ArrayList<>(rows());
        collector(new FakeHttp(KisNewsFixtures.server(rows, 4)), seoul("2026-10-21T07:00:00"), 100).collect(null);
        rows.add(row("20261021", "170000", "D1", "005930"));
        rows.add(row("20261021", "160000", "D2", "005930"));
        rows.add(row("20261021", "150000", "D3", "005930"));
        rows.add(row("20261021", "065800", "LATE2", "005930"));
        rows.add(row("20261021", "065500", "LATE", "005930"));
        collector(new FakeHttp(KisNewsFixtures.server(rows, 4)), seoul("2026-10-21T18:00:00"), 100).collect(null);
        assertThat(KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 21)))
                .extracting(KisNewsClient.Headline::serial).contains("LATE", "LATE2", "D1");
    }

    @Test
    void 자정_행이_한_쪽보다_많으면_확인_못_한_것을_세어_알린다() throws IOException {
        // PR #222 QA Q4: 00:00:00 행 10건, 한 쪽 4건. 커서 000000은 보낼 수 없어 나머지를 받을 길이 없다 → 조용히 넘기지 않고 센다
        List<KisNewsFixtures.Row> rows = new ArrayList<>();
        rows.add(row("20261021", "010000", "ONE", "005930"));
        for (int i = 0; i < 10; i++) {
            rows.add(row("20261021", "000000", "M" + i, "005930"));
        }
        rows.add(row("20261020", "230000", "Y", "005930"));
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows, 4));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T07:00:00"), 100).collect(null);
        KisNewsCollector.Walk w = r.walks().getFirst();
        assertThat(w.midnightCuts()).isEqualTo(1);
        assertThat(w.complete()).isTrue();
        assertThat(http.calls).hasSize(1); // 멈출 시각을 넘은 커서로 더 부르지 않는다(PR #225 QA Q3)
        assertThat(http.calls).noneMatch(u -> "000000".equals(KisNewsFixtures.param(u, "FID_INPUT_HOUR_1")));
    }

    @Test
    void 멈출_시각과_같은_초에서_쪽이_끝나면_그_초를_한_번_더_받는다() throws IOException {
        // PR #222 QA Q4: 쪽의 가장 옛 행이 멈출 시각과 같은 초면 그 초의 나머지가 다음 쪽에 있을 수 있어 끝으로 보지 않는다
        List<KisNewsFixtures.Row> rows = new ArrayList<>(rows());
        collector(new FakeHttp(KisNewsFixtures.server(rows, 4)), seoul("2026-10-21T07:00:00"), 100).collect(null);
        // 다음 실행의 멈출 시각 = 07:00 − 10분 = 06:50:00. 그 초에 행 3건(앞 실행 뒤에 실림), 첫 쪽은 그중 1건에서 끝난다
        for (int i = 0; i < 3; i++) {
            rows.add(row("20261021", "065000", "S" + i, "005930"));
        }
        rows.add(row("20261021", "080000", "N1", "005930"));
        rows.add(row("20261021", "075000", "N2", "005930"));
        rows.add(row("20261021", "074000", "N3", "005930"));
        KisNewsCollector.Result r = collector(new FakeHttp(KisNewsFixtures.server(rows, 4)), seoul("2026-10-21T08:00:00"), 100)
                .collect(null);
        assertThat(r.walks().getFirst().complete()).isTrue();
        assertThat(r.walks().getFirst().crowdedSeconds()).isZero();
        assertThat(KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 21)))
                .extracting(KisNewsClient.Headline::serial).contains("S0", "S1", "S2");
    }

    @Test
    void 새_행이_있어도_한_쪽이_모두_같은_초면_세어_알린다() throws IOException {
        // 한 초에 6건, 한 쪽 4건: 둘째 쪽도 모두 같은 초라 커서가 그 초를 넘어가면 나머지는 못 받는다(D-136 위험 4)
        List<KisNewsFixtures.Row> rows = new ArrayList<>();
        rows.add(row("20261021", "064000", "TOP", "005930"));
        for (int i = 0; i < 6; i++) {
            rows.add(row("20261021", "063000", "S" + i, "005930"));
        }
        rows.add(row("20261020", "230000", "Y", "005930"));
        KisNewsCollector.Result r = collector(new FakeHttp(KisNewsFixtures.server(rows, 4)), seoul("2026-10-21T07:00:00"), 100)
                .collect(null);
        assertThat(r.walks().getFirst().crowdedSeconds()).isEqualTo(1);
    }

    @Test
    void 스냅샷은_구간_양_끝_시각의_행을_담는다() throws IOException {
        // PR #222 QA Q3(M9): 구간은 양 끝 포함. 끝 시각 06:30·시작 시각 00:30 행이 들어간다
        collector(new FakeHttp(KisNewsFixtures.server(rows(), 4)), seoul("2026-10-21T07:00:00"), 100).collect(null);
        List<List<String>> out = new ArrayList<>();
        KisNewsSnapshotBuilder.build(cache, List.of(new KisNewsCollector.Interval(LocalDateTime.parse("2026-10-21T00:30:00"),
                LocalDateTime.parse("2026-10-21T06:30:00"))), out::add);
        assertThat(out).extracting(row -> row.get(1)).contains("C30", "C390").doesNotContain("A23", "B23");
    }

    @Test
    void 새_행_없는_쪽이_00시_00분_01초에서_끝나면_000001로_한_번_더_받아_00시_행을_본다() throws IOException {
        // PR #225 QA Q1·S3: 앞 실행(00:00:05) 뒤에 00:00:00 행 Z가 실렸다. 쪽 크기 2
        List<KisNewsFixtures.Row> rows = new ArrayList<>();
        for (int sec = 1; sec <= 4; sec++) {
            rows.add(row("20261021", String.format("00000%d", sec), "T" + sec, "005930"));
        }
        rows.add(row("20261020", "230000", "Y1", "005930"));
        rows.add(row("20261020", "220000", "Y2", "005930"));
        collector(new FakeHttp(KisNewsFixtures.server(rows, 2)), seoul("2026-10-21T00:00:05"), 100).collect(null);
        rows.add(row("20261021", "000000", "Z", "005930"));
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows, 2));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T00:05:00"), 100).collect(null);
        assertThat(KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 21)))
                .extracting(KisNewsClient.Headline::serial).contains("Z");
        assertThat(http.calls).anyMatch(u -> "000001".equals(KisNewsFixtures.param(u, "FID_INPUT_HOUR_1")));
        assertThat(r.walks().getFirst().complete()).isTrue();
    }

    @Test
    void 쪽이_커서보다_앞선_날의_00시_행에서_끝나도_000000을_보내지_않고_센다() throws IOException {
        // PR #228 architect: 커서는 10-21인데 쪽이 10-20 00:00:00 행에서 끝난다. 10-20의 000000을 보내면 안 된다
        Path root = cache.resolve("kis-news");
        Files.createDirectories(root);
        Files.writeString(root.resolve(KisNewsCollector.COVERAGE_FILE), """
                {"intervals":[{"from":"2026-10-19T00:00:00","to":"2026-10-19T12:00:00"}],"floor":null}""");
        List<KisNewsFixtures.Row> rows = List.of(row("20261020", "000000", "A1", "005930"), row("20261020", "000000", "A2", "005930"),
                row("20261019", "230000", "Y", "005930"), row("20261019", "110000", "Q", "005930"));
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows, 2));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T07:00:00"), 100).collect(null);
        assertThat(http.calls).noneMatch(u -> "000000".equals(KisNewsFixtures.param(u, "FID_INPUT_HOUR_1")));
        assertThat(KisNewsFixtures.param(http.calls.get(1), "FID_INPUT_DATE_1")).isEqualTo("20261019");
        assertThat(KisNewsFixtures.param(http.calls.get(1), "FID_INPUT_HOUR_1")).isEqualTo("235959");
        assertThat(r.walks().getFirst().midnightCuts()).isEqualTo(1);
        assertThat(KisNewsCollector.readDay(cache.resolve("kis-news"), LocalDate.of(2026, 10, 19)))
                .extracting(KisNewsClient.Headline::serial).contains("Y");
    }

    @Test
    void 자정_1초_행이_한_쪽보다_많으면_000001을_되풀이하지_않고_자정을_센다() throws IOException {
        // PR #228 risk-ops R1: 000001로 다시 받아도 그 초 행으로 가득 차면 00:00:00 행을 볼 수 없다. 같은 커서를 되풀이해
        // 호출 몫을 쓰지 않고, 전날로 넘어가며 셈해서 알린다
        List<KisNewsFixtures.Row> rows = new ArrayList<>();
        rows.add(row("20261021", "000005", "F", "005930"));
        for (int i = 0; i < 4; i++) {
            rows.add(row("20261021", "000001", "S" + i, "005930"));
        }
        rows.add(row("20261021", "000000", "Z", "005930"));
        rows.add(row("20261020", "230000", "Y", "005930"));
        FakeHttp http = new FakeHttp(KisNewsFixtures.server(rows, 2));
        KisNewsCollector.Result r = collector(http, seoul("2026-10-21T07:00:00"), 100).collect(null);
        assertThat(http.calls.stream().filter(u -> "000001".equals(KisNewsFixtures.param(u, "FID_INPUT_HOUR_1")))).hasSizeLessThanOrEqualTo(2);
        assertThat(r.walks().getFirst().midnightCuts()).isGreaterThanOrEqualTo(1);
        assertThat(r.walks().getFirst().complete()).isTrue();
    }
}
