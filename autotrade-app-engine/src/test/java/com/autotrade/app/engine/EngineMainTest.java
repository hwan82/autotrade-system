package com.autotrade.app.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.engine.command.OperatorPasswordHash;
import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.Secret;
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
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.NotificationQueue;
import com.autotrade.notification.NotificationSetup;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
            // 운영 배선: 시작 알림과 실전 제어 명령 알림이 엔진의 알림 큐에 들어간다(PR #128 QA Q1, 변이 M10)
            assertThat(engine.notifications().acceptedCount(EventRoutes.ENGINE_STARTED)).isEqualTo(1);
            assertThat(engine.notifications().acceptedCount(EventRoutes.REAL_TRADING_COMMAND)).isPositive();
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

    @Test
    void 알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다() {
        // PR #128 architect R1: 문자열 값이 여럿이라 자리가 바뀌어도 컴파일된다. 값마다 다르게 넣어 제자리에 갔는지 본다
        RuntimeConfig.Notification n = new RuntimeConfig.Notification(
                new RuntimeConfig.Slack(true, Secret.of("SLACK-URL-PH")),
                new RuntimeConfig.Kakao(true, KakaoMode.ME, Secret.of("ACCESS-PH"), Secret.of("REST-PH"),
                        Secret.of("REFRESH-PH"), Secret.of("CLIENT-PH"), "http://link.example"));
        NotificationSetup.Builder expected = NotificationSetup.builder()
                .slackEnabled(true)
                .slackWebhookUrl("SLACK-URL-PH")
                .kakaoEnabled(true)
                .kakaoPaidMode(false)
                .kakaoRestApiKey("REST-PH")
                .kakaoClientSecret("CLIENT-PH")
                .kakaoAccessToken("ACCESS-PH")
                .kakaoRefreshToken("REFRESH-PH")
                .kakaoLinkUrl("http://link.example")
                .displayZone(ZoneId.of("Asia/Seoul"))
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP);
        assertThat(EngineMain.setup(n)).isEqualTo(expected.build());
        // 같은 값이라도 자리가 바뀌면 다르다(비교가 값을 실제로 본다)
        assertThat(EngineMain.setup(n)).isNotEqualTo(expected.kakaoRestApiKey("CLIENT-PH").kakaoClientSecret("REST-PH").build());
        // 재동의 문구는 새 토큰을 넣을 설정 키를 알려 준다(라이브러리 기본 문구가 아님)
        assertThat(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP).contains("notification.kakao.refresh-token")
                .isNotEqualTo(NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP);

        RuntimeConfig.Notification biz = new RuntimeConfig.Notification(new RuntimeConfig.Slack(false, null),
                new RuntimeConfig.Kakao(true, KakaoMode.BIZ, null, null, null, null, null));
        assertThat(EngineMain.setup(biz)).isEqualTo(NotificationSetup.builder()
                .kakaoEnabled(true)
                .kakaoPaidMode(true)
                .displayZone(ZoneId.of("Asia/Seoul"))
                .kakaoReconsentHelp(RuntimeConfig.Notification.KAKAO_RECONSENT_HELP)
                .build());
    }

    // ---- 기동 실패 종류(D-107, PR #144 독립 QA R2) ----

    /** 알림 채널 이름이 어긋나는 코드 실수(PR #144)와 같은 예외를 알림 설정 단계에서 던진다. */
    private static final Function<RuntimeConfig.Notification, NotificationSetup> CHANNEL_NAME_BUG = n -> {
        throw new IllegalArgumentException("외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x");
    };

    private String captureErr(Runnable action) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void writeTwoAccounts(ConfigPaths paths) throws IOException {
        Files.writeString(paths.runtimeFile(), """
                { "real-trading": { "accounts": [
                    { "id": "KIS-US-1", "broker": "KIS", "market": "US" },
                    { "id": "KIWOOM-KR-1", "broker": "KIWOOM", "market": "KRX" } ] } }
                """);
    }

    @Test
    void 진입점은_정상이면_종료를_부르지_않고_모든_계좌_OFF인_엔진을_돌려준다() throws IOException {
        ConfigPaths paths = ConfigPaths.in(dir);
        writeTwoAccounts(paths);
        List<Integer> exits = new ArrayList<>();

        try (EngineMain.Engine engine = EngineMain.launch(() -> paths, FIXED, EngineMain::setup, exits::add)) {
            assertThat(engine).isNotNull();
            assertThat(exits).isEmpty();
            assertThat(engine.guard().accounts()).hasSize(2)
                    .allSatisfy(a -> assertThat(engine.guard().state(a.id())).isEqualTo(GuardState.OFF));
        }
    }

    @Test
    void 진입점은_알림_채널_코드_실수를_설정_오류로_표시하지_않고_종료_코드_1로_끝낸다() throws IOException {
        ConfigPaths paths = ConfigPaths.in(dir);
        writeTwoAccounts(paths);
        List<Integer> exits = new ArrayList<>();
        EngineMain.Engine[] result = new EngineMain.Engine[1];

        String err = captureErr(() -> result[0] = EngineMain.launch(() -> paths, FIXED, CHANNEL_NAME_BUG, exits::add));

        assertThat(result[0]).isNull();
        assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED);
        assertThat(err).contains(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE)
                .contains("IllegalArgumentException: 외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x")
                .doesNotContain(EngineMain.StartFailure.SETTINGS_HEADLINE);
        // 명령 채널을 열기 전에 멈췄다: 소켓이 없고, 잠금이 남지 않아 고친 뒤 바로 다시 뜬다
        assertThat(ControlSocket.path(paths)).doesNotExist();
        // 기동 실패 뒤에도 다음 기동은 모든 계좌 OFF로 시작한다(절대 규칙 1)
        try (EngineMain.Engine engine = EngineMain.launch(() -> paths, FIXED, EngineMain::setup, exits::add)) {
            assertThat(engine).isNotNull();
            assertThat(engine.guard().accounts()).hasSize(2)
                    .allSatisfy(a -> assertThat(engine.guard().state(a.id())).isEqualTo(GuardState.OFF));
        }
        assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED);
    }

    @Test
    void 진입점은_그_밖의_기동_예외도_프로그램_오류로_종료_코드_1이다() {
        List<Integer> exits = new ArrayList<>();
        String err = captureErr(() -> assertThat(EngineMain.launch(() -> ConfigPaths.in(dir), FIXED, n -> {
            throw new IllegalStateException("꾸리기 실패");
        }, exits::add)).isNull());

        assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED);
        assertThat(err).contains(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE).contains("IllegalStateException: 꾸리기 실패");
    }

    @Test
    void 진입점은_설정_폴더_경로가_틀리면_설정_오류로_종료_코드_2로_끝낸다() {
        List<Integer> exits = new ArrayList<>();
        String err = captureErr(() -> assertThat(EngineMain.launch(() -> {
            throw new java.nio.file.InvalidPathException("\"C:\\x\"", "Illegal char <\">");
        }, FIXED, EngineMain::setup, exits::add)).isNull());

        assertThat(exits).containsExactly(EngineMain.EXIT_BAD_SETTINGS);
        assertThat(err).contains(EngineMain.StartFailure.SETTINGS_HEADLINE)
                .contains("설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR)")
                .doesNotContain(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE);
    }

    @Test
    void 진입점은_명령_채널을_열지_못하면_예전_문구로_종료_코드_1로_끝낸다() throws Exception {
        ConfigPaths paths = ConfigPaths.in(dir);
        List<Integer> exits = new ArrayList<>();
        try (EngineMain.Engine first = EngineMain.start(paths, FIXED)) {
            String err = captureErr(() -> assertThat(EngineMain.launch(() -> paths, FIXED, EngineMain::setup, exits::add)).isNull());

            assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED);
            assertThat(err).contains(EngineMain.StartFailure.CHANNEL_HEADLINE).contains("다른 실전 엔진이 이미 실행 중입니다")
                    .doesNotContain(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE);
            assertThat(first.server().socket()).exists();
        }
    }

    @Test
    void 진입점은_실제_설정_폴더_값이_경로_형식이_아니면_설정_오류로_종료_코드_2로_끝낸다() {
        // PR #151 risk-ops 권고 2: 운영과 같은 ConfigPaths.defaultLocation으로 본다. NUL 문자는 어느 OS에서도 경로가 아니다
        String key = "autotrade.config-dir";
        String before = System.getProperty(key);
        List<Integer> exits = new ArrayList<>();
        try {
            System.setProperty(key, "bad\u0000dir");
            String err = captureErr(() -> assertThat(EngineMain.launch(ConfigPaths::defaultLocation, FIXED, EngineMain::setup,
                    exits::add)).isNull());

            assertThat(exits).containsExactly(EngineMain.EXIT_BAD_SETTINGS);
            assertThat(err).contains(EngineMain.StartFailure.SETTINGS_HEADLINE).contains("설정 폴더 경로가 틀렸습니다");
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }

    @Test
    void 진입점은_프로그램_오류만_로그에_예외_흐름을_남기고_설정_오류와_명령_채널_실패는_한_줄만_남긴다() throws Exception {
        // PR #151 독립 QA Q1(변이 M4 늘 스택, M5 스택 없음 생존): report가 programError 필드대로 로그에 넘기는지 본다.
        // 명령 채널 IOException이나 설정 거부 원인의 예외 흐름이 로그에 남지 않아야 한다(비밀값이 실릴 수 있는 원인 예외)
        ConfigPaths paths = ConfigPaths.in(dir);
        List<Integer> exits = new ArrayList<>();
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("com.autotrade." + EngineMain.PROCESS);
        List<java.util.logging.LogRecord> records = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        IllegalStateException codeBug = new IllegalStateException("꾸리기 실패");
        java.nio.file.InvalidPathException badPath = new java.nio.file.InvalidPathException("\"C:\\x\"", "Illegal char <\">");
        jul.addHandler(handler);
        try {
            captureErr(() -> EngineMain.launch(() -> paths, FIXED, n -> {
                throw codeBug;
            }, exits::add));
            captureErr(() -> EngineMain.launch(() -> {
                throw badPath;
            }, FIXED, EngineMain::setup, exits::add));
            try (EngineMain.Engine first = EngineMain.start(paths, FIXED)) {
                captureErr(() -> EngineMain.launch(() -> paths, FIXED, EngineMain::setup, exits::add));
                assertThat(first.server().socket()).exists();
            }
        } finally {
            jul.removeHandler(handler);
        }

        assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED, EngineMain.EXIT_BAD_SETTINGS,
                EngineMain.EXIT_START_FAILED);
        List<java.util.logging.LogRecord> errors = records.stream()
                .filter(r -> r.getLevel() == java.util.logging.Level.SEVERE).toList();
        assertThat(errors).hasSize(3);
        assertThat(errors.get(0).getMessage()).startsWith(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE);
        assertThat(errors.get(0).getThrown()).isSameAs(codeBug); // 프로그램 오류는 로그에 예외 흐름(스택)까지
        assertThat(errors.get(1).getMessage()).startsWith(EngineMain.StartFailure.SETTINGS_HEADLINE);
        assertThat(errors.get(1).getThrown()).isNull(); // 설정 오류는 한 줄만
        assertThat(errors.get(2).getMessage()).startsWith(EngineMain.StartFailure.CHANNEL_HEADLINE);
        assertThat(errors.get(2).getThrown()).isNull(); // 명령 채널 실패도 한 줄만
        // PR #165 독립 QA Q1(변이 N5·N6 생존): ERROR 줄에 실제 원인이 실린다. 콘솔을 못 본 운영자(예약 실행)는
        // runbook대로 로그 ERROR 줄만 보고 원인을 찾는다(D-107 4번). 첫머리만 보면 원인이 빠져도 통과하므로 원인 글자까지 본다
        assertThat(errors.get(0).getMessage())
                .isEqualTo(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE + ": IllegalStateException: 꾸리기 실패");
        assertThat(errors.get(1).getMessage()).isEqualTo(EngineMain.StartFailure.SETTINGS_HEADLINE
                + ": 설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR): " + badPath.getMessage());
        assertThat(errors.get(2).getMessage()).startsWith(EngineMain.StartFailure.CHANNEL_HEADLINE + ": ")
                .contains("다른 실전 엔진이 이미 실행 중입니다");
    }

    @Test
    void 기동_실패_표준_오류_첫머리는_runbook이_안내하는_낱말로_시작한다() throws Exception {
        // PR #151 독립 QA Q2(변이 M10·M11 생존): 상수끼리 비교하면 문구가 바뀌어도 통과하므로 runbook 낱말을 글자로 본다.
        // docs/runbooks/README.md 종료 코드 표: "명령 채널을 열지 못해"면 다른 엔진, "프로그램 오류"면 코드 문제(D-107).
        // 괄호가 두 번 이어지지 않는다(PR #151 QA R1)
        ConfigPaths paths = ConfigPaths.in(dir);
        List<Integer> exits = new ArrayList<>();
        String programError = captureErr(() -> EngineMain.launch(() -> paths, FIXED, CHANNEL_NAME_BUG, exits::add));
        String settings = captureErr(() -> EngineMain.launch(() -> {
            throw new java.nio.file.InvalidPathException("\"C:\\x\"", "Illegal char <\">");
        }, FIXED, EngineMain::setup, exits::add));
        String channel;
        try (EngineMain.Engine first = EngineMain.start(paths, FIXED)) {
            channel = captureErr(() -> EngineMain.launch(() -> paths, FIXED, EngineMain::setup, exits::add));
            assertThat(first.server().socket()).exists();
        }

        assertThat(programError).startsWith("실전 엔진이 기동 중 프로그램 오류로 멈췄습니다. 설정 파일 문제가 아니니 "
                + "로그의 예외를 개발 쪽에 알려 주세요(실전 엔진이 뜨지 않았습니다): IllegalArgumentException: ");
        assertThat(settings).startsWith("실전 엔진 설정이 틀려 기동하지 않습니다(실전 엔진이 뜨지 않았습니다): "
                + "설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR): ");
        assertThat(channel).startsWith("실전 제어 명령 채널을 열지 못해 기동을 중단합니다(실전 엔진이 뜨지 않았습니다): ");
        assertThat(List.of(programError, settings, channel)).allSatisfy(err -> assertThat(err).doesNotContain(")("));
        // PR #165 독립 QA Q2(변이 N9 생존): 표준 오류는 한 줄이다(D-107 4번). 예외 toString·스택이 콘솔로 더 나가지 않는다
        assertThat(List.of(programError, settings, channel)).allSatisfy(err -> assertThat(err.lines()).hasSize(1));
    }

    @Test
    void 기동_실패_표준_오류는_첫머리부터_줄_끝까지_정해진_한_줄이다() throws Exception {
        // PR #169 독립 QA Q1(변이 P3 줄 끝 없음, P8 원인 뒤 덧붙임 생존): 첫머리·줄 개수만 보면 줄 끝이 빠지거나 원인 뒤에
        // 다른 글자가 붙어도 통과하므로, 세 경우 모두 표준 오류 전체를 줄 끝까지 글자로 고정한다(D-107 4번)
        ConfigPaths paths = ConfigPaths.in(dir);
        List<Integer> exits = new ArrayList<>();
        java.nio.file.InvalidPathException badPath = new java.nio.file.InvalidPathException("\"C:\\x\"", "Illegal char <\">");
        String programError = captureErr(() -> EngineMain.launch(() -> paths, FIXED, CHANNEL_NAME_BUG, exits::add));
        String settings = captureErr(() -> EngineMain.launch(() -> {
            throw badPath;
        }, FIXED, EngineMain::setup, exits::add));
        String channel;
        try (EngineMain.Engine first = EngineMain.start(paths, FIXED)) {
            channel = captureErr(() -> EngineMain.launch(() -> paths, FIXED, EngineMain::setup, exits::add));
            assertThat(first.server().socket()).exists();
        }

        String nl = System.lineSeparator();
        assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED, EngineMain.EXIT_BAD_SETTINGS,
                EngineMain.EXIT_START_FAILED);
        assertThat(programError).isEqualTo("실전 엔진이 기동 중 프로그램 오류로 멈췄습니다. 설정 파일 문제가 아니니 "
                + "로그의 예외를 개발 쪽에 알려 주세요(실전 엔진이 뜨지 않았습니다): "
                + "IllegalArgumentException: 외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x" + nl);
        assertThat(settings).isEqualTo("실전 엔진 설정이 틀려 기동하지 않습니다(실전 엔진이 뜨지 않았습니다): "
                + "설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR): " + badPath.getMessage() + nl);
        assertThat(channel).isEqualTo("실전 제어 명령 채널을 열지 못해 기동을 중단합니다(실전 엔진이 뜨지 않았습니다): "
                + "다른 실전 엔진이 이미 실행 중입니다(" + com.autotrade.control.ControlServer.LOCK_FILE + " 잠금)" + nl);
    }

    @Test
    void 기동_실패_원인에_줄바꿈이_있어도_로그_ERROR와_표준_오류는_한_줄이고_예외_흐름은_원문을_남긴다() {
        // PR #169 독립 QA R2(D-107 남은 한계): 원인 메시지의 줄바꿈을 " / "로 접어, 로그를 줄 단위로 걸러 보는 운영자도
        // 원인 전체를 ERROR 한 줄에서 본다(D-112). 프로그램 오류의 예외 흐름(스택)에는 원래 메시지가 그대로 남는다
        List<Integer> exits = new ArrayList<>();
        IllegalStateException codeBug = new IllegalStateException("첫 줄\n둘째 줄\r\n  셋째 줄\r");
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("com.autotrade." + EngineMain.PROCESS);
        List<java.util.logging.LogRecord> records = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        jul.addHandler(handler);
        String err;
        try {
            err = captureErr(() -> assertThat(EngineMain.launch(() -> ConfigPaths.in(dir), FIXED, n -> {
                throw codeBug;
            }, exits::add)).isNull());
        } finally {
            jul.removeHandler(handler);
        }

        assertThat(exits).containsExactly(EngineMain.EXIT_START_FAILED);
        String detail = "IllegalStateException: 첫 줄 / 둘째 줄 / 셋째 줄";
        assertThat(err).isEqualTo(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE + "(실전 엔진이 뜨지 않았습니다): " + detail
                + System.lineSeparator());
        List<java.util.logging.LogRecord> errors = records.stream()
                .filter(r -> r.getLevel() == java.util.logging.Level.SEVERE).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getMessage()).isEqualTo(EngineMain.StartFailure.PROGRAM_ERROR_HEADLINE + ": " + detail);
        assertThat(errors.get(0).getThrown()).isSameAs(codeBug).hasMessage("첫 줄\n둘째 줄\r\n  셋째 줄\r");
    }

    @Test
    void 기동_실패_원인의_줄바꿈은_세_종류_모두_한_줄로_접는다() {
        assertThat(EngineMain.StartFailure.of(new EngineMain.EngineRefusal("a\nb")).detail()).isEqualTo("a / b");
        assertThat(EngineMain.StartFailure.of(new IOException("a\r\n\r\nb")).detail()).isEqualTo("a / b");
        assertThat(EngineMain.StartFailure.of(new IllegalStateException("a\u2028b")).detail())
                .isEqualTo("IllegalStateException: a / b");
        // \s에 들지 않는 유니코드 줄바꿈이 이어지거나 끝에 있어도 구분자는 하나, 끝에 남지 않는다(PR #172 risk-ops K1·architect N2)
        assertThat(EngineMain.StartFailure.of(new IOException("a\u2028\u2028b")).detail()).isEqualTo("a / b");
        assertThat(EngineMain.StartFailure.of(new IOException("a\u2029 \u0085\nb")).detail()).isEqualTo("a / b");
        assertThat(EngineMain.StartFailure.of(new IOException("\u2029a\nb\u0085")).detail()).isEqualTo("a / b");
        assertThat(EngineMain.StartFailure.of(new IOException("a\u000Bb\fc")).detail()).isEqualTo("a / b / c");
        // 줄바꿈이 없는 원인은 글자 그대로다(앞뒤 공백도 바꾸지 않는다)
        assertThat(EngineMain.StartFailure.of(new IOException(" a  b ")).detail()).isEqualTo(" a  b ");
        assertThat(EngineMain.StartFailure.of(new IOException((String) null)).detail()).isEqualTo("null");
    }

    @Test
    void 프로그램_오류만_예외_흐름을_남기는_종류로_판정한다() {
        assertThat(EngineMain.StartFailure.of(new IllegalStateException("x")).programError()).isTrue();
        assertThat(EngineMain.StartFailure.of(new IOException("x")).programError()).isFalse();
        assertThat(EngineMain.StartFailure.of(new EngineMain.EngineRefusal("x")).programError()).isFalse();
    }
}
