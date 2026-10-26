package com.autotrade.app.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlSocket;
import com.autotrade.domain.account.AccountId;
import com.autotrade.realstore.EngineStore;
import com.autotrade.realstore.StoreAlerts;
import com.autotrade.realstore.StoreRefusal;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.RaiseNotAppliedException;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실전 엔진의 저장소 배선(ADR-015, D-146). */
class EngineStoreWiringTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"), ZoneOffset.UTC);
    private static final AccountId KIS = new AccountId("KIS-US-1");

    @TempDir
    Path dir;

    private ConfigPaths paths() throws Exception {
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
                """);
        Files.writeString(paths.secretsFile(), """
                { "real-trading": { "account-numbers": { "KIS-US-1": "<ACCOUNT_NUMBER>" } } }
                """);
        return paths;
    }

    @Test
    void 기동하면_저장소가_붙어_명령_감사가_원장에_남고_닫으면_정상_종료가_기록된다() throws Exception {
        ConfigPaths paths = paths();
        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED)) {
            RealTradingGuard guard = engine.guard();
            assertThat(engine.store().boot()).isEqualTo(1);
            assertThat(guard.state(KIS)).isEqualTo(GuardState.OFF);
            try (ControlClient client = new ControlClient(ControlSocket.path(paths), Duration.ofSeconds(5))) {
                client.send(ControlRequest.deactivate("KIS-US-1", "배선 확인"));
            }
            assertThat(guard.state(KIS)).isEqualTo(GuardState.OFF);
        }
        Path store = dir.resolve("engine-store");
        try (Stream<Path> files = Files.walk(store.resolve("audit/commands"))) {
            String commands = String.join("\n", files.filter(p -> p.toString().endsWith(".jsonl")).map(EngineStoreWiringTest::read).toList());
            assertThat(commands).contains("\"command\":\"DEACTIVATE\"").contains("\"target\":\"KIS-US-1\"");
        }
        // 계좌 감사 원장은 계좌마다 따로 있다(체크포인트 줄로 시작)
        assertThat(Files.isDirectory(store.resolve("accounts/KIS-US-1/audit"))).isTrue();
        List<String> boot = Files.readAllLines(store.resolve("boot/boot.jsonl"), StandardCharsets.UTF_8);
        assertThat(boot.get(boot.size() - 1)).contains("\"type\":\"shutdown\"");
        // 다시 띄우면 모든 계좌 OFF(ADR-015 시험 1), 이전 종료는 정상
        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED)) {
            assertThat(engine.guard().state(KIS)).isEqualTo(GuardState.OFF);
            assertThat(engine.store().report().previousShutdown()).isEqualTo("clean");
        }
    }

    @Test
    void 쓰기_잠금을_다른_프로세스가_잡고_있으면_저장소_문구로_기동을_거부한다() throws Exception {
        ConfigPaths paths = paths();
        Path configDir = paths.runtimeFile().getParent();
        try (EngineStore held = EngineStore.open(new EngineStore.Options(configDir, List.of(), ref -> null, FIXED, 1,
                List::of, StoreAlerts.NONE, EngineStore.Limits.DEFAULT))) {
            List<Integer> exits = new ArrayList<>();
            assertThat(EngineMain.launch(() -> paths, FIXED, EngineMain::setup, exits::add)).isNull();
            assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED);
            assertThat(held.boot()).isEqualTo(1);
        }
        // 실패한 기동은 명령 채널을 닫고 잠금을 남기지 않는다: 이제 뜬다
        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED)) {
            assertThat(engine.store().boot()).isEqualTo(2);
        }
    }

    @Test
    void 저장소_기동_거부는_명령_채널_문구가_아니라_저장소_문구다() {
        EngineMain.StartFailure f = EngineMain.StartFailure.of(new StoreRefusal("writer.lock을 다른 프로세스가 잡고 있습니다"));
        assertThat(f.headline()).isEqualTo(EngineMain.StartFailure.STORE_HEADLINE);
        assertThat(f.exitCode()).isEqualTo(EngineMain.EXIT_START_FAILED);
        assertThat(f.programError()).isFalse();
    }

    @Test
    void 저장소를_붙이지_않은_Guard는_올리는_전이를_거절한다() throws Exception {
        RealTradingGuard guard = EngineMain.startGuard(new ConfigLoader().load(paths()).config(), FIXED);
        SecondConfirmation c = guard.confirmationIssuer().issue(KIS, "BAE", FIXED.instant());
        assertThatThrownBy(() -> guard.enterProtective(c)).isInstanceOf(RaiseNotAppliedException.class);
        assertThat(guard.state(KIS)).isEqualTo(GuardState.OFF);
    }

    @Test
    void 가릴_비밀값은_설정의_계좌번호를_담고_빈_값은_뺀다() throws Exception {
        List<String> secrets = EngineMain.secretValues(new ConfigLoader().load(paths()).config());
        assertThat(secrets).containsExactly("<ACCOUNT_NUMBER>");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
