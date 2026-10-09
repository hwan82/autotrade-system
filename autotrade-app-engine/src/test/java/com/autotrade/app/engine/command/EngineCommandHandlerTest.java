package com.autotrade.app.engine.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.control.ControlCodes;
import com.autotrade.control.ControlCommand;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.PanicStepResult;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.risk.guard.ActivationStep;
import com.autotrade.risk.guard.GuardClosedException;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.GuardTransition;
import com.autotrade.risk.guard.RealTradingGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineCommandHandlerTest {

    static final AccountRef KIS = new AccountRef(new AccountId("KIS-US-1"), BrokerId.KIS, Market.US);
    static final AccountRef KIWOOM = new AccountRef(new AccountId("KIWOOM-KR-1"), BrokerId.KIWOOM, Market.KRX);
    static final String PASSWORD = OperatorPasswordHashTest.PASSWORD;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T00:00:00Z"));
    private final List<GuardTransition> guardAudit = new ArrayList<>();
    private final List<String> commandAudit = new ArrayList<>();
    private final List<ActivationStep> performed = new ArrayList<>();
    private final List<String> liquidated = new ArrayList<>();
    private RealTradingGuard guard;
    private ActivationStepRunner steps = (account, step) -> {
        performed.add(step);
        return Optional.empty();
    };
    private PanicLiquidator liquidator = account -> {
        liquidated.add(account.id().value());
        return PanicSteps.BROKER_STEPS.stream()
                .map(s -> PanicSteps.result(account, s, PanicSteps.DONE, "")).toList();
    };
    private Set<String> configured = Set.of("KIS-US-1", "KIWOOM-KR-1");

    @BeforeEach
    void setUp() {
        guard = new RealTradingGuard(List.of(KIS, KIWOOM), clock, guardAudit::add);
    }

    private EngineCommandHandler handler() {
        return new EngineCommandHandler(guard,
                new OperatorAuthenticator(OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH), clock),
                configured::contains, steps, liquidator,
                (command, target, code) -> commandAudit.add(command + " " + target + " " + code));
    }

    private ControlResponse send(EngineCommandHandler handler, ControlRequest request) {
        clock.advance(Duration.ofSeconds(1));
        return handler.apply(request);
    }

    @Test
    void 기동_직후_상태는_모든_계좌_OFF이고_비밀값을_담지_않는다() {
        ControlResponse r = send(handler(), ControlRequest.status());

        assertThat(r.ok()).isTrue();
        assertThat(r.status().passwordConfigured()).isTrue();
        assertThat(r.status().accounts()).extracting(a -> a.state()).containsExactly("OFF", "OFF");
        assertThat(r.status().accounts()).extracting(a -> a.id()).containsExactly("KIS-US-1", "KIWOOM-KR-1");
        assertThat(r.toString()).doesNotContain(OperatorPasswordHashTest.HASH);
        assertThat(commandAudit).isEmpty();
    }

    @Test
    void 비밀번호와_계좌_별칭이_맞으면_단계를_순서대로_통과해_OPEN이_된다() {
        ControlResponse r = send(handler(), ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.ok()).isTrue();
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OPEN);
        assertThat(guard.state(KIWOOM.id())).isEqualTo(GuardState.OFF);
        assertThat(performed).containsExactly(ActivationStep.values());
        assertThat(guardAudit).extracting(GuardTransition::to)
                .containsExactly(GuardState.ACTIVATING, GuardState.OPEN);
        assertThat(guardAudit).allSatisfy(t -> assertThat(t.actor()).isEqualTo("operator"));
        assertThat(commandAudit).containsExactly("ACTIVATE KIS-US-1 OK");
    }

    @Test
    void 비밀번호나_확인_문구가_틀리면_Guard가_움직이지_않는다() {
        EngineCommandHandler h = handler();

        ControlResponse wrongPassword = send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", "wrong-password"));
        ControlResponse wrongPhrase = send(h, ControlRequest.activate("KIS-US-1", "KIWOOM-KR-1", PASSWORD));
        ControlResponse noPassword = send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", null));

        assertThat(List.of(wrongPassword, wrongPhrase, noPassword)).allSatisfy(r -> {
            assertThat(r.ok()).isFalse();
            assertThat(r.code()).isEqualTo(ControlCodes.CONFIRMATION_FAILED);
            assertThat(r.message()).doesNotContain(PASSWORD).doesNotContain("wrong-password");
        });
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(performed).isEmpty();
        assertThat(guardAudit).isEmpty();
        assertThat(commandAudit).allSatisfy(line -> assertThat(line).endsWith("CONFIRMATION_FAILED"));
    }

    @Test
    void 증권사_연동_전에는_첫_단계에서_실패해_OFF로_돌아간다() {
        steps = ActivationStepRunner.BROKER_NOT_CONNECTED;

        ControlResponse r = send(handler(), ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("활성화 실패").contains("OFF");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(guardAudit).extracting(GuardTransition::to).containsExactly(GuardState.ACTIVATING, GuardState.OFF);
        assertThatThrownBy(() -> guard.issueSessionPermit(KIS.id())).isInstanceOf(GuardClosedException.class);
    }

    @Test
    void 단계_중간에_실패하거나_예외가_나면_출발_상태로_돌아간다() {
        for (ActivationStep failing : ActivationStep.values()) {
            setUp();
            performed.clear();
            steps = (account, step) -> {
                performed.add(step);
                if (step == failing) {
                    throw new IllegalStateException("broker down");
                }
                return Optional.empty();
            };
            send(handler(), ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

            assertThat(guard.state(KIS.id())).as(failing.name()).isEqualTo(GuardState.OFF);
            assertThat(performed).last().isEqualTo(failing);
        }
    }

    @Test
    void 계좌번호가_없거나_모르는_계좌거나_이미_열린_계좌는_활성화하지_않는다() {
        configured = Set.of("KIS-US-1");
        EngineCommandHandler h = handler();

        assertThat(send(h, ControlRequest.activate("KIWOOM-KR-1", "KIWOOM-KR-1", PASSWORD)).code())
                .isEqualTo(ControlCodes.INVALID_STATE);
        assertThat(send(h, ControlRequest.activate("NOPE", "NOPE", PASSWORD)).code())
                .isEqualTo(ControlCodes.UNKNOWN_ACCOUNT);
        assertThat(send(h, ControlRequest.activate(null, null, PASSWORD)).code())
                .isEqualTo(ControlCodes.UNKNOWN_ACCOUNT);
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        assertThat(send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD)).code())
                .isEqualTo(ControlCodes.INVALID_STATE);
        // 모르는 계좌 값은 감사 로그에 그대로 남기지 않는다
        assertThat(commandAudit).noneMatch(line -> line.contains("NOPE"));
    }

    @Test
    void 비밀번호가_설정되지_않으면_활성화와_PANIC을_모두_거절한다() {
        EngineCommandHandler h = new EngineCommandHandler(guard, new OperatorAuthenticator(Optional.empty(), clock),
                configured::contains, steps, liquidator, (c, t, code) -> commandAudit.add(code));

        assertThat(send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD)).code())
                .isEqualTo(ControlCodes.PASSWORD_NOT_CONFIGURED);
        assertThat(send(h, ControlRequest.panicAll(ControlRequest.PANIC_ALL_PHRASE, PASSWORD)).code())
                .isEqualTo(ControlCodes.PASSWORD_NOT_CONFIGURED);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(liquidated).isEmpty();
    }

    @Test
    void 내리기는_확인_없이_OPEN을_보호_모드로_내린다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        ControlResponse r = send(h, ControlRequest.deactivate("KIS-US-1", "점검\n\u0007"));

        assertThat(r.ok()).isTrue();
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(guardAudit.getLast().reason()).isEqualTo("점검");
        assertThat(send(h, ControlRequest.deactivate("KIS-US-1", null)).message()).contains("이미 PROTECTIVE");
    }

    @Test
    void 연속_실패로_잠기면_내리기는_되고_올리기는_안_된다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES; i++) {
            send(h, ControlRequest.activate("KIWOOM-KR-1", "KIWOOM-KR-1", "wrong-password"));
        }

        assertThat(send(h, ControlRequest.activate("KIWOOM-KR-1", "KIWOOM-KR-1", PASSWORD)).code())
                .isEqualTo(ControlCodes.LOCKED);
        assertThat(send(h, ControlRequest.status()).status().lockedUntil()).isNotNull();
        send(h, ControlRequest.deactivate("KIS-US-1", null));
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
    }

    @Test
    void PANIC_SELL은_보호_모드_고정_후_증권사_단계가_모두_끝나야_Guard를_끈다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        ControlResponse r = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.ok()).isTrue();
        assertThat(r.steps()).extracting(PanicStepResult::step).containsExactly(
                "PROTECTIVE_LOCK", "CANCEL_OPEN_ORDERS", "CANCEL_SERVER_STOPS", "SELL", "CONFIRM_FILLS", "GUARD_OFF");
        assertThat(r.steps()).extracting(PanicStepResult::outcome).containsOnly("DONE");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(guardAudit).extracting(GuardTransition::to).endsWith(GuardState.PROTECTIVE, GuardState.OFF);
    }

    @Test
    void 증권사_연동_전_PANIC은_주문을_내지_않고_보호_모드를_유지한다() {
        EngineCommandHandler opener = handler();
        send(opener, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = PanicLiquidator.BROKER_NOT_CONNECTED;

        ControlResponse r = send(handler(), ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(r.steps()).filteredOn(s -> s.step().equals("SELL")).extracting(PanicStepResult::outcome)
                .containsExactly("NOT_AVAILABLE");
        assertThat(r.steps().getLast().outcome()).isEqualTo("SKIPPED");
        assertThat(r.steps().getLast().note()).contains("보호 모드를 유지");
    }

    @Test
    void 매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다() {
        EngineCommandHandler opener = handler();
        send(opener, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = account -> List.of(
                PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.CANCEL_SERVER_STOPS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.SELL, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.CONFIRM_FILLS, PanicSteps.FAILED, "미체결 잔량"));

        send(handler(), ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
    }

    @Test
    void PANIC은_2차_확인이_틀려도_보호_모드_고정은_하고_청산은_하지_않는다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        ControlResponse r = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", "wrong-password"));

        assertThat(r.ok()).isFalse();
        assertThat(r.code()).isEqualTo(ControlCodes.CONFIRMATION_FAILED);
        assertThat(r.message()).contains("보호 모드 고정");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(r.steps()).extracting(PanicStepResult::step).containsExactly(
                "PROTECTIVE_LOCK", "CANCEL_OPEN_ORDERS", "CANCEL_SERVER_STOPS", "SELL", "CONFIRM_FILLS", "GUARD_OFF");
        assertThat(r.steps().getFirst().outcome()).isEqualTo("DONE");
        assertThat(r.steps().subList(1, 6)).extracting(PanicStepResult::outcome).containsOnly("SKIPPED");
        assertThat(liquidated).isEmpty();

        ControlResponse all = send(h, ControlRequest.panicAll("전체", PASSWORD));
        assertThat(all.code()).isEqualTo(ControlCodes.CONFIRMATION_FAILED);
        assertThat(liquidated).isEmpty();
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
    }

    @Test
    void 잠긴_상태에서도_PANIC은_보호_모드_고정을_한다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES; i++) {
            send(h, ControlRequest.activate("KIWOOM-KR-1", "KIWOOM-KR-1", "wrong-password"));
        }

        ControlResponse r = send(h, ControlRequest.panicAll(ControlRequest.PANIC_ALL_PHRASE, PASSWORD));

        assertThat(r.code()).isEqualTo(ControlCodes.LOCKED);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(liquidated).isEmpty();
    }

    @Test
    void 비밀번호가_없어도_PANIC은_열린_계좌를_보호_모드로_고정한다() {
        EngineCommandHandler opener = handler();
        send(opener, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        EngineCommandHandler none = new EngineCommandHandler(guard, new OperatorAuthenticator(Optional.empty(), clock),
                configured::contains, steps, liquidator, (c, t, code) -> commandAudit.add(code));

        ControlResponse r = send(none, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.code()).isEqualTo(ControlCodes.PASSWORD_NOT_CONFIGURED);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(liquidated).isEmpty();
    }

    @Test
    void 서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다() {
        EngineCommandHandler opener = handler();
        send(opener, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = account -> List.of(
                PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.CANCEL_SERVER_STOPS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.SELL, PanicSteps.FAILED, "거부"));

        ControlResponse r = send(handler(), ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        PanicStepResult last = r.steps().getLast();
        assertThat(last.step()).isEqualTo("GUARD_OFF");
        assertThat(last.urgent()).isTrue();
        assertThat(last.note()).contains("서버 측 손절이 취소된 상태").doesNotContain("서버 측 손절 계속");
        assertThat(r.message()).contains("긴급");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
    }

    @Test
    void 서버_측_손절을_취소하기_전에_멈추면_긴급이_아니다() {
        EngineCommandHandler opener = handler();
        send(opener, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = account -> List.of(
                PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.FAILED, "조회 실패"));

        ControlResponse r = send(handler(), ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.steps().getLast().urgent()).isFalse();
        assertThat(r.steps().getLast().note()).contains("서버 측 손절 계속");
    }

    @Test
    void 단계_수행_중_Error가_나도_ACTIVATING에_남지_않는다() {
        steps = (account, step) -> {
            if (step == ActivationStep.ORDER_SESSION) {
                throw new LinkageError("test");
            }
            return Optional.empty();
        };
        EngineCommandHandler h = handler();

        assertThatThrownBy(() -> send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD)))
                .isInstanceOf(LinkageError.class);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
    }

    @Test
    void 감사_로그가_실패해도_결과는_그대로_돌려준다() {
        EngineCommandHandler h = new EngineCommandHandler(guard,
                new OperatorAuthenticator(OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH), clock),
                configured::contains, steps, liquidator, (command, target, code) -> {
                    throw new IllegalStateException("disk full");
                });

        ControlResponse r = send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("감사 로그 기록 실패");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OPEN);
    }

    @Test
    void 전체_PANIC은_모든_계좌를_먼저_잠근_뒤_계좌별로_청산하고_OFF_계좌는_건너뛴다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        ControlResponse r = send(h, ControlRequest.panicAll(ControlRequest.PANIC_ALL_PHRASE, PASSWORD));

        assertThat(r.steps().subList(0, 2)).extracting(PanicStepResult::step)
                .containsExactly("PROTECTIVE_LOCK", "PROTECTIVE_LOCK");
        assertThat(liquidated).containsExactly("KIS-US-1");
        assertThat(r.steps()).filteredOn(s -> s.account().equals("KIWOOM-KR-1"))
                .extracting(PanicStepResult::outcome).containsOnly("SKIPPED");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(guard.state(KIWOOM.id())).isEqualTo(GuardState.OFF);
        assertThat(commandAudit.getLast()).isEqualTo("PANIC_ALL ALL OK");
    }

    @Test
    void 활성화_중_PANIC은_활성화를_중단한다() {
        EngineCommandHandler[] holder = new EngineCommandHandler[1];
        steps = (account, step) -> {
            if (step == ActivationStep.ORDER_SESSION) {
                // 활성화 도중 PANIC이 처리되는 상황은 단일 스레드라 없지만, 상태 ACTIVATING에서의 잠금 규칙을 확인한다
                assertThat(guard.state(account.id())).isEqualTo(GuardState.ACTIVATING);
                holder[0].apply(new ControlRequest(ControlCommand.DEACTIVATE, account.id().value(), null, null, "x"));
            }
            return Optional.empty();
        };
        holder[0] = handler();

        send(holder[0], ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
    }
}
