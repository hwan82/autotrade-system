package com.autotrade.app.engine.command;

import com.autotrade.control.ControlCommand;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.NotificationQueue;
import java.util.Objects;

/**
 * 명령 감사 로그를 남기고 같은 내용을 긴급 알림으로도 넣는다(D-100). 실전 활성화·비활성화·PANIC SELL의 시도와 결과(거절 포함)를
 * 사람이 바로 알게 한다. 알림은 큐에 넣기만 하므로(막지 않고 던지지 않음) 명령 처리 결과에 영향을 주지 않는다. 비밀번호·확인
 * 문구는 받지 않으므로 알림에도 없다. 대상은 설정의 계좌 별칭(계좌번호 아님)이다.
 */
public final class NotifyingCommandAuditLog implements CommandAuditLog {

    private final CommandAuditLog delegate;
    private final NotificationQueue notifications;

    public NotifyingCommandAuditLog(CommandAuditLog delegate, NotificationQueue notifications) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public void record(ControlCommand command, String target, String code) {
        try {
            delegate.record(command, target, code);
        } finally {
            // 억제 대상에 결과 코드까지 넣어, 거절 뒤 성공처럼 결과가 바뀐 명령은 10분 안이어도 늘 알린다
            notifications.offer(EventRoutes.REAL_TRADING_COMMAND, command + " " + target + " " + code,
                    "실전 제어 명령 " + command + " (" + target + "): " + code, "");
        }
    }
}
