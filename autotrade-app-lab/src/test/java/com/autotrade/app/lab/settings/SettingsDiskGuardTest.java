package com.autotrade.app.lab.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.RuntimeConfigHolder;
import com.autotrade.config.RuntimeConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/**
 * risk-ops 검토 B1: 설정 화면 저장이 실험실 메모리의 옛 값으로 실전 계좌·증권사 키 파일을 덮어쓰지 않는다.
 * 디스크가 메모리와 다르면 409로 거절하고 두 파일은 바이트 단위로 그대로다. 비밀값은 자리표시자다.
 */
class SettingsDiskGuardTest {

    @TempDir
    Path dir;

    private static final String ACCOUNTS =
            "{\"real-trading\": {\"accounts\": [{\"id\": \"KIS-KR-1\", \"broker\": \"KIS\", \"market\": \"KRX\"}]}}";
    private static final String SECRETS = "{\"brokers\": {\"kis\": {\"app-key\": \"PLACEHOLDER-K\", \"app-secret\": \"PLACEHOLDER-S\"}},"
            + " \"real-trading\": {\"account-numbers\": {\"KIS-KR-1\": \"PLACEHOLDER-A\"}}}";

    private SettingsController start(String runtime, String secrets) throws Exception {
        Files.writeString(dir.resolve("runtime-config.json"), runtime);
        Files.writeString(dir.resolve("secrets.json"), secrets);
        ConfigPaths paths = ConfigPaths.in(dir);
        RuntimeConfigStore store = new RuntimeConfigStore(paths);
        LoadedConfig loaded = new ConfigLoader().load(paths);
        return new SettingsController(new RuntimeConfigHolder(loaded.config(), store), store, loaded);
    }

    private static SettingsController.UpdateRequest lab(String parallel) {
        return new SettingsController.UpdateRequest(Map.of("lab.max-parallel-runs", parallel), List.of(), null);
    }

    private void assertConflict(SettingsController controller, String code, byte[] runtime, byte[] secrets) throws Exception {
        assertThatThrownBy(() -> controller.update(lab("2")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(code);
                });
        assertThat(Files.readAllBytes(dir.resolve("runtime-config.json"))).isEqualTo(runtime);
        assertThat(Files.readAllBytes(dir.resolve("secrets.json"))).isEqualTo(secrets);
    }

    @Test
    void 정상이면_저장되고_실전_계좌와_증권사_키는_그대로_남는다() throws Exception {
        SettingsController controller = start(ACCOUNTS, SECRETS);
        controller.update(lab("2"));
        LoadedConfig reread = new ConfigLoader().load(ConfigPaths.in(dir));
        assertThat(reread.warning()).isEmpty();
        assertThat(reread.config().lab().maxParallelRuns()).isEqualTo(2);
        assertThat(reread.config().realTrading().accounts()).hasSize(1);
        assertThat(reread.config().realTrading().accountNumbers().get("KIS-KR-1").reveal()).isEqualTo("PLACEHOLDER-A");
        assertThat(reread.config().brokers().kis().appKey().reveal()).isEqualTo("PLACEHOLDER-K");
    }

    @Test
    void 기동할_때_파일을_버렸으면_저장하지_않는다() throws Exception {
        // 활성화 키를 넣으면 runtime-config.json 전체를 버리고(CFG-02) 실전 계좌가 빈 상태로 뜬다
        String broken = "{\"real-trading\": {\"accounts\": [{\"id\": \"KIS-KR-1\", \"broker\": \"KIS\", \"market\": \"KRX\","
                + " \"enabled\": true}]}}";
        SettingsController controller = start(broken, SECRETS);
        assertConflict(controller, "CONFIG_FILE_PROBLEM", broken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(dir.resolve("secrets.json")));
    }

    @Test
    void 고아_계좌번호를_버린_상태면_저장하지_않는다() throws Exception {
        String orphan = "{\"real-trading\": {\"account-numbers\": {\"KIS-KR-1\": \"PLACEHOLDER-A\", \"GHOST\": \"PLACEHOLDER-G\"}}}";
        SettingsController controller = start(ACCOUNTS, orphan);
        assertConflict(controller, "CONFIG_FILE_PROBLEM", Files.readAllBytes(dir.resolve("runtime-config.json")),
                Files.readAllBytes(dir.resolve("secrets.json")));
    }

    @Test
    void 실행_중에_파일을_직접_고쳤으면_덮어쓰지_않는다() throws Exception {
        SettingsController controller = start(ACCOUNTS, SECRETS);
        String added = "{\"real-trading\": {\"accounts\": [{\"id\": \"KIS-KR-1\", \"broker\": \"KIS\", \"market\": \"KRX\"},"
                + " {\"id\": \"KIS-US-1\", \"broker\": \"KIS\", \"market\": \"US\"}]}}";
        Files.writeString(dir.resolve("runtime-config.json"), added);
        assertConflict(controller, "CONFIG_CHANGED_ON_DISK", Files.readAllBytes(dir.resolve("runtime-config.json")),
                Files.readAllBytes(dir.resolve("secrets.json")));
    }

    @Test
    void BIZ_모드에서_알림을_켤_때도_비용_확인이_필요하다() throws Exception {
        SettingsController controller = start(
                "{\"notification\": {\"kakao\": {\"mode\": \"BIZ\"}}}",
                "{\"notification\": {\"kakao\": {\"access-token\": \"PLACEHOLDER-T\"}}}");
        assertThatThrownBy(() -> controller.update(new SettingsController.UpdateRequest(
                Map.of("notification.kakao.enabled", "true"), List.of(), null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("PAID_CONFIRMATION_REQUIRED"));
        controller.update(new SettingsController.UpdateRequest(Map.of("notification.kakao.enabled", "true"), List.of(), true));
        assertThat(new ConfigLoader().load(ConfigPaths.in(dir)).config().notification().kakao().enabled()).isTrue();
    }
}
