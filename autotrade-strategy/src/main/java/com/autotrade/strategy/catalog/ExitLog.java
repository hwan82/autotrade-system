package com.autotrade.strategy.catalog;

import com.autotrade.domain.market.Symbol;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 청산 사유 기록(PR #98 strategy-researcher 권고 R2): 종목별로 거래일마다 낸 마지막 매도 신호의 사유를 남긴다. 왕복 하나의
 * 청산 사유는 그 왕복의 진입일부터 청산일까지 낸 마지막 매도 신호의 사유다(거절·취소 뒤 다시 낸 신호는 마지막 것이 이긴다).
 * 판정에는 쓰지 않고 결과 파일에 C10 해석 근거로만 낸다. 한 전략 인스턴스 안에서만 쓰고(스레드 하나), 읽기는 Run이 끝난
 * 뒤에 한다.
 */
public final class ExitLog {

    private final Map<Symbol, TreeMap<LocalDate, ExitReason>> bySymbol = new HashMap<>();

    void sent(Symbol symbol, LocalDate date, ExitReason reason) {
        bySymbol.computeIfAbsent(symbol, s -> new TreeMap<>()).put(date, reason);
    }

    /**
     * {@code [entryDate, exitDate]} 안에서 이 종목에 낸 마지막 매도 신호의 사유. 전략이 매도 신호를 내지 않은 왕복(구간 끝
     * 정리 등)이면 빈 값.
     */
    public Optional<ExitReason> reasonFor(Symbol symbol, LocalDate entryDate, LocalDate exitDate) {
        TreeMap<LocalDate, ExitReason> days = bySymbol.get(symbol);
        if (days == null) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, ExitReason> e = days.floorEntry(exitDate);
        return e == null || e.getKey().isBefore(entryDate) ? Optional.empty() : Optional.of(e.getValue());
    }
}
