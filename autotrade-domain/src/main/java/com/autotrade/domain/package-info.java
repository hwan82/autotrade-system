/**
 * 공통 도메인 모델. 순수 Java이며 다른 autotrade 모듈과 프레임워크에 의존하지 않는다.
 *
 * <p>금액은 {@link com.autotrade.domain.Money}(BigDecimal + 통화)로만 다루고, 현재 시각은 주입된
 * {@link java.time.Clock}으로만 얻는다. 근거: ADR-001.
 */
package com.autotrade.domain;
