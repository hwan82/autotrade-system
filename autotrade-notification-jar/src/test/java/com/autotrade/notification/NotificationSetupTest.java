package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationSetupTest {

    private static final String SLACK_URL = "https://hooks.slack.com/services/TPLACEHOLDER/BPLACEHOLDER/SECRETPART";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private static NotificationSetup setup(boolean slack, String url, boolean kakao, boolean paid, String refresh, String link) {
        return new NotificationSetup(slack, url, kakao, paid, "REST-KEY-PH", "", "", refresh, link);
    }

    private NotificationQueue start(NotificationSetup s) {
        return s.start(dir, "test", CLOCK, new FakeHttp());
    }

    @Test
    void 켜진_채널만_표준_순서로_꾸린다() {
        try (NotificationQueue q = start(setup(true, SLACK_URL, true, false, "REFRESH-PH", "http://localhost"))) {
            assertThat(q.notifier().channelNames(Severity.URGENT)).containsExactly("slack", "kakao", "log");
            assertThat(q.notifier().channelNames(Severity.INFO)).containsExactly("kakao", "slack", "log");
        }
        try (NotificationQueue q = start(NotificationSetup.logOnly())) {
            assertThat(q.notifier().channelNames(Severity.URGENT)).containsExactly("log");
            assertThat(q.notifier().hasExternalChannel(Severity.URGENT)).isFalse();
        }
    }

    @Test
    void 알림톡_모드는_만들지_않는다() {
        try (NotificationQueue q = start(setup(false, "", true, true, "REFRESH-PH", "http://localhost"))) {
            assertThat(q.notifier().channelNames(Severity.INFO)).containsExactly("log");
        }
    }

    @Test
    void 틀린_채널_설정은_던지지_않고_그_채널만_끈다() {
        try (NotificationQueue q = start(setup(true, "https://example.com/hook", true, false, "", "http://localhost"))) {
            assertThat(q.notifier().channelNames(Severity.URGENT)).containsExactly("log");
        }
        try (NotificationQueue q = start(setup(true, SLACK_URL, true, false, "REFRESH-PH", "localhost?x=1"))) {
            assertThat(q.notifier().channelNames(Severity.URGENT)).containsExactly("slack", "log");
        }
    }

    @Test
    void 문자열에_값이_나오지_않는다() {
        assertThat(setup(true, SLACK_URL, true, false, "REFRESH-PH", "http://localhost").toString())
                .doesNotContain("SECRETPART").doesNotContain("REFRESH-PH").doesNotContain("REST-KEY");
    }
}
