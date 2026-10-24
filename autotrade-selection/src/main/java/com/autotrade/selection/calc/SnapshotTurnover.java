package com.autotrade.selection.calc;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 일봉 스냅샷의 실제 거래대금 선택 파일 {@value #FILE}(수집 명령 {@code krx-daily}, D-133)을 읽는다. 지표 {@code turnover}의
 * 입력이다(D-139).
 *
 * <pre>
 * daily_turnover.csv: market,code,date,turnover
 * </pre>
 *
 * 금액은 KRX {@code ACC_TRDVAL} 그대로(원, 수정 계수를 곱하지 않음)이고 거래정지일 0원도 행으로 있다. 수집기는 일봉을 쓴 행 중 값이
 * 있는 행만 쓴다. 파일이 manifest의 SHA-256·행 수와 다르거나, 한 행이라도 형식이 틀리거나(날짜·금액·시장), 같은 종목·날짜가 두 번
 * 나오면 스냅샷 전체를 거부한다. 거래대금 범위 밖 행(D-133 5번 경고)은 수집기가 경고만 남긴 값이라 그대로 읽는다.
 *
 * <p>힙: 종목별 날짜(int)·금액(long) 배열로 행당 12바이트 남짓이다. 일봉 한 행 추정 1KB({@code SnapshotHeap})에 비하면 작아 따로
 * 힙 검사를 두지 않는다 `[추론]`.
 */
public final class SnapshotTurnover implements SelectionData.TurnoverInput {

    /** 수집기 {@code KrxDailySnapshotBuilder.TURNOVER_FILE}과 같은 이름(수집기 모듈은 선정 모듈이 의존하지 않는다). */
    public static final String FILE = "daily_turnover.csv";
    public static final List<String> HEADER = List.of("market", "code", "date", "turnover");

    private final String snapshotId;
    private final String sha256;
    private final LocalDate lastDate;
    private final Map<Symbol, Series> series;

    /** 한 종목의 날짜(에포크 일, 오름차순, 중복 없음)와 금액. */
    private record Series(int[] days, long[] values) {
    }

    private SnapshotTurnover(String snapshotId, String sha256, LocalDate lastDate, Map<Symbol, Series> series) {
        this.snapshotId = snapshotId;
        this.sha256 = sha256;
        this.lastDate = lastDate;
        this.series = series;
    }

    /** manifest에 거래대금 파일 항목이 있는지(D-133 형식 v2 스냅샷인지). */
    public static boolean present(SnapshotManifest manifest) {
        return manifest.files().stream().anyMatch(f -> f.name().equals(FILE));
    }

    /**
     * @throws IllegalStateException manifest에 파일 항목이 없거나(D-133 전 스냅샷, {@code --without-turnover}) 파일이 manifest·형식과
     *     맞지 않을 때
     */
    public static SnapshotTurnover load(Path dir) {
        try {
            SnapshotManifest manifest = SnapshotFiles.readManifest(dir);
            SnapshotManifest.FileEntry entry = manifest.files().stream().filter(f -> f.name().equals(FILE)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("일봉 스냅샷에 실제 거래대금 파일(" + FILE + ")이 없습니다: "
                            + manifest.snapshotId() + ". D-133 전 스냅샷이거나 --without-turnover로 만든 스냅샷입니다"));
            Path file = dir.resolve(FILE);
            if (!Files.exists(file)) {
                throw new IllegalStateException("스냅샷 파일이 없습니다: " + FILE + " (" + manifest.snapshotId() + ")");
            }
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
            }
            Map<String, Builder> builders = new HashMap<>();
            long rows = 0;
            int last = Integer.MIN_VALUE;
            try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), md);
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String head = r.readLine();
                if (head == null || !List.of(head.split(",", -1)).equals(HEADER)) {
                    throw new IllegalStateException(FILE + " 헤더가 스키마와 다릅니다: " + head);
                }
                String line;
                long n = 1;
                while ((line = r.readLine()) != null) {
                    n++;
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] f = line.split(",", -1);
                    if (f.length != HEADER.size()) {
                        throw new IllegalStateException(FILE + " " + n + "번째 줄 열 수가 다릅니다");
                    }
                    Market market;
                    try {
                        market = Market.valueOf(f[0]);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(FILE + " " + n + "번째 줄: 모르는 시장 " + f[0], e);
                    }
                    if (f[1].isEmpty()) {
                        throw new IllegalStateException(FILE + " " + n + "번째 줄: 종목 코드가 없습니다");
                    }
                    int day = (int) date(f[2], n).toEpochDay();
                    long value = amount(f[3], n);
                    builders.computeIfAbsent(f[0] + ',' + f[1], k -> new Builder(new Symbol(market, f[1])))
                            .add(day, value);
                    last = Math.max(last, day);
                    rows++;
                }
            }
            String sha = HexFormat.of().formatHex(md.digest());
            if (!sha.equalsIgnoreCase(entry.sha256())) {
                throw new IllegalStateException(FILE + " SHA-256이 manifest와 다릅니다: " + manifest.snapshotId());
            }
            if (rows != entry.rows()) {
                throw new IllegalStateException(FILE + " 행 수가 manifest와 다릅니다: " + rows + " != " + entry.rows());
            }
            Map<Symbol, Series> series = new HashMap<>();
            for (Builder b : builders.values()) {
                series.put(b.symbol, b.build());
            }
            return new SnapshotTurnover(manifest.snapshotId(), sha,
                    rows == 0 ? null : LocalDate.ofEpochDay(last), Map.copyOf(series));
        } catch (IOException e) {
            throw new UncheckedIOException("실제 거래대금 파일을 읽을 수 없습니다: " + dir, e);
        }
    }

    private static LocalDate date(String v, long line) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(FILE + " " + line + "번째 줄: 날짜 형식이 틀립니다: " + v, e);
        }
    }

    /** 0 이상 정수(원). 수집기는 음수·소수를 원자료 이상으로 멈추므로(D-133 1번) 여기서 보이면 파일이 깨진 것이다. */
    private static long amount(String v, long line) {
        if (!v.matches("[0-9]{1,19}")) {
            throw new IllegalStateException(FILE + " " + line + "번째 줄: 거래대금은 0 이상 정수(원)여야 합니다: " + v);
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(FILE + " " + line + "번째 줄: 거래대금이 너무 큽니다: " + v, e);
        }
    }

    /** 한 종목 행 모으기. 수집기는 날짜 순으로 쓰지만 순서에 기대지 않고 마지막에 정렬한다. */
    private static final class Builder {
        final Symbol symbol;
        int[] days = new int[64];
        long[] values = new long[64];
        int size;

        Builder(Symbol symbol) {
            this.symbol = symbol;
        }

        void add(int day, long value) {
            if (size == days.length) {
                days = Arrays.copyOf(days, size * 2);
                values = Arrays.copyOf(values, size * 2);
            }
            days[size] = day;
            values[size] = value;
            size++;
        }

        Series build() {
            int[] d = Arrays.copyOf(days, size);
            long[] v = Arrays.copyOf(values, size);
            boolean sorted = true;
            for (int i = 1; i < size; i++) {
                if (d[i] <= d[i - 1]) {
                    sorted = false;
                    break;
                }
            }
            if (!sorted) {
                Integer[] idx = new Integer[size];
                for (int i = 0; i < size; i++) {
                    idx[i] = i;
                }
                Arrays.sort(idx, (a, b) -> Integer.compare(d[a], d[b]));
                int[] d2 = new int[size];
                long[] v2 = new long[size];
                for (int i = 0; i < size; i++) {
                    d2[i] = d[idx[i]];
                    v2[i] = v[idx[i]];
                }
                for (int i = 1; i < size; i++) {
                    if (d2[i] == d2[i - 1]) {
                        throw new IllegalStateException(FILE + ": 같은 종목·날짜 행이 두 번 있습니다: " + symbol.code() + " "
                                + LocalDate.ofEpochDay(d2[i]));
                    }
                }
                return new Series(d2, v2);
            }
            return new Series(d, v);
        }
    }

    @Override
    public String snapshotId() {
        return snapshotId;
    }

    @Override
    public String sha256() {
        return sha256;
    }

    @Override
    public LocalDate lastDate() {
        return lastDate;
    }

    @Override
    public OptionalLong turnover(Symbol symbol, LocalDate date) {
        Objects.requireNonNull(symbol, "symbol");
        Series s = series.get(symbol);
        if (s == null) {
            return OptionalLong.empty();
        }
        int i = Arrays.binarySearch(s.days, (int) date.toEpochDay());
        return i < 0 ? OptionalLong.empty() : OptionalLong.of(s.values[i]);
    }
}
