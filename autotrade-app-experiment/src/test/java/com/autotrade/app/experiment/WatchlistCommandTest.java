package com.autotrade.app.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentJobStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 감시 종목 후보 명령(D-122)을 운영 진입점 {@link ExperimentMain}으로 돌린다: 작업 상태 파일·잠금·정지 확인(D-063)을 거쳐
 * 끝까지 가는지(PR #195 architect R1·R2). 스냅샷은 설정 폴더 기본 위치({@code <설정>/snapshots})에 둔다.
 */
class WatchlistCommandTest {

    private static final String SNAPSHOT = "krx-daily-test-0001";

    @TempDir
    Path config;
    @TempDir
    Path work;

    /** 번들 캘린더 거래일 25일, 3종목. 20거래일 평균 거래대금 000010 50억, 000020 40억, 000030 30억. */
    private void snapshot() throws IOException {
        List<LocalDate> days = ExchangeCalendar.bundled(Market.KRX)
                .tradingDays(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31)).subList(0, 25);
        Path dir = Files.createDirectories(config.resolve("snapshots").resolve("krx-daily").resolve(SNAPSHOT));
        String[][] rows = {{"000010", "500000"}, {"000020", "400000"}, {"000030", "300000"}};
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        for (LocalDate d : days) {
            for (String[] r : rows) {
                bars.append("KRX,").append(r[0]).append(',').append(d).append(",10000,10000,10000,10000,")
                        .append(r[1]).append(",1\n");
            }
        }
        Path b = dir.resolve(SnapshotManifest.DAILY_BARS);
        Files.writeString(b, bars.toString(), StandardCharsets.UTF_8);
        StringBuilder hist = new StringBuilder("market,code,name,kind,board,valid_from,valid_to\n");
        for (String[] r : rows) {
            hist.append("KRX,").append(r[0]).append(",종목").append(r[0]).append(",STOCK,KOSPI,2000-01-04,\n");
        }
        Path h = dir.resolve(SnapshotManifest.INSTRUMENT_HISTORY);
        Files.writeString(h, hist.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(SnapshotManifest.FILE_NAME), """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":%d}],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s"}}
                """.formatted(SNAPSHOT, Sha256.ofFile(b), days.size() * rows.length, Sha256.ofFile(h), rows.length,
                days.get(0), days.get(days.size() - 1)), StandardCharsets.UTF_8);
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private String[] args(String option, Path out) {
        List<String> all = new java.util.ArrayList<>(List.of("watchlist"));
        if (option != null) {
            all.add(option);
        }
        all.addAll(List.of("..", SNAPSHOT, "000030", "2", out.toString()));
        return all.toArray(String[]::new);
    }

    @Test
    void 장_밖에는_끝까지_돌아_목록_파일을_쓰고_작업_상태를_남긴다() throws IOException {
        snapshot();
        Path out = work.resolve("candidates.txt");
        assertThat(ExperimentMain.run(args(null, out), at(ExperimentGateTest.WEEKEND), config)).isZero();
        assertThat(Files.readAllLines(out, StandardCharsets.UTF_8)).containsExactly("000030", "000010");
        ExperimentJobFiles files = ExperimentJobFiles.in(config);
        ExperimentJobStatus status = files.read(files.status("WATCHLIST-CANDIDATES"));
        assertThat(status.command()).isEqualTo("watchlist");
        assertThat(status.state()).isEqualTo(ExperimentJobStatus.State.COMPLETED);
    }

    @Test
    void 장_시간과_정지_요청에는_시작하지_않고_파일을_쓰지_않는다() throws IOException {
        snapshot();
        Path out = work.resolve("candidates.txt");
        assertThat(ExperimentMain.run(args(null, out), at(ExperimentGateTest.KRX_SESSION), config)).isEqualTo(4);
        Path stop = config.resolve(ExperimentGate.STOP_REQUEST);
        Files.createDirectories(stop.getParent());
        Files.writeString(stop, "");
        assertThat(ExperimentMain.run(args("--allow-during-session", out), at(ExperimentGateTest.WEEKEND), config))
                .isEqualTo(4);
        assertThat(out).doesNotExist();
    }

    @Test
    void 같은_작업이_돌고_있으면_입력_오류로_거절하고_파일을_쓰지_않는다() throws Exception {
        snapshot();
        Path out = work.resolve("candidates.txt");
        Clock weekend = at(ExperimentGateTest.WEEKEND);
        try (ExperimentJob running = ExperimentJob.start("WATCHLIST-CANDIDATES", "watchlist",
                ExperimentMain.gate(weekend, config, false), 1, weekend, ExperimentJobFiles.in(config),
                ProcessHandle.current().pid(), Duration.ofHours(1))) {
            assertThat(ExperimentMain.run(args(null, out), weekend, config)).isEqualTo(1);
            assertThat(out).doesNotExist();
            running.finish(0);
        }
    }

    @Test
    void 없는_스냅샷이면_입력_오류로_끝나고_파일을_쓰지_않는다() throws IOException {
        Path out = work.resolve("candidates.txt");
        assertThat(ExperimentMain.run(args(null, out), at(ExperimentGateTest.WEEKEND), config)).isEqualTo(1);
        assertThat(out).doesNotExist();
        ExperimentJobFiles files = ExperimentJobFiles.in(config);
        assertThat(files.read(files.status("WATCHLIST-CANDIDATES")).state())
                .isEqualTo(ExperimentJobStatus.State.INPUT_ERROR);
    }

    @Test
    void 작업_ID는_고정이다() {
        assertThat(ExperimentMain.jobId("watchlist", List.of("..", SNAPSHOT, "-", "100", "x.txt")))
                .isEqualTo("WATCHLIST-CANDIDATES");
    }
}
