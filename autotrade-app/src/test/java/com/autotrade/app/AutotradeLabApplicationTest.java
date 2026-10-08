package com.autotrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigSource;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.RuntimeConfigHolder;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AutotradeLabApplicationTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void configPath(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-path", () -> dir.resolve("runtime-config.json").toString());
    }

    @Autowired
    Clock clock;

    @Autowired
    LoadedConfig loaded;

    @Autowired
    RuntimeConfigHolder holder;

    @Test
    void 설정_파일이_없어도_default로_기동한다() {
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_NO_FILE);
        assertThat(holder.current()).isEqualTo(loaded.config());
        assertThat(clock.getZone().getId()).isEqualTo("Z");
    }
}
