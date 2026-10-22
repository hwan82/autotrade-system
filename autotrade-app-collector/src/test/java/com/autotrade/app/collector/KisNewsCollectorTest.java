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
        // 00:00:00 커서는 전날 23:59:59로(1번), 빈 쪽이면 전날로 넘어가기를 6번, 7번째 빈 쪽에서 끝으로 본다
        assertThat(http.calls.stream().filter(u -> "235959".equals(KisNewsFixtures.param(u, "FID_INPUT_HOUR_1")))).hasSize(7);

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
}
