package com.autotrade.sim.cost;

/** 비용 표 값. 표 해시에 들어가는 정규 문자열을 낸다(같은 값이면 같은 문자열, 스케일 무관). */
public interface CostValue {

    String canonical();
}
