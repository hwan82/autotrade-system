package com.autotrade.sim.selection;

import com.autotrade.selection.profile.SelectionProfile;
import java.time.LocalDate;

/**
 * 리플레이·백테스트 실험의 날짜별 선정 결과 공급자(STR-17, ADR-007 2.7 "실험실 쪽"). 실험실 프로세스 안에서 선정 계산
 * ({@code selection.calc}, 배치표 2번)을 돌리는 구현이 들어온다. 실행기는 선정 해시 × 거래일마다 많아야 한 번 부르고
 * 같은 선정을 쓰는 계좌들이 그 결과를 공유한다.
 *
 * <p>계약
 * <ul>
 *   <li>미래 정보 금지: {@code tradingDate}에 적용할 결과는 그 결과의 {@code applyFrom} 직전 거래일까지의 데이터로만
 *       계산한다(MD-10). 실행기는 결과가 {@code tradingDate}를 적용 범위에 넣는지 다시 본다.</li>
 *   <li>결정론: 같은 데이터 스냅샷·같은 선정 프로필·같은 날짜면 같은 값(같은 {@code resultHash})을 돌려준다.</li>
 *   <li>결과가 없으면 {@link DailySelection.Unavailable}을 돌려준다. 예외는 실행기가 {@code PRODUCER_FAILED}로 기록한다.</li>
 *   <li>실행기는 거래일 오름차순으로, 한 스레드에서 부른다.</li>
 * </ul>
 */
@FunctionalInterface
public interface SelectionSource {

    DailySelection select(SelectionProfile selection, LocalDate tradingDate);
}
