package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventRoutesTest {

    private static final Instant T = Instant.parse("2026-10-16T00:00:00Z");

    @Test
    void 기본_매핑표는_오류_손절_한도_강제종료가_긴급이고_익절_예수금_결산이_정보다() {
        EventRoutes r = EventRoutes.defaults();
        assertThat(r.severityOf(EventRoutes.ERROR)).isEqualTo(Severity.URGENT);
        assertThat(r.severityOf(EventRoutes.STOP_LOSS)).isEqualTo(Severity.URGENT);
        assertThat(r.severityOf(EventRoutes.RISK_LIMIT)).isEqualTo(Severity.URGENT);
        assertThat(r.severityOf(EventRoutes.FORCED_SHUTDOWN)).isEqualTo(Severity.URGENT);
        assertThat(r.severityOf(EventRoutes.TAKE_PROFIT)).isEqualTo(Severity.INFO);
        assertThat(r.severityOf(EventRoutes.MARKET_OPEN_CASH)).isEqualTo(Severity.INFO);
        assertThat(r.severityOf(EventRoutes.MARKET_CLOSE_SUMMARY)).isEqualTo(Severity.INFO);
    }

    @Test
    void 감시_엔진_운영_이벤트는_멈춤_정지요청_결측_설정경고_실전명령_채널문제가_긴급이다() {
        EventRoutes r = EventRoutes.defaults();
        for (String urgent : new String[] {EventRoutes.PROCESS_STALE, EventRoutes.HEARTBEAT_UNREADABLE,
                EventRoutes.PROCESS_RESTARTED, EventRoutes.STOP_REQUEST_ON, EventRoutes.READING_MISSING,
                EventRoutes.CONFIG_WARNING, EventRoutes.REAL_TRADING_COMMAND, EventRoutes.NOTIFICATION_CHANNEL,
                EventRoutes.CONTROL_CHANNEL, EventRoutes.REAL_CONFIG_CHANGED, EventRoutes.ENGINE_STARTED_REAL,
                EventRoutes.OPERATOR_LOCKED}) {
            assertThat(r.known(urgent)).as(urgent).isTrue();
            assertThat(r.severityOf(urgent)).as(urgent).isEqualTo(Severity.URGENT);
        }
        for (String info : new String[] {EventRoutes.PROCESS_RECOVERED, EventRoutes.PROCESS_STOPPED,
                EventRoutes.STOP_REQUEST_OFF, EventRoutes.READING_RECOVERED, EventRoutes.WATCHDOG_STARTED,
                EventRoutes.ENGINE_STARTED}) {
            assertThat(r.known(info)).as(info).isTrue();
            assertThat(r.severityOf(info)).as(info).isEqualTo(Severity.INFO);
        }
    }

    @Test
    void 표에_없는_종류는_긴급으로_보낸다() {
        assertThat(EventRoutes.defaults().known("watchdog-stale")).isFalse();
        assertThat(EventRoutes.defaults().notification("watchdog-stale", "엔진 멈춤", "", T).severity())
                .isEqualTo(Severity.URGENT);
    }

    @Test
    void 종류를_더한_표는_원래_표를_바꾸지_않는다() {
        EventRoutes more = EventRoutes.defaults().with("daily-report", Severity.INFO);
        assertThat(more.severityOf("daily-report")).isEqualTo(Severity.INFO);
        assertThat(EventRoutes.defaults().known("daily-report")).isFalse();
    }

    @Test
    void 종류_형식과_빈_제목을_거부한다() {
        assertThatThrownBy(() -> EventRoutes.defaults().with("Stop Loss", Severity.INFO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventRoutes.defaults().notification("", "제목", "", T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventRoutes.defaults().notification(EventRoutes.ERROR, " ", "", T))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 평문은_등급_제목_시각_본문_순서다() {
        Notification n = EventRoutes.defaults().notification(EventRoutes.STOP_LOSS, "005930 손절", "수량 10", T);
        assertThat(n.plainText()).isEqualTo("[긴급] 005930 손절 (2026-10-16T00:00:00Z)\n수량 10");
        Notification noBody = EventRoutes.defaults().notification(EventRoutes.TAKE_PROFIT, "익절", "", T);
        assertThat(noBody.plainText()).isEqualTo("[정보] 익절 (2026-10-16T00:00:00Z)");
    }
}
