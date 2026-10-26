package com.autotrade.control;

/**
 * 명령 채널 요청 처리기(실전 엔진 쪽). {@link ControlServer}는 연결마다 다른 스레드에서 이 처리기를 바로 부른다. 명령끼리의 순서,
 * 계좌별 처리, 기한, 대기 상한은 처리기가 정한다(ADR-006 2.2, D-142). 그래서 구현은 모든 명령에 대해 스레드 안전해야 한다.
 */
@FunctionalInterface
public interface ControlHandler {

    /**
     * @param request 엄격하게 읽은 요청
     * @param receivedAtNanos 서버가 연결을 받은 시각({@link System#nanoTime()}). 명령 기한은 이 시각부터 잰다. 요청을 읽는 시간
     *     (최대 {@link ControlServer#READ_TIMEOUT})도 기한 안에 든다
     */
    ControlResponse handle(ControlRequest request, long receivedAtNanos);
}
