package com.autotrade.app.engine.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.control.ControlCommand;
import com.autotrade.notification.Channel;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.LogChannel;
import com.autotrade.notification.Notification;
import com.autotrade.notification.NotificationQueue;
import com.autotrade.notification.Notifier;
import com.autotrade.notification.SendResult;
import com.autotrade.notification.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotifyingCommandAuditLogTest {

    private final List<Notification> got = Collections.synchronizedList(new ArrayList<>());
    private final Channel sink = new Channel() {
        @Override
        public String name() {
            return "sink";
        }

        @Override
        public SendResult send(Notification n) {
            got.add(n);
            return SendResult.ok();
        }
    };
    private final NotificationQueue queue = new NotificationQueue(
            Notifier.of(List.of(sink, new LogChannel()), List.of(sink, new LogChannel())), EventRoutes.defaults(),
            Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC, "engine-test",
            NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP).start();
    private final List<String> audited = new ArrayList<>();

    @AfterEach
    void close() {
        queue.close(Duration.ofSeconds(1));
    }

    private void awaitGot(int n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (got.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(got).hasSize(n);
    }

    @Test
    void 감사_로그를_남기고_같은_내용을_긴급_알림으로_넣는다() throws Exception {
        CommandAuditLog log = new NotifyingCommandAuditLog((c, t, code) -> audited.add(c + " " + t + " " + code), queue);
        log.record(ControlCommand.ACTIVATE, "KIS-KR-1", "REJECTED_PASSWORD");
        log.record(ControlCommand.ACTIVATE, "KIS-KR-1", "OK");
        assertThat(audited).containsExactly("ACTIVATE KIS-KR-1 REJECTED_PASSWORD", "ACTIVATE KIS-KR-1 OK");
        // 결과가 바뀐 같은 명령은 10분 안이어도 둘 다 알린다
        awaitGot(2);
        assertThat(got).allSatisfy(n -> {
            assertThat(n.kind()).isEqualTo(EventRoutes.REAL_TRADING_COMMAND);
            assertThat(n.severity()).isEqualTo(Severity.URGENT);
        });
        assertThat(got).extracting(Notification::title)
                .containsExactly("실전 제어 명령 ACTIVATE (KIS-KR-1): REJECTED_PASSWORD", "실전 제어 명령 ACTIVATE (KIS-KR-1): OK");
    }

    @Test
    void 감사_로그가_실패해도_알림은_넣고_실패는_그대로_올린다() throws Exception {
        CommandAuditLog log = new NotifyingCommandAuditLog((c, t, code) -> {
            throw new IllegalStateException("감사 로그 실패 주입");
        }, queue);
        assertThatThrownBy(() -> log.record(ControlCommand.PANIC_ALL, "ALL", "OK"))
                .isInstanceOf(IllegalStateException.class);
        awaitGot(1);
    }
}
