package com.autotrade.sim.run;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import java.util.function.Consumer;

/**
 * 실험 입력 시세를 처음부터 차례로 읽어 주는 것(D-115). 목록을 메모리에 들고 있는 {@link MarketDataSet}도 이 계약을 따른다.
 * 목록이 아닌 구현(날짜별 녹화를 그때그때 디스크에서 읽는 것)은 실행기가 모든 Run을 한 번 읽기에 함께 진행시켜 메모리를 이벤트
 * 수와 상관없게 한다({@link SimulationExperimentRunner}).
 *
 * <p>계약
 * <ul>
 *   <li>{@link #forEach}는 부를 때마다 같은 이벤트를 같은 순서(sequence 오름차순, 중복 없음)로 넘긴다. 실행기가 확인하고 어기면
 *       실험을 시작하지 않는다(첫 번째 읽기에서).</li>
 *   <li>같은 {@link #version()}이면 같은 이벤트다.</li>
 *   <li>{@link #forEach}가 읽는 도중 입력이 바뀌었거나 깨졌음을 알면 예외를 던진다. 실행기는 그 예외를 Run 실패가 아니라 실험
 *       실패로 올린다.</li>
 * </ul>
 */
public interface MarketEventStream {

    /** 데이터 스냅샷 ID. {@code ExperimentSpec#dataSnapshotVersion}과 같아야 한다. */
    String version();

    Market market();

    /** {@link MarketDataSet#exchangeTimed()}와 같은 뜻. 기본 true. */
    default boolean exchangeTimed() {
        return true;
    }

    /** 이벤트 수. */
    long eventCount();

    /** 이벤트를 처음부터 순서대로 {@code sink}에 넘긴다. {@code sink}가 던진 예외는 그대로 올라간다. */
    void forEach(Consumer<MarketEvent> sink);
}
