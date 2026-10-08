package com.autotrade.sim.cost;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 가상 체결 비용 모델(SIM-07, D-021): 비용 항목 목록 + 체결가 기반 체결용 고정 슬리피지율.
 *
 * <p>계약
 * <ul>
 *   <li>불변, 스레드 안전. 실험 시작 시 한 번 만들고 실험 동안 바꾸지 않는다(설정 변경은 다음 실험부터).</li>
 *   <li>{@link #fees}는 체결 건 하나 단위로, 항목마다 계좌 통화 소수 자릿수로 따로 반올림한다(Q-004 조사 4.3).</li>
 *   <li>구간 선택 날짜는 체결 이벤트의 거래소 현지 날짜다. Clock의 현재 날짜가 아니다.</li>
 *   <li>{@link #id()}는 프로필 ID + 표 전체 내용 해시 앞 12자리다. 표 내용이 바뀌면 ID도 바뀐다
 *       ({@code ExperimentSpec#costModelId}).</li>
 *   <li>{@link #tableHash}는 실험 기간과 겹치는 구간만 넣은 해시다. 미래 구간을 추가해도 과거 실험의 해시는 그대로다.</li>
 *   <li>세그먼트 판별({@code segmentOf})은 해시에 들어가지 않는다. 국내 소속부는 시점별 이력이 필요하다(Q-012).</li>
 * </ul>
 */
public final class CostModel {

    private static final Pattern PROFILE_ID = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,47}");

    private final String profileId;
    private final Market market;
    private final List<Levy> levies;
    private final BigDecimal slippageRate;
    private final Function<Symbol, String> segmentOf;
    private final String id;

    /**
     * @param slippageRate 호가 없이 체결가로 체결할 때 불리한 방향으로 더하는 비율(D-021 0.15%). 호가 기반 체결에는 쓰지 않는다
     * @param segmentOf 종목 → 세그먼트(국내 소속부). 세그먼트 요율이 없는 시장은 {@code s -> SegmentRates.ALL}
     */
    public CostModel(
            String profileId, Market market, List<Levy> levies, BigDecimal slippageRate,
            Function<Symbol, String> segmentOf) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        if (!PROFILE_ID.matcher(profileId).matches()) {
            throw new IllegalArgumentException("비용 프로필 ID 형식이 아닙니다: " + profileId);
        }
        this.market = Objects.requireNonNull(market, "market");
        this.levies = List.copyOf(levies);
        Objects.requireNonNull(slippageRate, "slippageRate");
        if (slippageRate.signum() < 0 || slippageRate.compareTo(new BigDecimal("0.05")) > 0) {
            throw new IllegalArgumentException("슬리피지율은 0 이상 0.05 이하여야 합니다: " + slippageRate);
        }
        this.slippageRate = slippageRate.signum() == 0 ? BigDecimal.ZERO : slippageRate.stripTrailingZeros();
        this.segmentOf = Objects.requireNonNull(segmentOf, "segmentOf");
        if (this.levies.stream().map(Levy::name).distinct().count() != this.levies.size()) {
            throw new IllegalArgumentException("비용 항목 이름이 겹칩니다");
        }
        this.id = profileId + "-" + sha256(canonical(null, null, true)).substring(0, 12);
    }

    public String profileId() {
        return profileId;
    }

    /** 내용이 바뀌면 바뀌는 불변 ID. 예: {@code KR-DEFAULT-1a2b3c4d5e6f}. */
    public String id() {
        return id;
    }

    public Market market() {
        return market;
    }

    public Currency currency() {
        return market.currency();
    }

    public BigDecimal slippageRate() {
        return slippageRate;
    }

    public List<Levy> levies() {
        return levies;
    }

    /** 체결 건 하나의 비용. */
    public FeeBreakdown fees(Side side, Symbol symbol, LocalDate tradeDate, BigDecimal price, long quantity) {
        Objects.requireNonNull(side, "side");
        if (symbol.market() != market) {
            throw new IllegalArgumentException("비용 모델 시장과 종목 시장이 다릅니다: " + market + ", " + symbol);
        }
        BigDecimal notional = price.multiply(BigDecimal.valueOf(quantity));
        String segment = segmentOf.apply(symbol);
        int scale = currency().getDefaultFractionDigits();
        Money commission = Money.zero(currency());
        Money tax = Money.zero(currency());
        TreeMap<String, Money> items = new TreeMap<>();
        for (Levy levy : levies) {
            if (!levy.applies(side)) {
                continue;
            }
            Money amount = new Money(
                    levy.rawAmount(tradeDate, segment, notional, quantity).setScale(scale, levy.rounding()), currency());
            items.put(levy.name(), amount);
            if (levy.category() == CostCategory.COMMISSION) {
                commission = commission.plus(amount);
            } else {
                tax = tax.plus(amount);
            }
        }
        return new FeeBreakdown(commission, tax, items);
    }

    /** 매수 1건의 수수료 추정(현금 예약용). 매수에 붙는 모든 항목을 계산한다. */
    public Money buyFees(Symbol symbol, LocalDate tradeDate, BigDecimal price, long quantity) {
        return fees(Side.BUY, symbol, tradeDate, price, quantity).total();
    }

    /** 실험 기간 전체가 모든 항목의 표 안에 있는지(SIM-07: 밖이면 실험 시작 거부). to가 null이면 열린 기간. */
    public boolean covers(LocalDate from, LocalDate to) {
        return levies.stream().allMatch(l -> l.table().covers(from, to));
    }

    /** 실험 기간과 겹치는 구간만 정규화해 계산한 SHA-256(소문자 hex 64자). D-021 3항. */
    public String tableHash(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        return sha256(canonical(from, to, false));
    }

    private String canonical(LocalDate from, LocalDate to, boolean all) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(profileId).append('\n');
        sb.append("market=").append(market.name()).append('\n');
        sb.append("slippage=").append(slippageRate.toPlainString()).append('\n');
        for (Levy levy : levies) {
            sb.append("levy=").append(levy.canonicalHeader()).append('\n');
            List<? extends Period<? extends CostValue>> periods =
                    all ? levy.table().periods() : levy.table().overlapping(from, to);
            for (Period<? extends CostValue> p : periods) {
                sb.append("  ").append(p.canonical()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
