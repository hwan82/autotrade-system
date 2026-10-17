package com.autotrade.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Slack Incoming Webhook 채널(무료). 본문 {@code {"text": ...}}를 JSON으로 POST하고 HTTP 200·본문 {@code ok}를 성공으로 본다
 * ([사실] Slack 공식 SDK 문서의 Webhook 예: 성공 응답은 200, 본문 "ok"). 재시도는 하지 않는다: 실패하면 {@link Notifier}가
 * 다음 채널로 넘긴다.
 *
 * <p>Webhook URL은 경로 자체가 비밀이다. {@code https://hooks.slack.com/}로 시작하는 URL만 받아 오타·잘못된 붙여넣기로 알림이
 * 다른 호스트로 새지 않게 한다. URL은 결과·예외 문구·{@code toString}에 나오지 않는다.
 */
public final class SlackWebhookChannel implements Channel {

    static final String HOST = "hooks.slack.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI webhook;
    private final HttpTransport http;

    private SlackWebhookChannel(URI webhook, HttpTransport http) {
        this.webhook = webhook;
        this.http = http;
    }

    /**
     * @param webhookUrl 설정의 비밀값 원문
     * @throws IllegalArgumentException https·{@value #HOST} URL이 아니면(문구에 URL을 넣지 않는다)
     */
    public static SlackWebhookChannel of(String webhookUrl, HttpTransport http) {
        Objects.requireNonNull(webhookUrl, "webhookUrl");
        Objects.requireNonNull(http, "http");
        URI uri;
        try {
            uri = URI.create(webhookUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Slack Webhook URL 형식이 틀렸습니다");
        }
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawPath() == null || !uri.getRawPath().startsWith("/services/")) {
            throw new IllegalArgumentException("Slack Webhook URL은 https://" + HOST + "/services/로 시작해야 합니다");
        }
        return new SlackWebhookChannel(uri, http);
    }

    @Override
    public String name() {
        return NotificationSetup.SLACK;
    }

    @Override
    public SendResult send(Notification notification) throws InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("text", escape(notification.plainText()));
        HttpTransport.HttpResult r;
        try {
            r = http.post(webhook, Map.of("Content-Type", "application/json; charset=utf-8"), payload.toString());
        } catch (IOException e) {
            return SendResult.failed(Failures.of(e));
        }
        if (r.status() == 200 && "ok".equals(r.body().strip())) {
            return SendResult.ok();
        }
        return SendResult.failed("http-" + r.status());
    }

    /** Slack 메시지 서식 문자 {@code & < >}를 바꾼다(링크·멘션으로 해석되지 않게). */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public String toString() {
        return "SlackWebhookChannel[webhook=****]";
    }
}
