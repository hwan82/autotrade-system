package com.autotrade.sim.live;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.recording.RecordingPlan;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 실시간 모의 세션과 그 리플레이 확인이 같이 쓰는 실험 조립(시장 규칙, 비용, 전략, 블랙리스트, 체결 설정). 녹화 실험(D-069)과
 * 같은 규칙으로 만든다: 등록 파일 종목은 주식 호가 단위, 비용은 국내 기본 표, 블랙리스트는 등록 파일 스냅샷 하나(RSK-12).
 * 두 쪽이 같은 조립을 써야 실시간 결과와 리플레이 결과를 바이트로 비교할 수 있다.
 */
record LiveSetup(
        LivePlan plan,
        KrxMarketRules rules,
        CostModel cost,
        CatalogStrategyFactory factory,
        List<Combination> combinations,
        BlacklistSnapshot blacklist,
        RiskChain risk,
        ExecutionSettings execution,
        Symbol benchmark) {

    /** 실시간 세션 계약 데이터 버전. 리플레이 확인은 입력 기록의 녹화 데이터셋 버전을 쓴다(보고서에는 나오지 않음). */
    static final String LIVE_DATA_VERSION_PREFIX = "LIVE-";

    static LiveSetup of(LivePlan plan, Path repoRoot) {
        ExchangeCalendar calendar = ExchangeCalendar.bundled(plan.market());
        KrxMarketRules rules = new KrxMarketRules(calendar, s -> InstrumentKind.STOCK);
        CostModel cost = CostModels.krDefault(s -> plan.boards().get(s.code()));
        if (!cost.covers(plan.from(), plan.to())) {
            throw new IllegalArgumentException(
                    "운용 기간이 비용 표 밖입니다(SIM-07): " + plan.from() + " ~ " + plan.to() + ", " + cost.id());
        }
        CatalogStrategyFactory factory = new CatalogStrategyFactory(rules);
        List<Combination> combinations = new ArrayList<>();
        for (RecordingPlan.ProfileRef ref : plan.profiles()) {
            StrategyProfile p = RecordingExperiment.loadStrategy(repoRoot, ref);
            factory.validate(p);
            combinations.add(Combination.of(p));
        }
        List<BlacklistEntry> black = plan.blacklist().stream()
                .map(c -> new BlacklistEntry(Symbol.krx(c), BlacklistType.MANUAL, "실험 등록 파일 " + plan.id())).toList();
        BlacklistSnapshot blacklist = BlacklistSnapshot.of(black);
        RiskChain risk = RiskChain.builder(blacklist).build();
        Symbol benchmark = plan.benchmark().map(Symbol::krx).orElse(null);
        return new LiveSetup(plan, rules, cost, factory, List.copyOf(combinations), blacklist, risk,
                ExecutionSettings.defaults(), benchmark);
    }

    /** @param dataVersion 실시간 세션은 {@link #LIVE_DATA_VERSION_PREFIX} + 실험 ID, 리플레이 확인은 입력 기록 데이터셋 버전 */
    ExperimentSpec spec(ClockMode mode, String dataVersion) {
        return new ExperimentSpec(plan.id(), plan.from(), plan.to(), plan.seed(), plan.seedCapital(), cost.id(),
                dataVersion, mode, combinations);
    }

    /** 녹화 실험 보고서 형식({@link RecordingExperiment#results})을 그대로 쓰기 위한 같은 내용의 녹화 등록. */
    RecordingPlan asRecordingPlan() {
        return new RecordingPlan(plan.id(), plan.question(), plan.market(), plan.from(), plan.to(), plan.seed(),
                plan.seedCapital(), plan.benchmark(), 1, plan.profiles(), plan.boards(), plan.blacklist());
    }
}
