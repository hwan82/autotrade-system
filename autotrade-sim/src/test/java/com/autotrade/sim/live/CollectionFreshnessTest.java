package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.selection.SnapshotHeap;
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
        Path dir = snapshotsRoot.resolve(SnapshotHeap.KRX_DAILY).resolve(id);
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
        Path only = tmp.resolve(SnapshotHeap.KRX_DAILY).resolve("krx-daily-2026-10-15-11111111");
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
        Path broken = tmp.resolve(SnapshotHeap.KRX_DAILY).resolve("krx-daily-2026-10-16-77777777");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("manifest.json"), "{not json", StandardCharsets.UTF_8);
        // 쓰는 중인 임시 폴더(manifest 없음)와 다른 이름은 보지 않는다
        Files.createDirectories(tmp.resolve(SnapshotHeap.KRX_DAILY).resolve(".tmp-krx-daily-1"));
        Files.createDirectories(tmp.resolve(SnapshotHeap.KRX_DAILY).resolve("krx-daily-2026-10-16-88888888"));

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
    void recheckingAnEarlierWeekSeesNewerSnapshotsAsNormal() throws Exception {
        // PR #145 strategy-researcher R3·architect N2: -Pto로 지난 주를 다시 볼 때 그 뒤 수집된 스냅샷이면 뒤처짐 없음
        writeSnapshot(tmp, "krx-daily-2026-10-16-13131313", LocalDate.of(2026, 10, 16));
        CollectionFreshness.Status s = check(LocalDate.of(2026, 10, 8));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.OK);
        assertThat(s.behind()).isEmpty();
        assertThat(s.newerThanEnd()).isTrue();
        assertThat(CollectionFreshness.render(s)).contains("스냅샷이 점검 마지막 날보다 뒤(2026-10-16)까지 있다")
                .contains("그 주에 있었던 멈춤은 이 칸에 보이지 않는다");
        // PR #147 architect 3: 머리·콘솔 한 줄에도 나온다
        assertThat(s.summary())
                .isEqualTo("정상(일봉 스냅샷 마지막 거래일 2026-10-16, 점검 마지막 날보다 뒤라 그 주의 멈춤은 보이지 않음)");
        // 같은 날까지면 그 표시가 없다
        assertThat(check(LocalDate.of(2026, 10, 16)).newerThanEnd()).isFalse();
        assertThat(check(LocalDate.of(2026, 10, 16)).summary()).isEqualTo("정상(일봉 스냅샷 마지막 거래일 2026-10-16)");
        assertThat(CollectionFreshness.render(check(LocalDate.of(2026, 10, 16)))).doesNotContain("점검 마지막 날보다 뒤");
    }

    @Test
    void oldManifestWithoutCoverageIsListedApartFromBrokenOnes() throws Exception {
        // PR #145 architect N1: ADR-008 이전 형식(coverage 없음)은 깨진 것이 아니라 셀 수 없는 것
        Path old = tmp.resolve(SnapshotHeap.KRX_DAILY).resolve("krx-daily-2026-10-01-aaaaaaaa");
        Files.createDirectories(old);
        Files.writeString(old.resolve("manifest.json"), """
                {"snapshotId":"krx-daily-2026-10-01-aaaaaaaa","schemaVersion":1,"createdAt":"2026-10-01T09:00:00Z",
                 "scriptVersion":"0.1.0","sources":[],"files":[]}
                """, StandardCharsets.UTF_8);
        CollectionFreshness.Status only = check(LocalDate.of(2026, 10, 16));
        assertThat(only.kind()).isEqualTo(CollectionFreshness.Kind.NO_SNAPSHOT);
        assertThat(only.noCoverage()).containsExactly("krx-daily-2026-10-01-aaaaaaaa");
        assertThat(only.unreadable()).isEmpty();
        assertThat(CollectionFreshness.render(only))
                .contains("마지막 거래일 칸이 없는 옛 형식이라 세지 않은 스냅샷 폴더(깨진 것 아님): krx-daily-2026-10-01-aaaaaaaa")
                .doesNotContain("읽지 못해 건너뛴");
        // PR #147 strategy-researcher P2: 머리 한 줄도 "없음"이 아니라 셀 수 있는 것이 없다고 적는다
        assertThat(only.summary()).isEqualTo("**확인 필요**: 셀 수 있는 일봉 스냅샷이 없음(마지막 거래일 칸 없는 옛 형식 1개, 아래 4장)");
        assertThat(only.warn()).isTrue();

        writeSnapshot(tmp, "krx-daily-2026-10-15-bbbbbbbb", LocalDate.of(2026, 10, 15));
        CollectionFreshness.Status s = check(LocalDate.of(2026, 10, 16));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.OK);
        assertThat(s.snapshotId()).isEqualTo("krx-daily-2026-10-15-bbbbbbbb");
        assertThat(s.noCoverage()).containsExactly("krx-daily-2026-10-01-aaaaaaaa");
    }

    @Test
    void coverageWithoutLastTradingDateIsOldFormatToo() throws Exception {
        // PR #147 architect 2: coverage는 있고 lastTradingDate만 없으면(다른 형식이 섞인 경우 등) 같은 옛 형식 취급
        Path half = tmp.resolve(SnapshotHeap.KRX_DAILY).resolve("krx-daily-2026-10-02-dddddddd");
        Files.createDirectories(half);
        Files.writeString(half.resolve("manifest.json"), """
                {"snapshotId":"krx-daily-2026-10-02-dddddddd","schemaVersion":1,"createdAt":"2026-10-02T09:00:00Z",
                 "scriptVersion":"0.1.0","sources":[],"files":[],
                 "coverage":{"firstDate":"2010-01-04","sharesAsOf":"2026-10-02","lastCollectedAt":"2026-10-02T09:00:00Z"}}
                """, StandardCharsets.UTF_8);
        CollectionFreshness.Status s = check(LocalDate.of(2026, 10, 16));
        assertThat(s.kind()).isEqualTo(CollectionFreshness.Kind.NO_SNAPSHOT);
        assertThat(s.noCoverage()).containsExactly("krx-daily-2026-10-02-dddddddd");
        assertThat(s.unreadable()).isEmpty();
        assertThat(s.summary()).contains("옛 형식 1개");
    }

    @Test
    void noSnapshotSummaryWithoutOldFormatStaysShort() {
        assertThat(check(LocalDate.of(2026, 10, 16)).summary()).isEqualTo("**확인 필요**: 일봉 스냅샷이 없음(아래 4장)");
    }

    @Test
    void sectionSaysTheLiveAccountsDoNotReadDailySnapshots() throws Exception {
        // PR #145 strategy-researcher R2: 3장 계좌 성과를 의심하지 않도록
        writeSnapshot(tmp, "krx-daily-2026-10-14-cccccccc", LocalDate.of(2026, 10, 14));
        assertThat(CollectionFreshness.render(check(LocalDate.of(2026, 10, 16))))
                .contains("6단계 실시간 모의 계좌는 일봉 스냅샷을 입력으로 쓰지 않으므로")
                .contains("3장 계좌 성과는 바뀌지 않고");
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
