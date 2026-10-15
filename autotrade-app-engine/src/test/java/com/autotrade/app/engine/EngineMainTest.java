package com.autotrade.app.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.app.engine.command.OperatorPasswordHash;
import com.autotrade.config.LoadedConfig;
import com.autotrade.control.ControlClient;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.ControlSocket;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.RealTradingGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineMainTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    @Test
    void 설정_파일이_없어도_독립적으로_기동한다() {
        assertThat(EngineMain.run(ConfigPaths.in(dir), FIXED)).isZero();
    }

    @Test
    void 실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다() throws IOException {
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "accounts": [
                    { "id": "KIS-US-1", "broker": "KIS", "market": "US" },
                    { "id": "KIWOOM-KR-1", "broker": "KIWOOM", "market": "KRX" } ] } }
                """);
        Files.writeString(paths.secretsFile(), """
                { "real-trading": { "account-numbers": { "KIS-US-1": "<ACCOUNT_NUMBER>" } } }
                """);
        LoadedConfig loaded = new ConfigLoader().load(paths);
        assertThat(loaded.warning()).isEmpty();

        RealTradingGuard guard = EngineMain.startGuard(loaded.config(), FIXED);

        assertThat(guard.accounts()).hasSize(2);
        assertThat(guard.accounts()).allSatisfy(a -> assertThat(guard.state(a.id())).isEqualTo(GuardState.OFF));
        assertThat(EngineMain.run(paths, FIXED)).isZero();
    }

    @Test
    void 설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다() throws IOException {
        // CFG-02 실패 테스트: enabled가 든 파일은 통째로 버려지고, 계좌가 없으니 열 계좌도 없다
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "accounts": [
                    { "id": "KIS-US-1", "broker": "KIS", "market": "US", "enabled": true } ] } }
                """);
        LoadedConfig loaded = new ConfigLoader().load(paths);
        assertThat(loaded.warning()).contains("runtime-config.json");

        RealTradingGuard guard = EngineMain.startGuard(loaded.config(), FIXED);

        assertThat(guard.accounts()).isEmpty();
        Symbol aapl = Symbol.us("AAPL");
        OrderRequest buy = new OrderRequest(IdempotencyKey.of(LocalDate.of(2026, 10, 8), aapl, Side.BUY, 1),
                new AccountId("KIS-US-1"), aapl, Side.BUY, OrderIntent.ENTRY, OrderType.MARKET, null, 1);
        assertThat(guard.check(buy).isApproved()).isFalse();
    }

    @Test
    void 기동하면_명령_채널로_상태를_읽을_수_있고_닫으면_소켓이_사라진다() throws Exception {
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
                """);
        Path socket = ControlSocket.path(paths);

        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED);
                ControlClient client = new ControlClient(socket, Duration.ofSeconds(5))) {
            ControlResponse status = client.send(ControlRequest.status());

            assertThat(status.status().accounts()).singleElement()
                    .satisfies(a -> assertThat(a.state()).isEqualTo("OFF"));
            assertThat(status.status().passwordConfigured()).isFalse();
            assertThat(status.status().configWarning()).isEmpty();
            // 같은 설정 폴더로 두 번째 엔진은 뜨지 않는다
            assertThat(EngineMain.run(paths, FIXED)).isEqualTo(1);
            assertThat(engine.guard().state(new AccountId("KIS-US-1"))).isEqualTo(GuardState.OFF);
        }
        assertThat(socket).doesNotExist();
    }

    @Test
    void 기동하면_하트비트를_쓰고_닫으면_지운다() throws Exception {
        ConfigPaths paths = ConfigPaths.in(dir);
        Path heartbeat = dir.resolve("heartbeat").resolve("engine.hb");

        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED)) {
            assertThat(engine.heartbeat().file()).isEqualTo(heartbeat);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(heartbeat) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(Files.readString(heartbeat)).contains("process=engine", "written-at=" + FIXED.instant());
        }
        assertThat(heartbeat).doesNotExist(); // 정상 종료 표시(감시가 꺼짐으로 본다, D-092)
    }

    @Test
    void 하트비트를_쓰지_못해도_엔진은_뜨고_Guard는_OFF이며_상태에_응답한다() throws Exception {
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
                """);
        Files.writeString(dir.resolve("heartbeat"), "폴더 자리에 파일"); // 하트비트 폴더를 만들 수 없게 막는다

        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED);
                ControlClient client = new ControlClient(ControlSocket.path(paths), Duration.ofSeconds(5))) {
            assertThat(engine.heartbeat().beat()).isFalse();
            ControlResponse status = client.send(ControlRequest.status());
            assertThat(status.status().accounts()).singleElement()
                    .satisfies(a -> assertThat(a.state()).isEqualTo("OFF"));
            assertThat(engine.guard().state(new AccountId("KIS-US-1"))).isEqualTo(GuardState.OFF);
        }
    }

    @Test
    void 설정_파일을_버리고_기동하면_상태_조회에_그_경고가_실린다() throws Exception {
        // 1단계 QA 권고 R3: 오타 키 하나로 runtime-config.json을 통째로 버리면 실전 계좌도 사라진다. 운영 화면이 알 수 있어야 한다
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "acounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
                """);
        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED);
                ControlClient client = new ControlClient(ControlSocket.path(paths), Duration.ofSeconds(5))) {
            ControlResponse status = client.send(ControlRequest.status());

            assertThat(status.status().accounts()).isEmpty();
            assertThat(status.status().configWarning()).contains("runtime-config.json을(를) 쓰지 않고 default로 기동")
                    .doesNotContain(dir.toString());
            assertThat(engine.guard().accounts()).isEmpty();
        }
    }

    @Test
    void 경고가_아주_길어도_상태_조회와_내리기는_응답한다() throws Exception {
        // PR #100 QA Q1: secrets.json에 비밀값 아닌 키가 많으면 경고가 수만 자가 된다. 잘라서 명령 채널 응답 한도 안에 둔다
        ConfigPaths paths = ConfigPaths.in(dir);
        StringBuilder keys = new StringBuilder("{ \"lab\": {");
        for (int i = 0; i < 6000; i++) {
            // 숫자가 든 키 이름은 경고에서 "*"로 묶이므로(QA R4) 글자로만 된 서로 다른 이름을 쓴다
            String suffix = "" + (char) ('a' + i / 676) + (char) ('a' + i / 26 % 26) + (char) ('a' + i % 26);
            keys.append(i == 0 ? "" : ",").append("\"not-a-secret-key-").append(suffix).append("\": \"x\"");
        }
        Files.writeString(paths.secretsFile(), keys.append("} }").toString());
        assertThat(new ConfigLoader().load(paths).warning().length()).isGreaterThan(64 * 1024);

        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED);
                ControlClient client = new ControlClient(ControlSocket.path(paths), Duration.ofSeconds(5))) {
            ControlResponse status = client.send(ControlRequest.status());
            assertThat(status.status().configWarning())
                    .hasSize(com.autotrade.control.EngineStatus.MAX_CONFIG_WARNING
                            + com.autotrade.control.EngineStatus.TRUNCATED_MARK.length())
                    .startsWith("secrets.json을(를) 쓰지 않고").endsWith(com.autotrade.control.EngineStatus.TRUNCATED_MARK);
            ControlResponse deactivate = client.send(ControlRequest.deactivate("KIS-US-1", null));
            assertThat(deactivate.status().configWarning()).endsWith(com.autotrade.control.EngineStatus.TRUNCATED_MARK);
            assertThat(engine.guard().accounts()).isEmpty();
        }
    }

    @Test
    void 소켓_파일을_지워도_두_번째_엔진은_잠금_때문에_뜨지_않는다() throws Exception {
        ConfigPaths paths = ConfigPaths.in(dir);
        Path socket = ControlSocket.path(paths);

        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED)) {
            Files.delete(socket);

            assertThat(EngineMain.run(paths, FIXED)).isEqualTo(1);
            assertThat(socket).doesNotExist();
            assertThat(engine.server().socket()).isEqualTo(socket);
        }
        // 첫 엔진이 끝나면 잠금이 풀려 다시 뜬다
        assertThat(EngineMain.run(paths, FIXED)).isZero();
    }

    @Test
    void 실제_배선으로_활성화하면_증권사_연동_전이라_첫_단계에서_OFF로_돌아간다() throws Exception {
        String password = "PLACEHOLDER-OPERATOR-PW";
        String hash = OperatorPasswordHash.create(password.toCharArray(), new SecureRandom());
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "accounts": [ { "id": "KIS-US-1", "broker": "KIS", "market": "US" } ] } }
                """);
        Files.writeString(paths.secretsFile(), """
                { "real-trading": { "account-numbers": { "KIS-US-1": "<ACCOUNT_NUMBER>" },
                                    "operator-password-hash": "%s" } }
                """.formatted(hash));

        try (EngineMain.Engine engine = EngineMain.start(paths, FIXED);
                ControlClient client = new ControlClient(ControlSocket.path(paths), Duration.ofSeconds(10))) {
            ControlResponse r = client.send(ControlRequest.activate("KIS-US-1", "KIS-US-1", password));

            assertThat(r.ok()).isTrue();
            assertThat(r.message()).contains("AUTHENTICATED").contains("OFF");
            assertThat(r.status().passwordConfigured()).isTrue();
            assertThat(r.status().accounts()).singleElement().satisfies(a -> {
                assertThat(a.state()).isEqualTo("OFF");
                assertThat(a.accountNumberConfigured()).isTrue();
            });
            assertThat(engine.guard().state(new AccountId("KIS-US-1"))).isEqualTo(GuardState.OFF);
        }
    }

    @Test
    void 비밀번호_해시_형식이_틀리면_2차_확인_명령을_받지_않는다() throws Exception {
        ConfigPaths paths = ConfigPaths.in(dir);
        Files.writeString(paths.secretsFile(), """
                { "real-trading": { "operator-password-hash": "not-a-hash" } }
                """);
        LoadedConfig loaded = new ConfigLoader().load(paths);
        RealTradingGuard guard = EngineMain.startGuard(loaded.config(), FIXED);

        assertThat(EngineMain.commandHandler(loaded.config(), guard, FIXED, loaded.warning()).status().passwordConfigured()).isFalse();
    }

    @Test
    void 해시_도구는_터미널이_없으면_실행하지_않는다() {
        assertThat(EngineMain.hashPassword(null)).isEqualTo(2);
    }
}
