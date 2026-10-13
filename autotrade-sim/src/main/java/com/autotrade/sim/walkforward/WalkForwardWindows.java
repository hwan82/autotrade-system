package com.autotrade.sim.walkforward;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 워크포워드 구간 계산(D-066: 학습 3년 + 검증 1년, 1년씩 굴림). 구간 k의 검증은 {@code [t_k, t_{k+1})}, 학습은
 * {@code [t_k − 학습 연수, t_k)}이고 {@code t_k = firstTestFrom + k × testYears년}(달력 연산, 윤일은 {@link LocalDate#plusYears}
 * 규칙: 2월 29일 + 1년 = 2월 28일)이다. 날짜는 달력 날짜 경계이고 휴장일에 걸려도 실험 기간이 빠짐·겹침 없이 이어진다.
 * 실제 거래일 경계는 데이터셋이 정한다.
 */
public final class WalkForwardWindows {

    private WalkForwardWindows() {
    }

    /** 구간 하나. 모든 날짜는 포함(inclusive)이다. */
    public record Window(int index, LocalDate trainFrom, LocalDate trainTo, LocalDate testFrom, LocalDate testTo) {

        /** 1부터 세는 구간 번호. */
        public int number() {
            return index + 1;
        }
    }

    public static List<Window> compute(LocalDate firstTestFrom, int trainYears, int testYears, int windows) {
        if (windows < WalkForwardPlan.MIN_WINDOWS) {
            throw new IllegalArgumentException("구간은 " + WalkForwardPlan.MIN_WINDOWS + "개 이상이어야 합니다(D-066): " + windows);
        }
        if (trainYears < 1 || testYears < 1) {
            throw new IllegalArgumentException("학습·검증 연수는 1 이상이어야 합니다");
        }
        List<Window> out = new ArrayList<>();
        for (int k = 0; k < windows; k++) {
            LocalDate t = firstTestFrom.plusYears((long) k * testYears);
            LocalDate next = firstTestFrom.plusYears((long) (k + 1) * testYears);
            out.add(new Window(k, t.minusYears(trainYears), t.minusDays(1), t, next.minusDays(1)));
        }
        return List.copyOf(out);
    }

    /**
     * 구간이 스냅샷 안에 있는지 본다. 학습 시작이 스냅샷 첫 날보다 앞이거나 마지막 검증일이 마지막 거래일보다 뒤면 거부한다.
     *
     * @throws IllegalArgumentException 범위 밖일 때
     */
    public static void checkCoverage(List<Window> windows, LocalDate snapshotFirst, LocalDate snapshotLast) {
        LocalDate from = windows.get(0).trainFrom();
        LocalDate to = windows.get(windows.size() - 1).testTo();
        if (from.isBefore(snapshotFirst) || to.isAfter(snapshotLast)) {
            throw new IllegalArgumentException("워크포워드 구간이 스냅샷 범위 밖입니다: " + from + " ~ " + to + ", 스냅샷 "
                    + snapshotFirst + " ~ " + snapshotLast + ". first-test-from·windows를 줄이거나 스냅샷을 다시 수집하세요");
        }
    }
}
