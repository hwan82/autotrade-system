package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import java.time.Instant;
import java.util.Objects;

/**
 * 사람의 2차 확인 결과(RSK-01: 로컬 비밀번호 재입력 + 계좌 표시명 확인). Guard를 올리는 전이에만 쓴다.
 *
 * <p>이 값은 비밀번호 검증이 끝난 뒤에만 만들어야 한다. 검증기(비밀번호 해시 비교, 실패 횟수 제한)는
 * UI 명령 채널 작업에서 구현한다. Guard는 계좌 일치, 유효 시간, 재사용 여부만 검사한다.
 *
 * @param account 확인한 계좌. 다른 계좌의 확인으로는 올릴 수 없다
 * @param operator 확인한 사람(감사 로그용). 비밀값을 넣지 않는다
 * @param confirmedAt 확인 시각(주입된 Clock 기준)
 */
public record SecondConfirmation(AccountId account, String operator, Instant confirmedAt) {

    public SecondConfirmation {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (operator.isBlank()) {
            throw new IllegalArgumentException("operator가 비었습니다");
        }
    }
}
