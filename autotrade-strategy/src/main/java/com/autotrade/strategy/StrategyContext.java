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
}
