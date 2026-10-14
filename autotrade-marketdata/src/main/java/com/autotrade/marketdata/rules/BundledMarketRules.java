package com.autotrade.marketdata.rules;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;

/**
 * 이 모듈에 든 캘린더({@link ExchangeCalendar#bundled})로 만든 시장 규칙. 시장 enum에서 규칙 구현을 고르는 분기는 여기와
 * 캘린더 파일 선택에만 둔다(OVS-02, 1단계 QA 권고 R2).
 */
public final class BundledMarketRules {

    private BundledMarketRules() {}

    /**
     * 종목 마스터 없이 쓰는 곳(일봉 품질 검사)용이다. KRX 종목은 모두 주식({@link InstrumentKind#STOCK})으로 보므로
     * ETF·ETN 호가 단위가 필요한 곳은 종목 마스터를 넘겨 {@link KrxMarketRules}를 직접 만든다.
     */
    public static MarketRules of(Market market) {
        ExchangeCalendar calendar = ExchangeCalendar.bundled(market);
        return switch (market) {
            case KRX -> new KrxMarketRules(calendar, s -> InstrumentKind.STOCK);
            case US -> new UsMarketRules(calendar);
        };
    }
}
