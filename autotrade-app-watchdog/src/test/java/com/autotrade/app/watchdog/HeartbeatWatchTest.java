package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.watchdog.HeartbeatWatch.Change;
import com.autotrade.app.watchdog.HeartbeatWatch.State;
import com.autotrade.monitor.Heartbeat;
import com.autotrade.monitor.HeartbeatWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeartbeatWatchTest {

    @TempDir
    Path dir;

    @Test
    void 살아_있음_멈춤_꺼짐을_가르고_바뀔_때만_알린다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        HeartbeatWatch watch = new HeartbeatWatch(dir, List.of("engine", "gateway"), Duration.ofSeconds(15));
        HeartbeatWriter engine = new HeartbeatWriter(dir, "engine", clock);
        engine.beat();

        List<Change> first = watch.check(clock.instant());
        assertThat(first).extracting(Change::process, Change::from, Change::to)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("engine", null, State.ALIVE),
                        org.assertj.core.groups.Tuple.tuple("gateway", null, State.ABSENT));

        clock.advance(Duration.ofSeconds(15));
        assertThat(watch.check(clock.instant())).isEmpty(); // 기준과 같으면 아직 살아 있음

        clock.advance(Duration.ofSeconds(1));
        List<Change> stale = watch.check(clock.instant());
        assertThat(stale).singleElement().satisfies(c -> {
            assertThat(c.process()).isEqualTo("engine");
            assertThat(c.from()).isEqualTo(State.ALIVE);
            assertThat(c.to()).isEqualTo(State.STALE);
            assertThat(c.age()).isEqualTo(Duration.ofSeconds(16));
        });
        assertThat(watch.check(clock.instant())).isEmpty(); // 같은 상태는 다시 알리지 않음

        engine.beat();
        assertThat(watch.check(clock.instant())).extracting(Change::to).containsExactly(State.ALIVE);

        engine.close(); // 정상 종료는 파일을 지운다
        assertThat(watch.check(clock.instant())).extracting(Change::to).containsExactly(State.ABSENT);
        assertThat(watch.states()).containsEntry("engine", State.ABSENT).containsEntry("gateway", State.ABSENT);
    }

    @Test
    void 해석할_수_없거나_다른_프로세스_이름이면_읽을_수_없음이다() throws Exception {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        HeartbeatWatch watch = new HeartbeatWatch(dir, List.of("lab"), Duration.ofSeconds(15));
        Path file = Heartbeat.file(dir, "lab");
        Files.createDirectories(file.getParent());

        Files.writeString(file, "반쯤 쓴");
        assertThat(watch.check(clock.instant())).extracting(Change::to).containsExactly(State.UNREADABLE);

        Files.writeString(file, new Heartbeat("engine", 1, clock.instant(), clock.instant(), 1).format());
        assertThat(watch.check(clock.instant())).isEmpty();
        assertThat(watch.states()).containsEntry("lab", State.UNREADABLE);
    }

    @Test
    void 기록_시각이_기준보다_많이_미래면_멈춤으로_본다() throws Exception {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        HeartbeatWatch watch = new HeartbeatWatch(dir, List.of("gateway"), Duration.ofSeconds(15));
        Path file = Heartbeat.file(dir, "gateway");
        Files.createDirectories(file.getParent());
        java.time.Instant future = clock.instant().plus(Duration.ofHours(9)); // 시간대를 잘못 맞춘 시계 등
        Files.writeString(file, new Heartbeat("gateway", 1, future, future, 1).format());

        assertThat(watch.check(clock.instant())).extracting(Change::to).containsExactly(State.STALE);
    }

    @Test
    void 점검_사이_재기동은_상태가_그대로여도_시작_시각으로_알아본다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        HeartbeatWatch watch = new HeartbeatWatch(dir, List.of("lab"), Duration.ofSeconds(15));
        new HeartbeatWriter(dir, "lab", clock).beat();
        watch.check(clock.instant());

        clock.advance(Duration.ofSeconds(5));
        new HeartbeatWriter(dir, "lab", clock).beat(); // 새 프로세스(새 시작 시각)

        assertThat(watch.check(clock.instant())).isEmpty(); // 상태는 계속 ALIVE
        assertThat(watch.states()).containsEntry("lab", State.ALIVE);
    }

    @Test
    void 첫_점검에서_모두_꺼짐이면_알리고_하나라도_있으면_알리지_않는다() {
        // 설정 폴더를 잘못 적으면 빈 폴더를 지키며 모두 꺼짐으로만 보인다(architect PR #116 N2)
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        HeartbeatWatch empty = new HeartbeatWatch(dir, List.of("engine", "gateway", "lab"), Duration.ofSeconds(15));
        empty.check(clock.instant());
        assertThat(empty.firstCheckAllAbsent()).isTrue();

        new HeartbeatWriter(dir, "gateway", clock).beat();
        HeartbeatWatch some = new HeartbeatWatch(dir, List.of("engine", "gateway", "lab"), Duration.ofSeconds(15));
        some.check(clock.instant());
        assertThat(some.firstCheckAllAbsent()).isFalse();
    }

    @Test
    void 상한보다_큰_하트비트_파일은_읽지_않고_읽을_수_없음이다() throws Exception {
        // PR #116 독립 QA R1: 비정상으로 커진 파일이 감시 힙을 채우지 않게 4KB 상한
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        HeartbeatWatch watch = new HeartbeatWatch(dir, List.of("engine"), Duration.ofSeconds(15));
        Path file = Heartbeat.file(dir, "engine");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new Heartbeat("engine", 1, clock.instant(), clock.instant(), 1).format() + "\n".repeat(5000)); // 다 읽으면 정상으로 해석되는 내용
        assertThat(watch.check(clock.instant())).extracting(Change::to).containsExactly(State.UNREADABLE);
    }
}
