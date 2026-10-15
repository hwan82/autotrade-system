package com.autotrade.monitor;

import java.util.List;

/** 주기마다 스냅샷과 그 주기에 생긴 임계 이벤트를 받는다. 수집 스레드에서 불리므로 오래 붙잡지 않는다. */
@FunctionalInterface
public interface MonitorListener {

    void onSample(MetricSnapshot snapshot, List<ThresholdEvent> events);
}
