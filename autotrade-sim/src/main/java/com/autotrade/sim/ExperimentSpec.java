package com.autotrade.sim;

import com.autotrade.domain.Money;
import com.autotrade.strategy.StrategyProfile;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 실험 정의: 기간 × 시드 × 전략 프로필 N개. 프로필마다 독립 모의 계좌 하나가 같은 시세 위에서 돈다.
 * 성과 비교는 같은 기간·같은 시드·같은 비용 모델끼리만 하므로(프로젝트 지침 5장) 이 값들은 실험 단위로 고정한다.
 *
 * @param from 시작일(포함, 거래소 현지 날짜)
 * @param to 종료일(포함)
 * @param randomSeed 슬리피지 등 난수 시드. 같은 시드면 같은 결과
 * @param seedCapital 계좌별 시드머니. 계좌 통화 기준
 * @param costModelId 비용 모델 ID(수수료·거래세·슬리피지 묶음). 정의는 3단계
 * @param dataVersion 시세 데이터 버전(녹화일 또는 일봉 스냅샷 ID)
 * @param profiles 실행 순서가 결과에 영향을 주지 않아야 하지만, 계좌 번호 부여는 이 목록 순서를 따른다
 */
public record ExperimentSpec(
        ExperimentId id,
        LocalDate from,
        LocalDate to,
        long randomSeed,
        Money seedCapital,
        String costModelId,
        String dataVersion,
        List<StrategyProfile> profiles) {

    public ExperimentSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(seedCapital, "seedCapital");
        Objects.requireNonNull(costModelId, "costModelId");
        Objects.requireNonNull(dataVersion, "dataVersion");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("종료일이 시작일보다 앞섭니다: " + from + " ~ " + to);
        }
        if (seedCapital.signum() <= 0) {
            throw new IllegalArgumentException("시드머니는 0보다 커야 합니다: " + seedCapital);
        }
        profiles = List.copyOf(profiles);
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("전략 프로필이 하나 이상 필요합니다");
        }
        Set<String> hashes = new HashSet<>();
        for (StrategyProfile p : profiles) {
            if (!hashes.add(p.profileHash())) {
                throw new IllegalArgumentException("같은 프로필이 두 번 들어 있습니다: " + p.id() + " v" + p.version());
            }
            if (!p.market().equals(profiles.get(0).market())) {
                throw new IllegalArgumentException("한 실험의 프로필은 같은 시장이어야 합니다");
            }
        }
    }
}
