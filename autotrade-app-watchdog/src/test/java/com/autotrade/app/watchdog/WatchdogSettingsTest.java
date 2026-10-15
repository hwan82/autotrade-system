package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchdogSettingsTest {

    @TempDir
    Path dir;

    @Test
    void 파일이_없으면_기본값이다() {
        WatchdogSettings s = WatchdogSettings.load(dir);

        assertThat(s.interval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(s.heartbeatStale()).isEqualTo(Duration.ofSeconds(15));
        assertThat(s.processes()).containsExactly("engine", "gateway", "lab");
        assertThat(s.disks()).containsExactly(dir.toAbsolutePath());
        assertThat(s.diskWarnGb()).isEqualTo(50.0);
        assertThat(s.diskCriticalGb()).isEqualTo(20.0);
        assertThat(s.diskSustain()).isEqualTo(1);
        assertThat(s.releaseHold()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void 파일의_값을_읽는다() throws Exception {
        Path other = Files.createDirectory(dir.resolve("recordings"));
        Files.writeString(dir.resolve(WatchdogSettings.FILE), """
                # 감시 설정
                interval-seconds = 2
                heartbeat-stale-seconds=30
                processes=engine, gateway
                disks=%s,%s
                disk.free-gb=100:30.5:2
                release-hold-seconds=0
                """.formatted(dir, other));

        WatchdogSettings s = WatchdogSettings.load(dir);

        assertThat(s.interval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(s.heartbeatStale()).isEqualTo(Duration.ofSeconds(30));
        assertThat(s.processes()).containsExactly("engine", "gateway");
        assertThat(s.disks()).containsExactly(dir.toAbsolutePath(), other.toAbsolutePath());
        assertThat(s.diskWarnGb()).isEqualTo(100.0);
        assertThat(s.diskCriticalGb()).isEqualTo(30.5);
        assertThat(s.diskSustain()).isEqualTo(2);
        assertThat(s.releaseHold()).isZero();
    }

    @Test
    void 모르는_키는_오타로_보고_기동을_거부한다() {
        assertThatThrownBy(() -> WatchdogSettings.parse("interval-second=5", dir))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("모르는 키").hasMessageContaining("interval-second");
    }

    @Test
    void 틀린_값은_모두_모아_거부한다() {
        String text = String.join("\n", List.of(
                "interval-seconds=0",
                "heartbeat-stale-seconds=abc",
                "processes=engine,collector",
                "disks=" + dir.resolve("missing-dir"),
                "disk.free-gb=20:50:1",
                "release-hold-seconds=99999",
                "잘못된 줄",
                "interval-seconds=5"));

        assertThatThrownBy(() -> WatchdogSettings.parse(text, dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval-seconds는 1~60")
                .hasMessageContaining("heartbeat-stale-seconds가 정수가 아닙니다")
                .hasMessageContaining("모르는 프로세스입니다")
                .hasMessageContaining("없는 폴더")
                .hasMessageContaining("0 < 위험 ≤ 경고")
                .hasMessageContaining("release-hold-seconds는 0~3600")
                .hasMessageContaining("키=값 형식이 아닙니다")
                .hasMessageContaining("같은 키가 두 번");
    }

    @Test
    void 임계_형식과_숫자를_검사한다() {
        assertThatThrownBy(() -> WatchdogSettings.parse("disk.free-gb=50:20", dir)).hasMessageContaining("경고GB:위험GB:연속");
        assertThatThrownBy(() -> WatchdogSettings.parse("disk.free-gb=50:x:1", dir)).hasMessageContaining("숫자가 아닙니다");
        assertThatThrownBy(() -> WatchdogSettings.parse("disk.free-gb=50:0:1", dir)).hasMessageContaining("0 < 위험");
        assertThatThrownBy(() -> WatchdogSettings.parse("disk.free-gb=50:20:0", dir)).hasMessageContaining("연속은 1~10");
        assertThatThrownBy(() -> WatchdogSettings.parse("interval-seconds=20\nheartbeat-stale-seconds=10", dir))
                .hasMessageContaining("interval-seconds(20) 이상");
        assertThatThrownBy(() -> WatchdogSettings.parse("disks= , ", dir)).hasMessageContaining("disks가 비었습니다");
    }

    @Test
    void 처음_쓰는_PC라_설정_폴더가_없으면_만들고_기본값으로_뜬다() {
        Path fresh = dir.resolve("fresh-config");

        WatchdogSettings s = WatchdogSettings.load(fresh);

        assertThat(fresh).isDirectory();
        assertThat(s.disks()).containsExactly(fresh.toAbsolutePath());
    }
}
