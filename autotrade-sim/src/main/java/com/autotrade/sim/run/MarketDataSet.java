package com.autotrade.sim.run;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import java.util.List;

/**
 * 실험 입력 시세: 불변 스냅샷 하나(D-019, SIM-10). 실행기는 이 목록을 한 번 읽어 모든 Run에 같은 순서로 준다.
 * Run마다 시세 소스를 따로 구독하지 않으므로 계좌 수와 무관하게 같은 데이터를 본다(SIM-03).
 *
 * <p>계약
 * <ul>
 *   <li>{@link #events()}는 sequence 오름차순(중복 없음)이고 불변이다. 실행기가 확인하고 어기면 실험을 시작하지 않는다.</li>
 *   <li>같은 {@link #version()}이면 같은 이벤트다.</li>
 *   <li>2단계 녹화(ADR-003 시세)는 {@code RecordedMarketDataSet}이 이 계약에 맞춘다(ADR-004 4장, D-068).</li>
 * </ul>
 */
public interface MarketDataSet {

    /** 데이터 스냅샷 ID. {@code ExperimentSpec#dataSnapshotVersion}과 같아야 한다. */
    String version();

    Market market();

    List<MarketEvent> events();
}
