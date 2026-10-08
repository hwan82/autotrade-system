package com.autotrade.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * 실행 중 설정의 단일 출처. 실시간 반영 대상은 매번(매 틱) {@link #current()}로 읽고 값을 캐시하지 않는다.
 *
 * <p>변경({@link #update})은 검증된 새 스냅샷을 디스크에 먼저 쓰고(write-through) 성공하면 메모리를 바꾼다.
 * 디스크 저장이 실패하면 메모리도 그대로라서 재기동 후 상태가 화면과 어긋나지 않는다.
 *
 * <p>실시간 변경 불가 키(시세 소스 구성, 증권사 키)는 여기서 바꿀 수 없다. 증권사 키 변경은
 * "미체결·보유 확인 → 세션 종료 → 재연결" 절차가 필요하므로 그 절차를 구현하는 작업(2·7단계)에서 별도 경로로 연다.
 */
public final class RuntimeConfigHolder {

    private final AtomicReference<RuntimeConfig> current;
    private final RuntimeConfigStore store;

    public RuntimeConfigHolder(RuntimeConfig initial, RuntimeConfigStore store) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.store = Objects.requireNonNull(store, "store");
    }

    public RuntimeConfig current() {
        return current.get();
    }

    /**
     * 설정을 바꾼다. 동시 호출은 직렬화된다.
     *
     * @throws IllegalArgumentException 실시간 변경 불가 키를 바꾸려 할 때
     * @throws java.io.UncheckedIOException 디스크 저장 실패(메모리 상태는 바뀌지 않음)
     */
    public synchronized RuntimeConfig update(UnaryOperator<RuntimeConfig> change) {
        RuntimeConfig before = current.get();
        RuntimeConfig after = Objects.requireNonNull(change.apply(before), "변경 결과");
        if (!after.marketData().equals(before.marketData())) {
            throw new IllegalArgumentException("market-data.*는 실시간 변경 대상이 아닙니다(시세 게이트웨이 재기동 필요)");
        }
        if (!after.brokers().equals(before.brokers())) {
            throw new IllegalArgumentException("brokers.*는 재연결 절차를 거쳐야 바꿀 수 있습니다");
        }
        store.save(after);
        current.set(after);
        return after;
    }
}
