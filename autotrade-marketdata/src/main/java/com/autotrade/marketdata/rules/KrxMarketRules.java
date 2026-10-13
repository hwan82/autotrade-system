package com.autotrade.marketdata.rules;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.SessionPhase;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.calendar.TradingSession;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Function;

/**
 * 한국거래소 규칙(ADR-003 2.5). 거래 시간은 {@link ExchangeCalendar}, 호가 단위는 아래 표.
 *
 * <p>주식 호가가격단위(2023-01-25 시행, 코스피·코스닥 통일). `[사실]` 2,000원 미만 1원, 1만~2만원대 10원,
 * 10만~20만원대 100원, 20만~50만원 500원, 50만원 이상 1,000원은 보도 자료로 확인
 * (머니투데이방송 2022-11-01 https://news.mtn.co.kr/news-detail/2022110114103274390,
 * 비즈워치 2022-11-01 https://news.bizwatch.co.kr/article/market/2022/11/01/0005).
 * `[추론]` 2,000~5,000원 5원, 5만원 미만 50원 구간 경계는 같은 개편의 구간표에서 옮긴 값이며 한국거래소 시행세칙 원문 대조가 남았다.
 * ETF·ETN은 5원 유지 `[사실]`(머니투데이방송 같은 기사).
 *
 * <p>매매 단계({@link #sessionPhase}, D-069): 정규장 09:00~15:30 안에서 시가 단일가 08:30~09:00(개장 전 30분), 종가 단일가
 * 15:20~15:30(폐장 전 10분), 그 사이 접속 매매 `[사실]`(한국거래소 유가증권시장 업무규정 제4조 매매거래시간·제23조 단일가
 * 매매, 거래소 홈페이지 "매매거래시간" 안내). 개장·폐장이 늦춰지는 특별 운영일(수능일 등)은 캘린더의 그날 개장·폐장 시각에서
 * 같은 길이(30분, 10분)를 잡는다 `[가정]`: 공지마다 단일가 시간을 따로 확인하지 않았다. 장전·장후 시간외 매매와 시간외
 * 단일가는 {@link SessionPhase#CLOSED}다(정규장 주문이 체결되지 않는 시간).
 */
public final class KrxMarketRules implements MarketRules {

    private static final BigDecimal[][] STOCK_TICKS = {
        // {구간 상한(미만), 호가 단위}
        {new BigDecimal("2000"), new BigDecimal("1")},
        {new BigDecimal("5000"), new BigDecimal("5")},
        {new BigDecimal("20000"), new BigDecimal("10")},
        {new BigDecimal("50000"), new BigDecimal("50")},
        {new BigDecimal("200000"), new BigDecimal("100")},
        {new BigDecimal("500000"), new BigDecimal("500")},
    };
    private static final BigDecimal STOCK_TOP_TICK = new BigDecimal("1000");
    private static final BigDecimal FUND_TICK = new BigDecimal("5");
    /** 시가 단일가 접수 시간(개장 전). */
    static final Duration OPENING_AUCTION = Duration.ofMinutes(30);
    /** 종가 단일가 접수 시간(폐장 전). */
    static final Duration CLOSING_AUCTION = Duration.ofMinutes(10);

    private final ExchangeCalendar calendar;
    private final Function<Symbol, InstrumentKind> kinds;

    /** @param kinds 종목 → 상품 유형(종목 마스터). 모르면 STOCK을 돌려준다 */
    public KrxMarketRules(ExchangeCalendar calendar, Function<Symbol, InstrumentKind> kinds) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        if (calendar.market() != Market.KRX) {
            throw new IllegalArgumentException("KRX 캘린더가 아닙니다: " + calendar.market());
        }
        this.kinds = Objects.requireNonNull(kinds, "kinds");
    }

    @Override
    public Market market() {
        return Market.KRX;
    }

    @Override
    public ZoneId zone() {
        return calendar.zone();
    }

    @Override
    public boolean isRegularSession(Instant instant) {
        return calendar.isRegularSession(instant);
    }

    @Override
    public SessionPhase sessionPhase(Instant instant) {
        TradingSession session = calendar.session(tradingDate(instant)).orElse(null);
        if (session == null) {
            return SessionPhase.CLOSED;
        }
        Instant open = session.openInstant();
        Instant close = session.closeInstant();
        if (instant.isBefore(open)) {
            return instant.isBefore(open.minus(OPENING_AUCTION)) ? SessionPhase.CLOSED : SessionPhase.OPENING_AUCTION;
        }
        if (!instant.isBefore(close)) {
            return SessionPhase.CLOSED;
        }
        return instant.isBefore(close.minus(CLOSING_AUCTION)) ? SessionPhase.CONTINUOUS : SessionPhase.CLOSING_AUCTION;
    }

    /**
     * 시가 단일가 체결로 열린다. 접수 마감은 개장 시각에서 최대 30초 안에서 임의로 늦춰진다(랜덤 엔드) `[가정]`: 2026-10-12
     * 실녹화 첫 체결이 09:00:03~09:00:24에 흩어진 것과 맞지만(experiments/stage-2-replay/2026-10-12.md) 업무규정 시행세칙 원문은
     * 대조하지 않았다. 그래서 개장 뒤 시각 기준으로 끊지 않고 종목별 첫 체결을 시가 단일가 체결로 본다.
     */
    @Override
    public boolean opensWithAuction() {
        return true;
    }

    @Override
    public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
        if (symbol.market() != Market.KRX) {
            throw new IllegalArgumentException("KRX 종목이 아닙니다: " + symbol);
        }
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다: " + price);
        }
        InstrumentKind kind = kinds.apply(symbol);
        if (kind == InstrumentKind.ETF || kind == InstrumentKind.ETN) {
            return FUND_TICK;
        }
        for (BigDecimal[] row : STOCK_TICKS) {
            if (price.compareTo(row[0]) < 0) {
                return row[1];
            }
        }
        return STOCK_TOP_TICK;
    }

    public ExchangeCalendar calendar() {
        return calendar;
    }
}
