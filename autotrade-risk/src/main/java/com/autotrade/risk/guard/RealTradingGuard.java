package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.risk.RiskDecision;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 실전 주문 차단 장치(RSK-01, D-016, D-023). 실전 계좌마다 독립된 {@link GuardState}를 둔다.
 *
 * <p>불변식
 * <ul>
 *   <li>생성 직후 모든 계좌는 {@link GuardState#OFF}다. 생성자는 상태를 인자로 받지 않으므로 설정 파일·환경 변수·저장된
 *       상태가 기동 시 Guard를 열 방법이 없다(안전 불변식 1, CFG-02). 상태는 메모리에만 있고 저장하지 않는다.</li>
 *   <li>올라가는 전이는 그 계좌의 {@link SecondConfirmation}이 있어야 한다. 활성화는 {@link ActivationStep} 순서를 모두
 *       통과해야 OPEN이 된다. 자동으로 올라가는 경로는 없다.</li>
 *   <li>내려가는 전이(보호 모드, OFF)는 누구나 언제든 할 수 있고, 감사 로그 저장 실패로 막히지 않는다.</li>
 *   <li>신규 진입은 OPEN에서만 통과한다. 청산·손절은 OPEN·PROTECTIVE(보호 모드에서 출발한 활성화 중 포함)에서 통과한다.
 *       OFF에서는 어떤 주문도 통과하지 않는다(주문 세션 자체가 없다).</li>
 *   <li>계좌끼리 상태를 공유하지 않는다. 한 계좌의 전이는 다른 계좌에 영향이 없다(지침 4장 6항).</li>
 * </ul>
 *
 * <p>스레드 안전: 계좌별 잠금. {@link #check}는 잠금 없이 최신 상태를 읽는다.
 *
 * <p>가상 계좌는 이 Guard를 쓰지 않는다. 실험실 프로세스는 실전 주문 경로가 없고(ARC-03), Guard 판정은
 * {@link RiskDecision}으로 돌려주므로 실전 엔진의 검증 체인 맨 앞에 다른 규칙과 같은 방식으로 놓인다(D-023 공통 원칙 3).
 */
public final class RealTradingGuard {

    /** 거절 사유 로그·화면에 쓰는 규칙 ID. */
    public static final String RULE_ID = "REAL_TRADING_GUARD";

    /** 2차 확인 유효 시간. 확인 화면을 띄워 둔 채 한참 뒤에 쓰는 것을 막는다. [가정] 값은 Q-008에서 함께 정한다. */
    public static final Duration CONFIRMATION_TTL = Duration.ofSeconds(60);

    private static final Logger LOG = System.getLogger(RealTradingGuard.class.getName());

    private final Clock clock;
    private final GuardAuditLog audit;
    private final Map<AccountId, AccountGuard> accounts;

    /**
     * 실전 계좌 목록으로 Guard를 만든다. 모든 계좌는 OFF로 시작한다.
     *
     * @throws IllegalArgumentException 계좌 별칭이 겹칠 때
     */
    public RealTradingGuard(Collection<AccountRef> realAccounts, Clock clock, GuardAuditLog audit) {
        Objects.requireNonNull(realAccounts, "realAccounts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = Objects.requireNonNull(audit, "audit");
        Map<AccountId, AccountGuard> map = new TreeMap<>();
        for (AccountRef ref : realAccounts) {
            if (map.put(ref.id(), new AccountGuard(ref)) != null) {
                throw new IllegalArgumentException("실전 계좌 별칭이 겹칩니다: " + ref.id());
            }
        }
        this.accounts = Collections.unmodifiableMap(map);
    }

    /** 관리 대상 실전 계좌(별칭 순). */
    public List<AccountRef> accounts() {
        return accounts.values().stream().map(a -> a.ref).toList();
    }

    public GuardState state(AccountId account) {
        return guard(account).state;
    }

    /** 이 계좌에서 통과한 활성화 단계. ACTIVATING이 아니면 비어 있다. */
    public Set<ActivationStep> completedSteps(AccountId account) {
        AccountGuard g = guard(account);
        synchronized (g) {
            return g.state == GuardState.ACTIVATING ? EnumSet.copyOf(g.completed) : EnumSet.noneOf(ActivationStep.class);
        }
    }

    /**
     * 주문이 Guard를 통과하는지. 관리 대상이 아닌 계좌는 거절한다.
     * 다른 리스크 규칙과 같은 {@link RiskDecision}으로 돌려준다.
     */
    public RiskDecision check(OrderRequest order) {
        Objects.requireNonNull(order, "order");
        AccountGuard g = accounts.get(order.account());
        if (g == null) {
            return RiskDecision.reject(RULE_ID, "실전 계좌로 등록되지 않은 계좌입니다: " + order.account());
        }
        GuardState s = g.state;
        boolean protective = order.intent().isProtective();
        boolean allowed = switch (s) {
            case OPEN -> true;
            case PROTECTIVE -> protective;
            case ACTIVATING -> protective && g.activationOrigin == GuardState.PROTECTIVE;
            case OFF -> false;
        };
        if (allowed) {
            return RiskDecision.approve();
        }
        return RiskDecision.reject(RULE_ID, order.account() + " Guard " + s + ": "
                + (s == GuardState.OFF ? "실전 주문 차단" : "신규 진입 차단(청산·손절만 허용)"));
    }

    /** 취소 요청을 보내도 되는지. 취소는 보호 동작이라 주문 세션이 있는 상태(OFF가 아닌 상태)면 모두 허용한다. */
    public boolean mayCancel(AccountId account) {
        return guard(account).state != GuardState.OFF;
    }

    /**
     * 주문 세션 허가를 받는다. OPEN, PROTECTIVE, 또는 인증을 통과한 ACTIVATING에서만 발급한다.
     *
     * @throws GuardClosedException 그 밖의 상태(특히 OFF)
     */
    public OrderSessionPermit issueSessionPermit(AccountId account) {
        AccountGuard g = guard(account);
        synchronized (g) {
            boolean allowed = switch (g.state) {
                case OPEN, PROTECTIVE -> true;
                case ACTIVATING -> g.completed.contains(ActivationStep.AUTHENTICATED);
                case OFF -> false;
            };
            if (!allowed) {
                throw new GuardClosedException(account + " Guard " + g.state + ": 주문 세션을 만들 수 없습니다");
            }
            return new OrderSessionPermit(account, g.epoch, this);
        }
    }

    boolean isValid(OrderSessionPermit permit) {
        if (permit.issuer() != this) {
            return false;
        }
        AccountGuard g = accounts.get(permit.account());
        if (g == null) {
            return false;
        }
        synchronized (g) {
            return g.state != GuardState.OFF && g.epoch == permit.epoch();
        }
    }

    // ---- 올라가는 전이: 2차 확인 필요 ----

    /**
     * 활성화를 시작한다(OFF 또는 PROTECTIVE → ACTIVATING).
     *
     * @throws GuardClosedException 이미 ACTIVATING·OPEN일 때
     * @throws IllegalArgumentException 2차 확인이 다른 계좌 것이거나, 만료됐거나, 이미 쓴 것일 때
     */
    public void beginActivation(SecondConfirmation confirmation) {
        AccountGuard g = guard(confirmation.account());
        synchronized (g) {
            if (g.state != GuardState.OFF && g.state != GuardState.PROTECTIVE) {
                throw new GuardClosedException(g.ref.id() + " Guard " + g.state + "에서는 활성화를 시작할 수 없습니다");
            }
            accept(g, confirmation);
            // check()는 잠금 없이 state(volatile)만 읽으므로 출발 상태를 먼저 적고 state를 마지막에 바꾼다
            g.activationOrigin = g.state;
            g.operator = confirmation.operator();
            g.completed.clear();
            raise(g, GuardState.ACTIVATING, confirmation.operator(), "활성화 시작");
        }
    }

    /**
     * 활성화 단계 통과를 알린다. 단계는 {@link ActivationStep} 순서대로만 받는다. 마지막 단계면 OPEN이 된다.
     *
     * @throws GuardClosedException ACTIVATING이 아니거나 순서가 틀렸을 때(상태는 그대로)
     */
    public void completeStep(AccountId account, ActivationStep step) {
        Objects.requireNonNull(step, "step");
        AccountGuard g = guard(account);
        synchronized (g) {
            if (g.state != GuardState.ACTIVATING) {
                throw new GuardClosedException(account + " Guard " + g.state + ": 활성화 중이 아닙니다");
            }
            ActivationStep expected = ActivationStep.values()[g.completed.size()];
            if (step != expected) {
                throw new GuardClosedException(account + " 활성화 순서 위반: " + expected + " 차례에 " + step);
            }
            if (step == ActivationStep.FIRST_RECONCILIATION) {
                raise(g, GuardState.OPEN, g.operator, "활성화 단계 모두 통과");
                g.completed.clear();
            } else {
                g.completed.add(step);
            }
        }
    }

    /**
     * 재기동 뒤 보유가 남은 OFF 계좌를 보호 모드로 올린다(OFF → PROTECTIVE). 재기동 시 자동으로 보호 모드에 들어가지 않으므로
     * 사람이 2차 확인으로 고른다(RSK-01).
     *
     * @throws GuardClosedException OFF가 아닐 때
     */
    public void enterProtective(SecondConfirmation confirmation) {
        AccountGuard g = guard(confirmation.account());
        synchronized (g) {
            if (g.state != GuardState.OFF) {
                throw new GuardClosedException(g.ref.id() + " Guard " + g.state + ": OFF에서만 보호 모드로 올릴 수 있습니다");
            }
            accept(g, confirmation);
            raise(g, GuardState.PROTECTIVE, confirmation.operator(), "재기동 후 보유 보호");
        }
    }

    // ---- 내려가는 전이: 확인 없이 즉시 ----

    /** 활성화 중 단계 실패. 출발 상태(OFF 또는 PROTECTIVE)로 돌아간다. ACTIVATING이 아니면 아무것도 하지 않는다. */
    public void failActivation(AccountId account, String reason) {
        AccountGuard g = guard(account);
        synchronized (g) {
            if (g.state == GuardState.ACTIVATING) {
                lower(g, g.activationOrigin, GuardTransition.SYSTEM, "활성화 실패: " + reason);
            }
        }
    }

    /**
     * 실전 매매를 끈다. OPEN이면 보호 모드로 내려가 보유분 청산·손절은 계속한다(RSK-01 OFF 전환).
     * ACTIVATING이면 활성화를 중단하고 출발 상태로 돌아간다. OFF·PROTECTIVE면 아무것도 하지 않는다.
     */
    public void deactivate(AccountId account, String actor, String reason) {
        AccountGuard g = guard(account);
        synchronized (g) {
            switch (g.state) {
                case OPEN -> lower(g, GuardState.PROTECTIVE, actor, reason);
                case ACTIVATING -> lower(g, g.activationOrigin, actor, reason);
                case OFF, PROTECTIVE -> { }
            }
        }
    }

    /** 보호 모드 계좌의 보유·미체결이 0이 됐다. PROTECTIVE → OFF(주문 세션 종료). 다른 상태면 아무것도 하지 않는다. */
    public void holdingsCleared(AccountId account) {
        AccountGuard g = guard(account);
        synchronized (g) {
            if (g.state == GuardState.PROTECTIVE) {
                lower(g, GuardState.OFF, GuardTransition.SYSTEM, "보유·미체결 0");
            }
        }
    }

    /** 어떤 상태에서든 즉시 OFF(PANIC SELL 마지막 단계, 킬스위치). 이미 OFF면 아무것도 하지 않는다. */
    public void shutdown(AccountId account, String actor, String reason) {
        AccountGuard g = guard(account);
        synchronized (g) {
            if (g.state != GuardState.OFF) {
                lower(g, GuardState.OFF, actor, reason);
            }
        }
    }

    // ---- 내부 ----

    private AccountGuard guard(AccountId account) {
        Objects.requireNonNull(account, "account");
        AccountGuard g = accounts.get(account);
        if (g == null) {
            throw new IllegalArgumentException("실전 계좌로 등록되지 않은 계좌입니다: " + account);
        }
        return g;
    }

    private void accept(AccountGuard g, SecondConfirmation c) {
        Instant now = clock.instant();
        if (c.confirmedAt().isAfter(now) || c.confirmedAt().plus(CONFIRMATION_TTL).isBefore(now)) {
            throw new IllegalArgumentException(g.ref.id() + " 2차 확인이 유효 시간을 벗어났습니다");
        }
        if (g.lastConfirmation != null && !c.confirmedAt().isAfter(g.lastConfirmation)) {
            throw new IllegalArgumentException(g.ref.id() + " 이미 사용한 2차 확인입니다");
        }
        g.lastConfirmation = c.confirmedAt();
    }

    /** 올라가는 전이: 감사 기록이 성공해야 상태를 바꾼다. */
    private void raise(AccountGuard g, GuardState to, String actor, String reason) {
        audit.record(new GuardTransition(g.ref.id(), g.state, to, actor, reason, clock.instant()));
        g.state = to;
    }

    /** 내려가는 전이: 상태를 먼저 바꾸고, 감사 기록 실패는 경고만 남긴다. */
    private void lower(AccountGuard g, GuardState to, String actor, String reason) {
        GuardState from = g.state;
        if (to == GuardState.OFF) {
            g.epoch++;
        }
        g.state = to;
        g.completed.clear();
        try {
            audit.record(new GuardTransition(g.ref.id(), from, to, actor, reason, clock.instant()));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Guard 감사 기록 실패(전이는 적용됨): {0} {1}→{2}", g.ref.id(), from, to);
        }
    }

    private static final class AccountGuard {
        final AccountRef ref;
        volatile GuardState state = GuardState.OFF;
        GuardState activationOrigin = GuardState.OFF;
        final EnumSet<ActivationStep> completed = EnumSet.noneOf(ActivationStep.class);
        String operator = GuardTransition.SYSTEM;
        Instant lastConfirmation;
        long epoch;

        AccountGuard(AccountRef ref) {
            this.ref = ref;
        }
    }
}
