package com.autotrade.sim;

/**
 * 실험 실행기. 구현은 3단계(sim-engine) 작업이다.
 *
 * <p>계약
 * <ul>
 *   <li>결정론: 같은 {@link ExperimentSpec}과 같은 데이터면 거래 내역·지표가 비트 단위로 같다.
 *       병렬 실행 여부·스레드 수가 결과에 영향을 주지 않는다.</li>
 *   <li>미래 정보 금지: 신호는 T 시점까지 받은 이벤트로만, 체결은 그 다음 시점 가격으로 한다.</li>
 *   <li>계좌 격리: Run끼리 가변 상태를 공유하지 않는다. 한 Run의 실패는 그 Run만 FAILED로 끝낸다.</li>
 *   <li>모의 주문도 실전과 같은 리스크 검증·주문 상태머신·블랙리스트 코드를 통과한다.</li>
 *   <li>시간은 주입된 {@link java.time.Clock}(리플레이 시 {@code ReplayClock})으로만 얻는다.</li>
 *   <li>호출 스레드를 막는 동기 실행이다. 진행 상황 보고·취소는 3단계에서 확장한다.</li>
 * </ul>
 */
public interface ExperimentRunner {

    ExperimentResult run(ExperimentSpec spec);
}
