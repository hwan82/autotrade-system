package com.autotrade.app.watchdog;

import com.autotrade.monitor.Heartbeat;
import com.autotrade.monitor.SmallFile;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 상주 프로세스 하트비트 감시(MON-02, 장애표 #8·#9, D-092). 각 프로세스가 쓰는 {@code <설정 폴더>/heartbeat/<이름>.hb}를 읽어
 * 상태를 정하고, 바뀔 때만 로그를 남긴다. 감시는 이 판정으로 어떤 프로세스도 멈추거나 죽이거나 띄우지 않는다(D-058, 자동
 * 재기동은 7단계 후속).
 */
final class HeartbeatWatch {

    private static final Logger LOG = System.getLogger(HeartbeatWatch.class.getName());

    /** 하트비트 상태. */
    enum State {
        /** 기준 시간 안에 쓴 기록이 있음. */
        ALIVE,
        /** 기록이 기준보다 오래됨: 프로세스가 죽었거나(비정상 종료는 파일을 남김) 멈춤. */
        STALE,
        /** 파일 없음: 실행하지 않았거나 정상 종료(정상 종료는 파일을 지운다). */
        ABSENT,
        /** 파일은 있으나 읽거나 해석하지 못함. */
        UNREADABLE
    }

    /** 상태가 바뀐 사건. */
    record Change(String process, State from, State to, Optional<Heartbeat> last, Duration age) {}

    private final Path configDir;
    private final List<String> processes;
    private final Duration stale;
    private final Map<String, State> states = new HashMap<>();
    private final Map<String, Instant> startedAt = new HashMap<>();
    private boolean checkedOnce;
    private boolean firstCheckAllAbsent;

    HeartbeatWatch(Path configDir, List<String> processes, Duration stale) {
        this.configDir = configDir;
        this.processes = List.copyOf(processes);
        this.stale = stale;
    }

    /** 한 번 점검하고 바뀐 것을 돌려준다(첫 점검은 모든 프로세스가 "처음"에서 바뀐 것으로 나온다). */
    List<Change> check(Instant now) {
        List<Change> changes = new ArrayList<>();
        for (String process : processes) {
            Optional<Heartbeat> last = Optional.empty();
            State state;
            Duration age = Duration.ZERO;
            try {
                String text = SmallFile.read(Heartbeat.file(configDir, process));
                last = Heartbeat.parse(text).filter(h -> h.process().equals(process));
                if (last.isEmpty()) {
                    state = State.UNREADABLE;
                } else {
                    // 미래 시각(시계 조정 등)도 차이가 크면 멈춤으로 본다. 그대로 두면 나이가 음수라 늘 살아 있음이 된다
                    age = Duration.between(last.get().writtenAt(), now).abs();
                    state = age.compareTo(stale) > 0 ? State.STALE : State.ALIVE;
                }
            } catch (NoSuchFileException e) {
                state = State.ABSENT;
            } catch (IOException | RuntimeException e) {
                state = State.UNREADABLE;
            }
            if (last.isPresent()) {
                Instant prev = startedAt.put(process, last.get().startedAt());
                if (prev != null && !prev.equals(last.get().startedAt())) {
                    // 점검 사이에 죽고 다시 떴으면 상태는 계속 ALIVE라 바뀜이 없다. 재기동은 따로 알린다(architect PR #116 R4)
                    LOG.log(Level.WARNING, process + " 재기동 감지(이전 시작 " + prev + ", 새 시작 " + last.get().startedAt() + ")");
                }
            }
            State before = states.put(process, state);
            if (before != state) {
                Change c = new Change(process, before, state, last, age);
                changes.add(c);
                log(c);
            }
        }
        if (!checkedOnce) {
            checkedOnce = true;
            firstCheckAllAbsent = !processes.isEmpty() && processes.stream().allMatch(p -> states.get(p) == State.ABSENT);
            if (firstCheckAllAbsent) {
                // 설정 폴더(AUTOTRADE_CONFIG_DIR)를 잘못 적으면 빈 폴더를 지키며 모두 꺼짐으로만 보인다(architect PR #116 N2)
                LOG.log(Level.WARNING, "첫 점검에서 모든 프로세스 하트비트가 없습니다. 아무것도 띄우지 않았다면 정상이고, 띄웠다면 감시의 설정 폴더가 "
                        + "그 프로세스들과 같은지 확인하세요: " + configDir.toAbsolutePath());
            }
        }
        return changes;
    }

    /** 첫 점검에서 모든 프로세스가 꺼짐(파일 없음)이었는지. */
    boolean firstCheckAllAbsent() {
        return firstCheckAllAbsent;
    }

    Map<String, State> states() {
        return Map.copyOf(states);
    }

    private static void log(Change c) {
        String what = c.process() + " 하트비트 " + (c.from() == null ? "처음" : c.from().name()) + " → " + c.to();
        switch (c.to()) {
            case ALIVE -> LOG.log(Level.INFO, what + c.last().map(h -> " (pid " + h.pid() + ", 시작 " + h.startedAt() + ")").orElse(""));
            case ABSENT -> LOG.log(Level.INFO, what + " (실행하지 않았거나 정상 종료)");
            case STALE -> LOG.log("engine".equals(c.process()) ? Level.ERROR : Level.WARNING,
                    what + " (마지막 기록 " + c.age().toSeconds() + "초 전. 비정상 종료나 멈춤"
                            + ("engine".equals(c.process()) ? ", 장애표 #8: 재기동은 사람이 하고 대사 통과 후에만 재개)" : ", 장애표 #9)"));
            case UNREADABLE -> LOG.log(Level.WARNING, what + " (하트비트 파일을 읽지 못함)");
        }
    }
}
