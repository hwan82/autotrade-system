package com.autotrade.notification;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 설정 값으로 알림 채널을 꾸리고 큐를 띄운다(D-100). 실전 엔진·감시가 설정({@code notification.*})을 이 값으로 옮겨 부른다. 이 모듈은
 * 설정 모듈을 모르므로 값을 문자열로 받는다.
 *
 * <ul>
 *   <li>Slack: 켜져 있고 Webhook URL이 {@code https://hooks.slack.com/services/}로 시작할 때만. 틀리면 ERROR 로그(값 없이)를 남기고 끈다.</li>
 *   <li>카카오톡: 켜져 있고 모드가 나에게 보내기({@code ME})일 때만. 알림톡({@code BIZ})은 유료라 만들지 않고 WARN 로그를 남긴다(D-033).
 *       REST API 키·리프레시 토큰·링크 URL이 있어야 한다. 갱신한 토큰은 {@code <설정 폴더>/}{@value FileKakaoTokenStore#DEFAULT_FILE}에 남기고
 *       다음 기동에 이어 쓴다. 액세스 토큰은 선택이다(없으면 첫 발송 전에 갱신해 받는다).</li>
 *   <li>로그 채널은 늘 마지막에 있다.</li>
 * </ul>
 *
 * <p>값은 {@code toString}에 나오지 않는다.
 */
public final class NotificationSetup {

    /** 사람이 읽는 알림 시각의 시간대(사용자 PC가 한국에 있음, PR #127 architect R3). 계산에는 쓰지 않는다. */
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    /** 처음 액세스 토큰이 없을 때 쓰는 자리 값. 만료 시각을 {@link Instant#EPOCH}로 두어 첫 발송 전에 갱신한다. */
    static final String NO_ACCESS_TOKEN = "unset";

    private static final Logger LOG = System.getLogger(NotificationSetup.class.getName());

    private final boolean slackEnabled;
    private final String slackWebhookUrl;
    private final boolean kakaoEnabled;
    private final boolean kakaoPaidMode;
    private final String kakaoRestApiKey;
    private final String kakaoClientSecret;
    private final String kakaoAccessToken;
    private final String kakaoRefreshToken;
    private final String kakaoLinkUrl;

    /**
     * @param kakaoPaidMode 카카오 모드가 알림톡(BIZ)인지. 참이면 카카오 채널을 만들지 않는다
     * 빈 문자열은 "값 없음"이다. null은 빈 문자열로 본다.
     */
    public NotificationSetup(boolean slackEnabled, String slackWebhookUrl, boolean kakaoEnabled, boolean kakaoPaidMode,
            String kakaoRestApiKey, String kakaoClientSecret, String kakaoAccessToken, String kakaoRefreshToken,
            String kakaoLinkUrl) {
        this.slackEnabled = slackEnabled;
        this.slackWebhookUrl = text(slackWebhookUrl);
        this.kakaoEnabled = kakaoEnabled;
        this.kakaoPaidMode = kakaoPaidMode;
        this.kakaoRestApiKey = text(kakaoRestApiKey);
        this.kakaoClientSecret = text(kakaoClientSecret);
        this.kakaoAccessToken = text(kakaoAccessToken);
        this.kakaoRefreshToken = text(kakaoRefreshToken);
        this.kakaoLinkUrl = text(kakaoLinkUrl);
    }

    /** 외부 채널을 하나도 쓰지 않는 설정(로그만). */
    public static NotificationSetup logOnly() {
        return new NotificationSetup(false, "", false, false, "", "", "", "", "");
    }

    private static String text(String v) {
        return v == null ? "" : v.strip();
    }

    /** 채널을 꾸리고 보내는 스레드를 띄운다. 채널 설정이 틀려도 던지지 않고 그 채널만 끈다(로그 채널은 늘 있다). */
    public NotificationQueue start(Path configDir, String process, Clock clock) {
        return start(configDir, process, clock, new JdkHttpTransport());
    }

    NotificationQueue start(Path configDir, String process, Clock clock, HttpTransport http) {
        Objects.requireNonNull(configDir, "configDir");
        Notifier notifier = Notifier.standard(slack(http), kakao(configDir, clock, http), new LogChannel());
        return new NotificationQueue(notifier, EventRoutes.defaults(), clock, DISPLAY_ZONE, process).start();
    }

    private Channel slack(HttpTransport http) {
        if (!slackEnabled) {
            return null;
        }
        try {
            return SlackWebhookChannel.of(slackWebhookUrl, http);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "notification.slack.webhook-url 형식이 틀려 Slack 알림을 끕니다(https://hooks.slack.com/services/로 시작해야 함)");
            return null;
        }
    }

    private Channel kakao(Path configDir, Clock clock, HttpTransport http) {
        if (!kakaoEnabled) {
            return null;
        }
        if (kakaoPaidMode) {
            LOG.log(Level.WARNING, "notification.kakao.mode=BIZ(알림톡, 유료)는 지원하지 않아 카카오톡 알림을 끕니다(D-033). ME로 바꾸세요");
            return null;
        }
        try {
            FileKakaoTokenStore store = new FileKakaoTokenStore(configDir.resolve(FileKakaoTokenStore.DEFAULT_FILE),
                    kakaoRefreshToken);
            String access = kakaoAccessToken.isEmpty() ? NO_ACCESS_TOKEN : kakaoAccessToken;
            KakaoToken token = store.initial(new KakaoToken(access, kakaoRefreshToken, Instant.EPOCH));
            return new KakaoMemoChannel(kakaoRestApiKey, kakaoClientSecret, kakaoLinkUrl, token, store, http, clock);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "카카오톡 알림 설정(rest-api-key·refresh-token·link-url)이 비었거나 틀려 카카오톡 알림을 끕니다");
            return null;
        }
    }

    @Override
    public String toString() {
        return "NotificationSetup[slack=" + slackEnabled + ", kakao=" + kakaoEnabled + ", values=****]";
    }
}
