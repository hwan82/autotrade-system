package com.autotrade.sim.selection;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.combo.CombinationPlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 선정 결과 고정 파일 시험용 결과(계산기 없이 손으로 만든다, D-124). */
public final class SelectionFixtures {

    /** 저장소의 단타 예시 선정(PRE_OPEN_DAILY, ADR-012 3.3). */
    public static SelectionProfile intradayHot(Path repoRoot) {
        return CombinationExperiment.loadSelection(repoRoot, new CombinationPlan.Ref("kr-intraday-hot", 1));
    }

    /**
     * 거래일 {@code date} 하루 적용 결과. 선정 종목은 {@code codes} 순서대로 순위를 매기고, {@code rejected}는 탈락 평가로만 넣는다.
     *
     * @param barsLast 일봉 스냅샷 마지막 거래일
     */
    public static SelectionResult result(SelectionProfile p, LocalDate date, LocalDate barsLast, List<String> codes,
            List<String> rejected) {
        List<SelectionResult.Selected> selected = new ArrayList<>();
        List<SelectionResult.Evaluation> evaluations = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            selected.add(new SelectionResult.Selected(codes.get(i), i + 1));
            TreeMap<String, String> values = new TreeMap<>();
            values.put("score", String.valueOf(100 - i));
            evaluations.add(new SelectionResult.Evaluation(codes.get(i), true, List.of(), values, false));
        }
        for (String c : rejected) {
            evaluations.add(new SelectionResult.Evaluation(c, false, List.of("GATE_FAILED"), new TreeMap<>(), false));
        }
        SelectionResult.Inputs inputs = new SelectionResult.Inputs("krx-daily-test", "a".repeat(64), barsLast, null, null,
                null, null, null, "b".repeat(64));
        return new SelectionResult(SelectionResult.Selection.of(p), date, date, inputs, selected, evaluations);
    }

    public static SelectionResult result(SelectionProfile p, LocalDate date, List<String> codes) {
        return result(p, date, date.minusDays(1), codes, List.of());
    }

    /**
     * KRX 일봉 스냅샷 폴더 하나({@code <snapshotsRoot>/krx-daily/<id>}). 마지막 거래일 {@code last}까지 30거래일.
     * 005930은 종가 60,000·거래량 200,000(거래대금 120억)이다가 {@code last}에 62,000(+3.3%)·500,000(앞 20일 평균의 2.5배)
     * → 예시 (다) {@code kr-intraday-hot@1}에 걸린다. 035720도 같은 모양(boards 밖 시험용). 000660은 180,000·100,000 고정이라
     * 걸리지 않는다. {@code hynixJumps}면 000660도 {@code last}에 189,000(+5%)·300,000으로 걸린다(스냅샷이 바뀐 경우 시험용).
     *
     * @return {@code snapshotsRoot}
     */
    public static Path dailySnapshot(Path snapshotsRoot, String id, LocalDate last, boolean hynixJumps) {
        ExchangeCalendar krx = ExchangeCalendar.bundled(Market.KRX);
        List<LocalDate> days = krx.tradingDays(last.minusDays(80), last);
        days = days.subList(days.size() - 30, days.size());
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        for (LocalDate d : days) {
            boolean c = d.equals(last);
            row(bars, "005930", d, c ? 62_000 : 60_000, c ? 500_000 : 200_000);
            row(bars, "000660", d, c && hynixJumps ? 189_000 : 180_000, c && hynixJumps ? 300_000 : 100_000);
            row(bars, "035720", d, c ? 52_000 : 50_000, c ? 900_000 : 300_000);
        }
        try {
            Path dir = Files.createDirectories(snapshotsRoot.resolve("krx-daily").resolve(id));
            Path b = write(dir, SnapshotManifest.DAILY_BARS, bars.toString());
            Path h = write(dir, SnapshotManifest.INSTRUMENT_HISTORY, """
                    market,code,name,kind,board,valid_from,valid_to
                    KRX,000660,하,STOCK,KOSPI,2000-01-04,
                    KRX,005930,삼,STOCK,KOSPI,2000-01-04,
                    KRX,035720,카,STOCK,KOSPI,2000-01-04,
                    """);
            write(dir, SnapshotManifest.FILE_NAME, """
                    {"snapshotId":"%s","schemaVersion":1,"createdAt":"%sT09:00:00Z","scriptVersion":"0.1.0",
                     "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"%sT08:00:00Z"}],
                     "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":3}],
                     "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                    """.formatted(id, last, last, Sha256.ofFile(b), days.size() * 3, Sha256.ofFile(h), days.get(0),
                    last));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return snapshotsRoot;
    }

    private static void row(StringBuilder sb, String code, LocalDate d, int close, long volume) {
        sb.append("KRX,").append(code).append(',').append(d).append(',').append(close).append(',').append(close)
                .append(',').append(close).append(',').append(close).append(',').append(volume).append(",1\n");
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private SelectionFixtures() {
    }
}
