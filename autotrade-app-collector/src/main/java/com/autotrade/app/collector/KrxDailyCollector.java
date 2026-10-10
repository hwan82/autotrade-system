package com.autotrade.app.collector;

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

    public KrxDailyCollector(KrxOpenApiClient client, Path cacheDir, Clock clock) {
        this.client = client;
        this.cacheDir = cacheDir.resolve("krx");
        this.clock = clock;
    }

    /** 결과: 새로 부른 횟수와, 캐시에 두지 않은 오늘 응답. */
    public record Result(int fetched, Map<String, String> uncached) {}

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
                KrxOpenApiClient.parse(b, d, body); // 모양이 틀린 응답은 캐시에 넣지 않는다
                fetched++;
                if (d.isBefore(today)) {
                    write(f, body);
                } else {
                    uncached.put(key(b, d), body);
                }
            }
        }
        return new Result(fetched, Map.copyOf(uncached));
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
