package com.autotrade.risk.guard;

import static com.autotrade.risk.guard.GuardFixtures.ACCOUNTS;
import static com.autotrade.risk.guard.GuardFixtures.KIS_KR;
import static com.autotrade.risk.guard.GuardFixtures.KIWOOM_KR;
import static com.autotrade.risk.guard.GuardFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.guard.GuardFixtures.MutableClock;
import com.autotrade.risk.guard.GuardFixtures.RecordingAudit;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** RSK-01·CFG-02 실패 테스트(TST-03). */
class RealTradingGuardTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T00:00:00Z"));
    private final RecordingAudit audit = new RecordingAudit();
    private final RealTradingGuard guard = new RealTradingGuard(ACCOUNTS, clock, audit);

    private SecondConfirmation confirm(AccountId account) {
        clock.advance(Duration.ofSeconds(1));
        return new SecondConfirmation(account, "BAE", clock.instant());
    }

    private void open(AccountId account) {
        guard.beginActivation(confirm(account));
        for (ActivationStep step : ActivationStep.values()) {
            guard.completeStep(account, step);
        }
    }

    // ---- 기본 OFF, 재기동 OFF ----

    @Test
    void 생성_직후_모든_계좌는_OFF다() {
        assertThat(guard.accounts()).extracting(AccountRef::id).containsExactly(KIS_KR, KIWOOM_KR);
        assertThat(guard.accounts()).allSatisfy(a -> assertThat(guard.state(a.id())).isEqualTo(GuardState.OFF));
        assertThat(audit.records).isEmpty();
    }

    @Test
    void 재기동하면_열려_있던_계좌도_OFF로_시작한다() {
        open(KIS_KR);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OPEN);

        // 재기동 = 같은 계좌 목록으로 새 Guard 생성. 이전 상태를 넘길 방법이 없다
        RealTradingGuard restarted = new RealTradingGuard(ACCOUNTS, clock, audit);

        assertThat(restarted.state(KIS_KR)).isEqualTo(GuardState.OFF);
        assertThat(restarted.check(order(KIS_KR, OrderIntent.ENTRY)).isApproved()).isFalse();
    }

    @Test
    void 상태를_받아_열린_채로_만드는_생성_경로가_없다() {
        // 공개 생성자는 계좌 목록·시계·감사 로그만 받는다. 상태·enabled 인자를 추가하면 이 테스트가 실패한다(CFG-02)
        Constructor<?>[] ctors = RealTradingGuard.class.getConstructors();
        assertThat(ctors).hasSize(1);
        assertThat(ctors[0].getParameterTypes()).extracting(Class::getSimpleName)
                .containsExactly("Collection", "Clock", "GuardAuditLog");
        assertThat(Arrays.stream(RealTradingGuard.class.getMethods()))
                .filteredOn(m -> Modifier.isStatic(m.getModifiers()))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OrderIntent.class)
    void OFF에서는_어떤_주문도_통과하지_않는다(OrderIntent intent) {
        RiskDecision decision = guard.check(order(KIS_KR, intent));

        assertThat(decision).isInstanceOf(RiskDecision.Rejected.class);
        assertThat(((RiskDecision.Rejected) decision).ruleId()).isEqualTo(RealTradingGuard.RULE_ID);
    }

    @Test
    void 등록되지_않은_계좌의_주문은_거절한다() {
        RiskDecision decision = guard.check(order(new AccountId("TOSS-KR-9"), OrderIntent.STOP_LOSS));
        assertThat(decision.isApproved()).isFalse();
    }

    @Test
    void 계좌_별칭이_겹치면_만들지_않는다() {
        List<AccountRef> dup = List.of(ACCOUNTS.get(0), new AccountRef(KIS_KR, BrokerId.KIS, Market.US));
        assertThatThrownBy(() -> new RealTradingGuard(dup, clock, audit)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 주문 세션 허가 ----

    @Test
    void OFF_계좌는_주문_세션_허가를_받을_수_없다() {
        assertThatThrownBy(() -> guard.issueSessionPermit(KIS_KR)).isInstanceOf(GuardClosedException.class);
    }

    @Test
    void 인증을_통과하기_전에는_주문_세션_허가를_받을_수_없다() {
        guard.beginActivation(confirm(KIS_KR));
        assertThatThrownBy(() -> guard.issueSessionPermit(KIS_KR)).isInstanceOf(GuardClosedException.class);

        guard.completeStep(KIS_KR, ActivationStep.AUTHENTICATED);
        assertThat(guard.issueSessionPermit(KIS_KR).isValid()).isTrue();
    }

    @Test
    void OFF로_내려가면_그전_허가는_모두_무효가_되고_다시_열어도_살아나지_않는다() {
        open(KIS_KR);
        OrderSessionPermit permit = guard.issueSessionPermit(KIS_KR);

        guard.shutdown(KIS_KR, "BAE", "킬스위치");
        assertThat(permit.isValid()).isFalse();

        open(KIS_KR);
        assertThat(permit.isValid()).isFalse();
        assertThat(guard.issueSessionPermit(KIS_KR).isValid()).isTrue();
    }

    // ---- 활성화 순서 ----

    @Test
    void 모든_단계를_순서대로_통과해야_OPEN이_된다() {
        guard.beginActivation(confirm(KIS_KR));
        guard.completeStep(KIS_KR, ActivationStep.AUTHENTICATED);
        guard.completeStep(KIS_KR, ActivationStep.ORDER_SESSION);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.ACTIVATING);
        assertThat(guard.check(order(KIS_KR, OrderIntent.ENTRY)).isApproved()).isFalse();

        guard.completeStep(KIS_KR, ActivationStep.FIRST_RECONCILIATION);

        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OPEN);
        assertThat(guard.check(order(KIS_KR, OrderIntent.ENTRY)).isApproved()).isTrue();
        assertThat(audit.records).extracting(GuardTransition::to)
                .containsExactly(GuardState.ACTIVATING, GuardState.OPEN);
        assertThat(audit.records.get(1).actor()).isEqualTo("BAE");
    }

    @Test
    void 단계를_건너뛰면_거부하고_상태는_그대로다() {
        guard.beginActivation(confirm(KIS_KR));
        assertThatThrownBy(() -> guard.completeStep(KIS_KR, ActivationStep.FIRST_RECONCILIATION))
                .isInstanceOf(GuardClosedException.class);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.ACTIVATING);
        assertThat(guard.completedSteps(KIS_KR)).isEmpty();
    }

    @Test
    void 활성화를_시작하지_않고는_단계를_통과시킬_수_없다() {
        assertThatThrownBy(() -> guard.completeStep(KIS_KR, ActivationStep.AUTHENTICATED))
                .isInstanceOf(GuardClosedException.class);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
    }

    @ParameterizedTest
    @EnumSource(ActivationStep.class)
    void 어느_단계에서_실패해도_OFF로_돌아간다(ActivationStep failingStep) {
        guard.beginActivation(confirm(KIS_KR));
        for (ActivationStep step : ActivationStep.values()) {
            if (step == failingStep) {
                guard.failActivation(KIS_KR, step + " 실패");
                break;
            }
            guard.completeStep(KIS_KR, step);
        }
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
        assertThatThrownBy(() -> guard.issueSessionPermit(KIS_KR)).isInstanceOf(GuardClosedException.class);
    }

    // ---- 2차 확인 ----

    @Test
    void 다른_계좌의_2차_확인으로는_열_수_없다() {
        SecondConfirmation other = confirm(KIWOOM_KR);
        // 확인 객체의 계좌가 대상이므로 KIWOOM이 활성화되고 KIS는 그대로다
        guard.beginActivation(other);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
        assertThat(guard.state(KIWOOM_KR)).isEqualTo(GuardState.ACTIVATING);
    }

    @Test
    void 만료된_2차_확인은_거부한다() {
        SecondConfirmation c = confirm(KIS_KR);
        clock.advance(RealTradingGuard.CONFIRMATION_TTL.plusSeconds(1));

        assertThatThrownBy(() -> guard.beginActivation(c)).isInstanceOf(IllegalArgumentException.class);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
    }

    @Test
    void 미래_시각의_2차_확인은_거부한다() {
        SecondConfirmation c = new SecondConfirmation(KIS_KR, "BAE", clock.instant().plusSeconds(5));
        assertThatThrownBy(() -> guard.beginActivation(c)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_2차_확인을_두_번_쓸_수_없다() {
        SecondConfirmation c = confirm(KIS_KR);
        guard.beginActivation(c);
        guard.failActivation(KIS_KR, "인증 실패");

        assertThatThrownBy(() -> guard.beginActivation(c)).isInstanceOf(IllegalArgumentException.class);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
    }

    @Test
    void 감사_기록에_실패하면_올라가지_않는다() {
        audit.failing = true;
        assertThatThrownBy(() -> guard.beginActivation(confirm(KIS_KR))).isInstanceOf(IllegalStateException.class);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
    }

    @Test
    void 감사_기록에_실패해도_내려가는_전이는_일어난다() {
        open(KIS_KR);
        audit.failing = true;

        guard.shutdown(KIS_KR, GuardTransition.SYSTEM, "킬스위치");

        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
    }

    // ---- 보호 모드 ----

    @Test
    void 끄면_보호_모드가_되어_신규_진입은_막고_청산_손절은_통과한다() {
        open(KIS_KR);

        guard.deactivate(KIS_KR, "BAE", "사용자 OFF");

        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.PROTECTIVE);
        assertThat(guard.check(order(KIS_KR, OrderIntent.ENTRY)).isApproved()).isFalse();
        assertThat(guard.check(order(KIS_KR, OrderIntent.EXIT)).isApproved()).isTrue();
        assertThat(guard.check(order(KIS_KR, OrderIntent.STOP_LOSS)).isApproved()).isTrue();
        assertThat(guard.mayCancel(KIS_KR)).isTrue();
    }

    @Test
    void 보호_모드에서_보유가_0이_되면_OFF로_내려간다() {
        open(KIS_KR);
        guard.deactivate(KIS_KR, "BAE", "사용자 OFF");

        guard.holdingsCleared(KIS_KR);

        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
        assertThat(guard.mayCancel(KIS_KR)).isFalse();
    }

    @Test
    void 재기동_후_보유가_있으면_2차_확인으로만_보호_모드에_들어간다() {
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);

        guard.enterProtective(confirm(KIS_KR));

        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.PROTECTIVE);
        assertThat(guard.check(order(KIS_KR, OrderIntent.STOP_LOSS)).isApproved()).isTrue();
        assertThat(guard.check(order(KIS_KR, OrderIntent.ENTRY)).isApproved()).isFalse();
    }

    @Test
    void 보호_모드에서_다시_여는_동안에도_손절은_계속되고_실패하면_보호_모드로_돌아간다() {
        guard.enterProtective(confirm(KIS_KR));
        guard.beginActivation(confirm(KIS_KR));

        assertThat(guard.check(order(KIS_KR, OrderIntent.STOP_LOSS)).isApproved()).isTrue();
        assertThat(guard.check(order(KIS_KR, OrderIntent.ENTRY)).isApproved()).isFalse();

        guard.failActivation(KIS_KR, "대사 불일치");
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.PROTECTIVE);
    }

    @Test
    void OFF에서_시작한_활성화_중에는_손절도_통과하지_않는다() {
        // 보유가 없다고 확인된 상태에서 연 것이므로 보호할 주문이 없다. 대사 전 주문을 막는다
        guard.beginActivation(confirm(KIS_KR));
        guard.completeStep(KIS_KR, ActivationStep.AUTHENTICATED);
        assertThat(guard.check(order(KIS_KR, OrderIntent.STOP_LOSS)).isApproved()).isFalse();
    }

    @Test
    void 자동으로_올라가는_경로는_없다() {
        // 2차 확인을 받지 않는 공개 메서드는 모두 상태를 낮추거나 그대로 둔다
        open(KIS_KR);
        guard.deactivate(KIS_KR, GuardTransition.SYSTEM, "서킷 2단계");
        guard.holdingsCleared(KIS_KR);
        guard.deactivate(KIS_KR, GuardTransition.SYSTEM, "다시");
        guard.failActivation(KIS_KR, "없음");
        guard.holdingsCleared(KIS_KR);
        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OFF);
    }

    @Test
    void 감사_기록이_느려도_주문_판정은_기다리지_않는다() throws Exception {
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockAudit = new AtomicBoolean();
        RealTradingGuard slow = new RealTradingGuard(ACCOUNTS, clock, t -> {
            if (blockAudit.get()) {
                auditEntered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        slow.enterProtective(confirm(KIWOOM_KR));
        OrderSessionPermit permit = slow.issueSessionPermit(KIWOOM_KR);
        blockAudit.set(true);
        SecondConfirmation c = confirm(KIWOOM_KR);
        Thread raiser = new Thread(() -> slow.beginActivation(c));
        raiser.start();
        try {
            assertThat(auditEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // 상향 전이가 계좌 잠금을 잡고 감사 기록에서 멈춘 동안에도 판정·취소 허용·허가 검사는 바로 돌아온다
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                assertThat(slow.check(order(KIWOOM_KR, OrderIntent.STOP_LOSS)).isApproved()).isTrue();
                assertThat(slow.check(order(KIWOOM_KR, OrderIntent.ENTRY)).isApproved()).isFalse();
                assertThat(slow.mayCancel(KIWOOM_KR)).isTrue();
                assertThat(permit.isValid()).isTrue();
            });
        } finally {
            release.countDown();
            raiser.join();
        }
        assertThat(slow.state(KIWOOM_KR)).isEqualTo(GuardState.ACTIVATING);
    }

    // ---- 계좌 격리 ----

    @Test
    void 한_계좌의_전이는_다른_계좌에_영향이_없다() {
        open(KIS_KR);
        open(KIWOOM_KR);

        guard.shutdown(KIWOOM_KR, "BAE", "킬스위치");

        assertThat(guard.state(KIS_KR)).isEqualTo(GuardState.OPEN);
        assertThat(guard.check(order(KIS_KR, OrderIntent.ENTRY)).isApproved()).isTrue();
        assertThat(guard.check(order(KIWOOM_KR, OrderIntent.STOP_LOSS)).isApproved()).isFalse();
    }
}
