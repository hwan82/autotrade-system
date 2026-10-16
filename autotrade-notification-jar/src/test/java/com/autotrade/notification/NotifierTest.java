package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotifierTest {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String SLACK_URL = "https://hooks.slack.com/services/TPLACEHOLDER/BPLACEHOLDER/SECRETPART";
    private static final Notification URGENT = EventRoutes.defaults().notification(EventRoutes.FORCED_SHUTDOWN, "엔진 멈춤", "", NOW);
    private static final Notification INFO = EventRoutes.defaults().notification(EventRoutes.MARKET_CLOSE_SUMMARY, "결산", "", NOW);

    /** 받은 알림을 기록하고 정해진 결과를 돌려주는 채널. */
    static final class Recording implements Channel {
        final String name;
        final List<Notification> got = new ArrayList<>();
        SendResult result;
        RuntimeException error;

        Recording(String name, SendResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SendResult send(Notification n) {
            got.add(n);
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    private final Recording log = new Recording("log", SendResult.ok());

    @Test
    void Slack_발송이_실패하면_긴급_알림은_카카오톡으로_넘어간다() {
        FakeHttp slackHttp = new FakeHttp().reply(500, "");
        FakeHttp kakaoHttp = new FakeHttp().reply(200, "{\"result_code\":0}");
        Channel slack = SlackWebhookChannel.of(SLACK_URL, slackHttp);
        Channel kakao = new KakaoMemoChannel("K", "", "http://localhost", new KakaoToken("A", "R", NOW.plusSeconds(3600)),
                KakaoTokenStore.none(), kakaoHttp, Clock.fixed(NOW, ZoneOffset.UTC));
        Notifier notifier = Notifier.standard(slack, kakao, new LogChannel());

        DispatchReport r = notifier.dispatch(URGENT);

        assertThat(r.attempts()).extracting(DispatchReport.Attempt::channel).containsExactly("slack", "kakao");
        assertThat(r.attempts().get(0).result()).isEqualTo(SendResult.failed("http-500"));
        assertThat(r.delivered()).isTrue();
        assertThat(r.fellBack()).isTrue();
        assertThat(kakaoHttp.requests).hasSize(1);
    }

    @Test
    void Slack이_예외로_실패해도_다음_채널로_넘어간다() {
        Recording slack = new Recording("slack", SendResult.ok());
        slack.error = new IllegalStateException("boom");
        Recording kakao = new Recording("kakao", SendResult.ok());

        DispatchReport r = Notifier.of(List.of(slack, kakao, log), List.of(kakao, slack, log)).dispatch(URGENT);

        assertThat(r.attempts().get(0).result()).isEqualTo(SendResult.failed("error-IllegalStateException"));
        assertThat(kakao.got).containsExactly(URGENT);
        assertThat(log.got).isEmpty();
    }

    @Test
    void 외부_채널이_모두_실패하면_로그로_남기고_전달되지_않음으로_보고한다() {
        Recording slack = new Recording("slack", SendResult.failed("http-500"));
        Recording kakao = new Recording("kakao", SendResult.failed("timeout"));

        DispatchReport r = Notifier.of(List.of(slack, kakao, log), List.of(kakao, slack, log)).dispatch(URGENT);

        assertThat(r.attempts()).extracting(DispatchReport.Attempt::channel).containsExactly("slack", "kakao", "log");
        assertThat(log.got).containsExactly(URGENT);
        assertThat(r.delivered()).isFalse();
    }

    @Test
    void 정보_알림은_카카오톡이_먼저고_성공하면_멈춘다() {
        Recording slack = new Recording("slack", SendResult.ok());
        Recording kakao = new Recording("kakao", SendResult.ok());
        Notifier notifier = Notifier.standard(slack, kakao, new LogChannel());

        assertThat(notifier.channelNames(Severity.INFO)).containsExactly("kakao", "slack", "log");
        assertThat(notifier.channelNames(Severity.URGENT)).containsExactly("slack", "kakao", "log");
        DispatchReport r = notifier.dispatch(INFO);
        assertThat(r.attempts()).extracting(DispatchReport.Attempt::channel).containsExactly("kakao");
        assertThat(slack.got).isEmpty();
        assertThat(r.fellBack()).isFalse();
    }

    @Test
    void 채널이_하나도_설정되지_않으면_로그만_있고_외부_채널_없음을_알_수_있다() {
        Notifier notifier = Notifier.standard(null, null, new LogChannel());
        assertThat(notifier.channelNames(Severity.URGENT)).containsExactly("log");
        assertThat(notifier.hasExternalChannel(Severity.URGENT)).isFalse();
        DispatchReport r = notifier.dispatch(URGENT);
        assertThat(r.attempts()).singleElement().extracting(a -> a.result().sent()).isEqualTo(true);
        assertThat(r.delivered()).isFalse();

        assertThat(Notifier.standard(new Recording("slack", SendResult.ok()), null, new LogChannel())
                .hasExternalChannel(Severity.INFO)).isTrue();
    }

    @Test
    void 중단되면_인터럽트_표시를_되살리고_남은_채널을_건너뛴다() {
        Channel slack = SlackWebhookChannel.of(SLACK_URL, new FakeHttp().interrupt());
        try {
            DispatchReport r = Notifier.of(List.of(slack, log), List.of(log)).dispatch(URGENT);
            assertThat(r.interrupted()).isTrue();
            assertThat(r.attempts()).singleElement().extracting(DispatchReport.Attempt::result)
                    .isEqualTo(SendResult.failed("interrupted"));
            assertThat(log.got).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 채널이_null_결과를_주면_실패로_친다() {
        Recording broken = new Recording("slack", null);
        DispatchReport r = Notifier.of(List.of(broken, log), List.of(log)).dispatch(URGENT);
        assertThat(r.attempts().get(0).result()).isEqualTo(SendResult.failed("no-result"));
        assertThat(log.got).containsExactly(URGENT);
    }

    @Test
    void 빈_순서와_겹치는_이름을_거부한다() {
        assertThatThrownBy(() -> Notifier.of(List.of(), List.of(log))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Notifier.of(List.of(log, new Recording("log", SendResult.ok())), List.of(log)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 로그_채널은_긴급을_ERROR_정보를_INFO로_남긴다() throws IOException {
        List<String> lines = new ArrayList<>();
        System.Logger logger = new System.Logger() {
            @Override
            public String getName() {
                return "t";
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {
                lines.add(level + " " + msg);
            }

            @Override
            public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {
                lines.add(level + " " + format);
            }
        };
        LogChannel channel = new LogChannel(logger);
        channel.send(URGENT);
        channel.send(INFO);
        assertThat(lines).containsExactly(
                "ERROR 알림(forced-shutdown) [긴급] 엔진 멈춤 (2026-10-16T00:00:00Z)",
                "INFO 알림(market-close-summary) [정보] 결산 (2026-10-16T00:00:00Z)");
    }
}
