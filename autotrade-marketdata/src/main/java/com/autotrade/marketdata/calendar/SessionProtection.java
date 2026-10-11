package com.autotrade.marketdata.calendar;

import com.autotrade.domain.market.Market;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 장 시간 보호 구간(broker-and-fault-design 5.1·5.2, ADR-008 2.7, D-063): 실전 엔진과 같은 PC에서 도는 단발 명령(수집기, 실험
 * 실행 명령)이 시작하지 않고, 실행 중이면 스스로 멈추는 구간. 거래일 정규장 {@value #BEFORE_OPEN_MINUTES}분 전부터 폐장
 * {@value #AFTER_CLOSE_MINUTES}분 뒤까지다. 명령은 Guard 상태를 읽지 못하므로 실전 계좌가 켜졌는지와 상관없이 시간으로 막는다.
 *
 * <p>순수 계산이다. 현재 시각은 부르는 쪽(프로세스 진입점이 만든 {@code Clock})이 넣는다.
 */
public final class SessionProtection {

    public static final int BEFORE_OPEN_MINUTES = 60;
    public static final int AFTER_CLOSE_MINUTES = 30;

    private SessionProtection() {
    }

    /** 판정. {@link Inside}와 {@link Unknown}이면 멈춘다. */
    public sealed interface Verdict permits Clear, Inside, Unknown {
    }

    /** 어느 시장의 보호 구간도 아님. */
    public record Clear() implements Verdict {
    }

    /** 이 시장의 보호 구간 안. */
    public record Inside(Market market, ZoneId zone, Instant from, Instant to) implements Verdict {

        /** 거래소 현지 시각 구간, 예: {@code 08:00~16:00 Asia/Seoul}. */
        public String localRange() {
            return from.atZone(zone).toLocalTime() + "~" + to.atZone(zone).toLocalTime() + " " + zone;
        }
    }

    /** 캘린더 수록 범위 밖이라 판단할 수 없음(멈추는 쪽). */
    public record Unknown(Market market, LocalDate date) implements Verdict {
    }

    /**
     * 캘린더 순서대로 보고 처음 걸린 판정을 돌려준다. 현지 날짜 전날·당일·다음날 세션을 모두 봐서 자정을 넘는 구간과 시간대
     * 차이를 놓치지 않는다.
     */
    public static Verdict check(Instant now, List<ExchangeCalendar> calendars) {
        Objects.requireNonNull(now, "now");
        for (ExchangeCalendar cal : calendars) {
            LocalDate local = now.atZone(cal.zone()).toLocalDate();
            for (LocalDate d : List.of(local.minusDays(1), local, local.plusDays(1))) {
                Optional<TradingSession> s;
                try {
                    s = cal.session(d);
                } catch (CalendarCoverageException e) {
                    return new Unknown(cal.market(), d);
                }
                if (s.isPresent()) {
                    Instant from = s.get().openInstant().minus(Duration.ofMinutes(BEFORE_OPEN_MINUTES));
                    Instant to = s.get().closeInstant().plus(Duration.ofMinutes(AFTER_CLOSE_MINUTES));
                    if (!now.isBefore(from) && now.isBefore(to)) {
                        return new Inside(cal.market(), cal.zone(), from, to);
                    }
                }
            }
        }
        return new Clear();
    }
}
