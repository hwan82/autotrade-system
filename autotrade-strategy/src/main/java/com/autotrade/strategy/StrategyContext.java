package com.autotrade.strategy;

import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.market.Symbol;
import java.time.Instant;

/** 전략이 신호를 낼 때 볼 수 있는 읽기 전용 상태. 실행기가 이벤트마다 새로 만든다. */
public interface StrategyContext {

    /** 현재 시각(주입된 Clock 기준. 리플레이면 이벤트 수신 시각). */
    Instant now();

    /** 이 이벤트까지 반영한 계좌 상태. 현금은 미체결 매수 예약분을 빼지 않은 값이다. */
    AccountSnapshot account();

    /** 이 종목에 아직 끝나지 않은 주문이 있는지. 중복 진입을 막을 때 쓴다. */
    boolean hasOpenOrder(Symbol symbol);

    /**
     * 실행기가 지금 이 종목의 신규 진입(매수) 신호를 주문으로 받는지(D-130, ADR-013). 선정 조합 계좌는 그날 후보 밖 종목이나
     * 장중 순위 시각 전이면 false다. 청산 신호에는 쓰지 않는다(청산은 언제나 받음).
     *
     * <p>전략은 신규 진입 신호를 내기로 정한 순간에 한 번만 묻고, false면 그 신호를 내지 않으며 하루 진입 수·대기 진입 금액 같은
     * 자기 한도도 쓰지 않는다. 실행기는 false를 돌려준 횟수를 "막은 신규 진입"으로 센다. 그래서 같은 판단에서 두 번 묻지 않는다.
     * 묻지 않는 전략이 낸 신호도 실행기가 주문 전에 같은 기준으로 거른다(이 메서드는 그 거름을 앞당길 뿐 대신하지 않는다).
     *
     * <p>기본값은 true(거르지 않음)다. 선정이 없는 실행기는 이 메서드를 구현하지 않아 전략 동작이 이 메서드 도입 전과 같다.
     */
    default boolean admitsEntry(Symbol symbol) {
        return true;
    }
}
