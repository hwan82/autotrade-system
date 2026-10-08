package com.autotrade.strategy;

import com.autotrade.domain.market.MarketEvent;
import java.util.List;

/**
 * 전략 실행 로직: 시세 이벤트를 받아 매매 신호를 낸다. 프로필({@link StrategyProfile})은 데이터이고, 이 인터페이스가
 * 그 데이터를 해석해 신호를 만든다(ADR-004 2.1).
 *
 * <p>계약
 * <ul>
 *   <li>인스턴스는 Run(모의 계좌) 하나 전용이다. Run끼리 공유하지 않으며, 한 스레드에서만 호출된다.
 *       인스턴스 안에 지표 상태를 가져도 된다.</li>
 *   <li>결정론: 같은 프로필·같은 runSeed·같은 이벤트 순서면 같은 신호를 낸다. 시각은 이벤트와
 *       {@link StrategyContext#now()}로만, 난수는 {@link StrategyFactory}가 넘긴 runSeed로만 얻는다.</li>
 *   <li>미래 정보 금지: {@code onEvent}는 지금까지 받은 이벤트만 안다. 신호는 이 이벤트 시점(T)에 내고,
 *       체결은 실행기가 T 다음 이벤트로 정한다.</li>
 *   <li>신호는 요청일 뿐이다. 블랙리스트·리스크 검증·잔고 확인에서 거절될 수 있고, 그 결과는 다음 이벤트의
 *       {@link StrategyContext#account()}로 보인다.</li>
 *   <li>모르는 이벤트 타입(예: 이후 추가되는 데이터 공백 표시)도 전달될 수 있다. 처리하지 않으면 빈 목록을 돌려주면 된다.</li>
 *   <li>예외를 던지면 그 Run만 FAILED로 끝난다.</li>
 * </ul>
 */
public interface Strategy {

    /**
     * @param event 방금 도착한 시세 이벤트(실행기가 이벤트 sequence 순서대로 준다)
     * @param context 이 이벤트까지 반영한 계좌 상태(체결은 이 이벤트로 일어난 것까지 포함)
     * @return 낼 신호. 없으면 빈 목록. 목록 순서대로 주문된다
     */
    List<TradeSignal> onEvent(MarketEvent event, StrategyContext context);
}
