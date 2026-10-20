package com.autotrade.sim.exchange;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 중간가 체결(최우선 매수·매도 호가의 정확한 중간 가격 체결) 판정 한 곳(D-118).
 *
 * <p>호가 단위에 맞지 않지만 바로 아래 호가 단위 가격에서 그 단위의 정확히 반 칸 위인 체결가를 중간가 체결로 본다.
 * 한국거래소 복수시장 도입 뒤 KRX 단독 체결 틱에도 이런 가격이 온다 {@code [추론]}(증권사 복수시장 안내가 중간가 호가를
 * 설명한다는 것만 확인, 거래소 원문 대조 남음). 입력 검사({@code RecordingExperiment.offStockTick})와 가상 거래소가 같은
 * 판정을 쓰도록 여기에 둔다. 호가 가격에는 쓰지 않는다: 호가는 계속 호가 단위에 맞아야 한다.
 */
public final class MidpointTrades {

    private MidpointTrades() {
    }

    /** 호가 단위에 맞으면 false(일반 체결), 반 칸이면 true, 그 밖의 단위 밖 가격도 false. */
    public static boolean isMidpoint(MarketRules rules, Symbol symbol, BigDecimal price) {
        if (price.signum() <= 0) {
            return false;
        }
        BigDecimal lower = rules.roundToTick(symbol, price, RoundingMode.DOWN);
        if (lower.compareTo(price) == 0 || lower.signum() <= 0) {
            return false;
        }
        BigDecimal half = rules.tickSize(symbol, lower).divide(BigDecimal.valueOf(2));
        return lower.add(half).compareTo(price) == 0;
    }
}
