package com.autotrade.app.engine.command;

import com.autotrade.domain.account.AccountId;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 2차 확인 검증기(RSK-01: 로컬 비밀번호 재입력 + 계좌 표시명 확인, ADR-006 2.4).
 *
 * <p>{@link SecondConfirmation}을 만드는 곳은 이 클래스뿐이다. 만들 때는 Guard가 한 번만 내주는 발급기를 쓴다
 * ({@link RealTradingGuard#confirmationIssuer()}, ADR-002 3장 되돌릴 조건, D-142). 비밀번호와 확인 문구가 모두 맞아야 통과하며,
 * 어느 쪽이 틀렸는지는 알려 주지 않는다. {@link #MAX_FAILURES}번 연속 실패하면 {@link #LOCKOUT} 동안 모든 2차 확인을 거절하고,
 * 잠기는 순간 알림 처리기를 부른다(C10). 잠금은 메모리에만 있어 엔진을 다시 띄우면 풀린다(재기동 시 모든 계좌 OFF이므로 위험이
 * 늘지 않음).
 *
 * <p>검증 결과는 {@link Verification} 하나로 돌려준다. 그 계좌 별칭으로 통과한 검증만 {@link #confirmationFor}가 한 번 쓸 수 있다.
 * 다른 계좌의 2차 확인으로 쓸 수 없고, 다른 명령의 검증과 섞이지 않는다(명령마다 따로 들고 다닌다).
 *
 * <p>잠금 두 개를 나눠 쓴다(C4). 해시 계산은 검증 잠금({@link ReentrantLock}, 공정)을 쥔 채 한 번에 하나씩 해서 대입 시도를
 * 병렬로 늘릴 수 없게 하고, 실패 횟수·잠금 시각은 따로 짧게 잠근다. 그래서 검증이 도는 동안에도 상태 조회({@link #lockedUntil()})는
 * 막히지 않는다.
 */
public final class OperatorAuthenticator {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCKOUT = Duration.ofMinutes(15);
    /** 감사 로그의 주체. 로컬 PC 단일 운영자라 사람 이름 대신 고정값을 쓴다. */
    public static final String OPERATOR = "operator";

    private static final Logger LOG = System.getLogger(OperatorAuthenticator.class.getName());

    private final Optional<OperatorPasswordHash> hash;
    private final Clock clock;
    private final SecondConfirmation.Issuer issuer;
    private final Consumer<Instant> onLocked;
    /** 해시 검증은 한 번에 하나씩. 상태 잠금과 따로 둔다. */
    private final ReentrantLock attempts = new ReentrantLock(true);
    private final Object state = new Object();
    private int failures;
    private Instant lockedUntil;

    /**
     * @param guard 이 검증기가 확인을 만들어 줄 Guard. 생성자가 발급기를 가져간다(Guard마다 한 번)
     */
    public OperatorAuthenticator(Optional<OperatorPasswordHash> hash, Clock clock, RealTradingGuard guard) {
        this(hash, clock, guard, until -> { });
    }

    /**
     * @param onLocked 연속 실패로 잠기는 순간 풀리는 시각을 받는다(외부 긴급 알림, C10). 막지 않고 빨리 돌아와야 한다. 던져도 검증
     *     결과는 그대로다
     */
    public OperatorAuthenticator(Optional<OperatorPasswordHash> hash, Clock clock, RealTradingGuard guard,
            Consumer<Instant> onLocked) {
        this(hash, clock, Objects.requireNonNull(guard, "guard").confirmationIssuer(), onLocked);
    }

    /**
     * 이미 가져온 발급기로 만든다. 발급기는 Guard가 한 번만 내주고 그 호출은 이 클래스만 한다(ArchitectureTest). 한 발급기를 검증기
     * 여럿이 나눠 쓰는 것은 시험뿐이다.
     */
    public OperatorAuthenticator(Optional<OperatorPasswordHash> hash, Clock clock, SecondConfirmation.Issuer issuer,
            Consumer<Instant> onLocked) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onLocked = Objects.requireNonNull(onLocked, "onLocked");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    public boolean passwordConfigured() {
        return hash.isPresent();
    }

    /** 잠겨 있으면 풀리는 시각. 검증이 도는 중에도 기다리지 않는다. */
    public Optional<Instant> lockedUntil() {
        synchronized (state) {
            Instant now = clock.instant();
            return lockedUntil != null && now.isBefore(lockedUntil) ? Optional.of(lockedUntil) : Optional.empty();
        }
    }

    /**
     * 비밀번호와 확인 문구를 검사한다. 다른 검증이 도는 중이면 끝날 때까지 기다린다(해시 계산은 한 번에 하나).
     *
     * @param password 평문. 검사 후 이 메서드가 0으로 지운다
     * @param typed 사람이 입력한 확인 문구
     * @param expected 맞아야 하는 문구(계좌 별칭 또는 전체 PANIC 문구)
     */
    public Verification verify(char[] password, String typed, String expected) {
        Instant lockedNow = null;
        try {
            if (hash.isEmpty()) {
                return new Verification(this, Result.NOT_CONFIGURED, null);
            }
            attempts.lock();
            try {
                // 앞선 검증이 잠금을 걸었을 수 있어 검증 잠금을 쥔 뒤에 본다(기다리던 시도가 잠금을 건너뛰지 않게)
                if (lockedUntil().isPresent()) {
                    return new Verification(this, Result.LOCKED, null);
                }
                String want = normalize(expected);
                boolean phraseOk = typed != null && want != null && normalize(typed).equals(want);
                boolean passwordOk = hash.get().matches(password);
                synchronized (state) {
                    if (phraseOk && passwordOk) {
                        failures = 0;
                        lockedUntil = null;
                        return new Verification(this, Result.OK, want);
                    }
                    failures++;
                    if (failures >= MAX_FAILURES) {
                        failures = 0;
                        lockedUntil = clock.instant().plus(LOCKOUT);
                        lockedNow = lockedUntil;
                        return new Verification(this, Result.LOCKED, null);
                    }
                    return new Verification(this, Result.FAILED, null);
                }
            } finally {
                attempts.unlock();
            }
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (lockedNow != null) {
                notifyLocked(lockedNow);
            }
        }
    }

    private void notifyLocked(Instant until) {
        try {
            onLocked.accept(until);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "2차 확인 잠금 알림 처리 실패: {0}", e.getClass().getSimpleName());
        }
    }

    /**
     * 검증을 통과한 직후 그 계좌의 2차 확인을 한 번 만든다. 유효 시간은 Guard가 본다(60초).
     *
     * @throws IllegalStateException 이 검증기가 낸 검증이 아니거나, 통과하지 않았거나, 다른 문구로 통과했거나, 이미 썼을 때
     */
    SecondConfirmation confirmationFor(Verification verification, AccountId account) {
        Objects.requireNonNull(verification, "verification");
        if (verification.owner != this || verification.result != Result.OK
                || !verification.phrase.equals(normalize(account.value()))) {
            throw new IllegalStateException("이 계좌로 2차 확인 검증을 통과하지 않았습니다");
        }
        if (!verification.used.compareAndSet(false, true)) {
            throw new IllegalStateException("이 검증으로 이미 2차 확인을 만들었습니다");
        }
        return issuer.issue(account, OPERATOR, clock.instant());
    }

    private static String normalize(String text) {
        return text == null ? null : Normalizer.normalize(text.strip(), Normalizer.Form.NFC);
    }

    public enum Result { OK, FAILED, LOCKED, NOT_CONFIGURED }

    /**
     * 검증 한 번의 결과. 이 검증기만 만들고, 통과한 검증은 그 문구(계좌 별칭)로 2차 확인을 한 번만 만들 수 있다. 명령마다 따로
     * 들고 다니므로 동시에 도는 다른 명령의 검증과 섞이지 않는다.
     */
    public static final class Verification {

        private final OperatorAuthenticator owner;
        private final Result result;
        private final String phrase;
        private final AtomicBoolean used = new AtomicBoolean();

        private Verification(OperatorAuthenticator owner, Result result, String phrase) {
            this.owner = owner;
            this.result = result;
            this.phrase = phrase;
        }

        public Result result() {
            return result;
        }

        @Override
        public String toString() {
            return "Verification[" + result + "]";
        }
    }
}
