package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlackWebhookChannelTest {

    /** 자리표시자(실제 Webhook 아님). */
    private static final String URL = "https://hooks.slack.com/services/TPLACEHOLDER/BPLACEHOLDER/SECRETPART";
    private static final Notification N = EventRoutes.defaults()
            .notification(EventRoutes.ERROR, "주문 <조회> 실패 & 재시도 없음", "", Instant.parse("2026-10-16T00:00:00Z"));

    @Test
    void Webhook에_text_JSON을_보내고_200_ok면_성공이다() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, "ok");
        SendResult r = SlackWebhookChannel.of(URL, http).send(N);

        assertThat(r).isEqualTo(SendResult.ok());
        assertThat(http.requests).singleElement().satisfies(q -> {
            assertThat(q.uri()).isEqualTo(URI.create(URL));
            assertThat(q.headers()).containsEntry("Content-Type", "application/json; charset=utf-8");
            assertThat(q.body()).isEqualTo(
                    "{\"text\":\"[긴급] 주문 &lt;조회&gt; 실패 &amp; 재시도 없음 (2026-10-16T00:00:00Z)\"}");
        });
    }

    @Test
    void 실패는_짧은_코드로만_돌려주고_비밀값을_담지_않는다() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(500, "error " + URL)
                .reply(404, "no_service")
                .reply(200, "invalid_payload")
                .fail(new IOException("connect failed " + URL))
                .fail(new HttpTimeoutException("timed out " + URL));
        SlackWebhookChannel slack = SlackWebhookChannel.of(URL, http);

        assertThat(slack.send(N)).isEqualTo(SendResult.failed("http-500"));
        assertThat(slack.send(N)).isEqualTo(SendResult.failed("http-404"));
        assertThat(slack.send(N)).isEqualTo(SendResult.failed("http-200"));
        assertThat(slack.send(N)).isEqualTo(SendResult.failed("io-error"));
        assertThat(slack.send(N)).isEqualTo(SendResult.failed("timeout"));
        assertThat(slack.toString()).doesNotContain("SECRETPART").doesNotContain("hooks.slack.com/services");
    }

    @Test
    void hooks_slack_com_https가_아닌_URL은_URL을_문구에_넣지_않고_거부한다() {
        for (String bad : new String[] {
            "http://hooks.slack.com/services/A/B/SECRETPART",
            "https://evil.example/services/A/B/SECRETPART",
            "https://hooks.slack.com.evil.example/services/A/B/SECRETPART",
            "https://user@hooks.slack.com/services/A/B/SECRETPART",
            "https://hooks.slack.com:8443/services/A/B/SECRETPART",
            "https://hooks.slack.com/other/SECRETPART",
            "<SLACK_WEBHOOK_URL>",
            "not a url SECRETPART",
        }) {
            assertThatThrownBy(() -> SlackWebhookChannel.of(bad, new FakeHttp()))
                    .as(bad).isInstanceOf(IllegalArgumentException.class)
                    .message().doesNotContain("SECRETPART").doesNotContain("evil");
        }
    }
}
