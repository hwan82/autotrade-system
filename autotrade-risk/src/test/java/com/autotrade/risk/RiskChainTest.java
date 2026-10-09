package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 가상·실전 공통 검증 체인(SIM-12, RSK-02, D-023 공통 원칙 1). 위반 시도가 막히는지 본다(TST-03). */
class RiskChainTest {

    private static final AccountId ACCOUNT = new AccountId("SIM-A01");
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");
    private static final Symbol APPLE = Symbol.us("AAPL");
    private static final AccountSnapshot SNAPSHOT = new AccountSnapshot(ACCOUNT, Instant.parse("2026-10-08T01:00:00Z"),
            Money.of(10_000_000, Money.KRW), new TreeMap<>());
    private static final RiskPolicy DENY = (o, a) -> RiskDecision.reject("DAILY_LOSS_LIMIT", "테스트 한도");

    private static final BlacklistSnapshot LIST = BlacklistSnapshot.of(List.of(
            new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "사용자 지정"),
            new BlacklistEntry(APPLE, BlacklistType.AUTO_ASSET, "대사에서 감지")));

    @Test
    void 제외_종목은_진입_청산_손절_모두_막는다() {
        RiskChain chain = RiskChain.builder(LIST).build();
        for (OrderIntent intent : OrderIntent.values()) {
            RiskDecision d = chain.evaluate(order(HYNIX, intent), SNAPSHOT);
            assertThat(d).as(intent.name()).isEqualTo(RiskDecision.reject("BLACKLIST", "제외 종목: KRX:000660"));
        }
    }

    @Test
    void 수동_보유_종목은_다른_규칙_ID로_막는다() {
        RiskChain chain = RiskChain.builder(LIST).build();
        for (OrderIntent intent : OrderIntent.values()) {
            RiskDecision d = chain.evaluate(order(APPLE, intent), SNAPSHOT);
            assertThat(d).as(intent.name()).isInstanceOfSatisfying(RiskDecision.Rejected.class,
                    r -> assertThat(r.ruleId()).isEqualTo("MANUAL_HOLDING"));
        }
    }

    @Test
    void 블랙리스트는_진입_규칙보다_먼저다() {
        List<String> called = new ArrayList<>();
        RiskChain chain = RiskChain.builder(LIST).entryRule((o, a) -> {
            called.add(o.symbol().code());
            return RiskDecision.approve();
        }).build();

        assertThat(chain.evaluate(order(HYNIX, OrderIntent.ENTRY), SNAPSHOT).isApproved()).isFalse();
        assertThat(called).isEmpty();
        assertThat(chain.evaluate(order(SAMSUNG, OrderIntent.ENTRY), SNAPSHOT).isApproved()).isTrue();
        assertThat(called).containsExactly("005930");
    }

    @Test
    void 진입_규칙은_청산과_손절을_막지_못한다() {
        RiskChain chain = RiskChain.builder(BlacklistSnapshot.EMPTY).entryRule(DENY).build();

        assertThat(chain.evaluate(order(SAMSUNG, OrderIntent.ENTRY), SNAPSHOT))
                .isEqualTo(RiskDecision.reject("DAILY_LOSS_LIMIT", "테스트 한도"));
        assertThat(chain.evaluate(order(SAMSUNG, OrderIntent.EXIT), SNAPSHOT).isApproved()).isTrue();
        assertThat(chain.evaluate(order(SAMSUNG, OrderIntent.STOP_LOSS), SNAPSHOT).isApproved()).isTrue();
    }

    @Test
    void 진입_규칙은_등록_순서대로_보고_첫_거절에서_멈춘다() {
        List<String> called = new ArrayList<>();
        RiskChain chain = RiskChain.builder(BlacklistSnapshot.EMPTY)
                .entryRule((o, a) -> {
                    called.add("first");
                    return RiskDecision.approve();
                })
                .entryRule((o, a) -> {
                    called.add("second");
                    return RiskDecision.reject("SECOND", "둘째");
                })
                .entryRule((o, a) -> {
                    called.add("third");
                    return RiskDecision.reject("THIRD", "셋째");
                })
                .build();

        assertThat(chain.evaluate(order(SAMSUNG, OrderIntent.ENTRY), SNAPSHOT))
                .isEqualTo(RiskDecision.reject("SECOND", "둘째"));
        assertThat(called).containsExactly("first", "second");
        assertThat(chain.entryRuleCount()).isEqualTo(3);
    }

    @Test
    void 규칙_오류는_진입을_닫는다() {
        RiskChain throwing = RiskChain.builder(BlacklistSnapshot.EMPTY).entryRule((o, a) -> {
            throw new IllegalStateException("저장소 장애");
        }).build();
        RiskChain nulls = RiskChain.builder(BlacklistSnapshot.EMPTY).entryRule((o, a) -> null).build();

        assertThat(throwing.evaluate(order(SAMSUNG, OrderIntent.ENTRY), SNAPSHOT))
                .isInstanceOfSatisfying(RiskDecision.Rejected.class,
                        r -> assertThat(r.ruleId()).isEqualTo(RiskChain.RULE_ERROR));
        assertThat(nulls.evaluate(order(SAMSUNG, OrderIntent.ENTRY), SNAPSHOT))
                .isInstanceOfSatisfying(RiskDecision.Rejected.class,
                        r -> assertThat(r.ruleId()).isEqualTo(RiskChain.RULE_ERROR));
        // 규칙 오류가 있어도 손절은 진입 규칙을 거치지 않으므로 나간다
        assertThat(throwing.evaluate(order(SAMSUNG, OrderIntent.STOP_LOSS), SNAPSHOT).isApproved()).isTrue();
    }

    @Test
    void 다른_계좌의_스냅샷으로는_판정하지_않는다() {
        AccountSnapshot other = new AccountSnapshot(new AccountId("SIM-A02"), SNAPSHOT.asOf(), SNAPSHOT.cash(),
                new TreeMap<>());
        RiskChain chain = RiskChain.builder(BlacklistSnapshot.EMPTY).build();

        assertThat(chain.evaluate(order(SAMSUNG, OrderIntent.STOP_LOSS), other))
                .isInstanceOfSatisfying(RiskDecision.Rejected.class,
                        r -> assertThat(r.ruleId()).isEqualTo(RiskChain.RULE_ERROR));
    }

    @Test
    void 블랙리스트_없는_체인과_체인_중첩은_만들_수_없다() {
        assertThatThrownBy(() -> RiskChain.builder(null)).isInstanceOf(NullPointerException.class);
        RiskChain inner = RiskChain.builder(LIST).build();
        assertThatThrownBy(() -> RiskChain.builder(BlacklistSnapshot.EMPTY).entryRule(inner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 빈_블랙리스트와_진입_규칙_없는_체인은_정상_주문을_승인한다() {
        RiskChain chain = RiskChain.builder(BlacklistSnapshot.EMPTY).build();
        for (OrderIntent intent : OrderIntent.values()) {
            assertThat(chain.evaluate(order(SAMSUNG, intent), SNAPSHOT).isApproved()).as(intent.name()).isTrue();
        }
    }

    private static OrderRequest order(Symbol symbol, OrderIntent intent) {
        Side side = intent == OrderIntent.ENTRY ? Side.BUY : Side.SELL;
        return new OrderRequest(IdempotencyKey.of(LocalDate.of(2026, 10, 8), symbol, side, 1), ACCOUNT, symbol, side,
                intent, OrderType.MARKET, null, 10);
    }
}
