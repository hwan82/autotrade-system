package com.autotrade.app.lab.web;

import com.autotrade.config.RuntimeConfig;
import com.autotrade.config.RuntimeConfigHolder;
import com.autotrade.domain.account.BrokerId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면 상단 정보: 프로세스 모드, 증권사 설정 상태, 실전 계좌 설정 상태, CSRF 토큰.
 *
 * <p>이 프로세스는 실험실이라 실전 주문 경로가 없다(D-013). 증권사 연결 상태(ACTIVE·DEGRADED)와 실전 계좌별
 * Guard 상태는 실전 엔진·시세 게이트웨이 스냅샷에서 와야 하므로 운영 화면 작업(UI-05·06)에서 붙인다.
 * 여기서는 설정 여부만 보여 준다.
 */
@RestController
public class SessionController {

    private final LocalOnlyRequestFilter.CsrfToken token;
    private final RuntimeConfigHolder config;

    public SessionController(LocalOnlyRequestFilter.CsrfToken token, RuntimeConfigHolder config) {
        this.token = token;
        this.config = config;
    }

    @GetMapping("/api/session")
    public SessionView session() {
        RuntimeConfig c = config.current();
        List<BrokerView> brokers = new ArrayList<>();
        for (BrokerId id : BrokerId.values()) {
            brokers.add(new BrokerView(id.name(), c.brokers().of(id).isConfigured() ? "CONFIGURED" : "NOT_CONFIGURED"));
        }
        List<RealAccountView> accounts = c.realTrading().accounts().stream()
                .map(a -> new RealAccountView(a.id(), a.broker().name(), a.market().name(),
                        c.realTrading().isConfigured(a.id()) ? "CONFIGURED" : "NOT_CONFIGURED"))
                .toList();
        return new SessionView(token.value(), "LAB", false, brokers, accounts);
    }

    /**
     * @param mode 이 프로세스의 모드. 항상 LAB(가상 탐색)
     * @param realOrderPath 이 프로세스에 실전 주문 경로가 있는지. 실험실은 항상 false
     * @param brokers 증권사별 키 설정 여부(연결 상태 아님)
     * @param realAccounts 실전 계좌 목록과 계좌번호 설정 여부. 활성화 상태는 실전 엔진만 안다
     */
    public record SessionView(
            String csrfToken, String mode, boolean realOrderPath, List<BrokerView> brokers,
            List<RealAccountView> realAccounts) {
    }

    public record BrokerView(String id, String keyStatus) {
    }

    public record RealAccountView(String id, String broker, String market, String accountNumberStatus) {
    }
}
