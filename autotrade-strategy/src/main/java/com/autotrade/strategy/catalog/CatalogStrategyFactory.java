package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.profile.ProfileParams;
import java.util.List;
import java.util.Objects;

/**
 * 전략 카탈로그(STR-06): 프로필의 {@code logic} 값으로 진입 로직을 고르고 공통 규칙({@link TradingRules})을 붙인다.
 *
 * <p>프로필 해석이 실패하면(필수 키 없음, 범위 밖, 안 쓰는 키) {@link IllegalArgumentException}을 던지고 실행기는 그 Run만
 * FAILED로 끝낸다. 실험 전에 {@link #validate}로 미리 확인할 수 있다. 상태가 없어 스레드 안전하다.
 */
public final class CatalogStrategyFactory implements StrategyFactory {

    /** 지원하는 logic 값. */
    public static final List<String> LOGICS = List.of(
            "buy-and-hold", "orb", "vwap-reclaim", "prior-day-high", "volume-surge", "book-imbalance", "pullback",
            "ma-trend", "breakout-days", "short-reversal", "near-high", "random", "random-daily");

    private final MarketRules market;

    /** @param market 프로필 시장의 규칙(시간대, 수량 단위). 프로필 시장과 다르면 생성 시 거절한다 */
    public CatalogStrategyFactory(MarketRules market) {
        this.market = Objects.requireNonNull(market, "market");
    }

    @Override
    public Strategy create(StrategyProfile profile, long runSeed) {
        if (profile.market() != market.market()) {
            throw new IllegalArgumentException("프로필 시장과 시장 규칙이 다릅니다: " + profile.market() + " ≠ " + market.market());
        }
        ProfileParams p = new ProfileParams(profile);
        String hypothesis = p.text("hypothesis");
        if (hypothesis.isBlank()) {
            throw p.fail("hypothesis", "가설 문장이 필요합니다(STR-02)");
        }
        String logic = p.text("logic");
        Strategy strategy = switch (logic) {
            case "buy-and-hold" -> {
                requireStyle(p, TradingStyle.SWING);
                yield new BuyAndHold(market, p);
            }
            case "orb" -> new OpeningRangeBreakout(intraday(p), market, p);
            case "vwap-reclaim" -> new VwapReclaim(intraday(p), market, p);
            case "prior-day-high" -> new PriorDayHighBreakout(intraday(p), market, p);
            case "volume-surge" -> new VolumeSurge(intraday(p), market, p);
            case "book-imbalance" -> new BookImbalance(intraday(p), market, p);
            case "pullback" -> new Pullback(intraday(p), market, p);
            case "random" -> new RandomEntry(intraday(p), market, p, runSeed);
            case "ma-trend" -> new MovingAverageTrend(daily(p), market, p);
            case "breakout-days" -> new DonchianBreakout(daily(p), market, p);
            case "short-reversal" -> new ShortReversal(styled(p, TradingStyle.SWING), market, p);
            case "near-high" -> new NearHigh(styled(p, TradingStyle.POSITION), market, p);
            case "random-daily" -> new RandomDailyEntry(daily(p), market, p, runSeed);
            default -> throw p.fail("logic", "모르는 로직입니다. 지원: " + LOGICS);
        };
        p.requireAllUsed();
        return strategy;
    }

    /** 실험 전 프로필 검증. 문제가 있으면 예외. */
    public void validate(StrategyProfile profile) {
        create(profile, 0L);
    }

    private static TradingRules intraday(ProfileParams p) {
        requireStyle(p, TradingStyle.INTRADAY);
        return TradingRules.from(p);
    }

    /** 일봉 판단 로직(스윙·중기, STR-18). */
    private static TradingRules daily(ProfileParams p) {
        TradingStyle style = p.profile().style();
        if (style != TradingStyle.SWING && style != TradingStyle.POSITION) {
            throw p.fail("style", "이 로직은 SWING 또는 POSITION 프로필이어야 합니다(STR-02 스타일 혼합 금지)");
        }
        return TradingRules.from(p);
    }

    private static TradingRules styled(ProfileParams p, TradingStyle style) {
        requireStyle(p, style);
        return TradingRules.from(p);
    }

    private static void requireStyle(ProfileParams p, TradingStyle style) {
        if (p.profile().style() != style) {
            throw p.fail("style", "이 로직은 " + style + " 프로필이어야 합니다(STR-02 스타일 혼합 금지)");
        }
    }
}
