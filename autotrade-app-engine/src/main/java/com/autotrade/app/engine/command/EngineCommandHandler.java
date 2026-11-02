package com.autotrade.app.engine.command;

import com.autotrade.control.ControlCodes;
import com.autotrade.control.ControlCommand;
import com.autotrade.control.ControlHandler;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.ControlServer;
import com.autotrade.control.EngineStatus;
import com.autotrade.control.PanicStepResult;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.risk.guard.ActivationStep;
import com.autotrade.risk.guard.GuardClosedException;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.GuardTransition;
import com.autotrade.risk.guard.RaiseNotAppliedException;
import com.autotrade.risk.guard.RealTradingGuard;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 실전 제어 명령 처리(ADR-006 2.3, D-142). 명령 채널이 받은 요청을 Guard 전이로 바꾼다. 명령 채널 서버는 연결마다 다른 스레드에서
 * 이 처리기를 부르므로 모든 명령이 동시에 들어올 수 있다. Guard와 검증기는 스레드 안전하다.
 *
 * <ul>
 *   <li>STATUS: 계좌별 Guard 스냅샷과 마지막 활성화·PANIC 결과. 확인 없음. 다른 명령을 기다리지 않는다.</li>
 *   <li>ACTIVATE: 2차 확인(비밀번호 + 계좌 별칭 입력) 후 활성화 단계를 그 계좌의 작업 스레드에서 순서대로 수행한다(C1). 응답 기한
 *       ({@link Limits#respondWithin()}) 안에 끝나면 결과를, 아니면 "진행 중"을 돌려주고 단계는 계속 돈다. 명령마다 절대 기한이 있다
 *       (C3, 연결을 받은 시각부터): ① 2차 확인 뒤 {@code beginActivation} 직전에 {@link Limits#beginWithin()}, ② 각 단계 직전과
 *       ③ 각 단계 통과({@code completeStep}, 마지막은 OPEN 전이) 직전에 {@link Limits#activationWithin()}. 넘기면 출발 상태로 돌리고
 *       {@link ControlCodes#STALE}. 한 단계라도 실패하면 출발 상태로 돌아간다(RSK-01). 계좌번호가 없는 계좌, 이미 ACTIVATING·OPEN인
 *       계좌는 거절. 2차 확인을 동시에 진행하는 활성화 요청은 {@link Limits#maxPendingActivations()}개까지(넘치면 BUSY).</li>
 *   <li>DEACTIVATE: 내려가는 전이라 확인 없이 즉시(OPEN → PROTECTIVE, ACTIVATING → 출발 상태, D-023 공통 원칙 2).</li>
 *   <li>PANIC_ACCOUNT·PANIC_ALL: RSK-13 순서. 보호 모드 고정(2차 확인 전에, 확인이 실패·잠김이어도 한다, 즉시) → 2차 확인 →
 *       계좌별 증권사 단계({@link PanicLiquidator})를 그 계좌의 작업 스레드에서 → 증권사 단계가 모두 끝났을 때만 Guard OFF. 끝나지
 *       않으면 보호 모드에 남겨 손절이 계속되게 한다. 계좌끼리 서로 기다리지 않는다(C2). 응답 기한 안에 끝나지 않은 계좌는 "진행 중"
 *       ({@link PanicSteps#PENDING})으로 답하고 청산은 계속한다. 같은 계좌의 청산이 이미 돌고 있으면 새로 시작하지 않고 그 결과를 쓴다.</li>
 * </ul>
 *
 * <p>PANIC은 활성화보다 앞선다(architect·risk-ops PR #240 R1). PANIC을 받으면 그 계좌의 PANIC 세대 값을 1 올린 뒤 보호 모드를
 * 고정한다. 활성화는 받을 때 세대 값을 적어 두고 {@code beginActivation} 직전, 각 단계 직전, 각 단계 통과 직전에 다시 본다. 그 사이
 * PANIC이 들어왔으면 활성화를 멈추고 출발 상태로 돌린다. 그래서 2차 확인 중이던 활성화가 PANIC 뒤에 계좌를 OPEN으로 올리지 않는다.
 * 청산 작업도 시작할 때 계좌 작업 스레드 안에서 보호 모드를 다시 고정한다.
 *
 * <p>응답 기한까지 기다리는 연결은 {@link Limits#maxWaiting()}개까지만 둔다. 넘치면 기다리지 않고 바로 "진행 중"으로 답해, 새로
 * 들어오는 내리기·PANIC이 읽힐 연결 자리를 남긴다(C5, {@link ControlServer#RESERVED_FOR_NEW}).
 *
 * <p>응답과 감사 로그에는 비밀번호·확인 문구를 넣지 않는다. 활성화·청산이 끝나면 결과를 감사 로그에 한 번 더 남긴다
 * ({@link #OUTCOME_OPEN} 등, 실전 활성화 외부 알림 C11).
 */
public final class EngineCommandHandler implements ControlHandler, AutoCloseable {

    /** 활성화가 OPEN까지 갔다(감사 로그 결과 코드). */
    public static final String OUTCOME_OPEN = "OPEN";
    /** 활성화가 단계 실패·중단으로 출발 상태로 돌아갔다. */
    public static final String OUTCOME_ACTIVATION_FAILED = "ACTIVATION_FAILED";
    /** PANIC 청산이 끝나 Guard OFF. */
    public static final String OUTCOME_PANIC_DONE = "PANIC_DONE";
    /** PANIC 청산이 끝나지 않아 보호 모드 유지. */
    public static final String OUTCOME_PANIC_INCOMPLETE = "PANIC_INCOMPLETE";
    /** 닫을 때 계좌별 작업(활성화·청산)을 기다리는 상한(C7). */
    public static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private static final int MAX_RESULT_LENGTH = 300;
    private static final long NO_PANIC = Long.MIN_VALUE;
    private static final Logger LOG = System.getLogger(EngineCommandHandler.class.getName());

    /**
     * 기한과 상한(D-142, ADR-006 2.2 (나) 엔진 고정 기한). 기한은 모두 연결을 받은 시각부터 잰다.
     *
     * @param beginWithin ① 2차 확인을 마치고 활성화를 시작할 수 있는 기한. 요청 읽기 상한(5초)이 이 안에 든다. 실험실 대기 상한(15초)보다
     *     짧아야 사람이 "응답 없음"을 본 뒤에 활성화가 시작되지 않는다
     * @param activationWithin ②③ 활성화 단계 전체 기한. 넘기면 OPEN이 되지 않고 출발 상태로 돌아간다 [가정: Guard 2차 확인 유효 시간 60초와 같게]
     * @param respondWithin 응답 기한. 이 안에 끝나지 않은 활성화·청산은 계속 돌고 "진행 중"으로 답한다. 실험실 대기 상한보다 짧다
     * @param maxPendingActivations 2차 확인을 동시에 진행하는 활성화 요청 상한(넘치면 BUSY)
     * @param maxWaiting 응답 기한까지 기다리는 연결 상한(넘치면 바로 "진행 중")
     */
    public record Limits(Duration beginWithin, Duration activationWithin, Duration respondWithin, int maxPendingActivations,
            int maxWaiting) {

        public static final Limits DEFAULT = new Limits(Duration.ofSeconds(10), Duration.ofSeconds(60),
                Duration.ofSeconds(12), 2, ControlServer.MAX_CONNECTIONS - ControlServer.RESERVED_FOR_NEW);

        public Limits {
            Objects.requireNonNull(beginWithin, "beginWithin");
            Objects.requireNonNull(activationWithin, "activationWithin");
            Objects.requireNonNull(respondWithin, "respondWithin");
            if (beginWithin.isNegative() || activationWithin.compareTo(beginWithin) < 0 || respondWithin.isNegative()
                    || maxPendingActivations < 1 || maxWaiting < 0) {
                throw new IllegalArgumentException("명령 기한·상한이 맞지 않습니다");
            }
        }
    }

    private final RealTradingGuard guard;
    private final OperatorAuthenticator authenticator;
    private final Predicate<String> accountNumberConfigured;
    private final ActivationStepRunner steps;
    private final PanicLiquidator liquidator;
    private final CommandAuditLog audit;
    private final String configWarning;
    private final Clock clock;
    private final Limits limits;
    private final LongSupplier ticker;
    /** 계좌마다 작업 스레드 하나: 한 계좌의 활성화 단계와 청산은 차례로, 계좌끼리는 따로 돈다. */
    private final Map<AccountId, ExecutorService> lanes;
    private final Semaphore pendingActivations;
    private final Semaphore waiters;
    private final Map<AccountId, CompletableFuture<List<PanicStepResult>>> liquidations = new ConcurrentHashMap<>();
    private final Map<String, String> lastResults = new ConcurrentHashMap<>();
    /** 계좌별 PANIC 세대(받을 때마다 1 올림). 활성화가 자기가 받은 뒤 PANIC이 들어왔는지 본다. */
    private final Map<AccountId, AtomicLong> panicGenerations;
    private volatile boolean closing;
    /** 두 번째(늦게 뜬) 엔진이라 올리는 명령을 받지 않는 이유. null이면 정상(PR #240 QA 발견 1). */
    private volatile String silenced;
    /** 계좌별 마지막 PANIC을 받은 시각(단조 시계). 그보다 먼저 받은 활성화는 열지 않는다(QA Q2). */
    private final Map<AccountId, AtomicLong> panicReceived;

    public EngineCommandHandler(
            RealTradingGuard guard, OperatorAuthenticator authenticator, Predicate<String> accountNumberConfigured,
            ActivationStepRunner steps, PanicLiquidator liquidator, CommandAuditLog audit, Clock clock) {
        this(guard, authenticator, accountNumberConfigured, steps, liquidator, audit, "", clock, Limits.DEFAULT,
                System::nanoTime);
    }

    /**
     * @param configWarning 기동 때 설정 로딩 경고({@code LoadedConfig.warning()}). 상태 스냅샷에 그대로 실어 운영 화면이 보인다.
     *     표시용일 뿐 Guard 전이·명령 검증에는 쓰지 않는다
     * @param clock 마지막 결과의 시각 표시용
     * @param ticker 기한을 재는 단조 시계(나노초, 명령 채널 서버의 받은 시각과 같은 기준). 시험이 바꿔 끼운다
     */
    public EngineCommandHandler(
            RealTradingGuard guard, OperatorAuthenticator authenticator, Predicate<String> accountNumberConfigured,
            ActivationStepRunner steps, PanicLiquidator liquidator, CommandAuditLog audit, String configWarning,
            Clock clock, Limits limits, LongSupplier ticker) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.accountNumberConfigured = Objects.requireNonNull(accountNumberConfigured, "accountNumberConfigured");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.liquidator = Objects.requireNonNull(liquidator, "liquidator");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configWarning = Objects.requireNonNull(configWarning, "configWarning");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.pendingActivations = new Semaphore(limits.maxPendingActivations());
        this.waiters = new Semaphore(limits.maxWaiting());
        Map<AccountId, ExecutorService> map = new LinkedHashMap<>();
        Map<AccountId, AtomicLong> generations = new LinkedHashMap<>();
        Map<AccountId, AtomicLong> received = new LinkedHashMap<>();
        for (AccountRef ref : guard.accounts()) {
            map.put(ref.id(), Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("engine-account-" + ref.id().value()).daemon(true).factory()));
            generations.put(ref.id(), new AtomicLong());
            received.put(ref.id(), new AtomicLong(NO_PANIC));
        }
        this.lanes = Map.copyOf(map);
        this.panicGenerations = Map.copyOf(generations);
        this.panicReceived = Map.copyOf(received);
    }

    /** 지금 시각을 받은 시각으로 보고 처리한다(시험·내부 호출용). */
    public ControlResponse apply(ControlRequest request) {
        return handle(request, ticker.getAsLong());
    }

    @Override
    public ControlResponse handle(ControlRequest request, long receivedAtNanos) {
        if (request.command() == ControlCommand.STATUS) {
            return ControlResponse.accepted("", status(), List.of());
        }
        ControlResponse response = switch (request.command()) {
            case ACTIVATE -> activate(request, receivedAtNanos);
            case DEACTIVATE -> deactivate(request);
            case PANIC_ACCOUNT -> panicAccount(request, receivedAtNanos);
            case PANIC_ALL -> panicAll(request, receivedAtNanos);
            case STATUS -> throw new IllegalStateException();
        };
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
                    done.stream().map(Enum::name).toList(), accountNumberConfigured.test(ref.id().value()),
                    lastResults.getOrDefault(ref.id().value(), "")));
        }
        return new EngineStatus(authenticator.passwordConfigured(),
                authenticator.lockedUntil().map(Object::toString).orElse(null), accounts, configWarning);
    }

    // ---- 활성화(C1·C3) ----

    private ControlResponse activate(ControlRequest request, long receivedAt) {
        Optional<AccountRef> found = find(request.account());
        if (found.isEmpty()) {
            return reject(ControlCodes.UNKNOWN_ACCOUNT, "설정에 없는 실전 계좌입니다");
        }
        AccountRef account = found.get();
        // 상태를 보기 전에 PANIC 세대를 적는다. 이 뒤에 들어온 PANIC은 이 활성화를 멈춘다
        long generation = panicGeneration(account);
        Optional<ControlResponse> refused = refuseRaising(account);
        if (refused.isPresent()) {
            return refused.get();
        }
        GuardState state = guard.state(account.id());
        if (state != GuardState.OFF && state != GuardState.PROTECTIVE) {
            return reject(ControlCodes.INVALID_STATE, account.id() + "는 " + state + " 상태라 활성화를 시작할 수 없습니다");
        }
        if (!accountNumberConfigured.test(account.id().value())) {
            return reject(ControlCodes.INVALID_STATE, account.id() + "는 계좌번호가 설정되지 않아 활성화할 수 없습니다(secrets.json)");
        }
        if (!pendingActivations.tryAcquire()) {
            return reject(ControlCodes.BUSY, "실전 엔진이 다른 활성화 요청을 확인하는 중입니다. 상태를 새로 읽은 뒤 다시 보내세요");
        }
        try {
            if (expired(receivedAt, limits.beginWithin())) {
                return stale(account, "활성화 요청이 너무 오래 기다려 확인하지 않았습니다");
            }
            OperatorAuthenticator.Verification verification = authenticator.verify(password(request),
                    request.typedConfirmation(), account.id().value());
            Optional<ControlResponse> denied = denied(verification);
            if (denied.isPresent()) {
                return denied.get();
            }
            // ① 2차 확인(해시 검증) 뒤, 활성화 시작 직전
            if (expired(receivedAt, limits.beginWithin())) {
                return stale(account, "2차 확인이 활성화 기한 뒤에 끝나 활성화를 시작하지 않았습니다");
            }
            // 확인하는 사이 닫기·응답 멈춤·청산이 시작됐을 수 있다(QA Q5·발견 1)
            refused = refuseRaising(account);
            if (refused.isPresent()) {
                recordOutcome(ControlCommand.ACTIVATE, account, OUTCOME_ACTIVATION_FAILED);
                return refused.get();
            }
            if (panicked(account, generation, receivedAt)) {
                return panicStopped(account, "활성화를 시작하지 않았습니다");
            }
            try {
                guard.beginActivation(authenticator.confirmationFor(verification, account.id()));
            } catch (RaiseNotAppliedException e) {
                return reject(ControlCodes.INVALID_STATE, "활성화를 시작하지 못했습니다: " + notApplied(e));
            } catch (GuardClosedException | IllegalArgumentException | IllegalStateException e) {
                return reject(ControlCodes.INVALID_STATE, "활성화를 시작하지 못했습니다: " + e.getMessage());
            }
        } finally {
            pendingActivations.release();
        }
        CompletableFuture<ControlResponse> done = new CompletableFuture<>();
        try {
            lanes.get(account.id()).execute(() -> {
                try {
                    done.complete(runActivation(account, receivedAt, generation));
                } catch (Throwable t) {
                    // Error도 기다리는 연결에 알린다(응답 기한까지 묶이지 않게). 계좌는 runActivation이 이미 출발 상태로 내렸다
                    done.completeExceptionally(t);
                    throw t;
                }
            });
        } catch (RejectedExecutionException e) {
            guard.failActivation(account.id(), "실전 엔진 종료 중");
            return reject(ControlCodes.INVALID_STATE, "실전 엔진이 종료 중이라 활성화를 중단했습니다");
        }
        return await(done, receivedAt, () -> ControlResponse.accepted(account.id()
                + " 활성화 진행 중(ACTIVATING). 단계와 결과는 상태표에서 확인하세요(이 요청을 다시 보내지 마세요)",
                status(), List.of()));
    }

    /** 계좌 작업 스레드에서 단계를 돌리고, 어떻게 끝나든 ACTIVATING에 남기지 않으며, 결과를 기록한다. */
    private ControlResponse runActivation(AccountRef account, long receivedAt, long generation) {
        ControlResponse response = null;
        try {
            response = runActivationSteps(account, receivedAt, generation);
        } catch (RuntimeException e) {
            response = ControlResponse.accepted("활성화 처리 중 예기치 않은 중단(" + e.getClass().getSimpleName() + ")",
                    null, List.of());
        } finally {
            if (response == null) {
                // Error: 출발 상태로 내리고 기록한 뒤 그대로 던진다
                finishActivation(account, ControlResponse.accepted("활성화 처리 중 예기치 않은 중단", null, List.of()));
            }
        }
        return finishActivation(account, response);
    }

    private ControlResponse finishActivation(AccountRef account, ControlResponse response) {
        // 어떤 예외·오류로 빠져나가도 ACTIVATING에 남기지 않는다(RSK-01: 실패하면 출발 상태로)
        if (guard.state(account.id()) == GuardState.ACTIVATING) {
            guard.failActivation(account.id(), "활성화 처리 중 예기치 않은 중단");
        }
        boolean opened = response.ok() && guard.state(account.id()) == GuardState.OPEN;
        String outcome = opened ? OUTCOME_OPEN : ControlCodes.STALE.equals(response.code()) ? ControlCodes.STALE
                : OUTCOME_ACTIVATION_FAILED;
        remember(account, "활성화 " + outcome + ": " + response.message());
        recordOutcome(ControlCommand.ACTIVATE, account, outcome);
        return new ControlResponse(response.ok(), response.code(), response.message(), status(), response.steps());
    }

    private ControlResponse runActivationSteps(AccountRef account, long receivedAt, long generation) {
        for (ActivationStep step : ActivationStep.values()) {
            // ② 단계 직전
            if (expired(receivedAt, limits.activationWithin())) {
                return activationStale(account, step);
            }
            if (panicked(account, generation, receivedAt)) {
                return activationPanicked(account, step);
            }
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
                        null, List.of());
            }
            // ③ 단계 통과 직전(마지막 단계면 OPEN 전이 직전)
            if (expired(receivedAt, limits.activationWithin())) {
                return activationStale(account, step);
            }
            if (panicked(account, generation, receivedAt)) {
                return activationPanicked(account, step);
            }
            try {
                guard.completeStep(account.id(), step);
            } catch (RaiseNotAppliedException e) {
                // OPEN 올림 감사를 기록하지 못했거나(상한 초과·저장소 차단) 기록하는 사이 내림이 먼저였다(ADR-015 2.5). 사유를 구별해 보인다
                if (guard.state(account.id()) == GuardState.ACTIVATING) {
                    guard.failActivation(account.id(), notApplied(e));
                }
                return ControlResponse.accepted("활성화 실패: " + notApplied(e) + ". " + guard.state(account.id())
                        + " 상태입니다. 2차 확인을 다시 받아야 합니다", null, List.of());
            } catch (GuardClosedException e) {
                // 단계 수행 중 다른 경로(내리기, PANIC, 서킷 등)가 Guard를 내렸다. 내려간 상태를 그대로 둔다
                return ControlResponse.accepted("활성화가 중단됐습니다. 현재 " + guard.state(account.id()) + " 상태입니다",
                        null, List.of());
            }
        }
        return ControlResponse.accepted(account.id() + " 실전 매매가 열렸습니다(OPEN)", null, List.of());
    }

    /**
     * 적용하지 않은 올림의 사유 문구. Guard 문구가 "감사 기록 실패·상한 초과"와 "내림이 먼저"를 구별한다(ADR-015 2.5). 화면의 사유별
     * 표시는 묶음 H에서 {@link RaiseNotAppliedException#reason()}으로 나눈다.
     */
    static String notApplied(RaiseNotAppliedException e) {
        return e.getMessage();
    }

    private ControlResponse activationStale(AccountRef account, ActivationStep step) {
        String reason = "활성화 기한(" + limits.activationWithin().toSeconds() + "초) 초과(" + step + " 단계)";
        guard.failActivation(account.id(), reason);
        return ControlResponse.rejected(ControlCodes.STALE, reason + "로 " + guard.state(account.id())
                + " 상태로 돌아갔습니다. 상태를 확인하고 다시 시도하세요", null);
    }

    private ControlResponse activationPanicked(AccountRef account, ActivationStep step) {
        String reason = (silenced != null ? "응답을 멈춘 엔진이라" : "PANIC SELL이 들어와") + " 활성화 중단(" + step + " 단계)";
        if (guard.state(account.id()) == GuardState.ACTIVATING) {
            guard.failActivation(account.id(), reason);
        }
        return ControlResponse.accepted(reason + ". 현재 " + guard.state(account.id()) + " 상태입니다", null, List.of());
    }

    private ControlResponse panicStopped(AccountRef account, String what) {
        recordOutcome(ControlCommand.ACTIVATE, account, OUTCOME_ACTIVATION_FAILED);
        return reject(ControlCodes.INVALID_STATE, "이 요청을 받은 뒤 PANIC SELL이 들어와 " + what + ". 현재 "
                + guard.state(account.id()) + " 상태입니다");
    }

    private long panicGeneration(AccountRef account) {
        return panicGenerations.get(account.id()).get();
    }

    /**
     * 이 활성화를 받은 뒤 PANIC이 들어왔는지. 처리기에 들어온 뒤 처리된 PANIC은 세대 값으로, 처리기에 먼저 들어왔지만 이 요청보다
     * 늦게 받은 PANIC은 받은 시각으로 본다(요청 읽기 동안의 순서 뒤바뀜, QA S2).
     */
    private boolean panicked(AccountRef account, long generation, long receivedAt) {
        if (panicGeneration(account) != generation) {
            return true;
        }
        long panic = panicReceived.get(account.id()).get();
        return panic != NO_PANIC && panic - receivedAt >= 0;
    }

    /** 올리는 명령을 받지 않는 경우: 닫는 중, 응답을 멈춘 엔진, 그 계좌 청산이 도는 중. */
    private Optional<ControlResponse> refuseRaising(AccountRef account) {
        if (closing) {
            return Optional.of(reject(ControlCodes.INVALID_STATE, "실전 엔진이 종료 중이라 활성화를 받지 않습니다"));
        }
        if (silenced != null) {
            return Optional.of(reject(ControlCodes.INVALID_STATE, "이 실전 엔진은 응답을 멈춘 엔진이라 활성화를 받지 않습니다"));
        }
        CompletableFuture<List<PanicStepResult>> running = liquidations.get(account.id());
        if (running != null && !running.isDone()) {
            return Optional.of(reject(ControlCodes.INVALID_STATE,
                    account.id() + "는 PANIC 청산이 도는 중이라 활성화할 수 없습니다. 청산 결과를 확인한 뒤 다시 시도하세요"));
        }
        return Optional.empty();
    }

    private ControlResponse stale(AccountRef account, String why) {
        recordOutcome(ControlCommand.ACTIVATE, account, ControlCodes.STALE);
        return reject(ControlCodes.STALE, why + ". 상태를 확인하고 다시 시도하세요");
    }

    // ---- 내리기 ----

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

    // ---- PANIC(C2) ----

    private ControlResponse panicAccount(ControlRequest request, long receivedAt) {
        Optional<AccountRef> found = find(request.account());
        if (found.isEmpty()) {
            return reject(ControlCodes.UNKNOWN_ACCOUNT, "설정에 없는 실전 계좌입니다");
        }
        AccountRef account = found.get();
        // 1단계(보호 모드 고정)는 내려가는 전이라 2차 확인 전에 한다. 잠금·비밀번호 미설정·확인 실패여도 신규 주문은 막힌다
        List<PanicStepResult> results = new ArrayList<>();
        results.add(lock(account, receivedAt));
        Optional<ControlResponse> denied = denied(authenticator.verify(password(request), request.typedConfirmation(),
                account.id().value()));
        if (denied.isPresent()) {
            results.addAll(brokerStepsSkipped(account));
            return lockOnly(denied.get(), results);
        }
        Map<AccountRef, CompletableFuture<List<PanicStepResult>>> running = new LinkedHashMap<>();
        running.put(account, liquidation(account, ControlCommand.PANIC_ACCOUNT));
        results.addAll(awaitLiquidations(running, receivedAt));
        return ControlResponse.accepted(summary(results), status(), results);
    }

    private ControlResponse panicAll(ControlRequest request, long receivedAt) {
        // 1단계(보호 모드 고정)를 모든 계좌에 먼저 해서 신규 주문을 한꺼번에 막은 뒤 2차 확인과 계좌별 증권사 단계를 한다
        List<PanicStepResult> results = new ArrayList<>();
        for (AccountRef account : guard.accounts()) {
            results.add(lock(account, receivedAt));
        }
        Optional<ControlResponse> denied = denied(authenticator.verify(password(request), request.typedConfirmation(),
                ControlRequest.PANIC_ALL_PHRASE));
        if (denied.isPresent()) {
            for (AccountRef account : guard.accounts()) {
                results.addAll(brokerStepsSkipped(account));
            }
            return lockOnly(denied.get(), results);
        }
        // 계좌마다 따로 시작하고(서로 기다리지 않음) 같은 응답 기한까지 함께 기다린다
        Map<AccountRef, CompletableFuture<List<PanicStepResult>>> running = new LinkedHashMap<>();
        for (AccountRef account : guard.accounts()) {
            running.put(account, liquidation(account, ControlCommand.PANIC_ALL));
        }
        results.addAll(awaitLiquidations(running, receivedAt));
        return ControlResponse.accepted(summary(results), status(), results);
    }

    /** 계좌의 2~6단계를 시작한다. 이미 돌고 있으면 그 작업을 쓴다. OFF 계좌는 작업 스레드를 거치지 않고 바로 끝낸다. */
    private CompletableFuture<List<PanicStepResult>> liquidation(AccountRef account, ControlCommand command) {
        if (guard.state(account.id()) == GuardState.OFF) {
            return CompletableFuture.completedFuture(offAccountSkipped(account));
        }
        return liquidations.compute(account.id(), (id, existing) ->
                existing != null && !existing.isDone() ? existing : startLiquidation(account, command));
    }

    private CompletableFuture<List<PanicStepResult>> startLiquidation(AccountRef account, ControlCommand command) {
        CompletableFuture<List<PanicStepResult>> future = new CompletableFuture<>();
        try {
            lanes.get(account.id()).execute(() -> {
                List<PanicStepResult> results = null;
                try {
                    results = afterLock(account);
                } catch (RuntimeException e) {
                    results = List.of(PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.FAILED,
                            "청산 처리 오류(" + e.getClass().getSimpleName() + ")"), keepProtective(account, List.of()));
                } finally {
                    if (results == null) {
                        // Error: 끝나지 않은 청산으로 적어 다음 PANIC이 이 작업을 영영 기다리지 않게 한 뒤 그대로 던진다
                        results = List.of(PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.FAILED,
                                "청산 처리 중 예기치 않은 중단"), keepProtective(account, List.of()));
                    }
                    try {
                        finishLiquidation(account, command, results);
                    } finally {
                        future.complete(results);
                        liquidations.remove(account.id(), future);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            future.complete(List.of(PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.FAILED,
                    "실전 엔진이 종료 중이라 청산을 시작하지 못했습니다"), keepProtective(account, List.of())));
        }
        return future;
    }

    private void finishLiquidation(AccountRef account, ControlCommand command, List<PanicStepResult> results) {
        boolean off = results.stream().anyMatch(r -> PanicSteps.GUARD_OFF.equals(r.step()) && PanicSteps.DONE.equals(r.outcome()));
        boolean urgent = results.stream().anyMatch(PanicStepResult::urgent);
        String outcome = off ? OUTCOME_PANIC_DONE : OUTCOME_PANIC_INCOMPLETE;
        remember(account, "PANIC " + outcome + (off ? ": 청산 완료, Guard OFF"
                : urgent ? ": 긴급, 서버 측 손절 없이 보유가 남았을 수 있음. 증권사 화면을 바로 확인하세요"
                        : ": 청산이 끝나지 않아 보호 모드 유지"));
        recordOutcome(command, account, outcome);
    }

    /** 응답 기한까지 계좌별 청산을 기다린다. 기한 안에 끝나지 않은 계좌는 "진행 중"으로 적는다(청산은 계속). */
    private List<PanicStepResult> awaitLiquidations(Map<AccountRef, CompletableFuture<List<PanicStepResult>>> running,
            long receivedAt) {
        List<PanicStepResult> results = new ArrayList<>();
        boolean acquired = waiters.tryAcquire();
        boolean waiting = acquired;
        try {
            for (Map.Entry<AccountRef, CompletableFuture<List<PanicStepResult>>> e : running.entrySet()) {
                List<PanicStepResult> done = null;
                if (waiting) {
                    try {
                        done = e.getValue().get(remaining(receivedAt), TimeUnit.NANOSECONDS);
                    } catch (TimeoutException ignored) {
                        // 아래에서 진행 중으로 적는다
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        waiting = false;
                    } catch (ExecutionException ex) {
                        done = List.of(PanicSteps.result(e.getKey(), PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.FAILED,
                                "청산 처리 오류"), keepProtective(e.getKey(), List.of()));
                    }
                } else {
                    done = e.getValue().getNow(null);
                }
                results.addAll(done != null ? done : pending(e.getKey()));
            }
        } finally {
            if (acquired) {
                waiters.release();
            }
        }
        return results;
    }

    private static List<PanicStepResult> pending(AccountRef account) {
        return List.of(
                PanicSteps.result(account, PanicSteps.CANCEL_OPEN_ORDERS, PanicSteps.PENDING,
                        "청산 진행 중(응답 기한 안에 끝나지 않음). 결과는 상태표의 마지막 결과에서 확인하세요. 다시 보내지 않아도 됩니다"),
                PanicSteps.result(account, PanicSteps.GUARD_OFF, PanicSteps.SKIPPED,
                        "청산이 끝나기 전에는 보호 모드를 유지합니다(손절·익절과 서버 측 손절 계속)"));
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
    private PanicStepResult lock(AccountRef account, long receivedAt) {
        // 받은 시각과 세대를 먼저 적는다: 이 뒤로는 진행 중인 활성화가 다음 확인 지점에서 멈춘다(그 사이 OPEN이 됐으면 아래에서 내린다)
        panicReceived.get(account.id()).accumulateAndGet(receivedAt,
                (prev, next) -> prev == NO_PANIC || next - prev > 0 ? next : prev);
        panicGenerations.get(account.id()).incrementAndGet();
        return relock(account);
    }

    /**
     * 내려가는 전이로 보호 모드를 고정한다(세대는 올리지 않음). 상태를 읽고 전이를 고르면 그 사이 활성화가 OPEN으로 올릴 수 있어
     * (risk-ops PR #240 R-6), Guard 계좌 잠금 안에서 전이를 고르는 {@code deactivate} 하나로 내린다(OPEN → 보호 모드,
     * ACTIVATING → 출발 상태). 결과 문구는 전이 뒤 실제 상태로 쓴다.
     */
    private PanicStepResult relock(AccountRef account) {
        GuardState before = guard.state(account.id());
        guard.deactivate(account.id(), OperatorAuthenticator.OPERATOR, "PANIC SELL 보호 모드 고정(활성화 중이면 중단)");
        GuardState after = guard.state(account.id());
        if (after == before) {
            return PanicSteps.result(account, PanicSteps.PROTECTIVE_LOCK, PanicSteps.SKIPPED,
                    "이미 " + after + " 상태입니다(신규 주문 차단됨)");
        }
        return PanicSteps.result(account, PanicSteps.PROTECTIVE_LOCK, PanicSteps.DONE, before + " → " + after);
    }

    private static List<PanicStepResult> offAccountSkipped(AccountRef account) {
        List<PanicStepResult> results = new ArrayList<>();
        for (String step : PanicSteps.BROKER_STEPS) {
            results.add(PanicSteps.result(account, step, PanicSteps.SKIPPED, "Guard OFF라 이 계좌로는 주문이 나가지 않습니다"));
        }
        results.add(PanicSteps.result(account, PanicSteps.GUARD_OFF, PanicSteps.SKIPPED, "이미 OFF입니다"));
        return results;
    }

    /** 2~6단계(계좌 작업 스레드에서). OFF 계좌는 주문 세션이 없어 증권사 단계를 할 수 없다. */
    private List<PanicStepResult> afterLock(AccountRef account) {
        // 같은 계좌 작업 스레드에서 앞서 돈 활성화가 1단계 뒤에 OPEN으로 올렸을 수 있다. 증권사 단계 전에 다시 고정한다(R1)
        relock(account);
        if (guard.state(account.id()) == GuardState.OFF) {
            return offAccountSkipped(account);
        }
        List<PanicStepResult> results = new ArrayList<>();
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
            // 청산 중에 다른 연결의 활성화가 시작됐을 수 있다. 결과를 적기 전에 다시 고정하고, 문구는 실제 상태로 쓴다
            relock(account);
            results.add(guard.state(account.id()) == GuardState.OFF
                    ? PanicSteps.result(account, PanicSteps.GUARD_OFF, PanicSteps.SKIPPED,
                            "매도가 확인되지 않았고 계좌는 OFF입니다(신규 주문 없음). 증권사 화면에서 보유를 확인하세요")
                    : keepProtective(account, broker));
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
        long pending = results.stream().filter(r -> PanicSteps.PENDING.equals(r.outcome())).count();
        return "PANIC SELL 처리: 신규 주문 차단 완료, 청산 완료 계좌 " + off + "개"
                + (pending > 0 ? ", 청산 진행 중 계좌 " + pending + "개(상태표에서 결과 확인)" : "")
                + (urgent > 0 ? ". 긴급: 서버 측 손절 없이 남은 계좌 " + urgent + "개" : "");
    }

    // ---- 공통 ----

    /** 응답 기한까지 기다린다. 기다리는 자리가 없거나 기한이 지나면 {@code progress}로 답한다. */
    private ControlResponse await(CompletableFuture<ControlResponse> done, long receivedAt,
            java.util.function.Supplier<ControlResponse> progress) {
        if (!waiters.tryAcquire()) {
            ControlResponse now = done.getNow(null);
            return now != null ? now : progress.get();
        }
        try {
            return done.get(remaining(receivedAt), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return progress.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return progress.get();
        } catch (ExecutionException e) {
            return reject(ControlCodes.INTERNAL, "실전 엔진 내부 오류로 결과를 알 수 없습니다. 상태를 확인하세요");
        } finally {
            waiters.release();
        }
    }

    private long remaining(long receivedAt) {
        return Math.max(0, receivedAt + limits.respondWithin().toNanos() - ticker.getAsLong());
    }

    private boolean expired(long receivedAt, Duration limit) {
        return ticker.getAsLong() - receivedAt > limit.toNanos();
    }

    private static char[] password(ControlRequest request) {
        return request.password() == null ? new char[0] : request.password().toCharArray();
    }

    private Optional<ControlResponse> denied(OperatorAuthenticator.Verification verification) {
        return switch (verification.result()) {
            case OK -> Optional.empty();
            case NOT_CONFIGURED -> Optional.of(reject(ControlCodes.PASSWORD_NOT_CONFIGURED,
                    "운영자 비밀번호가 설정되지 않았습니다. 실전 엔진 설정 도구로 해시를 만들어 secrets.json에 넣어 주세요"));
            case LOCKED -> Optional.of(reject(ControlCodes.LOCKED,
                    "2차 확인을 " + OperatorAuthenticator.MAX_FAILURES + "번 연속 실패해 잠겼습니다. 잠시 뒤 다시 시도하세요"));
            case FAILED -> Optional.of(reject(ControlCodes.CONFIRMATION_FAILED, "비밀번호 또는 확인 문구가 맞지 않습니다"));
        };
    }

    private void remember(AccountRef account, String text) {
        String line = clock.instant() + " " + text;
        lastResults.put(account.id().value(), line.length() > MAX_RESULT_LENGTH ? line.substring(0, MAX_RESULT_LENGTH) : line);
    }

    private void recordOutcome(ControlCommand command, AccountRef account, String outcome) {
        try {
            audit.record(command, account.id().value(), outcome);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "명령 결과 감사 기록 실패: {0} {1} {2}", command, account.id(), outcome);
        }
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

    /**
     * 이 엔진이 같은 설정 폴더의 두 번째(늦게 뜬) 엔진으로 판단됐다(D-142 C6, PR #240 QA 발견 1). 명령 채널이 응답을 멈추는 것과 함께
     * 부른다. 그 뒤로는 활성화를 받지 않고, 모든 계좌의 PANIC 세대를 올려 2차 확인·단계 중인 활성화가 다음 확인 지점에서 멈추게 하며,
     * 모든 계좌를 OFF로 내린다. 응답을 멈춘 엔진은 명령으로 멈출 수 없으므로 보호 모드(손절·익절 매도)에도 두지 않는다: 같은 계좌를
     * 먼저 뜬 엔진이 보호하고, 두 엔진이 같은 보유에 매도를 겹쳐 내지 않게 한다(QA Q1). 되돌릴 수 없다.
     */
    public void silence(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (silenced != null) {
            return;
        }
        silenced = reason;
        for (AccountRef account : guard.accounts()) {
            panicGenerations.get(account.id()).incrementAndGet();
            guard.shutdown(account.id(), GuardTransition.SYSTEM, "응답을 멈춘 실전 엔진: " + reason);
        }
    }

    /**
     * 엔진 종료(C7). 새 활성화를 받지 않고, 활성화 중인 계좌는 출발 상태로 내린 뒤(재기동하면 어차피 모두 OFF), 계좌 작업 스레드의
     * 진행 중 작업(청산 등)이 끝나기를 최대 {@link #CLOSE_WAIT} 기다린다. 넘으면 끊는다.
     */
    @Override
    public void close() {
        closing = true;
        for (AccountRef account : guard.accounts()) {
            if (guard.state(account.id()) == GuardState.ACTIVATING) {
                guard.failActivation(account.id(), "실전 엔진 종료");
            }
        }
        lanes.values().forEach(ExecutorService::shutdown);
        long deadline = ticker.getAsLong() + CLOSE_WAIT.toNanos();
        try {
            for (ExecutorService lane : lanes.values()) {
                long left = deadline - ticker.getAsLong();
                if (left <= 0 || !lane.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                    LOG.log(Level.WARNING, "실전 엔진 종료: {0} 안에 끝나지 않은 계좌 작업을 끊습니다", CLOSE_WAIT);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lanes.values().forEach(ExecutorService::shutdownNow);
    }
}
