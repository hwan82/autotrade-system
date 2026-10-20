package com.autotrade.sim.live;

import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.recording.RecordingPlan;
import com.autotrade.sim.run.RegistrationYaml;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 녹화 사전 검사 등록 파일 {@code experiments/<실험ID>/experiment.yaml}(input: recording-precheck, ADR-014 5.1). 녹화 실험 등록
 * 파일({@link RecordingPlan})의 키를 모두 받고({@code selection-input}은 스냅샷 ID만), 판정 역할 키({@code references}·
 * {@code group-null-seeds}, ADR-014 F1·F3)와 사전 검사 판정 값을 더한다. 판정 값은 계획 4.2가 이 실험용으로 정한 수치라 코드에 두지
 * 않고 등록 파일에 적는다.
 *
 * <pre>
 * family-size: "4"                 # m_pre. 빠진 조합도 센다(계획 4.1)
 * alpha: "0.05"                    # P2 문턱 = alpha / family-size
 * min-round-trips: "45"            # P0
 * p2:                              # P2를 판정할 계좌(나머지 판정 대상은 p만 참고로 적음)
 *   - "kr-intraday-open-momentum@1 x kr-orb-30@1"
 * p3:                              # "&lt;판정 대상&gt; vs &lt;참고 계좌&gt;"
 *   - "kr-intraday-open-momentum@1 x kr-orb-30@1 vs kr-intraday-open-all@1 x kr-orb-30@1"
 * p3-min-segments: "3"             # P3 구간 중 d &gt; 0 이어야 하는 수(p3가 있을 때 필수)
 * segments:                        # 구간(거래일, 양 끝 포함). 기간의 모든 거래일을 겹침 없이 덮어야 한다
 *   - "2026-10-26 2026-11-06"
 * </pre>
 *
 * <p>판정에 쓰는 수는 문자열을 {@link BigDecimal}·정수로 읽고 {@code double}로 바꾸지 않는다. 프로필을 읽어야 아는 거절(판정 대상이
 * 아님, 대조군 묶음의 시드 수 없음, {@code family-size} &lt; 판정 대상 수, P2 묶음의 N 하한)은 {@link RecordingPrecheck#roles}가 한다.
 *
 * @param recording 녹화 실험과 같은 키(계좌 순서·녹화 읽기·선정 준비·{@code inputs.yaml})
 * @param live 같은 계좌를 실험 조립({@link LiveSetup})과 역할 계산({@link JudgeRoles})에 넘기기 위한 같은 내용의 등록
 * @param p3 {@code "<판정 대상> vs <참고 계좌>"} 쌍(파일 순서)
 * @param segments P3 구간(날짜순)
 */
public record PrecheckPlan(
        RecordingPlan recording,
        LivePlan live,
        int familySize,
        BigDecimal alpha,
        int minRoundTrips,
        List<String> p2,
        List<Pair> p3,
        int p3MinSegments,
        List<Segment> segments) {

    /** 등록 파일의 {@code input} 값. */
    public static final String INPUT = "recording-precheck";
    static final String FAMILY_SIZE = "family-size";
    static final String ALPHA = "alpha";
    static final String MIN_ROUND_TRIPS = "min-round-trips";
    static final String P2 = "p2";
    static final String P3 = "p3";
    static final String P3_MIN_SEGMENTS = "p3-min-segments";
    static final String SEGMENTS = "segments";

    private static final Pattern COUNT = Pattern.compile("[1-9][0-9]{0,3}");
    private static final Pattern FRACTION = Pattern.compile("0\\.[0-9]{1,9}");
    private static final Pattern SEGMENT = Pattern.compile("([0-9]{4}-[0-9]{2}-[0-9]{2}) ([0-9]{4}-[0-9]{2}-[0-9]{2})");
    private static final String VS = " vs ";

    /** P3 비교 쌍: 판정 대상 계좌 줄과 참고 계좌 줄(등록 파일 계좌 줄 그대로). */
    public record Pair(String judged, String reference) {
        public Pair {
            Objects.requireNonNull(judged, "judged");
            Objects.requireNonNull(reference, "reference");
        }

        String text() {
            return judged + VS + reference;
        }
    }

    /** P3 구간(거래일, 양 끝 포함). */
    public record Segment(LocalDate from, LocalDate to) {
        public Segment {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        boolean contains(LocalDate d) {
            return !d.isBefore(from) && !d.isAfter(to);
        }

        @Override
        public String toString() {
            return from + " ~ " + to;
        }
    }

    public PrecheckPlan {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(alpha, "alpha");
        p2 = List.copyOf(p2);
        p3 = List.copyOf(p3);
        segments = List.copyOf(segments);
        if (familySize < 1) {
            throw new IllegalArgumentException(FAMILY_SIZE + "는 1 이상이어야 합니다: " + familySize);
        }
        if (alpha.signum() <= 0 || alpha.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(ALPHA + "는 0과 1 사이(양 끝 제외)여야 합니다: " + alpha.toPlainString());
        }
        if (minRoundTrips < 1) {
            throw new IllegalArgumentException(MIN_ROUND_TRIPS + "는 1 이상이어야 합니다: " + minRoundTrips);
        }
        List<String> lines = accountLines(live);
        Set<String> references = new HashSet<>(live.references());
        Set<String> seen = new HashSet<>();
        for (String line : p2) {
            if (!lines.contains(line)) {
                throw new IllegalArgumentException("p2 항목이 profiles·selections 줄에 없습니다(글자 그대로 다시 적어야 합니다): " + line);
            }
            if (!seen.add(line)) {
                throw new IllegalArgumentException("p2에 같은 계좌가 두 번 있습니다: " + line);
            }
        }
        Set<String> pairs = new HashSet<>();
        for (Pair p : p3) {
            if (!lines.contains(p.judged())) {
                throw new IllegalArgumentException("p3 왼쪽 계좌가 profiles·selections 줄에 없습니다: " + p.judged());
            }
            if (!references.contains(p.reference())) {
                throw new IllegalArgumentException("p3 오른쪽은 참고 계좌(references)여야 합니다: " + p.reference());
            }
            if (!pairs.add(p.text())) {
                throw new IllegalArgumentException("p3에 같은 줄이 두 번 있습니다: " + p.text());
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(SEGMENTS + "에 구간이 하나 이상 필요합니다(P3·구간 표, ADR-014 5.3)");
        }
        if (!p3.isEmpty() && (p3MinSegments < 1 || p3MinSegments > segments.size())) {
            throw new IllegalArgumentException(P3_MIN_SEGMENTS + "는 1 ~ 구간 수(" + segments.size() + ")여야 합니다: "
                    + p3MinSegments);
        }
        requireCover(segments, recording.from(), recording.to(), ExchangeCalendar.bundled(recording.market()));
    }

    /**
     * 구간들이 기간의 모든 거래일을 겹침 없이 덮는지(거래소 캘린더, strategy-researcher R5). 구간 양 끝은 기간 안 거래일이고 날짜순이며,
     * 다음 구간은 앞 구간 끝 다음 거래일에 시작한다. 첫 구간은 기간 첫 거래일, 마지막 구간은 기간 마지막 거래일에서 끝난다.
     */
    static void requireCover(List<Segment> segments, LocalDate from, LocalDate to, ExchangeCalendar calendar) {
        List<LocalDate> days = calendar.tradingDays(from, to);
        if (days.isEmpty()) {
            throw new IllegalArgumentException("기간에 거래일이 없습니다(거래소 캘린더): " + from + " ~ " + to);
        }
        int next = 0;
        for (Segment s : segments) {
            if (s.to().isBefore(s.from())) {
                throw new IllegalArgumentException("구간 끝이 시작보다 앞입니다: " + s);
            }
            if (s.from().isBefore(from) || s.to().isAfter(to)) {
                throw new IllegalArgumentException("구간이 기간(" + from + " ~ " + to + ") 밖입니다: " + s);
            }
            if (!days.contains(s.from()) || !days.contains(s.to())) {
                throw new IllegalArgumentException("구간 양 끝은 거래일이어야 합니다(거래소 캘린더): " + s);
            }
            if (next >= days.size() || s.from().isBefore(days.get(next))) {
                throw new IllegalArgumentException("구간이 앞 구간과 겹치거나 날짜순이 아닙니다: " + s);
            }
            if (s.from().isAfter(days.get(next))) {
                throw new IllegalArgumentException("구간 사이에 덮지 않은 거래일이 있습니다: " + days.get(next) + " (다음 구간 " + s
                        + ")");
            }
            next = days.indexOf(s.to()) + 1;
        }
        if (next < days.size()) {
            throw new IllegalArgumentException("마지막 구간 뒤에 덮지 않은 거래일이 있습니다: " + days.get(next) + " ~ " + to);
        }
    }

    /** 등록 파일 계좌 줄(계좌 번호 순서): {@code profiles} 줄 다음 {@code selections} 줄. */
    static List<String> accountLines(LivePlan live) {
        List<String> lines = new ArrayList<>();
        live.profiles().forEach(p -> lines.add(p.text()));
        live.selections().entries().forEach(e -> lines.add(e.text()));
        return lines;
    }

    /** 계좌 줄의 계좌 번호(0부터). */
    int account(String line) {
        return accountLines(live).indexOf(line);
    }

    /** P2 문턱 alpha / family-size의 표시(소수 5자리 반올림, 표시용). */
    String threshold() {
        return alpha.divide(BigDecimal.valueOf(familySize), 5, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** P2가 판정 가능할 수 있는 가장 작은 N′ + 1 = ⌈family-size ÷ alpha⌉(정확 나눗셈의 올림). */
    int minPlusOne() {
        return BigDecimal.valueOf(familySize).divide(alpha, 0, RoundingMode.CEILING).intValueExact();
    }

    /**
     * @param source 오류 메시지에 쓸 이름(파일 경로)
     * @throws IllegalArgumentException 형식이 틀렸거나 필수 키가 없을 때
     */
    public static PrecheckPlan parse(String text, String source) {
        Set<String> scalars = new LinkedHashSet<>(RecordingPlan.SCALARS);
        scalars.addAll(List.of(FAMILY_SIZE, ALPHA, MIN_ROUND_TRIPS, P3_MIN_SEGMENTS));
        Set<String> lists = new LinkedHashSet<>(RecordingPlan.LISTS);
        lists.addAll(List.of(LivePlan.REFERENCES_KEY, LivePlan.GROUP_NULL_SEEDS_KEY, P2, P3, SEGMENTS));
        RegistrationYaml raw = RegistrationYaml.parse(text, source, scalars, lists, RecordingPlan.MAPS);
        try {
            return build(raw);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static PrecheckPlan build(RegistrationYaml y) {
        RecordingPlan r = RecordingPlan.build(y, INPUT);
        LivePlan live = new LivePlan(r.id(), r.question(), r.market(), r.from(), r.to(), r.seed(), r.seedCapital(),
                r.benchmark(), r.profiles(), r.boards(), r.blacklist(), r.selections(), y.list(LivePlan.REFERENCES_KEY),
                LivePlan.groupNullSeeds(y));
        List<Pair> p3 = new ArrayList<>();
        for (String line : y.list(P3)) {
            int at = line.indexOf(VS);
            if (at <= 0 || line.indexOf(VS, at + 1) >= 0) {
                throw new IllegalArgumentException("p3 항목은 \"<판정 대상> vs <참고 계좌>\" 형식이어야 합니다: " + line);
            }
            p3.add(new Pair(line.substring(0, at), line.substring(at + VS.length())));
        }
        List<Segment> segments = new ArrayList<>();
        for (String line : y.list(SEGMENTS)) {
            Matcher m = SEGMENT.matcher(line);
            if (!m.matches()) {
                throw new IllegalArgumentException("segments 항목은 \"YYYY-MM-DD YYYY-MM-DD\" 형식이어야 합니다: " + line);
            }
            segments.add(new Segment(LocalDate.parse(m.group(1)), LocalDate.parse(m.group(2))));
        }
        String minSegments = y.scalars().get(P3_MIN_SEGMENTS);
        if (!p3.isEmpty() && minSegments == null) {
            throw new IllegalArgumentException("p3가 있으면 " + P3_MIN_SEGMENTS + "가 필요합니다");
        }
        return new PrecheckPlan(r, live, count(y, FAMILY_SIZE), fraction(y.required(ALPHA)), count(y, MIN_ROUND_TRIPS),
                y.list(P2), p3, minSegments == null ? 0 : count(minSegments, P3_MIN_SEGMENTS), segments);
    }

    private static int count(RegistrationYaml y, String key) {
        return count(y.required(key), key);
    }

    private static int count(String v, String key) {
        if (!COUNT.matcher(v).matches()) {
            throw new IllegalArgumentException(key + "는 1~9999 정수여야 합니다: " + v);
        }
        return Integer.parseInt(v);
    }

    private static BigDecimal fraction(String v) {
        if (!FRACTION.matcher(v).matches()) {
            throw new IllegalArgumentException(ALPHA + "는 0과 1 사이 소수(예: \"0.05\")여야 합니다: " + v);
        }
        return new BigDecimal(v);
    }
}
