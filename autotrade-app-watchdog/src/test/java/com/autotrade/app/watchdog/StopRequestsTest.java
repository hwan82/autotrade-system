package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.watchdog.StopRequests.Pressure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StopRequestsTest {

    @TempDir
    Path dir;

    private Path collector() {
        return dir.resolve("collector-state/stop-request");
    }

    private Path experiment() {
        return dir.resolve("experiment-state/stop-request");
    }

    @Test
    void 위험이면_두_명령의_정지_요청_파일을_만들고_점검마다_확인_시각을_갱신한다() throws Exception {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        StopRequests stop = new StopRequests(dir, Duration.ofMinutes(5));

        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        assertThat(collector()).doesNotExist();
        assertThat(experiment()).doesNotExist();

        stop.update(clock.instant(), Pressure.CRITICAL, "disk.free-gb:C:\\ 12GB CRITICAL");
        assertThat(stop.active()).isTrue();
        assertThat(Files.readString(collector())).contains("requested-at=2026-10-15T00:00:00Z", "confirmed-at=2026-10-15T00:00:00Z",
                "reason=disk.free-gb:C:\\ 12GB CRITICAL");
        assertThat(Files.readString(experiment())).isEqualTo(Files.readString(collector()));

        // 누가 지워도 다음 점검에 다시 만든다(레벨 신호, ADR-007 2.7)
        Files.delete(experiment());
        clock.advance(Duration.ofSeconds(5));
        stop.update(clock.instant(), Pressure.ELEVATED, "disk.free-gb:C:\\ 30GB WARN");
        assertThat(Files.readString(experiment())).contains("requested-at=2026-10-15T00:00:00Z", "confirmed-at=2026-10-15T00:00:05Z");
    }

    @Test
    void 정상이_해제_유지_시간_동안_이어져야_지우고_경고나_판단_불가는_유지_시간을_다시_센다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        StopRequests stop = new StopRequests(dir, Duration.ofMinutes(5));
        stop.update(clock.instant(), Pressure.CRITICAL, "위험");

        clock.advance(Duration.ofMinutes(1));
        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        clock.advance(Duration.ofMinutes(4));
        stop.update(clock.instant(), Pressure.ELEVATED, "경고"); // 4분 만에 경고: 다시 센다
        clock.advance(Duration.ofSeconds(5));
        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        clock.advance(Duration.ofMinutes(4));
        stop.update(clock.instant(), Pressure.UNKNOWN, "드라이브 여유를 읽지 못함"); // 판단 못 하면 풀지 않는다
        clock.advance(Duration.ofSeconds(5));
        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        assertThat(collector()).exists();
        assertThat(experiment()).exists();

        clock.advance(Duration.ofSeconds(1));
        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        assertThat(stop.active()).isFalse();
        assertThat(collector()).doesNotExist();
        assertThat(experiment()).doesNotExist();
    }

    @Test
    void 판단_못_할_때는_새로_만들지_않는다() {
        StopRequests stop = new StopRequests(dir, Duration.ZERO);
        stop.update(new TestClock("2026-10-15T00:00:00Z").instant(), Pressure.UNKNOWN, "드라이브 여유를 읽지 못함");
        assertThat(stop.active()).isFalse();
        assertThat(collector()).doesNotExist();
    }

    @Test
    void 감시가_죽은_채_남은_파일은_이어받아_자원이_정상이면_유지_시간_뒤_지운다() throws Exception {
        Files.createDirectories(experiment().getParent());
        Files.writeString(experiment(), "requested-at=2026-10-14T00:00:00Z\n");
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        StopRequests stop = new StopRequests(dir, Duration.ofMinutes(5));

        stop.adoptLeftovers(clock.instant());
        assertThat(stop.active()).isTrue();
        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        assertThat(Files.readString(collector())).contains("requested-at=2026-10-14T00:00:00Z"); // 처음 요청 시각을 이어 씀
        assertThat(collector()).exists(); // 이어받는 동안 두 파일 모두 유지·갱신
        assertThat(Files.readString(experiment())).contains("confirmed-at=2026-10-15T00:00:00Z");

        clock.advance(Duration.ofMinutes(5));
        stop.update(clock.instant(), Pressure.CLEAR, "정상");
        assertThat(collector()).doesNotExist();
        assertThat(experiment()).doesNotExist();
    }

    @Test
    void 쓰지_못해도_던지지_않고_다음_점검에_다시_쓴다() throws Exception {
        Files.writeString(dir.resolve("collector-state"), "폴더 자리에 파일");
        StopRequests stop = new StopRequests(dir, Duration.ZERO);
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");

        stop.update(clock.instant(), Pressure.CRITICAL, "위험");
        assertThat(experiment()).exists(); // 한 파일 실패가 다른 파일을 막지 않음

        Files.delete(dir.resolve("collector-state"));
        stop.update(clock.instant(), Pressure.CRITICAL, "위험");
        assertThat(collector()).exists();
    }
}
