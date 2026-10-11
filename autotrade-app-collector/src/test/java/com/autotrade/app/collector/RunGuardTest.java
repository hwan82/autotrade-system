package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunGuardTest {

    @TempDir
    Path dir;

    private static RunGuard at(String instant, boolean allow) {
        return new RunGuard(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
                List.of(ExchangeCalendar.bundled(Market.KRX), ExchangeCalendar.bundled(Market.US)), allow, d -> 1L << 40, 20);
    }

    @Test
    void 국내_정규장_앞뒤_보호_구간은_막는다() {
        // 서울 2026-10-08(목): 08:00 ~ 16:00 보호
        assertThatCode(() -> at("2026-10-07T22:59:00Z", false).checkSession()).doesNotThrowAnyException(); // 07:59
        assertThatThrownBy(() -> at("2026-10-07T23:00:00Z", false).checkSession()).isInstanceOf(RunGuard.Refused.class); // 08:00
        assertThatThrownBy(() -> at("2026-10-08T06:59:00Z", false).checkSession()).isInstanceOf(RunGuard.Refused.class); // 15:59
        assertThatCode(() -> at("2026-10-08T07:00:00Z", false).checkSession()).doesNotThrowAnyException(); // 16:00
    }

    @Test
    void 미국_정규장은_서머타임을_따라_막는다() {
        // 뉴욕 2026-10-08(목, EDT): 08:30 ~ 16:30 보호 = 12:30Z ~ 20:30Z
        assertThatCode(() -> at("2026-10-08T12:29:00Z", false).checkSession()).doesNotThrowAnyException();
        assertThatThrownBy(() -> at("2026-10-08T12:30:00Z", false).checkSession()).isInstanceOf(RunGuard.Refused.class);
        // 뉴욕 2026-12-08(화, EST): 08:30 = 13:30Z
        assertThatCode(() -> at("2026-12-08T13:29:00Z", false).checkSession()).doesNotThrowAnyException();
        assertThatThrownBy(() -> at("2026-12-08T13:30:00Z", false).checkSession()).isInstanceOf(RunGuard.Refused.class);
    }

    @Test
    void 휴장일과_주말은_막지_않는다() {
        // 2026-10-09 한글날(국내 휴장), 서울 10:00 = 01:00Z
        assertThatCode(() -> at("2026-10-09T01:00:00Z", false).checkSession()).doesNotThrowAnyException();
        // 2026-10-10 토요일 서울 10:00
        assertThatCode(() -> at("2026-10-10T01:00:00Z", false).checkSession()).doesNotThrowAnyException();
    }

    @Test
    void 캘린더_수록_범위_밖이면_판단할_수_없어_막는다() {
        assertThatThrownBy(() -> at("2027-01-05T10:00:00Z", false).checkSession()).isInstanceOf(RunGuard.Refused.class)
                .hasMessageContaining("수록 범위 밖");
    }

    @Test
    void 명시_옵션이면_장_시간에도_통과한다() {
        assertThatCode(() -> at("2026-10-08T01:00:00Z", true).checkSession()).doesNotThrowAnyException();
    }

    @Test
    void 디스크_여유가_기준_미만이면_막고_없는_폴더는_있는_상위_폴더로_본다() {
        RunGuard low = new RunGuard(Clock.systemUTC(), List.of(), false, d -> 5L * 1024 * 1024 * 1024, 20);
        assertThatThrownBy(() -> low.checkDisk(List.of(dir.resolve("a").resolve("b")))).isInstanceOf(RunGuard.Refused.class)
                .hasMessageContaining("5GB");
        RunGuard ok = new RunGuard(Clock.systemUTC(), List.of(), false, d -> 21L * 1024 * 1024 * 1024, 20);
        assertThatCode(() -> ok.checkDisk(List.of(dir.resolve("a")))).doesNotThrowAnyException();
    }

    @Test
    void 정지_요청_파일은_해제_옵션과_상관없이_막고_지우지_않는다() throws IOException {
        Path stop = dir.resolve("stop-request");
        RunGuard g = new RunGuard(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC),
                List.of(ExchangeCalendar.bundled(Market.KRX), ExchangeCalendar.bundled(Market.US)), true, d -> 1L << 40, 20, stop);
        assertThatCode(g::checkSession).doesNotThrowAnyException();
        java.nio.file.Files.writeString(stop, "x");
        assertThatThrownBy(g::checkSession).isInstanceOf(RunGuard.Refused.class).hasMessageContaining("정지를 요청");
        assertThatThrownBy(g::checkSession).isInstanceOf(RunGuard.Refused.class);
        org.assertj.core.api.Assertions.assertThat(stop).exists();
    }
}
