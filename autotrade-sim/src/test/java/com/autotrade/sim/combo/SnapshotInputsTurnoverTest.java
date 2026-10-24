package com.autotrade.sim.combo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.selection.SelectionResultFile;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실험 입력에 실제 거래대금 파일(D-133) 잇기(D-139): 일봉 스냅샷 폴더의 {@code daily_turnover.csv}는 turnover 조건이 있는
 * 선정이 처음 계산될 때만 읽고, 결과 파일에 {@code inputs.turnover}가 남아 다시 읽어도 해시가 같다.
 */
class SnapshotInputsTurnoverTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<LocalDate> DAYS =
            KRX.tradingDays(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31)).subList(0, 30);
    private static final String BARS = "krx-daily-test-0001";

    @TempDir
    Path tmp;

    /** 000010: 종가 10,000 × 거래량 1,000 = 근사 1,000만, 실제 거래대금 900만(마지막 날만 1,200만). */
    private Path snapshots(boolean withTurnover) throws IOException {
        Path root = tmp.resolve("snapshots");
        Path dir = Files.createDirectories(root.resolve("krx-daily").resolve(BARS));
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        StringBuilder turnover = new StringBuilder("market,code,date,turnover\n");
        for (LocalDate d : DAYS) {
            bars.append("KRX,000010,").append(d).append(",10000,10100,9900,10000,1000,1\n");
            turnover.append("KRX,000010,").append(d).append(',')
                    .append(d.equals(DAYS.get(DAYS.size() - 1)) ? 12_000_000 : 9_000_000).append('\n');
        }
        Path b = write(dir, SnapshotManifest.DAILY_BARS, bars.toString());
        Path h = write(dir, SnapshotManifest.INSTRUMENT_HISTORY, """
                market,code,name,kind,board,valid_from,valid_to
                KRX,000010,가,STOCK,KOSPI,2000-01-04,
                """);
        String turnoverEntry = "";
        if (withTurnover) {
            Path t = write(dir, "daily_turnover.csv", turnover.toString());
            turnoverEntry = ",{\"name\":\"daily_turnover.csv\",\"sha256\":\"" + Sha256.ofFile(t) + "\",\"rows\":" + DAYS.size() + "}";
        }
        write(dir, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":1}%s],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                """.formatted(BARS, Sha256.ofFile(b), DAYS.size(), Sha256.ofFile(h), turnoverEntry, DAYS.get(0),
                DAYS.get(DAYS.size() - 1)));
        return root;
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static SnapshotInputs load(Path root, List<String> log) {
        return SnapshotInputs.load(Market.KRX, BARS, Optional.empty(), Optional.empty(), root, KRX, RunGate.NONE, log::add,
                (a, b) -> { });
    }

    private static SelectionProfile profile(String metric, String window) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "실제 거래대금 잇기 시험");
        p.put("refresh", "PRE_OPEN_DAILY");
        p.put("max-symbols", "5");
        p.put("gate.01.metric", metric);
        p.put("gate.01.window", window);
        p.put("gate.01.op", "GE");
        p.put("gate.01.value", "0");
        return new SelectionProfile("tov-wiring", 1, Market.KRX, TradingStyle.INTRADAY, p);
    }

    /** 마지막 일봉 다음 거래일(T). c = 스냅샷 마지막 날. */
    private static LocalDate nextDay() {
        return KRX.tradingDays(DAYS.get(DAYS.size() - 1).plusDays(1), DAYS.get(DAYS.size() - 1).plusDays(10)).get(0);
    }

    @Test
    void turnover_선정이_처음_계산될_때만_파일을_읽고_결과에_입력을_남긴다() throws IOException {
        List<String> log = new ArrayList<>();
        SnapshotInputs in = load(snapshots(true), log);
        SelectionCalculator calc = new SelectionCalculator(in.selectionData());

        SelectionResult approx = calc.calculate(profile("avg-turnover", "2"), nextDay());
        assertThat(approx.evaluations().get(0).values()).containsEntry("gate.01", "10000000");
        assertThat(log).as("쓰지 않는 선정은 읽지 않음").noneMatch(l -> l.startsWith("실제 거래대금 읽기"));

        // (9,000,000 + 12,000,000) ÷ 2 = 10,500,000
        SelectionResult actual = calc.calculate(profile("turnover", "2"), nextDay());
        assertThat(actual.evaluations().get(0).values()).containsEntry("gate.01", "10500000");
        calc.calculate(profile("turnover", "1"), nextDay());
        assertThat(log).filteredOn(l -> l.startsWith("실제 거래대금 읽기")).hasSize(1);
        assertThat(actual.inputs().turnoverSha256())
                .isEqualTo(Sha256.ofFile(tmp.resolve("snapshots/krx-daily").resolve(BARS).resolve("daily_turnover.csv")));
        assertThat(actual.inputs().turnoverLastDate()).isEqualTo(DAYS.get(DAYS.size() - 1));

        String file = SelectionResultFile.write(actual, new SelectionResultFile.Producer("abc", false,
                Instant.parse("2026-05-01T09:00:00Z")));
        assertThat(file).contains("\"turnover\":{");
        SelectionResultFile.Read read = SelectionResultFile.read(file);
        assertThat(read.result()).isEqualTo(actual);
        assertThat(read.contentHash()).isEqualTo(actual.contentHash());
        assertThat(SelectionResultFile.write(approx, new SelectionResultFile.Producer("abc", false,
                Instant.parse("2026-05-01T09:00:00Z")))).doesNotContain("\"turnover\"");
    }

    @Test
    void 거래대금_파일이_없는_스냅샷은_turnover_선정만_실패하고_나머지는_그대로다() throws IOException {
        SnapshotInputs in = load(snapshots(false), new ArrayList<>());
        SelectionCalculator calc = new SelectionCalculator(in.selectionData());
        assertThat(calc.calculate(profile("avg-turnover", "2"), nextDay()).selected()).hasSize(1);
        assertThatThrownBy(() -> calc.calculate(profile("turnover", "2"), nextDay()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("daily_turnover.csv가 없습니다");
    }
}
