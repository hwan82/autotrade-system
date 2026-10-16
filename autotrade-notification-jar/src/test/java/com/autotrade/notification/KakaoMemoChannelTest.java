package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KakaoMemoChannelTest {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String LINK = "http://localhost";
    private static final Notification N = EventRoutes.defaults()
            .notification(EventRoutes.TAKE_PROFIT, "005930 익절", "수량 10", NOW);
    private static final String OK = "{\"result_code\":0}";

    private final List<KakaoToken> saved = new ArrayList<>();

    private KakaoMemoChannel channel(FakeHttp http, Instant expiresAt, String clientSecret) {
        return new KakaoMemoChannel("REST-KEY-PH", clientSecret, LINK,
                new KakaoToken("ACCESS-OLD", "REFRESH-OLD", expiresAt), saved::add, http, CLOCK);
    }

    private static String form(FakeHttp.Request r, String key) {
        for (String pair : r.body().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void 유효한_토큰이면_나에게_보내기로_텍스트_템플릿을_보낸다() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, OK);
        SendResult r = channel(http, NOW.plusSeconds(3600), "").send(N);

        assertThat(r).isEqualTo(SendResult.ok());
        FakeHttp.Request q = http.requests.get(0);
        assertThat(q.uri()).isEqualTo(URI.create("https://kapi.kakao.com/v2/api/talk/memo/default/send"));
        assertThat(q.headers()).containsEntry("Authorization", "Bearer ACCESS-OLD")
                .containsEntry("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
        assertThat(form(q, "template_object")).isEqualTo("{\"object_type\":\"text\",\"text\":\"[정보] 005930 익절 "
                + "(2026-10-16T00:00:00Z)\\n수량 10\",\"link\":{\"web_url\":\"http://localhost\",\"mobile_web_url\":\"http://localhost\"}}");
        assertThat(saved).isEmpty();
    }

    @Test
    void 토큰_만료를_강제하면_보내기_전에_갱신하고_새_토큰으로_보낸다() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, "{\"token_type\":\"bearer\",\"access_token\":\"ACCESS-NEW\",\"expires_in\":43199}")
                .reply(200, OK);
        KakaoMemoChannel kakao = channel(http, NOW, "");

        assertThat(kakao.send(N)).isEqualTo(SendResult.ok());
        FakeHttp.Request refresh = http.requests.get(0);
        assertThat(refresh.uri()).isEqualTo(URI.create("https://kauth.kakao.com/oauth/token"));
        assertThat(form(refresh, "grant_type")).isEqualTo("refresh_token");
        assertThat(form(refresh, "client_id")).isEqualTo("REST-KEY-PH");
        assertThat(form(refresh, "refresh_token")).isEqualTo("REFRESH-OLD");
        assertThat(form(refresh, "client_secret")).isNull();
        assertThat(http.requests.get(1).headers()).containsEntry("Authorization", "Bearer ACCESS-NEW");
        // 리프레시 토큰이 오지 않으면 원래 것을 그대로 쓰고, 만료 시각은 지금 + expires_in
        assertThat(kakao.currentToken().refreshToken()).isEqualTo("REFRESH-OLD");
        assertThat(kakao.currentToken().accessExpiresAt()).isEqualTo(NOW.plusSeconds(43199));
        assertThat(saved).singleElement().extracting(KakaoToken::accessToken).isEqualTo("ACCESS-NEW");
    }

    @Test
    void 만료_5분_전부터_미리_갱신하고_그_전에는_갱신하지_않는다() throws Exception {
        FakeHttp early = new FakeHttp().reply(200, OK);
        channel(early, NOW.plusSeconds(301), "").send(N);
        assertThat(early.requests).hasSize(1);

        FakeHttp due = new FakeHttp()
                .reply(200, "{\"access_token\":\"ACCESS-NEW\",\"expires_in\":43199}")
                .reply(200, OK);
        channel(due, NOW.plusSeconds(300), "").send(N);
        assertThat(due.requests).hasSize(2);
        assertThat(due.requests.get(0).uri().getHost()).isEqualTo("kauth.kakao.com");
    }

    @Test
    void 발송이_401이면_한_번_갱신하고_한_번_더_보낸다() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(401, "{\"code\":-401}")
                .reply(200, "{\"access_token\":\"ACCESS-NEW\",\"expires_in\":43199,"
                        + "\"refresh_token\":\"REFRESH-NEW\",\"refresh_token_expires_in\":5184000}")
                .reply(200, OK);
        KakaoMemoChannel kakao = channel(http, NOW.plusSeconds(3600), "SECRET-PH");

        assertThat(kakao.send(N)).isEqualTo(SendResult.ok());
        assertThat(http.requests).hasSize(3);
        assertThat(form(http.requests.get(1), "client_secret")).isEqualTo("SECRET-PH");
        assertThat(http.requests.get(2).headers()).containsEntry("Authorization", "Bearer ACCESS-NEW");
        assertThat(kakao.currentToken().refreshToken()).isEqualTo("REFRESH-NEW");
        assertThat(saved).singleElement().extracting(KakaoToken::refreshToken).isEqualTo("REFRESH-NEW");
    }

    @Test
    void 갱신_뒤에도_401이면_더_갱신하지_않고_실패로_돌려준다() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(401, "{\"code\":-401}")
                .reply(200, "{\"access_token\":\"ACCESS-NEW\",\"expires_in\":43199}")
                .reply(401, "{\"code\":-401}");
        assertThat(channel(http, NOW.plusSeconds(3600), "").send(N)).isEqualTo(SendResult.failed("http-401"));
        assertThat(http.requests).hasSize(3);
    }

    @Test
    void 리프레시_토큰이_거절되면_보내지_않고_재동의가_필요하다고_돌려준다() throws Exception {
        FakeHttp http = new FakeHttp().reply(400, "{\"error\":\"invalid_grant\"}");
        assertThat(channel(http, NOW, "").send(N)).isEqualTo(SendResult.failed("kakao-refresh-rejected"));
        assertThat(http.requests).hasSize(1);
        assertThat(saved).isEmpty();
    }

    @Test
    void 갱신_응답이_이상하거나_장애면_토큰을_바꾸지_않는다() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, "{\"access_token\":\"\",\"expires_in\":43199}")
                .reply(200, "{\"access_token\":\"A\",\"expires_in\":0}")
                .reply(200, "not json")
                .reply(503, "")
                .fail(new IOException("down"));
        KakaoMemoChannel kakao = channel(http, NOW, "");
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("kakao-refresh-bad-response"));
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("kakao-refresh-bad-response"));
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("kakao-refresh-bad-response"));
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("kakao-refresh-http-503"));
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("kakao-refresh-io-error"));
        assertThat(kakao.currentToken().accessToken()).isEqualTo("ACCESS-OLD");
        assertThat(saved).isEmpty();
    }

    @Test
    void 발송_200이어도_result_code가_0이_아니면_실패다() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, "{\"result_code\":1}").reply(200, "").reply(400, "{\"code\":-3}");
        KakaoMemoChannel kakao = channel(http, NOW.plusSeconds(3600), "");
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("kakao-result-code"));
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("kakao-result-code"));
        assertThat(kakao.send(N)).isEqualTo(SendResult.failed("http-400"));
    }

    @Test
    void 토큰_저장이_실패해도_보내기는_이어간다() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, "{\"access_token\":\"ACCESS-NEW\",\"expires_in\":43199,\"refresh_token\":\"REFRESH-NEW\"}")
                .reply(200, OK);
        KakaoMemoChannel kakao = new KakaoMemoChannel("REST-KEY-PH", "", LINK,
                new KakaoToken("ACCESS-OLD", "REFRESH-OLD", NOW), t -> {
                    throw new IOException("disk full");
                }, http, CLOCK);
        assertThat(kakao.send(N)).isEqualTo(SendResult.ok());
        assertThat(kakao.currentToken().refreshToken()).isEqualTo("REFRESH-NEW");
    }

    @Test
    void 본문은_200자에서_자른다() throws Exception {
        String longBody = "가".repeat(500);
        Notification n = EventRoutes.defaults().notification(EventRoutes.ERROR, "긴 오류", longBody, NOW);
        String text = channelForTemplate().template(n);
        String inner = text.substring(text.indexOf("\"text\":\"") + 8, text.indexOf("\",\"link\""));
        assertThat(inner.replace("\\n", "\n").codePointCount(0, inner.replace("\\n", "\n").length())).isEqualTo(200);
        assertThat(inner).endsWith("…");
        assertThat(KakaoMemoChannel.truncate("😀".repeat(300), 200)).hasSize(199 * 2 + 1);
        assertThat(KakaoMemoChannel.truncate("짧다", 200)).isEqualTo("짧다");
    }

    private KakaoMemoChannel channelForTemplate() {
        return channel(new FakeHttp(), NOW.plusSeconds(3600), "");
    }

    @Test
    void 비밀값은_toString에_나오지_않고_잘못된_설정은_거부한다() {
        KakaoMemoChannel kakao = channel(new FakeHttp(), NOW, "SECRET-PH");
        assertThat(kakao.toString()).doesNotContain("REST-KEY-PH").doesNotContain("ACCESS-OLD").doesNotContain("SECRET-PH");
        assertThat(new KakaoToken("ACCESS-OLD", "REFRESH-OLD", NOW).toString())
                .doesNotContain("ACCESS-OLD").doesNotContain("REFRESH-OLD");
        assertThatThrownBy(() -> new KakaoToken(" ", "R", NOW)).isInstanceOf(IllegalArgumentException.class);
        for (String bad : new String[] {"ftp://localhost", "localhost", "http://u@localhost", "http://localhost/?a=1"}) {
            assertThatThrownBy(() -> new KakaoMemoChannel("K", "", bad, new KakaoToken("A", "R", NOW),
                    KakaoTokenStore.none(), new FakeHttp(), CLOCK)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new KakaoMemoChannel(" ", "", LINK, new KakaoToken("A", "R", NOW),
                KakaoTokenStore.none(), new FakeHttp(), CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 중단되면_InterruptedException을_그대로_올린다() {
        FakeHttp http = new FakeHttp().interrupt();
        assertThatThrownBy(() -> channel(http, NOW.plusSeconds(3600), "").send(N)).isInstanceOf(InterruptedException.class);
    }

    @Test
    void 미리_갱신이_일시_장애로_실패해도_만료_전이면_지금_토큰으로_보낸다() throws Exception {
        FakeHttp http = new FakeHttp().reply(503, "").reply(200, OK);
        KakaoMemoChannel kakao = channel(http, NOW.plusSeconds(60), "");
        assertThat(kakao.send(N)).isEqualTo(SendResult.ok());
        assertThat(http.requests.get(1).headers()).containsEntry("Authorization", "Bearer ACCESS-OLD");

        FakeHttp rejected = new FakeHttp().reply(401, "{\"error\":\"invalid_grant\"}");
        assertThat(channel(rejected, NOW.plusSeconds(60), "").send(N)).isEqualTo(SendResult.failed("kakao-refresh-rejected"));
        assertThat(rejected.requests).hasSize(1);
    }

    @Test
    void 미리_갱신한_토큰이_401이면_다시_갱신하지_않는다() throws Exception {
        FakeHttp http = new FakeHttp()
                .reply(200, "{\"access_token\":\"ACCESS-NEW\",\"expires_in\":43199}")
                .reply(401, "{\"code\":-401}");
        assertThat(channel(http, NOW, "").send(N)).isEqualTo(SendResult.failed("http-401"));
        assertThat(http.requests).hasSize(2);
    }
}
