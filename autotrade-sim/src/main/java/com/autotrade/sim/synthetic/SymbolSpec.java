package com.autotrade.sim.synthetic;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 합성 종목 하나의 성격.
 *
 * @param code 종목 코드. 실제 종목과 겹치지 않게 9로 시작하는 6자리를 쓴다
 * @param startPrice 첫날 시가(호가 단위에 맞아야 한다)
 * @param baseDepth 호가 한 단계의 평균 잔량(주)
 * @param baseTradeQty 체결 한 건의 기본 수량(주)
 * @param dailyVolBp 하루 가격 변동성(표준편차, bp). 날마다 70~130% 범위로 흔든다
 */
public record SymbolSpec(String code, BigDecimal startPrice, long baseDepth, long baseTradeQty, int dailyVolBp) {

    public SymbolSpec {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(startPrice, "startPrice");
        if (startPrice.signum() <= 0 || baseDepth <= 0 || baseTradeQty <= 0 || dailyVolBp <= 0) {
            throw new IllegalArgumentException("합성 종목 값은 0보다 커야 합니다: " + code);
        }
    }
}
