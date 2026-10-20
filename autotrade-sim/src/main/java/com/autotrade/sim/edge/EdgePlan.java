package com.autotrade.sim.edge;

import com.autotrade.domain.market.Market;
import com.autotrade.sim.combo.CombinationPlan;
import com.autotrade.sim.run.RegistrationYaml;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 선정 엣지 일봉 사전 검사 등록 파일 {@code experiments/<실험ID>/edge.yaml}(ADR-012 3.3·배치표 2b, D-125). 결과를 보기 전에
 * 커밋하고 고치지 않는다. 바꾸려면 새 실험 ID.
 *
 * <pre>
 * id: "EXP-20261020-EDGE01"
 * question: "..."
 * market: "KRX"
 * bars-snapshot: "krx-daily-2026-10-16-a6df4719"
 * disclosures-snapshot: "dart-disclosures-..."  # 선정이 공시 지표를 쓸 때만(D-123). 생존편향 확인도 이 스냅샷으로 한다
 * selection: "kr-intraday-hot@1"
 * test: "RANGE"                 # RANGE: T 장중 변동폭 (고가 − 저가) ÷ 시가, NET: T 시가 매수 → T 종가 매도 비용 차감 수익률
 * family-size: "2"              # 본페로니 m(2b 검사 묶음 전체의 선정 프로필 수)
 * seed: "20261020"
 * null-seeds: "999"             # 무작위 선정 대조군 시드 수 N. N + 1 ≥ 20m
 * trade-notional: "1000000"     # 왕복 하나의 매수 금액(원). 수수료·거래세 원 미만 절사가 수익률에 주는 영향을 고정한다
 * holdout-from: "2026-04-01"    # 이 날부터는 어떤 구간에도 넣지 않는다(표본 외 보존)
 * parallelism: "4"
 * windows:                      # 판정 구간(3개 이상, 겹치지 않고 오름차순)
 *   - "2015-07-01..2016-06-30"
 * descriptive:                  # 판정에 넣지 않는 기술 통계 구간(선택)
 *   - "2010-03-02..2015-06-12"
 * </pre>
 */
public record EdgePlan(
        String id,
        String question,
        Market market,
        String barsSnapshot,
        Optional<String> disclosuresSnapshot,
        CombinationPlan.Ref selection,
        Test test,
        int familySize,
        long seed,
        int nullSeeds,
        BigDecimal tradeNotional,
        LocalDate holdoutFrom,
        int parallelism,
        List<Window> windows,
        List<Window> descriptive) {

    /** 실험 ID 상한. {@code ExperimentId} 48자 안에서 다른 명령과 같게 둔다. */
    static final int MAX_ID = 30;
    /** 판정 구간 최소 수(ADR-012 3.3 "워크포워드 3구간 이상"). */
    static final int MIN_WINDOWS = 3;
    /** 대조군 시드 상한(계산량). */
    static final int MAX_NULL_SEEDS = 20_000;

    private static final Set<String> SCALARS = Set.of("id", "question", "market", "bars-snapshot", "disclosures-snapshot",
            "selection", "test",
            "family-size", "seed", "null-seeds", "trade-notional", "holdout-from", "parallelism");
    private static final Set<String> LISTS = Set.of("windows", "descriptive");
    private static final Pattern ID = Pattern.compile("EXP-\\d{8}-[A-Z0-9]{1,16}");
    private static final Pattern SNAPSHOT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern WINDOW = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.\\.(\\d{4}-\\d{2}-\\d{2})");

    /** 판정 통계. */
    public enum Test {
        /** T 장중 변동폭 (T 고가 − T 저가) ÷ T 시가. 변동성 가설((다), PR #191 n1). 비용과 무관하다. */
        RANGE,
        /** T 시가 매수 → T 종가 매도의 비용 차감 수익률. 방향 가설((라)). */
        NET
    }

    /** 닫힌 날짜 구간. */
    public record Window(LocalDate from, LocalDate to) {

        public Window {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("구간이 뒤집혔습니다: " + from + ".." + to);
            }
        }

        boolean contains(LocalDate d) {
            return !d.isBefore(from) && !d.isAfter(to);
        }

        @Override
        public String toString() {
            return from + ".." + to;
        }
    }

    public EdgePlan {
        Objects.requireNonNull(disclosuresSnapshot, "disclosuresSnapshot");
        windows = List.copyOf(windows);
        descriptive = List.copyOf(descriptive);
    }

    /** 본페로니 유의수준 0.05 ÷ m. */
    public double alpha() {
        return 0.05 / familySize;
    }

    /**
     * @throws IllegalArgumentException 형식·값이 틀렸을 때
     */
    public static EdgePlan parse(String text, String source) {
        RegistrationYaml y = RegistrationYaml.parse(text, source, SCALARS, LISTS);
        String id = y.required("id");
        if (!ID.matcher(id).matches() || id.length() > MAX_ID) {
            throw new IllegalArgumentException("실험 ID는 EXP-YYYYMMDD-영문대문자·숫자, " + MAX_ID + "자 이내여야 합니다: " + id);
        }
        String question = y.required("question");
        Market market = Market.valueOf(y.required("market"));
        if (market != Market.KRX) {
            throw new IllegalArgumentException("지금은 KRX만 받습니다(일봉 스냅샷·비용 표): " + market);
        }
        String bars = y.required("bars-snapshot");
        if (!SNAPSHOT.matcher(bars).matches() || bars.equals("latest")) {
            throw new IllegalArgumentException("bars-snapshot은 스냅샷 ID여야 합니다(latest 불가): " + bars);
        }
        Optional<String> disclosures = Optional.ofNullable(y.scalars().get("disclosures-snapshot"));
        if (disclosures.isPresent() && (!SNAPSHOT.matcher(disclosures.get()).matches()
                || disclosures.get().equals("latest"))) {
            throw new IllegalArgumentException("disclosures-snapshot은 스냅샷 ID여야 합니다(latest 불가): " + disclosures.get());
        }
        CombinationPlan.Ref selection = CombinationPlan.Ref.parse(y.required("selection"));
        Test test = Test.valueOf(y.required("test"));
        int m = intIn(y, "family-size", 1, 20);
        long seed = Long.parseLong(y.required("seed"));
        int n = intIn(y, "null-seeds", 1, MAX_NULL_SEEDS);
        if (n + 1 < 20 * m) {
            throw new IllegalArgumentException("null-seeds가 모자랍니다: N + 1 ≥ 20m이어야 합니다(m=" + m + ", N=" + n + ")");
        }
        BigDecimal notional = new BigDecimal(y.required("trade-notional"));
        if (notional.signum() <= 0) {
            throw new IllegalArgumentException("trade-notional은 0보다 커야 합니다: " + notional);
        }
        LocalDate holdout = date(y.required("holdout-from"), "holdout-from");
        int parallelism = intIn(y, "parallelism", 1, 64);
        List<Window> windows = windows(y.list("windows"), "windows");
        List<Window> descriptive = windows(y.list("descriptive"), "descriptive");
        if (windows.size() < MIN_WINDOWS) {
            throw new IllegalArgumentException("판정 구간은 " + MIN_WINDOWS + "개 이상이어야 합니다: " + windows.size());
        }
        List<Window> all = new ArrayList<>(windows);
        all.addAll(descriptive);
        for (Window w : all) {
            if (!w.to().isBefore(holdout)) {
                throw new IllegalArgumentException("구간이 표본 외 보존(holdout-from " + holdout + ")에 들어갑니다: " + w);
            }
        }
        for (Window d : descriptive) {
            for (Window w : windows) {
                if (!d.to().isBefore(w.from()) && !w.to().isBefore(d.from())) {
                    throw new IllegalArgumentException("기술 통계 구간이 판정 구간과 겹칩니다: " + d + ", " + w);
                }
            }
        }
        return new EdgePlan(id, question, market, bars, disclosures, selection, test, m, seed, n, notional, holdout, parallelism,
                windows, descriptive);
    }

    private static List<Window> windows(List<String> items, String key) {
        List<Window> out = new ArrayList<>();
        for (String s : items) {
            Matcher m = WINDOW.matcher(s);
            if (!m.matches()) {
                throw new IllegalArgumentException(key + " 항목은 YYYY-MM-DD..YYYY-MM-DD여야 합니다: " + s);
            }
            Window w = new Window(date(m.group(1), key), date(m.group(2), key));
            if (!out.isEmpty() && !out.get(out.size() - 1).to().isBefore(w.from())) {
                throw new IllegalArgumentException(key + " 구간은 겹치지 않고 오름차순이어야 합니다: " + w);
            }
            out.add(w);
        }
        return out;
    }

    private static LocalDate date(String s, String key) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " 날짜 형식이 아닙니다: " + s, e);
        }
    }

    private static int intIn(RegistrationYaml y, String key, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(y.required(key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "는 정수여야 합니다", e);
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(key + "는 " + min + "~" + max + "이어야 합니다: " + v);
        }
        return v;
    }
}
