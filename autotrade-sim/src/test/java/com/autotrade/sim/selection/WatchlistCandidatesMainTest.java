package com.autotrade.sim.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.run.RunGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 감시 종목 후보 뽑기(ADR-012 배치표 0번). 번들 캘린더 거래일 25일 합성 스냅샷: 종목마다 종가·거래량 고정이라 20거래일 평균
 * 거래대금 = 종가 × 거래량. 000010 50억, 000020 40억, 000030 30억, 000040 20억(보통주), 000050 ETF 100억, 000060 종가 900원
 * (최소 가격 밖), 000070 마지막 날 일봉 없음(20거래일 일봉 일수 밖).
 */
class WatchlistCandidatesMainTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<LocalDate> DAYS =
            KRX.tradingDays(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31)).subList(0, 25);
    private static final String SNAPSHOT = "krx-daily-test-0001";

    @TempDir
    Path tmp;

    private Path snapshots() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("snapshots").resolve("krx-daily").resolve(SNAPSHOT));
        String[][] rows = {
            {"000010", "10000", "500000"}, {"000020", "10000", "400000"}, {"000030", "10000", "300000"},
            {"000040", "10000", "200000"}, {"000050", "10000", "1000000"}, {"000060", "900", "9000000"},
            {"000070", "10000", "900000"},
        };
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        int n = 0;
        for (LocalDate d : DAYS) {
            for (String[] r : rows) {
                if (r[0].equals("000070") && d.equals(DAYS.get(DAYS.size() - 2))) {
                    continue; // 기준 데이터일(마지막 거래일 앞날) 일봉 없음
                }
                bars.append("KRX,").append(r[0]).append(',').append(d).append(',').append(r[1]).append(',').append(r[1])
                        .append(',').append(r[1]).append(',').append(r[1]).append(',').append(r[2]).append(",1\n");
                n++;
            }
        }
        Path b = write(dir, SnapshotManifest.DAILY_BARS, bars.toString());
        StringBuilder hist = new StringBuilder("market,code,name,kind,board,valid_from,valid_to\n");
        for (String[] r : rows) {
            hist.append("KRX,").append(r[0]).append(",종목").append(r[0]).append(',')
                    .append(r[0].equals("000050") ? "ETF" : "STOCK").append(",KOSPI,2000-01-04,\n");
        }
        Path h = write(dir, SnapshotManifest.INSTRUMENT_HISTORY, hist.toString());
        write(dir, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":%d}],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                """.formatted(SNAPSHOT, Sha256.ofFile(b), n, Sha256.ofFile(h), rows.length, DAYS.get(0),
                DAYS.get(DAYS.size() - 1)));
        return tmp.resolve("snapshots");
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private String[] args(String keep, String total, Path out, Path snapshots) {
        return new String[] {"..", SNAPSHOT, keep, total, out.toString(), "--snapshots-dir", snapshots.toString()};
    }

    @Test
    void 꼭_넣을_종목을_앞에_두고_거래대금_순으로_채운다() throws IOException {
        Path snapshots = snapshots();
        Path out = tmp.resolve("out").resolve("candidates.txt");
        // 000040(4위)과 000070(일봉 일수 밖)을 꼭 넣고 총 4종목: 000040, 000070, 000010, 000020
        assertThat(WatchlistCandidatesMain.run(args("000040,000070,000040", "4", out, snapshots), RunGate.NONE)).isZero();
        assertThat(Files.readAllLines(out, StandardCharsets.UTF_8)).containsExactly("000040", "000070", "000010", "000020");
    }

    @Test
    void 보통주만_최소_가격_이상만_순위에_들어간다() throws IOException {
        Path snapshots = snapshots();
        WatchlistCandidatesMain.Candidates c = WatchlistCandidatesMain.compute(
                WatchlistCandidatesMain.profile(Path.of("..")), SNAPSHOT, snapshots, List.of(), 200, RunGate.NONE);
        assertThat(c.codes()).as("ETF·900원·일봉 모자란 종목 제외").containsExactly("000010", "000020", "000030", "000040");
        assertThat(c.applyFrom()).isEqualTo(DAYS.get(DAYS.size() - 1));
        assertThat(c.cutoff()).as("기준 데이터일은 마지막 거래일 앞날").isEqualTo(DAYS.get(DAYS.size() - 2));
        assertThat(c.turnover()).containsEntry("000010", "5000000000");
        assertThat(c.fromRanking()).isEqualTo(4);
        assertThat(c.keptOnly()).isZero();
    }

    @Test
    void 입력이_틀리면_파일을_쓰지_않는다() throws IOException {
        Path snapshots = snapshots();
        Path out = tmp.resolve("bad.txt");
        assertThat(WatchlistCandidatesMain.run(args("5930", "100", out, snapshots), RunGate.NONE)).isEqualTo(1);
        assertThat(WatchlistCandidatesMain.run(args("-", "201", out, snapshots), RunGate.NONE)).isEqualTo(1);
        assertThat(WatchlistCandidatesMain.run(args("000010,000020", "1", out, snapshots), RunGate.NONE)).isEqualTo(1);
        assertThat(WatchlistCandidatesMain.run(new String[] {"..", "../x", "-", "10", out.toString()}, RunGate.NONE))
                .isEqualTo(1);
        assertThat(out).doesNotExist();
    }

    @Test
    void 정지_확인이_멈추라면_파일을_쓰지_않고_4로_끝난다() throws IOException {
        Path snapshots = snapshots();
        Path out = tmp.resolve("stopped.txt");
        RunGate stop = () -> Optional.of("시험 정지");
        assertThat(WatchlistCandidatesMain.run(args("-", "10", out, snapshots), stop)).isEqualTo(4);
        assertThat(out).doesNotExist();
    }
}
