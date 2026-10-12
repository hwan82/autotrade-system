package com.autotrade.sim.daily;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.DailyBarSource;
import com.autotrade.selection.calc.SnapshotSelectionData;
import com.autotrade.selection.calc.TradingDays;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 일봉 스냅샷에서 이끌어 낸 거래일 목록(D-061). 번들 거래소 캘린더는 최근 연도만 담고 있어(krx.json 2026~2027, D-064) 여러 해 일봉 실험의
 * 선정 계산({@code TradingDays})에 그대로 쓸 수 없다. 수집기는 휴장일에 빈 응답을 받아 일봉을 만들지 않으므로(ADR-008 2.3)
 * "그 시장 종목 하나라도 일봉이 있는 날 = 거래일"로 본다 `[추론]`.
 *
 * <p>검사: 캘린더 수록 범위와 겹치는 구간에서는 두 목록이 날짜 하나까지 같아야 하고(수집 빠짐이나 캘린더 오류를 조용히 넘기지
 * 않는다), 주말에 일봉이 있으면 거부한다. 스냅샷 마지막 거래일 뒤는 캘린더 거래일로 잇는다(주간 갱신 선정이 그 주 끝까지 묻는다).
 */
public final class SnapshotTradingDays {

    private SnapshotTradingDays() {
    }

    /**
     * @param days 선정 계산에 넘길 거래일
     * @param digest 거래일 목록과 캘린더 출처 요약의 SHA-256. 선정 결과 {@code inputs.calendar_digest}에 들어간다
     */
    public record Derived(TradingDays days, String digest, LocalDate first, LocalDate last, int count) {

        public Derived {
            Objects.requireNonNull(days, "days");
            Objects.requireNonNull(digest, "digest");
        }
    }

    /**
     * @param first 스냅샷 첫 날(manifest {@code coverage.firstDate})
     * @param last 스냅샷 마지막 거래일(manifest {@code coverage.lastTradingDate})
     * @throws IllegalStateException 캘린더와 겹치는 구간에서 거래일이 다르거나 주말 일봉이 있을 때
     */
    public static Derived derive(DailyBarSource bars, String snapshotId, Market market, LocalDate first, LocalDate last,
            ExchangeCalendar calendar) {
        Objects.requireNonNull(bars, "bars");
        if (calendar.market() != market) {
            throw new IllegalArgumentException("캘린더 시장이 다릅니다: " + calendar.market() + " ≠ " + market);
        }
        if (last.isBefore(first)) {
            throw new IllegalArgumentException("범위가 뒤집혔습니다: " + first + " ~ " + last);
        }
        TreeSet<Symbol> symbols = new TreeSet<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            symbols.addAll(bars.universe(market, d));
        }
        TreeSet<LocalDate> fromBars = new TreeSet<>();
        for (Symbol s : symbols) {
            for (DailyBar b : bars.bars(s, first, last)) {
                fromBars.add(b.date());
            }
        }
        if (fromBars.isEmpty()) {
            throw new IllegalStateException("스냅샷에 일봉이 없습니다: " + snapshotId);
        }
        for (LocalDate d : fromBars) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                throw new IllegalStateException("주말 일봉이 있습니다(날짜 처리 오류 의심): " + snapshotId + " " + d);
            }
        }
        LocalDate overlapFrom = max(first, calendar.coverageFrom());
        LocalDate overlapTo = min(last, calendar.coverageTo());
        if (!overlapTo.isBefore(overlapFrom)) {
            TreeSet<LocalDate> expected = new TreeSet<>(calendar.tradingDays(overlapFrom, overlapTo));
            TreeSet<LocalDate> actual = new TreeSet<>(fromBars.subSet(overlapFrom, true, overlapTo, true));
            if (!expected.equals(actual)) {
                List<LocalDate> missing = new ArrayList<>(expected);
                missing.removeAll(actual);
                List<LocalDate> extra = new ArrayList<>(actual);
                extra.removeAll(expected);
                throw new IllegalStateException("스냅샷 거래일이 거래소 캘린더와 다릅니다(" + snapshotId + ", " + overlapFrom + " ~ "
                        + overlapTo + "). 캘린더 거래일인데 일봉 없음 " + head(missing) + ", 캘린더 휴장일인데 일봉 있음 "
                        + head(extra) + ". 수집 빠짐이면 수집기를 다시 돌리고, 캘린더 오류면 krx.json을 고치세요");
            }
        }
        TreeSet<LocalDate> all = new TreeSet<>(fromBars);
        LocalDate coverageTo = last;
        if (!calendar.coverageTo().isBefore(last.plusDays(1)) && !calendar.coverageFrom().isAfter(last.plusDays(1))) {
            all.addAll(calendar.tradingDays(last.plusDays(1), calendar.coverageTo()));
            coverageTo = calendar.coverageTo();
        }
        StringBuilder sb = new StringBuilder("snapshot-trading-days-v1\n").append(snapshotId).append('\n')
                .append(first).append('~').append(coverageTo).append('\n');
        all.forEach(d -> sb.append(d).append('\n'));
        sb.append("calendar ").append(SnapshotSelectionData.calendarDigest(calendar)).append('\n');
        return new Derived(new TradingDays(all, first, coverageTo), sha256(sb.toString()), first, coverageTo, all.size());
    }

    private static String head(List<LocalDate> dates) {
        return dates.size() <= 5 ? dates.toString() : dates.subList(0, 5) + " 외 " + (dates.size() - 5) + "일";
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
