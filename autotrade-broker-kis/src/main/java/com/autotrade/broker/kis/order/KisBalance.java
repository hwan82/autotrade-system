package com.autotrade.broker.kis.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 주식잔고조회({@code TTTC8434R}) 결과. 금액은 원.
 *
 * <p>`[사실]` 필드: open-trading-api cd63cae examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py
 * (output1 {@code pdno, hldg_qty, ord_psbl_qty, pchs_avg_pric}, output2 {@code dnca_tot_amt, prvs_rcdl_excc_amt,
 * tot_evlu_amt}).
 *
 * @param depositTotal 예수금총금액({@code dnca_tot_amt})
 * @param settledDeposit 가수도정산금액({@code prvs_rcdl_excc_amt}, D+2 예수금으로 쓰인다 `[가정]`)
 * @param totalEvaluation 총평가금액({@code tot_evlu_amt})
 * @param complete 모든 쪽을 받았고 읽지 못한 행이 없다. false면 보유가 없다고 판단하는 데 쓰지 않는다
 */
public record KisBalance(
        List<Holding> holdings,
        BigDecimal depositTotal,
        BigDecimal settledDeposit,
        BigDecimal totalEvaluation,
        boolean complete,
        int unreadableRows) {

    public KisBalance {
        holdings = List.copyOf(holdings);
        Objects.requireNonNull(depositTotal, "depositTotal");
        Objects.requireNonNull(settledDeposit, "settledDeposit");
        Objects.requireNonNull(totalEvaluation, "totalEvaluation");
    }

    /** 보유 종목 하나. 보유 0 행은 넣지 않는다. */
    public record Holding(String symbolCode, long quantity, long orderableQuantity, BigDecimal averagePrice) {
        public Holding {
            Objects.requireNonNull(symbolCode, "symbolCode");
            Objects.requireNonNull(averagePrice, "averagePrice");
            if (quantity <= 0 || orderableQuantity < 0) {
                throw new IllegalArgumentException("보유 수량이 범위를 벗어났습니다");
            }
        }
    }
}
