package com.autotrade.sim.run;

import java.util.Objects;

/** 실행한 코드 버전(SIM-10). 실험실 프로세스가 빌드 정보에서 만들어 주입한다. */
public record CodeVersion(String commit, boolean dirty) {

    public CodeVersion {
        Objects.requireNonNull(commit, "commit");
        if (commit.isBlank()) {
            throw new IllegalArgumentException("커밋이 비었습니다");
        }
    }
}
