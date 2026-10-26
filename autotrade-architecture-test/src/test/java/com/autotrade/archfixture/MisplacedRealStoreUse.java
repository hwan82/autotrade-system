package com.autotrade.archfixture;

import com.autotrade.realstore.EngineStore;

/** 위반 고정 예제: 실전 엔진 밖 코드가 실전 엔진 저장소를 쓴다(ONLY_ENGINE_USES_REAL_STORE). */
public final class MisplacedRealStoreUse {

    private MisplacedRealStoreUse() {}

    public static long boot(EngineStore store) {
        return store.boot();
    }
}
