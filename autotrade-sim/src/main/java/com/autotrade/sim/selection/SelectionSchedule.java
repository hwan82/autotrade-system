package com.autotrade.sim.selection;

import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.selection.result.SelectionInvalidReason;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 실험 하나의 날짜별 선정 결과 표(STR-17 "같은 날 같은 선정 결과를 여러 계좌가 공유", SIM-10). 실행기가 Run을 시작하기
 * 전에 한 번 만들고, 이후에는 읽기만 하는 불변 값이라 병렬 Run이 함께 읽어도 된다.
 *
 * <p>선정 해시마다 거래일 오름차순으로 공급자를 부르되, 앞서 받은 결과의 적용 범위가 그날을 덮으면 다시 부르지 않는다
 * (주간 갱신 결과 하나가 한 주 전체에 쓰인다). 받은 값은 ADR-007 2.6 판정 순서 중 메모리 결과에 해당하는 것만 본다:
 * 형식({@code MALFORMED}) → 선정 해시({@code HASH_MISMATCH}) → 적용 시작({@code NOT_YET}) → 적용 종료({@code EXPIRED}).
 * 생산 상태 파일·입력 기준일 판정은 결과 파일을 읽는 쪽(실시간 모의·실전 엔진의 {@code SelectionValidity})이 한다.
 */
public final class SelectionSchedule {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    /** 선정 해시 → (거래일 → 판정). */
    private final Map<String, NavigableMap<LocalDate, SelectionDay>> days;

    private SelectionSchedule(Map<String, NavigableMap<LocalDate, SelectionDay>> days) {
        this.days = days;
    }

    /** 선정 있는 조합이 없을 때 쓰는 빈 표. */
    public static SelectionSchedule empty() {
        return new SelectionSchedule(Map.of());
    }

    /**
     * @param combinations 실험의 조합. 선정 없는 조합은 건너뛴다
     * @param tradingDates 이벤트가 있는 거래일(중복 없음)
     */
    public static SelectionSchedule compute(
            Collection<Combination> combinations, Collection<LocalDate> tradingDates, SelectionSource source) {
        Objects.requireNonNull(source, "source");
        // 선정 해시 순으로 처리해 공급자 호출 순서가 조합 목록 순서와 무관하게 정해진다
        TreeMap<String, SelectionProfile> selections = new TreeMap<>();
        for (Combination c : combinations) {
            c.selection().ifPresent(s -> selections.putIfAbsent(s.selectionHash(), s));
        }
        List<LocalDate> dates = new TreeSet<>(tradingDates).stream().toList();
        Map<String, NavigableMap<LocalDate, SelectionDay>> out = new LinkedHashMap<>();
        for (Map.Entry<String, SelectionProfile> e : selections.entrySet()) {
            out.put(e.getKey(), Collections.unmodifiableNavigableMap(schedule(e.getValue(), e.getKey(), dates, source)));
        }
        return new SelectionSchedule(Collections.unmodifiableMap(out));
    }

    /** 그 선정의 그 거래일 판정. 표에 없는 날은 {@code MISSING}. */
    public SelectionDay day(String selectionHash, LocalDate date) {
        NavigableMap<LocalDate, SelectionDay> m = days.get(selectionHash);
        SelectionDay d = m == null ? null : m.get(date);
        return d != null ? d : SelectionDay.invalid(date, SelectionInvalidReason.MISSING);
    }

    /** 그 선정의 날짜별 판정 전체(거래일 오름차순). */
    public NavigableMap<LocalDate, SelectionDay> days(String selectionHash) {
        NavigableMap<LocalDate, SelectionDay> m = days.get(selectionHash);
        return m == null ? Collections.emptyNavigableMap() : m;
    }

    private static NavigableMap<LocalDate, SelectionDay> schedule(
            SelectionProfile selection, String selectionHash, List<LocalDate> dates, SelectionSource source) {
        TreeMap<LocalDate, SelectionDay> out = new TreeMap<>();
        DailySelection.Selected current = null;
        for (LocalDate date : dates) {
            if (current == null || date.isAfter(current.applyTo()) || date.isBefore(current.applyFrom())) {
                DailySelection got;
                try {
                    got = source.select(selection, date);
                } catch (RuntimeException ex) {
                    got = new DailySelection.Unavailable(SelectionInvalidReason.PRODUCER_FAILED,
                            ex.getClass().getSimpleName());
                }
                current = got instanceof DailySelection.Selected s ? s : null;
                if (got == null) {
                    out.put(date, SelectionDay.invalid(date, SelectionInvalidReason.MISSING));
                    continue;
                }
                if (got instanceof DailySelection.Unavailable u) {
                    out.put(date, SelectionDay.invalid(date, u.reason()));
                    continue;
                }
            }
            out.put(date, judge(selection, selectionHash, current, date));
        }
        return out;
    }

    private static SelectionDay judge(
            SelectionProfile selection, String selectionHash, DailySelection.Selected s, LocalDate date) {
        SelectionInvalidReason reason = null;
        if (!HEX64.matcher(s.resultHash()).matches() || s.applyTo().isBefore(s.applyFrom())
                || s.symbols().size() > selection.maxSymbols()
                || (selection.refresh() != SelectionRefresh.POST_CLOSE_WEEKLY && !s.applyFrom().equals(s.applyTo()))) {
            reason = SelectionInvalidReason.MALFORMED;
        } else if (!s.selectionHash().equals(selectionHash)) {
            reason = SelectionInvalidReason.HASH_MISMATCH;
        } else if (date.isBefore(s.applyFrom())) {
            reason = SelectionInvalidReason.NOT_YET;
        } else if (date.isAfter(s.applyTo())) {
            reason = SelectionInvalidReason.EXPIRED;
        }
        return reason == null ? SelectionDay.valid(date, s.resultHash(), s.symbols())
                : SelectionDay.invalid(date, reason);
    }
}
