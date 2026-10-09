package com.autotrade.app.engine.command;

import com.autotrade.control.ControlCodes;
import com.autotrade.control.ControlCommand;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.EngineStatus;
import com.autotrade.control.PanicStepResult;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.risk.guard.ActivationStep;
import com.autotrade.risk.guard.GuardClosedException;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.RealTradingGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 실전 제어 명령 처리(ADR-006 2.3). 명령 채널이 받은 요청을 Guard 전이로 바꾼다. ACTIVATE와 PANIC은 한 번에 하나씩
 * 들어오지만 STATUS와 DEACTIVATE는 다른 스레드에서 동시에 들어올 수 있다(ControlServer). Guard와 검증기는 스레드 안전하다.
 *
 * <ul>
 *   <li>STATUS: 계좌별 Guard 스냅샷. 확인 없음.</li>
 *   <li>ACTIVATE: 2차 확인(비밀번호 + 계좌 별칭 입력) 후 활성화 단계를 순서대로 수행. 한 단계라도 실패하면 출발 상태로
 *       돌아간다(RSK-01). 계좌번호가 없는 계좌, 이미 ACTIVATING·OPEN인 계좌는 거절.</li>
 *   <li>DEACTIVATE: 내려가는 전이라 확인 없이 즉시(OPEN → PROTECTIVE, ACTIVATING → 출발 상태, D-023 공통 원칙 2).</li>
 *   <li>PANIC_ACCOUNT·PANIC_ALL: RSK-13 순서. 보호 모드 고정(2차 확인 전에, 확인이 실패·잠김이어도 한다) → 2차 확인 →
 *       증권사 단계({@link PanicLiquidator}) → 증권사 단계가 모두 끝났을 때만 Guard OFF. 끝나지 않으면 보호 모드에 남겨
 *       손절이 계속되게 한다.</li>
 * </ul>
 * 응답과 감사 로그에는 비밀번호·확인 문구를 넣지 않는다.
 */
public final class EngineCommandHandler implements Function<ControlRequest, ControlResponse> {

    private final RealTradingGuard guard;
    private final OperatorAuthenticator authenticator;
    private final Predicate<String> accountNumberConfigured;
    private final ActivationStepRunner steps;
    private final PanicLiquidator liquidator;
    private final CommandAuditLog audit;

    public EngineCommandHandler(
            RealTradingGuard guard, OperatorAuthenticator authenticator, Predicate<String> accountNumberConfigured,
            ActivationStepRunner steps, PanicLiquidator liquidator, CommandAuditLog audit) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.accountNumberConfigured = Objects.requireNonNull(accountNumberConfigured, "accountNumberConfigured");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.liquidator = Objects.requireNonNull(liquidator, "liquidator");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public ControlResponse apply(ControlRequest request) {
        if (request.command() == ControlCommand.STATUS) {
            return ControlResponse.accepted("", status(), List.of());
        }
        ControlResponse response;
        try {
            response = switch (request.command()) {
                case ACTIVATE -> activate(request);
                case DEACTIVATE -> deactivate(request);
                case PANIC_ACCOUNT -> panicAccount(request);
                case PANIC_ALL -> panicAll(request);
                case STATUS -> throw new IllegalStateException();
            };
        } finally {
            // 검증을 쓰는 명령(ACTIVATE·PANIC)만 버린다. 대기열을 거치지 않는 DEACTIVATE가 동시에 진행 중인
            // ACTIVATE의 검증을 지우지 않게 한다
            if (request.command() != ControlCommand.DEACTIVATE) {
                authenticator.discardVerification();
            }
        }
        try {
            audit.record(request.command(), auditTarget(request), response.code());
        } catch (RuntimeException e) {
            // 감사 로그 실패가 이미 일어난 전이의 결과를 가리면 안 된다. Guard 감사 로그는 전이마다 따로 남는다
            response = new ControlResponse(response.ok(), response.code(),
                    response.message() + " (명령 감사 로그 기록 실패)", response.status(), response.steps());
        }
        return response;
    }

    public EngineStatus status() {
        List<EngineStatus.AccountStatus> accounts = new ArrayList<>();
        for (AccountRef ref : guard.accounts()) {
            Set<ActivationStep> done = guard.completedSteps(ref.id());
            accounts.add(new EngineStatus.AccountStatus(
                    ref.id().value(), ref.broker().name(), ref.market().name(), guard.state(ref.id()).name(),
                    done.stream().map(Enum::name).toList(), accountNumberConfigured.test(ref.id().value())));
        }
        return new EngineStatus(authenticator.passwordConfigured(),
                authenticator.lockedUntil().map(Object::toString).orElse(null), accounts);
    }

    // ---- 명령 ----

    private ControlResponse activate(ControlRequest request) {
        Optional<AccountRef> found = find(request.account());
        if (found.isEmpty()) {
            return reject(ControlCodes.UNKNOWN_ACCOUNT, "설정에 없는 실전 계좌입니다");
        }
        AccountRef account = found.get();
        GuardState state = guard.state(account.id());
        if (state != GuardState.OFF && state != GuardState.PROTECTIVE) {
            return reject(ControlCodes.INVALID_STATE, account.id() + "는 " + state + " 상태라 활성화를 시작할 수 없습니다");
        }
        if (!accountNumberConfigured.test(account.id().value())) {
            return reject(ControlCodes.INVALID_STATE, account.id() + "는 계좌번호가 설정되지 않아 활성화할 수 없습니다(secrets.json)");
        }
        Optional<ControlResponse> denied = confirm(request, account.id().value());
        if (denied.isPresent()) {
            return denied.get();
        }
        try {
            guard.beginActivation(authenticator.confirmationFor(account.id()));
        } catch (GuardClosedException | IllegalArgumentException | IllegalStateException e) {
            return reject(ControlCodes.INVALID_STATE, "활성화를 시작하지 못했습니다: " + e.getMessage());
        }
        try {
            return runActivationSteps(account);
        } finally {
            // 어떤 예외·오류로 빠져나가도 ACTIVATING에 남기지 않는다(RSK-01: 실패하면 출발 상태로)
            if (guard.state(account.id()) == GuardState.ACTIVATING) {
                guard.failActivation(account.id(), "활성화 처리 중 예기치 않은 중단");
            }
        }
    }

    private ControlResponse runActivationSteps(AccountRef account) {
        for (ActivationStep step : ActivationStep.values()) {
            Optional<String> failure;
            try {
                failure = steps.perform(account, step);
            } catch (RuntimeException e) {
                failure = Optional.of(step + " 단계 오류(" + e.getClass().getSimpleName() + ")");
            }
            if (failure.isPresent()) {
                guard.failActivation(account.id(), failure.get());
                return ControlResponse.accepted(
                        "활성화 실패: " + failure.get() + ". " + guard.state(account.id()) + " 상태로 돌아갔습니다",
                        status(), List.of());
            }
            try {
                guard.completeStep(account.id(), step);
            } catch (GuardClosedException e) {
                // 단계 수행 중 다른 경로(내리기, 서킷 등)가 Guard를 내렸다. 내려간 상태를 그대로 둔다
                return ControlResponse.accepted("활성화가 중단됐습니다. 현재 " + guard.state(account.id()) + " 상태입니다",
                        status(), List.of());
            }
        }
        return ControlResponse.accepted(account.id() + " 실전 매매가 열렸습니다(OPEN)", status(), List.of());
    }

    private ControlResponse deactivate(ControlRequest request) {
        Optional<AccountRef> found = find(request.account());
        if (found.isEmpty()) {
            return reject(ControlCodes.UNKNOWN_ACCOUNT, "설정에 없는 실전 계좌입니다");
        }
        AccountRef account = found.get();
        GuardState before = guard.state(account.id());
        guard.deactivate(account.id(), OperatorAuthenticator.OPERATOR, reason(request.reason(), "운영자 내리기"));
        GuardState after = guard.state(account.id());
        String message = before == after
                ? account.id() + "는 이미 " + after + " 상태입니다"
                : account.id() + " " + before + " → " + after;
        return ControlResponse.accepted(message, status(), List.of());
    }

    private ControlResponse panicAccount(ControlRequest request) {
        Optional<AccountRef> found = find(request.account());
        if (found.isEmpty()) {
            return reject(ControlCodes.UNKNOWN_ACCOUNT, "설정에 없는 실전 계좌입니다");
        }
        AccountRef account = found.get();
        // 1단계(보호 모드 고정)는 내려가는 전이라 2차 확인 전에 한다. 잠금·비밀번호 미설정·확인 실패여도 신규 주문은 막힌다
        List<PanicStepResult> results = new ArrayList<>();
        results.add(lock(account));
        Optional<ControlResponse> denied = confirm(request, account.id().value());
        if (denied.isPresent()) {
            results.addAll(brokerStepsSkipped(account));
            return lockOnly(denied.get(), results);
        }
        results.addAll(afterLock(account));
        return ControlResponse.accepted(summary(results), status(), results);
    }

    private ControlResponse panicAll(ControlRequest request) {
        // 1단계(보호 모드 고정)를 모든 계좌에 먼저 해서 신규 주문을 한꺼번에 막은 뒤 2차 확인과 계좌별 증권사 단계를 한다
        List<PanicStepResult> results = new ArrayList<>();
        for (AccountRef account : guard.accounts()) {
            results.add(lock(account));
        }
        Optional<ControlResponse> denied = confirm(request, ControlRequest.PANIC_ALL_PHRASE);
        if (denied.isPresent()) {
            for (AccountRef account : guard.accounts()) {
                results.addAll(brokerStepsSkipped(account));
            }
            return lockOnly(denied.get(), results);
        }
        for (AccountRef account : guard.accounts()) {
            results.addAll(afterLock(account));
        }
        return ControlResponse.accepted(summary(results), status(), results);
    }

    /** 2차 확인을 통과하지 못한 PANIC. 거절 코드는 그대로 두고 보호 모드 고정 결과를 함께 돌려준다. */
    private ControlResponse lockOnly(ControlResponse denied, List<PanicStepResult> results) {
        return new ControlResponse(false, denied.code(),
                denied.message() + ". 보호 모드 고정(신규 주문 차단)만 했고 청산은 하지 않았습니다", status(), results);
    }

    private List<PanicStepResult> brokerStepsSkipped(AccountRef account) {
        List<PanicStepResult> results = new ArrayList<>();
        for (String step : PanicSteps.BROKER_STEPS) {
            results.add(PanicSteps.result(account, step, PanicSteps.SKIPPED, "2차 확인을 통과하지 않아 하지 않았습니다"));
        }
        results.add(PanicSteps.result(account, PanicSteps.GUARD_OFF, PanicSteps.SKIPPED,
                guard.state(account.id()) == GuardState.OFF
                        ? "이미 OFF입니다"
                        : "보호 모드를 유지합니다(손절·익절과 서버 측 손절 계속)"));
        return results;
    }

    // ---- PANIC 단계 ----

    /** 1단계: 보호 모드 고정(신규 주문 즉시 차단). 내려가는 전이만 쓴다. */
    private PanicStepResult lock(AccountRef account) {
        GuardState before = guard.state(account.id());
        switch (before) {
            case OPEN -> guard.deactivate(account.id(), OperatorAuthenticator.OPERATOR, "PANIC SELL 보호 모드 고정");
            case ACTIVATING -> guard.failActivation(account.id(), "PANIC SELL로 활성화 중단");
            case PROTECTIVE, OFF -> {
                return PanicSteps.result(account, PanicSteps.PROTECTIVE_LOCK, PanicSteps.SKIPPED,
                        "이미 " + before + " 상태입니다(신규 주문 차단됨)");
            }
        }
        return PanicSteps.result(account, PanicSteps.PROTECTIVE_LOCK, PanicSteps.DONE,
                before + " → " + guard.state(account.id()));
    }

    /** 2~6단계. OFF 계좌는 주문 세션이 없어 증권사 단계를 할 수 없다. */
    private List<PanicStepResult> afterLock(AccountRef account) {
        List<PanicStepResult> results = new ArrayList<>();
        if (guard.state(account.id()) == GuardState.OFF) {
            for (String step : PanicSteps.BROKER_STEPS) {
                results.add(PanicSteps.result(account, step, PanicSteps.SKIPPED,
                        "Guard OFF라 이 계좌로는 주문이 나가지 않습니다"));
            }
            results.add(PanicSteps.result(account, PanicSteps.GUARD_OFF, PanicSteps.SKIPPED, "이미 OFF입니다"));
            return results;
        }
        List<PanicStepResult> broker;
        try {
            broker = liquidator.liquidate(account);
        } catch (RuntimeException e) {
            broker = List.of(PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.FAILED,
                    "증권사 단계 오류(" + e.getClass().getSimpleName() + ")"));
        }
        results.addAll(broker);
        if (allBrokerStepsDone(broker)) {
            guard.shutdown(account.id(), OperatorAuthenticator.OPERATOR, "PANIC SELL 완료");
            results.add(PanicSteps.result(account, PanicSteps.GUARD_OFF, PanicSteps.DONE, "PROTECTIVE → OFF"));
        } else {
            results.add(keepProtective(account, broker));
        }
        return results;
    }

    /**
     * 청산이 끝나지 않은 계좌의 6단계 결과. 무엇이 남아 보호하는지는 실제 단계 결과로 쓴다: 서버 측 손절을 이미 취소했다면
     * 엔진 손절 감시만 남은 것이라 긴급으로 표시한다(ADR-006 2.5).
     */
    private static PanicStepResult keepProtective(AccountRef account, List<PanicStepResult> broker) {
        boolean stopsCancelled = broker.stream().anyMatch(r -> PanicSteps.CANCEL_SERVER_STOPS.equals(r.step())
                && PanicSteps.DONE.equals(r.outcome()));
        if (stopsCancelled) {
            return new PanicStepResult(account.id().value(), PanicSteps.GUARD_OFF, PanicSteps.SKIPPED,
                    "매도가 확인되지 않아 보호 모드를 유지합니다. 서버 측 손절이 취소된 상태라 엔진 손절 감시만 동작합니다."
                            + " 증권사 화면에서 보유와 손절 주문을 바로 확인하세요", true);
        }
        return PanicSteps.result(account, PanicSteps.GUARD_OFF, PanicSteps.SKIPPED,
                "매도가 확인되지 않아 보호 모드를 유지합니다(손절·익절과 서버 측 손절 계속)");
    }

    private static boolean allBrokerStepsDone(List<PanicStepResult> broker) {
        List<String> done = broker.stream().filter(r -> PanicSteps.DONE.equals(r.outcome()))
                .map(PanicStepResult::step).toList();
        return done.equals(PanicSteps.BROKER_STEPS) && broker.size() == PanicSteps.BROKER_STEPS.size();
    }

    private static String summary(List<PanicStepResult> results) {
        long off = results.stream().filter(r -> PanicSteps.GUARD_OFF.equals(r.step())
                && PanicSteps.DONE.equals(r.outcome())).count();
        long urgent = results.stream().filter(PanicStepResult::urgent).count();
        return "PANIC SELL 처리: 신규 주문 차단 완료, 청산 완료 계좌 " + off + "개"
                + (urgent > 0 ? ". 긴급: 서버 측 손절 없이 남은 계좌 " + urgent + "개" : "");
    }

    // ---- 공통 ----

    private Optional<ControlResponse> confirm(ControlRequest request, String expectedPhrase) {
        char[] password = request.password() == null ? new char[0] : request.password().toCharArray();
        OperatorAuthenticator.Result result = authenticator.verify(password, request.typedConfirmation(), expectedPhrase);
        return switch (result) {
            case OK -> Optional.empty();
            case NOT_CONFIGURED -> Optional.of(reject(ControlCodes.PASSWORD_NOT_CONFIGURED,
                    "운영자 비밀번호가 설정되지 않았습니다. 실전 엔진 설정 도구로 해시를 만들어 secrets.json에 넣어 주세요"));
            case LOCKED -> Optional.of(reject(ControlCodes.LOCKED,
                    "2차 확인을 " + OperatorAuthenticator.MAX_FAILURES + "번 연속 실패해 잠겼습니다. 잠시 뒤 다시 시도하세요"));
            case FAILED -> Optional.of(reject(ControlCodes.CONFIRMATION_FAILED, "비밀번호 또는 확인 문구가 맞지 않습니다"));
        };
    }

    private Optional<AccountRef> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return guard.accounts().stream().filter(a -> a.id().value().equals(id)).findFirst();
    }

    /** 감사 로그 대상. 설정에 있는 별칭만 그대로 쓰고, 모르는 값은 남기지 않는다(입력 실수로 비밀값이 들어올 수 있음). */
    private String auditTarget(ControlRequest request) {
        if (request.command() == ControlCommand.PANIC_ALL) {
            return "ALL";
        }
        return find(request.account()).map(a -> a.id().value()).orElse("(설정에 없는 계좌)");
    }

    private static String reason(String given, String fallback) {
        if (given == null || given.isBlank()) {
            return fallback;
        }
        String clean = given.codePoints().map(c -> Character.isISOControl(c) ? ' ' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (clean.isEmpty()) {
            return fallback;
        }
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    private ControlResponse reject(String code, String message) {
        return ControlResponse.rejected(code, message, status());
    }
}
