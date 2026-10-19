package com.autotrade.app.watchdog;

/**
 * 감시가 알림을 넣는 곳(D-100). 운영은 알림 큐({@code NotificationQueue#offer})이고, 막지 않고 던지지 않는다. 등급은 종류로
 * 알림 매핑표가 정한다. 시험은 넣은 알림을 모은다.
 */
@FunctionalInterface
interface Alerts {

    /**
     * @param kind 알림 종류({@code EventRoutes} 상수)
     * @param subject 폭주 억제 대상(프로세스 이름 등)
     * @return 받았으면 true, 억제·상한·큐 가득·닫힘으로 버렸으면 false({@code NotificationQueue#offer}와 같다)
     */
    boolean send(String kind, String subject, String title, String body);

    /** 아무 데도 보내지 않음(로그만 쓰는 시험). 받은 것으로 친다. */
    Alerts NONE = (kind, subject, title, body) -> true;
}
