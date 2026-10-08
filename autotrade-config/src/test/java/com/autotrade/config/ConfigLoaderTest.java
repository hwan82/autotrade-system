package com.autotrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.account.BrokerId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    private static final String FAKE_SECRET = "PLACEHOLDER-NOT-A-REAL-KEY-7f3a";

    @TempDir
    Path dir;

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void 파일이_없으면_내장_default로_기동한다() {
        LoadedConfig loaded = loader.load(dir.resolve("runtime-config.json"));

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_NO_FILE);
        RuntimeConfig c = loaded.config();
        assertThat(c.marketData().priority()).containsExactly(BrokerId.KIWOOM, BrokerId.KIS, BrokerId.TOSS);
        assertThat(c.marketData().mode()).isEqualTo(MarketDataMode.AUTO);
        assertThat(c.lab().maxParallelRuns()).isEqualTo(4);
        assertThat(c.brokers().asMap().values()).noneMatch(RuntimeConfig.BrokerKeys::isConfigured);
        assertThat(c.notification().slack().enabled()).isFalse();
    }

    @Test
    void 외부_파일은_바꾼_키만_덮어쓴다() throws IOException {
        Path file = write("""
                { "lab": { "max-parallel-runs": 2 },
                  "brokers": { "kis": { "app-key": "%s", "app-secret": "%s" } } }
                """.formatted(FAKE_SECRET, FAKE_SECRET));

        LoadedConfig loaded = loader.load(file);

        assertThat(loaded.source()).isEqualTo(ConfigSource.RUNTIME_FILE);
        assertThat(loaded.config().lab().maxParallelRuns()).isEqualTo(2);
        assertThat(loaded.config().lab().defaultSeedCapitalKrw()).isEqualTo(10_000_000L);
        assertThat(loaded.config().brokers().kis().isConfigured()).isTrue();
        assertThat(loaded.config().brokers().kiwoom().isConfigured()).isFalse();
    }

    @Test
    void 배열은_통째로_바뀐다() throws IOException {
        Path file = write("""
                { "market-data": { "priority": ["KIS"] } }
                """);
        assertThat(loader.load(file).config().marketData().priority()).isEqualTo(List.of(BrokerId.KIS));
    }

    @Test
    void 문법이_깨진_파일이면_default로_기동하고_경고한다() throws IOException {
        Path file = write("{ \"brokers\": { \"kis\": { \"app-key\": \"" + FAKE_SECRET + "\" ");

        LoadedConfig loaded = loader.load(file);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.config()).isEqualTo(loader.defaults());
        assertThat(loaded.warning()).isNotBlank().doesNotContain(FAKE_SECRET);
    }

    @Test
    void 모르는_키는_오타로_보고_default로_기동한다() throws IOException {
        Path file = write("""
                { "lab": { "max-paralel-runs": 8 } }
                """);
        LoadedConfig loaded = loader.load(file);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("max-paralel-runs");
    }

    @Test
    void 범위를_벗어난_값은_키_이름과_함께_경고한다() throws IOException {
        Path file = write("""
                { "lab": { "max-parallel-runs": 0 } }
                """);
        LoadedConfig loaded = loader.load(file);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("lab.max-parallel-runs");
    }

    @Test
    void 비밀값을_잘못된_자리에_넣어도_경고에_값이_나오지_않는다() throws IOException {
        Path file = write("""
                { "lab": { "max-parallel-runs": "%s" } }
                """.formatted(FAKE_SECRET));
        LoadedConfig loaded = loader.load(file);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).doesNotContain(FAKE_SECRET);
    }

    @Test
    void 실전_매매_키가_있는_파일은_받아들이지_않는다() throws IOException {
        // RealTradingGuard와 실전 계좌는 설정 파일로 켤 수 없다(안전 불변식 1)
        for (String json : new String[] {
            "{ \"real-trading\": { \"accounts\": [ { \"id\": \"KIS-1\", \"broker\": \"KIS\", \"enabled\": true } ] } }",
            "{ \"real-trading-guard\": true }",
            "{ \"lab\": { \"real-trading\": true } }"
        }) {
            LoadedConfig loaded = loader.load(write(json));
            assertThat(loaded.source()).as(json).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
            assertThat(loaded.config()).isEqualTo(loader.defaults());
        }
    }

    @Test
    void 설정_스냅샷에는_실전_매매_항목이_없다() {
        // 실전 키를 RuntimeConfig에 넣으려면 이 테스트를 바꾸는 별도 PR(risk-ops 검토, 사용자 직접 머지)이 필요하다
        assertThat(java.util.Arrays.stream(RuntimeConfig.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("marketData", "lab", "notification", "brokers");
    }

    @Test
    void 알림을_켜면서_비밀값이_없으면_거부한다() throws IOException {
        Path file = write("""
                { "notification": { "slack": { "enabled": true } } }
                """);
        LoadedConfig loaded = loader.load(file);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("webhook-url");
    }

    private Path write(String json) throws IOException {
        Path file = dir.resolve("runtime-config.json");
        Files.writeString(file, json);
        return file;
    }
}
