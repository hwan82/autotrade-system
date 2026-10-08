package com.autotrade.app.engine.order;

import com.autotrade.broker.BrokerPlugin;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.risk.guard.GuardedOrderGateway;
import com.autotrade.risk.guard.OrderSessionPermit;
import com.autotrade.risk.guard.RealTradingGuard;
import java.util.Objects;

/**
 * 실전 주문 출구를 얻는 유일한 곳(ADR-002). 증권사 플러그인의 {@link BrokerPlugin#orderGateway}는 이 클래스만 부른다
 * (ArchitectureTest {@code ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY}).
 *
 * <p>순서: 계좌의 증권사 확인(D-010) → Guard 허가(OFF면 여기서 멈춤, 주문 세션을 만들지 않음, RSK-01)
 * → 플러그인 출구 생성 → {@link GuardedOrderGateway}로 감싸 돌려준다.
 */
public final class RealOrderGateways {

    private RealOrderGateways() {}

    /**
     * @throws com.autotrade.risk.guard.GuardClosedException 계좌 Guard가 주문 세션을 허가하지 않는 상태(OFF 등)
     * @throws IllegalArgumentException 플러그인이 계좌의 증권사가 아니거나 Guard에 등록되지 않은 계좌
     * @throws IllegalStateException 플러그인이 이 계좌의 주문 출구를 주지 않을 때(미설정, 능력 없음)
     */
    public static OrderGateway open(RealTradingGuard guard, BrokerPlugin plugin, AccountRef account) {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(account, "account");
        if (plugin.brokerId() != account.broker()) {
            throw new IllegalArgumentException("주문은 계좌의 증권사로만 보낸다(D-010): "
                    + account.id() + "는 " + account.broker() + " 계좌, 플러그인은 " + plugin.brokerId());
        }
        if (!guard.accounts().contains(account)) {
            throw new IllegalArgumentException("Guard에 등록된 실전 계좌가 아닙니다: " + account);
        }
        // 허가를 먼저 받는다. OFF 계좌는 플러그인 출구(주문 세션·토큰)를 만들기 전에 막힌다
        OrderSessionPermit permit = guard.issueSessionPermit(account.id());
        OrderGateway raw = plugin.orderGateway(account)
                .orElseThrow(() -> new IllegalStateException(account.id() + " 주문 출구를 만들 수 없습니다(증권사 미설정 또는 능력 없음)"));
        return new GuardedOrderGateway(guard, permit, raw);
    }
}
