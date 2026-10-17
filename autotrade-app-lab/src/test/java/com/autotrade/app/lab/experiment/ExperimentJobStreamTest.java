package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentJobStatus;
import com.autotrade.sim.run.ExperimentProgress;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** D-093: 진행 상태 SSE는 바뀐 첫 페이지만 보내고, 새 연결에는 지금 상태를 주며, 떠난 연결과 상한을 지킨다. */
class ExperimentJobStreamTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final String JOB = "EXP-20261015-RUN01";

    @TempDir
    Path config;

    private final MutableClock clock = new MutableClock(T0);
    private ExperimentJobFiles files;
    private ExperimentJobStream stream;

    @BeforeEach
    void setUp() throws IOException {
        files = ExperimentJobFiles.in(config);
        write(ExperimentJobStatus.State.RUNNING, T0, 1);
        stream = new ExperimentJobStream(new ExperimentJobs(config, clock), clock); // 스레드 없이 tick을 직접 부른다
    }

    private void write(ExperimentJobStatus.State state, Instant updated, int finished) throws IOException {
        files.write(new ExperimentJobStatus(JOB, "combination", state, 1234, T0, updated, 4, 1, 0,
                new ExperimentProgress(JOB, ExperimentProgress.Phase.RUNNING, 4, 2, finished, 0, 10, finished * 10L),
                ""));
    }

    @Test
    void 바뀌었을_때만_보내고_새_연결에는_지금_상태를_준다() throws IOException {
        Capture a = new Capture();
        assertThat(stream.register(a)).isTrue();
        stream.tick();
        assertThat(a.snapshots()).hasSize(1);
        assertThat(a.snapshots().get(0).items().get(0).runsFinished()).isEqualTo(1);
        assertThat(a.snapshots().get(0).total()).isEqualTo(1);

        stream.tick(); // 그대로
        assertThat(a.snapshots()).hasSize(1);

        Capture b = new Capture();
        stream.register(b);
        stream.tick(); // 그대로지만 b는 처음
        assertThat(a.snapshots()).hasSize(1);
        assertThat(b.snapshots()).hasSize(1);

        clock.set(T0.plusSeconds(5));
        write(ExperimentJobStatus.State.RUNNING, T0.plusSeconds(5), 2);
        stream.tick();
        assertThat(a.snapshots()).hasSize(2);
        assertThat(b.snapshots()).hasSize(2);
        assertThat(a.snapshots().get(1).items().get(0).runsFinished()).isEqualTo(2);
    }

    @Test
    void 갱신이_멈춘_실행은_응답_없음으로_바뀌어_간다() {
        Capture a = new Capture();
        stream.register(a);
        stream.tick();
        clock.set(T0.plus(ExperimentJobStatus.STALE_AFTER).plusSeconds(1));
        stream.tick();
        assertThat(a.snapshots()).hasSize(2);
        assertThat(a.snapshots().get(1).items().get(0).state()).isEqualTo("UNRESPONSIVE");
    }

    @Test
    void 조용하면_주석_줄을_보낸다() {
        Capture a = new Capture();
        stream.register(a);
        stream.tick();
        clock.set(T0.plus(ExperimentJobStream.KEEPALIVE).minusSeconds(1));
        stream.tick();
        assertThat(a.comments).isZero();
        clock.set(T0.plus(ExperimentJobStream.KEEPALIVE));
        stream.tick();
        assertThat(a.comments).isEqualTo(1);
        assertThat(a.snapshots()).hasSize(1);
    }

    @Test
    void 작업_폴더_읽기가_null이면_공개_생성자가_바로_거절한다() {
        // PR #156 architect R2: 시험용 생성자와 겹쳐 null 인자 호출이 모호하던 것을 없앴다(컴파일되는 것 자체가 확인)
        assertThatThrownBy(() -> new ExperimentJobStream(null, clock)).isInstanceOf(NullPointerException.class)
                .hasMessage("jobs");
    }

    @Test
    void 작업_폴더를_못_읽어도_주석_줄은_보내고_예외는_다시_던진다() {
        // architect PR #117 2차 권고 1: 읽기 실패 분기(tick의 catch에서 keepalive 뒤 재던짐)
        ExperimentJobs real = new ExperimentJobs(config, clock);
        java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean();
        UncheckedIOException failure = new UncheckedIOException("작업 폴더를 읽지 못했습니다", new IOException("잠김"));
        ExperimentJobStream s = ExperimentJobStream.reading(() -> {
            if (broken.get()) {
                throw failure;
            }
            return real.list();
        }, clock);
        Capture a = new Capture();
        s.register(a);
        s.tick();
        assertThat(a.snapshots()).hasSize(1);

        broken.set(true);
        clock.set(T0.plus(ExperimentJobStream.KEEPALIVE).minusSeconds(1));
        assertThatThrownBy(s::tick).isSameAs(failure);
        assertThat(a.comments).isZero(); // 주기 전에는 보내지 않는다

        clock.set(T0.plus(ExperimentJobStream.KEEPALIVE));
        assertThatThrownBy(s::tick).isSameAs(failure);
        assertThat(a.comments).isEqualTo(1);
        assertThat(a.snapshots()).hasSize(1);

        // 떠난 연결은 읽기 실패 중에도 주석 줄 보내기에서 빠진다(상한 자리를 차지하지 않게)
        a.fail = true;
        clock.set(T0.plus(ExperimentJobStream.KEEPALIVE.multipliedBy(2)));
        assertThatThrownBy(s::tick).isSameAs(failure);
        assertThat(s.openStreams()).isZero();
    }

    @Test
    void 보내기에_실패한_연결은_빠진다() {
        Capture gone = new Capture();
        gone.fail = true;
        Capture a = new Capture();
        stream.register(gone);
        stream.register(a);
        stream.tick();
        assertThat(stream.openStreams()).isEqualTo(1);
        assertThat(a.snapshots()).hasSize(1);
    }

    @Test
    void 연결_수_상한과_종료() {
        List<Capture> open = new ArrayList<>();
        for (int i = 0; i < ExperimentJobStream.MAX_STREAMS; i++) {
            Capture c = new Capture();
            assertThat(stream.register(c)).isTrue();
            open.add(c);
        }
        assertThat(stream.register(new Capture())).isFalse();
        assertThat(stream.open()).isEmpty();

        stream.closeStreams();
        assertThat(stream.openStreams()).isZero();
        assertThat(open).allMatch(c -> c.completed);
        assertThat(stream.register(new Capture())).isFalse(); // 종료 중에는 새 연결을 받지 않는다
    }

    @Test
    void 첫_페이지는_50개까지() throws IOException {
        for (int i = 0; i < ExperimentJobStream.PAGE_SIZE + 3; i++) {
            String job = String.format("EXP-20261015-J%04d", i);
            files.write(new ExperimentJobStatus(job, "combination", ExperimentJobStatus.State.COMPLETED, 1, T0, T0, 4, 1, 0,
                    null, ""));
        }
        Capture a = new Capture();
        stream.register(a);
        stream.tick();
        ExperimentJobStream.Snapshot s = a.snapshots().get(0);
        assertThat(s.items()).hasSize(ExperimentJobStream.PAGE_SIZE);
        assertThat(s.total()).isEqualTo(ExperimentJobStream.PAGE_SIZE + 4);
    }

    /** 보낸 이벤트를 모으는 연결. */
    static final class Capture extends SseEmitter {
        final List<Object> data = new ArrayList<>();
        int comments;
        boolean fail;
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (fail) {
                throw new IOException("떠난 브라우저");
            }
            boolean any = false;
            for (var item : builder.build()) {
                if (item.getData() instanceof ExperimentJobStream.Snapshot s) {
                    data.add(s);
                    any = true;
                }
            }
            if (!any) {
                comments++;
            }
        }

        @Override
        public void send(Object object, MediaType mediaType) throws IOException {
            throw new AssertionError("이벤트 빌더로만 보낸다");
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        List<ExperimentJobStream.Snapshot> snapshots() {
            return data.stream().map(ExperimentJobStream.Snapshot.class::cast).toList();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
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
