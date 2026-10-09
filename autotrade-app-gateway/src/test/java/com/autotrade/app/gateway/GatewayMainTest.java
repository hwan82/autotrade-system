package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.Secret;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayMainTest {

    @TempDir
    Path dir;

    @Test
    void 설정_파일이_없어도_독립적으로_기동한다() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
        assertThat(GatewayMain.run(ConfigPaths.in(dir), fixed)).isZero();
    }

    @Test
    void 계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다() {
        RuntimeConfig.RealTrading rt = new RuntimeConfig.RealTrading(
                List.of(new RuntimeConfig.RealAccount("KIWOOM-KRX-1", BrokerId.KIWOOM, Market.KRX),
                        new RuntimeConfig.RealAccount("KIS-KRX-1", BrokerId.KIS, Market.KRX)),
                Map.of("KIWOOM-KRX-1", Secret.of("<ACCOUNT_NUMBER>")));

        // 한투 계좌는 계좌번호가 없어 활성화할 수 없으므로 게이트웨이가 써도 된다
        assertThat(GatewayMain.reservedForRealTrading(rt)).containsExactly(BrokerId.KIWOOM);
        assertThat(GatewayMain.reservedForRealTrading(new RuntimeConfig.RealTrading(List.of(), Map.of()))).isEmpty();
    }
}
