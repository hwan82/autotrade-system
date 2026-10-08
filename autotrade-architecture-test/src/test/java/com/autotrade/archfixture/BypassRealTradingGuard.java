package com.autotrade.archfixture;

import com.autotrade.broker.BrokerPlugin;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.risk.guard.ActivationStep;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 규칙 검증용 위반 코드: Guard를 거치지 않고 증권사 주문 출구를 얻는 흔한 방법들과, 명령 채널 밖에서 Guard를 올리는 코드.
 * 운영 코드가 아니다.
 */
public final class BypassRealTradingGuard {

    private BypassRealTradingGuard() {}

    /** 인터페이스 타입으로 호출. */
    public static Optional<OrderGateway> viaInterface(BrokerPlugin plugin, AccountRef account) {
        return plugin.orderGateway(account);
    }

    /** 구체 플러그인 타입으로 호출(2단계 KisBrokerPlugin 같은 경우). */
    public static Optional<OrderGateway> viaConcrete(ConcretePlugin plugin, AccountRef account) {
        return plugin.orderGateway(account);
    }

    /** 하위 인터페이스로 호출. */
    public static Optional<OrderGateway> viaSubInterface(SubPlugin plugin, AccountRef account) {
        return plugin.orderGateway(account);
    }

    /** 메서드 참조. */
    public static Function<AccountRef, Optional<OrderGateway>> viaMethodReference(BrokerPlugin plugin) {
        return plugin::orderGateway;
    }

    /** 명령 채널 밖에서 2차 확인을 만들어 Guard를 올린다. */
    public static void raiseWithoutCommandChannel(RealTradingGuard guard) {
        guard.beginActivation(new SecondConfirmation(new AccountId("KIS-1"), "nobody", Instant.EPOCH));
    }

    /** 명령 채널 밖에서 참조로 Guard를 올린다. */
    public static void raiseByReference(RealTradingGuard guard) {
        Consumer<SecondConfirmation> begin = guard::beginActivation;
        Consumer<SecondConfirmation> protect = guard::enterProtective;
        BiConsumer<AccountId, ActivationStep> step = guard::completeStep;
        TriFunction<AccountId, String, Instant, SecondConfirmation> confirm = SecondConfirmation::new;
        begin.accept(confirm.apply(new AccountId("KIS-1"), "nobody", Instant.EPOCH));
        protect.accept(null);
        step.accept(null, ActivationStep.AUTHENTICATED);
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    public abstract static class ConcretePlugin implements BrokerPlugin {
        @Override
        public Optional<OrderGateway> orderGateway(AccountRef account) {
            return Optional.empty();
        }
    }

    public interface SubPlugin extends BrokerPlugin {}
}
