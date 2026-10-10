package com.autotrade.sim;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.strategy.StrategyProfile;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 실험 정의: 기간 × 시드 × 조합 N개. 조합(선정 프로필 버전, 전략 프로필 버전)마다 독립 모의 계좌 하나가 같은 시세 위에서
 * 돈다(D-041, STR-17, ADR-007 2.4). 성과 비교는 같은 기간·같은 시드·같은 비용 모델끼리만 하므로(프로젝트 지침 5장) 이
 * 값들은 실험 단위로 고정한다. 유니버스(상장폐지 포함 당시 종목), 체결 해상도, 벤치마크, 블랙리스트·수동 보유 목록, 리스크
 * 한도는 3단계 ADR에서 더한다.
 *
 * <p>이 목록이 실험의 등록 조합 전체다. 실행 중 조합을 더하는 API는 없다(STR-17 "등록되지 않은 조합 추가 거부").
 *
 * @param from 시작일(포함, 거래소 현지 날짜)
 * @param to 종료일(포함)
 * @param randomSeed 슬리피지 등 난수 시드. 같은 시드면 같은 결과
 * @param seedCapital 계좌별 시드머니. 계좌 통화 기준
 * @param costModelId 비용 모델 ID(수수료·거래세·슬리피지 묶음). 내용이 바뀌면 ID도 바뀌는 불변 ID여야 한다
 *     (예: 내용 해시 포함). 정의는 3단계
 * @param dataSnapshotVersion 불변 데이터 스냅샷 ID(녹화일 또는 일봉 스냅샷, D-019). 같은 ID면 같은 데이터다
 * @param clockMode 실시간 모의인지 녹화 리플레이인지
 * @param combinations 실행 순서가 결과에 영향을 주지 않아야 하지만, 계좌 번호 부여는 이 목록 순서를 따른다
 */
public record ExperimentSpec(
        ExperimentId id,
        LocalDate from,
        LocalDate to,
        long randomSeed,
        Money seedCapital,
        String costModelId,
        String dataSnapshotVersion,
        ClockMode clockMode,
        List<Combination> combinations) {

    public ExperimentSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(seedCapital, "seedCapital");
        Objects.requireNonNull(costModelId, "costModelId");
        Objects.requireNonNull(dataSnapshotVersion, "dataSnapshotVersion");
        Objects.requireNonNull(clockMode, "clockMode");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("종료일이 시작일보다 앞섭니다: " + from + " ~ " + to);
        }
        if (seedCapital.signum() <= 0) {
            throw new IllegalArgumentException("시드머니는 0보다 커야 합니다: " + seedCapital);
        }
        combinations = List.copyOf(combinations);
        if (combinations.isEmpty()) {
            throw new IllegalArgumentException("조합이 하나 이상 필요합니다");
        }
        Set<String> pairs = new HashSet<>();
        Map<String, String> selectionHashes = new HashMap<>();
        Map<String, String> profileHashes = new HashMap<>();
        Market market = combinations.get(0).profile().market();
        for (Combination c : combinations) {
            StrategyProfile p = c.profile();
            String profileKey = p.id() + "@" + p.version();
            // 조합 단위 중복 판정(ADR-007 2.4): 같은 (선정 id@version 또는 none, 전략 id@version) 쌍은 한 번만
            if (!pairs.add(c.selectionKeyOrNone() + "|" + profileKey)) {
                throw new IllegalArgumentException("같은 조합이 두 번 들어 있습니다: 선정 " + c.selectionKeyOrNone()
                        + ", 전략 " + profileKey);
            }
            // (id, version)이 같으면 내용도 같아야 한다. 같은 키에 다른 내용을 허용하면 결과 추적이 깨진다(두 축 모두)
            String previous = profileHashes.putIfAbsent(profileKey, p.profileHash());
            if (previous != null && !previous.equals(p.profileHash())) {
                throw new IllegalArgumentException("같은 프로필 ID·버전에 다른 내용이 있습니다: " + p.id() + " v" + p.version());
            }
            if (c.selection().isPresent()) {
                SelectionProfile s = c.selection().get();
                String prev = selectionHashes.putIfAbsent(s.key(), s.selectionHash());
                if (prev != null && !prev.equals(s.selectionHash())) {
                    throw new IllegalArgumentException("같은 선정 프로필 ID·버전에 다른 내용이 있습니다: " + s.id() + " v"
                            + s.version());
                }
            }
            if (p.market() != market) {
                throw new IllegalArgumentException("한 실험의 조합은 같은 시장이어야 합니다");
            }
        }
        if (!seedCapital.currency().equals(market.currency())) {
            throw new IllegalArgumentException("시드머니 통화가 시장 통화와 다릅니다: " + seedCapital + ", " + market);
        }
    }

    /**
     * 선정 없는 단일 프로필 실험(기존 계약). 프로필마다 {@link Combination#of(StrategyProfile)}로 감싸므로 계좌 번호·Run
     * 시드(run-seed-v1)·결과가 조합 도입 전과 같다(ADR-007 2.4 호환).
     */
    public static ExperimentSpec ofProfiles(
            ExperimentId id, LocalDate from, LocalDate to, long randomSeed, Money seedCapital, String costModelId,
            String dataSnapshotVersion, ClockMode clockMode, List<StrategyProfile> profiles) {
        return new ExperimentSpec(id, from, to, randomSeed, seedCapital, costModelId, dataSnapshotVersion, clockMode,
                profiles.stream().map(Combination::of).toList());
    }

    /** 실험의 시장. 모든 조합이 같다. */
    public Market market() {
        return combinations.get(0).profile().market();
    }

    /** 선정 있는 조합이 하나라도 있는지. */
    public boolean hasSelections() {
        return combinations.stream().anyMatch(Combination::hasSelection);
    }
}
