package com.autotrade.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 보낼 알림 한 건. 등급은 보내는 쪽이 고르지 않고 {@link EventRoutes}가 종류로 정한다.
 *
 * @param kind 이벤트 종류({@link EventRoutes}의 상수, 소문자·숫자·{@code -})
 * @param severity 종류로 정해진 등급
 * @param title 한 줄 제목(비어 있으면 안 된다)
 * @param body 본문(비어 있어도 된다). 비밀값을 넣지 않는 것은 부르는 쪽 책임이다
 * @param occurredAt 이벤트 시각(주입된 Clock 기준)
 * @param zone 사람이 읽는 시각을 보일 시간대(계산에는 쓰지 않는다, PR #127 architect R3). 실전 엔진·감시는 Asia/Seoul
 */
public record Notification(String kind, Severity severity, String title, String body, Instant occurredAt, ZoneId zone) {

    public Notification {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(zone, "zone");
        if (!EventRoutes.validKind(kind)) {
            throw new IllegalArgumentException("알림 종류는 소문자·숫자·-로 1~40자여야 합니다");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("알림 제목이 비었습니다");
        }
    }

    /** UTC로 시각을 보이는 알림. */
    public Notification(String kind, Severity severity, String title, String body, Instant occurredAt) {
        this(kind, severity, title, body, occurredAt, ZoneOffset.UTC);
    }

    /**
     * 채널이 보내는 평문: {@code [긴급] 제목 (시각)} 줄, 본문이 있으면 다음 줄부터. 시각은 {@code zone}의 ISO-8601(초까지, 오프셋 포함,
     * 예: {@code 2026-10-16T09:00:00+09:00}, UTC는 {@code ...Z}).
     */
    public String plainText() {
        String at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                occurredAt.truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime());
        String head = "[" + severity.label() + "] " + title + " (" + at + ")";
        return body.isBlank() ? head : head + "\n" + body;
    }
}
