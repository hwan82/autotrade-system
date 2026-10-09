package com.autotrade.broker.fixturea;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.fixtureb.PluginB;

/** ArchUnit 위반 픽스처: 다른 플러그인 패키지를 참조한다. 공통 계약(broker-api) 참조는 허용. */
public final class PluginA {
    final BrokerCredentials allowed = BrokerCredentials.none();

    String borrow() {
        return PluginB.helper();
    }
}
