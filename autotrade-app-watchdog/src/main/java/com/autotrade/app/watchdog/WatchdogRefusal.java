package com.autotrade.app.watchdog;

/**
 * 감시가 뜨지 않기로 판정한 이유(종료 코드 2). 감시 설정 파일이 틀렸거나 이 JVM이 PC CPU·메모리를 줄 수 없을 때처럼 사람이 PC·설정을
 * 고쳐야 하는 경우만 이 예외로 던진다. 알림 채널 꾸리기 같은 코드 실수는 이 예외가 아니어서 진입점이 "설정 오류"로 표시하지 않는다
 * (D-106, PR #144 독립 QA R1).
 *
 * <p>{@link IllegalArgumentException}을 잇는 것은 기존 호출자·시험이 받던 종류를 그대로 두기 위해서다. 나누는 기준은 이 종류이지
 * {@link IllegalArgumentException} 전체가 아니다.
 */
final class WatchdogRefusal extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    static final String SETTINGS_HEADLINE = "감시 설정이 틀려 기동하지 않습니다";
    static final String JVM_HEADLINE = "이 JVM에서는 감시가 기동하지 않습니다";

    private final String headline;

    private WatchdogRefusal(String headline, String detail) {
        super(detail);
        this.headline = headline;
    }

    /** 감시 설정 파일({@code watchdog.properties})을 읽지 못했거나 값이 틀림. */
    static WatchdogRefusal settings(String detail) {
        return new WatchdogRefusal(SETTINGS_HEADLINE, detail);
    }

    /** 이 JVM이 감시에 필요한 값을 줄 수 없음(D-095 4번). */
    static WatchdogRefusal jvm(String detail) {
        return new WatchdogRefusal(JVM_HEADLINE, detail);
    }

    /** 콘솔·로그 첫머리 문구. 운영자가 무엇을 고칠지 고르는 데 쓴다. */
    String headline() {
        return headline;
    }
}
