package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.symbols.SymbolNames;
import com.autotrade.sim.run.OrderRejection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 거절 기록 한 쪽(실험 계좌 상세와 실시간 모의 계좌 상세 공용, D-120): 거르면 행만 줄고 사유별 건수는 전체 그대로. */
class RejectionPageTest {

    @Test
    void filterNarrowsRowsButCountsStayWholeAndOrderIsKept() {
        LocalDate d = LocalDate.of(2026, 10, 20);
        Symbol samsung = Symbol.krx("005930");
        Symbol hynix = Symbol.krx("000660");
        List<OrderRejection> all = new ArrayList<>();
        all.add(new OrderRejection(10, IdempotencyKey.of(d, samsung, Side.BUY, 1), samsung, "EXCHANGE", "INSUFFICIENT_CASH", ""));
        for (int i = 0; i < 3; i++) {
            all.add(new OrderRejection(20 + i, IdempotencyKey.of(d, hynix, Side.BUY, i + 1), hynix, "RISK", "BLACKLIST", "제외 종목"));
        }
        // 실시간 모의 API는 최근 것부터 주려고 뒤집은 목록을 넘긴다
        ExperimentViews.RejectionPage page = ExperimentViews.rejectionPage(all.reversed(), "RISK", "BLACKLIST", 0, 2,
                SymbolNames.empty());

        assertThat(page.page().total()).isEqualTo(3);
        assertThat(page.page().items()).extracting(ExperimentViews.RejectionRow::eventSeq).containsExactly(22L, 21L);
        assertThat(page.counts()).containsExactly(new ExperimentViews.RejectionCount("RISK", "BLACKLIST", 3),
                new ExperimentViews.RejectionCount("EXCHANGE", "INSUFFICIENT_CASH", 1));
    }
}
