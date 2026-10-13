package com.autotrade.sim.recording;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.cost.KrxBoard;
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
 * 녹화 실험 등록 파일 {@code experiments/<실험ID>/experiment.yaml}(사전 등록, D-069). 실행 전에 커밋하고 결과를 본 뒤 고치지
 * 않는다. 바꾸려면 새 실험 ID를 쓴다. 모양은 {@link RegistrationYaml}.
 *
 * <pre>
 * id: "EXP-20261013-REC01"
 * question: "실녹화 하루에서 장중 프로필들이 같은 결과를 두 번 내는가"
 * input: "recording"
 * market: "KRX"
 * from: "2026-10-12"
 * to: "2026-10-12"
 * seed: "20261013"
 * seed-capital: "10000000"
 * benchmark: "005930"            # 또는 none
 * parallelism: "2"
 * profiles:                      # 이 순서로 계좌 번호를 매긴다
 *   - "kr-bh-baseline@1"
 * boards:                        # 녹화에 나오는 종목마다 소속부(비용 표 구분)
 *   - "005930 KOSPI"
 * blacklist:                     # 선택. 실험 시작 시점 제외 종목(RSK-12)
 *   - "000660"
 * </pre>
 *
 * <p>선정 프로필은 받지 않는다: 선정 계산은 일봉·재무 스냅샷이 필요하다(조합 실험, D-061). 소속부는 녹화에 없어 등록
 * 파일에 적는다(실험 시작 시점 기준). 지금은 국내(KRX)만 받는다.
 *
 * @param profiles 전략 프로필 {@code id@버전}
 * @param boards 종목 코드 → 소속부(코드 순)
 * @param blacklist 제외 종목 코드(코드 순, 중복 없음)
 */
public record RecordingPlan(
        ExperimentId id,
        String question,
        Market market,
        LocalDate from,
        LocalDate to,
        long seed,
        Money seedCapital,
        Optional<String> benchmark,
        int parallelism,
        List<ProfileRef> profiles,
        Map<String, KrxBoard> boards,
        List<String> blacklist) {

    /** 등록 파일의 {@code input} 값. 조합 실험 등록 파일과 섞이지 않게 꼭 적는다. */
    public static final String INPUT = "recording";

    private static final Set<String> SCALARS = Set.of("id", "question", "input", "market", "from", "to", "seed",
            "seed-capital", "benchmark", "parallelism");
    private static final Set<String> LISTS = Set.of("profiles", "boards", "blacklist");
    private static final Pattern REF = Pattern.compile("([a-z0-9][a-z0-9-]*)@([1-9][0-9]*)");
    private static final Pattern CODE = Pattern.compile("[0-9A-Z]{6}");
    private static final Pattern BOARD = Pattern.compile("([0-9A-Z]{6}) ([A-Z]+)");

    /** 전략 프로필 {@code id@버전}. */
    public record ProfileRef(String id, int version) {

        public ProfileRef {
            Objects.requireNonNull(id, "id");
        }

        public String text() {
            return id + "@" + version;
        }
    }

    public RecordingPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(seedCapital, "seedCapital");
        Objects.requireNonNull(benchmark, "benchmark");
        profiles = List.copyOf(profiles);
        boards = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(boards));
        blacklist = List.copyOf(blacklist);
        if (market != Market.KRX) {
            throw new IllegalArgumentException("녹화 실험은 지금 국내(KRX)만 지원합니다: " + market);
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("종료일이 시작일보다 앞섭니다: " + from + " ~ " + to);
        }
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("parallelism은 1~64여야 합니다: " + parallelism);
        }
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("프로필이 하나 이상 필요합니다");
        }
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일 경로)
     * @throws IllegalArgumentException 형식이 틀렸거나 필수 키가 없을 때
     */
    public static RecordingPlan parse(String text, String source) {
        RegistrationYaml raw = RegistrationYaml.parse(text, source, SCALARS, LISTS);
        try {
            return build(raw);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static RecordingPlan build(RegistrationYaml y) {
        if (!INPUT.equals(y.required("input"))) {
            throw new IllegalArgumentException("input은 \"" + INPUT + "\"이어야 합니다: " + y.scalars().get("input"));
        }
        Market market = Market.valueOf(y.required("market"));
        List<ProfileRef> profiles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : y.list("profiles")) {
            Matcher m = REF.matcher(p);
            if (!m.matches()) {
                throw new IllegalArgumentException("프로필은 id@버전 형식이어야 합니다: " + p);
            }
            if (!seen.add(p)) {
                throw new IllegalArgumentException("같은 프로필이 두 번 있습니다: " + p);
            }
            profiles.add(new ProfileRef(m.group(1), Integer.parseInt(m.group(2))));
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
        return new RecordingPlan(new ExperimentId(y.required("id")), y.required("question"), market,
                LocalDate.parse(y.required("from")), LocalDate.parse(y.required("to")), Long.parseLong(y.required("seed")),
                Money.of(Long.parseLong(y.required("seed-capital")), market.currency()), benchmark,
                Integer.parseInt(y.scalars().getOrDefault("parallelism", "1")), profiles, boards, List.copyOf(black));
    }

    private static String code(String v) {
        if (!CODE.matcher(v).matches()) {
            throw new IllegalArgumentException("종목 코드 형식이 아닙니다: " + v);
        }
        return v;
    }
}
