package com.autotrade.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 지표 값을 임계와 비교해 수준을 유지하고, 같은 판정이 {@link Threshold#sustain()}번 이어지면 수준을 바꾸며 이벤트를 낸다.
 * 대상별(예: 디스크 드라이브마다)로 따로 센다. 한 스레드(수집 스레드)에서만 부른다.
 *
 * <p>수준은 한 번에 판정 수준으로 옮긴다(OK에서 바로 CRITICAL로 갈 수 있다). 판정이 중간에 바뀌면(예: WARN 판정 두 번 뒤
 * CRITICAL 판정) 연속 횟수를 새로 센다. 이번 주기에 값이 없는 대상은 수준과 연속 횟수를 그대로 둔다.
 */
public final class ThresholdEvaluator {

    private final Map<String, Threshold> byMetric;
    private final Map<String, State> states = new HashMap<>();

    public ThresholdEvaluator(List<Threshold> thresholds) {
        Map<String, Threshold> map = new HashMap<>();
        for (Threshold t : thresholds) {
            if (map.put(t.metric(), t) != null) {
                throw new IllegalArgumentException("같은 지표의 임계가 두 번 있습니다: " + t.metric());
            }
        }
        this.byMetric = Map.copyOf(map);
    }

    /** 이번 주기 값들을 반영하고 생긴 이벤트를 돌려준다(대상 이름순). */
    public List<ThresholdEvent> evaluate(Instant at, List<Reading> readings) {
        List<ThresholdEvent> events = new ArrayList<>();
        for (Reading r : readings) {
            Threshold t = byMetric.get(r.metric());
            if (t == null) {
                continue;
            }
            State s = states.computeIfAbsent(r.name(), k -> new State());
            Level judged = t.levelOf(r.value());
            if (judged == s.level) {
                s.pending = null;
                s.count = 0;
                continue;
            }
            if (judged == s.pending) {
                s.count++;
            } else {
                s.pending = judged;
                s.count = 1;
            }
            if (s.count >= t.sustain()) {
                Level from = s.level;
                s.level = judged;
                s.pending = null;
                s.count = 0;
                // 넘은 경계: 나빠질 때는 새 수준의 경계, 좋아질 때는 떠난 수준의 경계
                double limit = t.limitOf(judged.compareTo(from) > 0 ? judged : from);
                events.add(new ThresholdEvent(at, r.name(), from, judged, r.value(), limit, r.unit()));
            }
        }
        events.sort((a, b) -> a.reading().compareTo(b.reading()));
        return events;
    }

    /** 임계가 있는 대상의 현재 수준(이름순). */
    public Map<String, Level> levels() {
        Map<String, Level> out = new TreeMap<>();
        states.forEach((name, s) -> out.put(name, s.level));
        return out;
    }

    /** 임계 목록(지표 이름순). */
    public List<Threshold> thresholds() {
        List<Threshold> out = new ArrayList<>(byMetric.values());
        out.sort((a, b) -> a.metric().compareTo(b.metric()));
        return out;
    }

    private static final class State {
        Level level = Level.OK;
        Level pending;
        int count;
    }
}
