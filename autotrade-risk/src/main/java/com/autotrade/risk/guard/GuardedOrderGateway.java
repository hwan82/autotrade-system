package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderAck;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.domain.order.OrderQueryResult;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.risk.RiskDecision;
import java.util.Objects;

/**
 * Guard를 거친 실전 주문 출구. 실전 엔진은 증권사 플러그인의 {@link OrderGateway}를 직접 쓰지 않고 이 래퍼만 쓴다
 * (OrderGateway·BrokerPlugin 계약, ArchitectureTest가 강제).
 *
 * <ul>
 *   <li>만들 때 유효한 {@link OrderSessionPermit}이 필요하다. OFF 계좌는 허가를 받을 수 없으므로 이 출구도 만들 수 없다.</li>
 *   <li>호출마다 허가 유효성과 Guard 판정을 다시 본다. 계좌가 OFF로 내려가면 이 인스턴스는 영구히 막힌다.</li>
 *   <li>Guard가 막은 주문은 증권사로 나가지 않고 {@link OrderState#REJECTED}로 돌아온다(사유에 {@link RealTradingGuard#RULE_ID}).</li>
 *   <li>이미 증권사 호출을 시작한 주문은 그 사이 Guard가 내려가도 끝까지 진행된다. 계좌별 직렬 큐라 동시에 하나뿐이다.</li>
 * </ul>
 */
public final class GuardedOrderGateway implements OrderGateway {

    private final RealTradingGuard guard;
    private final OrderSessionPermit permit;
    private final OrderGateway delegate;

    /**
     * @throws GuardClosedException 허가가 이미 무효일 때
     * @throws IllegalArgumentException 허가와 출구의 계좌가 다르거나 다른 Guard의 허가일 때
     */
    public GuardedOrderGateway(RealTradingGuard guard, OrderSessionPermit permit, OrderGateway delegate) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.permit = Objects.requireNonNull(permit, "permit");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (permit.issuer() != guard) {
            throw new IllegalArgumentException("다른 Guard가 발급한 허가입니다");
        }
        if (!delegate.account().equals(permit.account())) {
            throw new IllegalArgumentException("허가 계좌와 주문 출구 계좌가 다릅니다: "
                    + permit.account() + " / " + delegate.account());
        }
        if (!permit.isValid()) {
            throw new GuardClosedException(permit.account() + " 주문 세션 허가가 무효입니다");
        }
    }

    @Override
    public AccountId account() {
        return permit.account();
    }

    @Override
    public OrderAck submit(OrderRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.account().equals(account())) {
            throw new IllegalArgumentException("이 출구의 계좌가 아닙니다: " + request.account());
        }
        if (!permit.isValid()) {
            return blocked(request.key(), OrderState.REJECTED, "주문 세션 허가 무효(Guard OFF)");
        }
        RiskDecision decision = guard.check(request);
        if (decision instanceof RiskDecision.Rejected rejected) {
            return blocked(request.key(), OrderState.REJECTED, rejected.reason());
        }
        return delegate.submit(request);
    }

    @Override
    public OrderQueryResult query(IdempotencyKey key) {
        Objects.requireNonNull(key, "key");
        if (!permit.isValid()) {
            return new OrderQueryResult.Indeterminate(RealTradingGuard.RULE_ID + ": 주문 세션 허가 무효(Guard OFF)");
        }
        return delegate.query(key);
    }

    @Override
    public OrderAck cancel(IdempotencyKey key) {
        Objects.requireNonNull(key, "key");
        if (!permit.isValid() || !guard.mayCancel(account())) {
            // 원주문 상태를 모르므로 UNKNOWN: 호출자는 조회부터 한다
            return blocked(key, OrderState.UNKNOWN, "주문 세션 허가 무효(Guard OFF), 취소 미전송");
        }
        return delegate.cancel(key);
    }

    private static OrderAck blocked(IdempotencyKey key, OrderState state, String reason) {
        return new OrderAck(key, state, false, null, RealTradingGuard.RULE_ID + ": " + reason);
    }
}
