package com.autotrade.app.lab.profile;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.calc.SelectionCriteria;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.strategy.TradingStyle;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

/**
 * 선정 프로필 YAML 저장소(UI-03, ADR-007 2.1). 파일 배치는 {@code <selections-dir>/<선정ID>/v<버전>.yaml}이고 전략 프로필과
 * 같은 파일 모양·같은 불변 버전 규칙을 쓴다({@link VersionedProfileFiles}). 실험 실행기는 같은 파일을 순수 모듈
 * {@link com.autotrade.selection.profile.SelectionProfileYaml}로 읽는다.
 *
 * <p>저장할 때는 예약 키({@link SelectionProfile}: 가설·갱신 주기·종목 수 상한)와 선정 조건 카탈로그
 * ({@link SelectionCriteria#parse}: 모르는 키, 지표·연산·기간)를 모두 통과해야 한다. 읽을 때는 예약 키만 보고, 카탈로그 검사
 * 결과는 {@link #catalogProblem}으로 따로 알려 준다(카탈로그가 바뀌어 옛 버전이 더는 통과하지 않아도 이력에서는 보이게).
 */
public final class SelectionProfileStore {

    private static final String HEADER = "# 선정 프로필(ADR-007 2.1, 카탈로그 docs/strategy/selection-catalog.md). "
            + "저장된 버전 파일은 고치지 않는다.\n";

    private final VersionedProfileFiles<StoredSelection> files;

    public SelectionProfileStore(Path root, Clock clock) {
        this.files = new VersionedProfileFiles<>(root, clock, new Codec());
    }

    public Path root() {
        return files.root();
    }

    /** 선정 ID 이름순. */
    public List<SelectionSummary> list() {
        return files.list().stream()
                .map(s -> new SelectionSummary(s.id(), s.market(), s.style(), s.versions(), s.latestHash()))
                .toList();
    }

    /** 버전 오름차순. 없는 선정이면 빈 목록. */
    public List<StoredSelection> versions(String id) {
        return files.versions(id);
    }

    public Optional<StoredSelection> find(String id, int version) {
        return files.find(id, version);
    }

    /**
     * 새 선정 프로필(버전 1). 복제할 때는 {@code basedOn}에 원본 {@code id@version}을 넣는다.
     *
     * @throws StrategyProfileStore.ProfileConflictException 같은 ID가 이미 있을 때
     * @throws IllegalArgumentException 예약 키·카탈로그 검사에 걸릴 때
     */
    public StoredSelection create(
            String id, Market market, TradingStyle style, Map<String, String> parameters, String basedOn, String note) {
        return files.create(id, parameters, (params, at) -> new StoredSelection(
                checked(id, 1, market, style, params), basedOn, at, VersionedProfileFiles.checkedNote(note)));
    }

    /**
     * 최신 버전 다음 버전을 만든다. 시장·스타일은 최신 버전 그대로다.
     *
     * @throws StrategyProfileStore.ProfileConflictException 최신 버전이 바뀌었을 때
     * @throws IllegalArgumentException 파라미터가 원본과 같거나 검사에 걸릴 때
     */
    public StoredSelection createVersion(String id, int baseVersion, Map<String, String> parameters, String note) {
        return files.createVersion(id, baseVersion, parameters, (latest, params, at) -> {
            SelectionProfile p = latest.profile();
            return new StoredSelection(checked(id, p.version() + 1, p.market(), p.style(), params),
                    p.key(), at, VersionedProfileFiles.checkedNote(note));
        });
    }

    /** 카탈로그 검사에 걸리는 이유. 통과하면 빈 값. */
    public static Optional<String> catalogProblem(SelectionProfile profile) {
        try {
            SelectionCriteria.parse(profile);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    private static SelectionProfile checked(
            String id, int version, Market market, TradingStyle style, SortedMap<String, String> params) {
        SelectionProfile profile = new SelectionProfile(id, version, market, style, params);
        catalogProblem(profile).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        return profile;
    }

    /** 목록 한 줄. */
    public record SelectionSummary(
            String id, Market market, TradingStyle style, List<Integer> versions, String latestHash) {
        public SelectionSummary {
            versions = List.copyOf(versions);
        }
    }

    private static final class Codec implements VersionedProfileFiles.Codec<StoredSelection> {
        @Override
        public String label() {
            return "선정 프로필";
        }

        @Override
        public String header() {
            return HEADER;
        }

        @Override
        public StoredSelection fromFile(VersionedProfileFiles.ProfileFile f) {
            return new StoredSelection(new SelectionProfile(f.id(), f.version(), f.market(), f.style(),
                    f.sortedParameters()), f.basedOn(), f.createdAtInstant(), f.note());
        }

        @Override
        public VersionedProfileFiles.ProfileFile toFile(StoredSelection s) {
            SelectionProfile p = s.profile();
            return new VersionedProfileFiles.ProfileFile(p.id(), p.version(), p.market(), p.style(), s.basedOn(),
                    s.createdAt().toString(), s.note(), p.parameters());
        }

        @Override
        public VersionedProfileFiles.Header headerOf(StoredSelection s) {
            SelectionProfile p = s.profile();
            return new VersionedProfileFiles.Header(p.id(), p.version(), p.market(), p.style(), p.parameters(),
                    p.selectionHash());
        }
    }
}
