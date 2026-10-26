package com.autotrade.app.engine.command;

import com.autotrade.control.ControlCommand;
import com.autotrade.realstore.EngineStore;
import java.util.Objects;

/**
 * 명령 감사를 앞선 감사(프로세스 로그·긴급 알림)에 남기고, 엔진 저장소가 열린 뒤에는 공통 {@code audit/commands} 원장에도 남긴다
 * (ADR-015 2.2, ADR-006 2.5, D-146). 원장 기록은 기다리지 않으므로 명령 처리 결과에 영향을 주지 않는다. 명령 채널은 저장소보다
 * 먼저 열리므로(잠금 순서 engine.lock → writer.lock) 그 사이의 명령은 프로세스 로그에만 남는다. 그 사이에는 올리는 전이도 모두 거절된다.
 */
public final class StoreCommandAuditLog implements CommandAuditLog {

    private final CommandAuditLog delegate;
    private volatile EngineStore store;

    public StoreCommandAuditLog(CommandAuditLog delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** 저장소가 열렸다. 한 번만 붙인다. */
    public void attach(EngineStore opened) {
        if (store != null) {
            throw new IllegalStateException("엔진 저장소는 한 번만 붙입니다");
        }
        store = Objects.requireNonNull(opened, "opened");
    }

    @Override
    public void record(ControlCommand command, String target, String code) {
        try {
            delegate.record(command, target, code);
        } finally {
            EngineStore s = store;
            if (s != null) {
                s.recordCommand(command.name(), target, code);
            }
        }
    }
}
