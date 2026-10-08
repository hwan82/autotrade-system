package com.autotrade.sim.run;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.Side;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Run 하나의 멱등 키 발급기(메모리). 날짜·종목·방향마다 seq를 1부터 올린다. 가상 계좌는 재시작 복구가 없으므로
 * 영속하지 않는다. 실전의 계좌별 영속 발급기는 주문 경로 작업(risk-ops)이 만든다(IdempotencyKey 주석).
 */
final class SimOrderKeys {

    private final Map<String, Integer> seqs = new HashMap<>();

    IdempotencyKey next(LocalDate date, Symbol symbol, Side side) {
        int seq = seqs.merge(date + "|" + symbol.code() + "|" + side, 1, Integer::sum);
        return IdempotencyKey.of(date, symbol, side, seq);
    }
}
