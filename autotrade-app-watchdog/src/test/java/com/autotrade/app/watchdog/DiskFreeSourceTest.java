package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.monitor.Reading;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class DiskFreeSourceTest {

    private final Path c = Path.of("c-drive");
    private final Path d = Path.of("d-drive");

    @Test
    void 읽지_못한_경로만_빼고_원인을_남기며_다시_읽으면_원인을_지운다() {
        // PR #119 risk-ops K3·QA R3(D-108)
        Set<Path> failing = ConcurrentHashMap.newKeySet();
        failing.add(d);
        DiskFreeSource source = new DiskFreeSource(List.of(c, d), path -> {
            if (failing.contains(path)) {
                throw new IOException("드라이브를 찾을 수 없음: " + path);
            }
            return 3L << 30;
        });

        assertThat(source.read()).extracting(Reading::name).containsExactly(DiskFreeSource.readingName(c));
        assertThat(source.failures()).containsExactly(
                java.util.Map.entry(DiskFreeSource.readingName(d), "IOException: 드라이브를 찾을 수 없음: d-drive"));

        failing.clear();
        assertThat(source.read()).hasSize(2);
        assertThat(source.failures()).isEmpty();
    }

    @Test
    void 원인은_한_줄이고_길면_자르며_메시지가_없으면_종류만() {
        assertThat(DiskFreeSource.cause(new IOException("가\n나\r다"))).isEqualTo("IOException: 가 나 다");
        assertThat(DiskFreeSource.cause(new IllegalStateException())).isEqualTo("IllegalStateException");
        assertThat(DiskFreeSource.cause(new IOException("  "))).isEqualTo("IOException");
        String longMessage = "x".repeat(DiskFreeSource.CAUSE_MAX + 50);
        assertThat(DiskFreeSource.cause(new IOException(longMessage)))
                .isEqualTo("IOException: " + "x".repeat(DiskFreeSource.CAUSE_MAX) + "…");
        assertThat(DiskFreeSource.cause(new IOException("x".repeat(DiskFreeSource.CAUSE_MAX))))
                .isEqualTo("IOException: " + "x".repeat(DiskFreeSource.CAUSE_MAX)); // 경계: 자르지 않음
        // 익명 예외는 이름이 빈 문자열이라 클래스 이름 전체로(PR #119 QA R2와 같은 표기)
        assertThat(DiskFreeSource.cause(new RuntimeException("m") {})).startsWith(DiskFreeSourceTest.class.getName() + "$").endsWith(": m");
    }
}
