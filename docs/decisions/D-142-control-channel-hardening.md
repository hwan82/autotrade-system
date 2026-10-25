# D-142 실전 제어 명령 채널 보강(7단계 묶음 A: C1~C12, C15 시험)

- 날짜: 2026-10-24
- 상태: 확정. 아래 결정 1~6은 **추천안 채택(자율)**(지침 8장, 각 역할 추천안). 실전 경로를 건드리는 PR이라 risk-ops·architect 검토와 독립 QA 뒤 **사용자가 직접 머지**한다(지침 10장). 머지가 이 기록의 최종 확인이다
- 근거: [7단계 준비 계획](../team/stage-7-readiness-plan.md) 2.1장(C1~C15)·3장 묶음 A, [ADR-006](../adr/ADR-006-real-control-channel.md) 4·6장 완료 조건, [D-140](D-140-stage7-panic-and-first-broker.md) ③(장애 테스트 3-R)과 후속(C6은 다시 만들기가 기본), QA [pr-18](../reviews/pr-18/qa.md) 1~5
- 담당: risk-ops(주관), architect(명령 채널·프로세스 수명)
- 바꾼 문서: ADR-006(2.2·2.4·2.8 새 절·3장 E·4·5·6장), [config-keys.md](../config/config-keys.md)(해시 반복 범위, 실전 설정 변경 알림)

## 무엇을 했나

| 항목 | 내용 | 코드 |
|---|---|---|
| C1 | 활성화 단계를 계좌 작업 스레드에서 돌린다. 응답 기한(12초) 안에 끝나지 않으면 "진행 중"으로 답하고 단계는 계속 돈다. 결과는 상태 스냅샷 `lastResult`와 감사 기록(`OPEN`·`ACTIVATION_FAILED`·`STALE`)으로 남는다. 화면은 "진행 중"과 마지막 결과를 보인다(단계별 진행 표시는 묶음 H) | `EngineCommandHandler`, `EngineStatus.AccountStatus.lastResult`, `real.js` |
| C2 | PANIC 청산을 계좌마다 따로 돌린다(계좌끼리 서로 기다리지 않음). 응답 기한 안에 끝나지 않은 계좌는 단계 결과 `PENDING`. 같은 계좌 청산이 돌고 있으면 새로 시작하지 않는다 | `EngineCommandHandler`, `PanicSteps.PENDING` |
| C3 | 절대 기한: 연결 받은 시각부터 ① 활성화 시작 10초(확인 앞뒤), ②③ 활성화 전체 60초(각 단계 직전·통과 직전). 넘으면 출발 상태와 `STALE`. 서버의 대기열 기준 `STALE`(10초)은 없앴다 | `ControlHandler`, `ControlServer`, `EngineCommandHandler.Limits` |
| C4 | 해시 계산은 공정한 검증 잠금 안에서 한 번에 하나, 실패 횟수·잠금 시각은 따로 잠근다. `MAX_ITERATIONS` 10,000,000 → 2,000,000. 검증 결과는 명령마다 따로 드는 `Verification` | `OperatorAuthenticator`, `OperatorPasswordHash` |
| C5 | 서버는 연결마다 바로 처리기를 부른다(단일 명령 스레드·대기열 없앰). 처리기는 응답 기한까지 기다리는 연결을 6개로 묶어 새 연결 2개 자리를 남긴다. 연결 상한(8)에 닿으면 1분에 한 번 긴급 알림 | `ControlServer`, `EngineCommandHandler` |
| C6 | 1초마다 run 폴더·잠금 파일·소켓이 자기 파일인지 확인하고, 지워졌으면 다시 만들고 알린다. 다른 프로세스가 잡았으면 빼앗지 않고 알린다. 기동 전에 하트비트로 다른 살아 있는 엔진을 확인해 거부하고, 실행 중 두 엔진이 보이면 늦게 뜬 엔진이 응답을 멈추고 계좌를 내린다 | `ControlServer`, `EngineSelfCheck`, `EngineMain` |
| C7 | 닫을 때: 새 활성화 거절, ACTIVATING 계좌 출발 상태로, 계좌 작업·진행 중 연결을 최대 5초 기다림, 소켓은 자기 파일일 때만 지움 | `EngineCommandHandler.close`, `ControlServer.close`, `EngineMain.Engine` |
| C8 | 잠금 파일 `NOFOLLOW_LINKS`, PID 먼저 쓰고 자른 뒤 force, PID 쓰기 실패 시 잠금 파일 지우고 거부. `hash-password` 안내에 "실전 엔진과 실험실을 모두 다시 시작" | `ControlServer`, `EngineMain` |
| C9 | `isListening` 시험 연결 1초 상한(넘으면 듣는 쪽이 있다고 봄) | `ControlServer` |
| C10 | 2차 확인이 잠기는 순간 긴급 알림 `operator-locked`. 잠긴 동안 청산 경로는 Q-059 ①(D-140, 묶음 F) | `OperatorAuthenticator`, `EngineMain`, `EventRoutes` |
| C11 | 실전 설정(계좌·계좌번호·해시)이 바뀌면 5초 안에 긴급 알림 `real-config-changed`(값 없음). 실전 계좌가 있는 엔진 기동은 긴급 `engine-started-real`. 실전 활성화·PANIC 결과는 기존 `real-trading-command` 긴급 알림으로 나간다 | `EngineSelfCheck`, `EngineMain`, `EventRoutes` |
| C12 | `SecondConfirmation`을 레코드에서 private 생성자 클래스로. Guard가 한 번만 내주는 발급기로만 만들고, Guard는 자기 발급기의 확인만 받는다. 검증기가 생성 때 발급기를 가져간다 | `SecondConfirmation`, `RealTradingGuard.confirmationIssuer`, ArchUnit 규칙·고정물 |
| C15 | 장애 테스트 3-R 시험: 실행 중 run 폴더 삭제 → 첫 엔진이 다시 만들고, 두 번째 엔진 거부, 화면 명령은 첫 엔진에(OFF, 가상 표시 없음). 하트비트 기동 거부 시험 | `EngineMainTest`, `ControlChannelTest`, `EngineSelfCheckTest` |

## 결정(추천안 채택(자율))

| # | 질문 | 결정 | 버린 대안과 이유 | 되돌릴 조건 |
|---|---|---|---|---|
| 1 | C3 절대 기한 방식 (가)/(나) | **(나) 엔진 고정 기한**. ① 10초, ②③ 60초, 응답 12초. 규칙: 요청 읽기 상한 5초 ≤ ① < 실험실 대기 상한 15초, 응답 기한 < 15초 | (가) 실험실이 기한을 실어 보냄: 두 프로세스 시계 맞춤, 명령 형식·ADR 개정 필요 | 실험실 대기 상한이나 요청 읽기 상한이 바뀌면 다시 계산. 증권사 단계가 60초를 자주 넘으면 ②③을 늘리되 Guard 확인 유효 시간(60초)과 함께 본다 |
| 2 | C4 `MAX_ITERATIONS` | **2,000,000**. 기본 600,000 그대로, 범위 밖이면 미설정 | 10,000,000 유지: 직접 시험 약 10.8초(QA pr-18 2)로 ① 10초를 넘음 | 운영 PC에서 2,000,000회 검증이 5초를 넘으면 낮춘다 `[가정: 리눅스 컨테이너에서 수백 밀리초~2초]` |
| 3 | C5 감시 프로세스의 연결 포화 점검 | **엔진이 알린다**(`control-channel`). 감시는 다른 프로세스의 소켓 연결 수를 볼 수 없고, 감시가 소켓에 시험 연결을 하면 자리를 하나 더 쓴다 | 감시가 시험 연결: 포화를 오히려 늘리고 감시가 명령 채널에 의존(D-013 분리 원칙과 충돌) | 감시가 엔진 내부 지표를 읽는 경로(MON)가 생기면 감시 화면에도 보인다 |
| 4 | C6 지워진 잠금·소켓 | **다시 만들기가 기본**(D-140 후속), 남이 잡은 자리는 빼앗지 않음, 하트비트로 기동·실행 중 두 엔진 검사. "지울 수 없는 단일 실행"(OS 잠금 객체)은 쓰지 않음 | 알리기만: 그동안 화면이 가상으로 보이고 PANIC이 닿지 않음. OS 잠금 객체: OS별 코드와 JNI·외부 라이브러리 필요(실전 경로 리플렉션·외부 jar 금지, D-086) | 3-R을 Windows 운영 PC에서 시행했을 때 합격하지 못하면 OS별 방식을 다시 본다 |
| 5 | C11 해시를 엔진 전용 파일로 분리 | **분리하지 않고 변경 감지 알림** | 분리: 같은 OS 사용자면 두 파일 모두 고칠 수 있어 막는 효과가 같고 설정 화면·config-keys·실험실 검증이 모두 바뀜 | 실험실이 secrets.json을 쓰지 않는 설계가 생기면 다시 본다 |
| 6 | C12 확인 생성 제한 방식 | **Guard가 한 번 내주는 발급기** | 패키지 전용 생성자: 같은 이름 패키지로 우회 가능하고 Guard 패키지 밖 검증기가 만들 수 없음. ArchUnit만으로 유지: 컴파일러가 막지 못함 | 검증기 외 다른 확인 수단(Q-059 ② 등)이 생기면 발급기를 나눠 줄지 새 결정 |

## 남은 위험과 한계
- 하트비트 검사는 PID와 시각을 믿는다. PID 재사용이면 두 번째 기동을 잘못 막는다(안전한 쪽). 하트비트 폴더까지 지우고 1초 안에 두 번째 엔진을 띄우면 두 엔진이 최대 5초 같이 돌 수 있고, 그때 늦게 뜬 엔진이 응답을 멈추고 계좌를 내린다. ADR-006 4장.
- 같은 계좌의 활성화 단계가 길면 그 계좌의 PANIC 청산은 기다린다(PANIC 1단계는 즉시). 단계 하나의 시간 상한은 묶음 E의 증권사 클라이언트 타임아웃이 묶어야 한다.
- 3-R 합격 조건 중 "두 번째 엔진으로 인한 증권사 토큰 무효화·재발급 없음"은 지금 엔진이 토큰을 발급하지 않아 저절로 맞는다. 묶음 E는 **기동 검사(잠금·하트비트)를 통과한 뒤에만** 토큰을 발급하고, 응답을 멈춘 엔진(`silence`)은 토큰을 새로 발급하지 않게 해야 한다(묶음 E 완료 조건).
- 응답을 멈춘 늦은 엔진은 스스로 종료하지 않는다. 사람이 끈다(알림 문구에 적음).
- 검증하지 못한 것: 컨테이너에서 Gradle을 돌리지 못해 로컬 javac 하네스로 risk·control·notification·monitor·config·domain·app-engine 시험(377건)과 ArchUnit 규칙 위반 수를 따로 확인했다. 전체 빌드(ArchUnit 전체, 실험실 시험, 화면 시험)는 CI 결과로 확인한다. Windows·macOS에서 파일 키 비교와 하트비트 PID 확인은 미확인(3-R 시행 때 확인, I1).

## 후속
- 묶음 E: 토큰 발급 시점(위), `ActivationStepRunner`·`PanicLiquidator` 실제 구현은 계좌 작업 스레드에서 돈다는 전제와 응답 기한 뒤 결과 기록을 지킨다.
- 묶음 H: 단계별 활성화 진행 표시(C1 화면 부분).
- 묶음 I(I1 절차서): 3-R 시행 단계(run 폴더 삭제, 하트비트 폴더까지 삭제, 늦게 뜬 엔진 응답 멈춤 확인, 알림 수신 확인).
