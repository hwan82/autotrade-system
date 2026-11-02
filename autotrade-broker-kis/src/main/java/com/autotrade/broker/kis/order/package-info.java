/**
 * 한국투자 실전 주문·계좌 조회(7단계 묶음 D-H, D-145). 이 패키지 밖에서는 이 패키지를 쓰지 않는다: 증권사 플러그인 밖의
 * 어떤 코드도 주문 클라이언트를 직접 만들거나 부를 수 없고(ArchUnit {@code PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN}),
 * 실전 주문 출구는 RealTradingGuard를 거친 {@code RealOrderGateways}만 연다(RSK-01, ADR-002).
 *
 * <p>지금은 어느 프로세스에도 연결하지 않는다. {@code KisPlugin.orderGateway}는 빈 값이고 능력 선언도 그대로다.
 */
package com.autotrade.broker.kis.order;
