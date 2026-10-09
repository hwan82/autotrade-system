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
                Map.of("KIWOOM-KRX-1", Secret.of("<ACCOUNT_NUMBER>")), Secret.empty());

        // 한투 계좌는 계좌번호가 없어 활성화할 수 없으므로 게이트웨이가 써도 된다
        assertThat(GatewayMain.reservedForRealTrading(rt)).containsExactly(BrokerId.KIWOOM);
        assertThat(GatewayMain.reservedForRealTrading(new RuntimeConfig.RealTrading(List.of(), Map.of(), Secret.empty()))).isEmpty();
    }

    @Test
    void 기본_목록의_토스는_키가_있어도_실시간을_구현하기_전까지_건너뛴다() {
        // D-039로 TOSS가 기본 우선순위에 들어갔지만 토스 실시간 프로토콜은 아직 없다(ADR-003 2.1). 키가 설정돼도 연결하지 않는다
        RuntimeConfig.BrokerKeys keys = new RuntimeConfig.BrokerKeys(Secret.of("<APP_KEY>"), Secret.of("<APP_SECRET>"));
        assertThat(keys.isConfigured()).isTrue();
        assertThat(GatewayMain.plugin(BrokerId.TOSS, keys, null)).isEmpty();
    }
}
