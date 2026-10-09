package com.autotrade.app.engine.command;

import com.autotrade.domain.account.AccountId;
import com.autotrade.risk.guard.SecondConfirmation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * 2차 확인 검증기(RSK-01: 로컬 비밀번호 재입력 + 계좌 표시명 확인, ADR-006 2.4).
 *
 * <p>{@link SecondConfirmation}을 만드는 곳은 이 클래스뿐이다(ArchUnit, ADR-002 3장 되돌릴 조건). 비밀번호와 확인 문구가
 * 모두 맞아야 통과하며, 어느 쪽이 틀렸는지는 알려 주지 않는다. {@link #MAX_FAILURES}번 연속 실패하면 {@link #LOCKOUT} 동안
 * 모든 2차 확인을 거절한다. 잠금은 메모리에만 있어 엔진을 다시 띄우면 풀린다(재기동 시 모든 계좌 OFF이므로 위험이 늘지 않음).
 *
 * <p>명령 채널은 한 번에 명령 하나만 처리하므로 이 클래스는 스레드 안전하지 않아도 된다(ControlServer 단일 스레드).
 */
public final class OperatorAuthenticator {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCKOUT = Duration.ofMinutes(15);
    /** 감사 로그의 주체. 로컬 PC 단일 운영자라 사람 이름 대신 고정값을 쓴다. */
    public static final String OPERATOR = "operator";

    private final Optional<OperatorPasswordHash> hash;
    private final Clock clock;
    private int failures;
    private Instant lockedUntil;
    /** 방금 통과한 검증. {@link #confirmationFor}가 한 번 쓰고 지운다. */
    private boolean verified;

    public OperatorAuthenticator(Optional<OperatorPasswordHash> hash, Clock clock) {
        this.hash = hash;
        this.clock = clock;
    }

    public boolean passwordConfigured() {
        return hash.isPresent();
    }

    /** 잠겨 있으면 풀리는 시각. */
    public Optional<Instant> lockedUntil() {
        Instant now = clock.instant();
        return lockedUntil != null && now.isBefore(lockedUntil) ? Optional.of(lockedUntil) : Optional.empty();
    }

    /**
     * 비밀번호와 확인 문구를 검사한다.
     *
     * @param password 평문. 검사 후 이 메서드가 0으로 지운다
     * @param typed 사람이 입력한 확인 문구
     * @param expected 맞아야 하는 문구(계좌 별칭 또는 전체 PANIC 문구)
     */
    public Result verify(char[] password, String typed, String expected) {
        verified = false;
        try {
            if (hash.isEmpty()) {
                return Result.NOT_CONFIGURED;
            }
            if (lockedUntil().isPresent()) {
                return Result.LOCKED;
            }
            boolean phraseOk = typed != null && typed.strip().equals(expected);
            boolean passwordOk = hash.get().matches(password);
            if (phraseOk && passwordOk) {
                failures = 0;
                lockedUntil = null;
                verified = true;
                return Result.OK;
            }
            failures++;
            if (failures >= MAX_FAILURES) {
                failures = 0;
                lockedUntil = clock.instant().plus(LOCKOUT);
                return Result.LOCKED;
            }
            return Result.FAILED;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /**
     * 검증을 통과한 직후 그 계좌의 2차 확인을 한 번 만든다. 유효 시간은 Guard가 본다(60초).
     *
     * @throws IllegalStateException 직전 {@link #verify}가 통과하지 않았거나 이미 확인을 만들었을 때
     */
    SecondConfirmation confirmationFor(AccountId account) {
        if (!verified) {
            throw new IllegalStateException("2차 확인 검증을 통과하지 않았습니다");
        }
        verified = false;
        return new SecondConfirmation(account, OPERATOR, clock.instant());
    }

    public enum Result { OK, FAILED, LOCKED, NOT_CONFIGURED }
}
