package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.recording.Sha256;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.synthetic.SyntheticKrxRules;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
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
        // 깔때기가 Run과 실제로 이어졌다: 후보 검증 행의 진입 시도가 0이 아니다(PR #79 architect R6)
        assertThat(out.files().get("runs.tsv").lines().filter(l -> l.startsWith("VALIDATION\t"))
                .mapToLong(l -> Long.parseLong(l.split("\t", -1)[14])).sum()).isPositive();
        // 청산 사유 분포(PR #98 strategy-researcher 권고 R2): 미상 0, 후보 합 = 검증 왕복 수 합
        String exits = results.substring(results.indexOf("## 청산 사유 분포"), results.indexOf("## 구간 끝 정리"));
        assertThat(exits).contains("| 조합 | 손절 | 익절 | 추적 손절 | 시간·최대 보유 | 로직 청산 | 장 마감 | 구간 끝 정리 | 미상 | 합 |")
                .contains("(대조군, 완료 시드 합)");
        List<String[]> exitRows = exits.lines().filter(l -> l.startsWith("| `")).map(l -> l.split("\\|", -1)).toList();
        assertThat(exitRows).hasSize(2).allSatisfy(c -> assertThat(c[9].trim()).as("미상").isEqualTo("0 (0.00%)"));
        long validationTrips = out.files().get("runs.tsv").lines().filter(l -> l.startsWith("VALIDATION\t"))
                .mapToLong(l -> Long.parseLong(l.split("\t", -1)[11])).sum();
        assertThat(validationTrips).isPositive();
        assertThat(Long.parseLong(exitRows.get(0)[10].trim())).isEqualTo(validationTrips);
        // 해석 칸(D-083): 리더보드 비용 비중·평균 보유·회전율, 대조군 평균 보유, 등록 파일 해시
        assertThat(results).contains("| 비용 비중 | 평균 보유(일) | 회전율 |").contains("| 최대 | 평균 보유(일) |")
                .contains("무작위 진입 + 대조군 청산");
        assertThat(out.files().get("inputs.yaml"))
                .contains("experiment: " + ID + "\nstudy-sha256: " + Sha256.ofFile(dir.resolve("study.yaml")) + "\n");
    }

    @Test
    void 등록_파일_해시는_줄바꿈이_CRLF여도_LF_원본과_같다() {
        String lf = "id: \"EXP-1\"\nseed: \"1\"\n";
        // Windows 작업 트리(core.autocrlf)의 사본과 커밋된 원본이 같은 값이어야 대조할 수 있다(PR #98 검토 M2)
        assertThat(WalkForwardStudy.studySha256(lf.replace("\n", "\r\n"))).isEqualTo(WalkForwardStudy.studySha256(lf))
                .isEqualTo("85faebdab269cb54e3c8fb7a244e5677749c8cf95d708edba348317c0260fa75");
    }

    @Test
    void 같은_입력을_두_번_돌리면_결과가_같다() throws IOException {
        WalkForwardStudy.Outcome first = run(false, RunGate.NONE);
        WalkForwardStudy.Outcome second = run(false, RunGate.NONE);
        assertThat(second.files()).isEqualTo(first.files());
    }

    @Test
    void 실행_명령_병렬_상한이_1이면_결정론_확인을_같음으로_적지_않는다() throws IOException {
        // 등록 파일 parallelism 3이라도 실행 명령 상한 1이면 두 실행이 모두 직렬이라 비교가 안 된다(D-089, PR #113 architect R6)
        RunGate capOne = new RunGate() {
            @Override
            public java.util.Optional<String> stopReason() {
                return java.util.Optional.empty();
            }

            @Override
            public int maxParallelRuns() {
                return 1;
            }
        };
        List<String> log = new ArrayList<>();

        WalkForwardStudy.Outcome out = WalkForwardStudy.runAndWrite(experiment("3"), Path.of(".."), snapshots(),
                new CodeVersion("test", false), true, log::add, capOne);

        assertThat(out.deterministic()).isNull();
        assertThat(out.files().get("inputs.yaml")).doesNotContain("같음(").contains("determinism: \"확인 안 함(실제 병렬 수가 1");
        assertThat(out.files().get("results.md")).doesNotContain("같음(").contains("확인 안 함(실제 병렬 수가 1");
        assertThat(log).contains("결정론 확인 생략: 실제 병렬 수가 1입니다")
                .doesNotContain("결정론 확인: 병렬 1로 전체를 다시 실행")
                .anySatisfy(l -> assertThat(l).startsWith("계산 시작: ").endsWith(", 병렬 1"));
    }

    /** 미리 알린 실험 수와 실제로 시작한 실험 수를 센다(D-097). */
    private static final class Counting implements RunGate {
        final List<Integer> planned = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public java.util.Optional<String> stopReason() {
            return java.util.Optional.empty();
        }

        @Override
        public void onExperimentsPlanned(int count) {
            planned.add(count);
        }

        @Override
        public void onProgress(com.autotrade.sim.run.ExperimentProgress p) {
            if (p.phase() == com.autotrade.sim.run.ExperimentProgress.Phase.STARTED) {
                started.incrementAndGet();
            }
        }
    }

    @Test
    void 미리_알린_실험_수가_실제로_시작한_실험_수와_같다() throws IOException {
        // 구간 3 × (학습 1 + 검증 1 + 스트레스 1 + 대조군 시드 20) = 69. 결정론 확인(병렬 3 vs 1)은 두 배
        Counting once = new Counting();
        run(false, once);
        assertThat(once.planned).containsExactly(69);
        assertThat(once.started).hasValue(69);

        Counting twice = new Counting();
        run(true, twice);
        assertThat(twice.planned).containsExactly(138);
        assertThat(twice.started).hasValue(138);
    }

    @Test
    void 전체_실험_수를_알리다_실패해도_결과는_같다() throws IOException {
        WalkForwardStudy.Outcome plain = run(false, RunGate.NONE);
        RunGate failing = new RunGate() {
            @Override
            public java.util.Optional<String> stopReason() {
                return java.util.Optional.empty();
            }

            @Override
            public void onExperimentsPlanned(int count) {
                throw new IllegalStateException("상태 파일 고장");
            }
        };
        assertThat(run(false, failing).files()).isEqualTo(plain.files());
    }

    @Test
    void 실험_수는_조합이_128개를_넘으면_나눠_부른_만큼_센다() {
        List<com.autotrade.selection.profile.Combination> none = List.of();
        WalkForwardStudy.ComboSets sets = new WalkForwardStudy.ComboSets(
                java.util.Collections.nCopies(129, null), java.util.Collections.nCopies(257, null), java.util.Collections.nCopies(1, null));
        // 구간마다 학습 3 + 검증·스트레스 2×2 + 대조군 10×1 = 17
        assertThat(WalkForwardStudy.experimentsPerCompute(2, sets, 10)).isEqualTo(34);
        assertThat(WalkForwardStudy.experimentsPerCompute(1, new WalkForwardStudy.ComboSets(none, none, none), 5)).isZero();
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
    void 사전_등록할_4단계_10조합은_시작_전_검증과_예열_300일을_통과한다() throws IOException {
        // 절차서 4장의 등록 파일 초안을 그대로 읽는다(자리표시자만 채움). 절차서가 바뀌면 이 테스트가 다시 검사한다
        String runbook = Files.readString(Path.of("../docs/runbooks/stage-4-walkforward.md"), StandardCharsets.UTF_8);
        int from = runbook.indexOf("```yaml\n") + "```yaml\n".length();
        String yaml = runbook.substring(from, runbook.indexOf("```", from))
                .replace("<등록일>", "20261013").replace("<일봉 스냅샷 ID>", "krx-daily-placeholder")
                .replace("<재무 스냅샷 ID>", "dart-financials-placeholder").replace("<PC 코어 수 - 2>", "4");
        assertThat(yaml).doesNotContain("<");
        WalkForwardPlan plan = WalkForwardPlan.parse(yaml, "runbook");
        var factory = new CatalogStrategyFactory(SyntheticKrxRules.INSTANCE);
        List<WalkForwardResult.Candidate> c = WalkForwardStudy.candidates(plan, Path.of(".."), factory);
        assertThat(plan.m()).isEqualTo(10);
        assertThat(c).hasSize(10).allSatisfy(x -> assertThat(x.low()).as(x.text() + " " + x.perturbNote()).isNotNull());
        // 반전 스윙은 하락 일수 3을 2·4로 흔든다(D-083)
        assertThat(c).filteredOn(x -> x.text().endsWith("kr-short-reversal-swing@2")).hasSize(2).allSatisfy(x -> {
            assertThat(x.perturbKey()).isEqualTo("signal.down-days");
            assertThat(x.low().profile().parameters().get("signal.down-days")).isEqualTo("2");
            assertThat(x.high().profile().parameters().get("signal.down-days")).isEqualTo("4");
        });
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
