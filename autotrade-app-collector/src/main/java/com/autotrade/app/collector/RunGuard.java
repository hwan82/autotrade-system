package com.autotrade.app.collector;

import com.autotrade.marketdata.calendar.CalendarCoverageException;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.calendar.TradingSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 실전 엔진 PC 자원 보호(broker-and-fault-design 5.1·5.2, risk-ops 검토 PR #39 F1·F2, D-058). 감시 프로세스는 어떤 프로세스도
 * 죽이지 않으므로 수집기가 스스로 물러난다.
 *
 * <ul>
 *   <li><b>정지 요청</b>(5.2 0번, D-058): 감시 프로세스가 자원 임계치에서 {@code <설정 폴더>/collector-state/stop-request}를
 *       만들면 시작하지 않고, 실행 중이면 다음 호출 전·하루 읽을 때마다 멈춘다. {@code --allow-during-session}으로도 풀리지 않는다.
 *       수집기는 이 파일을 지우지 않는다(감시만 지움, ADR-007 2.7과 같은 규칙).</li>
 *   <li><b>세션 시간대 거부</b>: 국내·미국 거래일의 정규장 {@value #BEFORE_OPEN_MINUTES}분 전부터 폐장
 *       {@value #AFTER_CLOSE_MINUTES}분 뒤까지는 시작하지 않고, 실행 중 그 구간에 들어서면 다음 호출 전에 멈춘다. 수집기는
 *       Guard 상태를 읽지 못하므로 실전 계좌가 켜졌는지와 상관없이 시간으로 막는다. 캘린더 수록 범위 밖이면 판단할 수 없어 막는다.
 *       {@code --allow-during-session}으로만 푼다(실전 엔진을 끈 날).</li>
 *   <li><b>디스크 여유</b>: 캐시·스냅샷 폴더 디스크의 여유가 {@code --min-free-gb} 미만이면 시작하지 않고, 스냅샷을 쓰기 전에도
 *       다시 본다. 실전 엔진의 상태 기록·DB 쓰기가 같은 디스크를 쓸 수 있다(장애표 #8 재기동 복구 전제).</li>
 * </ul>
 */
final class RunGuard {

    static final int BEFORE_OPEN_MINUTES = 60;
    static final int AFTER_CLOSE_MINUTES = 30;
    static final long DEFAULT_MIN_FREE_GB = 20;

    /** 디스크 여유 바이트. 테스트는 고정값을 넣는다. */
    @FunctionalInterface
    interface FreeSpace {
        long usableBytes(Path existingDir) throws IOException;
    }

    static final FreeSpace FILE_STORE = dir -> Files.getFileStore(dir).getUsableSpace();

    private final Clock clock;
    private final List<ExchangeCalendar> calendars;
    private final boolean allowDuringSession;
    private final FreeSpace freeSpace;
    private final long minFreeBytes;
    private final Path stopRequest;

    /** {@code stopRequest}가 null이면 정지 요청을 보지 않는다(테스트 전용, 운영 경로는 {@link CollectorMain}이 항상 넘긴다). */
    RunGuard(Clock clock, List<ExchangeCalendar> calendars, boolean allowDuringSession, FreeSpace freeSpace, long minFreeGb,
            Path stopRequest) {
        this.stopRequest = stopRequest;
        this.clock = clock;
        this.calendars = List.copyOf(calendars);
        this.allowDuringSession = allowDuringSession;
        this.freeSpace = freeSpace;
        this.minFreeBytes = minFreeGb * 1024L * 1024L * 1024L;
    }

    /** 정지 요청 파일이 있거나 지금 어느 시장 세션 보호 구간이면 {@link Refused}. 실행 중 반복해서 부른다. */
    void checkSession() {
        if (stopRequest != null && !Files.notExists(stopRequest)) { // 있는지 판단할 수 없으면(권한 오류 등) 멈추는 쪽
            throw new Refused("감시 프로세스가 자원 부족으로 정지를 요청해 멈춥니다(" + stopRequest + ", 만든 시각 " + modified(stopRequest)
                    + "). 받은 자료는 캐시에 남았고, 자원이 회복돼 감시가 요청을 거두면 다시 실행할 수 있습니다."
                    + " 오래 남아 있으면 감시 프로세스가 살아 있는지 먼저 확인하세요(runbook 3.5)");
        }
        if (allowDuringSession) {
            return;
        }
        Instant now = clock.instant();
        for (ExchangeCalendar cal : calendars) {
            LocalDate local = now.atZone(cal.zone()).toLocalDate();
            for (LocalDate d : List.of(local.minusDays(1), local, local.plusDays(1))) {
                Optional<TradingSession> s;
                try {
                    s = cal.session(d);
                } catch (CalendarCoverageException e) {
                    throw new Refused(cal.market() + " 캘린더 수록 범위 밖(" + d + ")이라 장 시간인지 판단할 수 없어 멈춥니다."
                            + " 캘린더를 갱신하거나, 실전 엔진을 끈 날에만 --allow-during-session으로 실행하세요");
                }
                if (s.isPresent()) {
                    Instant from = s.get().openInstant().minus(Duration.ofMinutes(BEFORE_OPEN_MINUTES));
                    Instant to = s.get().closeInstant().plus(Duration.ofMinutes(AFTER_CLOSE_MINUTES));
                    if (!now.isBefore(from) && now.isBefore(to)) {
                        throw new Refused(cal.market() + " 장 시간 보호 구간(" + from.atZone(cal.zone()).toLocalTime() + "~"
                                + to.atZone(cal.zone()).toLocalTime() + " " + cal.zone() + ")이라 수집을 멈춥니다."
                                + " 받은 자료는 캐시에 남았고 구간이 끝난 뒤 다시 실행하면 이어 받습니다");
                    }
                }
            }
        }
    }

    private static String modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant().toString();
        } catch (IOException e) {
            return "알 수 없음";
        }
    }

    /** 폴더(없으면 가장 가까운 있는 상위 폴더) 디스크 여유가 기준 미만이면 {@link Refused}. */
    void checkDisk(List<Path> dirs) throws IOException {
        for (Path dir : dirs) {
            Path p = dir.toAbsolutePath();
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            if (p == null) {
                continue;
            }
            long free = freeSpace.usableBytes(p);
            if (free < minFreeBytes) {
                throw new Refused("디스크 여유 " + (free / (1024L * 1024L * 1024L)) + "GB가 기준 " + (minFreeBytes / (1024L * 1024L * 1024L))
                        + "GB 미만이라 멈춥니다(" + p + "). 옛 스냅샷을 정리하세요(runbook 5장)");
            }
        }
    }

    /** 실전 PC 자원 보호로 멈춤. 종료 코드 4. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }
}
