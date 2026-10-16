package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationSetupTest {

    private static final String SLACK_URL = "https://hooks.slack.com/services/TPLACEHOLDER/BPLACEHOLDER/SECRETPART";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);
    private static final String HELP = "새 토큰을 넣고 다시 시작하세요";

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
                .kakaoReconsentHelp(HELP)
                .build();
    }

    /** 필수 값(표시 시간대·재동의 문구)만 넣은 빌더. 채널은 모두 꺼짐. */
    private static NotificationSetup.Builder minimal() {
        return NotificationSetup.builder().displayZone(ZoneOffset.UTC).kakaoReconsentHelp(HELP);
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
        try (NotificationQueue q = start(minimal().build())) {
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
        assertThatThrownBy(() -> NotificationSetup.builder().kakaoReconsentHelp(HELP).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("displayZone");
        try (NotificationQueue q = start(minimal().displayZone(ZoneId.of("Asia/Tokyo")).build())) {
            assertThat(q.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        }
    }

    @Test
    void 재동의_문구는_부르는_쪽이_반드시_넘기고_넘긴_것을_그대로_쓴다() {
        try (NotificationQueue q = start(minimal().kakaoReconsentHelp(" 새 토큰을 host.json에 넣으세요 ").build())) {
            assertThat(q.kakaoReconsentHelp()).isEqualTo("새 토큰을 host.json에 넣으세요");
        }
        // PR #134 architect R1·QA R1: 빠지거나 비면 일반 문구로 조용히 채우지 않고 만들지 않는다
        assertThatThrownBy(() -> NotificationSetup.builder().displayZone(ZoneOffset.UTC).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("kakaoReconsentHelp");
        assertThatThrownBy(() -> minimal().kakaoReconsentHelp(" ").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("kakaoReconsentHelp");
        assertThatThrownBy(() -> minimal().kakaoReconsentHelp(null).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("kakaoReconsentHelp");
        // 일반 문구가 맞으면 직접 넘긴다
        try (NotificationQueue q = start(minimal().kakaoReconsentHelp(NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP).build())) {
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

    @Test
    void 외부_채널은_Slack_카카오_순서로_꾸리고_못_꾸린_채널은_이유_코드로_돌려준다() {
        // D-103 반영 기록(PR #135 architect A2·risk-ops K4): 실험실 시험 보내기도 이 규칙 하나를 쓴다
        FakeHttp http = new FakeHttp();
        List<NotificationSetup.ExternalChannel> ready = full().build().externalChannels(dir, CLOCK, http);
        assertThat(ready).extracting(NotificationSetup.ExternalChannel::name)
                .containsExactly(NotificationSetup.SLACK, NotificationSetup.KAKAO);
        assertThat(ready).allSatisfy(c -> {
            assertThat(c.skipReason()).isNull();
            assertThat(c.channel().name()).isEqualTo(c.name());
        });

        assertThat(reasons(minimal().build())).containsExactly(NotificationSetup.SKIP_DISABLED, NotificationSetup.SKIP_DISABLED);
        assertThat(reasons(full().slackWebhookUrl("https://example.com/hook").build()).get(0))
                .isEqualTo(NotificationSetup.SKIP_INVALID_WEBHOOK_URL);
        assertThat(reasons(full().kakaoPaidMode(true).build()).get(1)).isEqualTo(NotificationSetup.SKIP_PAID_MODE);
        assertThat(reasons(full().kakaoRestApiKey(" ").build()).get(1)).isEqualTo(NotificationSetup.SKIP_MISSING_VALUES);
        assertThat(reasons(full().kakaoRefreshToken("").build()).get(1)).isEqualTo(NotificationSetup.SKIP_MISSING_VALUES);
        assertThat(reasons(full().kakaoLinkUrl(null).build()).get(1)).isEqualTo(NotificationSetup.SKIP_MISSING_VALUES);
        assertThat(reasons(full().kakaoLinkUrl("localhost?x=1").build()).get(1)).isEqualTo(NotificationSetup.SKIP_INVALID_VALUES);
        // 클라이언트 시크릿·액세스 토큰은 없어도 꾸린다(액세스 토큰은 첫 발송 전에 갱신)
        assertThat(reasons(full().kakaoClientSecret("").kakaoAccessToken("").build()).get(1)).isNull();
        // 꾸리기만 하고 HTTP는 부르지 않는다
        assertThat(http.requests).isEmpty();
    }

    @Test
    void 큐와_외부_채널_목록은_같은_규칙으로_꾸린다() {
        // 큐가 쓰는 채널 = externalChannels가 꾸린 채널(실험실 시험 성공과 엔진 발송 가능이 같은 규칙)
        for (NotificationSetup s : List.of(full().build(), full().slackWebhookUrl("x").build(),
                full().kakaoPaidMode(true).build(), full().kakaoRefreshToken("").build(), minimal().build())) {
            List<String> built = s.externalChannels(dir, CLOCK, new FakeHttp()).stream()
                    .filter(c -> c.channel() != null).map(NotificationSetup.ExternalChannel::name).toList();
            try (NotificationQueue q = start(s)) {
                assertThat(q.notifier().channelNames(Severity.URGENT)).containsExactlyElementsOf(
                        Stream.concat(built.stream(), Stream.of("log")).toList());
            }
        }
    }

    @Test
    void 외부_채널_결과는_채널과_이유_중_하나만_가진다() {
        assertThatThrownBy(() -> new NotificationSetup.ExternalChannel("slack", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSetup.ExternalChannel("log", new LogChannel(), "disabled"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> reasons(NotificationSetup s) {
        return s.externalChannels(dir, CLOCK, new FakeHttp()).stream().map(NotificationSetup.ExternalChannel::skipReason)
                .collect(Collectors.toList());
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
