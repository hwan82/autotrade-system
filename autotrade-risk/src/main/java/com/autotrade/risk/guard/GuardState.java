package com.autotrade.risk.guard;

/**
 * 실전 계좌 하나의 RealTradingGuard 상태(RSK-01, D-016, D-023).
 *
 * <pre>
 *            beginActivation(2차 확인)          모든 단계 통과
 *   OFF ─────────────────────────▶ ACTIVATING ─────────────▶ OPEN
 *    ▲  ▲                              │ 실패: 출발 상태로         │ deactivate(자동·수동)
 *    │  │ holdingsCleared / shutdown   ▼                         ▼
 *    │  └──────────────────────── PROTECTIVE ◀──────────────────┘
 *    └── enterProtective(2차 확인, 재기동 후 보유가 있을 때) ──┘
 * </pre>
 *
 * 올라가는 전이(OFF·PROTECTIVE → ACTIVATING → OPEN, OFF → PROTECTIVE)는 사람의 2차 확인이 있어야 하고,
 * 내려가는 전이는 자동으로도 일어난다. 자동으로 올라가는 경로는 없다(D-023 공통 원칙 2).
 */
public enum GuardState {
    /** 실전 주문 전면 차단. 주문용 세션·토큰을 만들지 않는다. 기동 직후 모든 계좌의 상태. */
    OFF,
    /** 활성화 진행 중(인증 → 주문 세션 → 첫 대사). 신규 진입은 막는다. */
    ACTIVATING,
    /** 실전 주문 허용. 다른 리스크 규칙은 그대로 적용된다. */
    OPEN,
    /** 보호 모드: 신규 진입 차단, 청산·손절·취소만 허용. 보유가 0이 되면 OFF로 내려간다. */
    PROTECTIVE
}
