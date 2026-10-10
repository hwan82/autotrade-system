package com.autotrade.sim.selection;

import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실험실 프로세스 안에서 선정 계산({@link SelectionCalculator})을 돌리는 공급자(ADR-007 2.7 "실험실 쪽", 배치표 2번).
 * 거래일마다 그날을 적용 범위에 넣는 결과의 선정 기준일(매일 갱신은 그날, 주간 갱신은 그 ISO 주 첫 거래일)을 정해 계산한다.
 * 계산은 그 기준일의 직전 거래일까지의 데이터만 쓴다(MD-10).
 *
 * <p>계산한 결과는 선정 해시·기준일별로 남겨 두어 실험 리포트(대체 규칙 적용 건수, 탈락 사유 건수)가 다시 계산하지 않고
 * 읽는다. {@link SelectionSchedule}은 한 스레드에서 부르지만 맵은 동시 읽기에도 안전하게 둔다.
 */
public final class CalculatedSelectionSource implements SelectionSource {

    private final SelectionCalculator calculator;
    private final Map<String, NavigableMap<LocalDate, SelectionResult>> results = new ConcurrentHashMap<>();

    public CalculatedSelectionSource(SelectionCalculator calculator) {
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    @Override
    public DailySelection select(SelectionProfile selection, LocalDate tradingDate) {
        LocalDate applyFrom = calculator.applyFromFor(selection, tradingDate);
        NavigableMap<LocalDate, SelectionResult> byDay = results.computeIfAbsent(selection.selectionHash(),
                k -> Collections.synchronizedNavigableMap(new TreeMap<>()));
        SelectionResult r = byDay.get(applyFrom);
        if (r == null) { // 주간 결과는 그 주 거래일마다 다시 계산하지 않는다(입력이 같으면 결과도 같다)
            r = calculator.calculate(selection, applyFrom);
            byDay.put(applyFrom, r);
        }
        TreeSet<Symbol> symbols = new TreeSet<>();
        r.selected().forEach(s -> symbols.add(new Symbol(selection.market(), s.code())));
        return new DailySelection.Selected(r.selection().hash(), r.contentHash(), r.applyFrom(), r.applyTo(), symbols);
    }

    /** 그 선정으로 계산한 결과(선정 기준일 오름차순). 없으면 빈 맵. */
    public NavigableMap<LocalDate, SelectionResult> results(String selectionHash) {
        NavigableMap<LocalDate, SelectionResult> m = results.get(selectionHash);
        return m == null ? Collections.emptyNavigableMap() : Collections.unmodifiableNavigableMap(new TreeMap<>(m));
    }
}
