package com.autotrade.notification;

import java.util.List;
import java.util.Objects;

/**
 * 알림 한 건을 보낸 기록.
 *
 * @param notification 보낸 알림
 * @param attempts 채널 순서대로 시도한 결과(성공한 채널에서 멈춘다)
 * @param interrupted 보내는 중 스레드가 중단돼 남은 채널을 시도하지 않았는지
 */
public record DispatchReport(Notification notification, List<Attempt> attempts, boolean interrupted) {

    public DispatchReport {
        Objects.requireNonNull(notification, "notification");
        attempts = List.copyOf(attempts);
    }

    /** 외부 채널(로그 말고) 하나라도 받았는지. 로그 대체만 됐으면 false라 부르는 쪽이 "알림 채널 없음"을 이상으로 볼 수 있다. */
    public boolean delivered() {
        return attempts.stream().anyMatch(a -> a.result().sent() && !a.channel().equals("log"));
    }

    /** 첫 채널이 아닌 채널로 보냈는지(대체 발송). */
    public boolean fellBack() {
        return attempts.size() > 1 && attempts.get(attempts.size() - 1).result().sent();
    }

    /** 채널 하나의 시도. */
    public record Attempt(String channel, SendResult result) {

        public Attempt {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(result, "result");
        }
    }
}
