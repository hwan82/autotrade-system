package com.autotrade.selection.result;

/**
 * 그 거래일에 쓸 선정 결과가 없는 사유(ADR-007 2.6 판정 순서). 이름은 DB {@code run_selection_day.validity} CHECK와 같다
 * (그 열의 {@code VALID}는 결과가 있는 경우라 여기 없다). 사유가 있으면 그날 그 계좌는 신규 진입 0건이고 보유 관리·청산은
 * 그대로다(STR-19, D-044). 실험실 리플레이, 실시간 모의, 실전 엔진이 같은 이름을 쓴다.
 */
public enum SelectionInvalidReason {
    /** 결과가 없음. */
    MISSING,
    /** 그날이 결과의 적용 시작일보다 앞섬. */
    NOT_YET,
    /** 그날이 결과의 적용 종료일보다 뒤. */
    EXPIRED,
    /** 결과의 선정 해시가 계좌 조합의 선정 해시와 다름. */
    HASH_MISMATCH,
    /** 입력 데이터 기준일이 선정 기준일의 직전 거래일보다 이름. */
    STALE_INPUT,
    /** 형식 위반(내용 해시 불일치, 적용 범위 형식, 종목 수 상한 초과 등). */
    MALFORMED,
    /** 생산 작업 실패. 실험실 리플레이에서는 선정 계산 중 예외. */
    PRODUCER_FAILED
}
