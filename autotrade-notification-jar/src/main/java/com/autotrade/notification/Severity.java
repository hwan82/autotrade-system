package com.autotrade.notification;

/** 알림 등급. 등급이 채널 순서를 정한다({@link Notifier}). */
public enum Severity {
    /** 바로 봐야 하는 알림(오류, 손절, 한도, 강제 종료). Slack이 먼저다. */
    URGENT("긴급"),
    /** 기록용 알림(익절, 장 시작 예수금, 장 마감 결산). 카카오톡이 먼저다. */
    INFO("정보");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    /** 메시지 앞에 붙이는 한국어 표시. */
    public String label() {
        return label;
    }
}
