package com.autotrade.sim.selection;

import java.time.LocalDate;

/**
 * 계좌({@code RunStepper})가 거래일마다 묻는 선정 판정. 리플레이는 Run 시작 전에 다 만든 {@link SelectionSchedule}을, 실시간 모의
 * 세션은 날짜 전환 지점에서 그날 적용 기록을 읽어 채우는 표를 넘긴다(ADR-012 3.5, D-122). 두 쪽이 같은 적용 기록에서 같은 값을
 * 돌려줘야 실시간 결과와 리플레이 결과가 같다.
 */
@FunctionalInterface
public interface SelectionDays {

    /** 그 선정의 그 거래일 판정. 모르는 날은 {@code MISSING}. */
    SelectionDay day(String selectionHash, LocalDate date);
}
