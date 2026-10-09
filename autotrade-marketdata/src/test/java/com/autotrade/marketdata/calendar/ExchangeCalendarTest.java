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
        assertThatThrownBy(() -> krx.session(LocalDate.of(2027, 1, 4))).isInstanceOf(CalendarCoverageException.class);
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
