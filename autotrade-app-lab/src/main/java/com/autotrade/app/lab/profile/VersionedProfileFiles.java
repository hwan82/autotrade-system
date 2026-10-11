package com.autotrade.app.lab.profile;

import com.autotrade.domain.market.Market;
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
 * 버전 파일 저장소의 공통 동작(전략 프로필 ADR-005 4장, 선정 프로필 ADR-007 2.1). 파일 배치는
 * {@code <root>/<ID>/v<버전>.yaml}이고 두 종류 모두 같은 YAML 모양({@link ProfileFile})을 쓴다.
 *
 * <p>버전 파일은 한 번 쓰면 고치지 않는다(append-only). 편집은 항상 새 버전을 만들고, 그래서 새 해시가 생긴다.
 * 같은 ID의 버전끼리는 시장과 매매 스타일을 바꿀 수 없다(바꾸려면 복제해 새 ID로 만든다, D-006).
 * 읽지 못하는 파일(형식 오류, 도메인 검증 실패, ID·버전이 경로와 다름)은 목록에서 빼고 파일 이름만 경고한다.
 *
 * @param <S> 저장된 한 버전(프로필 + 원본·저장 시각·메모)
 */
final class VersionedProfileFiles<S> {

    private static final Logger log = LoggerFactory.getLogger(VersionedProfileFiles.class);
    private static final Pattern VERSION_FILE = Pattern.compile("v([1-9][0-9]{0,8})\\.yaml");
    static final int MAX_PARAMETERS = 200;
    static final int MAX_VALUE_LENGTH = 500;
    static final int MAX_NOTE_LENGTH = 500;

    /** 저장 종류별 변환과 해시. {@link #fromFile}은 도메인 검증을 하고 틀리면 {@link IllegalArgumentException}을 던진다. */
    interface Codec<S> {
        /** 로그·메시지에 쓰는 이름(예: "전략 프로필"). */
        String label();

        /** 파일 첫 줄 주석. 줄바꿈으로 끝난다. */
        String header();

        S fromFile(ProfileFile file);

        ProfileFile toFile(S stored);

        Header headerOf(S stored);
    }

    /** 저장된 한 버전에서 저장소가 보는 값. */
    record Header(String id, int version, Market market, TradingStyle style, SortedMap<String, String> parameters,
            String hash) {
    }

    /** 목록 한 줄. */
    record Summary(String id, Market market, TradingStyle style, List<Integer> versions, String latestHash) {
        Summary {
            versions = List.copyOf(versions);
        }
    }

    private final Path root;
    private final Clock clock;
    private final Codec<S> codec;
    private final ObjectMapper yaml;

    VersionedProfileFiles(Path root, Clock clock, Codec<S> codec) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.yaml = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                // 문자열은 항상 따옴표로 쓴다. 따옴표를 줄이면 .inf·.nan 같은 값이 다시 읽을 때 숫자로 해석된다
                .disable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    Path root() {
        return root;
    }

    Instant now() {
        return clock.instant();
    }

    /** ID 이름순. */
    synchronized List<Summary> list() {
        List<Summary> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        TreeMap<String, Path> dirs = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : stream) {
                dirs.put(dir.getFileName().toString(), dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(codec.label() + " 폴더를 읽지 못했습니다", e);
        }
        for (String id : dirs.keySet()) {
            List<S> versions = readAll(id);
            if (versions.isEmpty()) {
                continue;
            }
            Header latest = codec.headerOf(versions.get(versions.size() - 1));
            out.add(new Summary(id, latest.market(), latest.style(),
                    versions.stream().map(v -> codec.headerOf(v).version()).toList(), latest.hash()));
        }
        return out;
    }

    /** 버전 오름차순. 없는 ID면 빈 목록. */
    synchronized List<S> versions(String id) {
        return readAll(id);
    }

    synchronized Optional<S> find(String id, int version) {
        return readAll(id).stream().filter(p -> codec.headerOf(p).version() == version).findFirst();
    }

    /**
     * 버전 1을 쓴다. {@code build}는 검증된 파라미터와 저장 시각을 받아 저장할 값을 만든다.
     *
     * @throws StrategyProfileStore.ProfileConflictException 같은 ID가 이미 있을 때
     */
    synchronized S create(String id, Map<String, String> parameters,
            java.util.function.BiFunction<SortedMap<String, String>, Instant, S> build) {
        if (!readAll(id).isEmpty() || Files.exists(dir(id))) {
            throw new StrategyProfileStore.ProfileConflictException("이미 있는 " + codec.label() + " ID입니다: " + id);
        }
        return write(build.apply(checkedParameters(parameters), clock.instant()));
    }

    /**
     * 최신 버전 다음 버전을 쓴다. {@code baseVersion}이 현재 최신 버전이 아니면 다른 곳에서 먼저 고친 것이므로 덮어쓰지 않고
     * 거절한다. {@code build}는 (최신 버전, 검증된 파라미터, 저장 시각)을 받는다.
     *
     * @throws StrategyProfileStore.ProfileConflictException 최신 버전이 바뀌었을 때
     * @throws IllegalArgumentException 파라미터가 원본과 같을 때(새 버전을 만들 이유가 없음)
     */
    synchronized S createVersion(String id, int baseVersion, Map<String, String> parameters, NextVersion<S> build) {
        List<S> all = readAll(id);
        if (all.isEmpty()) {
            throw new java.util.NoSuchElementException(codec.label() + "이 없습니다: " + id);
        }
        S latest = all.get(all.size() - 1);
        Header head = codec.headerOf(latest);
        if (head.version() != baseVersion) {
            throw new StrategyProfileStore.ProfileConflictException("v" + baseVersion + "을 고치는 동안 최신 버전이 v"
                    + head.version() + "(으)로 바뀌었습니다. 최신 버전을 다시 열어 고치세요");
        }
        SortedMap<String, String> next = checkedParameters(parameters);
        if (next.equals(head.parameters())) {
            throw new IllegalArgumentException("바뀐 파라미터가 없습니다");
        }
        if (Files.exists(dir(id).resolve("v" + (head.version() + 1) + ".yaml"))) {
            throw new StrategyProfileStore.ProfileConflictException("v" + (head.version() + 1)
                    + " 파일이 이미 있지만 읽을 수 없습니다. 실험실 로그의 경고를 보고 그 파일을 고친 뒤 다시 저장하세요");
        }
        return write(build.next(latest, next, clock.instant()));
    }

    @FunctionalInterface
    interface NextVersion<S> {
        S next(S latest, SortedMap<String, String> parameters, Instant createdAt);
    }

    static SortedMap<String, String> checkedParameters(Map<String, String> parameters) {
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

    static String checkedNote(String note) {
        String trimmed = note == null ? "" : note.strip();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("메모는 " + MAX_NOTE_LENGTH + "자까지입니다");
        }
        return trimmed;
    }

    private S write(S stored) {
        Header head = codec.headerOf(stored);
        Path dir = dir(head.id());
        Path target = dir.resolve("v" + head.version() + ".yaml");
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".v" + head.version(), ".tmp");
            try {
                Files.writeString(tmp, codec.header() + yaml.writeValueAsString(codec.toFile(stored)));
                // 쓴 파일을 다시 읽어 같은 내용(같은 해시)인지 확인한다. 못 읽는 파일을 성공으로 남기지 않는다
                S check = codec.fromFile(yaml.readValue(tmp.toFile(), ProfileFile.class));
                if (!codec.headerOf(check).hash().equals(head.hash())) {
                    throw new IllegalStateException("저장한 " + codec.label() + "을 다시 읽은 값이 다릅니다: " + head.id());
                }
                // REPLACE_EXISTING 없이 옮긴다: 같은 버전 파일이 이미 있으면 덮어쓰지 않고 실패한다
                Files.move(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (FileAlreadyExistsException e) {
            throw new StrategyProfileStore.ProfileConflictException(
                    "같은 버전 파일이 이미 있습니다: " + head.id() + " v" + head.version());
        } catch (IOException e) {
            throw new UncheckedIOException(codec.label() + " 저장 실패: " + head.id() + " v" + head.version(), e);
        }
        log.info("{} 저장: {} v{} ({})", codec.label(), head.id(), head.version(), head.hash());
        return stored;
    }

    private Path dir(String id) {
        if (!StrategyProfileIds.isValid(id)) {
            throw new IllegalArgumentException(codec.label() + " ID 형식이 아닙니다(영문 소문자·숫자·하이픈, 64자 이내)");
        }
        Path dir = root.resolve(id).normalize();
        if (!dir.getParent().equals(root)) {
            throw new IllegalArgumentException(codec.label() + " ID 형식이 아닙니다");
        }
        return dir;
    }

    private List<S> readAll(String id) {
        if (!StrategyProfileIds.isValid(id)) {
            return List.of();
        }
        Path dir = dir(id);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        TreeMap<Integer, S> byVersion = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "v*.yaml")) {
            for (Path file : stream) {
                Matcher m = VERSION_FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int version = Integer.parseInt(m.group(1));
                try {
                    S p = codec.fromFile(yaml.readValue(file.toFile(), ProfileFile.class));
                    Header head = codec.headerOf(p);
                    if (!head.id().equals(id) || head.version() != version) {
                        log.warn("{} 파일의 ID·버전이 경로와 달라 건너뜀: {}/{}", codec.label(), id, file.getFileName());
                        continue;
                    }
                    byVersion.put(version, p);
                } catch (IOException | RuntimeException e) {
                    log.warn("{} 파일을 읽지 못해 건너뜀: {}/{} ({})", codec.label(), id, file.getFileName(),
                            e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(codec.label() + " 폴더를 읽지 못했습니다: " + id, e);
        }
        return List.copyOf(byVersion.values());
    }

    /** YAML 파일 모양. 키는 kebab-case, 파라미터 값은 항상 문자열. 전략·선정 프로필이 같은 모양을 쓴다. */
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

        SortedMap<String, String> sortedParameters() {
            return new TreeMap<>(parameters == null ? Map.of() : parameters);
        }

        Instant createdAtInstant() {
            return Instant.parse(createdAt);
        }
    }
}
