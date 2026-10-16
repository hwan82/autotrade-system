package com.autotrade.app.collector;

import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KRX 일별매매정보 증분 수집(ADR-008 2.3). 날짜·시장마다 응답 원문을 {@code <cache>/krx/<시장>/<연도>/<yyyyMMdd>.json}에
 * 두고, 캐시에 있는 날은 다시 부르지 않는다. 그래서 첫 실행(2010년부터)은 여러 날에 나눠 받고, 그 뒤 실행은 새 거래일만
 * 받는다. 오늘(서울 날짜) 응답은 장 마감 뒤에도 바뀔 수 있어 캐시에 두지 않는다. 주말은 부르지 않는다.
 */
public final class KrxDailyCollector {

    private final KrxOpenApiClient client;
    private final Path cacheDir;
    private final Clock clock;
    private final ExchangeCalendar calendar;

    public KrxDailyCollector(KrxOpenApiClient client, Path cacheDir, Clock clock, ExchangeCalendar calendar) {
        this.client = client;
        this.cacheDir = cacheDir.resolve("krx");
        this.clock = clock;
        this.calendar = calendar;
    }

    /** 결과: 새로 부른 횟수와, 캐시에 두지 않은 응답(오늘, 최근 게시 전 빈 날. 키는 {@code 시장|날짜}). */
    public record Result(int fetched, Map<String, String> uncached) {}

    /** 이 날수 안의 평일 빈 응답은 캐시하지 않는다 `[가정]`. */
    static final int RECENT_EMPTY_DAYS = 7;

    /**
     * {@code from}~{@code to}의 빠진 날을 받는다. 호출 상한에 닿으면 {@link CallBudget.BudgetExhausted}가 그대로 나가고,
     * 그때까지 받은 날은 캐시에 남아 다음 실행이 이어 받는다.
     */
    public Result collect(LocalDate from, LocalDate to, Set<KrxBoard> boards) throws IOException {
        LocalDate today = LocalDate.now(clock.withZone(CallBudget.SEOUL));
        if (to.isAfter(today)) {
            throw new IllegalArgumentException("미래 날짜는 받을 수 없습니다: " + to);
        }
        int fetched = 0;
        Map<String, String> uncached = new HashMap<>();
        List<String> emptyTradingDays = new ArrayList<>(); // 캘린더상 거래일인데 최근이라 게시 전으로 본 빈 응답

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWeekend(d)) {
                continue;
            }
            for (KrxBoard b : EnumSet.copyOf(boards)) {
                Path f = file(b, d);
                if (Files.exists(f)) {
                    continue;
                }
                String body = client.fetchRaw(b, d);
                boolean empty;
                try {
                    empty = KrxOpenApiClient.parse(b, d, body).isEmpty(); // 모양이 틀린 응답은 캐시에 넣지 않는다
                } catch (IOException first) {
                    // 200인데 목록이 빠진 응답이 일시적으로 오는 경우가 있어(2026-10-16 KOSDAQ 2024-03-04, 직후 같은 요청은 1,715건)
                    // 한 번만 다시 부른다. 두 번째도 틀리면 멈춘다. 불안정 직후의 빈 목록은 휴장일 증거로 믿지 않고 멈춘다
                    // (거래일이 휴장일로 캐시되면 다음 거래일에 가짜 수정 이벤트가 생긴다). 휴장일이면 다음 실행 첫 호출이 캐시한다.
                    try {
                        body = client.fetchRaw(b, d);
                        empty = KrxOpenApiClient.parse(b, d, body).isEmpty();
                    } catch (IOException second) {
                        second.addSuppressed(first);
                        throw second;
                    }
                    if (empty) {
                        IOException e = new IOException("KRX 재호출 응답이 빈 목록이라 휴장일로 받지 않습니다: " + b + " " + d);
                        e.addSuppressed(first);
                        throw e;
                    }
                }
                fetched++;
                if (empty && calendarSaysTrading(d)) {
                    // 캘린더 범위 안 거래일의 빈 목록은 휴장일로 캐시하지 않는다(PR #137 strategy-researcher R1 (a)).
                    // 최근 날이면 게시 전일 수 있어 아래에서 캐시 없이 넘기고, 뒤 거래일에 자료가 있으면 끝에서 멈춘다
                    if (!d.isAfter(today.minusDays(RECENT_EMPTY_DAYS))) {
                        throw new IOException("KRX가 캘린더상 거래일에 빈 목록을 줬습니다: " + b + " " + d
                                + ". 일시 오류면 나중에 같은 명령을 다시 돌리고, 그날이 실제 휴장일이면 거래소 캘린더(krx.json)를 고쳐야"
                                + " 합니다(수집기 runbook 3.6)");
                    }
                    emptyTradingDays.add(key(b, d));
                }
                // 최근 평일 빈 응답은 자료 게시 전일 수 있어 캐시하지 않고 다음 실행에서 다시 부른다(오래된 빈 날은 휴장일)
                boolean maybeNotPublished = empty && d.isAfter(today.minusDays(RECENT_EMPTY_DAYS));
                if (d.isBefore(today) && !maybeNotPublished) {
                    write(f, body);
                } else {
                    uncached.put(key(b, d), body);
                }
            }
        }
        for (String k : emptyTradingDays) {
            String[] p = k.split("\\|");
            KrxBoard b = KrxBoard.valueOf(p[0]);
            LocalDate d = LocalDate.parse(p[1]);
            LocalDate later = laterDayWithRows(b, d, to, uncached);
            if (later != null) {
                // 게시 전이면 뒤 날짜도 비어 있어야 한다. 뒤에 자료가 있으면 그 빈 날은 휴장일처럼 다뤄져 가짜 수정 이벤트가 생긴다
                throw new IOException("KRX가 캘린더상 거래일 " + b + " " + d + "에 빈 목록을 줬는데 뒤 거래일 " + later
                        + "에는 자료가 있습니다. 나중에 같은 명령을 다시 돌려 주세요(그날이 실제 휴장일이면 krx.json을 고쳐야 합니다)");
            }
        }
        return new Result(fetched, Map.copyOf(uncached));
    }

    /** 캘린더 수록 범위 안이고 거래일이면 참. 범위 밖(2025년 이전 등)은 판단하지 않는다(품질 리포트의 빈 평일 목록으로 대조). */
    boolean calendarSaysTrading(LocalDate d) {
        return !d.isBefore(calendar.coverageFrom()) && !d.isAfter(calendar.coverageTo()) && calendar.isTradingDay(d);
    }

    private LocalDate laterDayWithRows(KrxBoard b, LocalDate d, LocalDate to, Map<String, String> uncached) throws IOException {
        for (LocalDate x = d.plusDays(1); !x.isAfter(to); x = x.plusDays(1)) {
            if (isWeekend(x)) {
                continue;
            }
            String body = uncached.get(key(b, x));
            if (body == null && Files.exists(file(b, x))) {
                body = Files.readString(file(b, x), StandardCharsets.UTF_8);
            }
            if (body != null && !KrxOpenApiClient.parse(b, x, body).isEmpty()) {
                return x;
            }
        }
        return null;
    }

    /** 캐시와 오늘 응답을 합친 날짜 소스. 범위 안에서 요청 시장이 모두 있는 날만 담는다(빠진 날이 있으면 예외). */
    public KrxDaySource source(LocalDate from, LocalDate to, Set<KrxBoard> boards, Map<String, String> uncached) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWeekend(d)) {
                continue;
            }
            for (KrxBoard b : boards) {
                if (!Files.exists(file(b, d)) && !uncached.containsKey(key(b, d))) {
                    throw new IllegalStateException("수집하지 않은 날이 있습니다: " + b + " " + d);
                }
            }
            dates.add(d);
        }
        Set<KrxBoard> ordered = EnumSet.copyOf(boards);
        return new KrxDaySource() {
            @Override
            public List<LocalDate> dates() {
                return List.copyOf(dates);
            }

            @Override
            public List<KrxDailyRow> rows(LocalDate date) throws IOException {
                List<KrxDailyRow> out = new ArrayList<>();
                for (KrxBoard b : ordered) {
                    String body = uncached.get(key(b, date));
                    if (body == null) {
                        body = Files.readString(file(b, date), StandardCharsets.UTF_8);
                    }
                    out.addAll(KrxOpenApiClient.parse(b, date, body));
                }
                return out;
            }
        };
    }

    static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private Path file(KrxBoard b, LocalDate d) {
        return cacheDir.resolve(b.name()).resolve(String.valueOf(d.getYear()))
                .resolve(KrxOpenApiClient.BAS_DD.format(d) + ".json");
    }

    private static String key(KrxBoard b, LocalDate d) {
        return b + "|" + d;
    }

    private static void write(Path f, String body) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
