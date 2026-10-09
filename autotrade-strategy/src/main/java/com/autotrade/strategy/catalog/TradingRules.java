package com.autotrade.strategy.catalog;

import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

/**
 * 진입 로직과 무관한 공통 매매 규칙: 진입 유효 시간·만료·확인 신호(STR-07), 비중(STR-12), 거래 건수·손실 제한(STR-12),
 * 청산(STR-09). 값은 모두 프로필 파라미터이고, 비율은 소수(0.015 = 1.5%)로 바꿔 둔다.
 *
 * <p>모의 실험용 한도다. 실전 계좌 한도는 이 값과 별개로 risk-ops 제안 후 사용자가 정한다(Q-008).
 *
 * @param entryStart 신규 진입 허용 시작(거래소 현지 시각, 포함)
 * @param entryEnd 신규 진입 허용 끝(제외)
 * @param signalExpiry 주 신호가 확인 신호를 기다리는 최대 시간. 지나면 신호를 버린다(STR-07 만료)
 * @param confirmVolumeRatio 확인 신호: 최근 {@code confirmWindowMinutes}분 거래량이 오늘 앞선 같은 길이 평균의 몇 배 이상.
 *     0이면 확인 신호 없음(최소 동시 신호 1개)
 * @param riskPerTrade 거래당 위험 금액 / 평가금액. 수량 = 위험 금액 / (가격 × 손절폭)
 * @param maxPositionWeight 종목당 투입 상한 / 평가금액
 * @param cashBuffer 남겨 둘 현금 / 평가금액
 * @param maxEntriesPerDay 하루 신규 진입 주문 수 상한
 * @param reentryCooldown 같은 종목 청산 후 재진입 금지 시간
 * @param dailyLossLimit 당일 시작 평가금액 대비 손실이 이만큼 이상이면 그날 신규 진입 중단(청산은 계속)
 * @param maxConsecutiveStops 연속 손절이 이 횟수에 이르면 그날 신규 진입 중단
 * @param stopLoss 평균 단가 대비 손절폭. 수량 계산에도 쓴다
 * @param takeProfit 익절폭. 0이면 없음
 * @param trailing 보유 중 최고가 대비 추적 손절폭. 0이면 없음
 * @param timeExit 진입 후 이 시간이 지나면 청산. 0이면 없음
 */
public record TradingRules(
        LocalTime entryStart,
        LocalTime entryEnd,
        Duration signalExpiry,
        BigDecimal confirmVolumeRatio,
        int confirmWindowMinutes,
        BigDecimal minPrice,
        BigDecimal riskPerTrade,
        BigDecimal maxPositionWeight,
        int maxPositions,
        BigDecimal cashBuffer,
        int maxEntriesPerDay,
        Duration reentryCooldown,
        BigDecimal dailyLossLimit,
        int maxConsecutiveStops,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal trailing,
        Duration timeExit,
        EodPolicy eod,
        LocalTime eodTime) {

    /** 프로필에서 읽는다. 범위를 벗어나거나 장중 프로필에 장 마감 정책이 맞지 않으면 예외. */
    public static TradingRules from(ProfileParams p) {
        LocalTime start = p.time("entry.window.start");
        LocalTime end = p.time("entry.window.end");
        if (!start.isBefore(end)) {
            throw p.fail("entry.window", "시작이 끝보다 앞서야 합니다");
        }
        BigDecimal confirm = p.decimal("entry.confirm.volume-ratio", "0", "20", "0");
        int confirmWindow = confirm.signum() > 0 ? p.count("entry.confirm.window-minutes", 1, 60) : 0;
        EodPolicy eod = p.choice("exit.eod", EodPolicy.class);
        TradingStyle style = p.profile().style();
        if (style == TradingStyle.INTRADAY && eod != EodPolicy.FLATTEN) {
            throw p.fail("exit.eod", "장중 프로필은 FLATTEN이어야 합니다(STR-09)");
        }
        LocalTime eodTime = eod == EodPolicy.FLATTEN ? p.time("exit.eod-time") : LocalTime.MAX;
        if (end.isAfter(eodTime)) {
            throw p.fail("exit.eod-time", "진입 허용 끝보다 앞설 수 없습니다");
        }
        return new TradingRules(
                start,
                end,
                Duration.ofSeconds(p.count("entry.expiry-seconds", 1, 86_400)),
                confirm,
                confirmWindow,
                p.decimal("universe.min-price", "0", "100000000", "0"),
                p.percent("sizing.risk-per-trade-pct", "0.01", "5"),
                p.percent("sizing.max-position-pct", "1", "100"),
                p.count("sizing.max-positions", 1, 50),
                p.percent("sizing.cash-buffer-pct", "0", "50"),
                p.count("limits.max-entries-per-day", 1, 1000),
                Duration.ofMinutes(p.count("limits.reentry-cooldown-minutes", 0, 100_000)),
                p.percent("limits.daily-loss-limit-pct", "0.1", "50"),
                p.count("limits.max-consecutive-stops", 1, 100),
                p.percent("exit.stop-loss-pct", "0.1", "50"),
                p.percent("exit.take-profit-pct", "0", "100", "0"),
                p.percent("exit.trailing-pct", "0", "50", "0"),
                Duration.ofMinutes(p.count("exit.time-exit-minutes", 0, 100_000, 0)),
                eod,
                eodTime);
    }

    /** 확인 신호까지 합친 최소 동시 신호 수(STR-07). */
    public int minSignals() {
        return confirmVolumeRatio.signum() > 0 ? 2 : 1;
    }
}
