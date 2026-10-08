package com.autotrade.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 기동 시 설정 로딩: 내장 {@code autotrade-default.json} 위에 외부 {@code runtime-config.json}, 그 위에 {@code secrets.json}을
 * 덮어쓴다. 외부 파일이 없거나 깨졌으면(문법 오류, 모르는 키, 범위 위반) 그 파일을 쓰지 않고 경고를 남긴다.
 *
 * <ul>
 *   <li>병합 규칙: 객체는 키 단위로 재귀 병합하고, 배열과 값은 통째로 바꾼다. 외부 파일에는 바꾼 키만 둬도 된다.</li>
 *   <li>runtime-config.json에 비밀값이 있으면 그 파일을 거부한다. 비밀값은 secrets.json에만 둔다(CFG-03).</li>
 *   <li>secrets.json에 비밀값이 아닌 키가 있으면 그 파일을 거부한다(일반 설정이 비밀 파일로 숨어드는 것 방지).</li>
 *   <li>secrets.json이 깨지면 비밀값 없이 기동한다. 해당 증권사·알림은 미설정(NOT_CONFIGURED)으로 남는다.</li>
 * </ul>
 */
public final class ConfigLoader {

    static final String DEFAULT_RESOURCE = "/autotrade-default.json";

    private final ObjectMapper mapper = ConfigJson.mapper();

    public LoadedConfig load(ConfigPaths paths) {
        Objects.requireNonNull(paths, "paths");
        ObjectNode[] secretsTree = new ObjectNode[1];
        LoadedConfig loaded = loadLayers(paths, secretsTree);
        String secretsName = paths.secretsFile().getFileName().toString();
        if (secretsTree[0] == null || loaded.warning().contains(secretsWarningPrefix(paths))) {
            return loaded;
        }
        // 쓴 secrets.json의 계좌번호 중 최종 계좌 목록에 없어 버려진 수(RuntimeConfig.RealTrading이 버림)
        int orphans = countAccountNumbers(secretsTree[0]) - loaded.config().realTrading().accountNumbers().size();
        if (orphans <= 0) {
            return loaded;
        }
        // 별칭도 남기지 않는다: 별칭 자리에 계좌번호를 잘못 적었을 수 있다
        String extra = secretsName + "의 real-trading.account-numbers 중 real-trading.accounts에 없거나 값이 빈 항목 "
                + orphans + "개를 무시했습니다";
        return new LoadedConfig(loaded.config(), loaded.source(),
                loaded.warning().isEmpty() ? extra : loaded.warning() + "; " + extra);
    }

    private LoadedConfig loadLayers(ConfigPaths paths, ObjectNode[] secretsTree) {
        ObjectNode defaults = readDefaults();
        List<String> warnings = new ArrayList<>();

        // 1. 파일별 형식 검사(문법, 비밀값 위치)
        ObjectNode runtime = null;
        boolean runtimeInvalid = false;
        if (Files.exists(paths.runtimeFile())) {
            try {
                runtime = readObject(paths.runtimeFile());
                List<String> leaked = SecretPaths.presentIn(runtime);
                if (!leaked.isEmpty()) {
                    throw new InvalidConfigException("비밀값은 " + ConfigPaths.SECRETS_FILE + "에만 둡니다: " + leaked);
                }
            } catch (IOException | RuntimeException e) {
                runtime = null;
                runtimeInvalid = true;
                warnings.add(runtimeWarning(paths, describe(e)));
            }
        }
        ObjectNode secrets = null;
        if (Files.exists(paths.secretsFile())) {
            try {
                secrets = readObject(paths.secretsFile());
                List<String> nonSecret = SecretPaths.nonSecretLeaves(secrets);
                if (!nonSecret.isEmpty()) {
                    throw new InvalidConfigException("비밀값이 아닌 키가 있습니다: " + nonSecret);
                }
            } catch (IOException | RuntimeException e) {
                secrets = null;
                warnings.add(secretsWarning(paths, describe(e)));
            }
        }
        if (secrets != null) {
            secretsTree[0] = secrets;
        }

        // 2. 합쳐서 값 검증. 실패하면 일반 설정 → 비밀값 순으로 빼 보며 원인 파일을 찾는다
        String combinedError = null;
        if (runtime != null || secrets != null) {
            try {
                return result(bind(layer(defaults, runtime, secrets)), runtime, runtimeInvalid, warnings);
            } catch (InvalidConfigException e) {
                combinedError = e.getMessage();
            }
        }
        if (runtime != null) {
            try {
                RuntimeConfig withoutRuntime = bind(layer(defaults, null, secrets));
                warnings.add(runtimeWarning(paths, combinedError));
                return result(withoutRuntime, null, true, warnings);
            } catch (InvalidConfigException ignored) {
                // 비밀값 쪽도 문제
            }
            if (secrets != null) {
                try {
                    RuntimeConfig withoutSecrets = bind(layer(defaults, runtime, null));
                    warnings.add(secretsWarning(paths, combinedError));
                    return result(withoutSecrets, runtime, false, warnings);
                } catch (InvalidConfigException ignored) {
                    // 둘 다 쓰지 않는다
                }
            }
            warnings.add(runtimeWarning(paths, combinedError));
            if (secrets != null) {
                warnings.add(secretsWarning(paths, combinedError));
            }
            return result(bind(defaults), null, true, warnings);
        }
        if (secrets != null) {
            warnings.add(secretsWarning(paths, combinedError));
        }
        return result(bind(defaults), null, runtimeInvalid, warnings);
    }

    private static LoadedConfig result(
            RuntimeConfig config, ObjectNode runtime, boolean runtimeInvalid, List<String> warnings) {
        ConfigSource source = runtime != null
                ? ConfigSource.RUNTIME_FILE
                : runtimeInvalid ? ConfigSource.DEFAULT_FILE_INVALID : ConfigSource.DEFAULT_NO_FILE;
        return new LoadedConfig(config, source, String.join("; ", warnings));
    }

    private static ObjectNode layer(ObjectNode defaults, ObjectNode runtime, ObjectNode secrets) {
        ObjectNode merged = defaults.deepCopy();
        if (runtime != null) {
            merge(merged, runtime);
        }
        if (secrets != null) {
            merge(merged, secrets);
        }
        return merged;
    }

    private static int countAccountNumbers(ObjectNode secrets) {
        JsonNode numbers = secrets.path("real-trading").path("account-numbers");
        return numbers.isObject() ? numbers.size() : 0;
    }

    private static String runtimeWarning(ConfigPaths paths, String reason) {
        return paths.runtimeFile().getFileName() + "을(를) 쓰지 않고 default로 기동: " + reason;
    }

    private static String secretsWarning(ConfigPaths paths, String reason) {
        return secretsWarningPrefix(paths) + reason;
    }

    /** secrets.json을 버렸다는 경고의 앞부분. load()가 secrets.json 사용 여부를 이것으로 판단하므로 문구를 한곳에 둔다. */
    private static String secretsWarningPrefix(ConfigPaths paths) {
        return paths.secretsFile().getFileName() + "을(를) 쓰지 않고 비밀값 없이 기동: ";
    }

    /** 내장 default만 읽는다. 내장 파일이 깨진 것은 빌드 오류이므로 예외. */
    public RuntimeConfig defaults() {
        return bind(readDefaults());
    }

    private ObjectNode readObject(Path file) throws IOException {
        JsonNode node = mapper.readTree(file.toFile());
        if (!(node instanceof ObjectNode object)) {
            throw new InvalidConfigException("최상위가 JSON 객체가 아닙니다");
        }
        return object;
    }

    private static String describe(Exception e) {
        return e instanceof InvalidConfigException ? e.getMessage() : ConfigJson.describe(e);
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
            // 원인 예외는 붙이지 않는다: Jackson 메시지에 원문 값이 실릴 수 있다
            throw new InvalidConfigException(ConfigJson.describe(e));
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

    /** 메시지에 값이 아니라 경로와 규칙만 담는 설정 오류. */
    static final class InvalidConfigException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidConfigException(String message) {
            super(message, null, false, false);
        }
    }
}
