package com.autotrade.app.lab.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.RuntimeConfigHolder;
import com.autotrade.config.RuntimeConfigStore;
import com.autotrade.notification.HttpTransport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/** D-103: 시험은 한 번에 하나, 끝난 뒤 10초 안에 다시 누르면 거절. */
class NotificationTestControllerTest {

    @TempDir
    Path dir;

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));

    RuntimeConfigHolder holder() {
        return new RuntimeConfigHolder(new ConfigLoader().defaults(), new RuntimeConfigStore(ConfigPaths.in(dir)));
    }

    @Test
    void 끝난_뒤_10초_안에는_거절하고_지나면_다시_보낸다() {
        AtomicInteger sends = new AtomicInteger();
        NotificationTestController controller = new NotificationTestController(holder(), counting(sends), clock);

        assertThat(controller.send().channels()).hasSize(2);
        clock.advance(NotificationTestController.COOLDOWN.minusSeconds(1));
        assertThatThrownBy(controller::send).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo("TEST_TOO_SOON");
        });
        clock.advance(Duration.ofSeconds(1));
        assertThat(controller.send().channels()).hasSize(2);
        assertThat(sends).hasValue(2);
    }

    @Test
    void 보내는_중에는_겹쳐_보내지_않는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NotificationTester blocking = new NotificationTester(dir, unusedHttp(), clock) {
            @Override
            public List<NotificationTester.ChannelResult> sendAll(RuntimeConfig.Notification config) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.sendAll(config);
            }
        };
        NotificationTestController controller = new NotificationTestController(holder(), blocking, clock);

        CompletableFuture<NotificationTestController.TestView> first = CompletableFuture.supplyAsync(controller::send);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(controller::send).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("TEST_RUNNING");
        });
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).channels()).hasSize(2);
    }

    @Test
    void 시험이_예외로_끝나도_다음_시험을_막지_않는다() {
        NotificationTester broken = new NotificationTester(dir, unusedHttp(), clock) {
            @Override
            public List<NotificationTester.ChannelResult> sendAll(RuntimeConfig.Notification config) {
                throw new IllegalStateException("boom");
            }
        };
        NotificationTestController controller = new NotificationTestController(holder(), broken, clock);

        assertThatThrownBy(controller::send).isInstanceOf(IllegalStateException.class);
        clock.advance(NotificationTestController.COOLDOWN);
        assertThatThrownBy(controller::send).isInstanceOf(IllegalStateException.class);
    }

    private NotificationTester counting(AtomicInteger sends) {
        return new NotificationTester(dir, unusedHttp(), clock) {
            @Override
            public List<NotificationTester.ChannelResult> sendAll(RuntimeConfig.Notification config) {
                sends.incrementAndGet();
                return super.sendAll(config);
            }
        };
    }

    /** 기본 설정은 알림이 모두 꺼져 있어 HTTP를 부르지 않는다. 부르면 시험이 실패한다. */
    private static HttpTransport unusedHttp() {
        return (uri, headers, body) -> {
            throw new AssertionError("알림이 꺼져 있는데 HTTP를 불렀습니다");
        };
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
