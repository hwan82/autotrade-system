package com.autotrade.sim.live;

import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.recording.RecordingPlan;
import com.autotrade.sim.selection.IntradayTracker;
import com.autotrade.sim.selection.IntradayUniverse;
import com.autotrade.sim.selection.SessionSelections;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
        Symbol benchmark,
        ExchangeCalendar calendar,
        List<SelectionProfile> selectionProfiles) {
    // selectionProfiles: 장전 선정(PRE_OPEN_DAILY)만. 장중 순위 선정은 combinations에서 찾는다(D-127)

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
        // 선정 조합 계좌는 profiles 계좌 뒤에 등록 파일 순서로 번호를 매긴다(D-124). 선정 없는 계좌의 번호·시드는 그대로다
        List<Combination> selected = RecordingExperiment.selectionCombinations(repoRoot, plan.selections(), factory);
        combinations.addAll(selected);
        List<BlacklistEntry> black = plan.blacklist().stream()
                .map(c -> new BlacklistEntry(Symbol.krx(c), BlacklistType.MANUAL, "실험 등록 파일 " + plan.id())).toList();
        BlacklistSnapshot blacklist = BlacklistSnapshot.of(black);
        RiskChain risk = RiskChain.builder(blacklist).build();
        Symbol benchmark = plan.benchmark().map(Symbol::krx).orElse(null);
        return new LiveSetup(plan, rules, cost, factory, List.copyOf(combinations), blacklist, risk,
                ExecutionSettings.defaults(), benchmark, calendar, RecordingExperiment.selectionProfiles(selected));
    }

    /** 장전 선정 조합 계좌가 있는지(고정 파일·적용 기록을 쓰는 선정, D-124). 장중 순위 선정은 {@link #hasIntraday}. */
    boolean hasSelections() {
        return !selectionProfiles.isEmpty();
    }

    /** 장중 순위 선정 조합 계좌가 있는지(D-127). */
    boolean hasIntraday() {
        return !IntradayTracker.intradayProfiles(combinations).isEmpty();
    }

    /** 장중 순위 선정 입력 범위(등록 파일 boards·blacklist). 실시간 세션과 리플레이 확인이 같은 값을 쓴다. */
    IntradayUniverse intradayUniverse() {
        return new IntradayUniverse(plan.boards().keySet(), Set.copyOf(plan.blacklist()), rules);
    }

    /** 장중 순위 선정 추적기(새 것). 장중 순위 선정이 없으면 null. */
    IntradayTracker newIntradayTracker() {
        return hasIntraday() ? new IntradayTracker(IntradayTracker.intradayProfiles(combinations), intradayUniverse()) : null;
    }

    /** 세션 폴더의 선정 고정 파일·적용 기록(D-124). */
    SessionSelections selectionFiles(Path sessionDir) {
        return new SessionSelections(sessionDir, selectionProfiles, plan.boards().keySet(), Set.copyOf(plan.blacklist()),
                calendar::previousTradingDay);
    }

    /** @param dataVersion 실시간 세션은 {@link #LIVE_DATA_VERSION_PREFIX} + 실험 ID, 리플레이 확인은 입력 기록 데이터셋 버전 */
    ExperimentSpec spec(ClockMode mode, String dataVersion) {
        return spec(mode, dataVersion, plan.to());
    }

    /** 운용 기간 중 {@code to}까지만 도는 실험 명세(주간 점검이 운용 중간까지 다시 돌릴 때). */
    ExperimentSpec spec(ClockMode mode, String dataVersion, LocalDate to) {
        return new ExperimentSpec(plan.id(), plan.from(), to, plan.seed(), plan.seedCapital(), cost.id(),
                dataVersion, mode, combinations);
    }

    /**
     * 세션이 받는 이벤트인지: 수신 날짜와 거래일이 모두 운용 기간 안. 캘린더 수록 범위 밖 등 판단할 수 없으면 받지 않는다.
     * 종목(boards) 확인은 따로 한다.
     */
    boolean inPlan(MarketEvent e) {
        LocalDate received = e.receivedAt().atZone(rules.zone()).toLocalDate();
        if (received.isBefore(plan.from()) || received.isAfter(plan.to())) {
            return false;
        }
        LocalDate trading;
        try {
            trading = rules.tradingDate(e.exchangeTime());
        } catch (RuntimeException ex) {
            return false;
        }
        return !trading.isBefore(plan.from()) && !trading.isAfter(plan.to());
    }

    /** 세션이 받는 종목인지(등록 파일 boards, 같은 시장). */
    boolean onBoards(MarketEvent e) {
        return e.symbol().market() == plan.market() && plan.boards().containsKey(e.symbol().code());
    }

    /** 녹화 실험 보고서 형식({@link RecordingExperiment#results})을 그대로 쓰기 위한 같은 내용의 녹화 등록. */
    RecordingPlan asRecordingPlan() {
        return new RecordingPlan(plan.id(), plan.question(), plan.market(), plan.from(), plan.to(), plan.seed(),
                plan.seedCapital(), plan.benchmark(), 1, plan.profiles(), plan.boards(), plan.blacklist(), plan.selections());
    }
}
