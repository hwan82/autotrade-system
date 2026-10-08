package com.autotrade.marketdata.daily;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.QualityReport.Finding;
import com.autotrade.marketdata.daily.QualityReport.Severity;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 스냅샷 폴더({@code manifest.json}, {@code daily_bars.csv}, {@code instrument_history.csv})에서 읽는 일봉 소스
 * (D-019, ADR-002 2.6). 읽을 때 SHA-256·행 수를 manifest와 맞춰 보고, 품질 검사 오류 행은 뺀다.
 *
 * <pre>
 * daily_bars.csv:         market,code,date,open,high,low,close,volume,adj_factor
 * instrument_history.csv: market,code,name,kind,board,valid_from,valid_to
 * </pre>
 */
public final class SnapshotDailyBarSource implements DailyBarSource {

    static final List<String> BAR_HEADER =
            List.of("market", "code", "date", "open", "high", "low", "close", "volume", "adj_factor");
    static final List<String> HISTORY_HEADER =
            List.of("market", "code", "name", "kind", "board", "valid_from", "valid_to");

    private final SnapshotManifest manifest;
    private final Map<Symbol, NavigableMap<LocalDate, DailyBar>> bars;
    private final Map<Symbol, List<InstrumentPeriod>> history;
    private final QualityReport report;

    private SnapshotDailyBarSource(SnapshotManifest manifest, Map<Symbol, NavigableMap<LocalDate, DailyBar>> bars,
            Map<Symbol, List<InstrumentPeriod>> history, QualityReport report) {
        this.manifest = manifest;
        this.bars = bars;
        this.history = history;
        this.report = report;
    }

    public static SnapshotDailyBarSource load(Path dir) {
        try {
            ObjectMapper m = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            SnapshotManifest manifest = m.readValue(dir.resolve(SnapshotManifest.FILE_NAME).toFile(), SnapshotManifest.class);
            if (manifest.schemaVersion() != SnapshotManifest.SCHEMA_VERSION) {
                throw new IllegalStateException("지원하지 않는 스냅샷 스키마 버전: " + manifest.schemaVersion());
            }
            List<List<String>> barRows = readVerified(dir, manifest, SnapshotManifest.DAILY_BARS, BAR_HEADER);
            List<List<String>> histRows = readVerified(dir, manifest, SnapshotManifest.INSTRUMENT_HISTORY, HISTORY_HEADER);
            List<DailyBar> allBars = new ArrayList<>(barRows.size());
            for (List<String> r : barRows) {
                allBars.add(new DailyBar(new Symbol(Market.valueOf(r.get(0)), r.get(1)), LocalDate.parse(r.get(2)),
                        new BigDecimal(r.get(3)), new BigDecimal(r.get(4)), new BigDecimal(r.get(5)), new BigDecimal(r.get(6)),
                        Long.parseLong(r.get(7)), new BigDecimal(r.get(8))));
            }
            List<InstrumentPeriod> periods = new ArrayList<>(histRows.size());
            for (List<String> r : histRows) {
                periods.add(new InstrumentPeriod(new Symbol(Market.valueOf(r.get(0)), r.get(1)), r.get(2),
                        InstrumentKind.valueOf(r.get(3)), r.get(4), LocalDate.parse(r.get(5)),
                        r.get(6).isEmpty() ? Optional.empty() : Optional.of(LocalDate.parse(r.get(6)))));
            }
            QualityReport report = DailyBarQualityCheck.check(manifest, allBars, periods);
            Set<String> rejected = new HashSet<>();
            for (Finding f : report.findings()) {
                if (f.rule().severity == Severity.ERROR) {
                    rejected.add(f.symbol() + "|" + f.date());
                }
            }
            Map<Symbol, NavigableMap<LocalDate, DailyBar>> byBar = new TreeMap<>();
            for (DailyBar b : allBars) {
                if (!rejected.contains(b.symbol() + "|" + b.date())) {
                    byBar.computeIfAbsent(b.symbol(), s -> new TreeMap<>()).put(b.date(), b);
                }
            }
            Map<Symbol, List<InstrumentPeriod>> byHistory = new TreeMap<>();
            for (InstrumentPeriod p : periods) {
                byHistory.computeIfAbsent(p.symbol(), s -> new ArrayList<>()).add(p);
            }
            byHistory.replaceAll((s, l) -> {
                l.sort((a, b) -> a.validFrom().compareTo(b.validFrom()));
                return List.copyOf(l);
            });
            byBar.replaceAll((s, t) -> Collections.unmodifiableNavigableMap(t));
            return new SnapshotDailyBarSource(manifest, Collections.unmodifiableMap(byBar),
                    Collections.unmodifiableMap(byHistory), report);
        } catch (IOException e) {
            throw new UncheckedIOException("스냅샷을 읽을 수 없습니다: " + dir, e);
        }
    }

    private static List<List<String>> readVerified(Path dir, SnapshotManifest manifest, String name, List<String> header)
            throws IOException {
        SnapshotManifest.FileEntry entry = manifest.files().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest에 " + name + " 항목이 없습니다"));
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            throw new IllegalStateException("스냅샷 파일이 없습니다: " + name);
        }
        String sha = Sha256.ofFile(file);
        if (!sha.equalsIgnoreCase(entry.sha256())) {
            throw new IllegalStateException(name + " SHA-256이 manifest와 다릅니다");
        }
        List<List<String>> rows = Csv.read(file, header);
        if (rows.size() != entry.rows()) {
            throw new IllegalStateException(name + " 행 수가 manifest와 다릅니다: " + rows.size() + " != " + entry.rows());
        }
        return rows;
    }

    @Override
    public String snapshotId() {
        return manifest.snapshotId();
    }

    public SnapshotManifest manifest() {
        return manifest;
    }

    /** 읽을 때 만든 품질 리포트. */
    public QualityReport qualityReport() {
        return report;
    }

    @Override
    public List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to) {
        NavigableMap<LocalDate, DailyBar> m = bars.get(symbol);
        if (m == null || to.isBefore(from)) {
            return List.of();
        }
        return List.copyOf(m.subMap(from, true, to, true).values());
    }

    @Override
    public SortedSet<Symbol> universe(Market market, LocalDate date) {
        SortedSet<Symbol> out = new TreeSet<>();
        history.forEach((s, ps) -> {
            if (s.market() == market && ps.stream().anyMatch(p -> p.covers(date))) {
                out.add(s);
            }
        });
        return Collections.unmodifiableSortedSet(out);
    }

    @Override
    public Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date) {
        return history.getOrDefault(symbol, List.of()).stream().filter(p -> p.covers(date)).findFirst();
    }
}
