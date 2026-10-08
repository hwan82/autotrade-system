package com.autotrade.sim.cost;

/** 거래 기록(virtual_trade_log)의 비용 열. 미국 규제 수수료(SEC fee, TAF)는 TAX 열에 넣는다(ADR-004 2.3). */
public enum CostCategory {
    COMMISSION,
    TAX
}
