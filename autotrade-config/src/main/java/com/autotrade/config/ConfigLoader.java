package com.autotrade.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 기동 시 설정 로딩: 내장 {@code autotrade-default.json} 위에 외부 {@code runtime-config.json}을 덮어쓴다.
 * 외부 파일이 없거나 깨졌으면(문법 오류, 모르는 키, 범위 위반) 내장 default로만 기동하고 경고를 남긴다.
 *
 * <p>병합 규칙: 객체는 키 단위로 재귀 병합하고, 배열과 값은 통째로 바꾼다. 그래서 외부 파일에는 바꾼 키만 둬도 된다.
 */
public final class ConfigLoader {

    static final String DEFAULT_RESOURCE = "/autotrade-default.json";

    private final ObjectMapper mapper = ConfigJson.mapper();

    public LoadedConfig load(Path runtimeFile) {
        Objects.requireNonNull(runtimeFile, "runtimeFile");
        ObjectNode defaults = readDefaults();
        RuntimeConfig defaultConfig = bind(defaults.deepCopy());
        if (!Files.exists(runtimeFile)) {
            return new LoadedConfig(defaultConfig, ConfigSource.DEFAULT_NO_FILE, "");
        }
        try {
            JsonNode override = mapper.readTree(runtimeFile.toFile());
            if (!(override instanceof ObjectNode overrideObject)) {
                return invalid(defaultConfig, runtimeFile, "최상위가 JSON 객체가 아닙니다");
            }
            ObjectNode merged = defaults.deepCopy();
            merge(merged, overrideObject);
            return new LoadedConfig(mapper.treeToValue(merged, RuntimeConfig.class), ConfigSource.RUNTIME_FILE, "");
        } catch (IOException | RuntimeException e) {
            return invalid(defaultConfig, runtimeFile, ConfigJson.describe(e));
        }
    }

    /** 내장 default만 읽는다. 내장 파일이 깨진 것은 빌드 오류이므로 예외. */
    public RuntimeConfig defaults() {
        return bind(readDefaults());
    }

    private LoadedConfig invalid(RuntimeConfig defaults, Path file, String reason) {
        return new LoadedConfig(
                defaults, ConfigSource.DEFAULT_FILE_INVALID, file.getFileName() + "을(를) 쓰지 않고 default로 기동: " + reason);
    }

    private ObjectNode readDefaults() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("내장 설정 파일이 없습니다: " + DEFAULT_RESOURCE);
            }
            return (ObjectNode) mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RuntimeConfig bind(JsonNode tree) {
        try {
            return mapper.treeToValue(tree, RuntimeConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException(ConfigJson.describe(e), e);
        }
    }

    static void merge(ObjectNode target, ObjectNode override) {
        for (Map.Entry<String, JsonNode> e : override.properties()) {
            JsonNode existing = target.get(e.getKey());
            if (existing instanceof ObjectNode existingObject && e.getValue() instanceof ObjectNode overrideObject) {
                merge(existingObject, overrideObject);
            } else {
                target.set(e.getKey(), e.getValue());
            }
        }
    }
}
