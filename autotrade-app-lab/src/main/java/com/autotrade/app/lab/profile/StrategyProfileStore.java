package com.autotrade.app.lab.profile;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 전략 프로필 YAML 저장소(UI-03, ADR-005 4장). 파일 배치는 {@code <strategies-dir>/<프로필ID>/v<버전>.yaml}이다.
 *
 * <p>버전 파일은 한 번 쓰면 고치지 않는다(append-only). 편집은 항상 새 버전을 만들고, 그래서 새 해시가 생긴다.
 * 실험 결과는 profile_hash로 프로필을 가리키므로 옛 버전이 바뀌면 결과 추적이 깨지기 때문이다.
 * 같은 프로필의 버전끼리는 시장과 매매 스타일을 바꿀 수 없다(바꾸려면 복제해 새 ID로 만든다, D-006).
 *
 * <p>읽지 못하는 파일(형식 오류, ID·버전이 경로와 다름)은 목록에서 빼고 파일 이름만 경고한다.
 */
public final class StrategyProfileStore {

    private static final Logger log = LoggerFactory.getLogger(StrategyProfileStore.class);
    private static final Pattern VERSION_FILE = Pattern.compile("v([1-9][0-9]{0,8})\\.yaml");
    static final int MAX_PARAMETERS = 200;
    static final int MAX_VALUE_LENGTH = 500;
    static final int MAX_NOTE_LENGTH = 500;

    private final Path root;
    private final Clock clock;
    private final ObjectMapper yaml;

    public StrategyProfileStore(Path root, Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.yaml = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                // 문자열은 항상 따옴표로 쓴다. 따옴표를 줄이면 .inf·.nan 같은 값이 다시 읽을 때 숫자로 해석된다
                .disable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public Path root() {
        return root;
    }

    /** 프로필 ID 이름순. */
    public synchronized List<ProfileSummary> list() {
        List<ProfileSummary> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        TreeMap<String, Path> dirs = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : stream) {
                dirs.put(dir.getFileName().toString(), dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("전략 프로필 폴더를 읽지 못했습니다", e);
        }
        for (String id : dirs.keySet()) {
            List<StoredProfile> versions = readAll(id);
            if (versions.isEmpty()) {
                continue;
            }
            StoredProfile latest = versions.get(versions.size() - 1);
            out.add(new ProfileSummary(id, latest.profile().market(), latest.profile().style(),
                    versions.stream().map(v -> v.profile().version()).toList(), latest.profile().profileHash()));
        }
        return out;
    }

    /** 버전 오름차순. 없는 프로필이면 빈 목록. */
    public synchronized List<StoredProfile> versions(String id) {
        return readAll(id);
    }

    public synchronized Optional<StoredProfile> find(String id, int version) {
        return readAll(id).stream().filter(p -> p.profile().version() == version).findFirst();
    }

    /**
     * 새 프로필(버전 1)을 만든다. 복제할 때는 {@code basedOn}에 원본 {@code id@version}을 넣는다.
     *
     * @throws ProfileConflictException 같은 ID가 이미 있을 때
     */
    public synchronized StoredProfile create(
            String id, Market market, TradingStyle style, Map<String, String> parameters, String basedOn, String note) {
        if (!readAll(id).isEmpty() || Files.exists(dir(id))) {
            throw new ProfileConflictException("이미 있는 프로필 ID입니다: " + id);
        }
        return write(new StrategyProfile(id, 1, market, style, checkedParameters(parameters)), basedOn, note);
    }

    /**
     * 기존 프로필의 새 버전을 만든다. {@code baseVersion}이 현재 최신 버전이 아니면 다른 곳에서 먼저 고친 것이므로
     * 덮어쓰지 않고 거절한다.
     *
     * @throws ProfileConflictException 최신 버전이 바뀌었을 때
     * @throws IllegalArgumentException 파라미터가 원본과 같을 때(새 버전을 만들 이유가 없음)
     */
    public synchronized StoredProfile createVersion(
            String id, int baseVersion, Map<String, String> parameters, String note) {
        List<StoredProfile> all = readAll(id);
        if (all.isEmpty()) {
            throw new java.util.NoSuchElementException("프로필이 없습니다: " + id);
        }
        StrategyProfile latest = all.get(all.size() - 1).profile();
        if (latest.version() != baseVersion) {
            throw new ProfileConflictException("v" + baseVersion + "을 고치는 동안 최신 버전이 v" + latest.version()
                    + "(으)로 바뀌었습니다. 최신 버전을 다시 열어 고치세요");
        }
        SortedMap<String, String> next = checkedParameters(parameters);
        if (next.equals(latest.parameters())) {
            throw new IllegalArgumentException("바뀐 파라미터가 없습니다");
        }
        StrategyProfile profile = new StrategyProfile(id, latest.version() + 1, latest.market(), latest.style(), next);
        if (Files.exists(dir(id).resolve("v" + profile.version() + ".yaml"))) {
            throw new ProfileConflictException("v" + profile.version() + " 파일이 이미 있지만 읽을 수 없습니다. "
                    + "실험실 로그의 경고를 보고 그 파일을 고친 뒤 다시 저장하세요");
        }
        return write(profile, id + "@" + latest.version(), note);
    }

    private static SortedMap<String, String> checkedParameters(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("파라미터는 " + MAX_PARAMETERS + "개까지입니다");
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue().strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("파라미터 값이 비어 있습니다: " + e.getKey());
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("파라미터 값은 " + MAX_VALUE_LENGTH + "자까지입니다: " + e.getKey());
            }
            if (out.put(e.getKey().strip(), value) != null) {
                throw new IllegalArgumentException("같은 파라미터 키가 두 번 있습니다: " + e.getKey().strip());
            }
        }
        return out;
    }

    private StoredProfile write(StrategyProfile profile, String basedOn, String note) {
        String trimmedNote = note == null ? "" : note.strip();
        if (trimmedNote.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("메모는 " + MAX_NOTE_LENGTH + "자까지입니다");
        }
        StoredProfile stored = new StoredProfile(profile, basedOn, clock.instant(), trimmedNote);
        Path dir = dir(profile.id());
        Path target = dir.resolve("v" + profile.version() + ".yaml");
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".v" + profile.version(), ".tmp");
            try {
                Files.writeString(tmp, HEADER + yaml.writeValueAsString(ProfileFile.of(stored)));
                // 쓴 파일을 다시 읽어 같은 프로필(같은 해시)인지 확인한다. 못 읽는 파일을 성공으로 남기지 않는다
                StoredProfile check = yaml.readValue(tmp.toFile(), ProfileFile.class).toStored();
                if (!check.profile().profileHash().equals(profile.profileHash())) {
                    throw new IllegalStateException("저장한 프로필을 다시 읽은 값이 다릅니다: " + profile.id());
                }
                // REPLACE_EXISTING 없이 옮긴다: 같은 버전 파일이 이미 있으면 덮어쓰지 않고 실패한다
                Files.move(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (FileAlreadyExistsException e) {
            throw new ProfileConflictException("같은 버전 파일이 이미 있습니다: " + profile.id() + " v" + profile.version());
        } catch (IOException e) {
            throw new UncheckedIOException("전략 프로필 저장 실패: " + profile.id() + " v" + profile.version(), e);
        }
        log.info("전략 프로필 저장: {} v{} ({})", profile.id(), profile.version(), profile.profileHash());
        return stored;
    }

    private static final String HEADER = "# 전략 프로필(ADR-005 4장). 저장된 버전 파일은 고치지 않는다. 바꾸려면 새 버전을 만든다.\n";

    private Path dir(String id) {
        if (!StrategyProfileIds.isValid(id)) {
            throw new IllegalArgumentException("프로필 ID 형식이 아닙니다(영문 소문자·숫자·하이픈, 64자 이내)");
        }
        Path dir = root.resolve(id).normalize();
        if (!dir.getParent().equals(root)) {
            throw new IllegalArgumentException("프로필 ID 형식이 아닙니다");
        }
        return dir;
    }

    private List<StoredProfile> readAll(String id) {
        if (!StrategyProfileIds.isValid(id)) {
            return List.of();
        }
        Path dir = dir(id);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        TreeMap<Integer, StoredProfile> byVersion = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "v*.yaml")) {
            for (Path file : stream) {
                Matcher m = VERSION_FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int version = Integer.parseInt(m.group(1));
                try {
                    StoredProfile p = yaml.readValue(file.toFile(), ProfileFile.class).toStored();
                    if (!p.profile().id().equals(id) || p.profile().version() != version) {
                        log.warn("전략 프로필 파일의 ID·버전이 경로와 달라 건너뜀: {}/{}", id, file.getFileName());
                        continue;
                    }
                    byVersion.put(version, p);
                } catch (IOException | RuntimeException e) {
                    log.warn("전략 프로필 파일을 읽지 못해 건너뜀: {}/{} ({})", id, file.getFileName(),
                            e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("전략 프로필 폴더를 읽지 못했습니다: " + id, e);
        }
        return List.copyOf(byVersion.values());
    }

    /** 목록 한 줄. */
    public record ProfileSummary(
            String id, Market market, TradingStyle style, List<Integer> versions, String latestHash) {
        public ProfileSummary {
            versions = List.copyOf(versions);
        }
    }

    /** YAML 파일 모양. 키는 kebab-case, 파라미터 값은 항상 문자열. */
    @JsonPropertyOrder({"id", "version", "market", "style", "based-on", "created-at", "note", "parameters"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record ProfileFile(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "version", required = true) int version,
            @JsonProperty(value = "market", required = true) Market market,
            @JsonProperty(value = "style", required = true) TradingStyle style,
            @JsonProperty("based-on") String basedOn,
            @JsonProperty(value = "created-at", required = true) String createdAt,
            @JsonProperty("note") String note,
            @JsonProperty("parameters") Map<String, String> parameters) {

        static ProfileFile of(StoredProfile s) {
            StrategyProfile p = s.profile();
            return new ProfileFile(p.id(), p.version(), p.market(), p.style(), s.basedOn(), s.createdAt().toString(),
                    s.note(), p.parameters());
        }

        StoredProfile toStored() {
            TreeMap<String, String> params = new TreeMap<>(parameters == null ? Map.of() : parameters);
            return new StoredProfile(new StrategyProfile(id, version, market, style, params), basedOn,
                    Instant.parse(createdAt), note);
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
