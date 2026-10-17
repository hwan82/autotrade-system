package com.autotrade.app.watchdog;

import java.lang.System.Logger;

/**
 * 점검 루프 안의 감시 로그. 출력 실패(로그 핸들러 예외)는 삼킨다(risk-ops PR #155 K1).
 *
 * <p>점검 단계는 상태를 바꾼 뒤 로그를 낸다. 그 로그가 던지면 같은 점검의 남은 일(바뀐 하트비트의 알림, 정지 요청 파일 쓰기, 압력
 * 판정)을 건너뛰고, 상태는 이미 바뀌어 다음 점검에서 바뀜으로 보이지 않으므로 그 알림은 영영 나가지 않는다. 출력이 안 되는 로그로
 * 그 실패를 알릴 수도 없다. 기동 때 로그(설정·채널 안내, 기동 실패)는 여기 대상이 아니다.
 */
final class WatchdogLog {

    private WatchdogLog() {}

    static void log(Logger logger, Logger.Level level, String text) {
        try {
            logger.log(level, text);
        } catch (Throwable ignored) {
            // 위 설명대로 삼킨다
        }
    }
}
