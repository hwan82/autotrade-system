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
 *   <li>난수: Run마다 {@code runSeed = H(randomSeed, 조합)}으로 파생한 독립 난수열을 쓴다(선정 없음은
 *       {@code profileHash}, 선정 있음은 {@code combinationHash}, {@code com.autotrade.sim.run.RunSeeds}). 계좌끼리
 *       난수열을 공유하지 않는다(공유하면 실행 순서에 따라 결과가 달라진다).</li>
 *   <li>시계: Run마다 {@code ReplayClock}을 하나씩 쓰고 공유하지 않는다.</li>
 *   <li>식별자: runId·accountId는 실험 ID와 조합 순번에서 결정론적으로 만든다(예: {@code EXP-..-A01}).</li>
 *   <li>시세 소스, 비용 모델, 코드 버전은 구현의 생성자로 주입한다.</li>
 *   <li>호출 스레드를 막는 동기 실행이다. 진행 상황 보고·취소는 3단계에서 확장한다.</li>
 * </ul>
 */
public interface ExperimentRunner {

    ExperimentResult run(ExperimentSpec spec);
}
