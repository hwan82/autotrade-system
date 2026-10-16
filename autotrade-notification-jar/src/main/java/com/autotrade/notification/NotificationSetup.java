package com.autotrade.notification;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 설정 값으로 알림 채널을 꾸리고 큐를 띄운다(D-100). 실전 엔진·감시가 설정({@code notification.*})을 이 값으로 옮겨 부른다. 이 모듈은
 * 설정 모듈을 모르므로 값을 문자열로 받는다. 값은 {@link #builder()}로 이름을 붙여 넣는다(PR #128 architect R1). 이 시스템의 설정 키
 * 이름·표시 시간대는 이 모듈에 두지 않고 부르는 쪽이 넘긴다(PR #128 architect R2). 채널을 꾸리는 규칙은 {@link #externalChannels}
 * 한 곳에만 있고, 큐({@link #start})와 실험실 "알림 시험 보내기"가 함께 쓴다(D-103 반영 기록, PR #135 architect A2·risk-ops K4).
 *
 * <ul>
 *   <li>Slack: 켜져 있고 Webhook URL이 {@code https://hooks.slack.com/services/}로 시작할 때만. 틀리면 ERROR 로그(값 없이)를 남기고 끈다.</li>
 *   <li>카카오톡: 켜져 있고 모드가 나에게 보내기({@code ME})일 때만. 알림톡({@code BIZ})은 유료라 만들지 않고 WARN 로그를 남긴다(D-033).
 *       REST API 키·리프레시 토큰·링크 URL이 있어야 한다. 갱신한 토큰은 {@code <설정 폴더>/}{@value FileKakaoTokenStore#DEFAULT_FILE}에 남기고
 *       다음 기동에 이어 쓴다. 액세스 토큰은 선택이다(없으면 첫 발송 전에 갱신해 받는다).</li>
 *   <li>로그 채널은 늘 마지막에 있다.</li>
 * </ul>
 *
 * <p>값은 {@code toString}·{@code hashCode}에 쓰지 않는다.
 */
public final class NotificationSetup {

    /** 처음 액세스 토큰이 없을 때 쓰는 자리 값. 만료 시각을 {@link Instant#EPOCH}로 두어 첫 발송 전에 갱신한다. */
    static final String NO_ACCESS_TOKEN = "unset";

    /** 외부 채널 이름. {@link Channel#name()}과 같다. */
    public static final String SLACK = "slack";
    /** 외부 채널 이름. {@link Channel#name()}과 같다. */
    public static final String KAKAO = "kakao";

    /** 꾸리지 못한 이유: 꺼져 있음. */
    public static final String SKIP_DISABLED = "disabled";
    /** 꾸리지 못한 이유: Slack Webhook URL이 {@code https://hooks.slack.com/services/}로 시작하지 않음. */
    public static final String SKIP_INVALID_WEBHOOK_URL = "invalid-webhook-url";
    /** 꾸리지 못한 이유: 카카오 모드가 알림톡(BIZ, 유료)이라 만들지 않음(D-033). */
    public static final String SKIP_PAID_MODE = "paid-mode";
    /** 꾸리지 못한 이유: 카카오 REST API 키·리프레시 토큰·링크 URL 중 빈 값이 있음. */
    public static final String SKIP_MISSING_VALUES = "missing-values";
    /** 꾸리지 못한 이유: 카카오 값 형식이 틀림(예: 링크 URL이 http(s)가 아니거나 쿼리가 있음). */
    public static final String SKIP_INVALID_VALUES = "invalid-values";

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
    private final ZoneId displayZone;
    private final String kakaoReconsentHelp;

    private NotificationSetup(Builder b) {
        this.slackEnabled = b.slackEnabled;
        this.slackWebhookUrl = b.slackWebhookUrl;
        this.kakaoEnabled = b.kakaoEnabled;
        this.kakaoPaidMode = b.kakaoPaidMode;
        this.kakaoRestApiKey = b.kakaoRestApiKey;
        this.kakaoClientSecret = b.kakaoClientSecret;
        this.kakaoAccessToken = b.kakaoAccessToken;
        this.kakaoRefreshToken = b.kakaoRefreshToken;
        this.kakaoLinkUrl = b.kakaoLinkUrl;
        this.displayZone = Objects.requireNonNull(b.displayZone, "displayZone");
        this.kakaoReconsentHelp = Objects.requireNonNull(b.kakaoReconsentHelp, "kakaoReconsentHelp");
    }

    /**
     * 외부 채널 하나를 꾸린 결과. 꾸렸으면 {@code channel}이 있고 {@code skipReason}이 null이다. 못 꾸렸으면 {@code channel}이 null이고
     * {@code skipReason}이 {@code SKIP_*} 코드다. 이유 코드에는 비밀값이 없다.
     *
     * @param name {@link #SLACK} 또는 {@link #KAKAO}
     * @param channel 꾸린 채널(못 꾸렸으면 null)
     * @param skipReason 못 꾸린 이유 코드(꾸렸으면 null)
     */
    public record ExternalChannel(String name, Channel channel, String skipReason) {
        public ExternalChannel {
            Objects.requireNonNull(name, "name");
            if ((channel == null) == (skipReason == null)) {
                throw new IllegalArgumentException("channel과 skipReason 중 하나만 있어야 한다");
            }
        }

        static ExternalChannel ready(String name, Channel channel) {
            return new ExternalChannel(name, Objects.requireNonNull(channel, "channel"), null);
        }

        static ExternalChannel skipped(String name, String reason) {
            return new ExternalChannel(name, null, reason);
        }
    }

    /**
     * 값을 이름으로 넣는 빌더. 문자열 값이 여럿이라 위치 인자로 받으면 순서가 바뀌어도 컴파일되므로 이름으로만 받는다(PR #128 architect R1).
     * 표시 시간대와 재동의 문구는 반드시 넣는다(빠지면 {@link Builder#build()}가 던진다). 다른 빠진 값은 "값 없음"(빈 문자열, 꺼짐)이다.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link NotificationSetup} 빌더. null 문자열은 빈 문자열로, 앞뒤 공백은 떼어 본다. */
    public static final class Builder {
        private boolean slackEnabled;
        private String slackWebhookUrl = "";
        private boolean kakaoEnabled;
        private boolean kakaoPaidMode;
        private String kakaoRestApiKey = "";
        private String kakaoClientSecret = "";
        private String kakaoAccessToken = "";
        private String kakaoRefreshToken = "";
        private String kakaoLinkUrl = "";
        private ZoneId displayZone;
        private String kakaoReconsentHelp;

        private Builder() {}

        public Builder slackEnabled(boolean v) {
            slackEnabled = v;
            return this;
        }

        public Builder slackWebhookUrl(String v) {
            slackWebhookUrl = text(v);
            return this;
        }

        public Builder kakaoEnabled(boolean v) {
            kakaoEnabled = v;
            return this;
        }

        /** 카카오 모드가 알림톡(BIZ, 유료)인지. 참이면 카카오 채널을 만들지 않는다(D-033). */
        public Builder kakaoPaidMode(boolean v) {
            kakaoPaidMode = v;
            return this;
        }

        public Builder kakaoRestApiKey(String v) {
            kakaoRestApiKey = text(v);
            return this;
        }

        public Builder kakaoClientSecret(String v) {
            kakaoClientSecret = text(v);
            return this;
        }

        public Builder kakaoAccessToken(String v) {
            kakaoAccessToken = text(v);
            return this;
        }

        public Builder kakaoRefreshToken(String v) {
            kakaoRefreshToken = text(v);
            return this;
        }

        public Builder kakaoLinkUrl(String v) {
            kakaoLinkUrl = text(v);
            return this;
        }

        /** 사람이 읽는 알림 시각의 시간대. 계산에는 쓰지 않는다(PR #127 architect R3). 부르는 쪽이 정한다(PR #128 architect R2). */
        public Builder displayZone(ZoneId v) {
            displayZone = Objects.requireNonNull(v, "displayZone");
            return this;
        }

        /**
         * 카카오 리프레시 토큰이 거절됐을 때 재동의 알림 본문. 어디에 새 토큰을 넣고 무엇을 다시 시작할지는 부르는 시스템만 알므로 부르는 쪽이
         * 넘긴다(PR #128 architect R2). 비밀값을 넣지 않는다. 반드시 넣는다: 빠지거나 비면 {@link #build()}가 던진다. 빠뜨려도 일반 문구로
         * 조용히 채우면 새 토큰을 넣을 곳과 다시 시작할 프로세스가 빠진 안내가 사용자에게 간다(PR #134 architect R1·QA R1). 일반 문구가 맞으면
         * {@link NotificationQueue#DEFAULT_KAKAO_RECONSENT_HELP}를 직접 넘긴다.
         */
        public Builder kakaoReconsentHelp(String v) {
            String t = text(v);
            kakaoReconsentHelp = t.isEmpty() ? null : t;
            return this;
        }

        /**
         * @throws NullPointerException 표시 시간대나 재동의 문구가 빠졌을 때(메시지에 빠진 이름)
         */
        public NotificationSetup build() {
            return new NotificationSetup(this);
        }
    }

    private static String text(String v) {
        return v == null ? "" : v.strip();
    }

    /** 채널을 꾸리고 보내는 스레드를 띄운다. 채널 설정이 틀려도 던지지 않고 그 채널만 끈다(로그 채널은 늘 있다). */
    public NotificationQueue start(Path configDir, String process, Clock clock) {
        return start(configDir, process, clock, new JdkHttpTransport());
    }

    NotificationQueue start(Path configDir, String process, Clock clock, HttpTransport http) {
        List<ExternalChannel> channels = externalChannels(configDir, clock, http);
        channels.forEach(NotificationSetup::logSkip);
        Notifier notifier = Notifier.standard(channels.get(0).channel(), channels.get(1).channel(), new LogChannel());
        return new NotificationQueue(notifier, EventRoutes.defaults(), clock, displayZone, process, kakaoReconsentHelp)
                .start();
    }

    /**
     * 외부 채널을 꾸린다. 순서는 늘 Slack, 카카오톡이다. 큐({@link #start})도 이것으로 꾸리므로, 부르는 쪽(실험실 "알림 시험 보내기", D-103)이
     * 채널마다 직접 보내도 실전 엔진·감시와 같은 규칙으로 꾸린 채널이다. 던지지 않는다: 설정이 틀린 채널은 이유 코드로 돌려주고 HTTP를 부르지
     * 않는다. 로그도 남기지 않는다(로그는 {@link #start}가 남긴다).
     *
     * <ul>
     *   <li>Slack: 꺼짐 → {@link #SKIP_DISABLED}, Webhook 접두사가 틀림 → {@link #SKIP_INVALID_WEBHOOK_URL}.</li>
     *   <li>카카오톡: 꺼짐 → {@link #SKIP_DISABLED}, 알림톡(BIZ) → {@link #SKIP_PAID_MODE}, REST API 키·리프레시 토큰·링크 URL 중 빈 값 →
     *       {@link #SKIP_MISSING_VALUES}, 값 형식이 틀림 → {@link #SKIP_INVALID_VALUES}. 액세스 토큰이 없으면 첫 발송 전에 갱신한다. 토큰은
     *       {@code <configDir>/}{@value FileKakaoTokenStore#DEFAULT_FILE}로 이어 쓴다.</li>
     * </ul>
     *
     * @param configDir 설정 폴더(카카오 토큰 파일 위치)
     * @param clock 카카오 토큰 만료 판단에 쓰는 시계
     * @param http 채널이 쓰는 HTTP
     */
    public List<ExternalChannel> externalChannels(Path configDir, Clock clock, HttpTransport http) {
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(http, "http");
        return List.of(slack(http), kakao(configDir, clock, http));
    }

    private ExternalChannel slack(HttpTransport http) {
        if (!slackEnabled) {
            return ExternalChannel.skipped(SLACK, SKIP_DISABLED);
        }
        try {
            return ExternalChannel.ready(SLACK, SlackWebhookChannel.of(slackWebhookUrl, http));
        } catch (RuntimeException e) {
            return ExternalChannel.skipped(SLACK, SKIP_INVALID_WEBHOOK_URL);
        }
    }

    private ExternalChannel kakao(Path configDir, Clock clock, HttpTransport http) {
        if (!kakaoEnabled) {
            return ExternalChannel.skipped(KAKAO, SKIP_DISABLED);
        }
        if (kakaoPaidMode) {
            return ExternalChannel.skipped(KAKAO, SKIP_PAID_MODE);
        }
        if (kakaoRestApiKey.isEmpty() || kakaoRefreshToken.isEmpty() || kakaoLinkUrl.isEmpty()) {
            return ExternalChannel.skipped(KAKAO, SKIP_MISSING_VALUES);
        }
        try {
            FileKakaoTokenStore store = new FileKakaoTokenStore(configDir.resolve(FileKakaoTokenStore.DEFAULT_FILE),
                    kakaoRefreshToken);
            String access = kakaoAccessToken.isEmpty() ? NO_ACCESS_TOKEN : kakaoAccessToken;
            KakaoToken token = store.initial(new KakaoToken(access, kakaoRefreshToken, Instant.EPOCH));
            return ExternalChannel.ready(KAKAO,
                    new KakaoMemoChannel(kakaoRestApiKey, kakaoClientSecret, kakaoLinkUrl, token, store, http, clock));
        } catch (RuntimeException e) {
            return ExternalChannel.skipped(KAKAO, SKIP_INVALID_VALUES);
        }
    }

    /** 꺼진 채널은 조용히 넘기고, 켰는데 못 꾸린 채널만 로그에 남긴다(값은 넣지 않는다). */
    private static void logSkip(ExternalChannel c) {
        String reason = c.skipReason();
        if (reason == null || reason.equals(SKIP_DISABLED)) {
            return;
        }
        switch (reason) {
            case SKIP_INVALID_WEBHOOK_URL -> LOG.log(Level.ERROR,
                    "notification.slack.webhook-url 형식이 틀려 Slack 알림을 끕니다(https://hooks.slack.com/services/로 시작해야 함)");
            case SKIP_PAID_MODE -> LOG.log(Level.WARNING,
                    "notification.kakao.mode=BIZ(알림톡, 유료)는 지원하지 않아 카카오톡 알림을 끕니다(D-033). ME로 바꾸세요");
            case SKIP_MISSING_VALUES -> LOG.log(Level.ERROR,
                    "카카오톡 알림 설정(rest-api-key·refresh-token·link-url) 중 빈 값이 있어 카카오톡 알림을 끕니다");
            default -> LOG.log(Level.ERROR, "카카오톡 알림 설정(rest-api-key·refresh-token·link-url) 형식이 틀려 카카오톡 알림을 끕니다");
        }
    }

    /** 표시 시간대(시험용). */
    ZoneId displayZone() {
        return displayZone;
    }

    /** 값이 모두 같으면 같다. 실전 엔진·감시가 설정 키를 제자리에 옮겼는지 보는 시험용(PR #128 architect R1). */
    @Override
    public boolean equals(Object o) {
        return o instanceof NotificationSetup s && slackEnabled == s.slackEnabled && kakaoEnabled == s.kakaoEnabled
                && kakaoPaidMode == s.kakaoPaidMode && slackWebhookUrl.equals(s.slackWebhookUrl)
                && kakaoRestApiKey.equals(s.kakaoRestApiKey) && kakaoClientSecret.equals(s.kakaoClientSecret)
                && kakaoAccessToken.equals(s.kakaoAccessToken) && kakaoRefreshToken.equals(s.kakaoRefreshToken)
                && kakaoLinkUrl.equals(s.kakaoLinkUrl) && displayZone.equals(s.displayZone)
                && kakaoReconsentHelp.equals(s.kakaoReconsentHelp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slackEnabled, kakaoEnabled, kakaoPaidMode, displayZone);
    }

    @Override
    public String toString() {
        return "NotificationSetup[slack=" + slackEnabled + ", kakao=" + kakaoEnabled + ", values=****]";
    }
}
