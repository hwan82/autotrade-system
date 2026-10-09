package com.autotrade.marketdata.calendar;

import com.autotrade.domain.market.Market;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 거래소 캘린더(MD-08, ADR-003 2.5). 휴장일·특별 운영일은 데이터 파일(리소스 {@code krx.json}, {@code us.json})에서 읽고,
 * 코드에 날짜를 박지 않는다. 수록 범위 밖 날짜는 {@link CalendarCoverageException}으로 실패한다.
 *
 * <p>주말(토·일)은 두 시장 모두 휴장이다. 서머타임은 {@link ZoneId}(tzdb)가 처리한다. 불변, 스레드 안전.
 */
public final class ExchangeCalendar {

    private final Market market;
    private final ZoneId zone;
    private final LocalDate coverageFrom;
    private final LocalDate coverageTo;
    private final LocalTime regularOpen;
    private final LocalTime regularClose;
    private final NavigableMap<LocalDate, Entry> holidays;
    private final NavigableMap<LocalDate, Special> specials;
    private final Map<String, String> sources;

    private ExchangeCalendar(Data d) {
        this.market = Market.valueOf(d.market());
        this.zone = ZoneId.of(d.zone());
        this.coverageFrom = LocalDate.parse(d.coverageFrom());
        this.coverageTo = LocalDate.parse(d.coverageTo());
        this.regularOpen = LocalTime.parse(d.regularOpen());
        this.regularClose = LocalTime.parse(d.regularClose());
        TreeMap<LocalDate, Entry> h = new TreeMap<>();
        for (Entry e : d.holidays()) {
            if (h.put(LocalDate.parse(e.date()), e) != null) {
                throw new IllegalArgumentException("휴장일 중복: " + e.date());
            }
        }
        TreeMap<LocalDate, Special> s = new TreeMap<>();
        for (Special e : d.specialSessions()) {
            LocalDate date = LocalDate.parse(e.date());
            if (h.containsKey(date) || s.put(date, e) != null) {
                throw new IllegalArgumentException("특별 운영일이 휴장일과 겹치거나 중복: " + e.date());
            }
        }
        for (LocalDate date : h.keySet()) {
            requireCovered(date, "휴장일");
        }
        for (LocalDate date : s.keySet()) {
            requireCovered(date, "특별 운영일");
        }
        this.holidays = Collections.unmodifiableNavigableMap(h);
        this.specials = Collections.unmodifiableNavigableMap(s);
        this.sources = Map.copyOf(d.sources());
    }

    private void requireCovered(LocalDate date, String what) {
        if (date.isBefore(coverageFrom) || date.isAfter(coverageTo)) {
            throw new IllegalArgumentException(what + " " + date + "가 수록 범위 밖입니다");
        }
    }

    /** 이 모듈에 든 시장 캘린더. */
    public static ExchangeCalendar bundled(Market market) {
        String name = switch (market) {
            case KRX -> "krx.json";
            case US -> "us.json";
        };
        try (InputStream in = ExchangeCalendar.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("캘린더 리소스가 없습니다: " + name);
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ExchangeCalendar parse(InputStream json) throws IOException {
        ObjectMapper m = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return new ExchangeCalendar(m.readValue(json, Data.class));
    }

    public Market market() {
        return market;
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate coverageFrom() {
        return coverageFrom;
    }

    public LocalDate coverageTo() {
        return coverageTo;
    }

    /** 거래일이면 그날 정규장 구간. 휴장이면 빈 값. 수록 범위 밖이면 예외. */
    public Optional<TradingSession> session(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(coverageFrom) || date.isAfter(coverageTo)) {
            throw new CalendarCoverageException(market.name(), date, coverageFrom, coverageTo);
        }
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY || holidays.containsKey(date)) {
            return Optional.empty();
        }
        Special sp = specials.get(date);
        if (sp != null) {
            return Optional.of(new TradingSession(date, zone, LocalTime.parse(sp.open()), LocalTime.parse(sp.close()), sp.name()));
        }
        return Optional.of(new TradingSession(date, zone, regularOpen, regularClose, ""));
    }

    public boolean isTradingDay(LocalDate date) {
        return session(date).isPresent();
    }

    /** 그 순간이 정규장 안인지. 순간을 거래소 현지 날짜로 바꿔 그날 구간으로 판단한다. */
    public boolean isRegularSession(Instant instant) {
        LocalDate date = instant.atZone(zone).toLocalDate();
        return session(date).map(s -> s.contains(instant)).orElse(false);
    }

    /** {@code date} 다음 거래일. 범위를 넘으면 예외. */
    public LocalDate nextTradingDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (!isTradingDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** {@code date} 이전 거래일. */
    public LocalDate previousTradingDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (!isTradingDay(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** {@code [from, to]} 안의 거래일(오름차순). */
    public List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isTradingDay(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** 2차 자료만 있거나 아직 거래소 공지가 없어 확인이 필요한 날짜(`[가정]`). */
    public List<LocalDate> unverifiedDates() {
        List<LocalDate> out = new ArrayList<>();
        holidays.forEach((d, e) -> {
            if (!e.verified()) {
                out.add(d);
            }
        });
        specials.forEach((d, e) -> {
            if (!e.verified()) {
                out.add(d);
            }
        });
        Collections.sort(out);
        return out;
    }

    public Map<String, String> sources() {
        return sources;
    }

    record Data(
            String market,
            String zone,
            String coverageFrom,
            String coverageTo,
            String regularOpen,
            String regularClose,
            String checkedOn,
            Map<String, String> sources,
            List<Entry> holidays,
            List<Special> specialSessions) {}

    record Entry(String date, String name, String source, boolean verified) {}

    record Special(String date, String open, String close, String name, String source, boolean verified) {}
}
