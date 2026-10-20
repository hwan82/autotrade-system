package com.autotrade.sim.live;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.recording.RecordingPlan;
import com.autotrade.sim.recording.RegisteredSelections;
import com.autotrade.sim.run.RegistrationYaml;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실시간 모의 등록 파일 {@code experiments/<실험ID>/experiment.yaml}(input: live, ADR-010 2.4, D-076). 녹화 실험 등록 파일
 * ({@link RecordingPlan})과 같은 키를 쓰고 {@code input}만 다르다. 실행 전에 커밋하고 운용 중 고치지 않는다. 바꾸려면 새 실험 ID를
 * 쓴다(사전 등록).
 *
 * <pre>
 * id: "EXP-20261015-LIVE01"
 * question: "장중 프로필들이 실시간 시세에서 리플레이와 같은 결과를 내는가"
 * input: "live"
 * market: "KRX"
 * from: "2026-10-15"             # 운용 시작 거래일(포함)
 * to: "2026-12-11"               # 운용 끝 거래일(포함). 이 기간 밖 이벤트는 받지 않는다
 * seed: "20261015"
 * seed-capital: "10000000"
 * benchmark: "005930"            # 또는 none
 * profiles:                      # 이 순서로 계좌 번호를 매긴다
 *   - "kr-bh-baseline@1"
 * boards:                        # 받을 종목과 소속부(비용 표 구분). 여기 없는 종목의 시세는 세션 입력에서 뺀다
 *   - "005930 KOSPI"
 * blacklist:                     # 선택. 세션 시작 시점 제외 종목(RSK-12)
 *   - "000660"
 * selections:                    # 선택(D-124). 선정 x 전략 조합 계좌. profiles 계좌 뒤에 번호를 매긴다
 *   - "kr-intraday-hot@1 x kr-volume-surge@1"
 * selection-input:               # selections가 있으면 필수
 *   daily: "latest"              # 준비 시점에 기준 데이터일을 덮는 가장 최근 일봉 스냅샷(스냅샷 ID도 됨)
 * </pre>
 *
 * <p>선정 조합 계좌는 거래일마다 준비 작업이 미리 만든 그날 선정 결과 ∩ {@code boards}에만 신규 진입한다(ADR-012 3.5,
 * {@link com.autotrade.sim.selection.SessionSelections}). 지금은 국내(KRX)만 받는다.
 */
public record LivePlan(
        ExperimentId id,
        String question,
        Market market,
        LocalDate from,
        LocalDate to,
        long seed,
        Money seedCapital,
        Optional<String> benchmark,
        List<RecordingPlan.ProfileRef> profiles,
        Map<String, KrxBoard> boards,
        List<String> blacklist,
        RegisteredSelections selections) {

    /** 등록 파일의 {@code input} 값. */
    public static final String INPUT = "live";

    private static final Set<String> SCALARS = Set.of("id", "question", "input", "market", "from", "to", "seed",
            "seed-capital", "benchmark");
    private static final Set<String> LISTS = Set.of("profiles", "boards", "blacklist", RegisteredSelections.LIST_KEY);
    private static final Map<String, Set<String>> MAPS =
            Map.of(RegisteredSelections.INPUT_KEY, RegisteredSelections.INPUT_SUBKEYS);
    private static final Pattern REF = Pattern.compile("([a-z0-9][a-z0-9-]*)@([1-9][0-9]*)");
    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{6}");
    private static final Pattern BOARD = Pattern.compile("([0-9A-Z]{6}) ([A-Z]+)");

    public LivePlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(seedCapital, "seedCapital");
        Objects.requireNonNull(benchmark, "benchmark");
        profiles = List.copyOf(profiles);
        boards = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(boards));
        blacklist = List.copyOf(blacklist);
        Objects.requireNonNull(selections, "selections");
        if (market != Market.KRX) {
            throw new IllegalArgumentException("실시간 모의는 지금 국내(KRX)만 지원합니다: " + market);
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("종료일이 시작일보다 앞섭니다: " + from + " ~ " + to);
        }
        if (profiles.isEmpty() && selections.isEmpty()) {
            throw new IllegalArgumentException("프로필(profiles)이나 선정 조합(selections)이 하나 이상 필요합니다");
        }
        if (boards.isEmpty()) {
            throw new IllegalArgumentException("boards에 받을 종목이 하나 이상 필요합니다");
        }
        if (benchmark.isPresent() && !boards.containsKey(benchmark.get())) {
            throw new IllegalArgumentException("벤치마크 종목이 boards에 없습니다: " + benchmark.get());
        }
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일 경로)
     * @throws IllegalArgumentException 형식이 틀렸거나 필수 키가 없을 때
     */
    public static LivePlan parse(String text, String source) {
        RegistrationYaml raw = RegistrationYaml.parse(text, source, SCALARS, LISTS, MAPS);
        try {
            return build(raw);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static LivePlan build(RegistrationYaml y) {
        if (!INPUT.equals(y.required("input"))) {
            throw new IllegalArgumentException("input은 \"" + INPUT + "\"이어야 합니다: " + y.scalars().get("input"));
        }
        Market market = Market.valueOf(y.required("market"));
        List<RecordingPlan.ProfileRef> profiles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : y.list("profiles")) {
            Matcher m = REF.matcher(p);
            if (!m.matches()) {
                throw new IllegalArgumentException("프로필은 id@버전 형식이어야 합니다: " + p);
            }
            if (!seen.add(p)) {
                throw new IllegalArgumentException("같은 프로필이 두 번 있습니다: " + p);
            }
            profiles.add(new RecordingPlan.ProfileRef(m.group(1), Integer.parseInt(m.group(2))));
        }
        Map<String, KrxBoard> boards = new TreeMap<>();
        for (String b : y.list("boards")) {
            Matcher m = BOARD.matcher(b);
            if (!m.matches()) {
                throw new IllegalArgumentException("소속부는 \"종목코드 소속부\" 형식이어야 합니다: " + b);
            }
            KrxBoard board;
            try {
                board = KrxBoard.valueOf(m.group(2));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("비용 표에 없는 소속부입니다: " + b, e);
            }
            if (boards.put(m.group(1), board) != null) {
                throw new IllegalArgumentException("같은 종목의 소속부가 두 번 있습니다: " + m.group(1));
            }
        }
        TreeSet<String> black = new TreeSet<>();
        for (String c : y.list("blacklist")) {
            black.add(code(c));
        }
        String bench = y.scalars().getOrDefault("benchmark", "none");
        Optional<String> benchmark = bench.equals("none") ? Optional.empty() : Optional.of(code(bench));
        return new LivePlan(new ExperimentId(y.required("id")), y.required("question"), market,
                LocalDate.parse(y.required("from")), LocalDate.parse(y.required("to")), Long.parseLong(y.required("seed")),
                Money.of(Long.parseLong(y.required("seed-capital")), market.currency()), benchmark, profiles, boards,
                List.copyOf(black), RegisteredSelections.read(y, true));
    }

    /** 선정 조합 계좌가 없는 등록(D-124 전 모양). */
    public LivePlan(ExperimentId id, String question, Market market, LocalDate from, LocalDate to, long seed,
            Money seedCapital, Optional<String> benchmark, List<RecordingPlan.ProfileRef> profiles,
            Map<String, KrxBoard> boards, List<String> blacklist) {
        this(id, question, market, from, to, seed, seedCapital, benchmark, profiles, boards, blacklist,
                RegisteredSelections.NONE);
    }

    private static String code(String v) {
        if (!CODE.matcher(v).matches()) {
            throw new IllegalArgumentException("종목 코드 형식이 아닙니다: " + v);
        }
        return v;
    }
}
