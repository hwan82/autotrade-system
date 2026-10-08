package com.autotrade.config;

import java.util.Objects;

/**
 * 기동 시 로딩 결과.
 *
 * @param warning 파일을 못 쓴 이유(JSON 경로와 검증 메시지만, 값은 담지 않음). 문제가 없으면 빈 문자열
 */
public record LoadedConfig(RuntimeConfig config, ConfigSource source, String warning) {

    public LoadedConfig {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(source, "source");
        warning = warning == null ? "" : warning;
    }
}
