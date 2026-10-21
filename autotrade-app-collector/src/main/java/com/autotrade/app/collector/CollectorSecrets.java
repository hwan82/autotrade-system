package com.autotrade.app.collector;

import com.autotrade.config.Secret;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 수집기 전용 비밀 파일 {@code <설정 폴더>/collector-secrets.json}(D-050 ⑤, D-051, ADR-008 2.2).
 *
 * <pre>
 * {"krx": {"auth-key": "&lt;KRX Open API 인증키&gt;"}, "opendart": {"crtfc-key": "&lt;OpenDART 인증키&gt;"},
 *  "kis-news": {"app-key": "&lt;한국투자 앱키&gt;", "app-secret": "&lt;한국투자 앱시크릿&gt;"}}
 * </pre>
 *
 * <p>4개 프로세스용 {@code secrets.json}·{@code runtime-config.json}과 다른 파일이다. 수집기는 그 두 파일을 열지 않고,
 * 4개 프로세스는 이 파일을 모른다. 모르는 키가 있으면 파일 전체를 거부한다(오타·증권사 키를 잘못 넣은 경우를 조용히
 * 넘기지 않는다). 값은 {@link Secret}로만 들고 다녀 문자열로 바뀌어도 가려진다.
 *
 * <p>{@code kis-news}(D-136)는 한국투자 뉴스 제목 조회 한 가지에만 쓰는 앱키다. 계좌번호 칸은 받지 않는다. 실전 계좌용 앱키와
 * 다른 앱키를 넣는 것을 권한다(runbook collector.md 3.9).
 */
public record CollectorSecrets(Secret krxAuthKey, Secret openDartKey, Secret kisNewsAppKey, Secret kisNewsAppSecret) {

    public static final String FILE_NAME = "collector-secrets.json";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "krx", Set.of("auth-key"),
            "opendart", Set.of("crtfc-key"),
            "kis-news", Set.of("app-key", "app-secret"));

    /** 파일이 없으면 모든 값이 빈 값. 형식이 틀리면 예외(값은 메시지에 넣지 않는다). */
    public static CollectorSecrets load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new CollectorSecrets(Secret.empty(), Secret.empty(), Secret.empty(), Secret.empty());
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(file.toFile());
        } catch (IOException e) {
            // 파서 메시지에 값 일부가 실릴 수 있어 원인 메시지를 옮기지 않는다
            throw new IllegalStateException(FILE_NAME + "을 JSON으로 읽을 수 없습니다");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(FILE_NAME + " 최상위는 객체여야 합니다");
        }
        for (Map.Entry<String, JsonNode> section : root.properties()) {
            Set<String> keys = ALLOWED.get(section.getKey());
            if (keys == null || !section.getValue().isObject()) {
                throw new IllegalStateException(FILE_NAME + "에 모르는 항목이 있습니다: " + section.getKey());
            }
            for (Map.Entry<String, JsonNode> e : section.getValue().properties()) {
                if (!keys.contains(e.getKey()) || !e.getValue().isTextual()) {
                    throw new IllegalStateException(
                            FILE_NAME + "에 모르는 항목이 있습니다: " + section.getKey() + "." + e.getKey());
                }
            }
        }
        return new CollectorSecrets(
                Secret.of(root.path("krx").path("auth-key").asText("")),
                Secret.of(root.path("opendart").path("crtfc-key").asText("")),
                Secret.of(root.path("kis-news").path("app-key").asText("")),
                Secret.of(root.path("kis-news").path("app-secret").asText("")));
    }
}
