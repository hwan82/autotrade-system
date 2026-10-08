package com.autotrade.app.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
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
import java.time.Clock;
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
}
