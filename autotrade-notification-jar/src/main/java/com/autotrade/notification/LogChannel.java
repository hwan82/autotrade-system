package com.autotrade.notification;

/**
 * 마지막 대체 채널: 프로세스 로그({@code System.Logger}, 긴급은 ERROR·정보는 INFO). 외부 채널이 모두 실패하거나 하나도 설정되지
 * 않아도 알림이 어디에도 남지 않는 일은 없게 한다. 사람이 로그를 봐야 알 수 있으므로 "전달됨"으로 치지 않는다
 * ({@link DispatchReport#delivered()}는 외부 채널 성공만 센다).
 */
public final class LogChannel implements Channel {

    /** 로그 채널 이름. 다른 채널은 쓸 수 없다({@link Notifier}). */
    public static final String NAME = "log";

    private final System.Logger log;

    public LogChannel() {
        this(System.getLogger("com.autotrade.notification"));
    }

    LogChannel(System.Logger log) {
        this.log = log;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SendResult send(Notification n) {
        System.Logger.Level level = n.severity() == Severity.URGENT ? System.Logger.Level.ERROR : System.Logger.Level.INFO;
        log.log(level, "알림(" + n.kind() + ") " + n.plainText());
        return SendResult.ok();
    }

    @Override
    public String toString() {
        return "LogChannel";
    }
}
