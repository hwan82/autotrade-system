package com.autotrade.app.lab.monitor;

import com.autotrade.monitor.MonitorAgent;

/** 설정으로 감시 에이전트를 만든다. 시험이 수집원 생성 실패를 주입하는 자리다(risk-ops PR #114 R1). */
@FunctionalInterface
public interface MonitorAgentFactory {

    MonitorAgent create(MonitorSettings settings);
}
