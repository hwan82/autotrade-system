package com.autotrade.sim.run;

import com.autotrade.sim.selection.SelectionDay;
import java.util.Objects;

/**
 * 선정 있는 Run의 거래일 하나 기록({@code run_selection_day}, SIM-10 "날짜별 선정 결과 스냅샷 해시").
 *
 * @param selection 그날 쓴 선정 판정(같은 선정을 쓰는 계좌들이 공유한 값)
 * @param blockedEntries 그날 선정 밖 종목이거나 결과가 없어서 내지 않은 신규 진입 신호 수. 청산·손절은 막지 않는다. 진입 여부를
 *     묻는 전략({@code ManagedStrategy})은 한도 확인 앞에서 막힌 횟수라 하루 진입 수 상한에 묶이지 않는다. 묻지 않는 전략은 주문 전
 *     거름이 버린 수다. D-130 전 결과의 값과 비교하지 않는다(D-135)
 */
public record RunSelectionDay(SelectionDay selection, int blockedEntries) {

    public RunSelectionDay {
        Objects.requireNonNull(selection, "selection");
        if (blockedEntries < 0) {
            throw new IllegalArgumentException("blockedEntries는 0 이상이어야 합니다: " + blockedEntries);
        }
    }
}
