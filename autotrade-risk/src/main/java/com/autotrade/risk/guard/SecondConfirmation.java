package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import java.time.Instant;
import java.util.Objects;

/**
 * 사람의 2차 확인 결과(RSK-01: 로컬 비밀번호 재입력 + 계좌 표시명 확인). Guard를 올리는 전이에만 쓴다.
 *
 * <p>이 값은 그 Guard가 내준 {@link Issuer}로만 만들 수 있다(ADR-002 3장 되돌릴 조건, D-142). 생성자는 공개하지 않고, 발급기는
 * {@link RealTradingGuard#confirmationIssuer()}가 Guard마다 한 번만 내준다. 실전 엔진은 기동할 때 그 발급기를 비밀번호 검증기
 * ({@code OperatorAuthenticator})에 바로 넘긴다. 그래서 같은 프로세스 안의 다른 코드는 검증 없이 확인을 만들 수 없고, 다른 Guard의
 * 발급기로 만든 확인은 이 Guard가 받지 않는다. 리플렉션은 실전 경로에서 금지돼 있다(D-086). Guard는 발급기 일치, 계좌 일치, 유효
 * 시간, 재사용 여부를 검사한다.
 *
 * <p>Jackson 같은 데이터 바인딩이 만들 수 있는 모양(레코드, 공개 생성자, 인자 없는 생성자)이 아니다.
 */
public final class SecondConfirmation {

    private final Issuer issuer;
    private final AccountId account;
    private final String operator;
    private final Instant confirmedAt;

    private SecondConfirmation(Issuer issuer, AccountId account, String operator, Instant confirmedAt) {
        this.issuer = issuer;
        this.account = Objects.requireNonNull(account, "account");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (operator.isBlank()) {
            throw new IllegalArgumentException("operator가 비었습니다");
        }
    }

    /** 확인한 계좌. 다른 계좌의 확인으로는 올릴 수 없다. */
    public AccountId account() {
        return account;
    }

    /** 확인한 사람(감사 로그용). 비밀값을 넣지 않는다. */
    public String operator() {
        return operator;
    }

    /** 확인 시각(주입된 Clock 기준). */
    public Instant confirmedAt() {
        return confirmedAt;
    }

    /** 이 확인을 만든 발급기. Guard가 자기 발급기인지 본다. */
    Issuer issuer() {
        return issuer;
    }

    @Override
    public String toString() {
        return "SecondConfirmation[account=" + account + ", operator=" + operator + ", confirmedAt=" + confirmedAt + "]";
    }

    /**
     * 2차 확인 발급기. {@link RealTradingGuard}만 만들고 Guard마다 한 번만 내준다. 받은 쪽(비밀번호 검증기)은 검증을 통과한 뒤에만
     * {@link #issue}를 부른다.
     */
    public static final class Issuer {

        /** 이 발급기를 내주는 Guard(표시용). 인자 없는 생성자를 두지 않아 데이터 바인딩이 만들 수 없게 한다(RealPathReflectionTest). */
        private final RealTradingGuard owner;

        Issuer(RealTradingGuard owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        /** 검증을 통과한 2차 확인을 만든다. */
        public SecondConfirmation issue(AccountId account, String operator, Instant confirmedAt) {
            return new SecondConfirmation(this, account, operator, confirmedAt);
        }

        @Override
        public String toString() {
            return "SecondConfirmation.Issuer";
        }
    }
}
