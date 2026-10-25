# architect 검토: 실전 제어 명령 채널 보강 — 7단계 묶음 A C1~C12·C15 (D-142) [실전 관련]
- 대상: PR #240 / 커밋 6344545c(4fa9f110, 16326b2a, 6344545c) / `git diff origin/main...HEAD` 전체(37개 파일, +2605 −561)
- 점검표: B
- 직접 실행한 것:
  - 공유 작업 폴더가 검토 중에 다른 작업으로 바뀌어(커밋 전 `OperatorAuthenticator` 변경), 별도 `git worktree`를 PR 머리 커밋에 두고 실행했다.
  - `./gradlew build --continue` (6344545c): **BUILD SUCCESSFUL**. 시험 결과 XML 합계 1,988건, 실패·오류·건너뜀 0. 그중 `autotrade-architecture-test` 46건(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest 등), `autotrade-app-engine` 96건(EngineCommandHandlerTest·EngineSelfCheckTest·EngineMainTest·OperatorAuthenticatorTest 포함), `autotrade-control` 29건, `autotrade-risk` 57건.
  - 참고: 16326b2a에서 `--offline`으로 돌린 첫 빌드는 실험실 시험 의존성(objenesis) 캐시가 없어 멈췄다(코드 문제 아님). 6344545c는 온라인으로 다시 돌렸다.
  - 검토용 임시 시험 `ArchProbeR1Test`(커밋하지 않음, 실행 뒤 지움)로 아래 R1을 재현했다: 출력 `PROBE final state=OPEN`, PANIC 응답의 6단계 문구는 "보호 모드를 유지합니다".

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 6344545c에서 직접 `./gradlew build --continue` 성공, 1,988건 실패 0 |
| B2 | 통과 | 순수 모듈 금지 의존 검사·ArchUnit 통과. 새 인터페이스 `ControlHandler`는 `autotrade-control`(순수)에 있고 Spring 의존 없음. 규칙 변경(`ONLY_COMMAND_CHANNEL_RAISES_GUARD`에 `confirmationIssuer`·`Issuer` 추가, `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`에 발급기 접근 추가, `SECOND_CONFIRMATION_IS_NOT_DATA`에 `Issuer` 추가, `GUARD_RECORDS`에서 `SecondConfirmation` 제거)은 ADR-006 2.4·5장과 D-142 C12에 적혀 있다. 고정물 개수 6→8, 2→4는 고정물 코드와 맞다(ArchitectureTest.java:497-522) |
| B3 | 통과 | 계약 변경: `ControlHandler`(새 처리기 계약, 받은 시각), `EngineStatus.AccountStatus.lastResult`(응답 필드 추가, 옛 응답 호환 생성자), `PanicSteps.PENDING`, 알림 종류 4개(EventRoutes), `MAX_ITERATIONS` 범위 변경(config-keys.md). ADR-006 개정(2.2·2.4·2.8·3장 E·4·5·6장)과 D-142, config-keys.md 갱신이 있다 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 실전 엔진 기한은 주입된 단조 시계(`LongSupplier ticker`)로 재고 시험이 바꿔 끼운다(EngineCommandHandler.java:117,618-624) |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 실패 | 새 동작 시험은 많지만(EngineCommandHandlerTest 33건, ControlChannelTest 29건, EngineSelfCheckTest 6건, Guard 발급기 2건), **ACTIVATE가 2차 확인을 통과한 뒤 `beginActivation` 전에 PANIC이 들어오는 순서**를 다루는 시험이 없고 그 순서에서 결함이 있다(R1). 하트비트 두 엔진 검사는 시험이 파일을 직접 써서 확인하고 두 기록기가 같은 파일을 덮어쓰는 실제 모양은 다루지 않는다(R2). 건너뛰거나 지운 시험은 없다(기존 STALE·BUSY 서버 시험 4건은 처리기 시험으로 옮김, ADR-006 5장) |
| B7 | risk-ops 검토 필요 | RealTradingGuard·SecondConfirmation·명령 처리기·보호 테스트(ArchUnit 규칙·고정물·RealPathReflectionTest)를 바꾼 실전 관련 PR. `docs/reviews/pr-240/risk-ops.md`가 따로 있다(이 검토는 그 내용을 보지 않고 판정) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(직접 grep). `real-config-changed` 알림·`lastResult`·하트비트 알림에 값 없음(EngineSelfCheck.java:188-200, EngineCommandHandler.java:641-644). `Secret.equals`는 값 비교라 5초마다 오알림하지 않음(Secret.java:53) |

## 점검표 밖 발견

### R1 (수정 필요) ACTIVATE를 먼저 받고 PANIC을 뒤에 받았는데, 청산이 끝나지 않으면 계좌가 OPEN으로 끝난다
- 순서: ① ACTIVATE 연결이 상태 검사(OFF/PROTECTIVE)를 지나 2차 확인(해시) 중(EngineCommandHandler.java:214-229). ② PANIC_ACCOUNT가 들어와 1단계 `lock()`을 한다. 계좌가 아직 OFF/PROTECTIVE라 **SKIPPED**(516-529). PANIC의 검증은 공정 잠금으로 ACTIVATE 뒤에 선다. ③ ACTIVATE가 `beginActivation`(239) → ACTIVATING, 계좌 작업 스레드에 활성화 작업을 넣는다(248). ④ PANIC 검증이 끝나 `liquidation()`이 ACTIVATING을 보고 같은 계좌 작업 스레드 뒤에 청산을 넣거나(405-411), 앞의 예처럼 PROTECTIVE에서 청산이 먼저 돌고 활성화가 뒤에 선다. ⑤ 청산이 끝나지 않으면 `afterLock`은 `keepProtective`로 "보호 모드를 유지합니다"라고 적기만 하고 Guard를 내리지 않는다(554-559). ⑥ 활성화 단계가 통과하면 OPEN.
- 결과: 더 늦게 받은 내리는 명령(PANIC)이 앞서 받은 올리는 명령에 덮인다. 응답과 `lastResult`는 "보호 모드 유지"인데 실제는 OPEN이라 신규 진입이 허용된다. 서버 측 손절을 취소한 뒤라면(긴급 표시가 나간 경우) 더 위험하다.
- 재현: 임시 시험(PROTECTIVE 계좌, ACTIVATE 스레드를 `beginActivation` 직전에 세우고 PANIC 보냄, 청산은 SELL FAILED) → 최종 `OPEN`. 지금은 `ActivationStepRunner.BROKER_NOT_CONNECTED`라 활성화가 늘 실패해 운영에서 일어나지 않지만, 묶음 E에서 실제 단계가 붙으면 그대로 열린다.
- 이전 코드(단일 명령 스레드)에서는 ACTIVATE가 끝까지 처리된 뒤 PANIC `lock()`이 OPEN → PROTECTIVE로 내렸으므로 **이 PR이 만든 회귀**다.
- 고칠 방향(택1 이상): (가) 청산 작업 시작 때(`afterLock` 첫 줄, 계좌 작업 스레드 안) 1단계 `lock()`을 다시 해서 그 사이 시작된 활성화를 내린다. (나) `beginActivation` 직전에 그 계좌의 청산이 돌고 있거나 PANIC 뒤라면 거절한다(`liquidations` 확인, 계좌별 PANIC 세대 번호). (다) 청산 결과가 미완료일 때 `keepProtective`가 실제 Guard 상태를 보고 OPEN·ACTIVATING이면 내린다. 어느 쪽이든 위 순서의 합격 시험을 추가한다. 실전 경로라 risk-ops와 방법을 맞춘다.

### R2 (수정 필요) 두 엔진이 같은 하트비트 파일을 덮어써, 실행 중 두 엔진 검사가 "5초 안에"를 보장하지 못한다
- 두 엔진 모두 `heartbeat/engine.hb` 한 파일에 쓴다(`Heartbeat.file(configDir, PROCESS)`). 검사는 그 파일을 읽어 다른 PID가 보일 때만 반응한다(EngineSelfCheck.java:98-125, 156-179).
- 기록기는 기동 즉시 쓰고 5초 고정 지연(HeartbeatWriter.java:68), 자가 확인은 기동 뒤 5초부터 5초 고정 지연(EngineSelfCheck.java:135, EngineMain에서 기록기 바로 다음에 시작). 두 일정의 위상 차가 기동 순간 몇 밀리초로 정해진다. 검사가 자기 기록 **직후**에 오는 위상이면, 다른 엔진이 그 몇 밀리초 사이에 썼을 때만 보인다. 두 일정의 주기는 각 작업 시간만큼 서로 다르게 늘어나 위상이 천천히 움직이므로 결국 보이지만, 걸리는 시간은 정해지지 않는다.
- 그래서 ADR-006 4장 위험 표와 D-142 "남은 위험"의 "늦게 뜬 엔진이 5초 안에 응답을 멈추고 계좌를 내린다"는 코드로 뒷받침되지 않는다. EngineSelfCheckTest는 시험이 하트비트 파일을 직접 써서(EngineSelfCheckTest.java:54-57) 이 덮어쓰기 경쟁을 다루지 않는다. (감시 프로세스는 PID가 번갈아 바뀌어 재기동 긴급 알림을 낼 수 있어 사람이 아는 길은 따로 있을 수 있다 `[추론, 미확인]`.)
- 고칠 방향(택1): 기록기가 덮어쓰기 **직전에** 읽어 다른 살아 있는 PID를 보면 알리게(검사를 쓰기와 같은 스레드·같은 순간에), 또는 연속 두 번 읽은 값의 PID·seq가 자기 것이 아니게 바뀌었는지로 판단, 또는 PID별 파일. 시험은 실제 `HeartbeatWriter` 두 개로 "N초 안에 늦은 쪽 silence"를 확인한다. 고치지 않을 거면 ADR-006 4장·D-142의 "5초 안에" 문구를 실제 보장으로 고친다.

### 참고 (판정에 넣지 않음)
- N1: 응답을 멈춘 늦은 엔진(`silence`)은 소켓을 계속 들고 연결을 받아 닫는다(ControlServer.java:278-281). 그 소켓이 현재 파일이면 먼저 뜬 엔진의 자가 확인은 `SOCKET_TAKEN`으로 빼앗지 않으므로(411-420) 화면은 "확인 불가"이고 PANIC은 먼저 뜬 엔진에 닿지 않는다(3-R 합격 기준의 "확인 불가" 분기에는 맞음). `silence`가 듣기 소켓을 닫으면(파일은 남김) 먼저 뜬 엔진이 1초 안에 다시 만들어 화면 명령이 첫 엔진에 닿는다. 묶음 I 3-R 시행 전에 검토 권장.
- N2: `iAmLate`는 `startedAt`이 같으면 둘 다 "먼저"로 본다(EngineSelfCheck.java:163). 밀리초 아래 같은 시각은 드물지만 PID로 결정을 깨면 비용이 없다.
- N3: `EngineMain.start`에서 하트비트·자가 확인을 시작한 뒤 예외가 나면 catch가 그 둘은 닫지 않는다(서버·처리기·알림만). 데몬 스레드라 프로세스 종료로 정리되지만 순서대로 닫는 편이 맞다.
- N4: `SecondConfirmation.Issuer.owner`는 "표시용"이라 했지만 쓰는 곳이 없다(toString도 쓰지 않음). 인자 없는 생성자를 피하려는 목적이면 주석을 그렇게 고친다.
- N5: 자가 확인이 5초마다 `ConfigLoader.load`로 전체 설정·비밀값을 다시 읽는다. 설정 경고가 있는 파일이면 매번 같은 경고 로그가 남는지 확인 권장 `[미확인]`.
- 6344545c(ReentrantLock → 공정 Semaphore)는 동작이 같다(허가 1개, 공정, 끼어들기 없는 대기, `finally` 해제). 재진입이 필요한 호출 경로 없음(verify 안에서 verify 호출 없음). 문제 없음.

## 판정
수정 필요(R1: ACTIVATE 확인 중 들어온 PANIC이 청산을 끝내지 못하면 계좌가 OPEN으로 끝나는 회귀와 그 합격 시험, R2: 두 엔진 하트비트 검사의 "5초 안에" 보장 — 코드로 보장하거나 문서를 실제와 맞춤)
