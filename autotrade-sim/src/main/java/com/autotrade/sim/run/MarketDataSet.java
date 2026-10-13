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

    /**
     * 이벤트 시각이 실제 거래소 시각인가(D-069). 그렇지 않은 데이터(일봉 경로처럼 칸 시각을 합성한 것)는 가상 거래소가 매매
     * 단계(단일가·장 밖)를 보지 않고 모든 이벤트를 접속 매매로 체결한다. 합성 시각은 그날 실제 개장 시각을 따르지 않고(10:00 개장일에도
     * 09:00 칸), 캘린더 수록 범위 밖 과거도 있기 때문이다. 기본 true.
     */
    default boolean exchangeTimed() {
        return true;
    }
}
