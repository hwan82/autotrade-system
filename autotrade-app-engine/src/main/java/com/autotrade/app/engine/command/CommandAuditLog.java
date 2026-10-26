package com.autotrade.app.engine.command;

import com.autotrade.control.ControlCommand;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * 명령 채널 감사 로그(D-023 공통 원칙 2: 다시 열 때는 사람, 감사 로그). 상태 조회를 뺀 모든 명령의 시도와 결과를 남긴다.
 * 비밀번호와 확인 문구는 받지 않는다. Guard 전이 자체는 {@code GuardAuditLog}가 따로 남긴다.
 * {@link #LOGGING}은 프로세스 로그다. 실전 엔진은 그 위에 알림과 엔진 저장소 원장을 겹친다({@link StoreCommandAuditLog}, ADR-015).
 */
public interface CommandAuditLog {

    void record(ControlCommand command, String target, String code);

    CommandAuditLog LOGGING = new CommandAuditLog() {
        private final Logger log = System.getLogger("com.autotrade.engine.command-audit");

        @Override
        public void record(ControlCommand command, String target, String code) {
            log.log(Level.WARNING, "실전 제어 명령 {0} 대상 {1} 결과 {2}", command, target, code);
        }
    };
}
