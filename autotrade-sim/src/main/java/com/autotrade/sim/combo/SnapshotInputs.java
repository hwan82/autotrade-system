package com.autotrade.sim.combo;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.daily.SnapshotListedShares;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.financial.SnapshotFinancials;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.calc.SnapshotDisclosures;
import com.autotrade.selection.calc.SnapshotSelectionData;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.daily.SnapshotTradingDays;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.selection.CalculatedSelectionSource;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 수집 스냅샷 폴더에서 읽은 일봉 실험 입력 묶음. 조합 실험({@link CombinationExperiment})과 워크포워드 판정
 * ({@code com.autotrade.sim.walkforward})이 같은 적재·검증을 쓴다(D-061).
 *
 * @param first manifest {@code coverage.firstDate}
 * @param last manifest {@code coverage.lastTradingDate}
 * @param shares 상장주식수. 스냅샷에 파일이 없으면 null
 * @param financials 재무 스냅샷. 요청하지 않았으면 null
 * @param disclosures 공시 스냅샷(D-122). 요청하지 않았으면 null
 */
public record SnapshotInputs(Path barsDir, SnapshotDailyBarSource bars, LocalDate first, LocalDate last,
        SnapshotListedShares shares, SnapshotFinancials financials, SnapshotDisclosures disclosures,
        SnapshotTradingDays.Derived days,
        SnapshotSelectionData selectionData, ExchangeCalendar calendar) {

    public SnapshotInputs {
        Objects.requireNonNull(bars, "bars");
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(selectionData, "selectionData");
    }

    /**
     * @param rangeCheck 스냅샷 범위(first, last)가 정해진 직후, 상장주식수·재무를 읽기 전에 부른다. 실험 기간이 범위 밖이면 여기서
     *     {@link IllegalArgumentException}을 던진다
     */
    public static SnapshotInputs load(Market market, String barsSnapshot, Optional<String> financialsSnapshot,
            Optional<String> disclosuresSnapshot, Path snapshotsRoot, ExchangeCalendar calendar, RunGate gate, Consumer<String> log,
            BiConsumer<LocalDate, LocalDate> rangeCheck) {
        gate.check(); // 힙을 크게 쓰는 스냅샷 읽기 전에 먼저 본다
        Path barsDir = snapshotsRoot.resolve("krx-daily").resolve(barsSnapshot);
        log.accept("일봉 스냅샷 읽기: " + barsDir);
        SnapshotDailyBarSource bars = SnapshotDailyBarSource.load(barsDir);
        if (!bars.snapshotId().equals(barsSnapshot)) {
            throw new IllegalArgumentException("폴더 이름과 manifest 스냅샷 ID가 다릅니다: " + barsSnapshot + " ≠ "
                    + bars.snapshotId());
        }
        SnapshotManifest manifest = bars.manifest();
        SnapshotManifest.Coverage coverage = manifest.coverage();
        if (coverage == null || coverage.firstDate() == null || coverage.lastTradingDate() == null) {
            throw new IllegalArgumentException("일봉 스냅샷 manifest에 coverage(firstDate, lastTradingDate)가 없습니다: "
                    + barsSnapshot + ". 수집기(ADR-008)로 다시 만드세요");
        }
        LocalDate first = LocalDate.parse(coverage.firstDate());
        LocalDate last = LocalDate.parse(coverage.lastTradingDate());
        rangeCheck.accept(first, last);
        gate.check(); // 준비 단계(스냅샷 적재·이벤트 변환)도 단계마다 본다(PR #52 QA F1)
        SnapshotListedShares shares = manifest.files().stream()
                .anyMatch(f -> f.name().equals(SnapshotManifest.LISTED_SHARES)) ? SnapshotListedShares.load(barsDir) : null;
        SnapshotFinancials financials = null;
        if (financialsSnapshot.isPresent()) {
            Path dir = snapshotsRoot.resolve("dart-financials").resolve(financialsSnapshot.get());
            log.accept("재무 스냅샷 읽기: " + dir);
            financials = SnapshotFinancials.load(dir);
            if (!financials.manifest().snapshotId().equals(financialsSnapshot.get())) {
                throw new IllegalArgumentException("폴더 이름과 manifest 스냅샷 ID가 다릅니다: " + financialsSnapshot.get());
            }
        }
        SnapshotDisclosures disclosures = null;
        if (disclosuresSnapshot.isPresent()) {
            gate.check();
            Path dir = snapshotsRoot.resolve("dart-disclosures").resolve(disclosuresSnapshot.get());
            log.accept("공시 스냅샷 읽기: " + dir);
            disclosures = SnapshotDisclosures.load(dir);
            if (!disclosures.snapshotId().equals(disclosuresSnapshot.get())) {
                throw new IllegalArgumentException("폴더 이름과 manifest 스냅샷 ID가 다릅니다: " + disclosuresSnapshot.get());
            }
        }
        gate.check();
        SnapshotTradingDays.Derived days = SnapshotTradingDays.derive(bars, bars.snapshotId(), market, first, last, calendar);
        SnapshotSelectionData selectionData = new SnapshotSelectionData(market, bars, shares, financials, disclosures, days.days(),
                days.digest(), calendar.zone());
        return new SnapshotInputs(barsDir, bars, first, last, shares, financials, disclosures, days, selectionData, calendar);
    }

    /** 이 입력으로 날짜별 선정을 계산하는 공급자(결과를 캐시한다). */
    public CalculatedSelectionSource newSelectionSource() {
        return new CalculatedSelectionSource(new SelectionCalculator(selectionData));
    }

    /** 종목 → 종목 이력(마지막 일봉일 기준 구간)으로 만든 시장 규칙. 소속부 이력은 Q-012 단순화다. */
    public static KrxMarketRules rules(ExchangeCalendar calendar, Map<Symbol, InstrumentPeriod> periods) {
        Function<Symbol, InstrumentKind> kinds = s -> {
            InstrumentPeriod p = periods.get(s);
            return p == null ? InstrumentKind.STOCK : p.kind();
        };
        return new KrxMarketRules(calendar, kinds);
    }

    public static CostModel krCost(Map<Symbol, InstrumentPeriod> periods) {
        return CostModels.krDefault(s -> CombinationExperiment.board(periods.get(s), s));
    }
}
