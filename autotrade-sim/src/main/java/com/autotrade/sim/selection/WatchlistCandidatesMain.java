package com.autotrade.sim.selection;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionProfileYaml;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.combo.SnapshotInputs;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 게이트웨이 감시 종목 후보 뽑기(ADR-012 배치표 0번). 수집 일봉 스냅샷의 마지막 거래일 T에 선정 프로필
 * {@code kr-watchlist-turnover@1}(최근 20거래일 평균 거래대금 상위 {@code STOCK}, 기준 데이터일 c = T의 앞 거래일)을 계산하고, 꼭 넣을
 * 종목(LIVE01 {@code boards} 등)을 앞에 둔 뒤 순위대로 채워 {@code total}종목 목록을 파일에 쓴다. 게이트웨이 설정은 바꾸지 않는다.
 * {@code watchlist.json}은 사용자가 시작 점검표의 상자로 만든다.
 *
 * <p>프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}이다({@code ./gradlew
 * :autotrade-app-experiment:runWatchlistCandidates}). 그쪽이 장 시간 보호와 감시의 정지 요청 파일을 {@link RunGate}로 넣는다
 * (D-063, 전 기간 스냅샷을 읽어 힙을 크게 쓰기 때문). 이 클래스에는 {@code main}이 없다.
 *
 * <p>인자: {@code <저장소 루트> <일봉 스냅샷 ID> <꼭 넣을 종목 쉼표 목록 또는 -> <총 종목 수 1~200> <출력 파일>
 * [--snapshots-dir 폴더]}. 출력은 한 줄에 6자리 종목 코드 하나(UTF-8), 임시 파일에 쓴 뒤 이름을 바꾼다. 종료 코드: 0 완료,
 * 1 입력 오류, 3 취소 요청·4 정지 확인으로 멈춤(파일을 쓰지 않음).
 */
public final class WatchlistCandidatesMain {

    /** 작업 잠금·상태 파일용 고정 ID(실험 결과 폴더를 만들지 않는다). */
    public static final ExperimentId ID = new ExperimentId("WATCHLIST-CANDIDATES");
    /** 쓰는 선정 프로필. */
    static final String PROFILE = "kr-watchlist-turnover";
    /** 키움 세션당 실시간 등록 상한(docs/research/market-data-limits.md K1). */
    static final int MAX_TOTAL = 200;
    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{6}");
    private static final Pattern SNAPSHOT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private WatchlistCandidatesMain() {
    }

    /** 뽑은 목록과 설명. */
    record Candidates(LocalDate applyFrom, LocalDate cutoff, List<String> codes, int keptOnly, int fromRanking,
            Map<String, String> turnover) {
    }

    /**
     * @param configDir 설정 폴더(실험 실행 명령이 정한 것). {@code --snapshots-dir}가 없으면 그 아래 {@code snapshots}
     * @param gate 정지 확인(D-063). 스냅샷을 읽기 전과 계산 전에 묻는다
     */
    public static int run(String[] args, Path configDir, RunGate gate) {
        if (args.length < 5) {
            System.err.println("인자: <저장소 루트> <일봉 스냅샷 ID> <꼭 넣을 종목 쉼표 목록 또는 -> <총 종목 수 1~"
                    + MAX_TOTAL + "> <출력 파일> [--snapshots-dir 폴더]");
            return 1;
        }
        Path root = Path.of(args[0]);
        String snapshot = args[1];
        if (!SNAPSHOT.matcher(snapshot).matches()) {
            System.err.println("스냅샷 ID 형식이 아닙니다: " + snapshot);
            return 1;
        }
        List<String> keep = new ArrayList<>();
        if (!args[2].equals("-")) {
            for (String c : args[2].split(",")) {
                String code = c.strip();
                if (!CODE.matcher(code).matches()) {
                    System.err.println("종목 코드는 6자리여야 합니다: " + c);
                    return 1;
                }
                keep.add(code);
            }
        }
        int total;
        try {
            total = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            total = -1;
        }
        if (total < 1 || total > MAX_TOTAL) {
            System.err.println("총 종목 수는 1~" + MAX_TOTAL + "이어야 합니다: " + args[3]);
            return 1;
        }
        if (new LinkedHashSet<>(keep).size() > total) {
            System.err.println("꼭 넣을 종목(" + keep.size() + ")이 총 종목 수(" + total + ")보다 많습니다");
            return 1;
        }
        Path out = Path.of(args[4]);
        Path snapshots = null;
        for (int i = 5; i < args.length; i++) {
            if (args[i].equals("--snapshots-dir") && i + 1 < args.length) {
                snapshots = Path.of(args[++i]);
            } else {
                System.err.println("모르는 옵션입니다: " + args[i]);
                return 1;
            }
        }
        if (snapshots == null) {
            snapshots = configDir.resolve("snapshots");
        }
        try {
            SelectionProfile profile = profile(root);
            Candidates c = compute(profile, snapshot, snapshots, keep, total, gate);
            write(out, c.codes());
            log("선정 기준일 " + c.applyFrom() + "(기준 데이터일 " + c.cutoff() + "), 스냅샷 " + snapshot);
            log("후보 " + c.codes().size() + "종목: 꼭 넣을 종목 " + keep.size() + "(그중 거래대금 순위 밖 " + c.keptOnly()
                    + ") + 거래대금 순위 " + c.fromRanking());
            String last = c.codes().get(c.codes().size() - 1);
            log("마지막 종목 " + last + " 20거래일 평균 거래대금(원): " + c.turnover().getOrDefault(last, "순위 밖"));
            log("출력: " + out.toAbsolutePath());
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[watchlist] 멈췄습니다(파일을 쓰지 않음): " + e.getMessage());
            return e.exitCode();
        } catch (IOException | RuntimeException e) {
            // 스냅샷을 못 읽은 UncheckedIOException 등도 입력 오류로 끝낸다(다른 실험 명령과 같다, PR #195 architect R3)
            System.err.println("[watchlist] 실패: " + e.getMessage());
            return 1;
        }
    }

    static SelectionProfile profile(Path root) throws IOException {
        Path f = root.resolve("selections").resolve(PROFILE).resolve("v1.yaml");
        return SelectionProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
    }

    /**
     * 꼭 넣을 종목을 입력 순서대로 앞에 두고(중복 제거), 선정 순위대로 {@code total}까지 채운다. 선정 기준일은 스냅샷 마지막
     * 거래일이다(스냅샷 밖 다음 거래일은 계산기 거래일 목록에 없다). 하루 늦은 정보지만 감시 종목 후보라 충분하다.
     */
    static Candidates compute(SelectionProfile profile, String snapshot, Path snapshots, List<String> keep, int total,
            RunGate gate) {
        SnapshotInputs in = SnapshotInputs.load(Market.KRX, snapshot, Optional.empty(), snapshots,
                ExchangeCalendar.bundled(Market.KRX), gate, WatchlistCandidatesMain::log, (f, l) -> { });
        gate.check();
        LocalDate t = in.last();
        SelectionResult r = new SelectionCalculator(in.selectionData()).calculate(profile, t);
        Map<String, String> turnover = r.evaluations().stream().filter(e -> e.values().containsKey("score"))
                .collect(Collectors.toMap(SelectionResult.Evaluation::code, e -> e.values().get("score")));
        Set<String> codes = new LinkedHashSet<>(keep);
        int keptOnly = (int) codes.stream().filter(c -> r.selected().stream().noneMatch(s -> s.code().equals(c))).count();
        int fromRanking = 0;
        for (SelectionResult.Selected s : r.selected()) {
            if (codes.size() >= total) {
                break;
            }
            if (codes.add(s.code())) {
                fromRanking++;
            }
        }
        return new Candidates(t, in.days().days().previous(t), List.copyOf(codes), keptOnly, fromRanking, turnover);
    }

    private static void write(Path out, List<String> codes) throws IOException {
        Path dir = out.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".watchlist-", ".tmp");
        try {
            Files.writeString(tmp, String.join("\n", codes) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }


    private static void log(String line) {
        System.out.println("[watchlist] " + line);
    }
}
