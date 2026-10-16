package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationSetupTest {

    private static final String SLACK_URL = "https://hooks.slack.com/services/TPLACEHOLDER/BPLACEHOLDER/SECRETPART";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private static NotificationSetup setup(boolean slack, String url, boolean kakao, boolean paid, String refresh, String link) {
        return NotificationSetup.builder()
                .slackEnabled(slack)
                .slackWebhookUrl(url)
                .kakaoEnabled(kakao)
                .kakaoPaidMode(paid)
                .kakaoRestApiKey("REST-KEY-PH")
                .kakaoRefreshToken(refresh)
                .kakaoLinkUrl(link)
                .displayZone(ZoneOffset.UTC)
                .build();
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
        try (NotificationQueue q = start(NotificationSetup.builder().displayZone(ZoneOffset.UTC).build())) {
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

    @Test
    void 표시_시간대는_부르는_쪽이_반드시_넣는다() {
        // PR #128 architect R2: 라이브러리가 시간대를 정하지 않는다
        assertThatThrownBy(() -> NotificationSetup.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("displayZone");
        try (NotificationQueue q = start(NotificationSetup.builder().displayZone(ZoneId.of("Asia/Tokyo")).build())) {
            assertThat(q.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        }
    }

    @Test
    void 재동의_문구는_부르는_쪽이_넘긴_것을_쓰고_없으면_일반_문구를_쓴다() {
        try (NotificationQueue q = start(NotificationSetup.builder().displayZone(ZoneOffset.UTC)
                .kakaoReconsentHelp(" 새 토큰을 host.json에 넣으세요 ").build())) {
            assertThat(q.kakaoReconsentHelp()).isEqualTo("새 토큰을 host.json에 넣으세요");
        }
        try (NotificationQueue q = start(NotificationSetup.builder().displayZone(ZoneOffset.UTC).kakaoReconsentHelp(" ").build())) {
            assertThat(q.kakaoReconsentHelp()).isEqualTo(NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP);
        }
        // 라이브러리 기본 문구에는 이 시스템의 설정 키·프로세스 이름이 없다
        assertThat(NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP).doesNotContain("secrets.json")
                .doesNotContain("notification.kakao").doesNotContain("실전");
    }

    @Test
    void 같은_값이면_같고_값이_하나라도_다르면_다르다() {
        NotificationSetup a = setup(true, SLACK_URL, true, false, "REFRESH-PH", "http://localhost");
        assertThat(a).isEqualTo(setup(true, SLACK_URL, true, false, "REFRESH-PH", "http://localhost"))
                .hasSameHashCodeAs(setup(true, SLACK_URL, true, false, "REFRESH-PH", "http://localhost"));
        assertThat(a).isNotEqualTo(setup(true, SLACK_URL, true, false, "OTHER-PH", "http://localhost"))
                .isNotEqualTo(setup(true, SLACK_URL, true, false, "REFRESH-PH", "http://other"))
                .isNotEqualTo(setup(true, SLACK_URL, true, true, "REFRESH-PH", "http://localhost"));
        // 값 하나씩만 다른 경우(PR #132 architect R1: 비교에서 빠진 값이 없게)
        NotificationSetup base = full().build();
        assertThat(base).isEqualTo(full().build());
        assertThat(base).isNotEqualTo(full().slackEnabled(false).build())
                .isNotEqualTo(full().slackWebhookUrl("https://hooks.slack.com/services/TOTHER/BOTHER/OTHER").build())
                .isNotEqualTo(full().kakaoEnabled(false).build())
                .isNotEqualTo(full().kakaoRestApiKey("OTHER-PH").build())
                .isNotEqualTo(full().kakaoClientSecret("OTHER-PH").build())
                .isNotEqualTo(full().kakaoAccessToken("OTHER-PH").build())
                .isNotEqualTo(full().displayZone(ZoneId.of("Asia/Tokyo")).build())
                .isNotEqualTo(full().kakaoReconsentHelp("다른 문구").build());
    }

    private static NotificationSetup.Builder full() {
        return NotificationSetup.builder()
                .slackEnabled(true)
                .slackWebhookUrl(SLACK_URL)
                .kakaoEnabled(true)
                .kakaoRestApiKey("REST-KEY-PH")
                .kakaoClientSecret("CLIENT-PH")
                .kakaoAccessToken("ACCESS-PH")
                .kakaoRefreshToken("REFRESH-PH")
                .kakaoLinkUrl("http://localhost")
                .displayZone(ZoneOffset.UTC)
                .kakaoReconsentHelp("문구");
    }
}
