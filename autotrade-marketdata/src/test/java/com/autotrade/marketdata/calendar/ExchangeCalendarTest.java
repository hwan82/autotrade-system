package com.autotrade.marketdata.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ExchangeCalendarTest {

    private final ExchangeCalendar krx = ExchangeCalendar.bundled(Market.KRX);
    private final ExchangeCalendar us = ExchangeCalendar.bundled(Market.US);

    @Test
    void KRX_2026_휴장일은_17일이다() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        long weekdays = from.datesUntil(to.plusDays(1))
                .filter(d -> d.getDayOfWeek().getValue() <= 5).count();
        assertThat(weekdays - krx.tradingDays(from, to).size()).isEqualTo(17);
        assertThat(krx.isTradingDay(LocalDate.of(2026, 6, 3))).isFalse();  // 지방선거
        assertThat(krx.isTradingDay(LocalDate.of(2026, 7, 17))).isFalse(); // 제헌절
        assertThat(krx.isTradingDay(LocalDate.of(2026, 12, 30))).isTrue();
    }

    @Test
    void KRX_2027_휴장일은_월력요항_공휴일과_연말_휴장_16일이다() {
        LocalDate from = LocalDate.of(2027, 1, 1);
        LocalDate to = LocalDate.of(2027, 12, 31);
        long weekdays = from.datesUntil(to.plusDays(1))
                .filter(d -> d.getDayOfWeek().getValue() <= 5).count();
        assertThat(weekdays - krx.tradingDays(from, to).size()).isEqualTo(16);
        assertThat(krx.isTradingDay(LocalDate.of(2027, 2, 9))).isFalse();   // 설날 대체공휴일
        assertThat(krx.isTradingDay(LocalDate.of(2027, 5, 3))).isFalse();   // 노동절 대체공휴일
        assertThat(krx.isTradingDay(LocalDate.of(2027, 7, 19))).isFalse();  // 제헌절 대체공휴일
        assertThat(krx.isTradingDay(LocalDate.of(2027, 12, 31))).isFalse(); // 연말 휴장
        assertThat(krx.nextTradingDay(LocalDate.of(2027, 2, 5))).isEqualTo(LocalDate.of(2027, 2, 10));
        assertThat(krx.nextTradingDay(LocalDate.of(2026, 12, 30))).isEqualTo(LocalDate.of(2027, 1, 4));
        assertThat(krx.session(LocalDate.of(2027, 1, 4)).orElseThrow().open()).isEqualTo(LocalTime.of(10, 0));
        assertThat(krx.session(LocalDate.of(2027, 11, 18)).orElseThrow().close()).isEqualTo(LocalTime.of(16, 30));
        // 거래소 공지 전이라 2027년 날짜는 모두 확인 대상으로 남긴다
        assertThat(krx.unverifiedDates()).contains(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 4), LocalDate.of(2027, 12, 31));
    }

    @Test
    void 연말_자정에도_장_시간_보호가_판단할_수_있다() {
        // 2026-12-31 18:00 KST(뉴욕 04:00): 다음 날 2027-01-01까지 수록돼야 판단된다
        assertThat(SessionProtection.check(Instant.parse("2026-12-31T09:00:00Z"), java.util.List.of(krx, us)))
                .isInstanceOf(SessionProtection.Clear.class);
        // 확인 안 된 2027-01-04 연초 개장일(10:00 개장 [가정])은 정규장과 합쳐 08:00 KST(전날 23:00Z)부터 보호
        assertThat(SessionProtection.check(Instant.parse("2027-01-03T22:59:00Z"), java.util.List.of(krx)))
                .isInstanceOf(SessionProtection.Clear.class);
        assertThat(SessionProtection.check(Instant.parse("2027-01-03T23:00:00Z"), java.util.List.of(krx)))
                .isInstanceOf(SessionProtection.Inside.class);
        // 2027-12-31 00:00 KST부터는 다음 날(2028-01-01)이 수록 범위 밖이라 멈춘다: 그 전에 2028년을 넣는다
        assertThat(SessionProtection.check(Instant.parse("2027-12-30T15:00:00Z"), java.util.List.of(krx)))
                .isInstanceOf(SessionProtection.Unknown.class);
    }

    @Test
    void 장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다() {
        java.util.List<ExchangeCalendar> cals = java.util.List.of(krx);
        // 확인 안 된 휴장일(노동절·제헌절 대체공휴일): 거래일 계산은 휴장, 보호는 정규장이 있는 날처럼
        assertThat(krx.isTradingDay(LocalDate.of(2027, 5, 3))).isFalse();
        assertThat(SessionProtection.check(Instant.parse("2027-05-03T00:30:00Z"), cals)).isInstanceOf(SessionProtection.Inside.class); // 09:30
        assertThat(SessionProtection.check(Instant.parse("2027-07-19T00:30:00Z"), cals)).isInstanceOf(SessionProtection.Inside.class);
        // 확인 안 된 수능일(10:00~16:30 [가정]): 08:00~17:00 보호(정규장 09:00 개장과 16:30 폐장을 합침)
        assertThat(SessionProtection.check(Instant.parse("2027-11-17T23:00:00Z"), cals)).isInstanceOf(SessionProtection.Inside.class); // 08:00
        assertThat(SessionProtection.check(Instant.parse("2027-11-18T07:59:00Z"), cals)).isInstanceOf(SessionProtection.Inside.class); // 16:59
        assertThat(SessionProtection.check(Instant.parse("2027-11-18T08:00:00Z"), cals)).isInstanceOf(SessionProtection.Clear.class);  // 17:00
        // 확인된 휴장일(2026-06-03 지방선거)은 막지 않는다
        assertThat(SessionProtection.check(Instant.parse("2026-06-03T01:00:00Z"), cals)).isInstanceOf(SessionProtection.Clear.class);
        // 확인된 특별 운영일(2026-01-02 10:00 개장)은 그 시각 그대로
        assertThat(krx.protectionSession(LocalDate.of(2026, 1, 2)).orElseThrow().open()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void 확인_안_된_조기_폐장일도_보호는_정규장_폐장까지_간다() throws Exception {
        // PR #53 QA Q1: 폐장 쪽 합치기를 지키는 테스트. 2026-10-08(목) 12:00 조기 폐장을 verified:false로 둔다
        String json = """
                {"market":"KRX","zone":"Asia/Seoul","coverageFrom":"2026-01-01","coverageTo":"2026-12-31",
                 "regularOpen":"09:00","regularClose":"15:30","checkedOn":"2026-10-12","sources":{},
                 "holidays":[],
                 "specialSessions":[{"date":"2026-10-08","open":"09:00","close":"12:00","name":"조기 폐장","source":"s","verified":false}]}
                """;
        ExchangeCalendar cal = ExchangeCalendar.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(cal.session(LocalDate.of(2026, 10, 8)).orElseThrow().close()).isEqualTo(LocalTime.of(12, 0));
        assertThat(cal.protectionSession(LocalDate.of(2026, 10, 8)).orElseThrow().close()).isEqualTo(LocalTime.of(15, 30));
        // 서울 15:59(06:59Z)는 정규장 폐장 30분 뒤(16:00) 전이라 보호 구간 안
        assertThat(SessionProtection.check(Instant.parse("2026-10-08T06:59:00Z"), java.util.List.of(cal)))
                .isInstanceOf(SessionProtection.Inside.class);
        assertThat(SessionProtection.check(Instant.parse("2026-10-08T07:00:00Z"), java.util.List.of(cal)))
                .isInstanceOf(SessionProtection.Clear.class);
    }

    @Test
    void KRX_연초_개장일은_10시에_연다() {
        TradingSession s = krx.session(LocalDate.of(2026, 1, 2)).orElseThrow();
        assertThat(s.open()).isEqualTo(LocalTime.of(10, 0));
        assertThat(krx.isRegularSession(Instant.parse("2026-01-02T00:30:00Z"))).isFalse(); // 09:30 KST
        assertThat(krx.isRegularSession(Instant.parse("2026-01-02T01:00:00Z"))).isTrue();  // 10:00 KST
    }

    @Test
    void KRX_정규장은_9시부터_15시30분_전까지다() {
        assertThat(krx.isRegularSession(Instant.parse("2026-10-08T00:00:00Z"))).isTrue();  // 09:00
        assertThat(krx.isRegularSession(Instant.parse("2026-10-08T06:29:59Z"))).isTrue();  // 15:29:59
        assertThat(krx.isRegularSession(Instant.parse("2026-10-08T06:30:00Z"))).isFalse(); // 15:30
        assertThat(krx.isRegularSession(Instant.parse("2026-10-09T01:00:00Z"))).isFalse(); // 한글날
    }

    @Test
    void 추석_연휴를_건너_다음_거래일을_찾는다() {
        assertThat(krx.nextTradingDay(LocalDate.of(2026, 9, 23))).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(krx.previousTradingDay(LocalDate.of(2026, 10, 6))).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    void 미국_정규장은_서머타임을_시간대_데이터로_따른다() {
        // 2026-03-08 서머타임 시작: 3/6(금)은 14:30Z 개장, 3/9(월)은 13:30Z 개장
        assertThat(us.session(LocalDate.of(2026, 3, 6)).orElseThrow().openInstant()).isEqualTo(Instant.parse("2026-03-06T14:30:00Z"));
        assertThat(us.session(LocalDate.of(2026, 3, 9)).orElseThrow().openInstant()).isEqualTo(Instant.parse("2026-03-09T13:30:00Z"));
    }

    @Test
    void 미국_조기_폐장일은_13시에_닫는다() {
        TradingSession s = us.session(LocalDate.of(2026, 11, 27)).orElseThrow();
        assertThat(s.close()).isEqualTo(LocalTime.of(13, 0));
        assertThat(us.isRegularSession(Instant.parse("2026-11-27T18:30:00Z"))).isFalse(); // 13:30 ET
        assertThat(us.isTradingDay(LocalDate.of(2026, 11, 26))).isFalse();
        assertThat(us.isTradingDay(LocalDate.of(2027, 12, 24))).isFalse(); // 성탄절 대체
    }

    @Test
    void 미국_장은_한국_날짜로_이틀에_걸쳐도_현지_날짜로_판단한다() {
        // 2026-10-08 15:59 ET = 2026-10-09 04:59 KST
        assertThat(us.isRegularSession(Instant.parse("2026-10-08T19:59:00Z"))).isTrue();
    }

    @Test
    void 수록_범위_밖은_추측하지_않고_실패한다() {
        assertThatThrownBy(() -> krx.session(LocalDate.of(2028, 1, 3))).isInstanceOf(CalendarCoverageException.class);
        assertThatThrownBy(() -> us.isTradingDay(LocalDate.of(2025, 12, 31))).isInstanceOf(CalendarCoverageException.class);
    }

    @Test
    void 확인이_남은_날짜를_알려준다() {
        assertThat(krx.unverifiedDates()).contains(LocalDate.of(2026, 11, 19)).doesNotContain(LocalDate.of(2026, 6, 3));
        assertThat(us.unverifiedDates()).isEmpty();
    }

    @Test
    void 범위_밖_휴장일이나_중복은_데이터_오류다() {
        String bad = """
                {"market":"KRX","zone":"Asia/Seoul","coverageFrom":"2026-01-01","coverageTo":"2026-12-31",
                 "regularOpen":"09:00","regularClose":"15:30","checkedOn":"2026-10-08","sources":{},
                 "holidays":[{"date":"2027-01-01","name":"x","source":"s","verified":true}],"specialSessions":[]}
                """;
        assertThatThrownBy(() -> ExchangeCalendar.parse(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("수록 범위 밖");
    }
}
