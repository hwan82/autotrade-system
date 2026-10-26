package com.autotrade.realstore;

import java.io.IOException;

/** 엔진 저장소를 열 수 없어 엔진이 기동하지 않는다(쓰기 잠금을 못 잡음, 심볼릭 링크 등, ADR-015 2.7 ②·2.3). */
public final class StoreRefusal extends IOException {

    private static final long serialVersionUID = 1L;

    public StoreRefusal(String message) {
        super(message);
    }
}
