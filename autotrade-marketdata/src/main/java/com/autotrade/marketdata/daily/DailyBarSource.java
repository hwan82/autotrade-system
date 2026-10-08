package com.autotrade.marketdata.daily;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;

/**
 * 일봉과 종목 이력 조회(MD-07, ADR-002 2.6). 장전 후보 풀(Stage 0)과 일봉 스윙 전략이 쓴다.
 *
 * <p>계약
 * <ul>
 *   <li>입력은 버전이 붙은 스냅샷 하나다({@link #snapshotId()}). 같은 스냅샷이면 같은 결과다(D-019).</li>
 *   <li>미래 정보 방지는 호출자가 {@code to}를 신호 시점 이전으로 두어 지킨다. 이 소스는 요청 범위를 넘는 행을 주지 않는다.</li>
 *   <li>결과는 날짜 오름차순, 종목은 {@link Symbol} 순서. 불변, 스레드 안전.</li>
 * </ul>
 */
public interface DailyBarSource {

    String snapshotId();

    /** {@code [from, to]} 일봉. 품질 검사 오류 행은 빠진다. */
    List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to);

    /** 그날 상장돼 있던 종목(나중에 상장폐지된 종목 포함). 생존편향 제거용. */
    SortedSet<Symbol> universe(Market market, LocalDate date);

    /** 그날 기준 종목 이력 구간. 그날 상장돼 있지 않았으면 빈 값. */
    Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date);
}
