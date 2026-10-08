package com.autotrade.strategy;

/**
 * 프로필에서 Run 전용 {@link Strategy} 인스턴스를 만든다. 실험 실행기가 Run마다 한 번 부른다.
 *
 * <p>스레드 안전해야 한다(여러 Run을 병렬로 시작). 모르는 프로필이면 {@link IllegalArgumentException}을 던지고,
 * 실행기는 그 Run만 FAILED로 끝낸다.
 */
@FunctionalInterface
public interface StrategyFactory {

    /**
     * @param runSeed 이 Run 전용 난수 시드. 전략이 난수가 필요하면 이 값으로 시드한 생성기만 쓴다
     */
    Strategy create(StrategyProfile profile, long runSeed);
}
