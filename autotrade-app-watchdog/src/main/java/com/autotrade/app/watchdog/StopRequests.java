package com.autotrade.app.watchdog;

import com.autotrade.monitor.AtomicFile;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 단발 일괄 명령 정지 요청 파일 관리(broker-and-fault-design 5.2 0번, D-058 ④, D-092). 감시가 할 수 있는 일은 파일을 만들고,
 * 확인 시각을 갱신하고, 지우는 것뿐이다. 프로세스를 죽이지 않는다(ArchUnit {@code WATCHDOG_KILLS_NO_PROCESS}).
 *
 * <ul>
 *   <li><b>켜기</b>: 자원이 위험 수준이면 켠다. 켜져 있는 동안은 점검마다 모든 파일을 다시 쓴다(없으면 만들고, 있으면
 *       {@code confirmed-at}과 수정 시각을 갱신). 일괄 명령의 거부 메시지가 이 파일의 수정 시각을 보여 주므로, 그 시각이 점검
 *       주기보다 많이 오래됐으면 감시가 멈춘 채 파일이 남은 것이다(장애표 #10 감지, 감시 생존 확인).</li>
 *   <li><b>끄기</b>: 모든 값이 경고 경계 위(정상)로 해제 유지 시간 동안 이어지면 파일을 지운다(ADR-007 2.7 해제 기준. 기본 임계에서
 *       CPU·메모리 경고 경계는 위험 경계 −10%p와 같다). 경고 수준이거나 판단 못 하면 유지 시간을 처음부터 다시 센다.</li>
 *   <li><b>남은 파일</b>: 감시가 뜰 때 파일이 이미 있으면(감시가 죽은 채 남은 파일) 켜진 상태로 이어받는다. 자원이 정상이면
 *       해제 유지 시간 뒤 지운다(D-058: 남은 파일은 감시를 고쳐 거두게 하고 사람이 지우지 않는다).</li>
 * </ul>
 */
final class StopRequests {

    private static final Logger LOG = System.getLogger(StopRequests.class.getName());

    /** 수집기 정지 요청 파일(D-058, ADR-008 2.7). */
    static final String COLLECTOR_STOP_REQUEST = "collector-state/stop-request";
    /** 실험 실행 명령·리플레이 점검·실험실 실시간 모의 세션 정지 요청 파일(D-063, D-085, D-076). */
    static final String EXPERIMENT_STOP_REQUEST = "experiment-state/stop-request";

    /** 이번 점검의 자원 판정. */
    enum Pressure {
        /** 하나라도 위험 수준. */
        CRITICAL,
        /** 위험은 없고 경고 수준이 있음. */
        ELEVATED,
        /** 모든 값이 정상. */
        CLEAR,
        /** 값이 빠져 판단 못 함(위험 값이 없을 때). */
        UNKNOWN
    }

    private final List<Path> files;
    private final Duration hold;
    private boolean active;
    private Instant activeSince;
    private String reason = "";
    private Instant clearSince;
    private boolean writeFailing;
    private boolean deleteFailing;

    StopRequests(Path configDir, Duration hold) {
        this(List.of(configDir.resolve(COLLECTOR_STOP_REQUEST), configDir.resolve(EXPERIMENT_STOP_REQUEST)), hold);
    }

    StopRequests(List<Path> files, Duration hold) {
        this.files = List.copyOf(files);
        this.hold = hold;
    }

    /** 감시가 뜰 때 한 번: 남은 파일이 있으면 켜진 상태로 이어받는다. */
    void adoptLeftovers(Instant now) {
        for (Path f : files) {
            if (Files.exists(f)) {
                active = true;
                activeSince = now;
                reason = "감시 기동 때 남아 있던 정지 요청 파일";
                LOG.log(Level.WARNING, "남은 정지 요청 파일을 이어받습니다(자원이 정상으로 " + hold.toSeconds() + "초 이어지면 지움): " + f);
            }
        }
    }

    /** 한 점검을 반영한다. */
    void update(Instant now, Pressure pressure, String detail) {
        switch (pressure) {
            case CRITICAL -> {
                clearSince = null;
                if (!active) {
                    active = true;
                    activeSince = now;
                    LOG.log(Level.WARNING, "자원 위험: 단발 일괄 명령 정지 요청 파일을 만듭니다(5.2 0번): " + detail);
                }
                reason = detail;
            }
            case ELEVATED, UNKNOWN -> clearSince = null;
            case CLEAR -> {
                if (clearSince == null) {
                    clearSince = now;
                }
                if (active && Duration.between(clearSince, now).compareTo(hold) >= 0) {
                    release();
                }
            }
        }
        if (active) {
            refresh(now);
        }
    }

    boolean active() {
        return active;
    }

    List<Path> files() {
        return files;
    }

    private void refresh(Instant now) {
        String content = "requested-at=" + activeSince + "\nconfirmed-at=" + now + "\nreason=" + reason.replace('\n', ' ') + "\n";
        boolean ok = true;
        for (Path f : files) {
            try {
                AtomicFile.write(f, content);
            } catch (IOException | RuntimeException e) {
                ok = false;
                if (!writeFailing) {
                    LOG.log(Level.ERROR, "정지 요청 파일을 쓰지 못했습니다(복구될 때까지 다시 알리지 않음): " + f + " ("
                            + e.getClass().getSimpleName() + ")");
                }
            }
        }
        if (ok && writeFailing) {
            LOG.log(Level.INFO, "정지 요청 파일 쓰기 복구");
        }
        writeFailing = !ok;
    }

    private void release() {
        boolean ok = true;
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException | RuntimeException e) {
                ok = false;
                if (!deleteFailing) {
                    LOG.log(Level.ERROR, "정지 요청 파일을 지우지 못했습니다(다음 점검에 다시 시도): " + f);
                }
            }
        }
        deleteFailing = !ok;
        if (ok) {
            active = false;
            activeSince = null;
            reason = "";
            LOG.log(Level.INFO, "자원 정상이 " + hold.toSeconds() + "초 이어져 정지 요청 파일을 지웠습니다");
        }
    }
}
