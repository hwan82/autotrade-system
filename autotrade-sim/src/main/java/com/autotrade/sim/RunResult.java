package com.autotrade.sim;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import java.util.Objects;

/**
 * Run 하나의 요약. 상세(거래 내역, 자산 곡선, 지표)는 저장소에 남기고 여기에는 식별자와 최종 상태만 둔다.
 *
 * @param finalEquity 비용 차감 후 최종 평가금액. 실패한 Run이면 null
 */
public record RunResult(String runId, AccountId accountId, String profileHash, RunStatus status, Money finalEquity) {

    public RunResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(profileHash, "profileHash");
        Objects.requireNonNull(status, "status");
        if (status == RunStatus.COMPLETED && finalEquity == null) {
            throw new IllegalArgumentException("완료된 Run에는 최종 평가금액이 필요합니다");
        }
    }
}
