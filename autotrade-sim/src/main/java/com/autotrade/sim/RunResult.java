package com.autotrade.sim;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.RunSeeds;
import java.util.Objects;

/**
 * Run 하나의 요약. 상세(거래 내역, 자산 곡선)는 {@code com.autotrade.sim.run.RunReport}로 나오고 저장소에 남긴다.
 *
 * @param selectionHash 조합의 선정 해시. 선정이 없으면 {@code none}(ADR-007 2.2)
 * @param combinationHash 조합 해시({@code combination-v1})
 * @param runSeed 이 Run의 난수 시드. 실험 시드와 조합에서 파생한다({@link RunSeeds})
 * @param runSeedScheme 시드 파생 방식({@code run-seed-v1}: 선정 없음, {@code run-seed-v2}: 선정 있음)
 * @param finalEquity 비용 차감 후 최종 평가금액. 완료되지 않은 Run이면 null
 * @param failureReason 실패 사유. 실패가 아니면 빈 문자열. 비밀값을 넣지 않는다
 * @param metrics 성과 지표(SIM-11). 완료되지 않은 Run이면 null
 */
public record RunResult(
        String runId,
        AccountId accountId,
        String profileHash,
        String selectionHash,
        String combinationHash,
        long runSeed,
        String runSeedScheme,
        RunStatus status,
        Money finalEquity,
        String failureReason,
        RunMetrics metrics) {

    public RunResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(profileHash, "profileHash");
        Objects.requireNonNull(selectionHash, "selectionHash");
        Objects.requireNonNull(combinationHash, "combinationHash");
        Objects.requireNonNull(runSeedScheme, "runSeedScheme");
        Objects.requireNonNull(status, "status");
        // DB CHECK와 같은 규칙: 선정 없음 ⇔ v1, 조합 해시는 두 해시에서 나온 값(ADR-007 2.4·2.5)
        if (selectionHash.equals(Combination.NONE) != runSeedScheme.equals(RunSeeds.V1)
                || !(runSeedScheme.equals(RunSeeds.V1) || runSeedScheme.equals(RunSeeds.V2))) {
            throw new IllegalArgumentException("선정 유무와 시드 방식이 맞지 않습니다: " + selectionHash + ", " + runSeedScheme);
        }
        if (!combinationHash.equals(Combination.combinationHash(selectionHash, profileHash))) {
            throw new IllegalArgumentException("조합 해시가 선정·전략 해시와 맞지 않습니다: " + runId);
        }
        failureReason = failureReason == null ? "" : failureReason;
        if (status == RunStatus.COMPLETED && finalEquity == null) {
            throw new IllegalArgumentException("완료된 Run에는 최종 평가금액이 필요합니다");
        }
        if (status == RunStatus.COMPLETED && metrics == null) {
            throw new IllegalArgumentException("완료된 Run에는 성과 지표가 필요합니다");
        }
    }

    /** 선정 없는 조합의 Run(조합 도입 전 계약). 선정 해시 {@code none}, 시드 방식 {@code run-seed-v1}. */
    public RunResult(String runId, AccountId accountId, String profileHash, long runSeed, RunStatus status,
            Money finalEquity, String failureReason, RunMetrics metrics) {
        this(runId, accountId, profileHash, Combination.NONE, Combination.combinationHash(Combination.NONE, profileHash),
                runSeed, RunSeeds.V1, status, finalEquity, failureReason, metrics);
    }
}
