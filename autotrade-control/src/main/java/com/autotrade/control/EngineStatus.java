package com.autotrade.control;

import java.util.List;

/**
 * 실전 엔진 상태 스냅샷(UI-06: UI는 이 스냅샷만 읽는다).
 *
 * @param passwordConfigured 운영자 비밀번호 해시가 설정돼 있는지(값은 내보내지 않는다)
 * @param lockedUntil 연속 실패로 2차 확인이 잠긴 경우 풀리는 시각(UTC ISO). 잠기지 않았으면 null
 * @param accounts 실전 계좌별 상태(설정 순서)
 * @param configWarning 엔진이 기동할 때 runtime-config.json·secrets.json을 버렸거나 일부를 무시한 이유(ConfigLoader 경고: 파일 이름,
 *     JSON 경로와 규칙만, 값은 없음). 문제가 없으면 빈 문자열. 파일을 버리면 실전 계좌·비밀번호 해시도 같이 빠지므로 운영 화면이
 *     계좌 상태와 함께 보인다(1단계 QA 권고 R3). 이 필드가 없는 옛 엔진의 응답은 빈 문자열로 읽는다.
 *     {@value #MAX_CONFIG_WARNING}자를 넘으면 자르고 {@link #TRUNCATED_MARK}를 붙인다: 모든 명령 응답이 상태를 싣는데
 *     한 메시지는 {@link ControlCodec#MAX_BYTES}를 넘을 수 없어, 긴 경고가 상태 조회·내리기·PANIC 응답을 모두 끊지 않게 한다
 *     (PR #100 QA Q1)
 */
public record EngineStatus(boolean passwordConfigured, String lockedUntil, List<AccountStatus> accounts,
        String configWarning) {

    /** 설정 경고 최대 길이(UTF-16 문자 수). 한글 3바이트로 쳐도 응답 한도의 10분의 1이다. */
    public static final int MAX_CONFIG_WARNING = 2_000;
    /** 잘린 경고 끝에 붙는 표시. */
    public static final String TRUNCATED_MARK = " …(잘림, 전체는 실전 엔진 로그)";

    public EngineStatus {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        configWarning = configWarning == null ? "" : configWarning;
        if (configWarning.length() > MAX_CONFIG_WARNING) {
            int end = MAX_CONFIG_WARNING;
            if (Character.isHighSurrogate(configWarning.charAt(end - 1))) {
                end--;
            }
            configWarning = configWarning.substring(0, end) + TRUNCATED_MARK;
        }
    }

    /** 설정 경고가 없는 상태. */
    public EngineStatus(boolean passwordConfigured, String lockedUntil, List<AccountStatus> accounts) {
        this(passwordConfigured, lockedUntil, accounts, "");
    }

    /**
     * 실전 계좌 하나.
     *
     * @param id 계좌 별칭(계좌번호 아님)
     * @param broker 증권사
     * @param market 시장
     * @param state Guard 상태(OFF, ACTIVATING, OPEN, PROTECTIVE)
     * @param completedSteps 활성화 중 통과한 단계
     * @param accountNumberConfigured 계좌번호가 secrets.json에 있는지(값은 내보내지 않는다)
     */
    public record AccountStatus(
            String id, String broker, String market, String state, List<String> completedSteps,
            boolean accountNumberConfigured) {

        public AccountStatus {
            completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        }
    }
}
