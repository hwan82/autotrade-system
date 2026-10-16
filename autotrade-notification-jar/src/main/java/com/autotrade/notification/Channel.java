package com.autotrade.notification;

/** 알림 채널 하나. 구현은 비밀값을 결과·예외 문구·{@code toString}에 남기지 않는다. */
public interface Channel {

    /** 결과 보고에 쓰는 이름(예: {@code slack}). */
    String name();

    /**
     * 알림을 보낸다. 실패는 예외 대신 {@link SendResult#failed}로 돌려주는 것이 원칙이다. 런타임 예외가 나와도
     * {@link Notifier}가 잡아 실패로 친다.
     *
     * @throws InterruptedException 보내는 중 스레드가 중단되면
     */
    SendResult send(Notification notification) throws InterruptedException;
}
