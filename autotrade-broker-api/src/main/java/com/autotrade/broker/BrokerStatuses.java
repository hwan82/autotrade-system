package com.autotrade.broker;

import com.autotrade.marketdata.ConnectionState;

/** 시세 연결 상태에서 플러그인 상태를 정한다(broker-and-fault-design.md 3.1, ADR-002 2.1). */
public final class BrokerStatuses {

    private BrokerStatuses() {}

    /**
     * 키가 없으면 NOT_CONFIGURED. 연결되어 인증까지 끝났으면 ACTIVE. 끊겨 재연결을 기다리면 DEGRADED.
     * 아직 처음 연결 전이거나 닫혔으면 CONFIGURED.
     */
    public static BrokerStatus fromConnection(boolean configured, ConnectionState state) {
        if (!configured) {
            return BrokerStatus.NOT_CONFIGURED;
        }
        return switch (state) {
            case CONNECTED -> BrokerStatus.ACTIVE;
            case DISCONNECTED -> BrokerStatus.DEGRADED;
            case NEW, CONNECTING, CLOSED -> BrokerStatus.CONFIGURED;
        };
    }
}
