package com.autotrade.sim.selection;

import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.selection.result.SelectionResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 준비 작업의 계산 부분(ADR-012 3.5 "준비 작업", D-124): 거래일 T의 선정 결과를 계산해 고정 파일로 놓는다. 실시간 모의(실험실의
 * 준비 스레드·준비 실행 명령)와 녹화 실험이 같이 쓴다. 이벤트 경로 밖에서만 부른다.
 *
 * <p>그날 적용 기록이 이미 있으면 아무것도 하지 않는다(그날 계좌가 쓸 선정은 이미 정해짐). 결과 파일이 이미 있는 선정은 다시
 * 계산하지 않는다. 일봉 기준일이 T의 직전 거래일보다 이르면 {@code STALE_INPUT}, 계산이 실패하면 {@code PRODUCER_FAILED} 사유를
 * 남긴다(결과 파일 없음 → 그날 {@code SELECTION_MISSING}).
 */
public final class SelectionPreparer {

    private SelectionPreparer() {
    }

    /** 준비 결과 하나의 상태. */
    public enum Status {
        /** 새로 계산해 결과 파일을 썼다. */
        WRITTEN,
        /** 결과 파일이 이미 있었다. */
        EXISTS,
        /** 그날 적용 기록이 이미 있어 준비할 것이 없다. */
        ALREADY_APPLIED,
        /** 결과를 만들지 못했다(사유를 남김). */
        PROBLEM
    }

    /**
     * 선정 하나의 준비 결과.
     *
     * @param selection 선정 프로필 {@code id@버전}
     * @param reason {@link Status#PROBLEM}일 때 사유
     */
    public record Outcome(String selection, Status status, SelectionInvalidReason reason, String detail) {

        public Outcome {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
        }
    }

    /** 결과 파일이 모두 있거나 그날 적용 기록이 있어 계산할 것이 없는지(스냅샷을 읽기 전에 본다). */
    public static boolean nothingToDo(SessionSelections files, LocalDate date) {
        if (nothingToDoApplied(files, date)) {
            return true;
        }
        return files.selections().stream().allMatch(s -> files.hasResult(date, s.selectionHash()));
    }

    /** 지금 상태만 본다(쓰지 않음): 적용 기록이 있으면 모두 {@code ALREADY_APPLIED}, 아니면 결과 파일이 있는 선정은 {@code EXISTS}. */
    public static List<Outcome> current(SessionSelections files, LocalDate date) {
        List<Outcome> out = new ArrayList<>();
        boolean applied = nothingToDoApplied(files, date);
        for (SelectionProfile s : files.selections()) {
            if (applied) {
                out.add(new Outcome(s.key(), Status.ALREADY_APPLIED, null, ""));
            } else if (files.hasResult(date, s.selectionHash())) {
                out.add(new Outcome(s.key(), Status.EXISTS, null, ""));
            } else {
                out.add(new Outcome(s.key(), Status.PROBLEM, SelectionInvalidReason.MISSING, "결과 파일 없음"));
            }
        }
        return out;
    }

    /** 입력이 없어 계산하지 못한 선정마다 같은 사유를 남긴다(결과 파일이 이미 있는 선정은 건너뜀). */
    public static List<Outcome> problem(SessionSelections files, LocalDate date, SelectionInvalidReason reason,
            String detail) {
        List<Outcome> out = new ArrayList<>();
        if (nothingToDoApplied(files, date)) {
            files.selections().forEach(s -> out.add(new Outcome(s.key(), Status.ALREADY_APPLIED, null, "")));
            return out;
        }
        for (SelectionProfile s : files.selections()) {
            if (files.hasResult(date, s.selectionHash())) {
                out.add(new Outcome(s.key(), Status.EXISTS, null, ""));
            } else {
                files.writeProblem(date, s.selectionHash(), reason, detail);
                out.add(new Outcome(s.key(), Status.PROBLEM, reason, detail));
            }
        }
        return out;
    }

    /**
     * @param calculator 입력 스냅샷으로 만든 계산기
     * @param barsLastTradingDate 일봉 스냅샷 마지막 거래일
     * @param cutoff T의 직전 거래일(기준 데이터일 c)
     * @param checkpoint 선정 하나를 계산하기 전마다 부른다(정지 요청·디스크 관문, architect PR #191 N2). 던진 예외는 그대로 올라간다
     */
    public static List<Outcome> prepare(SessionSelections files, LocalDate date, SelectionCalculator calculator,
            LocalDate barsLastTradingDate, LocalDate cutoff, SelectionResultFile.Producer producer, Runnable checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        List<Outcome> out = new ArrayList<>();
        if (nothingToDoApplied(files, date)) {
            files.selections().forEach(s -> out.add(new Outcome(s.key(), Status.ALREADY_APPLIED, null, "")));
            return out;
        }
        for (SelectionProfile s : files.selections()) {
            if (files.hasResult(date, s.selectionHash())) {
                out.add(new Outcome(s.key(), Status.EXISTS, null, ""));
                continue;
            }
            checkpoint.run();
            if (barsLastTradingDate.isBefore(cutoff)) {
                String detail = "일봉 스냅샷 마지막 거래일 " + barsLastTradingDate + " < 기준 데이터일 " + cutoff;
                files.writeProblem(date, s.selectionHash(), SelectionInvalidReason.STALE_INPUT, detail);
                out.add(new Outcome(s.key(), Status.PROBLEM, SelectionInvalidReason.STALE_INPUT, detail));
                continue;
            }
            SelectionResult r;
            try {
                r = calculator.calculate(s, date);
            } catch (RuntimeException e) {
                String detail = "선정 계산 실패(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
                files.writeProblem(date, s.selectionHash(), SelectionInvalidReason.PRODUCER_FAILED, detail);
                out.add(new Outcome(s.key(), Status.PROBLEM, SelectionInvalidReason.PRODUCER_FAILED, detail));
                continue;
            }
            boolean wrote = files.writeResult(r, producer);
            out.add(new Outcome(s.key(), wrote ? Status.WRITTEN : Status.EXISTS, null, ""));
        }
        return out;
    }

    private static boolean nothingToDoApplied(SessionSelections files, LocalDate date) {
        return java.nio.file.Files.exists(files.appliedFile(date));
    }
}
