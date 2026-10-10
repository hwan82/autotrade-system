package com.autotrade.app.collector.archfixture;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
import com.autotrade.domain.order.OrderGateway;

/** ArchitectureTest 검증용: 수집 일괄 명령이 프로세스 설정(secrets.json 포함)을 읽고 주문 포트를 아는 위반. 운영 코드가 아니다. */
public final class LoadsProcessConfig {

    private LoadsProcessConfig() {}

    static LoadedConfig load() {
        return new ConfigLoader().load(ConfigPaths.defaultLocation());
    }

    static Class<?> port() {
        return OrderGateway.class;
    }
}
