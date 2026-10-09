package com.autotrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigHolderTest {

    private static final String FAKE_SECRET = "PLACEHOLDER-NOT-A-REAL-KEY-7f3a";
    private static final String FAKE_HASH = "PLACEHOLDER-OPERATOR-HASH";

    @TempDir
    Path dir;

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void 변경은_디스크에_먼저_쓰고_재기동하면_그대로_읽힌다() throws IOException {
        ConfigPaths paths = ConfigPaths.in(dir.resolve("conf"));
        RuntimeConfigHolder holder = new RuntimeConfigHolder(loader.defaults(), new RuntimeConfigStore(paths));

        RuntimeConfig updated = holder.update(c -> new RuntimeConfig(
                c.marketData(),
                new RuntimeConfig.Lab(8, c.lab().defaultSeedCapitalKrw(), c.lab().defaultSeedCapitalUsd()),
                new RuntimeConfig.Notification(
                        new RuntimeConfig.Slack(true, Secret.of(FAKE_SECRET)), c.notification().kakao()),
                c.brokers(), c.realTrading()));

        assertThat(holder.current()).isEqualTo(updated);
        LoadedConfig reloaded = loader.load(paths);
        assertThat(reloaded.source()).isEqualTo(ConfigSource.RUNTIME_FILE);
        assertThat(reloaded.warning()).isEmpty();
        assertThat(reloaded.config()).isEqualTo(updated);
        // 비밀값은 secrets.json에만 있다
        assertThat(Files.readString(paths.runtimeFile())).doesNotContain(FAKE_SECRET).doesNotContain("webhook-url");
        assertThat(Files.readString(paths.secretsFile())).contains(FAKE_SECRET);
    }

    @Test
    void 저장_파일과_폴더는_소유자만_접근하고_임시_파일이_남지_않는다() throws IOException {
        Path conf = dir.resolve("conf");
        new RuntimeConfigStore(ConfigPaths.in(conf)).save(loader.defaults());

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(conf))).isEqualTo("rwx------");
            for (String name : List.of(ConfigPaths.RUNTIME_FILE, ConfigPaths.SECRETS_FILE)) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(conf.resolve(name))))
                        .isEqualTo("rw-------");
            }
        }
        try (Stream<Path> files = Files.list(conf)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder(ConfigPaths.RUNTIME_FILE, ConfigPaths.SECRETS_FILE);
        }
    }

    @Test
    void 시세_구성과_증권사_키는_실시간으로_바꿀_수_없다() {
        ConfigPaths paths = ConfigPaths.in(dir);
        RuntimeConfigHolder holder = new RuntimeConfigHolder(loader.defaults(), new RuntimeConfigStore(paths));
        RuntimeConfig before = holder.current();

        assertThatThrownBy(() -> holder.update(c -> new RuntimeConfig(
                        new RuntimeConfig.MarketData(List.of(BrokerId.KIS), c.marketData().mode(),
                                c.marketData().tossPollingScope()),
                        c.lab(), c.notification(), c.brokers(), c.realTrading())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holder.update(c -> new RuntimeConfig(
                        c.marketData(), c.lab(), c.notification(),
                        new RuntimeConfig.Brokers(
                                new RuntimeConfig.BrokerKeys(Secret.of(FAKE_SECRET), Secret.of(FAKE_SECRET)),
                                c.brokers().kiwoom(), c.brokers().toss()),
                        c.realTrading())))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(holder.current()).isEqualTo(before);
        assertThat(paths.runtimeFile()).doesNotExist();
        assertThat(paths.secretsFile()).doesNotExist();
    }

    @Test
    void 실전_계좌는_실시간으로_바꿀_수_없다() {
        ConfigPaths paths = ConfigPaths.in(dir);
        RuntimeConfigHolder holder = new RuntimeConfigHolder(loader.defaults(), new RuntimeConfigStore(paths));

        assertThatThrownBy(() -> holder.update(c -> new RuntimeConfig(
                        c.marketData(), c.lab(), c.notification(), c.brokers(),
                        new RuntimeConfig.RealTrading(
                                List.of(new RuntimeConfig.RealAccount("KIS-1", BrokerId.KIS, Market.KRX)), Map.of(), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CFG-05");
        assertThat(paths.runtimeFile()).doesNotExist();
    }

    @Test
    void 계좌번호는_저장할_때_secrets로만_간다() throws IOException {
        ConfigPaths paths = ConfigPaths.in(dir.resolve("conf"));
        RuntimeConfig withAccount = new RuntimeConfig(
                loader.defaults().marketData(), loader.defaults().lab(), loader.defaults().notification(),
                loader.defaults().brokers(),
                new RuntimeConfig.RealTrading(
                        List.of(new RuntimeConfig.RealAccount("KIS-US-1", BrokerId.KIS, Market.US)),
                        Map.of("KIS-US-1", Secret.of(FAKE_SECRET)), Secret.of(FAKE_HASH)));

        new RuntimeConfigStore(paths).save(withAccount);

        assertThat(Files.readString(paths.runtimeFile())).contains("KIS-US-1").doesNotContain(FAKE_SECRET)
                .doesNotContain(FAKE_HASH);
        assertThat(Files.readString(paths.secretsFile())).contains(FAKE_SECRET).contains(FAKE_HASH)
                .doesNotContain("\"broker\"");
        LoadedConfig reloaded = loader.load(paths);
        assertThat(reloaded.warning()).isEmpty();
        assertThat(reloaded.config()).isEqualTo(withAccount);
    }

    @Test
    void 저장에_실패하면_메모리도_바뀌지_않는다() throws IOException {
        Path blocker = Files.writeString(dir.resolve("not-a-dir"), "x");
        RuntimeConfigHolder holder =
                new RuntimeConfigHolder(loader.defaults(), new RuntimeConfigStore(ConfigPaths.in(blocker)));
        RuntimeConfig before = holder.current();

        assertThatThrownBy(() -> holder.update(c -> new RuntimeConfig(
                        c.marketData(), new RuntimeConfig.Lab(1, 1L, 1L), c.notification(), c.brokers(),
                        c.realTrading())))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(holder.current()).isEqualTo(before);
    }
}
