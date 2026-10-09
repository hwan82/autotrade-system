package com.autotrade.control;

import java.util.List;

/**
 * 엔진의 응답(ADR-006 2.3). 요청 값(비밀번호, 확인 문구)은 담지 않는다.
 *
 * @param ok 명령이 받아들여졌는지. 활성화를 시작했지만 단계에서 실패한 경우도 true이고 결과는 {@code status}로 본다
 * @param code 결과 코드. 거절이면 {@link ControlCodes}의 값
 * @param message 사람이 읽을 설명(한국어)
 * @param status 명령 처리 후 상태 스냅샷
 * @param steps PANIC SELL 단계별 결과. 다른 명령은 빈 목록
 */
public record ControlResponse(
        boolean ok, String code, String message, EngineStatus status, List<PanicStepResult> steps) {

    public ControlResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static ControlResponse accepted(String message, EngineStatus status, List<PanicStepResult> steps) {
        return new ControlResponse(true, ControlCodes.OK, message, status, steps);
    }

    public static ControlResponse rejected(String code, String message, EngineStatus status) {
        return new ControlResponse(false, code, message, status, List.of());
    }
}
