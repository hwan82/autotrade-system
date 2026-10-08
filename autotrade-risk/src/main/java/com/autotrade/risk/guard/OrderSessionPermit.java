package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import java.util.Objects;

/**
 * 실전 주문 세션을 만들어도 된다는 Guard의 허가(RSK-01: OFF 계좌는 주문용 세션·토큰을 만들지 않는다).
 * {@link RealTradingGuard#issueSessionPermit}만 만든다.
 *
 * <p>허가는 발급 당시의 세대(epoch)에 묶인다. 계좌가 OFF로 내려가면 세대가 바뀌어 그전에 받은 허가는 모두 무효가 되고,
 * 그 허가로 만든 {@link GuardedOrderGateway}도 다시 쓸 수 없다. 다시 열려면 새 허가를 받아야 한다.
 */
public final class OrderSessionPermit {

    private final AccountId account;
    private final long epoch;
    private final RealTradingGuard issuer;

    OrderSessionPermit(AccountId account, long epoch, RealTradingGuard issuer) {
        this.account = Objects.requireNonNull(account, "account");
        this.epoch = epoch;
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    public AccountId account() {
        return account;
    }

    long epoch() {
        return epoch;
    }

    RealTradingGuard issuer() {
        return issuer;
    }

    /** 지금도 유효한지. 계좌가 OFF로 내려간 적이 있으면 false. */
    public boolean isValid() {
        return issuer.isValid(this);
    }

    @Override
    public String toString() {
        return "OrderSessionPermit[" + account + "#" + epoch + "]";
    }
}
