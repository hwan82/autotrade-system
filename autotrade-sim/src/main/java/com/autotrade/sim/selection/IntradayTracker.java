package com.autotrade.sim.selection;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.selection.calc.SessionSelectionCalculator;
import com.autotrade.selection.calc.SessionStats;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.selection.result.SessionSelectionResult;
import com.autotrade.selection.result.SelectionResult;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 장중 순위 선정({@code INTRADAY_AT}, ADR-012 5.2 2차-a, D-125)을 시세 이벤트 흐름 위에서 정한다. 이벤트를 순번 순서대로 한 번씩
 * 받는다({@link #onEvent}). 실시간 모의는 계좌에 주기 직전에, 리플레이 실행기는 Run을 시작하기 전 한 번 읽기에서 같은 이벤트를 같은
 * 순서로 주므로 두 쪽의 순위와 적용 시작 순번이 같다.
 *
 * <p>규칙(선정 프로필 하나, 거래일 D)
 * <ul>
 *   <li><b>모으기</b>: D의 체결 중 거래소 시각이 정규장이고 {@code at} 전인 boards 종목 체결을 종목마다 요약한다. D의 정규장 안에서
 *       끝나고 {@code at} 전에 시작한 시세 누락({@link DataGap})이 있던 종목은 표시한다(값을 믿을 수 없음).</li>
 *   <li><b>순위 시각</b>: D의 이벤트 중 누락 표시가 아니고 거래소 시각이 {@code at} 이상인 첫 이벤트가 오면, 그 이벤트를 모으기 전에
 *       그때까지 모은 것으로 순위를 매긴다. 그 뒤에 오는 {@code at} 전 체결은 넣지 않는다(그 순간 알 수 없었던 정보, 미래 정보 0건).
 *       그 이벤트의 순번부터 후보에 신규 진입을 허용한다.</li>
 *   <li><b>후보</b>: 선정 결과 ∩ boards에서 blacklist를 빼고 메우지 않는다(D-124와 같은 {@link SelectionCandidates}).</li>
 *   <li>D의 이벤트가 끝까지 {@code at}에 이르지 않으면 그날은 {@link SelectionDay#awaitingIntraday}(신규 진입 0건)다.</li>
 * </ul>
 *
 * <p>계산은 boards 크기만큼의 합계를 세우는 일이라 이벤트 경로에서 해도 받는 큐를 막지 않는다(일봉 선정을 이벤트 경로 밖에서 미리
 * 만드는 것과 다름, architect PR #191 F1). 한 스레드에서만 쓴다.
 */
public final class IntradayTracker implements SelectionDays {

    private final IntradayUniverse universe;
    /** 선정 해시 → 프로필(해시순). */
    private final TreeMap<String, SelectionProfile> profiles = new TreeMap<>();
    /** 순위 시각 → 그 시각을 쓰는 선정 해시. 같은 시각 선정은 같은 요약을 나눠 쓴다. */
    private final TreeMap<LocalTime, List<String>> byAt = new TreeMap<>();
    /** 선정 해시 → (거래일 → 판정). 순위를 매긴 날만 있다. */
    private final Map<String, NavigableMap<LocalDate, SelectionDay>> days = new HashMap<>();
    /** 선정 해시 → (거래일 → 결과). */
    private final Map<String, NavigableMap<LocalDate, Ranked>> results = new HashMap<>();
    private final Map<LocalTime, Window> windows = new HashMap<>();
    private LocalDate today;

    /**
     * @param selections 장중 순위 선정 프로필(다른 갱신 주기는 건너뛴다)
     */
    public IntradayTracker(Collection<SelectionProfile> selections, IntradayUniverse universe) {
        this.universe = Objects.requireNonNull(universe, "universe");
        for (SelectionProfile p : selections) {
            if (p.refresh() == SelectionRefresh.INTRADAY_AT && profiles.putIfAbsent(p.selectionHash(), p) == null) {
                if (p.market() != universe.rules().market()) {
                    throw new IllegalArgumentException("장중 순위 선정의 시장이 실험과 다릅니다: " + p.key());
                }
                byAt.computeIfAbsent(p.at().orElseThrow(), t -> new ArrayList<>()).add(p.selectionHash());
                days.put(p.selectionHash(), new TreeMap<>());
                results.put(p.selectionHash(), new TreeMap<>());
            }
        }
    }

    /** 조합들의 장중 순위 선정 프로필(선정 해시마다 하나). */
    public static List<SelectionProfile> intradayProfiles(Collection<Combination> combinations) {
        TreeMap<String, SelectionProfile> out = new TreeMap<>();
        for (Combination c : combinations) {
            c.selection().filter(s -> s.refresh() == SelectionRefresh.INTRADAY_AT)
                    .ifPresent(s -> out.putIfAbsent(s.selectionHash(), s));
        }
        return List.copyOf(out.values());
    }

    public boolean isEmpty() {
        return profiles.isEmpty();
    }

    /** 이 추적기가 다루는 선정인지. */
    public boolean tracks(String selectionHash) {
        return profiles.containsKey(selectionHash);
    }

    /** 이벤트 하나를 받는다(순번 오름차순, 계좌에 주기 전). */
    public void onEvent(MarketEvent e) {
        if (profiles.isEmpty()) {
            return;
        }
        LocalDate d = universe.rules().tradingDate(e.exchangeTime());
        if (!d.equals(today)) {
            today = d;
            windows.clear();
            byAt.keySet().forEach(t -> windows.put(t, new Window()));
        }
        LocalTime local = e.exchangeTime().atZone(universe.rules().zone()).toLocalTime();
        for (Map.Entry<LocalTime, List<String>> g : byAt.entrySet()) {
            Window w = windows.get(g.getKey());
            if (w.ranked) {
                continue;
            }
            if (!(e instanceof DataGap) && !local.isBefore(g.getKey())) {
                rank(g.getValue(), w, d, e.sequence());
                continue;
            }
            collect(w, e, local, g.getKey());
        }
    }

    private void collect(Window w, MarketEvent e, LocalTime local, LocalTime at) {
        String code = e.symbol().code();
        if (!universe.boards().contains(code) || e.symbol().market() != universe.rules().market()) {
            return;
        }
        if (e instanceof TradeTick t) {
            if (local.isBefore(at) && universe.rules().isRegularSession(t.exchangeTime())) {
                w.stats.merge(code, SessionStats.of(t.price(), t.quantity()),
                        (a, b) -> a.plus(t.price(), t.quantity()));
            }
        } else if (e instanceof DataGap g && universe.rules().isRegularSession(g.gapEnd())
                && universe.rules().tradingDate(g.gapEnd()).equals(today)
                && g.gapStart().atZone(universe.rules().zone()).toLocalTime().isBefore(at)) {
            // 구간(정규장 시작 ~ at 전)과 겹치는 누락만 표시한다. at 뒤에 시작한 누락은 순위 값과 상관없다
            w.gapped.add(code);
        }
    }

    private void rank(List<String> hashes, Window w, LocalDate d, long sequence) {
        w.ranked = true;
        for (String h : hashes) {
            SelectionProfile p = profiles.get(h);
            SessionSelectionResult r = SessionSelectionCalculator.calculate(p, d, universe.boards(), w.stats, w.gapped);
            SelectionCandidates c = SelectionCandidates.of(r.selected(), universe.boards(), universe.blacklist());
            TreeSet<Symbol> symbols = new TreeSet<>();
            c.candidates().forEach(code -> symbols.add(new Symbol(p.market(), code)));
            String hash = r.contentHash();
            days.get(h).put(d, SelectionDay.intraday(d, hash, symbols, sequence));
            results.get(h).put(d, new Ranked(r, hash, c, sequence));
        }
    }

    /** 그 선정의 그 거래일 판정. 순위를 매기지 않은 날은 {@link SelectionDay#awaitingIntraday}. */
    @Override
    public SelectionDay day(String selectionHash, LocalDate date) {
        NavigableMap<LocalDate, SelectionDay> m = days.get(selectionHash);
        if (m == null) {
            throw new IllegalArgumentException("장중 순위 선정이 아닌 선정입니다: " + selectionHash);
        }
        SelectionDay d = m.get(date);
        return d != null ? d : SelectionDay.awaitingIntraday(date);
    }

    /** 순위를 매긴 결과(선정 해시순, 거래일순). 보고서·화면용. */
    public List<Ranked> ranked() {
        List<Ranked> out = new ArrayList<>();
        for (String h : profiles.keySet()) {
            out.addAll(results.get(h).values());
        }
        return out;
    }

    /** 그 거래일의 선정마다 상태(선정 해시순). 순위 전이면 {@link Ranked} 없이 순위 시각만. */
    public List<DayStatus> status(LocalDate date) {
        List<DayStatus> out = new ArrayList<>();
        for (Map.Entry<String, SelectionProfile> e : profiles.entrySet()) {
            out.add(new DayStatus(e.getValue().key(), e.getKey(), e.getValue().at().orElseThrow(),
                    results.get(e.getKey()).get(date)));
        }
        return Collections.unmodifiableList(out);
    }

    /** 그날 한 순위 시각의 모으는 상태. */
    private static final class Window {
        final Map<String, SessionStats> stats = new LinkedHashMap<>();
        final Set<String> gapped = new HashSet<>();
        boolean ranked;
    }

    /**
     * 순위를 매긴 하루.
     *
     * @param contentHash {@code result.contentHash()}(한 번만 계산)
     * @param activeFrom 순위를 매긴 이벤트의 순번. 이 이벤트부터 후보에 신규 진입을 허용한다
     */
    public record Ranked(SessionSelectionResult result, String contentHash, SelectionCandidates candidates,
            long activeFrom) {
    }

    /** 화면용 상태. @param ranked 순위 전이면 null */
    public record DayStatus(String selection, String selectionHash, LocalTime at, Ranked ranked) {
    }

    /** 결과 파일 상대 경로에 쓰는 이름 {@code <거래일>/<선정 해시>.intraday.json}. */
    public static String fileName(Ranked r) {
        SelectionResult.Selection s = r.result().selection();
        return r.result().date() + "/" + s.hash() + ".intraday.json";
    }
}
