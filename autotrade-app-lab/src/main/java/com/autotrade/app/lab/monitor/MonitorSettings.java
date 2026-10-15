package com.autotrade.app.lab.monitor;

import com.autotrade.monitor.Threshold;
import com.autotrade.monitor.Threshold.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 실험실 모니터링 설정(MON-05, D-090). 실험실 프로세스 기동 속성 {@code autotrade.lab.monitor.*}에서 읽는다. 값이 범위 밖이거나
 * 형식이 틀리면 실험실이 뜨지 않는다(잘못 쓴 임계를 모르고 지나치지 않게).
 *
 * @param enabled 꺼 두면 수집하지 않고 화면은 꺼짐을 보여 준다
 * @param intervalSeconds 수집 주기(초, 1~3600)
 * @param historySize 화면 그래프에 들고 있을 표본 수(1~10000)
 * @param disks 지표를 낼 드라이브(마운트) 목록. 비면 모든 로컬 드라이브
 * @param thresholds 기본 임계에 덮어쓸 임계
 */
public record MonitorSettings(boolean enabled, int intervalSeconds, int historySize, Set<String> disks,
        List<Threshold> thresholds) {

    public static final int MAX_INTERVAL_SECONDS = 3600;
    public static final int MAX_HISTORY = 10_000;
    /** 최근 임계 이벤트 보관 수. */
    public static final int EVENT_HISTORY = 200;

    public MonitorSettings {
        if (intervalSeconds < 1 || intervalSeconds > MAX_INTERVAL_SECONDS) {
            throw new IllegalArgumentException(
                    "수집 주기(autotrade.lab.monitor.interval-seconds)는 1~" + MAX_INTERVAL_SECONDS + "초여야 합니다: " + intervalSeconds);
        }
        if (historySize < 1 || historySize > MAX_HISTORY) {
            throw new IllegalArgumentException(
                    "기록 수(autotrade.lab.monitor.history-size)는 1~" + MAX_HISTORY + "여야 합니다: " + historySize);
        }
        disks = Set.copyOf(disks);
        thresholds = List.copyOf(thresholds);
    }

    /** 드라이브 고르기: 목록이 비면 모두, 아니면 대소문자를 무시하고 같은 마운트만. */
    public Predicate<String> diskFilter() {
        if (disks.isEmpty()) {
            return mount -> true;
        }
        Set<String> lower = Set.copyOf(disks.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList());
        return mount -> lower.contains(mount.toLowerCase(Locale.ROOT));
    }

    /** 쉼표로 나눈 드라이브 목록(빈 칸 무시). */
    public static Set<String> parseDisks(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(List.of(text.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    /**
     * 임계 덮어쓰기 문자열: {@code 지표=방향:경고:위험:연속}을 {@code ;}로 잇는다. 예:
     * {@code cpu.system=ABOVE:70:90:3;disk.free-gb=BELOW:100:30:1}. 방향은 ABOVE(높을수록 나쁨)·BELOW(낮을수록 나쁨).
     * 지표 이름이 {@code known}에 없으면 거절한다(오타면 아무 효과 없는 새 임계가 되고 기본 임계는 그대로 남는다, architect
     * PR #114 R2).
     */
    public static List<Threshold> parseThresholds(String text, Set<String> known) {
        List<Threshold> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String item : text.split(";")) {
            String entry = item.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            String[] parts = eq < 0 ? new String[0] : entry.substring(eq + 1).split(":");
            if (eq <= 0 || parts.length != 4) {
                throw new IllegalArgumentException("임계 형식은 지표=방향:경고:위험:연속입니다(autotrade.lab.monitor.thresholds): " + entry);
            }
            String metric = entry.substring(0, eq).trim();
            if (!known.contains(metric)) {
                throw new IllegalArgumentException("모르는 지표 이름입니다(autotrade.lab.monitor.thresholds): " + metric
                        + " — 쓸 수 있는 이름: " + String.join(", ", new java.util.TreeSet<>(known)));
            }
            try {
                out.add(new Threshold(metric, Direction.valueOf(parts[0].trim().toUpperCase(Locale.ROOT)),
                        Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()),
                        Integer.parseInt(parts[3].trim())));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("임계를 읽지 못했습니다(autotrade.lab.monitor.thresholds): " + entry + " — "
                        + e.getMessage(), e);
            }
        }
        if (out.stream().map(Threshold::metric).distinct().count() != out.size()) {
            throw new IllegalArgumentException("같은 지표의 임계가 두 번 있습니다(autotrade.lab.monitor.thresholds)");
        }
        return out;
    }
}
