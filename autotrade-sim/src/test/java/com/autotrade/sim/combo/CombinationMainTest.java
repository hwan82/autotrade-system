package com.autotrade.sim.combo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실행 명령 인자 검사(PR #50 architect F2·F4). */
class CombinationMainTest {

    @Test
    void 실험_ID가_experiments_밖을_가리키면_실행하지_않는다(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("x"));
        Files.writeString(root.resolve("x").resolve("experiment.yaml"), "id: x\n");
        Files.createDirectories(root.resolve("experiments"));

        assertThat(CombinationMain.run(new String[] {root.toString(), "abc", "false", "../x"})).isEqualTo(1);
        assertThat(CombinationMain.run(new String[] {root.toString(), "abc", "false", ".."})).isEqualTo(1);
        assertThat(CombinationMain.run(new String[] {root.toString(), "abc", "false", "a/b"})).isEqualTo(1);
        // 형식은 맞지만 등록 파일이 없으면 입력 오류
        assertThat(CombinationMain.run(new String[] {root.toString(), "abc", "false", "EXP-20261011-CMB01"}))
                .isEqualTo(1);
    }

    @Test
    void 설정_폴더는_시스템_속성을_먼저_본다(@TempDir Path dir) {
        String before = System.getProperty("autotrade.config-dir");
        try {
            System.setProperty("autotrade.config-dir", dir.toString());
            assertThat(CombinationMain.configDir()).isEqualTo(dir);
        } finally {
            if (before == null) {
                System.clearProperty("autotrade.config-dir");
            } else {
                System.setProperty("autotrade.config-dir", before);
            }
        }
    }
}
