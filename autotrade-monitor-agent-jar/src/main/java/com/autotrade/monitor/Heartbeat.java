package com.autotrade.monitor;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * 프로세스 하트비트 한 건(MON-02). 각 상주 프로세스가 {@code <설정 폴더>/heartbeat/<프로세스>.hb}에 주기마다 덮어쓰고, 감시
 * 프로세스가 읽어 {@code writtenAt}이 오래됐는지 본다. 파일은 {@code 키=값} 다섯 줄이다(외부 의존 없이 읽고 쓰기 위해).
 *
 * <p>하트비트는 그 JVM과 하트비트 스레드가 돈다는 증거일 뿐 프로세스의 업무 스레드가 정상이라는 증거는 아니다(D-092).
 *
 * @param process 프로세스 이름(engine, gateway, lab, watchdog)
 * @param pid 쓴 프로세스의 PID(표시용. 감시는 이 값으로 아무것도 하지 않는다, D-058)
 * @param startedAt 하트비트를 시작한 시각(재기동 구분용)
 * @param writtenAt 이 기록을 쓴 시각
 * @param seq 시작 뒤 몇 번째 기록인지(1부터)
 */
public record Heartbeat(String process, long pid, Instant startedAt, Instant writtenAt, long seq) {

    /** 설정 폴더 아래 하트비트 폴더 이름. */
    public static final String DIR = "heartbeat";
    public static final String SUFFIX = ".hb";

    public Heartbeat {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(writtenAt, "writtenAt");
        if (!validName(process)) {
            throw new IllegalArgumentException("프로세스 이름은 소문자·숫자·하이픈만 씁니다: " + process);
        }
    }

    /** 프로세스의 하트비트 파일 위치. */
    public static Path file(Path configDir, String process) {
        if (!validName(process)) {
            throw new IllegalArgumentException("프로세스 이름은 소문자·숫자·하이픈만 씁니다: " + process);
        }
        return configDir.resolve(DIR).resolve(process + SUFFIX);
    }

    /** 파일 내용. */
    public String format() {
        return "process=" + process + "\npid=" + pid + "\nstarted-at=" + startedAt + "\nwritten-at=" + writtenAt + "\nseq=" + seq
                + "\n";
    }

    /** 파일 내용을 읽는다. 형식이 틀리면 빈 값(감시는 "읽을 수 없음"으로 본다). */
    public static Optional<Heartbeat> parse(String text) {
        String process = null;
        String pid = null;
        String started = null;
        String written = null;
        String seq = null;
        for (String line : text.split("\n")) {
            String l = line.strip();
            if (l.isEmpty()) {
                continue;
            }
            int eq = l.indexOf('=');
            if (eq <= 0) {
                return Optional.empty();
            }
            String value = l.substring(eq + 1);
            switch (l.substring(0, eq)) {
                case "process" -> process = value;
                case "pid" -> pid = value;
                case "started-at" -> started = value;
                case "written-at" -> written = value;
                case "seq" -> seq = value;
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (process == null || pid == null || started == null || written == null || seq == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Heartbeat(process, Long.parseLong(pid), Instant.parse(started), Instant.parse(written),
                    Long.parseLong(seq)));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            // 숫자 형식 오류(NumberFormatException)와 이름 규칙 위반을 함께 받는다
            return Optional.empty();
        }
    }

    static boolean validName(String name) {
        if (name.isEmpty() || name.length() > 32) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
