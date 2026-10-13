package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 합성 스냅샷(2021-10-01~2025-12-31 평일, 세 종목, 삼각파 가격) 위에서 등록 파일 → 결과 파일까지(4단계 계획 3·4장).
 * 구간 3개(학습 1년 + 검증 1년, 첫 검증 2022-12-31), 후보 1개(m=1), 대조군 시드 20.
 */
class WalkForwardStudyTest {

    private static final String SNAPSHOT = "krx-daily-test-0001";
    private static final String ID = "EXP-TEST-WF01";
    private static final String[] CODES = {"000010", "000020", "000030"};

    @TempDir
    Path tmp;

    private static List<LocalDate> days() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2021, 10, 1); !d.isAfter(LocalDate.of(2025, 12, 31)); d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean holiday = (d.getMonthValue() == 1 && d.getDayOfMonth() == 1)
                    || (d.getMonthValue() == 12 && d.getDayOfMonth() == 25);
            if (!weekend && !holiday) {
                out.add(d);
            }
        }
        return out;
    }

    private Path snapshots() throws IOException {
        List<LocalDate> days = days();
        Path dir = Files.createDirectories(tmp.resolve("snapshots").resolve("krx-daily").resolve(SNAPSHOT));
        StringBuilder bars = new StringBuilder("market,code,date,open,high,low,close,volume,adj_factor\n");
        for (int i = 0; i < days.size(); i++) {
            for (int s = 0; s < CODES.length; s++) {
                int p = (i + s * 37) % (100 + 20 * s);
                int half = (100 + 20 * s) / 2;
                int t = p < half ? p : 2 * half - p;
                int close = 11_000 + 50 * t; // 50원 배수: 모든 시기의 호가 단위와 맞는다
                bars.append("KRX,").append(CODES[s]).append(',').append(days.get(i)).append(',').append(close).append(',')
                        .append(close + 50).append(',').append(close - 50).append(',').append(close).append(",1000,1\n");
            }
        }
        Path b = write(dir, SnapshotManifest.DAILY_BARS, bars.toString());
        Path h = write(dir, SnapshotManifest.INSTRUMENT_HISTORY, """
                market,code,name,kind,board,valid_from,valid_to
                KRX,000010,가,STOCK,KOSPI,2000-01-04,
                KRX,000020,나,STOCK,KOSPI,2000-01-04,
                KRX,000030,다,STOCK,KOSPI,2000-01-04,
                """);
        StringBuilder sh = new StringBuilder("market,code,date,listed_shares\n");
        for (String c : CODES) {
            sh.append("KRX,").append(c).append(',').append(days.get(0)).append(",1000000\n");
        }
        Path l = write(dir, SnapshotManifest.LISTED_SHARES, sh.toString());
        write(dir, SnapshotManifest.FILE_NAME, """
                {"snapshotId":"%s","schemaVersion":1,"createdAt":"2026-05-01T09:00:00Z","scriptVersion":"0.1.0",
                 "sources":[{"name":"테스트","url":"https://example.invalid","license":"테스트용","collectedAt":"2026-05-01T08:00:00Z"}],
                 "files":[{"name":"daily_bars.csv","sha256":"%s","rows":%d},{"name":"instrument_history.csv","sha256":"%s","rows":3},
                          {"name":"listed_shares.csv","sha256":"%s","rows":3}],
                 "coverage":{"firstDate":"%s","lastTradingDate":"%s","sharesAsOf":"%s"}}
                """.formatted(SNAPSHOT, Sha256.ofFile(b), days.size() * CODES.length, Sha256.ofFile(h), Sha256.ofFile(l),
                days.get(0), days.get(days.size() - 1), days.get(days.size() - 1)));
        return tmp.resolve("snapshots");
    }

    private Path experiment(String windows) throws IOException {
        return experiment(windows, "30");
    }

    private Path experiment(String windows, String warmup) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("experiments").resolve(ID));
        write(dir, "study.yaml", """
                id: "%s"
                question: "합성 데이터로 명령이 끝까지 도는가"
                market: "KRX"
                bars-snapshot: "%s"
                seed: "7"
                seed-capital: "10000000"
                parallelism: "3"
                first-test-from: "2022-12-31"
                train-years: "1"
                test-years: "1"
                windows: "%s"
                warmup-days: "%s"
                null-seeds: "20"
                combinations:
                  - "none x kr-ma-trend@1"
                controls:
                  - "SWING kr-random-daily-swing@1"
                """.formatted(ID, SNAPSHOT, windows, warmup));
        return dir;
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private WalkForwardStudy.Outcome run(boolean verify, RunGate gate) throws IOException {
        Path snapshots = snapshots();
        Path dir = experiment("3");
        return WalkForwardStudy.runAndWrite(dir, Path.of(".."), snapshots, new CodeVersion("test", false), verify,
                s -> { }, gate);
    }

    @Test
    void 등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다() throws IOException {
        WalkForwardStudy.Outcome out = run(true, RunGate.NONE);

        assertThat(out.anyRunFailed()).isFalse();
        assertThat(out.deterministic()).isTrue();
        assertThat(out.files()).containsOnlyKeys("inputs.yaml", "results.md", "runs.tsv", "windows.tsv", "detail.txt");
        Path dir = tmp.resolve("experiments").resolve(ID);
        for (String name : out.files().keySet()) {
            assertThat(Files.readString(dir.resolve(name))).isEqualTo(out.files().get(name));
        }
        String results = out.files().get("results.md");
        assertThat(results).contains("C1w").contains("C10").contains("WINDOW_END").contains("`none x kr-ma-trend@1`")
                .contains("계산량 추정");
        assertThat(out.files().get("inputs.yaml")).contains("snapshot: " + SNAPSHOT).contains("combination-hash: ")
                .contains("variant-low-hash: ").contains("cost-table-hash: ").contains("determinism: \"같음");
        // 타임스탬프가 없다: 파일 내용에 실행 시각 흔적이 없어야 바이트 비교가 의미 있다
        assertThat(results).doesNotContainPattern("20\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d");
        assertThat(out.files().get("windows.tsv").lines().count()).isEqualTo(4); // 헤더 + 구간 3
        // 진입 한도 영향 측정(검토 M3): 결과 파일 표와 runs.tsv 열
        assertThat(results).contains("## 진입 한도 영향").contains("`none x kr-ma-trend@1` |")
                .contains("(대조군, 모든 시드 합)");
        assertThat(out.files().get("runs.tsv").lines().findFirst().orElseThrow())
                .contains("\tsignals\tentries\tlimit_blocked\tsize_blocked\tlimit_days\t");
    }

    @Test
    void 같은_입력을_두_번_돌리면_결과가_같다() throws IOException {
        WalkForwardStudy.Outcome first = run(false, RunGate.NONE);
        WalkForwardStudy.Outcome second = run(false, RunGate.NONE);
        assertThat(second.files()).isEqualTo(first.files());
    }

    @Test
    void 정지_확인이_걸리면_결과_파일을_쓰지_않는다() throws IOException {
        RunGate stop = () -> {
            throw new ExperimentStoppedException("테스트 정지");
        };
        assertThatThrownBy(() -> run(false, stop)).isInstanceOf(ExperimentStoppedException.class);
        assertThat(tmp.resolve("experiments").resolve(ID).resolve("results.md")).doesNotExist();
    }

    @Test
    void 구간이_스냅샷_밖으로_나가면_시작하지_않는다() throws IOException {
        Path snapshots = snapshots();
        Path dir = experiment("4"); // 4번째 검증 구간이 2026-12-31까지라 스냅샷(2025-12-31) 밖
        assertThatThrownBy(() -> WalkForwardStudy.runAndWrite(dir, Path.of(".."), snapshots, new CodeVersion("t", false),
                false, s -> { }, RunGate.NONE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스냅샷 범위 밖");
        assertThat(dir.resolve("results.md")).doesNotExist();
    }

    @Test
    void 예열_일수가_후보나_변형의_필요량보다_작으면_모자란_프로필을_적고_시작하지_않는다() throws IOException {
        Path snapshots = snapshots();
        // kr-ma-trend@1은 장기 8일, ±20% 변형 k120은 장기 10일(반올림)이 필요하다
        Path dir = experiment("3", "8");
        assertThatThrownBy(() -> WalkForwardStudy.runAndWrite(dir, Path.of(".."), snapshots, new CodeVersion("t", false),
                false, s -> { }, RunGate.NONE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warmup-days 8일").hasMessageContaining("kr-ma-trend-k120@1")
                .hasMessageNotContaining("kr-ma-trend@1=");
        assertThat(dir.resolve("results.md")).doesNotExist();
    }

    @Test
    void 사전_등록할_4단계_10조합은_시작_전_검증과_예열_300일을_통과한다() {
        // docs/runbooks/stage-4-walkforward.md 4장 등록 파일(스냅샷 ID만 자리표시자)
        WalkForwardPlan plan = WalkForwardPlan.parse("""
                id: "EXP-20261013-WF01"
                question: "q"
                market: "KRX"
                bars-snapshot: "krx-daily-placeholder"
                financials-snapshot: "dart-financials-placeholder"
                seed: "1"
                seed-capital: "10000000"
                parallelism: "4"
                first-test-from: "2022-04-01"
                train-years: "3"
                test-years: "1"
                windows: "4"
                warmup-days: "300"
                null-seeds: "400"
                combinations:
                  - "none x kr-ma-trend@1"
                  - "kr-vol-surge-quiet@1 x kr-ma-trend@1"
                  - "none x kr-donchian-swing@1"
                  - "kr-vol-surge-quiet@1 x kr-donchian-swing@1"
                  - "none x kr-short-reversal-swing@1"
                  - "kr-vol-surge-quiet@1 x kr-short-reversal-swing@1"
                  - "none x kr-ma-trend-position@1"
                  - "kr-fin-quality-growth@1 x kr-ma-trend-position@1"
                  - "none x kr-52w-high-position@1"
                  - "kr-fin-quality-growth@1 x kr-52w-high-position@1"
                controls:
                  - "SWING kr-random-daily-swing@1"
                  - "POSITION kr-random-daily-position@1"
                """, "runbook");
        var factory = new com.autotrade.strategy.catalog.CatalogStrategyFactory(
                com.autotrade.sim.synthetic.SyntheticKrxRules.INSTANCE);
        List<WalkForwardResult.Candidate> c = WalkForwardStudy.candidates(plan, Path.of(".."), factory);
        assertThat(plan.m()).isEqualTo(10);
        assertThat(c).hasSize(10).allSatisfy(x -> assertThat(x.low()).as(x.text() + " " + x.perturbNote()).isNotNull());
        assertThat(WalkForwardJudge.c10Possible(plan.nullSeeds(), plan.m())).isTrue();
        // 52주 고가 ×1.2 변형이 300일을 요구하므로 299일이면 거부된다
        WalkForwardPlan short299 = WalkForwardPlan.parse("""
                id: "EXP-20261013-WF01"
                question: "q"
                market: "KRX"
                bars-snapshot: "krx-daily-placeholder"
                seed: "1"
                seed-capital: "10000000"
                parallelism: "4"
                first-test-from: "2022-04-01"
                train-years: "3"
                test-years: "1"
                windows: "4"
                warmup-days: "299"
                null-seeds: "400"
                combinations:
                  - "none x kr-52w-high-position@1"
                controls:
                  - "POSITION kr-random-daily-position@1"
                """, "runbook-299");
        assertThatThrownBy(() -> WalkForwardStudy.candidates(short299, Path.of(".."), factory))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kr-52w-high-position-k120@1=300일");
    }
}
