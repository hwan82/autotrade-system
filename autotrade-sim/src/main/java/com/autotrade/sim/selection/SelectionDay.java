package com.autotrade.sim.selection;

import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.result.SelectionInvalidReason;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 한 선정·한 거래일의 판정 결과. 같은 선정을 쓰는 계좌들이 공유하는 불변 값이다.
 *
 * @param resultHash 쓴 결과의 내용 해시. 사유가 있으면 null
 * @param reason 결과가 없는 사유. 결과가 있으면 null
 * @param symbols 신규 진입을 허용하는 종목. 사유가 있으면 빈 집합
 */
public record SelectionDay(LocalDate date, String resultHash, SelectionInvalidReason reason, SortedSet<Symbol> symbols) {

    public SelectionDay {
        Objects.requireNonNull(date, "date");
        if ((resultHash == null) == (reason == null)) {
            throw new IllegalArgumentException("결과 해시와 사유 중 정확히 하나가 있어야 합니다: " + date);
        }
        symbols = Collections.unmodifiableSortedSet(new TreeSet<>(symbols));
        if (reason != null && !symbols.isEmpty()) {
            throw new IllegalArgumentException("사유가 있는 날은 선정 종목이 없어야 합니다: " + date);
        }
    }

    static SelectionDay valid(LocalDate date, String resultHash, SortedSet<Symbol> symbols) {
        return new SelectionDay(date, Objects.requireNonNull(resultHash, "resultHash"), null, symbols);
    }

    static SelectionDay invalid(LocalDate date, SelectionInvalidReason reason) {
        return new SelectionDay(date, null, Objects.requireNonNull(reason, "reason"), new TreeSet<>());
    }

    public boolean isValid() {
        return reason == null;
    }

    /** 이 종목에 신규 진입해도 되는지. */
    public boolean allowsEntry(Symbol symbol) {
        return reason == null && symbols.contains(symbol);
    }

    /** {@code run_selection_day.validity} 값: {@code VALID} 또는 사유 이름. */
    public String validity() {
        return reason == null ? "VALID" : reason.name();
    }
}
