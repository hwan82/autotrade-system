package com.autotrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    private static final String FAKE_SECRET = "PLACEHOLDER-NOT-A-REAL-KEY-7f3a";

    @TempDir
    Path dir;

    private ConfigPaths paths;
    private final ConfigLoader loader = new ConfigLoader();

    @BeforeEach
    void setUp() {
        paths = ConfigPaths.in(dir);
    }

    @Test
    void 파일이_없으면_내장_default로_기동한다() {
        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_NO_FILE);
        assertThat(loaded.warning()).isEmpty();
        RuntimeConfig c = loaded.config();
        // 토스 시세 조회는 무료(Q-020 사용자 확인, D-039)라 D-024 순서(키움 1, 토스 2, 한국투자 3)가 기본값이다
        assertThat(c.marketData().priority()).containsExactly(BrokerId.KIWOOM, BrokerId.TOSS, BrokerId.KIS);
        assertThat(c.marketData().mode()).isEqualTo(MarketDataMode.AUTO);
        assertThat(c.lab().maxParallelRuns()).isEqualTo(4);
        assertThat(c.lab().defaultSeedCapitalKrw()).isEqualTo(10_000_000L);
        assertThat(c.lab().defaultSeedCapitalUsd()).isEqualTo(10_000L);
        assertThat(c.brokers().asMap().values()).noneMatch(RuntimeConfig.BrokerKeys::isConfigured);
        assertThat(c.notification().slack().enabled()).isFalse();
    }

    @Test
    void 우선순위는_default_다음_runtime_config_다음_secrets다() throws IOException {
        runtime("""
                { "lab": { "max-parallel-runs": 2 } }
                """);
        secrets("""
                { "brokers": { "kis": { "app-key": "%s", "app-secret": "%s" } } }
                """.formatted(FAKE_SECRET, FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.RUNTIME_FILE);
        assertThat(loaded.warning()).isEmpty();
        assertThat(loaded.config().lab().maxParallelRuns()).isEqualTo(2);
        assertThat(loaded.config().lab().defaultSeedCapitalKrw()).isEqualTo(10_000_000L);
        assertThat(loaded.config().brokers().kis().isConfigured()).isTrue();
        assertThat(loaded.config().brokers().kiwoom().isConfigured()).isFalse();
    }

    @Test
    void 배열은_통째로_바뀐다() throws IOException {
        runtime("""
                { "market-data": { "priority": ["KIS"] } }
                """);
        assertThat(loader.load(paths).config().marketData().priority()).isEqualTo(List.of(BrokerId.KIS));
    }

    @Test
    void 사용자가_토스를_빼면_적은_순서대로_쓴다() throws IOException {
        // 기본값은 토스를 포함하지만(D-039) 사용자가 runtime-config에서 빼면 그 목록만 쓴다
        runtime("""
                { "market-data": { "priority": ["KIWOOM", "KIS"] } }
                """);
        LoadedConfig loaded = loader.load(paths);
        assertThat(loaded.source()).isEqualTo(ConfigSource.RUNTIME_FILE);
        assertThat(loaded.config().marketData().priority())
                .containsExactly(BrokerId.KIWOOM, BrokerId.KIS);
    }

    @Test
    void 일반_설정_파일에_비밀값이_있으면_그_파일을_쓰지_않는다() throws IOException {
        runtime("""
                { "lab": { "max-parallel-runs": 2 }, "brokers": { "kis": { "app-key": "%s" } } }
                """.formatted(FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.config()).isEqualTo(loader.defaults());
        assertThat(loaded.warning()).contains("brokers/kis/app-key").doesNotContain(FAKE_SECRET);
    }

    @Test
    void 비밀_파일에_일반_설정이_있으면_비밀_파일만_쓰지_않는다() throws IOException {
        runtime("""
                { "lab": { "max-parallel-runs": 2 } }
                """);
        secrets("""
                { "lab": { "max-parallel-runs": 64 }, "brokers": { "kis": { "app-key": "%s", "app-secret": "%s" } } }
                """.formatted(FAKE_SECRET, FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.RUNTIME_FILE);
        assertThat(loaded.config().lab().maxParallelRuns()).isEqualTo(2);
        assertThat(loaded.config().brokers().kis().isConfigured()).isFalse();
        assertThat(loaded.warning()).contains("secrets.json", "lab/max-parallel-runs").doesNotContain(FAKE_SECRET);
    }

    @Test
    void 문법이_깨진_파일이면_default로_기동하고_경고한다() throws IOException {
        secrets("{ \"brokers\": { \"kis\": { \"app-key\": \"" + FAKE_SECRET + "\" ");
        runtime("{ \"lab\": ");

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.config()).isEqualTo(loader.defaults());
        assertThat(loaded.warning()).contains("runtime-config.json", "secrets.json").doesNotContain(FAKE_SECRET);
    }

    @Test
    void 모르는_키는_오타로_보고_default로_기동한다() throws IOException {
        runtime("""
                { "lab": { "max-paralel-runs": 8 } }
                """);
        LoadedConfig loaded = loader.load(paths);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("max-paralel-runs");
    }

    @Test
    void 범위를_벗어난_값은_키_이름과_함께_경고한다() throws IOException {
        runtime("""
                { "lab": { "max-parallel-runs": 99990001 } }
                """);
        LoadedConfig loaded = loader.load(paths);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        // 값은 싣지 않는다(PR #102 risk-ops F7): 숫자 자리에 계좌번호를 적었을 수 있다
        assertThat(loaded.warning()).contains("lab.max-parallel-runs").doesNotContain("99990001");
    }

    @Test
    void 비밀값을_잘못된_자리에_넣어도_경고에_값이_나오지_않는다() throws IOException {
        runtime("""
                { "lab": { "max-parallel-runs": "%s" } }
                """.formatted(FAKE_SECRET));
        LoadedConfig loaded = loader.load(paths);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).doesNotContain(FAKE_SECRET);
    }

    @Test
    void 알림을_켜면서_비밀값이_없으면_거부한다() throws IOException {
        runtime("""
                { "notification": { "slack": { "enabled": true } } }
                """);
        LoadedConfig loaded = loader.load(paths);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("webhook-url");
    }

    @Test
    void 실전_활성화_키가_있는_파일은_받아들이지_않는다() throws IOException {
        // RealTradingGuard는 설정 파일로 켤 수 없다(안전 불변식 1, CFG-02). enabled 같은 키는 모르는 키라 파일 전체를 버린다
        for (String json : new String[] {
            "{ \"real-trading\": { \"accounts\": [ { \"id\": \"KIS-1\", \"broker\": \"KIS\", \"market\": \"KRX\", \"enabled\": true } ] } }",
            "{ \"real-trading\": { \"enabled\": true } }",
            "{ \"real-trading\": { \"guard\": \"OPEN\" } }",
            "{ \"real-trading-guard\": true }",
            "{ \"lab\": { \"real-trading\": true } }"
        }) {
            runtime(json);
            LoadedConfig loaded = loader.load(paths);
            assertThat(loaded.source()).as(json).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
            assertThat(loaded.config()).isEqualTo(loader.defaults());
        }
        Files.delete(paths.runtimeFile());
        secrets("{ \"real-trading\": { \"enabled\": true } }");
        assertThat(loader.load(paths).warning()).contains("secrets.json");
    }

    @Test
    void 설정_스냅샷에는_실전_활성화_상태가_없다() {
        // 실전 키 구조를 바꾸려면 이 테스트를 바꾸는 PR(risk-ops 검토, 사용자 직접 머지)이 필요하다
        assertThat(Arrays.stream(RuntimeConfig.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("marketData", "lab", "notification", "brokers", "realTrading");
        assertThat(Arrays.stream(RuntimeConfig.RealTrading.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("accounts", "accountNumbers", "operatorPasswordHash");
        assertThat(Arrays.stream(RuntimeConfig.RealAccount.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("id", "broker", "market");
    }

    @Test
    void 실전_계좌는_runtime_config에_계좌번호는_secrets에_둔다() throws IOException {
        runtime("""
                { "real-trading": { "accounts": [
                    { "id": "KIS-US-1", "broker": "KIS", "market": "US" },
                    { "id": "KIWOOM-KR-1", "broker": "KIWOOM", "market": "KRX" } ] } }
                """);
        secrets("""
                { "real-trading": { "account-numbers": { "KIS-US-1": "%s" } } }
                """.formatted(FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.warning()).isEmpty();
        RuntimeConfig.RealTrading rt = loaded.config().realTrading();
        assertThat(rt.accountRefs()).containsExactly(
                new AccountRef(new AccountId("KIS-US-1"), BrokerId.KIS, Market.US),
                new AccountRef(new AccountId("KIWOOM-KR-1"), BrokerId.KIWOOM, Market.KRX));
        assertThat(rt.isConfigured("KIS-US-1")).isTrue();
        assertThat(rt.isConfigured("KIWOOM-KR-1")).isFalse();
        assertThat(rt.accountNumbers().get("KIS-US-1").reveal()).isEqualTo(FAKE_SECRET);
        assertThat(rt.toString()).doesNotContain(FAKE_SECRET);
    }

    @Test
    void 기본값에는_실전_계좌가_없다() {
        assertThat(loader.defaults().realTrading().accounts()).isEmpty();
        assertThat(loader.defaults().realTrading().accountNumbers()).isEmpty();
    }

    @Test
    void 계좌번호가_runtime_config에_있으면_그_파일을_거부한다() throws IOException {
        runtime("""
                { "real-trading": {
                    "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ],
                    "account-numbers": { "KIS-US-1": "%s" } } }
                """.formatted(FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        // 키 이름(별칭)도 남기지 않는다: 별칭 자리에 계좌번호를 잘못 적었을 수 있다(PR #100 QA R2). 경로 패턴과 개수만
        assertThat(loaded.warning()).contains("real-trading/account-numbers/* 1개")
                .doesNotContain(FAKE_SECRET).doesNotContain("KIS-US-1");
    }

    @Test
    void runtime_config의_계좌번호_키_자리에_적은_값도_경고에_남기지_않는다() throws IOException {
        runtime("""
                { "real-trading": { "account-numbers": { "%s": "x", "%s-2": "y" } } }
                """.formatted(FAKE_SECRET, FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("real-trading/account-numbers/* 2개").doesNotContain(FAKE_SECRET);
    }

    @Test
    void 목록에_없는_계좌의_계좌번호는_버리고_증권사_키는_지키며_값을_남기지_않는다() throws IOException {
        runtime("""
                { "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
                """);
        // 별칭 자리에 계좌번호를 잘못 적은 경우
        secrets("""
                { "brokers": { "kis": { "app-key": "%s", "app-secret": "%s" } },
                  "real-trading": { "account-numbers": { "%s": "%s" } } }
                """.formatted(FAKE_SECRET, FAKE_SECRET, FAKE_SECRET, FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.RUNTIME_FILE);
        assertThat(loaded.warning()).contains("1개를 무시").doesNotContain(FAKE_SECRET);
        assertThat(loaded.config().brokers().kis().isConfigured()).isTrue();
        assertThat(loaded.config().realTrading().accounts()).hasSize(1);
        assertThat(loaded.config().realTrading().accountNumbers()).isEmpty();
    }

    @Test
    void runtime_config가_깨져도_계좌번호_때문에_증권사_키를_잃지_않는다() throws IOException {
        // 시세 게이트웨이가 runtime-config.json 문제로 증권사 키까지 잃으면 안 된다(D-013 프로세스 독립)
        runtime("{ \"lab\": ");
        secrets("""
                { "brokers": { "kis": { "app-key": "%s", "app-secret": "%s" } },
                  "real-trading": { "account-numbers": { "KIS-US-1": "%s" } } }
                """.formatted(FAKE_SECRET, FAKE_SECRET, FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.config().brokers().kis().isConfigured()).isTrue();
        assertThat(loaded.config().realTrading().accountNumbers()).isEmpty();
        assertThat(loaded.warning()).contains("1개를 무시").doesNotContain(FAKE_SECRET).doesNotContain("KIS-US-1");
    }

    @Test
    void secrets의_위치가_틀린_키_아래_키_이름은_경고에_남기지_않는다() throws IOException {
        // 부모 키 오타 아래 키 자리에 계좌번호를 적은 경우(PR #100 QA R4). 오타 키 이름은 보여 고칠 곳을 알게 한다
        secrets("""
                { "real-trading": { "acount-numbers": { "99990000-01": "%s", "99990000-02": "%s" } },
                  "lab": { "max-parallel-runs": 2 } }
                """.formatted(FAKE_SECRET, FAKE_SECRET));

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.warning()).contains("secrets.json")
                .contains("real-trading/acount-numbers/* 2개").contains("lab/max-parallel-runs")
                .doesNotContain("99990000").doesNotContain(FAKE_SECRET);
    }

    @Test
    void secrets의_숫자가_든_키_이름은_구조에_없으면_가린다() throws IOException {
        secrets("""
                { "real-trading": { "99990000-01": "x" }, "99990000-02": { "a": "y" },
                  "brokers": { "kis": { "app-kye": "z" } } }
                """);

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.warning()).contains("real-trading/* 1개").contains("*/* 1개")
                .contains("brokers/kis/app-kye").doesNotContain("99990000");
    }

    @Test
    void runtime_config의_모르는_키_이름도_숫자가_들면_가린다() throws IOException {
        // PR #101 architect 발견 1: Jackson 경로를 그대로 쓰면 모르는 키 자리의 계좌번호가 경고에 나간다
        runtime("""
                { "real-trading": { "99990000-01": "x" } }
                """);

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("UnrecognizedPropertyException at real-trading/*")
                .doesNotContain("99990000").doesNotContain("com.autotrade");
    }

    @Test
    void secrets의_값_바인딩이_실패해도_키_자리의_계좌번호는_경고에_남기지_않는다() throws IOException {
        // PR #101 risk-ops F1: 비밀값 아닌 키 검사를 지나 바인딩에서 실패하는 갈래(값이 문자열이 아님, 빈 객체)
        List<String> inputs = List.of(
                "{\"real-trading\":{\"account-numbers\":{\"99990000-01\":[\"x\"]}}}", // S1
                "{\"real-trading\":{\"account-numbers\":{\"99990000-01\":{}}}}", // S2
                "{\"real-trading\":{\"account-numbers\":{\"99990000-01\":12345}}}", // S11
                "{\"real-trading\":{\"99990000-01\":{}}}", // S4
                "{\"99990000-01\":{}}", // S5
                "{\"brokers\":{\"kis\":{\"99990000-01\":{}}}}"); // S15
        for (String json : inputs) {
            secrets(json);
            LoadedConfig loaded = loader.load(paths);
            assertThat(loaded.warning()).as(json).contains("secrets.json")
                    .doesNotContain("99990000").doesNotContain("com.autotrade").doesNotContain("java.util");
        }
    }

    @Test
    void runtime_config의_모르는_키가_키_이름_모양이면_고칠_곳을_보여_준다() throws IOException {
        runtime("""
                { "real-trading": { "accounts": [ { "id": "KIS-1", "broker": "KIS", "market": "KRX", "nmae": "x" } ] } }
                """);

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.warning()).contains("at real-trading/accounts/0/nmae");
    }

    @Test
    void secrets의_계좌번호_맵_키_자리는_아래에_키가_있어도_가린다() throws IOException {
        // PR #101 QA Q1: 맵 키 자리(account-numbers/*) 갈래를 지키는 시험
        secrets("""
                { "real-trading": { "account-numbers": { "99990000-01": { "x": "y" } } } }
                """);

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.warning()).contains("real-trading/account-numbers/*/* 1개").doesNotContain("99990000");
    }

    @Test
    void secrets의_값_자리_아래_키는_모양과_상관없이_가린다() throws IOException {
        // PR #101 QA R1: 구조의 잎 아래 키는 오타일 수 없어 이름을 보일 이유가 없다
        secrets("""
                { "real-trading": { "operator-password-hash": { "abc-def": "x" } },
                  "brokers": { "kis": { "app-key": { "ghi": "y" } } },
                  "lab": { "max-parallel-runs": { "jkl": "z" } } }
                """);

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.warning()).contains("real-trading/operator-password-hash/* 1개")
                .contains("brokers/kis/app-key/* 1개").contains("lab/max-parallel-runs/* 1개")
                .doesNotContain("abc-def").doesNotContain("ghi").doesNotContain("jkl");
    }

    @Test
    void 겹친_계좌_id는_값_없이_위치만_알린다() throws IOException {
        // PR #101 QA R2: id 자리에 계좌번호를 적었을 수 있다
        runtime("""
                { "real-trading": { "accounts": [
                    { "id": "99990000-01", "broker": "KIS", "market": "KRX" },
                    { "id": "99990000-01", "broker": "KIS", "market": "US" } ] } }
                """);

        LoadedConfig loaded = loader.load(paths);

        assertThat(loaded.warning()).contains("id가 겹칩니다(0번과 1번, 0부터)").doesNotContain("99990000");
    }

    @Test
    void 키_이름에_슬래시가_있어도_예외_없이_그_파일만_거부한다() throws IOException {
        secrets("""
                { "brokers/kis": { "app-key": "%s" } }
                """.formatted(FAKE_SECRET));
        LoadedConfig loaded = loader.load(paths);
        assertThat(loaded.warning()).contains("secrets.json").doesNotContain("NullPointer").doesNotContain(FAKE_SECRET);
    }

    @Test
    void 실전_계좌_별칭이_겹치면_거부한다() throws IOException {
        runtime("""
                { "real-trading": { "accounts": [
                    { "id": "KIS-1", "broker": "KIS", "market": "KRX" },
                    { "id": "KIS-1", "broker": "KIS", "market": "US" } ] } }
                """);
        LoadedConfig loaded = loader.load(paths);
        assertThat(loaded.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);
        assertThat(loaded.warning()).contains("id가 겹칩니다");
    }

    @Test
    void 비밀값_경로는_Secret_타입_필드에서_만들어진다() {
        assertThat(SecretPaths.all().stream().collect(Collectors.toList()))
                .containsExactlyInAnyOrder(
                        "brokers/kis/app-key", "brokers/kis/app-secret",
                        "brokers/kiwoom/app-key", "brokers/kiwoom/app-secret",
                        "brokers/toss/app-key", "brokers/toss/app-secret",
                        "notification/slack/webhook-url", "notification/kakao/access-token",
                        "real-trading/account-numbers/*", "real-trading/operator-password-hash");
    }

    private void runtime(String json) throws IOException {
        Files.writeString(paths.runtimeFile(), json);
    }

    private void secrets(String json) throws IOException {
        Files.writeString(paths.secretsFile(), json);
    }
}
