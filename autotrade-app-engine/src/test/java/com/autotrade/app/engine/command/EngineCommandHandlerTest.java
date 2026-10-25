package com.autotrade.app.engine.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.control.ControlCodes;
import com.autotrade.control.ControlCommand;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.EngineStatus;
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
import com.autotrade.risk.guard.SecondConfirmation;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineCommandHandlerTest {

    static final AccountRef KIS = new AccountRef(new AccountId("KIS-US-1"), BrokerId.KIS, Market.US);
    static final AccountRef KIWOOM = new AccountRef(new AccountId("KIWOOM-KR-1"), BrokerId.KIWOOM, Market.KRX);
    static final String PASSWORD = OperatorPasswordHashTest.PASSWORD;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T00:00:00Z"));
    private final List<GuardTransition> guardAudit = Collections.synchronizedList(new ArrayList<>());
    private final List<String> commandAudit = Collections.synchronizedList(new ArrayList<>());
    private final List<ActivationStep> performed = Collections.synchronizedList(new ArrayList<>());
    private final List<String> liquidated = Collections.synchronizedList(new ArrayList<>());
    private final List<EngineCommandHandler> handlers = new ArrayList<>();
    /** 기한을 재는 시계(나노초). 시험이 직접 앞으로 민다. */
    private final AtomicLong nanos = new AtomicLong();
    private RealTradingGuard guard;
    private SecondConfirmation.Issuer issuer;
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
    private EngineCommandHandler.Limits limits = EngineCommandHandler.Limits.DEFAULT;
    private LongSupplier ticker = nanos::get;

    @BeforeEach
    void setUp() {
        guard = new RealTradingGuard(List.of(KIS, KIWOOM), clock, guardAudit::add);
        issuer = guard.confirmationIssuer();
    }

    @AfterEach
    void closeHandlers() {
        handlers.forEach(EngineCommandHandler::close);
    }

    private OperatorAuthenticator auth(Optional<OperatorPasswordHash> hash) {
        return new OperatorAuthenticator(hash, clock, issuer, until -> { });
    }

    private EngineCommandHandler handler() {
        return handler(auth(OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH)));
    }

    /** 단계·청산·계좌번호 설정은 필드를 그때그때 읽는다(시험 중간에 바꿔 끼움). */
    private EngineCommandHandler handler(OperatorAuthenticator auth) {
        EngineCommandHandler h = new EngineCommandHandler(guard, auth, id -> configured.contains(id),
                (account, step) -> steps.perform(account, step), account -> liquidator.liquidate(account),
                (command, target, code) -> commandAudit.add(command + " " + target + " " + code), "", clock, limits,
                ticker);
        handlers.add(h);
        return h;
    }

    private ControlResponse send(EngineCommandHandler handler, ControlRequest request) {
        clock.advance(Duration.ofSeconds(1));
        return handler.apply(request);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("기다린 상태가 되지 않았습니다");
            }
            Thread.sleep(10);
        }
    }

    private static EngineStatus.AccountStatus account(EngineStatus status, AccountRef ref) {
        return status.accounts().stream().filter(a -> a.id().equals(ref.id().value())).findFirst().orElseThrow();
    }

    // ---- 기존 규칙 ----

    @Test
    void 기동_직후_상태는_모든_계좌_OFF이고_비밀값을_담지_않는다() {
        ControlResponse r = send(handler(), ControlRequest.status());

        assertThat(r.ok()).isTrue();
        assertThat(r.status().passwordConfigured()).isTrue();
        assertThat(r.status().accounts()).extracting(a -> a.state()).containsExactly("OFF", "OFF");
        assertThat(r.status().accounts()).extracting(a -> a.id()).containsExactly("KIS-US-1", "KIWOOM-KR-1");
        assertThat(r.status().accounts()).extracting(a -> a.lastResult()).containsOnly("");
        assertThat(r.toString()).doesNotContain(OperatorPasswordHashTest.HASH);
        assertThat(commandAudit).isEmpty();
    }

    @Test
    void 비밀번호와_계좌_별칭이_맞으면_단계를_순서대로_통과해_OPEN이_되고_결과를_따로_남긴다() {
        ControlResponse r = send(handler(), ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.ok()).isTrue();
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OPEN);
        assertThat(guard.state(KIWOOM.id())).isEqualTo(GuardState.OFF);
        assertThat(performed).containsExactly(ActivationStep.values());
        assertThat(guardAudit).extracting(GuardTransition::to)
                .containsExactly(GuardState.ACTIVATING, GuardState.OPEN);
        assertThat(guardAudit).allSatisfy(t -> assertThat(t.actor()).isEqualTo("operator"));
        // 결과(OPEN)는 응답 코드와 따로 한 번 더 남는다(실전 활성화 외부 알림, C11)
        assertThat(commandAudit).containsExactly("ACTIVATE KIS-US-1 OPEN", "ACTIVATE KIS-US-1 OK");
        assertThat(account(r.status(), KIS).state()).isEqualTo("OPEN");
        assertThat(account(r.status(), KIS).lastResult()).contains("활성화 OPEN");
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
        assertThat(commandAudit).contains("ACTIVATE KIS-US-1 " + EngineCommandHandler.OUTCOME_ACTIVATION_FAILED);
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
        EngineCommandHandler h = handler(auth(Optional.empty()));

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
        assertThat(commandAudit).contains("PANIC_ACCOUNT KIS-US-1 " + EngineCommandHandler.OUTCOME_PANIC_DONE);
        assertThat(account(r.status(), KIS).lastResult()).contains("PANIC_DONE");
    }

    @Test
    void 증권사_연동_전_PANIC은_주문을_내지_않고_보호_모드를_유지한다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = PanicLiquidator.BROKER_NOT_CONNECTED;

        ControlResponse r = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(r.steps()).filteredOn(s -> s.step().equals("SELL")).extracting(PanicStepResult::outcome)
                .containsExactly("NOT_AVAILABLE");
        assertThat(r.steps().getLast().outcome()).isEqualTo("SKIPPED");
        assertThat(r.steps().getLast().note()).contains("보호 모드를 유지");
        assertThat(commandAudit).contains("PANIC_ACCOUNT KIS-US-1 " + EngineCommandHandler.OUTCOME_PANIC_INCOMPLETE);
    }

    @Test
    void 매도가_하나라도_끝나지_않으면_Guard를_끄지_않는다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = account -> List.of(
                PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.CANCEL_SERVER_STOPS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.SELL, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.CONFIRM_FILLS, PanicSteps.FAILED, "미체결 잔량"));

        send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

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
        EngineCommandHandler none = handler(auth(Optional.empty()));

        ControlResponse r = send(none, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.code()).isEqualTo(ControlCodes.PASSWORD_NOT_CONFIGURED);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(liquidated).isEmpty();
    }

    @Test
    void 서버_측_손절을_취소한_뒤_매도가_끝나지_않으면_긴급으로_알린다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = account -> List.of(
                PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.CANCEL_SERVER_STOPS, PanicSteps.DONE, ""),
                PanicSteps.result(account, PanicSteps.SELL, PanicSteps.FAILED, "거부"));

        ControlResponse r = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        PanicStepResult last = r.steps().getLast();
        assertThat(last.step()).isEqualTo("GUARD_OFF");
        assertThat(last.urgent()).isTrue();
        assertThat(last.note()).contains("서버 측 손절이 취소된 상태").doesNotContain("서버 측 손절 계속");
        assertThat(r.message()).contains("긴급");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);
        assertThat(account(send(h, ControlRequest.status()).status(), KIS).lastResult()).contains("긴급");
    }

    @Test
    void 서버_측_손절을_취소하기_전에_멈추면_긴급이_아니다() {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        liquidator = account -> List.of(
                PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.FAILED, "조회 실패"));

        ControlResponse r = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.steps().getLast().urgent()).isFalse();
        assertThat(r.steps().getLast().note()).contains("서버 측 손절 계속");
    }

    @Test
    void 단계_수행_중_Error가_나도_ACTIVATING에_남지_않고_기다리던_연결은_바로_내부_오류를_받는다() {
        steps = (account, step) -> {
            if (step == ActivationStep.ORDER_SESSION) {
                throw new LinkageError("test");
            }
            return Optional.empty();
        };
        EngineCommandHandler h = handler();

        long start = System.nanoTime();
        ControlResponse r = send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.code()).isEqualTo(ControlCodes.INTERNAL);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(limits.respondWithin());
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
    }

    @Test
    void 청산_중_Error가_나도_다음_PANIC은_새로_청산을_시작한다() throws Exception {
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        AtomicInteger calls = new AtomicInteger();
        liquidator = account -> {
            if (calls.incrementAndGet() == 1) {
                throw new LinkageError("test");
            }
            return PanicSteps.BROKER_STEPS.stream().map(s -> PanicSteps.result(account, s, PanicSteps.DONE, "")).toList();
        };

        ControlResponse first = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));
        assertThat(first.steps()).anySatisfy(s -> assertThat(s.outcome()).isEqualTo(PanicSteps.FAILED));
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);

        ControlResponse second = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));
        assertThat(calls).hasValue(2);
        assertThat(second.steps().getLast().outcome()).isEqualTo(PanicSteps.DONE);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
    }

    @Test
    void 검증은_명령마다_따로라_다른_명령이_끼어들어도_지워지지_않는다() {
        OperatorAuthenticator auth = auth(OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH));
        EngineCommandHandler h = handler(auth);
        // 검증과 2차 확인 생성 사이에 다른 연결의 DEACTIVATE가 끝나는 상황
        OperatorAuthenticator.Verification v = auth.verify(PASSWORD.toCharArray(), "KIS-US-1", "KIS-US-1");
        h.apply(ControlRequest.deactivate("KIWOOM-KR-1", null));

        assertThat(auth.confirmationFor(v, KIS.id()).account()).isEqualTo(KIS.id());
    }

    @Test
    void 감사_로그가_실패해도_결과는_그대로_돌려준다() {
        EngineCommandHandler h = new EngineCommandHandler(guard, auth(OperatorPasswordHash.parse(OperatorPasswordHashTest.HASH)),
                configured::contains, steps, liquidator, (command, target, code) -> {
                    throw new IllegalStateException("disk full");
                }, clock);
        handlers.add(h);

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
    void 활성화_단계_중_다른_연결의_내리기가_오면_활성화가_중단된다() {
        EngineCommandHandler[] holder = new EngineCommandHandler[1];
        steps = (account, step) -> {
            if (step == ActivationStep.ORDER_SESSION) {
                // 단계는 계좌 작업 스레드에서 돌고, 내리기는 다른 연결에서 바로 처리된다
                assertThat(guard.state(account.id())).isEqualTo(GuardState.ACTIVATING);
                holder[0].apply(new ControlRequest(ControlCommand.DEACTIVATE, account.id().value(), null, null, "x"));
            }
            return Optional.empty();
        };
        holder[0] = handler();

        ControlResponse r = send(holder[0], ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.message()).contains("중단");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
    }

    // ---- C1: 비동기 활성화 ----

    @Test
    void 활성화가_응답_기한_안에_끝나지_않으면_진행_중으로_답하고_단계는_계속_돌아_OPEN이_된다() throws Exception {
        limits = new EngineCommandHandler.Limits(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMillis(200), 2, 6);
        CountDownLatch release = new CountDownLatch(1);
        steps = (account, step) -> {
            if (step == ActivationStep.values()[0]) {
                awaitQuietly(release);
            }
            performed.add(step);
            return Optional.empty();
        };
        EngineCommandHandler h = handler();

        ControlResponse r = send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("진행 중").contains("다시 보내지 마세요");
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.ACTIVATING);
        // 진행 중에도 상태 조회는 바로 된다
        assertThat(account(send(h, ControlRequest.status()).status(), KIS).state()).isEqualTo("ACTIVATING");

        release.countDown();
        await(() -> guard.state(KIS.id()) == GuardState.OPEN);
        await(() -> commandAudit.contains("ACTIVATE KIS-US-1 OPEN"));
        assertThat(account(h.status(), KIS).lastResult()).contains("활성화 OPEN");
    }

    @Test
    void 기다리는_연결이_상한에_닿으면_기다리지_않고_바로_진행_중으로_답한다() throws Exception {
        limits = new EngineCommandHandler.Limits(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(5), 2, 0);
        CountDownLatch release = new CountDownLatch(1);
        steps = (account, step) -> {
            awaitQuietly(release);
            return Optional.empty();
        };
        EngineCommandHandler h = handler();

        long start = System.nanoTime();
        ControlResponse r = send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(r.message()).contains("진행 중");
        release.countDown();
        await(() -> guard.state(KIS.id()) == GuardState.OPEN);
    }

    @Test
    void 동시에_2차_확인을_진행하는_활성화가_상한을_넘으면_BUSY다() throws Exception {
        limits = new EngineCommandHandler.Limits(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(12), 1, 6);
        // 상한 반복 횟수 해시로 검증이 수백 밀리초 걸리게 해 두 요청이 반드시 겹치게 한다
        String slow = OperatorPasswordHash.create(PASSWORD.toCharArray(), new SecureRandom(),
                OperatorPasswordHash.MAX_ITERATIONS);
        EngineCommandHandler h = handler(auth(OperatorPasswordHash.parse(slow)));
        CountDownLatch go = new CountDownLatch(1);

        CompletableFuture<ControlResponse> kis = CompletableFuture.supplyAsync(() -> {
            awaitQuietly(go);
            return h.apply(ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        });
        CompletableFuture<ControlResponse> kiwoom = CompletableFuture.supplyAsync(() -> {
            awaitQuietly(go);
            return h.apply(ControlRequest.activate("KIWOOM-KR-1", "KIWOOM-KR-1", PASSWORD));
        });
        go.countDown();

        List<String> codes = List.of(kis.get(30, TimeUnit.SECONDS).code(), kiwoom.get(30, TimeUnit.SECONDS).code());
        assertThat(codes).containsExactlyInAnyOrder(ControlCodes.OK, ControlCodes.BUSY);
        assertThat(List.of(guard.state(KIS.id()), guard.state(KIWOOM.id())))
                .containsExactlyInAnyOrder(GuardState.OPEN, GuardState.OFF);
    }

    // ---- C3: 절대 기한 ----

    @Test
    void 연결을_받은_뒤_활성화_시작_기한이_지난_요청은_확인도_하지_않고_STALE이다() {
        EngineCommandHandler h = handler();
        nanos.set(Duration.ofSeconds(11).toNanos());

        ControlResponse r = h.handle(ControlRequest.activate("KIS-US-1", "KIS-US-1", "wrong-password"), 0);

        assertThat(r.code()).isEqualTo(ControlCodes.STALE);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        // 확인을 하지 않았으니 실패 횟수도 늘지 않는다
        for (int i = 0; i < OperatorAuthenticator.MAX_FAILURES; i++) {
            h.handle(ControlRequest.activate("KIS-US-1", "KIS-US-1", "wrong-password"), 0);
        }
        assertThat(h.status().lockedUntil()).isNull();
        assertThat(commandAudit).contains("ACTIVATE KIS-US-1 STALE");
    }

    @Test
    void 확인이_활성화_시작_기한_뒤에_끝나면_활성화를_시작하지_않는다() {
        // 시계를 읽을 때마다 6초씩 간다: 확인 전 6초(통과), 확인 뒤 12초(기한 10초 넘김)
        ticker = () -> nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        EngineCommandHandler h = handler();

        ControlResponse r = h.handle(ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD), 0);

        assertThat(r.code()).isEqualTo(ControlCodes.STALE);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(guardAudit).isEmpty();
        assertThat(performed).isEmpty();
    }

    @Test
    void 단계를_시작하기_전에_활성화_기한이_지나면_출발_상태로_돌아가고_STALE이다() throws Exception {
        limits = new EngineCommandHandler.Limits(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(12), 2, 6);
        // 확인 전 4초, 확인 뒤 8초(통과), 첫 단계 직전 12초(기한 10초 넘김)
        ticker = () -> nanos.addAndGet(Duration.ofSeconds(4).toNanos());
        EngineCommandHandler h = handler();

        h.handle(ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD), 0);

        await(() -> commandAudit.contains("ACTIVATE KIS-US-1 STALE"));
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(performed).isEmpty();
        assertThat(guardAudit).extracting(GuardTransition::to).containsExactly(GuardState.ACTIVATING, GuardState.OFF);
        assertThat(account(h.status(), KIS).lastResult()).contains("STALE");
    }

    @Test
    void 단계가_활성화_기한_뒤에_끝나면_통과로_치지_않고_OPEN이_되지_않는다() throws Exception {
        steps = (account, step) -> {
            performed.add(step);
            if (step == ActivationStep.values()[ActivationStep.values().length - 1]) {
                nanos.addAndGet(Duration.ofSeconds(61).toNanos());
            }
            return Optional.empty();
        };
        EngineCommandHandler h = handler();

        ControlResponse r = send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(r.code()).isEqualTo(ControlCodes.STALE);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(guardAudit).extracting(GuardTransition::to).doesNotContain(GuardState.OPEN);
        assertThat(commandAudit).contains("ACTIVATE KIS-US-1 STALE");
    }

    // ---- C2: 계좌별 PANIC ----

    @Test
    void 전체_PANIC은_계좌끼리_기다리지_않고_끝나지_않은_계좌는_진행_중으로_답한다() throws Exception {
        limits = new EngineCommandHandler.Limits(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMillis(300), 2, 6);
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        send(h, ControlRequest.activate("KIWOOM-KR-1", "KIWOOM-KR-1", PASSWORD));
        CountDownLatch release = new CountDownLatch(1);
        PanicLiquidator done = liquidator;
        liquidator = account -> {
            if (account.equals(KIS)) {
                awaitQuietly(release);
            }
            return done.liquidate(account);
        };

        ControlResponse r = send(h, ControlRequest.panicAll(ControlRequest.PANIC_ALL_PHRASE, PASSWORD));

        assertThat(r.ok()).isTrue();
        assertThat(r.steps()).filteredOn(s -> s.account().equals("KIS-US-1")).extracting(PanicStepResult::outcome)
                .contains(PanicSteps.PENDING);
        assertThat(r.steps()).filteredOn(s -> s.account().equals("KIWOOM-KR-1") && s.step().equals("GUARD_OFF"))
                .extracting(PanicStepResult::outcome).containsExactly(PanicSteps.DONE);
        assertThat(r.message()).contains("진행 중");
        assertThat(guard.state(KIWOOM.id())).isEqualTo(GuardState.OFF);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.PROTECTIVE);

        release.countDown();
        await(() -> guard.state(KIS.id()) == GuardState.OFF);
        await(() -> account(h.status(), KIS).lastResult().contains("PANIC_DONE"));
    }

    @Test
    void 같은_계좌의_청산이_돌고_있으면_새로_시작하지_않는다() throws Exception {
        limits = new EngineCommandHandler.Limits(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMillis(200), 2, 6);
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        CountDownLatch release = new CountDownLatch(1);
        PanicLiquidator done = liquidator;
        liquidator = account -> {
            awaitQuietly(release);
            return done.liquidate(account);
        };

        ControlResponse first = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));
        ControlResponse second = send(h, ControlRequest.panicAccount("KIS-US-1", "KIS-US-1", PASSWORD));

        assertThat(List.of(first, second)).allSatisfy(r -> assertThat(r.steps()).extracting(PanicStepResult::outcome)
                .contains(PanicSteps.PENDING));
        release.countDown();
        await(() -> guard.state(KIS.id()) == GuardState.OFF);
        Thread.sleep(100);
        assertThat(liquidated).containsExactly("KIS-US-1");
    }

    // ---- C7: 닫기 ----

    @Test
    void 닫으면_활성화_중인_계좌를_출발_상태로_내리고_새_활성화를_받지_않는다() throws Exception {
        limits = new EngineCommandHandler.Limits(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofMillis(100), 2, 6);
        CountDownLatch release = new CountDownLatch(1);
        steps = (account, step) -> {
            awaitQuietly(release);
            return Optional.empty();
        };
        EngineCommandHandler h = handler();
        send(h, ControlRequest.activate("KIS-US-1", "KIS-US-1", PASSWORD));
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.ACTIVATING);

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        long start = System.nanoTime();
        h.close();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(EngineCommandHandler.CLOSE_WAIT);
        assertThat(guard.state(KIS.id())).isEqualTo(GuardState.OFF);
        assertThat(send(h, ControlRequest.activate("KIWOOM-KR-1", "KIWOOM-KR-1", PASSWORD)).code())
                .isEqualTo(ControlCodes.INVALID_STATE);
        // 내리기와 상태 조회는 닫는 중에도 된다
        assertThat(send(h, ControlRequest.deactivate("KIWOOM-KR-1", null)).ok()).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("시험 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("중단됨");
        }
    }
}
