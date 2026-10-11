package com.autotrade.marketdata.daily;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * 일봉 스냅샷의 시점별 상장주식수({@code listed_shares.csv}, MD-07, ADR-008). 행은 상장주식수가 바뀐 날(첫 거래일 포함)만
 * 담고, 그 사이 날짜는 직전 행 값을 쓴다. 날짜 D의 값은 D 장 마감 시점 상장주식수다.
 */
public final class SnapshotListedShares {

    private final Map<Symbol, NavigableMap<LocalDate, Long>> shares;

    private SnapshotListedShares(Map<Symbol, NavigableMap<LocalDate, Long>> shares) {
        this.shares = shares;
    }

    public static SnapshotListedShares load(Path dir) {
        try {
            SnapshotManifest manifest = SnapshotFiles.readManifest(dir);
            List<List<String>> rows = SnapshotFiles.readVerified(
                    dir, manifest, SnapshotManifest.LISTED_SHARES, SnapshotDailyBarSource.SHARES_HEADER);
            Map<Symbol, NavigableMap<LocalDate, Long>> out = new TreeMap<>();
            for (List<String> r : rows) {
                Symbol s = new Symbol(Market.valueOf(r.get(0)), r.get(1));
                long n = Long.parseLong(r.get(3));
                if (n <= 0) {
                    throw new IllegalStateException("상장주식수가 0 이하입니다: " + s + " " + r.get(2));
                }
                out.computeIfAbsent(s, k -> new TreeMap<>()).put(LocalDate.parse(r.get(2)), n);
            }
            out.replaceAll((s, m) -> Collections.unmodifiableNavigableMap(m));
            return new SnapshotListedShares(Collections.unmodifiableMap(out));
        } catch (IOException e) {
            throw new UncheckedIOException("상장주식수 파일을 읽을 수 없습니다: " + dir, e);
        }
    }

    /** 날짜 D 장 마감 시점 상장주식수. D 이전 행이 없으면 빈 값(그날 상장 전). */
    public OptionalLong asOf(Symbol symbol, LocalDate date) {
        NavigableMap<LocalDate, Long> m = shares.get(symbol);
        if (m == null) {
            return OptionalLong.empty();
        }
        Map.Entry<LocalDate, Long> e = m.floorEntry(date);
        return e == null ? OptionalLong.empty() : OptionalLong.of(e.getValue());
    }
}
