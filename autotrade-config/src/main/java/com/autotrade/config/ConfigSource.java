package com.autotrade.config;

/** 기동 시 설정을 어디서 읽었는지. 화면과 로그에 표시한다. */
public enum ConfigSource {
    /** 외부 runtime-config.json(내장 default 위에 덮어씀). */
    RUNTIME_FILE,
    /** runtime-config.json이 없어 내장 default만 사용. */
    DEFAULT_NO_FILE,
    /** runtime-config.json이 깨져 내장 default로 기동. 경고를 띄운다. */
    DEFAULT_FILE_INVALID
}
