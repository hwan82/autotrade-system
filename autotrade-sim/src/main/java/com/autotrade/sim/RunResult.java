package com.autotrade.sim;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import java.util.Objects;

/**
 * Run 하나의 요약. 상세(거래 내역, 자산 곡선)는 저장소에 남긴다. 성과 지표 묶음(RunMetrics)은 3단계에서 더한다.
 *
 * @param runSeed 이 Run의 난수 시드. 실험 시드와 프로필 해시에서 파생한다({@link ExperimentRunner} 참고)
 * @param finalEquity 비용 차감 후 최종 평가금액. 완료되지 않은 Run이면 null
 * @param failureReason 실패 사유. 실패가 아니면 빈 문자열. 비밀값을 넣지 않는다
 */
public record RunResult(
        String runId,
        AccountId accountId,
        String profileHash,
        long runSeed,
        RunStatus status,
        Money finalEquity,
        String failureReason) {

    public RunResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(profileHash, "profileHash");
        Objects.requireNonNull(status, "status");
        failureReason = failureReason == null ? "" : failureReason;
        if (status == RunStatus.COMPLETED && finalEquity == null) {
            throw new IllegalArgumentException("완료된 Run에는 최종 평가금액이 필요합니다");
        }
    }
}
