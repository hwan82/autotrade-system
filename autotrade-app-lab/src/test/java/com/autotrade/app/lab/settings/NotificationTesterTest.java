package com.autotrade.app.lab.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.lab.settings.NotificationTester.ChannelResult;
import com.autotrade.app.lab.settings.NotificationTester.Status;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.Secret;
import com.autotrade.notification.FileKakaoTokenStore;
import com.autotrade.notification.HttpTransport;
import com.autotrade.notification.KakaoToken;
import com.autotrade.notification.NotificationSetup;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-103 알림 시험 보내기: 켜진 채널마다 따로 보내고, 꺼졌거나 모자란 채널은 HTTP 없이 건너뛰며, 결과에 비밀값이 나오지 않는다.
 * 비밀값은 실제 값이 아닌 자리표시자다.
 */
class NotificationTesterTest {

    static final String WEBHOOK = "https://hooks.slack.com/services/PLACEHOLDER/HOOK/0001";
    static final String REST_KEY = "PLACEHOLDER-KAKAO-REST-0002";
    static final String REFRESH = "PLACEHOLDER-KAKAO-REFRESH-0003";
    static final String ACCESS = "PLACEHOLDER-KAKAO-ACCESS-0004";
    static final String NEW_ACCESS = "PLACEHOLDER-KAKAO-ACCESS-0005";
    static final String CLIENT_SECRET = "PLACEHOLDER-KAKAO-CLIENT-0006";
    static final String LINK = "https://example.invalid";
    static final List<String> SECRETS = List.of(WEBHOOK, "PLACEHOLDER/HOOK", REST_KEY, REFRESH, ACCESS, NEW_ACCESS,
            CLIENT_SECRET);
    static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    @TempDir
    Path dir;

    final FakeHttp http = new FakeHttp();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    NotificationTester tester() {
        return new NotificationTester(dir, http, clock);
    }

    static RuntimeConfig.Notification config(RuntimeConfig.Slack slack, RuntimeConfig.Kakao kakao) {
        return new RuntimeConfig.Notification(slack, kakao);
    }

    static RuntimeConfig.Slack slackOff() {
        return new RuntimeConfig.Slack(false, Secret.empty());
    }

    static RuntimeConfig.Slack slackOn(String url) {
        return new RuntimeConfig.Slack(true, Secret.of(url));
    }

    static RuntimeConfig.Kakao kakaoOff() {
        return new RuntimeConfig.Kakao(false, KakaoMode.ME, null, null, null, null, "");
    }

    static RuntimeConfig.Kakao kakaoMe(String access, String link) {
        return new RuntimeConfig.Kakao(true, KakaoMode.ME, access == null ? Secret.empty() : Secret.of(access),
                Secret.of(REST_KEY), Secret.of(REFRESH), Secret.of(CLIENT_SECRET), link);
    }

    @Test
    void 알림_설정_값을_실전_엔진과_같은_이름으로_옮긴다() {
        // D-103 반영 기록: 채널 구성 규칙은 NotificationSetup 하나다. 옮기기가 엔진·감시와 같아야 시험 성공 = 엔진 발송 가능
        RuntimeConfig.Notification n = new RuntimeConfig.Notification(
                new RuntimeConfig.Slack(true, Secret.of("SLACK-URL-PH")),
                new RuntimeConfig.Kakao(true, KakaoMode.ME, Secret.of("ACCESS-PH"), Secret.of("REST-PH"),
                        Secret.of("REFRESH-PH"), Secret.of("CLIENT-PH"), "http://link.example"));
        NotificationSetup.Builder expected = NotificationSetup.builder()
                .slackEnabled(true)
                .slackWebhookUrl("SLACK-URL-PH")
                .kakaoEnabled(true)
                .kakaoPaidMode(false)
                .kakaoRestApiKey("REST-PH")
                .kakaoClientSecret("CLIENT-PH")
                .kakaoAccessToken("ACCESS-PH")
                .kakaoRefreshToken("REFRESH-PH")
                .kakaoLinkUrl("http://link.example")
                .displayZone(ZoneId.of("Asia/Seoul"))
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP);
        assertThat(NotificationTester.setup(n)).isEqualTo(expected.build());
        assertThat(NotificationTester.setup(n))
                .isNotEqualTo(expected.kakaoRestApiKey("CLIENT-PH").kakaoClientSecret("REST-PH").build());

        RuntimeConfig.Notification biz = new RuntimeConfig.Notification(new RuntimeConfig.Slack(false, null),
                new RuntimeConfig.Kakao(true, KakaoMode.BIZ, null, null, null, null, null));
        assertThat(NotificationTester.setup(biz)).isEqualTo(NotificationSetup.builder()
                .kakaoEnabled(true)
                .kakaoPaidMode(true)
                .displayZone(ZoneId.of("Asia/Seoul"))
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP)
                .build());
    }

    @Test
    void 못_꾸린_이유_코드마다_화면_설명이_있다() {
        for (String reason : List.of(NotificationSetup.SKIP_DISABLED, NotificationSetup.SKIP_INVALID_WEBHOOK_URL,
                NotificationSetup.SKIP_PAID_MODE, NotificationSetup.SKIP_MISSING_VALUES,
                NotificationSetup.SKIP_INVALID_VALUES)) {
            assertThat(NotificationTester.skipMessage(reason)).doesNotStartWith("보내지 않았습니다(");
        }
    }

    @Test
    void 채널_화면_이름은_채널마다_정하고_모르는_채널을_Slack으로_적지_않는다() {
        // PR #138 QA Q1·architect R3: 건너뛴 결과에도 채널 이름이 제자리에 간다
        List<ChannelResult> results = tester().sendAll(config(slackOff(), kakaoOff()));
        assertThat(results).extracting(ChannelResult::channel, ChannelResult::label)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("slack", "Slack"),
                        org.assertj.core.groups.Tuple.tuple("kakao", "카카오톡"));
        assertThat(NotificationTester.label(NotificationSetup.SLACK)).isEqualTo("Slack");
        assertThat(NotificationTester.label(NotificationSetup.KAKAO)).isEqualTo("카카오톡");
        assertThat(NotificationTester.label("email")).isEqualTo("email");
    }

    @Test
    void 꺼진_채널은_HTTP_없이_건너뛴다() {
        List<ChannelResult> results = tester().sendAll(config(slackOff(), kakaoOff()));

        assertThat(results).extracting(ChannelResult::channel).containsExactly("slack", "kakao");
        assertThat(results).extracting(ChannelResult::status).containsOnly(Status.SKIPPED);
        assertThat(results).extracting(ChannelResult::detail).containsOnly("disabled");
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 켜진_채널마다_따로_보낸다_Slack이_받아도_카카오톡도_보낸다() {
        http.reply(200, "ok"); // slack
        // 설정의 액세스 토큰은 만료 시각을 모르므로 보내기 전에 갱신한다(NotificationSetup과 같음)
        http.reply(200, "{\"access_token\":\"" + NEW_ACCESS + "\",\"expires_in\":21599}");
        http.reply(200, "{\"result_code\":0}"); // kakao memo

        List<ChannelResult> results = tester().sendAll(config(slackOn(WEBHOOK), kakaoMe(ACCESS, LINK)));

        assertThat(results).extracting(ChannelResult::status).containsExactly(Status.SENT, Status.SENT);
        // 결과 화면 이름은 채널마다 다르다(PR #138 QA Q1, 변이 M6: 늘 "Slack")
        assertThat(results).extracting(ChannelResult::label).containsExactly("Slack", "카카오톡");
        assertThat(results).extracting(ChannelResult::message).containsExactly("보냈습니다. Slack에서 받았는지 확인하세요",
                "보냈습니다. 카카오톡에서 받았는지 확인하세요");
        assertThat(http.calls).extracting(c -> c.uri().getHost()).containsExactly("hooks.slack.com", "kauth.kakao.com",
                "kapi.kakao.com");
        // 시험 알림은 정보 등급이고 본문이 시험임을 밝힌다
        assertThat(http.calls.get(0).body()).contains("[정보] " + NotificationTester.TITLE).contains("시험 알림");
        assertThat(http.calls.get(2).headers().get("Authorization")).isEqualTo("Bearer " + NEW_ACCESS);
        assertNoSecrets(results);
    }

    @Test
    void Slack이_실패해도_카카오톡을_시험하고_실패_설명에_비밀값이_없다() {
        http.reply(404, "no_service");
        http.fail(new HttpTimeoutException("timed out " + WEBHOOK));

        List<ChannelResult> results = tester().sendAll(config(slackOn(WEBHOOK), kakaoMe(ACCESS, LINK)));

        assertThat(results.get(0).status()).isEqualTo(Status.FAILED);
        assertThat(results.get(0).detail()).isEqualTo("http-404");
        assertThat(results.get(0).message()).contains("HTTP 404").contains("Webhook URL");
        assertThat(results.get(1).status()).isEqualTo(Status.FAILED);
        assertThat(results.get(1).detail()).isEqualTo("kakao-refresh-timeout");
        assertThat(results.get(1).message()).contains("응답이 제때");
        assertNoSecrets(results);
    }

    @Test
    void Slack_URL_형식이_틀리면_보내지_않는다() {
        List<ChannelResult> results = tester().sendAll(config(slackOn("https://example.invalid/" + "PLACEHOLDER/HOOK"),
                kakaoOff()));

        assertThat(results.get(0).status()).isEqualTo(Status.SKIPPED);
        assertThat(results.get(0).detail()).isEqualTo("invalid-webhook-url");
        assertThat(http.calls).isEmpty();
        assertNoSecrets(results);
    }

    @Test
    void 카카오_알림톡_BIZ는_보내지_않는다() {
        RuntimeConfig.Kakao biz = new RuntimeConfig.Kakao(true, KakaoMode.BIZ, Secret.of(ACCESS), Secret.of(REST_KEY),
                Secret.of(REFRESH), Secret.empty(), LINK);

        List<ChannelResult> results = tester().sendAll(config(slackOff(), biz));

        assertThat(results.get(1).status()).isEqualTo(Status.SKIPPED);
        assertThat(results.get(1).detail()).isEqualTo("paid-mode");
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 카카오_필수_값이_모자라거나_링크가_틀리면_보내지_않는다() {
        RuntimeConfig.Kakao missing = new RuntimeConfig.Kakao(true, KakaoMode.ME, Secret.of(ACCESS), Secret.empty(),
                Secret.of(REFRESH), Secret.empty(), LINK);
        assertThat(tester().sendAll(config(slackOff(), missing)).get(1).detail()).isEqualTo("missing-values");

        List<ChannelResult> badLink = tester().sendAll(config(slackOff(), kakaoMe(ACCESS, "https://example.invalid/?q=1")));
        assertThat(badLink.get(1).status()).isEqualTo(Status.SKIPPED);
        assertThat(badLink.get(1).detail()).isEqualTo("invalid-values");
        assertThat(http.calls).isEmpty();
        assertNoSecrets(badLink);
    }

    @Test
    void 액세스_토큰이_없으면_갱신해_보내고_갱신한_토큰을_실전_엔진과_같은_파일에_남긴다() throws IOException {
        http.reply(200, "{\"access_token\":\"" + NEW_ACCESS + "\",\"expires_in\":21599}");
        http.reply(200, "{\"result_code\":0}");

        List<ChannelResult> results = tester().sendAll(config(slackOff(), kakaoMe(null, LINK)));

        assertThat(results.get(1).status()).isEqualTo(Status.SENT);
        assertThat(http.calls).extracting(c -> c.uri().getPath()).containsExactly("/oauth/token",
                "/v2/api/talk/memo/default/send");
        assertThat(http.calls.get(1).headers().get("Authorization")).isEqualTo("Bearer " + NEW_ACCESS);
        Path file = dir.resolve(FileKakaoTokenStore.DEFAULT_FILE);
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains(NEW_ACCESS);
        assertNoSecrets(results);
    }

    @Test
    void 다른_프로세스가_갱신한_토큰_파일이_있으면_그_토큰으로_보낸다() throws IOException {
        new FileKakaoTokenStore(dir.resolve(FileKakaoTokenStore.DEFAULT_FILE), REFRESH)
                .save(new KakaoToken(NEW_ACCESS, REFRESH, NOW.plusSeconds(3600)));
        http.reply(200, "{\"result_code\":0}");

        List<ChannelResult> results = tester().sendAll(config(slackOff(), kakaoMe(null, LINK)));

        assertThat(results.get(1).status()).isEqualTo(Status.SENT);
        assertThat(http.calls).hasSize(1);
        assertThat(http.calls.get(0).headers().get("Authorization")).isEqualTo("Bearer " + NEW_ACCESS);
    }

    @Test
    void 리프레시_토큰이_거절되면_재동의_안내를_보인다() {
        http.reply(401, "{\"error\":\"invalid_grant\"}");

        List<ChannelResult> results = tester().sendAll(config(slackOff(), kakaoMe(null, LINK)));

        assertThat(results.get(1).status()).isEqualTo(Status.FAILED);
        assertThat(results.get(1).detail()).isEqualTo("kakao-refresh-rejected");
        assertThat(results.get(1).message()).isEqualTo(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP);
        assertThat(http.calls).hasSize(1);
        // 거절이면 엔진·감시가 읽는 토큰 파일을 만들지 않는다(PR #135 risk-ops K1)
        assertThat(dir.resolve(FileKakaoTokenStore.DEFAULT_FILE)).doesNotExist();
        assertNoSecrets(results);
    }

    @Test
    void 갱신이_시간_초과면_토큰_파일을_만들지_않는다() {
        http.fail(new HttpTimeoutException("timed out"));

        List<ChannelResult> results = tester().sendAll(config(slackOff(), kakaoMe(null, LINK)));

        assertThat(results.get(1).detail()).isEqualTo("kakao-refresh-timeout");
        assertThat(dir.resolve(FileKakaoTokenStore.DEFAULT_FILE)).doesNotExist();
    }

    @Test
    void 카카오가_새_리프레시_토큰을_주면_토큰_파일에_남겨_엔진이_이어_쓴다() throws IOException {
        String rotated = "PLACEHOLDER-KAKAO-REFRESH-0007";
        http.reply(200, "{\"access_token\":\"" + NEW_ACCESS + "\",\"expires_in\":21599,\"refresh_token\":\"" + rotated
                + "\",\"refresh_token_expires_in\":5184000}");
        http.reply(200, "{\"result_code\":0}");

        List<ChannelResult> results = tester().sendAll(config(slackOff(), kakaoMe(null, LINK)));

        assertThat(results.get(1).status()).isEqualTo(Status.SENT);
        // 엔진·감시는 secrets.json의 옛 리프레시 토큰을 출처로 이 파일을 읽어 새 토큰으로 갈아탄다(PR #135 risk-ops K2)
        KakaoToken saved = new FileKakaoTokenStore(dir.resolve(FileKakaoTokenStore.DEFAULT_FILE), REFRESH).latest()
                .orElseThrow();
        assertThat(saved.refreshToken()).isEqualTo(rotated);
        assertThat(saved.accessToken()).isEqualTo(NEW_ACCESS);
        assertThat(results.toString()).doesNotContain(rotated);
    }

    @Test
    void 중단되면_남은_채널을_보내지_않는다() {
        http.interruptOnNext = true;

        List<ChannelResult> results;
        try {
            results = tester().sendAll(config(slackOn(WEBHOOK), kakaoMe(ACCESS, LINK)));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(results).extracting(ChannelResult::detail).containsExactly("interrupted", "interrupted");
        assertThat(http.calls).hasSize(1);
    }

    @Test
    void 사유_코드_설명() {
        assertThat(NotificationTester.failureMessage("io-error")).contains("연결하지 못했습니다");
        assertThat(NotificationTester.failureMessage("kakao-refresh-timeout")).contains("응답이 제때");
        assertThat(NotificationTester.failureMessage("kakao-refresh-http-503")).isEqualTo(
                "카카오 토큰 갱신이 HTTP 503로 거절했습니다. 잠시 뒤 다시 시험하세요");
        assertThat(NotificationTester.failureMessage("http-401")).contains("HTTP 401").contains("토큰이나 권한");
        assertThat(NotificationTester.failureMessage("kakao-result-code")).contains("talk_message");
        assertThat(NotificationTester.failureMessage("error-IllegalStateException"))
                .isEqualTo("보내지 못했습니다(error-IllegalStateException)");
    }

    static void assertNoSecrets(List<ChannelResult> results) {
        String all = results.toString();
        for (String s : SECRETS) {
            assertThat(all).doesNotContain(s);
        }
    }

    record Call(URI uri, Map<String, String> headers, String body) {}

    /** 정해 둔 응답을 차례로 돌려주는 가짜 HTTP. 응답이 없으면 시험이 실패한다. */
    static final class FakeHttp implements HttpTransport {
        final List<Call> calls = new ArrayList<>();
        final Deque<Object> replies = new ArrayDeque<>();
        boolean interruptOnNext;

        void reply(int status, String body) {
            replies.add(new HttpResult(status, body));
        }

        void fail(IOException e) {
            replies.add(e);
        }

        @Override
        public HttpResult post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException {
            calls.add(new Call(uri, Map.copyOf(headers), body));
            if (interruptOnNext) {
                interruptOnNext = false;
                throw new InterruptedException();
            }
            Object next = replies.poll();
            if (next instanceof IOException e) {
                throw e;
            }
            if (next == null) {
                throw new AssertionError("예상하지 않은 HTTP 요청: " + uri.getHost() + uri.getPath());
            }
            return (HttpResult) next;
        }
    }
}
