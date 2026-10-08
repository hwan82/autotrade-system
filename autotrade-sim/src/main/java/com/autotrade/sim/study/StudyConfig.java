package com.autotrade.sim.study;

import com.autotrade.domain.Money;
import com.autotrade.sim.ExperimentId;
import com.autotrade.strategy.StrategyProfile;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 다중 전략 비교 실험의 설계(사전 등록). 실행 전에 고정하고 결과를 본 뒤 바꾸지 않는다.
 *
 * @param id 실험 묶음 ID. 개별 실험 ID는 여기에 구간·지연 접미사를 붙인다
 * @param randomSeed 실험 시드(Run 시드 파생 입력)
 * @param seedCapital 계좌별 시드머니(D-021)
 * @param profiles 비교할 프로필(기준선·대조군 포함). 이 순서로 계좌 번호를 매긴다
 * @param baselineProfileId 초과수익 기준(매수 후 보유)
 * @param controlProfileId 무작위 진입 대조군. 대조군 분포 실험에 쓴다
 * @param trainDays 앞에서부터 학습 구간 거래일 수. 나머지가 검증(표본 외) 구간
 * @param latencies 주문 지연 민감도에 쓸 지연 목록(STR-13). {@code baseLatency}를 포함해야 한다
 * @param baseLatency 기본 비교 지연(D-025 0.5초)
 * @param nullSeeds 대조군을 다른 시드로 돌릴 횟수
 * @param parallelism 동시에 돌릴 Run 수(결과에는 영향 없음)
 */
public record StudyConfig(
        ExperimentId id,
        long randomSeed,
        Money seedCapital,
        List<StrategyProfile> profiles,
        String baselineProfileId,
        String controlProfileId,
        int trainDays,
        List<Duration> latencies,
        Duration baseLatency,
        int nullSeeds,
        ScreeningThresholds thresholds,
        int parallelism) {

    public StudyConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(seedCapital, "seedCapital");
        Objects.requireNonNull(baselineProfileId, "baselineProfileId");
        Objects.requireNonNull(controlProfileId, "controlProfileId");
        Objects.requireNonNull(baseLatency, "baseLatency");
        Objects.requireNonNull(thresholds, "thresholds");
        profiles = List.copyOf(profiles);
        latencies = List.copyOf(latencies);
        if (profiles.stream().map(StrategyProfile::id).distinct().count() != profiles.size()) {
            throw new IllegalArgumentException("프로필 ID가 겹칩니다(결과를 프로필 ID로 모은다)");
        }
        if (profiles.stream().noneMatch(p -> p.id().equals(baselineProfileId))) {
            throw new IllegalArgumentException("기준선 프로필이 목록에 없습니다: " + baselineProfileId);
        }
        if (profiles.stream().noneMatch(p -> p.id().equals(controlProfileId))) {
            throw new IllegalArgumentException("대조군 프로필이 목록에 없습니다: " + controlProfileId);
        }
        if (!latencies.contains(baseLatency) || !latencies.contains(thresholds.stressLatency())) {
            throw new IllegalArgumentException("지연 목록에 기본 지연과 판정 지연이 있어야 합니다");
        }
        if (trainDays < 1 || nullSeeds < 0) {
            throw new IllegalArgumentException("학습 구간 일수·대조군 횟수가 올바르지 않습니다");
        }
    }
}
