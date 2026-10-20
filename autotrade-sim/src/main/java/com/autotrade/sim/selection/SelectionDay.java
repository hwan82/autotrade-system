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
 * <p>장중 순위 선정({@code INTRADAY_AT}, D-127)은 그날 중간부터 적용한다. 순위를 매긴 날은 결과가 있는 판정에
 * {@code activeFrom}(순위를 매긴 이벤트의 순번)을 담아, 그 앞 이벤트의 신규 진입은 막는다. 아직 순위를 매기지 않은 날(그날 이벤트가
 * {@code at}에 이르지 않음)은 {@link #awaitingIntraday}로, 사유 {@code NOT_YET}에 표시 {@code awaiting}을 단다. 계좌는 이 표시가
 * 있는 동안 이벤트마다 판정을 다시 묻는다(실시간 모의는 그 사이 순위가 정해지고, 리플레이는 같은 값을 다시 받는다).
 *
 * @param resultHash 쓴 결과의 내용 해시. 사유가 있으면 null
 * @param reason 결과가 없는 사유. 결과가 있으면 null
 * @param symbols 신규 진입을 허용하는 종목. 사유가 있으면 빈 집합
 * @param activeFrom 이 순번 이상의 이벤트부터 신규 진입을 허용한다. 장전·장 마감 뒤 선정은 0(그날 처음부터)
 * @param awaiting 장중 순위 선정의 순위 시각 전(사유 {@code NOT_YET})
 */
public record SelectionDay(LocalDate date, String resultHash, SelectionInvalidReason reason, SortedSet<Symbol> symbols,
        long activeFrom, boolean awaiting) {

    public SelectionDay {
        Objects.requireNonNull(date, "date");
        if ((resultHash == null) == (reason == null)) {
            throw new IllegalArgumentException("결과 해시와 사유 중 정확히 하나가 있어야 합니다: " + date);
        }
        symbols = Collections.unmodifiableSortedSet(new TreeSet<>(symbols));
        if (reason != null && !symbols.isEmpty()) {
            throw new IllegalArgumentException("사유가 있는 날은 선정 종목이 없어야 합니다: " + date);
        }
        if (activeFrom < 0 || (reason != null && activeFrom != 0)) {
            throw new IllegalArgumentException("적용 시작 순번은 결과가 있는 날만, 0 이상이어야 합니다: " + date);
        }
        if (awaiting && reason != SelectionInvalidReason.NOT_YET) {
            throw new IllegalArgumentException("순위 시각 전 표시는 사유 NOT_YET에만 붙습니다: " + date);
        }
    }

    /** 장전·장 마감 뒤 선정의 판정(그날 처음부터 적용). */
    public SelectionDay(LocalDate date, String resultHash, SelectionInvalidReason reason, SortedSet<Symbol> symbols) {
        this(date, resultHash, reason, symbols, 0, false);
    }

    static SelectionDay valid(LocalDate date, String resultHash, SortedSet<Symbol> symbols) {
        return new SelectionDay(date, Objects.requireNonNull(resultHash, "resultHash"), null, symbols);
    }

    static SelectionDay invalid(LocalDate date, SelectionInvalidReason reason) {
        return new SelectionDay(date, null, Objects.requireNonNull(reason, "reason"), new TreeSet<>());
    }

    /** 장중 순위를 매긴 날: 순번 {@code activeFrom} 이벤트부터 {@code symbols}에 신규 진입을 허용한다. */
    public static SelectionDay intraday(LocalDate date, String resultHash, SortedSet<Symbol> symbols, long activeFrom) {
        return new SelectionDay(date, Objects.requireNonNull(resultHash, "resultHash"), null, symbols, activeFrom, false);
    }

    /** 장중 순위 시각 전(또는 그날 이벤트가 순위 시각에 이르지 않고 끝남). 신규 진입 0건. */
    public static SelectionDay awaitingIntraday(LocalDate date) {
        return new SelectionDay(date, null, SelectionInvalidReason.NOT_YET, new TreeSet<>(), 0, true);
    }

    public boolean isValid() {
        return reason == null;
    }

    /** 이 종목에 신규 진입해도 되는지(그날 처음부터 적용하는 판정). */
    public boolean allowsEntry(Symbol symbol) {
        return allowsEntry(symbol, Long.MAX_VALUE);
    }

    /** 순번 {@code sequence} 이벤트가 낸 신호로 이 종목에 신규 진입해도 되는지. */
    public boolean allowsEntry(Symbol symbol, long sequence) {
        return reason == null && sequence >= activeFrom && symbols.contains(symbol);
    }

    /** {@code run_selection_day.validity} 값: {@code VALID} 또는 사유 이름. */
    public String validity() {
        return reason == null ? "VALID" : reason.name();
    }
}
