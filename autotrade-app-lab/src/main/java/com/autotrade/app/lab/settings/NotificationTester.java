package com.autotrade.app.lab.settings;

import com.autotrade.config.KakaoMode;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.notification.Channel;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.FileKakaoTokenStore;
import com.autotrade.notification.HttpTransport;
import com.autotrade.notification.KakaoMemoChannel;
import com.autotrade.notification.Notification;
import com.autotrade.notification.NotificationSetup;
import com.autotrade.notification.SendResult;
import com.autotrade.notification.Severity;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설정 화면 "알림 시험 보내기"(D-103, D-100 후속 2번). 저장된 알림 설정으로 켜진 외부 채널(Slack·카카오톡)마다 시험 알림 한 건을 따로
 * 보내고 채널별 결과를 돌려준다.
 *
 * <ul>
 *   <li>실전 엔진·감시의 {@code Notifier}는 첫 성공에서 멈추므로(대체 발송) 채널마다 확인할 수 없다. 그래서 채널을 하나씩 직접 부른다.
 *       로그 채널은 시험하지 않는다.</li>
 *   <li>채널은 실전 엔진·감시와 같은 {@link NotificationSetup#externalChannels}로 꾸린다. 구성 규칙(Slack Webhook 접두사, 카카오 ME만,
 *       BIZ는 보내지 않음, 빈 값, 액세스 토큰이 없으면 첫 발송 전 갱신)은 알림 모듈 한 곳에만 있다(D-103 반영 기록, PR #135 architect
 *       A2·risk-ops K4). 이 클래스는 못 꾸린 이유 코드를 화면 설명으로 바꾸기만 한다.</li>
 *   <li>카카오 토큰은 실전 엔진·감시와 같은 파일({@value FileKakaoTokenStore#DEFAULT_FILE})로 이어 쓴다. 시험 중 갱신으로 리프레시 토큰이
 *       바뀌어도 두 프로세스가 새 토큰을 받는다(D-100 6번과 같은 공유 방식).</li>
 *   <li>결과에는 짧은 사유 코드와 한국어 설명만 넣는다. 비밀값·응답 본문·예외 문구는 넣지 않는다({@link SendResult} 규칙).</li>
 * </ul>
 */
public class NotificationTester {

    /** 시험 알림 종류. 표에 없으면 긴급으로 보이므로 정보로 더해 쓴다. */
    static final String KIND = "notification-test";
    static final String TITLE = "알림 시험";
    static final String BODY = "실험실 설정 화면에서 보낸 시험 알림입니다. 조치할 것은 없습니다.";
    private static final EventRoutes ROUTES = EventRoutes.defaults().with(KIND, Severity.INFO);
    private static final Pattern HTTP = Pattern.compile("(kakao-refresh-)?http-(\\d{3})");

    private final Path configDir;
    private final HttpTransport http;
    private final Clock clock;

    public NotificationTester(Path configDir, HttpTransport http, Clock clock) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 결과 상태. */
    public enum Status {
        /** 채널이 받았다고 답함. */
        SENT,
        /** 보냈지만 실패. */
        FAILED,
        /** 꺼져 있거나 설정이 모자라 보내지 않음. */
        SKIPPED
    }

    /**
     * 채널 하나의 결과.
     *
     * @param channel {@code slack} 또는 {@code kakao}
     * @param label 화면 이름
     * @param status 결과
     * @param detail 짧은 사유 코드(예: {@code http-404}, {@code disabled})
     * @param message 화면에 보일 한국어 설명
     */
    public record ChannelResult(String channel, String label, Status status, String detail, String message) {
    }

    /** 켜진 채널마다 보낸다. 중단되면 인터럽트 표시를 되살리고 남은 채널은 보내지 않는다. */
    public List<ChannelResult> sendAll(RuntimeConfig.Notification config) {
        Objects.requireNonNull(config, "config");
        Instant now = clock.instant();
        Notification notification = ROUTES.notification(KIND, TITLE, BODY, now, RuntimeConfig.Notification.DISPLAY_ZONE);
        List<ChannelResult> results = new ArrayList<>();
        for (NotificationSetup.ExternalChannel c : setup(config).externalChannels(configDir, clock, http)) {
            String label = label(c.name());
            results.add(c.channel() == null ? skipped(c.name(), label, c.skipReason())
                    : send(c.name(), label, c.channel(), notification));
        }
        return List.copyOf(results);
    }

    /**
     * 설정 값을 알림 모듈 값으로 이름을 붙여 옮긴다. 실전 엔진({@code EngineMain.setup})·감시({@code WatchdogMain.setup})와 같은 옮기기다
     * (알림 모듈은 설정 모듈을 모른다, PR #128 architect R1). 값마다 제자리에 가는지는 시험이 본다.
     */
    static NotificationSetup setup(RuntimeConfig.Notification n) {
        RuntimeConfig.Kakao k = n.kakao();
        return NotificationSetup.builder()
                .slackEnabled(n.slack().enabled())
                .slackWebhookUrl(n.slack().webhookUrl().reveal())
                .kakaoEnabled(k.enabled())
                .kakaoPaidMode(k.mode() == KakaoMode.BIZ)
                .kakaoRestApiKey(k.restApiKey().reveal())
                .kakaoClientSecret(k.clientSecret().reveal())
                .kakaoAccessToken(k.accessToken().reveal())
                .kakaoRefreshToken(k.refreshToken().reveal())
                .kakaoLinkUrl(k.linkUrl())
                .displayZone(RuntimeConfig.Notification.DISPLAY_ZONE)
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP)
                .build();
    }

    private static String label(String channel) {
        return NotificationSetup.KAKAO.equals(channel) ? "카카오톡" : "Slack";
    }

    /** 못 꾸린 이유 코드를 화면 설명으로. 코드는 {@link NotificationSetup}이 정한다. */
    static String skipMessage(String reason) {
        return switch (reason) {
            case NotificationSetup.SKIP_DISABLED -> "꺼져 있어 보내지 않았습니다";
            case NotificationSetup.SKIP_INVALID_WEBHOOK_URL ->
                "webhook-url 형식이 틀려 보내지 않았습니다(https://hooks.slack.com/services/로 시작해야 함). 실전 엔진·감시도 Slack을 끕니다";
            case NotificationSetup.SKIP_PAID_MODE ->
                "알림톡(BIZ)은 유료라 알림 모듈이 보내지 않습니다(D-033). 나에게 보내기(ME)로 바꾸세요";
            case NotificationSetup.SKIP_MISSING_VALUES ->
                "rest-api-key·refresh-token·link-url 중 빈 값이 있어 보내지 않았습니다. 실전 엔진·감시도 카카오톡을 끕니다";
            case NotificationSetup.SKIP_INVALID_VALUES ->
                "카카오톡 설정 값 형식이 틀려 보내지 않았습니다(link-url은 등록한 웹 도메인의 http(s) URL, 쿼리 없음). 실전 엔진·감시도 카카오톡을 끕니다";
            default -> "보내지 않았습니다(" + reason + ")";
        };
    }

    private static ChannelResult send(String name, String label, Channel channel, Notification notification) {
        if (Thread.currentThread().isInterrupted()) {
            return failed(name, label, "interrupted");
        }
        SendResult result;
        try {
            result = channel.send(notification);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(name, label, "interrupted");
        } catch (RuntimeException e) {
            return failed(name, label, "error-" + e.getClass().getSimpleName());
        }
        if (result == null) {
            return failed(name, label, "no-result");
        }
        if (result.sent()) {
            return new ChannelResult(name, label, Status.SENT, result.detail(), "보냈습니다. " + label + "에서 받았는지 확인하세요");
        }
        return failed(name, label, result.detail());
    }

    private static ChannelResult skipped(String name, String label, String reason) {
        return new ChannelResult(name, label, Status.SKIPPED, reason, skipMessage(reason));
    }

    private static ChannelResult failed(String name, String label, String detail) {
        return new ChannelResult(name, label, Status.FAILED, detail, failureMessage(detail));
    }

    /** 사유 코드를 화면 설명으로. 코드 자체에는 비밀값이 없다({@link SendResult}). */
    static String failureMessage(String detail) {
        switch (detail) {
            case "timeout":
            case "kakao-refresh-timeout":
                return "응답이 제때 오지 않았습니다. 인터넷 연결을 확인하세요";
            case "io-error":
            case "kakao-refresh-io-error":
                return "연결하지 못했습니다. 인터넷 연결과 방화벽을 확인하세요";
            case "interrupted":
                return "실험실이 멈추는 중이라 보내지 못했습니다";
            case KakaoMemoChannel.REFRESH_REJECTED:
            case KakaoMemoChannel.RECONSENT_REQUIRED:
                return RuntimeConfig.Notification.KAKAO_RECONSENT_HELP;
            case "kakao-refresh-bad-response":
                return "카카오 토큰 갱신 응답을 해석하지 못했습니다. 잠시 뒤 다시 시험하세요";
            case "kakao-result-code":
                return "카카오가 메시지를 받지 않았다고 답했습니다. talk_message 동의와 link-url(앱에 등록한 웹 도메인)을 확인하세요";
            default:
                break;
        }
        Matcher m = HTTP.matcher(detail);
        if (m.matches()) {
            String what = m.group(1) != null ? "카카오 토큰 갱신이" : "서버가";
            return what + " HTTP " + m.group(2) + "로 거절했습니다" + httpHint(m.group(1) != null, m.group(2));
        }
        return "보내지 못했습니다(" + detail + ")";
    }

    private static String httpHint(boolean refresh, String code) {
        if (refresh) {
            // 400·401은 거절(재동의)로 따로 온다. 나머지는 카카오 쪽 일시 장애로 본다
            return ". 잠시 뒤 다시 시험하세요";
        }
        return switch (code) {
            case "401", "403" -> ". 토큰이나 권한을 확인하세요";
            case "404", "410" -> ". Webhook URL이 지워졌거나 틀렸는지 확인하세요";
            default -> code.startsWith("5") ? ". 잠시 뒤 다시 시험하세요" : "";
        };
    }
}
