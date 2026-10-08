package com.autotrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.account.BrokerId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigHolderTest {

    private static final String FAKE_SECRET = "PLACEHOLDER-NOT-A-REAL-KEY-7f3a";

    @TempDir
    Path dir;

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void 변경은_디스크에_먼저_쓰고_재기동하면_그대로_읽힌다() {
        Path file = dir.resolve("conf").resolve("runtime-config.json");
        RuntimeConfigHolder holder = new RuntimeConfigHolder(loader.defaults(), new RuntimeConfigStore(file));

        RuntimeConfig updated = holder.update(c -> new RuntimeConfig(
                c.marketData(),
                new RuntimeConfig.Lab(8, c.lab().defaultSeedCapitalKrw()),
                new RuntimeConfig.Notification(
                        new RuntimeConfig.Slack(true, Secret.of(FAKE_SECRET)), c.notification().kakao()),
                c.brokers()));

        assertThat(holder.current()).isEqualTo(updated);
        LoadedConfig reloaded = loader.load(file);
        assertThat(reloaded.source()).isEqualTo(ConfigSource.RUNTIME_FILE);
        assertThat(reloaded.config()).isEqualTo(updated);
        assertThat(reloaded.config().notification().slack().webhookUrl().reveal()).isEqualTo(FAKE_SECRET);
    }

    @Test
    void 저장_파일은_소유자만_읽을_수_있고_임시_파일이_남지_않는다() throws IOException {
        Path file = dir.resolve("runtime-config.json");
        new RuntimeConfigStore(file).save(loader.defaults());

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("runtime-config.json");
        }
    }

    @Test
    void 시세_구성과_증권사_키는_실시간으로_바꿀_수_없다() {
        Path file = dir.resolve("runtime-config.json");
        RuntimeConfigHolder holder = new RuntimeConfigHolder(loader.defaults(), new RuntimeConfigStore(file));
        RuntimeConfig before = holder.current();

        assertThatThrownBy(() -> holder.update(c -> new RuntimeConfig(
                        new RuntimeConfig.MarketData(List.of(BrokerId.KIS), c.marketData().mode(),
                                c.marketData().tossPollingScope()),
                        c.lab(), c.notification(), c.brokers())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holder.update(c -> new RuntimeConfig(
                        c.marketData(), c.lab(), c.notification(),
                        new RuntimeConfig.Brokers(
                                new RuntimeConfig.BrokerKeys(Secret.of(FAKE_SECRET), Secret.of(FAKE_SECRET)),
                                c.brokers().kiwoom(), c.brokers().toss()))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(holder.current()).isEqualTo(before);
        assertThat(file).doesNotExist();
    }

    @Test
    void 저장에_실패하면_메모리도_바뀌지_않는다() throws IOException {
        Path blocker = Files.writeString(dir.resolve("not-a-dir"), "x");
        RuntimeConfigHolder holder =
                new RuntimeConfigHolder(loader.defaults(), new RuntimeConfigStore(blocker.resolve("runtime-config.json")));
        RuntimeConfig before = holder.current();

        assertThatThrownBy(() -> holder.update(c -> new RuntimeConfig(
                        c.marketData(), new RuntimeConfig.Lab(1, 1L), c.notification(), c.brokers())))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(holder.current()).isEqualTo(before);
    }
}
