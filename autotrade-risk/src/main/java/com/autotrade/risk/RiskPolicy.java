package com.autotrade.risk;

import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.order.OrderRequest;

/**
 * 주문 전 리스크 검증 규칙 하나. 가상과 실전이 같은 구현을 쓴다(프로젝트 지침 5장, 안전 불변식 7).
 *
 * <p>계약
 * <ul>
 *   <li>순수 함수: 같은 입력이면 같은 결과. I/O, 현재 시각 직접 조회, 난수를 쓰지 않는다(필요한 값은 입력으로 받는다).</li>
 *   <li>비즈니스 거절은 {@link RiskDecision.Rejected}로 돌려주고 예외를 던지지 않는다.</li>
 *   <li>스레드 안전해야 한다(여러 계좌 워커가 동시에 호출).</li>
 *   <li>규칙 묶음의 평가 순서는 블랙리스트·수동 보유 필터가 항상 먼저다(안전 불변식 6). 묶음 구성은 risk-ops 작업이다.</li>
 * </ul>
 */
@FunctionalInterface
public interface RiskPolicy {

    RiskDecision evaluate(OrderRequest order, AccountSnapshot account);
}
