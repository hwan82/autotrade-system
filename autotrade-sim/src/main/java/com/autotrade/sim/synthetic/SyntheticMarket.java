package com.autotrade.sim.synthetic;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.sim.run.InMemoryMarketDataSet;
import com.autotrade.sim.run.MarketDataSet;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * 합성 KRX 시세 생성기. 녹화 데이터(PR #10)가 쌓이기 전에 전략 실험 파이프라인(프로필 → 실행기 → 지표 → 선발 기준)을
 * 끝까지 돌려 보기 위한 것이다. <b>합성 데이터 결과는 매매 기법의 근거가 아니다.</b> 생성기가 심은 성질만 다시 찾을 뿐이다.
 *
 * <p>모형(버전 {@link #MODEL_VERSION}, 바꾸면 올린다)
 * <ul>
 *   <li>가격은 호가 단위로만 움직인다. 단계마다 0.5~5.5초(평균 3초) 간격, 정규장 09:00~15:20(장 마감 단일가 구간 제외).
 *       한 단계에서 움직일 확률은 그날 변동성으로 정하고, 움직이면 위·아래 한 호가.</li>
 *   <li>날마다 종목별 국면을 뽑는다. 추세일(기본 35%)은 하루 내내 한 방향 치우침(위 확률 +4~8%p)과 직전 움직임 방향으로
 *       +6%p 짧은 모멘텀을 갖는다. 횡보일은 시가에서 멀어질수록 되돌아갈 확률이 커진다(한 호가당 1.5%p, 최대 25%p).
 *       그래서 시가 범위 돌파는 추세일에, VWAP 회복은 횡보일에 유리하도록 심어져 있다. 거래량 폭증(무작위 60단계 동안 4배)과
 *       호가 잔량은 가격과 무관한 잡음이다.</li>
 *   <li>호가: 매수 1호가 = 현재가, 매도 1호가 = 현재가 + 1호가(10%는 2호가). 양쪽 5단계, 잔량은 기본 잔량의 50~150%.</li>
 *   <li>체결: 가격이 움직인 단계는 그 방향 1호가에서, 아니면 60% 확률로 무작위 쪽 1호가에서 체결 한 건.
 *       수량은 기본 수량 × 1~4배 × (장 시작 30분 2배, 마지막 30분 1.5배, 추세일 1.5배, 폭증 4배).</li>
 *   <li>다음 날 시가는 전일 종가 ± 최대 0.8% 갭.</li>
 *   <li>수신 시각 = 거래소 시각 + 20ms. 같은 시각 이벤트는 종목 코드 순, 한 단계 안에서는 호가 → 체결 순으로 번호를 매긴다.</li>
 * </ul>
 * 난수는 {@code SHA-256("synthetic-v1|" + seed + "|" + 종목 코드)}로 종목마다 따로 만든다. 그래서 같은 시드·같은 명세면
 * 바이트 단위로 같은 이벤트가 나오고, 종목을 더해도 기존 종목의 시세는 그대로다.
 */
public final class SyntheticMarket {

    public static final String MODEL_VERSION = "SYNTH-KRX-V1";

    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final LocalTime LAST_STEP = LocalTime.of(15, 20);
    private static final int LEVELS = 5;
    private static final int MILLION = 1_000_000;
    private static final long MEAN_STEP_MILLIS = 3_000;
    private static final long RECEIVE_DELAY_MILLIS = 20;

    private final MarketRules rules;

    public SyntheticMarket(MarketRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
        if (rules.market() != Market.KRX) {
            throw new IllegalArgumentException("합성 생성기는 KRX만 지원합니다: " + rules.market());
        }
    }

    /**
     * @param trendPerMille 추세일 비율(천분율)
     * @return 데이터 스냅샷(버전 = 모형 버전 + 시드 + 시작일 + 일수 + 생성 명세 해시)과 심은 국면, 거래일 목록
     */
    public Result generate(long seed, LocalDate start, int tradingDays, List<SymbolSpec> symbols, int trendPerMille) {
        if (tradingDays < 1 || symbols.isEmpty() || trendPerMille < 0 || trendPerMille > 1000) {
            throw new IllegalArgumentException("합성 데이터 명세가 올바르지 않습니다");
        }
        List<LocalDate> days = weekdays(start, tradingDays);
        List<SymbolSpec> ordered = symbols.stream().sorted(Comparator.comparing(SymbolSpec::code)).toList();
        List<Walker> walkers = new ArrayList<>();
        for (SymbolSpec s : ordered) {
            walkers.add(new Walker(s, new SplittableRandom(seedFor(seed, s.code())), trendPerMille));
        }
        List<MarketEvent> events = new ArrayList<>();
        List<DayRegime> regimes = new ArrayList<>();
        long[] seq = {0};
        for (LocalDate day : days) {
            List<List<Step>> perSymbol = new ArrayList<>();
            for (Walker w : walkers) {
                regimes.add(new DayRegime(w.spec.code(), day, w.startDay(day)));
                perSymbol.add(w.walk(day));
            }
            merge(perSymbol, events, seq);
        }
        String version = MODEL_VERSION + "-S" + seed + "-" + start + "-" + tradingDays + "D-"
                + specHash(ordered, trendPerMille);
        return new Result(new InMemoryMarketDataSet(version, Market.KRX, events), List.copyOf(regimes), days);
    }

    /** 생성 결과. */
    public record Result(MarketDataSet data, List<DayRegime> regimes, List<LocalDate> tradingDays) {
    }

    private static List<LocalDate> weekdays(LocalDate start, int n) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = start; out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    /** 종목별 단계 목록을 시각 → 종목 순으로 합치며 sequence를 매긴다. */
    private static void merge(List<List<Step>> perSymbol, List<MarketEvent> out, long[] seq) {
        int[] idx = new int[perSymbol.size()];
        while (true) {
            int best = -1;
            for (int i = 0; i < perSymbol.size(); i++) {
                if (idx[i] < perSymbol.get(i).size()
                        && (best < 0 || perSymbol.get(i).get(idx[i]).time.isBefore(perSymbol.get(best).get(idx[best]).time))) {
                    best = i;
                }
            }
            if (best < 0) {
                return;
            }
            Step s = perSymbol.get(best).get(idx[best]++);
            Instant received = s.time.plusMillis(RECEIVE_DELAY_MILLIS);
            out.add(new OrderBookSnapshot(s.symbol, ++seq[0], s.time, received, s.asks, s.bids));
            if (s.tradePrice != null) {
                out.add(new TradeTick(s.symbol, ++seq[0], s.time, received, s.tradePrice, s.tradeQty));
            }
        }
    }

    /** 생성 명세(종목 명세 전체, 추세일 비율)의 SHA-256 앞 12자. 명세가 바뀌면 데이터 스냅샷 ID도 바뀐다. */
    static String specHash(List<SymbolSpec> ordered, int trendPerMille) {
        StringBuilder sb = new StringBuilder("trend=").append(trendPerMille).append('\n');
        for (SymbolSpec s : ordered) {
            sb.append(s.code()).append('|').append(s.startPrice().toPlainString()).append('|').append(s.baseDepth())
                    .append('|').append(s.baseTradeQty()).append('|').append(s.dailyVolBp()).append('\n');
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    private static long seedFor(long seed, String code) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(("synthetic-v1|" + seed + "|" + code).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(h, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    /** 생성 중간 결과: 한 단계의 호가와 (있으면) 체결. */
    private record Step(Symbol symbol, Instant time, List<PriceLevel> asks, List<PriceLevel> bids,
            BigDecimal tradePrice, long tradeQty) {
    }

    /** 종목 하나의 가격 경로. 날을 넘어 상태(전일 종가)를 이어 간다. */
    private final class Walker {
        private final SymbolSpec spec;
        private final Symbol symbol;
        private final SplittableRandom rnd;
        private final int trendPerMille;
        /** (가격, 잔량) → 호가 단계. 같은 값을 공유해 메모리를 줄인다(결과에는 영향 없음). */
        private final Map<String, PriceLevel> levelCache = new HashMap<>();
        private BigDecimal price;
        private DayRegime.Regime regime;
        private int trendBiasPpm;
        private int dayVolBp;
        private boolean firstDay = true;

        Walker(SymbolSpec spec, SplittableRandom rnd, int trendPerMille) {
            this.spec = spec;
            this.symbol = Symbol.krx(spec.code());
            this.rnd = rnd;
            this.trendPerMille = trendPerMille;
            this.price = spec.startPrice();
        }

        DayRegime.Regime startDay(LocalDate day) {
            if (!firstDay) {
                BigDecimal tick = rules.tickSize(symbol, price);
                long maxGap = Math.max(1, price.multiply(new BigDecimal("0.008")).divide(tick, 0, RoundingMode.DOWN)
                        .longValueExact());
                long gap = rnd.nextLong(-maxGap, maxGap + 1);
                BigDecimal gapped = clampPositive(price.add(tick.multiply(BigDecimal.valueOf(gap))));
                price = rules.roundToTick(symbol, gapped, gap >= 0 ? RoundingMode.DOWN : RoundingMode.UP);
            }
            firstDay = false;
            if (rnd.nextInt(1000) < trendPerMille) {
                boolean up = rnd.nextBoolean();
                regime = up ? DayRegime.Regime.TREND_UP : DayRegime.Regime.TREND_DOWN;
                trendBiasPpm = (up ? 1 : -1) * (40_000 + rnd.nextInt(40_001));
            } else {
                regime = DayRegime.Regime.RANGE;
                trendBiasPpm = 0;
            }
            dayVolBp = spec.dailyVolBp() * (70 + rnd.nextInt(61)) / 100;
            return regime;
        }

        List<Step> walk(LocalDate day) {
            ZoneId zone = rules.zone();
            Instant t = day.atTime(OPEN).atZone(zone).toInstant().plusMillis(rnd.nextInt(2_000));
            Instant end = day.atTime(LAST_STEP).atZone(zone).toInstant();
            Instant firstHalfHour = day.atTime(OPEN.plusMinutes(30)).atZone(zone).toInstant();
            Instant lastHalfHour = day.atTime(LAST_STEP.minusMinutes(30)).atZone(zone).toInstant();
            BigDecimal anchor = price;
            int moveProbPpm = moveProbability(price);
            int lastMove = 0;
            int burstLeft = 0;
            List<Step> steps = new ArrayList<>();
            while (t.isBefore(end)) {
                int move = 0;
                if (rnd.nextInt(MILLION) < moveProbPpm) {
                    int upPpm = MILLION / 2 + bias(anchor, lastMove);
                    move = rnd.nextInt(MILLION) < upPpm ? 1 : -1;
                    price = step(price, move);
                    lastMove = move;
                }
                if (burstLeft > 0) {
                    burstLeft--;
                } else if (rnd.nextInt(1000) < 2) {
                    burstLeft = 60;
                }
                boolean wideSpread = rnd.nextInt(10) == 0;
                BigDecimal bid1 = price;
                BigDecimal ask1 = step(price, 1);
                if (wideSpread) {
                    ask1 = step(ask1, 1);
                }
                List<PriceLevel> asks = levels(ask1, 1);
                List<PriceLevel> bids = levels(bid1, -1);
                BigDecimal tradePrice = null;
                long tradeQty = 0;
                if (move != 0 || rnd.nextInt(10) < 6) {
                    boolean atAsk = move != 0 ? move > 0 : rnd.nextBoolean();
                    tradePrice = atAsk ? ask1 : bid1;
                    long tenths = 10;
                    if (t.isBefore(firstHalfHour)) {
                        tenths = tenths * 2;
                    } else if (!t.isBefore(lastHalfHour)) {
                        tenths = tenths * 15 / 10;
                    }
                    if (regime != DayRegime.Regime.RANGE) {
                        tenths = tenths * 15 / 10;
                    }
                    if (burstLeft > 0) {
                        tenths = tenths * 4;
                    }
                    tradeQty = Math.max(1, spec.baseTradeQty() * (1 + rnd.nextInt(4)) * tenths / 10);
                }
                steps.add(new Step(symbol, t, asks, bids, tradePrice, tradeQty));
                t = t.plusMillis(500 + rnd.nextInt(5_001));
            }
            return steps;
        }

        /** 위로 움직일 확률의 치우침(ppm). */
        private int bias(BigDecimal anchor, int lastMove) {
            int b;
            if (regime == DayRegime.Regime.RANGE) {
                long ticksAway = price.subtract(anchor).divide(rules.tickSize(symbol, anchor), 0, RoundingMode.DOWN)
                        .longValue();
                b = (int) Math.max(-250_000, Math.min(250_000, -15_000 * ticksAway));
            } else {
                b = trendBiasPpm + 60_000 * lastMove;
            }
            return Math.max(-300_000, Math.min(300_000, b));
        }

        /**
         * 단계당 움직일 확률(ppm): (하루 변동 호가 수)² / 하루 단계 수. 하루 단계 수 = 장 시간 / 평균 간격.
         * 정수·BigDecimal로만 계산한다(결정론).
         */
        private int moveProbability(BigDecimal at) {
            BigDecimal tick = rules.tickSize(symbol, at);
            BigDecimal ticksPerDay = at.multiply(BigDecimal.valueOf(dayVolBp))
                    .divide(tick.multiply(BigDecimal.valueOf(10_000)), MathContext.DECIMAL64);
            long stepsPerDay = (LAST_STEP.toSecondOfDay() - OPEN.toSecondOfDay()) * 1_000L / MEAN_STEP_MILLIS;
            long ppm = ticksPerDay.pow(2).multiply(BigDecimal.valueOf(MILLION))
                    .divide(BigDecimal.valueOf(stepsPerDay), 0, RoundingMode.DOWN).longValue();
            return (int) Math.max(1_000, Math.min(500_000, ppm));
        }

        private BigDecimal step(BigDecimal p, int direction) {
            if (direction > 0) {
                return p.add(rules.tickSize(symbol, p));
            }
            // 아래 구간 경계를 넘을 수 있으므로 바로 아래 가격의 호가 단위로 뺀다(예: 50,000 → 49,950)
            BigDecimal below = p.subtract(rules.tickSize(symbol, p).min(p).divide(BigDecimal.TEN.pow(6)));
            BigDecimal down = p.subtract(rules.tickSize(symbol, clampPositive(below)));
            return clampPositive(rules.roundToTick(symbol, clampPositive(down), RoundingMode.DOWN));
        }

        private BigDecimal clampPositive(BigDecimal p) {
            return p.signum() > 0 ? p : rules.tickSize(symbol, BigDecimal.ONE);
        }

        /** 1호가부터 바깥쪽으로 5단계. direction 1 = 매도(위로), -1 = 매수(아래로). */
        private List<PriceLevel> levels(BigDecimal first, int direction) {
            List<PriceLevel> out = new ArrayList<>(LEVELS);
            BigDecimal p = first;
            for (int i = 0; i < LEVELS; i++) {
                long qty = spec.baseDepth() * (5 + rnd.nextInt(11)) / 10;
                String key = p.toPlainString() + "x" + qty;
                BigDecimal at = p;
                out.add(levelCache.computeIfAbsent(key, k -> new PriceLevel(at, qty)));
                BigDecimal next = step(p, direction);
                if (direction < 0 && next.compareTo(p) >= 0) {
                    break; // 가격 하한
                }
                p = next;
            }
            return List.copyOf(out);
        }
    }
}
