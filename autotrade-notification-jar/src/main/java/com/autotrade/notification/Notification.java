package com.autotrade.notification;

import java.time.Instant;
import java.util.Objects;

/**
 * 보낼 알림 한 건. 등급은 보내는 쪽이 고르지 않고 {@link EventRoutes}가 종류로 정한다.
 *
 * @param kind 이벤트 종류({@link EventRoutes}의 상수, 소문자·숫자·{@code -})
 * @param severity 종류로 정해진 등급
 * @param title 한 줄 제목(비어 있으면 안 된다)
 * @param body 본문(비어 있어도 된다). 비밀값을 넣지 않는 것은 부르는 쪽 책임이다
 * @param occurredAt 이벤트 시각(주입된 Clock 기준)
 */
public record Notification(String kind, Severity severity, String title, String body, Instant occurredAt) {

    public Notification {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!EventRoutes.validKind(kind)) {
            throw new IllegalArgumentException("알림 종류는 소문자·숫자·-로 1~40자여야 합니다");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("알림 제목이 비었습니다");
        }
    }

    /** 채널이 보내는 평문: {@code [긴급] 제목} 줄, 본문이 있으면 다음 줄부터. 시각은 UTC ISO-8601. */
    public String plainText() {
        String head = "[" + severity.label() + "] " + title + " (" + occurredAt + ")";
        return body.isBlank() ? head : head + "\n" + body;
    }
}
