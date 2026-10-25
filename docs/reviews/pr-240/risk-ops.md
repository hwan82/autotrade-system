# risk-ops 검토: 실전 제어 명령 채널 보강: 7단계 묶음 A C1~C12·C15 시험 (D-142) [실전 관련]
- 대상: PR #240 / 브랜치 `claude/project-thread-uv9uq5` / 커밋 6344545c (4fa9f110, 16326b2a, 6344545c 포함) / `git diff origin/main...HEAD` 전체(37개 파일 + 6344545c의 `OperatorAuthenticator` 잠금 교체)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --continue` (6344545c): 컴파일·보호 테스트·ArchUnit·`verifyProcessIsolation` 통과. `:autotrade-app-lab:test`만 Maven Central 429(objenesis 내려받기 실패)로 의존성 해석 실패 → `./gradlew :autotrade-app-lab:test :autotrade-app-engine:test` 다시 실행해 BUILD SUCCESSFUL
  - 시험 결과(XML 집계, 실패·오류·건너뜀 0): autotrade-risk 57, autotrade-architecture-test 46, autotrade-app-engine 96, autotrade-control 29, autotrade-notification-jar 86, autotrade-app-lab 269
  - 임시 재현 시험(커밋하지 않고 지움, 아래 "점검표 밖 발견" R-1): `ZzRiskOpsProbeTest` — 활성화 요청의 2차 확인 도중 PANIC_ACCOUNT가 들어오는 순서. 결과 `stateAtLiquidation=[OPEN] final=OPEN`, PANIC 응답은 "신규 주문 차단 완료 … 보호 모드를 유지합니다". 단정(`최종 상태 != OPEN`) 실패
  - 처음 돌린 빌드 한 번은 같은 작업 폴더에서 6344545c가 들어오는 도중이라 `Semaphore` 기호 컴파일 오류가 났다. 6344545c 기준으로 다시 돌린 결과만 판정에 쓴다

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 초기 상태 변경 없음: `RealTradingGuard.java:353` `Snapshot.INITIAL = OFF`, 생성자 주석 62행. 새 발급기(`confirmationIssuer`, 298~303행)는 올리는 전이를 더 좁힐 뿐 OFF 기본값을 바꾸지 않음. `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다`, `#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다` 통과. `MAX_ITERATIONS` 하향(10,000,000→2,000,000)은 범위 밖 해시를 "미설정"으로 보게 해 올리는 쪽을 막는 방향 |
| A2 | 통과 | `ArchitectureTest.ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`(164행) 변경 없음, 고정 예제 위반 4건 그대로. `ONLY_COMMAND_CHANNEL_RAISES_GUARD`(178~186행)·`ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`(216~224행)은 `confirmationIssuer`·`SecondConfirmation.Issuer` 접근을 더 잡도록 넓어짐. `RealPathReflectionTest.SECOND_CONFIRMATION_IS_NOT_DATA`에 `Issuer` 의존 금지 추가. 전부 통과 |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 코드 변경 없음. 활성화 단계·PANIC 증권사 단계는 여전히 `ActivationStepRunner.BROKER_NOT_CONNECTED`·`PanicLiquidator.BROKER_NOT_CONNECTED`(`EngineMain.commandHandler`) |
| A4 | 해당 없음 | 주문 전송·타임아웃 처리 코드 변경 없음(묶음 E 범위) |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 판정 경로 변경 없음. PANIC 청산은 아직 증권사 미연동 스텁 |
| A7 | 통과 | 계좌마다 단일 스레드 작업 줄(`EngineCommandHandler` 118~119·155~160행)로 한 계좌의 활성화·청산은 차례로, 계좌끼리는 따로 돈다. `EngineCommandHandlerTest#전체_PANIC은_계좌끼리_기다리지_않고_끝나지_않은_계좌는_진행_중으로_답한다`, `#같은_계좌의_청산이_돌고_있으면_새로_시작하지_않는다` 통과. 주문 큐·토큰 버킷 코드 변경 없음 |
| A8 | 통과 | 프로세스 분리 변경 없음(`verifyProcessIsolation` 통과). 명령 채널은 연결 8개 상한·읽기 5초·대기 연결 6개 상한으로 실험실 쪽 연결 폭주가 엔진 스레드를 무한히 묶지 않음(`ControlServer` 230~247행, `EngineCommandHandler.await`). `ControlChannelTest` 29건 통과 |
| A9 | 실패 | PANIC의 "보호 모드 고정"이 그보다 먼저 받은 활성화 요청에 덮인다(R-1). 직접 재현: 활성화 요청이 2차 확인(해시 검증) 중일 때 PANIC_ACCOUNT가 1단계(OFF라 SKIPPED)를 끝내고 검증 잠금을 기다림 → 활성화가 `beginActivation` → OPEN → 청산은 계좌 작업 줄에서 **Guard OPEN 상태로** `liquidator.liquidate` 실행 → 매도 미완료 시 응답은 "보호 모드를 유지합니다(긴급)"인데 실제 최종 상태는 OPEN(신규 진입 허용). `EngineCommandHandler.activate` 228~242행(PANIC 이후 여부 확인 없음), `afterLock` 541~561행(청산 직전 보호 모드 재고정 없음), `keepProtective` 567~577행(실제 상태와 무관한 문구) |
| A10 | 통과 | diff에 API 키·계좌번호·토큰 없음. 시험 해시는 `OperatorPasswordHashTest`가 실행 때 `SecureRandom`으로 만듦. 새 알림(`operator-locked`, `real-config-changed`, `control-channel`, `engine-started-real`) 본문은 값 없이 PID·경로 이름·시각만 실음(`EngineMain` 156~161행, `EngineSelfCheck.checkRealConfig`) |
| A11 | 통과 | 보호 테스트 삭제·비활성화 없음. 고정 값 변경 3건 검토: ① `ArchitectureTest` 고정 예제 위반 수 6→8, 2→4는 새 금지 대상(발급기 가져오기·발급, 그 메서드 참조)이 늘어서이며 규칙 자체는 넓어짐 ② `RealPathReflectionTest.GUARD_RECORDS`에서 `SecondConfirmation` 제거는 레코드→private 생성자 클래스 전환(대상 코드 `SecondConfirmation.java`)에 따른 것으로, Jackson 생성 불가 전제는 더 강해짐 ③ 6344545c의 `ReentrantLock`→공정 `Semaphore`는 실전 경로 JDK 허용 목록을 넓히지 않고 코드를 목록 안으로 맞춘 것. 새 동작 시험에 실패 경우 포함(`RealTradingGuardTest#다른_Guard의_발급기로_만든_2차_확인은_거부한다`, `#발급기는_Guard마다_한_번만_내준다`, STALE 4종 등). 단 R-1 순서(활성화 확인 중 PANIC)를 다루는 시험이 없다 |
| A12 | 실패 | 장애 테스트 3-R 전제(broker-and-fault-design.md 6장 3번: "닿지 못하면 두 번째 엔진이 명령·상태에 응답하지 않고 화면이 확인 불가")를 채우는 실행 중 하트비트 검사가 실제로는 거의 발동하지 않는다(R-2, `[추론]`). 두 엔진이 같은 파일 `heartbeat/engine.hb`를 5초마다 덮어쓰고, 각 엔진의 `EngineSelfCheck`는 자기 하트비트를 쓴 직후 수 ms 뒤에 같은 주기로 읽는다(`EngineMain` 168~172행: `HeartbeatWriter.start`(첫 쓰기 지연 0) 바로 뒤 `selfCheck.start(INTERVAL)`(첫 확인 지연 5초), 둘 다 `scheduleWithFixedDelay` 5초). 그래서 읽을 때 마지막으로 쓴 쪽은 거의 늘 자기 자신이고(`otherEngine`이 자기 PID면 빈 값), 다른 엔진이 그 수 ms 틈에 쓴 경우만 감지된다. 이 분기에서 두 번째 엔진은 상태에 응답하고(모두 OFF) 화면 명령이 첫 엔진의 OPEN 계좌에 닿지 않는다. `EngineSelfCheckTest`는 하트비트 파일을 손으로 써 `check()`를 직접 불러 이 시간 관계를 시험하지 않는다 |
| A13 | 해당 없음 | 시장별 규칙·시간대·통화 계산 변경 없음. 새 기한은 단조 시계(`System.nanoTime`) 기준이라 시장 시간대와 무관 |
| A14 | 실패 | D-142·ADR-006이 바뀐 동작과 어긋나는 곳 2건: ① ADR-006 4장 표(178행)·D-142 "남은 위험"의 "같이 돌면 늦게 뜬 엔진이 5초 안에 응답을 멈추고 계좌를 내리며 양쪽 모두 긴급 알림"은 R-2 때문에 성립하지 않는다 ② ADR-006 2.3·`EngineCommandHandler` 클래스 주석의 PANIC 순서("보호 모드 고정 → … 끝나지 않으면 보호 모드에 남겨")가 R-1 순서에서 지켜지지 않는다. 기획서(`docs/spec/autotrade-spec.md`)는 바뀌지 않았고 RSK-13 서술과 충돌하는 새 규칙은 없음 |

## 점검표 밖 발견
- **R-1 (수정 필요, A9·A14)** 활성화와 PANIC의 순서. 단일 명령 스레드를 없애면서(C5) "먼저 받은 활성화가 나중에 받은 PANIC 뒤에 Guard를 올리는" 순서가 새로 생겼다. 이전 구조에서는 명령이 받은 순서대로 끝까지 처리돼 PANIC이 늘 마지막에 이겼다. 재현 순서: ① ACTIVATE 수신, 상태 OFF 확인, 해시 검증 시작(최대 약 2초, 공정 Semaphore 보유) ② PANIC_ACCOUNT 수신, 1단계 `lock`은 OFF라 건너뜀, 검증 대기 ③ ACTIVATE 검증 통과 → `beginActivation` → 계좌 작업 줄에서 단계 통과 → OPEN ④ PANIC 검증 통과 → `liquidation`: OFF가 아니므로 작업 줄에 청산을 넣음 → 활성화 뒤에 돌아 OPEN 상태에서 청산 ⑤ 매도가 끝나지 않으면 계좌는 OPEN으로 남고 응답·`lastResult`·감사 기록은 "보호 모드 유지"라고 말한다. PANIC 2차 확인이 틀린 경우에도 ③만으로 PANIC 뒤에 계좌가 OPEN이 된다. 고칠 방향(예): (가) 계좌별 PANIC 세대(또는 마지막 PANIC 수신 시각)를 두고, `beginActivation` 직전과 각 단계 통과 직전에 "이 활성화 요청보다 뒤에 받은 PANIC이 있으면" 출발 상태로 내리고 거절 (나) 같은 계좌의 청산이 돌고 있으면(`liquidations`에 끝나지 않은 작업) ACTIVATE를 `INVALID_STATE`로 거절 (다) `afterLock`이 작업 줄에서 청산 직전에 1단계(`lock`)를 다시 해 청산은 늘 PROTECTIVE에서 돌게 함 (라) `keepProtective` 문구는 실제 Guard 상태로 쓴다. 위 재현 순서를 시험으로 고정한다(활성화 확인 중 PANIC → 최종 OFF 또는 PROTECTIVE, 청산 시점 상태 PROTECTIVE).
- **R-2 (수정 필요, A12·A14)** 실행 중 두 엔진 감지. 두 엔진이 같은 하트비트 파일을 같은 주기로 덮어쓰고 각자 자기 쓰기 직후에 읽으므로 감지가 위상 운에 맡겨진다(`[추론]`, 근거 위 A12). 고칠 방향(예): 확인 주기를 하트비트 주기와 다르게 하고 쓰기 직전에 읽기(쓰기 전에 파일의 PID·seq가 마지막으로 자기가 쓴 값과 같은지 비교), 또는 프로세스별 파일(`engine-<pid>.hb`)을 두고 디렉터리를 훑기. 실제 `HeartbeatWriter` 두 개와 `EngineSelfCheck` 두 개를 돌려(PID를 주입할 수 있게) 시간 안에 늦게 뜬 쪽이 `silence`되는지 보는 시험을 넣는다. 고치지 못하면 ADR-006 4장과 D-142의 "5초 안에" 서술을 실제 보장 수준으로 고치고 3-R 시행 절차(I1)에 이 분기를 넣는다.
- R-3 (권고) `MAX_ITERATIONS` 하향으로 2,000,000회를 넘는 해시를 이미 쓰는 사용자는 업그레이드 뒤 "비밀번호 미설정"이 되어 **PANIC 2단계 이후(청산)도 거절**된다(1단계 보호 모드 고정은 됨). 지금은 실전 계좌가 열린 적이 없어 영향은 없지만, 릴리스 안내에 "반복 횟수 확인 후 `hash-password`로 다시 만들기"를 넣고 기동 경고 문구에 범위 밖이라는 이유가 나오는지 확인하기 바란다.
- R-4 (권고) 6344545c의 `attempts.acquireUninterruptibly()`는 엔진 종료 중에도 대기 중인 검증을 끊지 못한다. 대기 상한은 (동시 활성화 2 + 진행 중 PANIC 연결) × 검증 1회(≤ 약 2초)라 유한하지만 `CLOSE_WAIT` 5초를 넘을 수 있다. 종료 시 `closing` 이후 들어온 검증은 기다리지 않고 거절하는 것을 검토.
- R-5 (확인) 응답을 멈춘 늦은 엔진은 스스로 끝나지 않고, 그 엔진의 계좌는 PROTECTIVE로 남는다(D-142에 적힘). 묶음 E에서 `silence`된 엔진이 토큰을 새로 발급하지 않게 하는 조건은 D-142 후속에 기록돼 있음을 확인했다.

## 판정
수정 필요(A9·R-1 활성화 확인 중 PANIC 순서 역전과 OPEN 상태 청산, A12·R-2 실행 중 두 엔진 감지가 사실상 발동하지 않음, A14 D-142·ADR-006 서술 정정)
