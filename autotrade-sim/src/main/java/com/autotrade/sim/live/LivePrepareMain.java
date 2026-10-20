package com.autotrade.sim.live;

import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.selection.SelectionPreparer;
import com.autotrade.sim.selection.SelectionResultFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 실시간 모의 선정 준비 명령(D-124, ADR-012 3.5 "준비 작업")의 본체. 실험실 힙(기본 4g)으로 전체 일봉 스냅샷을 읽기 어려울 때
 * 실험실 밖에서 거래일 T의 선정 결과 고정 파일을 세션 폴더에 놓는다. 프로세스 진입점은
 * {@code com.autotrade.app.experiment.ExperimentMain}({@code ./gradlew :autotrade-app-experiment:runLiveSelectionPrepare
 * -Pexperiment=<실험ID> -Pdate=YYYY-MM-DD})이고 그쪽이 장 시간 보호와 정지 요청 파일을 {@link RunGate}로 넣는다(D-063).
 *
 * <p>날짜는 꼭 준다(실험 명령 본체는 벽시계를 읽지 않는다). 결과 파일은 같은 이름이 있으면 덮어쓰지 않고, 그날 적용 기록이 이미
 * 있으면 아무것도 하지 않는다. 실험실 세션이 열려 있어도 돌릴 수 있다(세션은 결과 파일을 읽기만 하고, 같은 입력이면 같은 내용이다).
 *
 * <p>인자: {@code <저장소 루트> <커밋> <dirty true|false> <실험ID> --date YYYY-MM-DD [--sessions-dir 폴더]
 * [--snapshots-dir 폴더]}. 종료 코드: 0 모든 선정 준비됨, 1 입력 오류, 2 못 만든 선정이 있음(사유는 출력과 문제 파일),
 * 3 취소 요청으로 멈춤(D-089), 4 정지 확인으로 멈춤.
 */
public final class LivePrepareMain {

    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private LivePrepareMain() {
    }

    /** @param configDir 설정 폴더(세션·스냅샷 폴더 기본값을 정한다) */
    public static int run(String[] args, Path configDir, RunGate gate) {
        if (args.length < 4) {
            System.err.println("인자: <저장소 루트> <커밋> <dirty true|false> <실험ID> --date YYYY-MM-DD [--sessions-dir 폴더] "
                    + "[--snapshots-dir 폴더]");
            return 1;
        }
        Path root = Path.of(args[0]);
        String commit = args[1];
        boolean dirty = Boolean.parseBoolean(args[2]);
        String id = args[3];
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            System.err.println("실험 ID 형식이 아닙니다(영문·숫자로 시작, 영문·숫자·.·_·- 128자 이내): " + id);
            return 1;
        }
        Path sessions = configDir.resolve(LiveCheckMain.SESSIONS_DIR);
        Path snapshots = configDir.resolve(LiveWeeklyMain.SNAPSHOTS_DIR);
        LocalDate date = null;
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--sessions-dir") && i + 1 < args.length) {
                sessions = Path.of(args[++i]);
            } else if (args[i].equals("--snapshots-dir") && i + 1 < args.length) {
                snapshots = Path.of(args[++i]);
            } else if (args[i].equals("--date") && i + 1 < args.length) {
                try {
                    date = LocalDate.parse(args[++i]);
                } catch (DateTimeParseException e) {
                    System.err.println("--date는 YYYY-MM-DD 날짜여야 합니다: " + args[i]);
                    return 1;
                }
            } else {
                System.err.println("모르는 옵션입니다: " + args[i]);
                return 1;
            }
        }
        if (date == null) {
            System.err.println("--date YYYY-MM-DD가 필요합니다(준비할 거래일 T)");
            return 1;
        }
        Path file = root.resolve("experiments").resolve(id).resolve("experiment.yaml");
        if (!Files.isRegularFile(file)) {
            System.err.println("등록 파일이 없습니다: " + file);
            return 1;
        }
        try {
            gate.check();
            LivePlan plan;
            try {
                plan = LivePlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            } catch (IOException e) {
                throw new UncheckedIOException("등록 파일을 읽을 수 없습니다: " + file, e);
            }
            if (!plan.id().value().equals(id)) {
                throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + id + " ≠ " + plan.id());
            }
            // 명령은 힙을 -Pheap으로 정하므로 행 수로 막지 않는다(모자라면 OOM으로 이 명령만 끝난다)
            LiveSelectionPrep.Report r = LiveSelectionPrep.prepare(plan, root, sessions, snapshots, date,
                    new SelectionResultFile.Producer(commit, dirty, null), gate, rows -> true, LivePrepareMain::log);
            for (SelectionPreparer.Outcome o : r.outcomes()) {
                log(o.selection() + ": " + o.status() + (o.reason() == null ? "" : " " + o.reason() + " " + o.detail()));
            }
            if (!r.ready()) {
                log("못 만든 선정이 있습니다: " + r.note());
                return 2;
            }
            log("완료: " + r.note());
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[live-prepare] 멈췄습니다(이미 만든 결과 파일은 그대로 둠): " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("[live-prepare] 실행하지 않았습니다: " + e.getMessage());
            return 1;
        }
    }

    private static void log(String message) {
        System.out.println("[live-prepare] " + message);
    }
}
