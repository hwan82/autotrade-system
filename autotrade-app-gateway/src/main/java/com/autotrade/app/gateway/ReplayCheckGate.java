package com.autotrade.app.gateway;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.calendar.SessionProtection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 리플레이 점검({@link ReplayCheck})의 정지 확인(D-085, Q-050). 실험 실행 명령의 관문(D-063 {@code ExperimentGate})과 같은 규칙이다.
 * 게이트웨이 모듈은 실험 모듈·sim을 의존할 수 없어(빌드 격리) 같은 판정({@link SessionProtection})과 같은 파일 위치를 여기서 쓴다.
 *
 * <ul>
 *   <li><b>정지 요청</b>: 감시 프로세스가 자원 임계치에서 만드는 실험 정지 요청 파일 {@code <설정 폴더>/experiment-state/stop-request}가
 *       있으면 멈춘다. {@code -PallowDuringSession}으로도 풀리지 않는다. 있는지 판단할 수 없으면(권한 오류 등) 멈추는 쪽이다.
 *       이 명령은 파일을 지우지 않는다(감시만 지움, D-058).</li>
 *   <li><b>장 시간 보호</b>: 국내·미국 거래일 정규장 {@value SessionProtection#BEFORE_OPEN_MINUTES}분 전부터 폐장
 *       {@value SessionProtection#AFTER_CLOSE_MINUTES}분 뒤까지 멈춘다(수집기·실험 명령과 같은 구간). 캘린더 수록 범위 밖이면
 *       판단할 수 없어 멈춘다. {@code -PallowDuringSession}으로만 푼다(실전 엔진을 끈 날).</li>
 * </ul>
 */
final class ReplayCheckGate implements ReplayCheck.StopCheck {

    /** 감시가 만드는 실험 정지 요청 파일의 설정 폴더 안 위치. 실험 명령(D-063)·실험실 실시간 모의 세션(D-076)과 같은 파일이다. */
    static final String STOP_REQUEST = "experiment-state/stop-request";

    private final Clock clock;
    private final List<ExchangeCalendar> calendars;
    private final boolean allowDuringSession;
    private final Path stopRequest;

    ReplayCheckGate(Clock clock, List<ExchangeCalendar> calendars, boolean allowDuringSession, Path stopRequest) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.calendars = List.copyOf(calendars);
        this.allowDuringSession = allowDuringSession;
        this.stopRequest = Objects.requireNonNull(stopRequest, "stopRequest");
    }

    /** 국내·미국 번들 캘린더와 설정 폴더의 정지 요청 파일로 만든다. */
    static ReplayCheckGate of(Clock clock, Path configDir, boolean allowDuringSession) {
        return new ReplayCheckGate(clock, List.of(ExchangeCalendar.bundled(Market.KRX), ExchangeCalendar.bundled(Market.US)),
                allowDuringSession, configDir.resolve(STOP_REQUEST));
    }

    /** 확인하는 정지 요청 파일. 감시가 만드는 위치와 같아야 한다(시작 때 출력). */
    Path stopRequest() {
        return stopRequest;
    }

    @Override
    public Optional<String> stopReason() {
        if (!Files.notExists(stopRequest)) { // 있는지 판단할 수 없으면 멈추는 쪽
            return Optional.of("감시 프로세스가 자원 부족으로 정지를 요청했습니다(" + stopRequest + ", 만든 시각 "
                    + modified(stopRequest) + "). 자원이 회복돼 감시가 요청을 거두면 다시 실행하세요."
                    + " 오래 남아 있으면 감시 프로세스가 살아 있는지 먼저 확인하세요(파일은 감시만 지웁니다)");
        }
        if (allowDuringSession) {
            return Optional.empty();
        }
        return switch (SessionProtection.check(clock.instant(), calendars)) {
            case SessionProtection.Clear c -> Optional.empty();
            case SessionProtection.Unknown u -> Optional.of(u.market() + " 캘린더 수록 범위 밖(" + u.date()
                    + ")이라 장 시간인지 판단할 수 없습니다. 캘린더를 갱신하거나, 실전 엔진을 끈 날에만 -PallowDuringSession으로"
                    + " 실행하세요");
            case SessionProtection.Inside in -> Optional.of(in.market() + " 장 시간 보호 구간(" + in.localRange()
                    + ")입니다. 실전 엔진이 같은 PC에서 돌 수 있어 리플레이 점검은 구간 밖에서 돌립니다."
                    + " 실전 엔진을 끈 날에만 -PallowDuringSession으로 실행하세요");
        };
    }

    private static String modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant().toString();
        } catch (IOException e) {
            return "알 수 없음";
        }
    }
}
