package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 주간 점검의 수집 멈춤 여부(D-105, PR #141 strategy-researcher R4). 날짜는 번들 KRX 캘린더(2026-10-09 한글날 휴장)를 쓴다. */
class CollectionFreshnessTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);

    @TempDir
    Path tmp;

    /** 수집기가 쓰는 모양의 manifest만 둔다(파일 본문은 이 점검이 읽지 않음). */
    static void writeSnapshot(Path snapshotsRoot, String id, LocalDate last) throws Exception {
        Path dir = snapshotsRoot.resolve(CollectionFreshness.KRX_DAILY).resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"%sT09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[],"files":[],
                 "coverage":{"firstDate":"2010-01-04","lastTradingDate":"%s","sharesAsOf":"%s","lastCollectedAt":"%sT09:00:00Z"}}
                """.formatted(id, last, last, last, last), StandardCharsets.UTF_8);
    }

    private CollectionFreshness.Status check(LocalDate end) {
        return CollectionFreshness.check(tmp, Market.KRX, end, KRX);
    }

    @Test
    void noSnapshotFolderIsReportedNotThrown() {
        CollectionFreshness.Status s = check(LocalDate.of(2026, 10, 16));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.NO_SNAPSHOT);
        assertThat(s.warn()).isTrue();
        assertThat(CollectionFreshness.render(s)).contains("수집기를 아직 돌리지 않았거나");
    }

    @Test
    void oneTradingDayBehindIsStillNormalTwoIsSuspected() throws Exception {
        writeSnapshot(tmp, "krx-daily-2026-10-15-11111111", LocalDate.of(2026, 10, 15));
        CollectionFreshness.Status ok = check(LocalDate.of(2026, 10, 16));
        assertThat(ok.kind()).isEqualTo(CollectionFreshness.Kind.OK);
        assertThat(ok.warn()).isFalse();
        assertThat(ok.behind()).containsExactly(LocalDate.of(2026, 10, 16));
        // 점검 마지막 날까지 수집됐으면 뒤처짐 없음
        assertThat(check(LocalDate.of(2026, 10, 15)).behind()).isEmpty();

        writeSnapshot(tmp, "krx-daily-2026-10-14-22222222", LocalDate.of(2026, 10, 14));
        Path only = tmp.resolve(CollectionFreshness.KRX_DAILY).resolve("krx-daily-2026-10-15-11111111");
        Files.delete(only.resolve("manifest.json"));
        Files.delete(only);
        CollectionFreshness.Status behind = check(LocalDate.of(2026, 10, 16));
        assertThat(behind.kind()).isEqualTo(CollectionFreshness.Kind.BEHIND);
        assertThat(behind.warn()).isTrue();
        assertThat(behind.behind()).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(CollectionFreshness.render(behind)).contains("캘린더상 거래일에 빈 목록").contains("krx.json");
    }

    @Test
    void weekendsAreNotTradingDays() throws Exception {
        // 금요일 스냅샷, 월요일 점검: 토·일은 빼고 월요일 하루만 뒤처짐
        writeSnapshot(tmp, "krx-daily-2026-10-16-12121212", LocalDate.of(2026, 10, 16));
        CollectionFreshness.Status s = check(LocalDate.of(2026, 10, 19));
        assertThat(s.behind()).containsExactly(LocalDate.of(2026, 10, 19));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.OK);
    }

    @Test
    void calendarHolidaysAreNotCountedAsMissedDays() throws Exception {
        // 수집기가 10-08(목)에서 멈춤: 10-09(금) 한글날은 휴장이라 빼고, 10-12~10-16 다섯 거래일
        writeSnapshot(tmp, "krx-daily-2026-10-08-33333333", LocalDate.of(2026, 10, 8));
        CollectionFreshness.Status s = check(LocalDate.of(2026, 10, 16));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.BEHIND);
        assertThat(s.behind()).hasSize(5).doesNotContain(LocalDate.of(2026, 10, 9))
                .startsWith(LocalDate.of(2026, 10, 12)).endsWith(LocalDate.of(2026, 10, 16));
        assertThat(s.weekdayFallback()).isFalse();
    }

    @Test
    void picksTheLatestReadableSnapshotAndListsBrokenOnes() throws Exception {
        writeSnapshot(tmp, "krx-daily-2026-10-12-44444444", LocalDate.of(2026, 10, 12));
        writeSnapshot(tmp, "krx-daily-2026-10-15-55555555", LocalDate.of(2026, 10, 15));
        writeSnapshot(tmp, "krx-daily-2026-10-14-66666666", LocalDate.of(2026, 10, 14));
        Path broken = tmp.resolve(CollectionFreshness.KRX_DAILY).resolve("krx-daily-2026-10-16-77777777");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("manifest.json"), "{not json", StandardCharsets.UTF_8);
        // 쓰는 중인 임시 폴더(manifest 없음)와 다른 이름은 보지 않는다
        Files.createDirectories(tmp.resolve(CollectionFreshness.KRX_DAILY).resolve(".tmp-krx-daily-1"));
        Files.createDirectories(tmp.resolve(CollectionFreshness.KRX_DAILY).resolve("krx-daily-2026-10-16-88888888"));

        CollectionFreshness.Status s = check(LocalDate.of(2026, 10, 16));
        assertThat(s.snapshotId()).isEqualTo("krx-daily-2026-10-15-55555555");
        assertThat(s.lastTradingDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.OK);
        assertThat(s.unreadable()).containsExactly("krx-daily-2026-10-16-77777777");
        assertThat(CollectionFreshness.render(s)).contains("읽지 못해 건너뛴 스냅샷 폴더: krx-daily-2026-10-16-77777777");
        // 같은 폴더면 같은 보고서
        assertThat(CollectionFreshness.render(check(LocalDate.of(2026, 10, 16)))).isEqualTo(CollectionFreshness.render(s));
    }

    @Test
    void daysBeyondTheCalendarAreCountedAsWeekdaysAndFlagged() throws Exception {
        writeSnapshot(tmp, "krx-daily-2027-12-30-99999999", LocalDate.of(2027, 12, 30));
        CollectionFreshness.Status s = check(LocalDate.of(2028, 1, 4));
        assertThat(s.weekdayFallback()).isTrue();
        assertThat(s.behind()).contains(LocalDate.of(2028, 1, 3), LocalDate.of(2028, 1, 4))
                .doesNotContain(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 1, 2));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.BEHIND);
        assertThat(CollectionFreshness.render(s)).contains("캘린더 수록 범위 밖이라 평일로 셌다");
    }

    @Test
    void otherMarketsHaveNoDailyCollection() {
        CollectionFreshness.Status s = CollectionFreshness.check(tmp, Market.US, LocalDate.of(2026, 10, 16), KRX);
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.NOT_APPLICABLE);
        assertThat(s.warn()).isFalse();
    }
}
