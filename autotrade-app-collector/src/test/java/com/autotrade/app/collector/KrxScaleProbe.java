package com.autotrade.app.collector;

import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 실제 규모 검사용 자식 JVM 진입점({@link CollectorScaleTest}). 힙 상한을 준 JVM에서 합성 일봉 스냅샷을 쓰고
 * 수집기 뒷단계(정리 → 품질 리포트)를 돈다. 모드 {@code full-load}는 비교용으로 예전 방식(전체 적재)을 돈다.
 *
 * <p>인자: mode configDir experimentsDir codes days
 */
public final class KrxScaleProbe {

    private KrxScaleProbe() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path config = Path.of(args[1]);
        Path experiments = Path.of(args[2]);
        int codes = Integer.parseInt(args[3]);
        int days = Integer.parseInt(args[4]);
        Path root = config.resolve("snapshots").resolve("krx-daily");
        LocalDate first = LocalDate.of(2020, 1, 1);
        Path dir;
        try (SnapshotWriter w = new SnapshotWriter(root, "krx-daily")) {
            SnapshotWriter.Sink bars = w.open(SnapshotManifest.DAILY_BARS, SnapshotDailyBarSource.BAR_HEADER);
            SnapshotWriter.Sink hist = w.open(SnapshotManifest.INSTRUMENT_HISTORY, SnapshotDailyBarSource.HISTORY_HEADER);
            w.open(SnapshotManifest.LISTED_SHARES, SnapshotDailyBarSource.SHARES_HEADER);
            for (int c = 0; c < codes; c++) {
                hist.write(List.of("KRX", code(c), "회사" + c, "STOCK", "KOSPI", first.toString(), ""));
            }
            for (int d = 0; d < days; d++) {
                String date = first.plusDays(d).toString();
                for (int c = 0; c < codes; c++) {
                    String p = Integer.toString(10_000 + (c * 7 + d) % 100);
                    bars.write(List.of("KRX", code(c), date, p, p, p, p, "100", "1"));
                }
            }
            String created = "2026-10-08T10:00:00Z";
            String last = first.plusDays(days - 1L).toString();
            dir = w.commit("krx-daily", last, SnapshotManifest.DAILY_BARS, created, "0.1.0",
                    List.of(new SnapshotManifest.Source("합성", "https://example.invalid", "테스트", created)),
                    new SnapshotManifest.Coverage(first.toString(), last, last, created));
        }
        if (mode.equals("full-load")) {
            SnapshotDailyBarSource.load(dir).qualityReport();
            return;
        }
        CollectorMain.Options o = CollectorMain.Options.parse(new String[] {"krx-daily", "--config-dir", config.toString(),
            "--report-dir", experiments.resolve("_data-quality").toString(), "--experiments-dir", experiments.toString(),
            "--keep-snapshots", "1"});
        RunGuard guard = new RunGuard(Clock.systemUTC(), List.of(), true, RunGuard.FILE_STORE, 1, java.nio.file.Path.of("build", "no-such-dir", "stop-request"));
        KrxDailySnapshotBuilder.Stats stats = new KrxDailySnapshotBuilder.Stats((long) codes * days, (long) codes * days, 0, 0, 0,
                0, 0, 0, first, first.plusDays(days - 1L));
        Path report = CollectorMain.finishKrxSnapshot(o, root, dir, guard, stats, 0, 0, java.util.Set.of());
        if (!Files.exists(report)) {
            throw new IllegalStateException("리포트 없음");
        }
    }

    private static String code(int c) {
        return String.format("%06d", 100_000 + c);
    }
}
