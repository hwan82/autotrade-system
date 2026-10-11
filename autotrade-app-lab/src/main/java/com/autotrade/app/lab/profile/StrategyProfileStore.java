package com.autotrade.app.lab.profile;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 전략 프로필 YAML 저장소(UI-03, ADR-005 4장). 파일 배치는 {@code <strategies-dir>/<프로필ID>/v<버전>.yaml}이다.
 *
 * <p>버전 파일은 한 번 쓰면 고치지 않는다(append-only). 편집은 항상 새 버전을 만들고, 그래서 새 해시가 생긴다.
 * 실험 결과는 profile_hash로 프로필을 가리키므로 옛 버전이 바뀌면 결과 추적이 깨지기 때문이다.
 * 같은 프로필의 버전끼리는 시장과 매매 스타일을 바꿀 수 없다(바꾸려면 복제해 새 ID로 만든다, D-006).
 * 파일 읽기·쓰기 규칙은 선정 프로필 저장소와 같다({@link VersionedProfileFiles}).
 *
 * <p>읽지 못하는 파일(형식 오류, ID·버전이 경로와 다름)은 목록에서 빼고 파일 이름만 경고한다.
 */
public final class StrategyProfileStore {

    private static final String HEADER = "# 전략 프로필(ADR-005 4장). 저장된 버전 파일은 고치지 않는다. 바꾸려면 새 버전을 만든다.\n";

    private final VersionedProfileFiles<StoredProfile> files;

    public StrategyProfileStore(Path root, Clock clock) {
        this.files = new VersionedProfileFiles<>(root, clock, new Codec());
    }

    public Path root() {
        return files.root();
    }

    /** 프로필 ID 이름순. */
    public List<ProfileSummary> list() {
        return files.list().stream()
                .map(s -> new ProfileSummary(s.id(), s.market(), s.style(), s.versions(), s.latestHash()))
                .toList();
    }

    /** 버전 오름차순. 없는 프로필이면 빈 목록. */
    public List<StoredProfile> versions(String id) {
        return files.versions(id);
    }

    public Optional<StoredProfile> find(String id, int version) {
        return files.find(id, version);
    }

    /**
     * 새 프로필(버전 1)을 만든다. 복제할 때는 {@code basedOn}에 원본 {@code id@version}을 넣는다.
     *
     * @throws ProfileConflictException 같은 ID가 이미 있을 때
     */
    public StoredProfile create(
            String id, Market market, TradingStyle style, Map<String, String> parameters, String basedOn, String note) {
        return files.create(id, parameters, (params, at) -> new StoredProfile(
                new StrategyProfile(id, 1, market, style, params), basedOn, at, VersionedProfileFiles.checkedNote(note)));
    }

    /**
     * 기존 프로필의 새 버전을 만든다. {@code baseVersion}이 현재 최신 버전이 아니면 다른 곳에서 먼저 고친 것이므로
     * 덮어쓰지 않고 거절한다.
     *
     * @throws ProfileConflictException 최신 버전이 바뀌었을 때
     * @throws IllegalArgumentException 파라미터가 원본과 같을 때(새 버전을 만들 이유가 없음)
     */
    public StoredProfile createVersion(String id, int baseVersion, Map<String, String> parameters, String note) {
        return files.createVersion(id, baseVersion, parameters, (latest, params, at) -> {
            StrategyProfile p = latest.profile();
            return new StoredProfile(new StrategyProfile(id, p.version() + 1, p.market(), p.style(), params),
                    id + "@" + p.version(), at, VersionedProfileFiles.checkedNote(note));
        });
    }

    /** 목록 한 줄. */
    public record ProfileSummary(
            String id, Market market, TradingStyle style, List<Integer> versions, String latestHash) {
        public ProfileSummary {
            versions = List.copyOf(versions);
        }
    }

    private static final class Codec implements VersionedProfileFiles.Codec<StoredProfile> {
        @Override
        public String label() {
            return "전략 프로필";
        }

        @Override
        public String header() {
            return HEADER;
        }

        @Override
        public StoredProfile fromFile(VersionedProfileFiles.ProfileFile f) {
            return new StoredProfile(new StrategyProfile(f.id(), f.version(), f.market(), f.style(),
                    f.sortedParameters()), f.basedOn(), f.createdAtInstant(), f.note());
        }

        @Override
        public VersionedProfileFiles.ProfileFile toFile(StoredProfile s) {
            StrategyProfile p = s.profile();
            return new VersionedProfileFiles.ProfileFile(p.id(), p.version(), p.market(), p.style(), s.basedOn(),
                    s.createdAt().toString(), s.note(), p.parameters());
        }

        @Override
        public VersionedProfileFiles.Header headerOf(StoredProfile s) {
            StrategyProfile p = s.profile();
            return new VersionedProfileFiles.Header(p.id(), p.version(), p.market(), p.style(), p.parameters(),
                    p.profileHash());
        }
    }

    /** 같은 ID·버전 충돌. 화면에서는 409로 보인다. */
    public static final class ProfileConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProfileConflictException(String message) {
            super(message);
        }
    }
}
