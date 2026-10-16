package com.autotrade.app.lab.settings;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.config.RuntimeConfigHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설정 화면 "알림 시험 보내기" API(D-103). 사용자가 버튼을 누를 때만 저장된 알림 설정으로 켜진 채널마다 시험 알림 한 건을 보낸다.
 *
 * <ul>
 *   <li>요청 본문을 받지 않는다. 화면에 입력만 하고 저장하지 않은 값은 쓰지 않는다(비밀값이 이 요청으로 오가지 않게).</li>
 *   <li>한 번에 하나만 보낸다(겹치면 409). 시험이 끝난 뒤 {@link #COOLDOWN} 안에 다시 누르면 429로 거절한다(두 번 눌러 알림이 쌓이지 않게).</li>
 *   <li>루프백·CSRF 검사는 다른 변경 요청처럼 {@code LocalOnlyRequestFilter}가 한다(127.0.0.1 전용).</li>
 *   <li>로그에는 채널 이름과 결과 코드만 남긴다.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/settings/notification-test")
public class NotificationTestController {

    static final Duration COOLDOWN = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(NotificationTestController.class);

    private final RuntimeConfigHolder holder;
    private final NotificationTester tester;
    private final Clock clock;
    private final Object lock = new Object();
    private boolean running;
    private Instant lastFinished = Instant.MIN;

    public NotificationTestController(RuntimeConfigHolder holder, NotificationTester tester, Clock clock) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.tester = Objects.requireNonNull(tester, "tester");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping
    public TestView send() {
        synchronized (lock) {
            if (running) {
                throw new ApiException(HttpStatus.CONFLICT, "TEST_RUNNING", "알림 시험을 보내는 중입니다. 끝난 뒤 다시 누르세요");
            }
            Instant now = clock.instant();
            if (now.isBefore(lastFinished.plus(COOLDOWN))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TEST_TOO_SOON",
                        "방금 시험을 보냈습니다. " + COOLDOWN.toSeconds() + "초 뒤에 다시 누르세요");
            }
            running = true;
        }
        try {
            Instant at = clock.instant();
            List<NotificationTester.ChannelResult> results = tester.sendAll(holder.current().notification());
            log.info("알림 시험(UI): {}", results.stream()
                    .map(r -> r.channel() + "=" + r.status() + "(" + r.detail() + ")")
                    .collect(Collectors.joining(", ")));
            return new TestView(at, results);
        } finally {
            synchronized (lock) {
                running = false;
                lastFinished = clock.instant();
            }
        }
    }

    /**
     * @param sentAt 시험을 시작한 시각
     * @param channels 채널별 결과(Slack, 카카오톡 순서)
     */
    public record TestView(Instant sentAt, List<NotificationTester.ChannelResult> channels) {
    }
}
