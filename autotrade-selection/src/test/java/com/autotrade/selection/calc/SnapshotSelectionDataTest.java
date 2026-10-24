package com.autotrade.selection.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.daily.SnapshotListedShares;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.financial.SnapshotFinancials;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 수집 스냅샷 폴더(D-019, ADR-008)를 읽는 실데이터 경로. 선정 결과 {@code inputs}가 manifest의 스냅샷 ID·파일 해시·
 * {@code coverage} 기준일을 옮기는지, 기준일을 모르거나 모자라면 계산하지 않는지 본다(PR #45 architect F1).
 * 캘린더는 번들 KRX 2026(10/5 휴장)이다.
 */
class SnapshotSelectionDataTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    /** 10/5 휴장이라 10/6의 기준 데이터일은 10/2. */
    private static final LocalDate T = LocalDate.of(2026, 10, 6);

    @TempDir
    Path tmp;

    private static final String BARS = """
            market,code,date,open,high,low,close,volume,adj_factor
            KRX,005930,2026-09-30,1000,1000,1000,1000,10,1
            KRX,005930,2026-10-01,1000,1000,1000,1000,10,1
            KRX,005930,2026-10-02,1000,1000,1000,1000,10,1
            KRX,000660,2026-09-30,1000,1000,1000,1000,10,1
            KRX,000660,2026-10-01,1000,1000,1000,1000,10,1
            KRX,000660,2026-10-02,1000,1000,1000,1000,10,1
            """;
    private static final String HISTORY = """
            market,code,name,kind,board,valid_from,valid_to
            KRX,005930,가,STOCK,KOSPI,2000-01-04,
            KRX,000660,나,STOCK,KOSPI,2000-01-04,
            """;
    private static final String SHARES = """
            market,code,date,listed_shares
            KRX,005930,2026-01-02,1000
            KRX,000660,2026-01-02,10
            """;
    private static final String FILINGS = """
            market,code,corp_code,bsns_year,reprt_code,rcept_no,rcept_dt,correction,values_known,fs_div,revenue,operating_income,total_assets,total_liabilities,total_equity
            KRX,005930,00000001,2025,11011,20260310000001,2026-03-10,N,Y,CFS,1000,100,150,50,100
            """;

    /** 시가총액 ≥ 100,000원, 부채비율 ≤ 1.5. */
    private static SelectionProfile profile() {
        return new SelectionProfile("snap-test", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>(Map.of(
                "hypothesis", "테스트", "refresh", "POST_CLOSE_DAILY", "max-symbols", "10",
                "gate.01.metric", "market-cap", "gate.01.op", "GE", "gate.01.value", "100000",
                "gate.02.metric", "debt-ratio", "gate.02.op", "LE", "gate.02.value", "1.5")));
    }

    private Path daily(String coverage) throws IOException {
        Path d = Files.createDirectories(tmp.resolve("daily"));
        Path b = write(d, SnapshotManifest.DAILY_BARS, BARS);
        Path h = write(d, SnapshotManifest.INSTRUMENT_HISTORY, HISTORY);
        Path s = write(d, SnapshotManifest.LISTED_SHARES, SHARES);
        write(d, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"krx-daily-snap","schemaVersion":1,"createdAt":"2026-10-02T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-10-02T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":6},{"name":"instrument_history.csv","sha256":"%s","rows":2},
                          {"name":"listed_shares.csv","sha256":"%s","rows":2}]%s}
                """.formatted(Sha256.ofFile(b), Sha256.ofFile(h), Sha256.ofFile(s),
                coverage == null ? "" : ",\"coverage\":" + coverage));
        return d;
    }

    private Path financial(String lastCollectedAt) throws IOException {
        Path d = Files.createDirectories(tmp.resolve("dart"));
        Path f = write(d, SnapshotManifest.FINANCIAL_FILINGS, FILINGS);
        write(d, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"dart-snap","schemaVersion":1,"createdAt":"2026-10-02T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-10-02T08:00:00Z"}],
                 "files":[{"name":"financial_filings.csv","sha256":"%s","rows":1}],
                 "coverage":{"firstDate":"2026-03-10","lastCollectedAt":"%s"}}
                """.formatted(Sha256.ofFile(f), lastCollectedAt));
        return d;
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private SnapshotSelectionData load(String coverage, String lastCollectedAt) throws IOException {
        Path d = daily(coverage);
        return SnapshotSelectionData.of(SnapshotDailyBarSource.load(d), SnapshotListedShares.load(d),
                SnapshotFinancials.load(financial(lastCollectedAt)), KRX);
    }

    @Test
    void 결과_입력_칸이_manifest의_ID_해시_수록_기준일을_옮긴다() throws IOException {
        // 수집 시각 10/1 16:00 UTC = 10/2 01:00 KST → 현지 수집일 10/2(기준 데이터일과 같아 통과)
        SnapshotSelectionData data = load(
                "{\"firstDate\":\"2026-09-30\",\"lastTradingDate\":\"2026-10-02\",\"sharesAsOf\":\"2026-10-02\"}",
                "2026-10-01T16:00:00Z");
        SelectionResult r = new SelectionCalculator(data).calculate(profile(), T);

        assertThat(r.selected()).containsExactly(new SelectionResult.Selected("005930", 1));
        assertThat(r.evaluations()).filteredOn(e -> e.code().equals("000660")).singleElement()
                .satisfies(e -> assertThat(e.reasons()).containsExactly("GATE_01", "GATE_02_NO_DATA"));
        SelectionResult.Inputs in = r.inputs();
        assertThat(in.barsSnapshotId()).isEqualTo("krx-daily-snap");
        assertThat(in.barsSha256()).isEqualTo(Sha256.ofFile(tmp.resolve("daily").resolve(SnapshotManifest.DAILY_BARS)));
        assertThat(in.barsLastTradingDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(in.sharesSnapshotId()).isEqualTo("krx-daily-snap");
        assertThat(in.sharesAsOfDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(in.financialsSnapshotId()).isEqualTo("dart-snap");
        assertThat(in.financialsSha256())
                .isEqualTo(Sha256.ofFile(tmp.resolve("dart").resolve(SnapshotManifest.FINANCIAL_FILINGS)));
        assertThat(in.financialsLastCollectedAt()).isEqualTo("2026-10-01T16:00:00Z");
        assertThat(in.calendarDigest()).isEqualTo(SnapshotSelectionData.calendarDigest(KRX));
    }

    @Test
    void 스냅샷이_기준_데이터일까지_오지_않으면_계산하지_않는다() throws IOException {
        SnapshotSelectionData data = load(
                "{\"firstDate\":\"2026-09-30\",\"lastTradingDate\":\"2026-10-02\",\"sharesAsOf\":\"2026-10-02\"}",
                "2026-10-02T09:00:00Z");
        // 10/7의 기준 데이터일은 10/6인데 일봉은 10/2까지
        assertThatThrownBy(() -> new SelectionCalculator(data).calculate(profile(), LocalDate.of(2026, 10, 7)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("일봉 스냅샷 마지막 거래일 2026-10-02");
    }

    @Test
    void 재무_수집일은_거래소_현지_날짜로_본다() throws IOException {
        // 10/1 14:00 UTC = 10/1 23:00 KST → 현지 수집일 10/1 < 기준 데이터일 10/2
        SnapshotSelectionData data = load(
                "{\"firstDate\":\"2026-09-30\",\"lastTradingDate\":\"2026-10-02\",\"sharesAsOf\":\"2026-10-02\"}",
                "2026-10-01T14:00:00Z");
        assertThatThrownBy(() -> new SelectionCalculator(data).calculate(profile(), T))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("재무 스냅샷 수집일 2026-10-01");
    }

    @Test
    void manifest에_수록_기준일이_없으면_읽지_않는다() throws IOException {
        assertThatThrownBy(() -> load(null, "2026-10-02T09:00:00Z"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("coverage.lastTradingDate");
        Files.walk(tmp.resolve("daily")).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        Files.walk(tmp.resolve("dart")).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        assertThatThrownBy(() -> load("{\"lastTradingDate\":\"2026-10-02\"}", "2026-10-02T09:00:00Z"))
                .as("상장주식수를 받았는데 기준일이 없음").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coverage.sharesAsOf");
    }

    /**
     * 실제 거래대금 파일(D-139)은 쓰는 선정이 처음 계산될 때 한 번만 읽고, 쓰지 않는 선정은 읽지 않는다. 일봉과 스냅샷 ID가 다른
     * 파일은 받지 않는다.
     */
    @Test
    void 실제_거래대금은_쓰는_선정에서_처음_한_번만_읽는다() throws IOException {
        Path d = daily("{\"firstDate\":\"2026-09-30\",\"lastTradingDate\":\"2026-10-02\",\"sharesAsOf\":\"2026-10-02\"}");
        FixtureData.Turnover t = new FixtureData.Turnover();
        t.snapshotId = "krx-daily-snap";
        for (LocalDate day : java.util.List.of(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2))) {
            t.put("005930", day, 30_000L).put("000660", day, 10_000L);
        }
        int[] calls = {0};
        SnapshotSelectionData data = new SnapshotSelectionData(Market.KRX, SnapshotDailyBarSource.load(d),
                SnapshotListedShares.load(d), null, null, () -> {
                    calls[0]++;
                    return t;
                }, TradingDays.of(KRX), SnapshotSelectionData.calendarDigest(KRX), KRX.zone());
        SelectionProfile marketCap = new SelectionProfile("snap-cap", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>(Map.of(
                "hypothesis", "테스트", "refresh", "POST_CLOSE_DAILY", "max-symbols", "10",
                "gate.01.metric", "market-cap", "gate.01.op", "GE", "gate.01.value", "100000")));
        new SelectionCalculator(data).calculate(marketCap, T);
        assertThat(calls[0]).as("쓰지 않는 선정은 읽지 않음").isZero();

        SelectionProfile turnover = new SelectionProfile("snap-tov", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>(Map.of(
                "hypothesis", "테스트", "refresh", "POST_CLOSE_DAILY", "max-symbols", "10",
                "gate.01.metric", "turnover", "gate.01.window", "3", "gate.01.op", "GE", "gate.01.value", "20000")));
        SelectionCalculator calc = new SelectionCalculator(data);
        assertThat(calc.calculate(turnover, T).selected()).containsExactly(new SelectionResult.Selected("005930", 1));
        calc.calculate(turnover, T);
        assertThat(calls[0]).isEqualTo(1);

        SnapshotSelectionData other = new SnapshotSelectionData(Market.KRX, SnapshotDailyBarSource.load(d), null, null, null,
                () -> new FixtureData.Turnover(), TradingDays.of(KRX), SnapshotSelectionData.calendarDigest(KRX), KRX.zone());
        assertThatThrownBy(() -> new SelectionCalculator(other).calculate(turnover, T))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("스냅샷 ID가 일봉과 다릅니다");
        SnapshotSelectionData none = new SnapshotSelectionData(Market.KRX, SnapshotDailyBarSource.load(d), null, null, null,
                null, TradingDays.of(KRX), SnapshotSelectionData.calendarDigest(KRX), KRX.zone());
        assertThat(none.turnover()).isEmpty();
    }
}
