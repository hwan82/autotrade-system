package com.autotrade.selection.calc;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.financial.FinancialFiling;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.selection.result.SelectionResult;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 날짜 T의 선정 계산(STR-15·16, ADR-007 2.1 {@code selection.calc}). 순수 함수다: 같은 입력 스냅샷·선정 프로필·T면 같은
 * {@link SelectionResult}(같은 내용 해시)를 낸다. 실험실 리플레이와 생산 일괄 명령(후속)이 쓰고, 실전 엔진은 쓰지 않는다.
 *
 * <p>순서
 * <ol>
 *   <li>선정 기준일 T = {@code applyFrom}(거래일), 정보 시점 I = T({@code PRE_OPEN_DAILY_LAG1}은 T의 직전 거래일, D-137),
 *       기준 데이터일 c = I의 직전 거래일. 일봉·상장주식수는 c까지, 재무·공시는 I 전 접수분만 읽는다(MD-10, {@link FinancialsAt}).
 *       유니버스는 c에 상장돼 있던 종목(상장폐지 예정 포함, MD-07)</li>
 *   <li>하드 필터(STR-10): 상품 유형 → 최소 가격 → 최근 일봉 일수. 걸리면 그 종목은 자격 조건을 보지 않는다</li>
 *   <li>자격 조건(Gate, 모두 AND): 모든 조건을 계산해 값과 사유를 남긴다(사유는 조건 순번 순)</li>
 *   <li>통과 종목을 점수 순(없으면 종목 코드 순)으로 세워 {@code max-symbols}까지 선정, 나머지는 {@code RANK_CUTOFF}</li>
 * </ol>
 * 금액·비율은 {@link BigDecimal}과 {@link MathContext#DECIMAL64}로 계산하고 값은 {@code toPlainString()}으로 남긴다.
 */
public final class SelectionCalculator {

    /** 결측(필요한 일봉·주식수·재무가 없거나 정의되지 않음). */
    public static final String NO_DATA = "NO_DATA";
    /** 영업이익률 증가율의 두 해 중 하나라도 0 이하(적자). */
    public static final String DEFICIT = "DEFICIT";
    /** 자본총계 ≤ 0(자본잠식). */
    public static final String CAPITAL_IMPAIRED = "CAPITAL_IMPAIRED";
    public static final String FILTER_KIND = "FILTER_KIND";
    public static final String FILTER_MIN_PRICE = "FILTER_MIN_PRICE";
    public static final String FILTER_MIN_BAR_DAYS = "FILTER_MIN_BAR_DAYS";
    public static final String SCORE_NO_DATA = "SCORE_NO_DATA";
    public static final String RANK_CUTOFF = "RANK_CUTOFF";

    private static final MathContext MC = MathContext.DECIMAL64;
    /** 여러 날을 더하는 중간 계산. 결과 값만 {@link #MC}로 줄여 경계 비교가 반올림 오차에 흔들리지 않게 한다. */
    private static final MathContext WIDE = MathContext.DECIMAL128;

    private final SelectionData data;

    public SelectionCalculator(SelectionData data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    /**
     * 거래일 {@code tradingDate}에 적용할 결과의 선정 기준일. 매일 갱신은 그날, 주간 갱신은 그날이 든 ISO 주의 첫 거래일이다
     * (ADR-007 2.6 적용 범위 표. 주중에 시작하는 실험도 그 주 월요일분 결과를 쓴다).
     */
    public LocalDate applyFromFor(SelectionProfile profile, LocalDate tradingDate) {
        return profile.refresh() == SelectionRefresh.POST_CLOSE_WEEKLY
                ? data.tradingDays().firstOfIsoWeek(tradingDate) : tradingDate;
    }

    /** {@code applyFrom}부터의 적용 종료 거래일(포함). */
    public LocalDate applyToFor(SelectionProfile profile, LocalDate applyFrom) {
        return profile.refresh() == SelectionRefresh.POST_CLOSE_WEEKLY
                ? data.tradingDays().lastOfIsoWeek(applyFrom) : applyFrom;
    }

    /**
     * @param applyFrom 선정 기준일(거래일). 주간 갱신이면 그 ISO 주의 첫 거래일이어야 한다
     * @throws IllegalArgumentException 프로필이 카탈로그에 맞지 않거나 시장이 다르거나 기준일이 맞지 않을 때
     * @throws IllegalStateException 거래일 수록 범위 밖
     */
    public SelectionResult calculate(SelectionProfile profile, LocalDate applyFrom) {
        Objects.requireNonNull(profile, "profile");
        if (profile.market() != data.market()) {
            throw new IllegalArgumentException("선정 프로필과 데이터의 시장이 다릅니다: " + profile.market() + " ≠ " + data.market());
        }
        if (profile.refresh() == SelectionRefresh.INTRADAY_AT) {
            // 장중 순위 선정은 일봉 스냅샷이 아니라 그날 입력 기록으로 계산한다(SessionSelectionCalculator, D-127)
            throw new IllegalArgumentException("장중 순위 선정(INTRADAY_AT)은 일봉 선정 계산기로 계산하지 않습니다(녹화 실험·실시간 모의만): "
                    + profile.key());
        }
        TradingDays days = data.tradingDays();
        if (!days.isTradingDay(applyFrom)) {
            throw new IllegalArgumentException("선정 기준일이 거래일이 아닙니다: " + applyFrom);
        }
        if (!applyFromFor(profile, applyFrom).equals(applyFrom)) {
            throw new IllegalArgumentException("주간 갱신 선정의 기준일은 그 주 첫 거래일이어야 합니다: " + applyFrom);
        }
        SelectionCriteria criteria = SelectionCriteria.parse(profile);
        // 정보 시점: 하루 늦춘 장전 선정(LAG1)은 T의 직전 거래일에 적용할 PRE_OPEN_DAILY 결과와 같은 입력만 읽는다(D-137).
        // 아래 계산은 모두 이 날짜를 기준으로 하고, 결과의 적용 범위만 T다
        LocalDate asOf = profile.refresh().infoDate(applyFrom, days::previous);
        LocalDate cutoff = days.previous(asOf);
        List<LocalDate> window = days.lastN(cutoff, criteria.lookbackDays());
        Set<Metric.Kind> kinds = criteria.inputKinds();
        checkInputs(kinds, cutoff);
        checkDisclosures(criteria, asOf, window);
        SelectionData.TurnoverInput turnover = checkTurnover(kinds, cutoff);

        List<Scored> passed = new ArrayList<>();
        Map<String, SelectionResult.Evaluation> evaluations = new TreeMap<>();
        for (Symbol symbol : data.universe(cutoff)) {
            Eval e = evaluate(symbol, criteria, asOf, cutoff, window, turnover);
            if (e.reasons.isEmpty()) {
                passed.add(new Scored(symbol, e));
            } else {
                evaluations.put(symbol.code(), e.toEvaluation(symbol, false));
            }
        }
        Comparator<Scored> order = Comparator.comparing(s -> s.symbol.code());
        if (criteria.score().isPresent()) {
            Comparator<BigDecimal> byScore = criteria.score().get().descending()
                    ? Comparator.reverseOrder() : Comparator.naturalOrder();
            order = Comparator.comparing((Scored s) -> s.eval.score, byScore).thenComparing(order);
        }
        passed.sort(order);
        List<SelectionResult.Selected> selected = new ArrayList<>();
        for (Scored s : passed) {
            if (selected.size() < profile.maxSymbols()) {
                selected.add(new SelectionResult.Selected(s.symbol.code(), selected.size() + 1));
                evaluations.put(s.symbol.code(), s.eval.toEvaluation(s.symbol, true));
            } else {
                s.eval.reasons.add(RANK_CUTOFF);
                evaluations.put(s.symbol.code(), s.eval.toEvaluation(s.symbol, false));
            }
        }
        SelectionResult.Inputs inputs = new SelectionResult.Inputs(
                data.barsSnapshotId(), data.barsSha256(), data.barsLastTradingDate(),
                kinds.contains(Metric.Kind.SHARES) ? data.sharesSnapshotId().orElseThrow() : null,
                kinds.contains(Metric.Kind.SHARES) ? data.sharesAsOf().orElseThrow() : null,
                kinds.contains(Metric.Kind.FINANCIAL)
                        ? data.financials().map(SelectionData.FinancialSnapshotInfo::snapshotId).orElse(null) : null,
                kinds.contains(Metric.Kind.FINANCIAL)
                        ? data.financials().map(SelectionData.FinancialSnapshotInfo::sha256).orElse(null) : null,
                kinds.contains(Metric.Kind.FINANCIAL)
                        ? data.financials().map(SelectionData.FinancialSnapshotInfo::lastCollectedAt).orElse(null) : null,
                data.calendarDigest(),
                kinds.contains(Metric.Kind.DISCLOSURE) ? data.disclosures().orElseThrow().snapshotId() : null,
                kinds.contains(Metric.Kind.DISCLOSURE) ? data.disclosures().orElseThrow().sha256() : null,
                kinds.contains(Metric.Kind.DISCLOSURE) ? disclosuresThrough(criteria, asOf) : null,
                turnover == null ? null : turnover.sha256(), turnover == null ? null : turnover.lastDate());
        return new SelectionResult(SelectionResult.Selection.of(profile), applyFrom, applyToFor(profile, applyFrom),
                inputs, selected, List.copyOf(evaluations.values()));
    }

    /**
     * 조건이 쓰는 입력이 있고 기준 데이터일 {@code cutoff}까지 담겼는지 본다(ADR-007 2.6 {@code STALE_INPUT}의 생산 쪽).
     * 모자라면 결측 탈락으로 "0종목" 결과를 내지 않고 실패한다. 재무는 수집 시각의 현지 날짜가 {@code cutoff} 이상이어야 한다
     * (엔진 판정의 지연 상한보다 엄격한 쪽. 실험실 리플레이는 보통 나중에 수집한 스냅샷을 쓰므로 걸리지 않는다).
     */
    private void checkInputs(Set<Metric.Kind> kinds, LocalDate cutoff) {
        LocalDate bars = data.barsLastTradingDate();
        if (bars.isBefore(cutoff)) {
            throw new IllegalStateException("일봉 스냅샷 마지막 거래일 " + bars + "이 기준 데이터일 " + cutoff
                    + "보다 이릅니다: " + data.barsSnapshotId());
        }
        if (kinds.contains(Metric.Kind.SHARES)) {
            LocalDate asOf = data.sharesAsOf().filter(d -> data.sharesSnapshotId().isPresent())
                    .orElseThrow(() -> new IllegalStateException("시가총액 조건이 있는데 상장주식수 입력이 없습니다"));
            if (asOf.isBefore(cutoff)) {
                throw new IllegalStateException("상장주식수 기준일 " + asOf + "이 기준 데이터일 " + cutoff + "보다 이릅니다");
            }
        }
        if (kinds.contains(Metric.Kind.FINANCIAL)) {
            SelectionData.FinancialSnapshotInfo f = data.financials()
                    .orElseThrow(() -> new IllegalStateException("재무 조건이 있는데 재무 입력이 없습니다"));
            if (f.collectedDate() == null) {
                throw new IllegalStateException("재무 스냅샷의 수집 시각을 모릅니다: " + f.snapshotId());
            }
            if (f.collectedDate().isBefore(cutoff)) {
                throw new IllegalStateException("재무 스냅샷 수집일 " + f.collectedDate() + "이 기준 데이터일 " + cutoff
                        + "보다 이릅니다: " + f.snapshotId());
            }
        }
    }

    /**
     * 실제 거래대금 조건({@code turnover}, D-139)이 쓰는 입력을 본다. 일봉 스냅샷에 거래대금 파일이 없으면(D-133 전 스냅샷,
     * {@code --without-turnover}) 근사({@code avg-turnover})로 조용히 바꾸지 않고 계산 전체를 멈춘다. 파일의 마지막 날짜가
     * 기준 데이터일 c보다 이르면 {@code STALE_INPUT:}으로 시작하는 메시지로 멈춘다(실시간 모의 준비가 그날 입력 늦음으로 남김).
     *
     * @return 조건이 없으면 null
     */
    private SelectionData.TurnoverInput checkTurnover(Set<Metric.Kind> kinds, LocalDate cutoff) {
        if (!kinds.contains(Metric.Kind.TURNOVER)) {
            return null;
        }
        SelectionData.TurnoverInput in = data.turnover().orElseThrow(() -> new IllegalStateException(
                "실제 거래대금 조건(turnover)이 있는데 일봉 스냅샷 " + data.barsSnapshotId()
                        + "에 daily_turnover.csv가 없습니다. D-133 뒤 krx-daily로(--without-turnover 없이) 만든 스냅샷을 쓰세요"));
        if (in.lastDate() == null || in.lastDate().isBefore(cutoff)) {
            throw new IllegalStateException("STALE_INPUT: 실제 거래대금 파일 마지막 날짜 " + in.lastDate() + "이 기준 데이터일 "
                    + cutoff + "보다 이릅니다: " + in.snapshotId());
        }
        return in;
    }

    /** 사유 코드: 공시 조건의 유형을 스냅샷이 받지 않음(ADR-012 3.2, strategy-researcher PR #191 m3). 계산 실패 메시지 머리말. */
    public static final String INPUT_TYPE_MISSING = "INPUT_TYPE_MISSING";

    /**
     * 공시 조건이 쓰는 유형을 스냅샷이 받았고, 조건 구간(첫날 ~ c)을 수집 구간이 통째로 덮는지 본다. 모자라면 "공시 0건"으로 내지 않고
     * 실패한다(0건과 미수록을 구분, ADR-012 3.2). 메시지는 {@code STALE_INPUT:}·{@code INPUT_TYPE_MISSING:}으로 시작한다.
     */
    private void checkDisclosures(SelectionCriteria c, LocalDate applyFrom, List<LocalDate> window) {
        LocalDate end = disclosureTo(applyFrom);
        if (!c.inputKinds().contains(Metric.Kind.DISCLOSURE)) {
            return;
        }
        SelectionData.DisclosureInput in = data.disclosures()
                .orElseThrow(() -> new IllegalStateException("STALE_INPUT: 공시 조건이 있는데 공시 입력이 없습니다"));
        for (Condition g : c.gates()) {
            if (g.metric() != Metric.DISCLOSURE_COUNT) {
                continue;
            }
            LocalDate from = disclosureFrom(window, g.period());
            for (String t : g.types()) {
                if (!in.types().contains(t)) {
                    throw new IllegalStateException(INPUT_TYPE_MISSING + ": " + g.id() + " 공시 유형 " + t + "를 공시 스냅샷이 받지 않았습니다: "
                            + in.snapshotId() + " (받은 유형 " + String.join(",", in.types()) + ")");
                }
                if (!in.covers(t, from, end)) {
                    throw new IllegalStateException("STALE_INPUT: 공시 스냅샷이 " + g.id() + " 구간 " + from + " ~ " + end
                            + "의 유형 " + t + "를 다 담지 않았습니다: " + in.snapshotId());
                }
            }
        }
    }

    /** 공시 조건이 쓴 유형들의 수집 구간 끝날 중 가장 이른 날(선정 결과 {@code inputs.disclosures.covered_through}). */
    private LocalDate disclosuresThrough(SelectionCriteria c, LocalDate applyFrom) {
        LocalDate end = disclosureTo(applyFrom);
        SelectionData.DisclosureInput in = data.disclosures().orElseThrow();
        LocalDate out = null;
        for (Condition g : c.gates()) {
            for (String t : g.types()) {
                LocalDate through = in.coveredThrough(t, end);
                out = out == null || through.isBefore(out) ? through : out;
            }
        }
        return out;
    }

    /**
     * {@code disclosure-count} 구간 첫날: c에서 끝나는 w거래일의 첫날. 끝날(T 전날)과 합쳐 w1이면 [c, T−1]이라, 날마다 선정하면
     * 각 접수일이 정확히 한 번 센다(c 장 마감 뒤·c와 T 사이 휴장일 접수분은 T 선정에, c 전날 접수분은 그 앞 선정에).
     */
    private static LocalDate disclosureFrom(List<LocalDate> window, int w) {
        return window.get(window.size() - w);
    }

    /**
     * {@code disclosure-count} 구간 끝날: T 전날(달력일). c와 T 사이 휴장일(주말·연말·근로자의 날 등) 접수분도 T 선정에 든다.
     * T 당일 접수(장 전 공시 포함)는 쓰지 않는다. 재무의 "접수일 &lt; T" 규칙과 같다(PR #196 strategy-researcher M1).
     */
    private static LocalDate disclosureTo(LocalDate applyFrom) {
        return applyFrom.minusDays(1);
    }

    private record Scored(Symbol symbol, Eval eval) {
    }

    /** 한 종목 평가 중간값. */
    private static final class Eval {
        final List<String> reasons = new ArrayList<>();
        final Map<String, String> values = new TreeMap<>();
        boolean fallback;
        BigDecimal score;

        SelectionResult.Evaluation toEvaluation(Symbol symbol, boolean selected) {
            return new SelectionResult.Evaluation(symbol.code(), selected, selected ? List.of() : reasons,
                    new TreeMap<>(values), fallback);
        }
    }

    private Eval evaluate(Symbol symbol, SelectionCriteria c, LocalDate asOf, LocalDate cutoff,
            List<LocalDate> window, SelectionData.TurnoverInput turnover) {
        Eval e = new Eval();
        Map<LocalDate, DailyBar> bars = new HashMap<>();
        for (DailyBar b : data.bars(symbol, window.get(0), cutoff)) {
            bars.put(b.date(), b);
        }
        if (!c.kinds().isEmpty()) {
            Optional<InstrumentPeriod> period = data.instrumentOn(symbol, cutoff);
            if (period.isEmpty() || !c.kinds().contains(period.get().kind())) {
                e.reasons.add(FILTER_KIND);
                return e;
            }
        }
        if (c.minPrice() != null) {
            DailyBar last = bars.get(cutoff);
            if (last == null || last.close().compareTo(c.minPrice()) < 0) {
                e.reasons.add(FILTER_MIN_PRICE);
                return e;
            }
        }
        if (c.minBarDays() > 0 && tail(window, c.minBarDays()).stream().anyMatch(d -> !bars.containsKey(d))) {
            e.reasons.add(FILTER_MIN_BAR_DAYS);
            return e;
        }
        Context ctx = new Context(symbol, asOf, cutoff, window, bars, turnover);
        for (Condition g : c.gates()) {
            Value v = g.metric() == Metric.DISCLOSURE_COUNT ? disclosureCount(ctx, g) : value(ctx, g.metric(), g.period());
            if (v.fallback) {
                e.fallback = true;
            }
            if (v.number == null) {
                e.reasons.add(g.reasonCode() + "_" + v.missing);
            } else {
                e.values.put(g.id(), v.number.toPlainString());
                if (!g.op().test(v.number, g.threshold())) {
                    e.reasons.add(g.reasonCode());
                }
            }
        }
        if (e.reasons.isEmpty() && c.score().isPresent()) {
            Value v = value(ctx, c.score().get().metric(), c.score().get().period());
            if (v.fallback) {
                e.fallback = true;
            }
            if (v.number == null) {
                e.reasons.add(SCORE_NO_DATA);
            } else {
                e.score = v.number;
                e.values.put("score", v.number.toPlainString());
            }
        }
        return e;
    }

    /** 지표 계산에 필요한 한 종목의 그날 입력. 재무는 처음 쓸 때 한 번 푼다. */
    private final class Context {
        final Symbol symbol;
        final LocalDate applyFrom;
        final LocalDate cutoff;
        final List<LocalDate> window;
        final Map<LocalDate, DailyBar> bars;
        /** 실제 거래대금 입력. 조건이 없으면 null. */
        final SelectionData.TurnoverInput turnover;
        FinancialsAt financials;

        Context(Symbol symbol, LocalDate applyFrom, LocalDate cutoff, List<LocalDate> window,
                Map<LocalDate, DailyBar> bars, SelectionData.TurnoverInput turnover) {
            this.symbol = symbol;
            this.applyFrom = applyFrom;
            this.cutoff = cutoff;
            this.window = window;
            this.bars = bars;
            this.turnover = turnover;
        }

        FinancialsAt financials() {
            if (financials == null) {
                financials = new FinancialsAt(data.filings(symbol), applyFrom);
            }
            return financials;
        }

        /** 기간 끝(c)에서 n거래일의 일봉. 하루라도 없으면 null. */
        List<DailyBar> lastBars(int n) {
            List<DailyBar> out = new ArrayList<>(n);
            for (LocalDate d : tail(window, n)) {
                DailyBar b = bars.get(d);
                if (b == null) {
                    return null;
                }
                out.add(b);
            }
            return out;
        }
    }

    /** 지표 값 또는 결측 사유. */
    private record Value(BigDecimal number, String missing, boolean fallback) {
        static Value of(BigDecimal n, boolean fallback) {
            return new Value(n.round(MC).stripTrailingZeros(), null, fallback); // 중간 계산은 WIDE, 값은 한 번만 줄인다
        }

        static Value missing(String reason, boolean fallback) {
            return new Value(null, reason, fallback);
        }
    }

    private Value value(Context ctx, Metric metric, int period) {
        return switch (metric) {
            case MARKET_CAP -> marketCap(ctx);
            case VOLUME_RATIO -> volumeRatio(ctx, period);
            case RETURN -> periodReturn(ctx, period);
            case AVG_TURNOVER -> avgTurnover(ctx, period);
            case TURNOVER -> actualTurnover(ctx, period);
            case DAY_RANGE -> dayRange(ctx);
            case CLOSE_STRENGTH -> closeStrength(ctx);
            case VOLUME_SPIKE -> volumeSpike(ctx, period);
            case DISCLOSURE_COUNT -> throw new IllegalStateException("disclosure-count는 자격 조건으로만 계산합니다");
            case SESSION_TURNOVER, SESSION_RETURN, SESSION_RANGE ->
                    throw new IllegalStateException("장중 지표는 장중 순위 계산기만 계산합니다: " + metric.key());
            case OP_MARGIN -> latestRatio(ctx, f -> margin(f));
            case DEBT_RATIO -> debtRatio(ctx);
            case OP_MARGIN_GROWTH -> growth(ctx, period, FinancialItem.OP_MARGIN);
            case TOTAL_ASSETS_GROWTH -> growth(ctx, period, FinancialItem.TOTAL_ASSETS);
            case REVENUE_GROWTH -> growth(ctx, period, FinancialItem.REVENUE);
        };
    }

    private Value marketCap(Context ctx) {
        DailyBar last = ctx.bars.get(ctx.cutoff);
        OptionalLong shares = data.listedShares(ctx.symbol, ctx.cutoff);
        if (last == null || shares.isEmpty()) {
            return Value.missing(NO_DATA, false);
        }
        return Value.of(last.close().multiply(BigDecimal.valueOf(shares.getAsLong())), false);
    }

    /** 수정 거래량 = 거래량 ÷ 수정 계수(가격에 곱하는 계수의 역, 분할 전 거래량을 분할 뒤 주식 수 기준으로). */
    private static BigDecimal adjustedVolume(DailyBar b) {
        return BigDecimal.valueOf(b.volume()).divide(b.adjFactor(), WIDE);
    }

    private Value volumeRatio(Context ctx, int w) {
        List<DailyBar> bars = ctx.lastBars(2 * w);
        if (bars == null) {
            return Value.missing(NO_DATA, false);
        }
        BigDecimal prior = BigDecimal.ZERO;
        BigDecimal recent = BigDecimal.ZERO;
        for (int i = 0; i < bars.size(); i++) {
            BigDecimal v = adjustedVolume(bars.get(i));
            if (i < w) {
                prior = prior.add(v, WIDE);
            } else {
                recent = recent.add(v, WIDE);
            }
        }
        if (prior.signum() == 0) {
            return Value.missing(NO_DATA, false);
        }
        return Value.of(recent.divide(prior, WIDE), false);
    }

    /** 수정 종가. {@link DailyBar#adjustedClose()}는 DECIMAL64로 줄이므로 비를 낼 때는 넓게 다시 곱한다. */
    private static BigDecimal adjustedClose(DailyBar b) {
        return b.close().multiply(b.adjFactor(), WIDE);
    }

    private Value periodReturn(Context ctx, int w) {
        List<DailyBar> bars = ctx.lastBars(w + 1);
        if (bars == null) {
            return Value.missing(NO_DATA, false);
        }
        BigDecimal base = adjustedClose(bars.get(0));
        if (base.signum() <= 0) {
            return Value.missing(NO_DATA, false);
        }
        return Value.of(adjustedClose(bars.get(w)).divide(base, WIDE).subtract(BigDecimal.ONE, WIDE), false);
    }

    private Value avgTurnover(Context ctx, int w) {
        List<DailyBar> bars = ctx.lastBars(w);
        if (bars == null) {
            return Value.missing(NO_DATA, false);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (DailyBar b : bars) {
            sum = sum.add(b.close().multiply(BigDecimal.valueOf(b.volume())), WIDE);
        }
        return Value.of(sum.divide(BigDecimal.valueOf(w), WIDE), false);
    }

    /**
     * c에서 끝나는 w거래일 실제 거래대금(KRX {@code ACC_TRDVAL}, 원, 수정 안 함) 합 ÷ w(D-139). 거래정지일 0원 행도 평균에 든다
     * (정지가 길면 평균이 작아져 유동성 조건에서 떨어지는 것이 맞는 방향이다). w거래일 중 하루라도 그 종목 행이 없으면
     * {@code NO_DATA}다(상장 전, 그날 일봉을 쓰지 않음, KRX가 값을 주지 않음). 일봉 행이 있는지는 보지 않는다.
     */
    private Value actualTurnover(Context ctx, int w) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LocalDate d : tail(ctx.window, w)) {
            OptionalLong v = ctx.turnover.turnover(ctx.symbol, d);
            if (v.isEmpty()) {
                return Value.missing(NO_DATA, false);
            }
            sum = sum.add(BigDecimal.valueOf(v.getAsLong()), WIDE);
        }
        return Value.of(sum.divide(BigDecimal.valueOf(w), WIDE), false);
    }

    /**
     * (c 수정 고가 − c 수정 저가) ÷ c−1 수정 종가. 수정 계수가 c−1과 c 사이에서 바뀌는(분할 등) 날도 같은 기준으로 비교한다.
     */
    private Value dayRange(Context ctx) {
        List<DailyBar> bars = ctx.lastBars(2);
        if (bars == null) {
            return Value.missing(NO_DATA, false);
        }
        BigDecimal base = adjustedClose(bars.get(0));
        if (base.signum() <= 0) {
            return Value.missing(NO_DATA, false);
        }
        DailyBar c = bars.get(1);
        BigDecimal range = c.high().subtract(c.low()).multiply(c.adjFactor(), WIDE);
        return Value.of(range.divide(base, WIDE), false);
    }

    /**
     * (c 종가 − c 저가) ÷ (c 고가 − c 저가). 같은 날 세 가격이라 수정 계수가 약분되어 원가격으로 계산한다. 고가 = 저가(상·하한가
     * 고정, 거래 1건)는 정의되지 않아 {@code NO_DATA}다. 고가 < 저가 같은 고저 모순 행은 스냅샷 품질 검사가 오류로 먼저 빼므로
     * ({@code SnapshotDailyBarSource}) 여기서는 방어로만 같은 결측으로 둔다.
     */
    private Value closeStrength(Context ctx) {
        DailyBar c = ctx.bars.get(ctx.cutoff);
        if (c == null) {
            return Value.missing(NO_DATA, false);
        }
        BigDecimal span = c.high().subtract(c.low());
        if (span.signum() <= 0) {
            return Value.missing(NO_DATA, false);
        }
        return Value.of(c.close().subtract(c.low()).divide(span, WIDE), false);
    }

    /**
     * c 수정 거래량 ÷ (c 앞 w거래일 수정 거래량 합 ÷ w). w+1거래일 중 하루라도 일봉이 없거나 <b>거래량 0 이하인 날</b>(거래정지일은
     * 거래량 0 일봉으로 저장됨, ADR-008)이 있으면 {@code NO_DATA}다. 정지일이 평균에 들어가면 배율이 20 ÷ (20 − k)배로 부풀어
     * 재개 뒤 평소 거래량만으로 급증 조건을 넘기 때문이다(PR #193 strategy-researcher M1). 음수 거래량 행은 품질 검사가 경고로만
     * 남겨 적재되므로 같은 결측으로 막아 분모가 0 이하가 되지 않게 한다(PR #193 architect-2 F1).
     */
    private Value volumeSpike(Context ctx, int w) {
        List<DailyBar> bars = ctx.lastBars(w + 1);
        if (bars == null || bars.stream().anyMatch(b -> b.volume() <= 0)) {
            return Value.missing(NO_DATA, false);
        }
        BigDecimal prior = BigDecimal.ZERO;
        for (int i = 0; i < w; i++) {
            prior = prior.add(adjustedVolume(bars.get(i)), WIDE);
        }
        BigDecimal last = adjustedVolume(bars.get(w));
        return Value.of(last.multiply(BigDecimal.valueOf(w), WIDE).divide(prior, WIDE), false);
    }

    /**
     * 구간(c에서 끝나는 w거래일의 첫날 ~ T 전날)에 접수된 그 종목 공시 건수. 접수일 &lt; T만 세므로 T 당일 접수 공시(장 전 공시
     * 포함)는 쓰지 않는다(ADR-012 3.1). 0건은 정상값 0이다(스냅샷이 구간을 덮는지는 계산 전에 {@link #checkDisclosures}로 확인).
     */
    private Value disclosureCount(Context ctx, Condition g) {
        LocalDate from = disclosureFrom(ctx.window, g.period());
        int n = data.disclosures().orElseThrow().count(ctx.symbol, g.types(), from, disclosureTo(ctx.applyFrom));
        return Value.of(BigDecimal.valueOf(n), false);
    }

    /** 재무 항목(성장률 계산용). */
    private enum FinancialItem { OP_MARGIN, TOTAL_ASSETS, REVENUE }

    /** 영업이익 ÷ 매출액. 매출액이 없거나 0 이하면 null. */
    private static BigDecimal margin(FinancialFiling f) {
        if (f.revenue() == null || f.operatingIncome() == null || f.revenue().signum() <= 0) {
            return null;
        }
        return f.operatingIncome().divide(f.revenue(), WIDE);
    }

    private static BigDecimal item(FinancialFiling f, FinancialItem item) {
        return switch (item) {
            case OP_MARGIN -> margin(f);
            case TOTAL_ASSETS -> f.totalAssets();
            case REVENUE -> f.revenue();
        };
    }

    private Value latestRatio(Context ctx, Function<FinancialFiling, BigDecimal> ratio) {
        FinancialsAt.Latest latest = ctx.financials().latest();
        if (latest.filing().isEmpty()) {
            return Value.missing(NO_DATA, latest.fallback());
        }
        BigDecimal v = ratio.apply(latest.filing().get());
        return v == null ? Value.missing(NO_DATA, latest.fallback()) : Value.of(v, latest.fallback());
    }

    private Value debtRatio(Context ctx) {
        FinancialsAt.Latest latest = ctx.financials().latest();
        if (latest.filing().isEmpty()) {
            return Value.missing(NO_DATA, latest.fallback());
        }
        FinancialFiling f = latest.filing().get();
        if (f.totalLiabilities() == null || f.totalEquity() == null) {
            return Value.missing(NO_DATA, latest.fallback());
        }
        if (f.totalEquity().signum() <= 0) {
            return Value.missing(CAPITAL_IMPAIRED, latest.fallback());
        }
        return Value.of(f.totalLiabilities().divide(f.totalEquity(), WIDE), latest.fallback());
    }

    /** 최근 사업보고서 값 ÷ {@code years}개 사업연도 전 값 − 1. */
    private Value growth(Context ctx, int years, FinancialItem item) {
        FinancialsAt.Latest latest = ctx.financials().latest();
        boolean fb = latest.fallback();
        if (latest.filing().isEmpty()) {
            return Value.missing(NO_DATA, fb);
        }
        Optional<FinancialFiling> base = ctx.financials().year(latest.year() - years);
        if (base.isEmpty()) {
            return Value.missing(NO_DATA, fb);
        }
        BigDecimal now = item(latest.filing().get(), item);
        BigDecimal then = item(base.get(), item);
        if (now == null || then == null) {
            return Value.missing(NO_DATA, fb);
        }
        if (item == FinancialItem.OP_MARGIN) {
            if (now.signum() <= 0 || then.signum() <= 0) {
                return Value.missing(DEFICIT, fb); // 적자는 증가율을 정의하지 않는다(D-041 4장)
            }
        } else if (then.signum() <= 0) {
            return Value.missing(NO_DATA, fb);
        }
        return Value.of(now.divide(then, WIDE).subtract(BigDecimal.ONE, WIDE), fb);
    }

    private static List<LocalDate> tail(List<LocalDate> window, int n) {
        return window.subList(window.size() - n, window.size());
    }
}
