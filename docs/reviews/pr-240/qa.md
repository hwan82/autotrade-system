# QA 검토: 실전 제어 명령 채널 보강: 7단계 묶음 A C1~C12·C15 시험 (D-142) (PR #240 QA)
- 대상: PR #240 / 커밋 `f95c4d48` (브랜치 `claude/project-thread-uv9uq5`) / main 대비 diff 전체(merge-base = main `4ce2dc93`, 39파일 +3,158 −571: 운영 코드 14파일(`EngineCommandHandler`, `OperatorAuthenticator`, `OperatorPasswordHash`, `PanicSteps`, `EngineMain`, 새 `EngineSelfCheck`, `ControlServer`, 새 `ControlHandler`, `ControlCodes`, `EngineStatus`, `EventRoutes`, `RealTradingGuard`, `SecondConfirmation`, `real.js`), 시험 9파일, 보호 고정물·규칙 7파일(`ArchitectureTest`, `RealPathReflectionTest`, 위반 고정 예제 5), 문서 9파일(ADR-006, D-142, 결정 색인, config-keys, 준비 계획, `pending.md`, 검토 원문 2))
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --no-daemon` (헤드 `f95c4d48`): 1·2회째 Maven Central 429로 의존성 받기 실패(빌드 실패로 보지 않음), 20초 간격 3회째 **BUILD SUCCESSFUL in 4m 50s, 138 작업 모두 실행**, 미러 없이. 시험 결과 XML 합계 1,996건, 실패·오류·건너뜀 0.
  - PR 헤드 CI(GitHub check runs, 2026-10-25T09:46Z): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16), Lab screens (Playwright) 모두 success.
  - `git merge-tree --write-tree origin/main HEAD`: 충돌 없음(main이 merge-base 그대로).
  - `scripts/ci/test-mark-protected-changes.sh`: "표시 스크립트 자체 시험 통과". `scripts/ci/mark-protected-changes.sh main`: 보호 대상 30개 표시(운영 코드·시험·고정물, 이 PR은 실전 관련 PR).
  - `git diff --stat origin/main...HEAD -- '*broker*' '*order*' autotrade-app-gateway autotrade-domain autotrade-marketdata autotrade-strategy .github scripts`: `RealOrderGatewaysTest` 1줄(확인 생성을 발급기로 바꿈)뿐.
  - QA 임시 시험 2개(`QaTmp240Test`, 커밋하지 않고 지움): 아래 S1·S2.
  - 변이 24개(아래 "변이", 하나씩 넣고 `:autotrade-risk:test :autotrade-control:test :autotrade-app-engine:test :autotrade-architecture-test:test`, 끝나고 되돌림, `git status` 깨끗 확인).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성자·초기 상태 무변경(모든 계좌 OFF, RealTradingGuard.java:66-77). 추가는 발급기 필드(58행)와 `confirmationIssuer()`(86-91행)·`accept`의 발급기 검사(305-307행)뿐이라 올리는 전이를 더 좁힌다. 엔진은 기동 때 Guard를 새로 만든다(EngineMain `start`). `RealTradingGuardTest` 전체 통과(직접 빌드) |
| A2 | 통과 | 주문 출구 코드 무변경(위 `--stat`). ArchUnit 규칙은 넓어짐: `ONLY_COMMAND_CHANNEL_RAISES_GUARD`에 `confirmationIssuer`와 `SecondConfirmation.Issuer` 접근 추가(ArchitectureTest.java:181-185), `ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`에 발급기 가져오기·발급 추가(218-223행, 위반 고정 예제 시험 포함 통과). 고정 위반 수 6→8, 2→4는 생성자가 private이 되어 고정 예제가 발급기 경로로 바뀐 만큼이다(아래 A11). `SecondConfirmation`은 private 생성자(SecondConfirmation.java:25), 발급기 생성자는 패키지 전용(69행). 변이 M12(발급기 일치 검사 제거)·M13(여러 번 내줌) 잡힘 |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음. 활성화 단계·PANIC 증권사 단계는 여전히 `BROKER_NOT_CONNECTED`(EngineMain `commandHandler`) |
| A4 | 해당 없음 | 주문 재전송 로직 없음 |
| A5 | 해당 없음 | 주문 생성 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음. PANIC 증권사 단계는 연동 전 구현 그대로 |
| A7 | 통과 | 계좌마다 단일 작업 스레드(EngineCommandHandler.java:163-170), 전체 PANIC은 계좌별로 시작하고 같은 응답 기한까지 기다림(440-444행). 시험 `전체_PANIC은_계좌끼리_기다리지_않고_끝나지_않은_계좌는_진행_중으로_답한다` 통과. 토큰 버킷·주문 큐 코드 무변경 |
| A8 | 통과 | 프로세스 구성 무변경. 자가 확인·명령 채널 스레드는 데몬이고 실패해도 엔진을 멈추지 않음(EngineSelfCheck.java:197-203, ControlServer.java:332-339). 알림은 큐에 넣기만 함 |
| A9 | 통과 | 청산이 끝나지 않으면 보호 모드 유지(EngineCommandHandler.java:611-618 `keepProtective`), 서버 측 손절을 취소한 뒤 미완료면 긴급 표시(626-636행). 응답 기한 넘은 청산은 `PENDING`이고 Guard OFF 안 함(532-538행). 서버 측 손절·게이트웨이 코드 무변경 |
| A10 | 통과 | diff 추가 줄에 키·토큰·계좌번호 모양 없음(직접 grep). 새 알림(`real-config-changed`, `control-channel`, `operator-locked`, `engine-started-real`)과 `lastResult`에 값 없음(EngineSelfCheck.java:257-259, EngineCommandHandler.java:700-703, 감사 대상은 설정에 있는 별칭만 721-726행). `Secret.equals`는 값 비교라 5초마다 헛알림하지 않음(Secret.java:53). CI Secret scan success |
| A11 | **실패** | 보호 시험 삭제·`@Disabled` 없음. 고정 값 변경 검토: `ArchitectureTest` 위반 수 6→8·2→4, `RealPathReflectionTest.GUARD_RECORDS`에서 `SecondConfirmation` 뺌(레코드 → 클래스), `SECOND_CONFIRMATION_IS_NOT_DATA`에 발급기 추가, 위반 예제 5개가 발급기 경로로 바뀜. 모두 C12 대상 코드 변경과 맞고 규칙은 좁아지지 않음. 그러나 **새 동작 중 "늦게 뜬 엔진이 계좌를 내림"과 "응답을 멈춘 서버는 이미 읽은 요청에도 답하지 않음"을 잡는 시험이 없다**: 변이 M15(계좌 내리기 줄 삭제)·M21(읽은 뒤 `silenced` 검사 삭제) 생존. `EngineSelfCheckTest` 70행은 처음부터 OFF인 계좌가 OFF인지만 본다(내릴 것이 없음). ADR-006 5장 시험 표는 이 시험이 "계좌 내림"을 확인한다고 적었다. 그 밖 생존 변이는 아래 Q4 |
| A12 | **실패** | 아래 **발견 1**: 응답을 멈춘 늦은 엔진(`silence`)이 계좌를 내린 뒤에도, 그때 2차 확인 중이던 활성화는 멈추지 않고 OPEN으로 끝난다(임시 시험 S1로 확인). 그 엔진은 더 이상 명령에 답하지 않으므로 화면 내리기·PANIC이 닿지 않는 OPEN 계좌가 생긴다. D-140 ③ 3-R과 D-142 C6의 전제("늦게 뜬 엔진은 응답을 멈추고 계좌를 내린다", 알림 문구 "계좌를 모두 내렸습니다")와 다르다 |
| A13 | 해당 없음 | 시장별 로직 변경 없음. 기한은 단조 시계(`System.nanoTime`), 표시 시각은 주입 `Clock` |
| A14 | 통과 | D-142 표 C1~C12·C15와 ADR-006 2.2·2.4·2.8이 코드와 일치(표본: 기한 10/60/12초 = `Limits.DEFAULT` EngineCommandHandler.java:100-101, `MAX_ITERATIONS` 2,000,000 = OperatorPasswordHash.java:31, 연결 8·대기 6 = ControlServer.java:59-61, 존재 파일 이름 = EngineSelfCheck.java:61-62, 발급기 한 번 = RealTradingGuard.java:86-91). config-keys 반복 범위 갱신. 발견 1을 고칠 때 D-142 C6 문장과 남은 위험을 함께 맞춰야 한다 |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(1,996건, 실패 0), CI Gradle build success |
| B6 | **실패** | 새 시험은 많다(`EngineCommandHandlerTest` 18건, `EngineSelfCheckTest` 10건, `ControlChannelTest` 10건, `OperatorAuthenticatorTest` 8건, Guard 2건 등). 건너뜀·삭제로 통과시킨 것은 없음(지운 시험 5개는 대기열 방식이 없어져 같은 뜻의 처리기 시험으로 옮김: BUSY·STALE·동시 처리). 그러나 A11의 M15·M21 생존(발견 1 경로)은 새 동작에 실패를 잡는 시험이 없는 경우다 |

## 변이 (하나씩 넣고 위 네 모듈 시험)
| # | 변이 | 결과 |
|---|---|---|
| M1 | PANIC 세대 확인 제거(`beginActivation` 직전) | 잡힘 |
| M2 | PANIC 세대 확인 제거(단계 통과 직전) | **생존** (PANIC 1단계 `deactivate`가 대신 막음. Q4) |
| M3 | PANIC 세대 확인 제거(단계 직전) | **생존** (같은 이유. PANIC 뒤에도 그 단계의 증권사 작업은 한다. Q4) |
| M4 | `lock()`이 세대를 올리지 않음 | 잡힘 |
| M5 | 청산 시작 때 다시 고정 제거 | **생존** (Q4) |
| M6 | 청산 미완료 때 다시 고정 제거 | **생존** (Q4) |
| M7 | ① 확인 뒤 기한 검사 제거 | 잡힘 |
| M8 | ③ 통과 직전 기한 검사 제거 | 잡힘 |
| M9 | 기다리는 연결 상한 무시(늘 기다림) | 잡힘(시험이 끝나지 않아 12분 뒤 QA가 끊음) |
| M10 | 같은 계좌 청산을 겹쳐 시작 | **생존** (Q4) |
| M11 | `close`가 ACTIVATING을 내리지 않음 | 잡힘 |
| M12 | Guard 발급기 일치 검사 제거 | 잡힘 |
| M13 | 발급기를 여러 번 내줌 | 잡힘 |
| M14 | 늦은 엔진이 `silence` 안 함 | 잡힘 |
| M15 | 늦은 엔진이 계좌를 내리지 않음 | **생존** (발견 1, A11) |
| M16 | 늦은/먼저 판정 뒤집음 | 잡힘 |
| M17 | 기동 전 다른 엔진(하트비트) 검사 제거 | 잡힘 |
| M18 | `silence`가 잠금을 풀지 않음 | 잡힘 |
| M19 | 자가 확인이 듣는 쪽이 있어도 소켓을 빼앗음 | 잡힘 |
| M20 | 잠금 파일 다시 만들기 안 함 | 잡힘 |
| M21 | 요청을 읽은 뒤 `silenced` 검사 제거(응답 멈춘 뒤에도 답함) | **생존** (발견 1, A11) |
| M22 | 잠금 걸린 뒤 기다리던 검증의 재확인 제거 | 잡힘 |
| M23 | 해시 검증 직렬화 제거(허가 1,000개) | **생존** (Q4) |
| M24 | 1단계 고정을 "상태 읽고 OPEN일 때만 내림"(옛 R-6 모양)으로 | 잡힘 |

24개 중 16개 잡힘, 8개 생존.

## 시나리오 (점검표 밖, 잘못된 주문·중복 주문·보호 공백을 만들 수 있는지)
- **S1 늦은 엔진의 응답 멈춤과 진행 중 활성화(임시 시험, 확인됨)**: `MAX_ITERATIONS` 해시(검증 약 2초)로 활성화를 보내고 150ms 뒤 `EngineSelfCheck`의 늦은 엔진 분기와 같은 일(`guard.deactivate` 모든 계좌)을 했다. 그 순간 계좌는 OFF(아직 확인 중)라 내릴 것이 없었고, 확인이 끝난 활성화는 `beginActivation` → 단계 → **OPEN**으로 끝났다(출력 `state-at-silence=OFF stillVerifying=true verifyMs~2043 final=OPEN msg=KIS-US-1 실전 매매가 열렸습니다(OPEN)`). 증권사 단계는 성공하는 가짜로 두었다(묶음 E 뒤 모양). 지금 운영 빌드는 단계가 `BROKER_NOT_CONNECTED`라 OPEN까지 가지 않는다 `[추론: EngineMain commandHandler]`. 이것은 PR #240 R1과 같은 조건이다(R1도 "운영에서는 활성화가 늘 실패해 일어나지 않음"이었지만 두 검토자가 수정 필요로 고치게 했다).
- **S2 PANIC보다 먼저 받은 활성화가 PANIC 뒤에 처리됨(임시 시험, 확인됨)**: 처리기에 PANIC(받은 시각 t+1ms)을 먼저 넣고, 받은 시각이 t인 활성화를 그 뒤에 넣으면 계좌가 OPEN으로 끝났다(`panic=OK act=OK final=OPEN`). 세대 값은 처리기에 들어간 때 적고(EngineCommandHandler.java:223), 연결을 받은 시각과 견주지 않기 때문이다. 실제로는 요청 읽기(최대 5초) 동안만 생기는 창이고, 사람이 PANIC 뒤에 다시 활성화한 것과 구별할 수 없어 권고로 둔다(Q2).
- **S3 엔진 종료와 진행 중 활성화(코드 읽기)**: `closing`은 2차 확인 전에만 본다(224행). 확인을 마친 활성화가 `close()`의 ACTIVATING 정리(751-755행) 뒤, 계좌 작업 스레드 `shutdown`(756행) 전에 `beginActivation`과 `execute`를 마치면 그 작업은 `awaitTermination` 5초 동안 돌아 종료 중에 OPEN이 될 수 있다 `[추론, 창이 아주 좁음]`(Q5).
- **S4 잠금 남용으로 PANIC 청산 막기(코드 읽기, 기존)**: 틀린 비밀번호 5번이면 15분 동안 PANIC 2차 확인도 `LOCKED`라 청산을 못 한다(OperatorAuthenticator.java:110-128). 1단계 보호 모드 고정은 된다. 이번 PR은 잠기는 순간 긴급 알림을 더했고(C10), 경로는 Q-059 ①(묶음 F)로 남아 있다. 이 PR이 새로 만든 위험은 아니다.
- **S5 Windows에서 먼저 뜬 엔진이 자리를 되찾는지(코드 읽기)**: 자가 확인은 파일 키가 없으면 "있으면 내 것"으로 본다(ControlServer.java:343 주석, 368-369·420행). Windows는 파일 키가 없다고 코드가 스스로 적었다. 늦은 엔진이 `silence`로 소켓을 닫아도 소켓 파일은 남으므로(파일은 지우지 않음, 199행 주석), 먼저 뜬 엔진은 그 파일을 자기 것으로 보고 다시 만들지 않아 화면 명령이 어느 엔진에도 닿지 않는다 `[추론]`. D-142 C6의 "먼저 뜬 엔진이 1초 안에 자리를 되찾는다"는 POSIX에서만 맞다(Q3).

## 점검표 밖 발견
- **발견 1 (수정 필요, A11·A12·B6)** 늦게 뜬 엔진의 `silence`는 명령 처리기를 멈추지 않는다. `EngineSelfCheck.checkOtherEngine`(EngineSelfCheck.java:223-230)은 서버 응답을 멈추고 그 순간의 Guard 상태만 내린다(`deactivate`). 처리기(`EngineCommandHandler`)는 이를 모르므로 (a) 이미 읽혀 2차 확인 중인 활성화가 그 뒤 `beginActivation`·단계를 마쳐 OPEN이 되고(S1), (b) 서버는 읽은 요청에 대해서는 `silence` 뒤에도 응답을 쓴다(ControlServer.java:311-314는 처리 **전**에만 본다: 처리 중 `silence`면 OPEN 응답이 화면에 간다). 결과로 아무 명령도 받지 않는 엔진에 OPEN 계좌가 남고, 그 엔진은 스스로 끝나지 않는다(D-142 남은 위험). 화면 내리기·PANIC이 닿지 않는 보호 공백이고, 알림은 "계좌를 모두 내렸습니다"라고 말한다. 고칠 방향 예: `silence` 때 처리기도 올리는 명령을 받지 않게 하고(종료 중과 같은 표시) 모든 계좌의 PANIC 세대를 올려 진행 중 활성화가 ①②③에서 멈추게 하기, 그리고 OPEN·ACTIVATING 계좌를 둔 채 `silence`가 내려가는지 보는 시험(M15)과 처리 중 `silence`면 응답을 쓰지 않거나 실제 상태로 쓰는지 보는 시험(M21). 지금 운영 빌드에서는 활성화 단계가 늘 실패해 OPEN까지 가지 않지만 묶음 E에서 단계가 연결되면 바로 열리는 경로다.
- **Q1 (권고, risk-ops 결정)** 늦은 엔진은 계좌를 OFF가 아니라 `deactivate`(OPEN → PROTECTIVE)로 내린다(EngineSelfCheck.java:228). 그 엔진이 보유를 가진 채 보호 모드에 남으면 먼저 뜬 엔진과 함께 같은 계좌에 손절·익절 매도를 낼 수 있고(중복 청산 주문), 그 엔진은 명령을 받지 않아 화면에서 멈출 수 없다. 반대로 OFF로 내리면 그 엔진이 연 보유의 엔진 손절이 사라진다. 어느 쪽인지 정하고 ADR-006 2.2·D-142 남은 위험에 적을 것.
- **Q2 (권고)** PANIC 세대는 처리기에 들어간 때 적는다(S2). "PANIC보다 먼저 받은 활성화는 PANIC 뒤에 열리지 않는다"를 지키려면 계좌별 마지막 PANIC의 받은 시각(nanoTime)을 두고 활성화의 `receivedAt`과 견주는 방식이 있다. 지금 동작을 유지한다면 ADR-006 2.8 "활성화는 받을 때 세대 값을 적어 둔다"의 "받을 때"가 처리기 진입 시각임을 적을 것.
- **Q3 (권고, 문서)** S5. Windows(파일 키 없음)에서는 늦은 엔진이 자리를 내줘도 먼저 뜬 엔진이 남은 소켓 파일을 자기 것으로 보고 되찾지 않는다 `[추론]`. Windows는 잠긴 파일 삭제가 막혀 두 엔진 상황 자체가 드물 수 있다(PR #235 QA Q3 `[추론]`). D-142 C6 문장을 "POSIX"로 한정하거나 I1 절차서 3-R Windows 시행에서 이 경우를 확인 항목으로 넣을 것.
- **Q4 (권고, 시험)** 생존 변이: M2·M3(PANIC 뒤 그 단계의 증권사 작업을 하는지 `performed`로 볼 수 있음), M5·M6(청산 작업의 다시 고정. M6은 "청산이 끝나지 않는 동안 새로 시작된 활성화를 내린다"는 관찰 가능한 동작인데 시험이 없음), M10(같은 계좌 청산 겹침: 첫 청산이 끝나지 않을 때 둘째가 다시 증권사 단계를 도는지), M23(해시 검증 직렬화, C4 "대입 시도를 병렬로 늘릴 수 없게"를 보는 시험 없음).
- **Q5 (권고)** S3. `beginActivation` 직전에 `closing`도 다시 보면 닫힘과의 창이 사라진다. 발견 1의 고침(올리는 명령을 받지 않는 표시)과 같은 자리다.

## 판정
수정 필요(A11, A12, B6: 발견 1)

## 재검토 2026-10-26
- 대상: PR #240 / 커밋 `a6492914` (브랜치 `claude/project-thread-uv9uq5`) / main 대비 diff 전체(merge-base = main `4ce2dc93` 그대로, 41파일 +3,537 −574). 1차(`f95c4d48`) 뒤 커밋 3개: `97d12d5e`(발견 1·Q1~Q5 고침, 운영 코드 4파일 `EngineCommandHandler`·`EngineSelfCheck`·`EngineMain`·`ControlServer`, 시험 3파일, 문서 ADR-006·D-142·`qa-out-status.md`·`pending.md`), risk-ops 재검토(3)·architect 재검토 원문. 1차 때 읽은 나머지 diff는 바뀌지 않았고(`git diff 1b2a7512..HEAD --stat` 13파일), 그 부분 판단은 1차 표를 그대로 다시 대조했다.
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --no-daemon` (헤드 `a6492914`): 1회째 **BUILD SUCCESSFUL in 5m 31s, 138 작업 모두 실행**, 미러 없이. 시험 결과 XML 합계 2,003건(1차 1,996건 + 7건), 실패·오류·건너뜀 0.
  - PR 헤드 CI(2026-10-26T01:12Z): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16), Lab screens (Playwright) 모두 success.
  - QA 임시 시험 3개(`QaTmp240Test`, 커밋하지 않고 지움): S1·S2를 이번 코드의 실제 경로로 다시, S3 새로(아래).
  - 변이 17개(1차 생존 8개를 지금 코드에 맞춰 다시 + 고침 자리 새 9개, 같은 네 모듈 시험, 끝나고 되돌림, `git status` 깨끗 확인).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 1차와 같음. 이번 고침은 Guard를 내리는 쪽만 더함(`shutdown`) |
| A2 | 통과 | 1차와 같음. 이번 diff에 주문 출구·ArchUnit 규칙·고정 값 변경 없음 |
| A3 | 해당 없음 | 1차와 같음 |
| A4 | 해당 없음 | 1차와 같음 |
| A5 | 해당 없음 | 1차와 같음 |
| A6 | 해당 없음 | 1차와 같음 |
| A7 | 통과 | 1차와 같음. 새 `refuseRaising`은 그 계좌 청산이 도는 동안 그 계좌 활성화만 거절(EngineCommandHandler.java:405-418), 다른 계좌는 영향 없음 |
| A8 | 통과 | 1차와 같음 |
| A9 | 통과 | 1차와 같음. 늦은 엔진 계좌를 OFF로 내리는 것은 응답을 멈춘 엔진에서만이고 먼저 뜬 엔진의 보호 모드·서버 측 손절은 그대로(아래 R1) |
| A10 | 통과 | 이번 diff 추가 줄에 키·토큰·계좌번호 없음(직접 grep), 알림 문구에 값 없음. CI Secret scan success |
| A11 | 통과 | 보호 시험·고정 값은 이번에 바뀌지 않음(1차 검토 그대로 유효). 1차 생존 M15·M21이 이제 잡힘(`EngineSelfCheckTest#늦게_뜬_엔진은_열린_계좌를_OFF로_내린다`, `ControlChannelTest#처리하는_사이_응답을_멈추면_그_요청의_결과도_쓰지_않는다`), M10도 잡힘. 새 동작(처리기 `silence`, 받은 시각 비교, 청산 중 거절)마다 실패를 잡는 시험이 있음(N2·N5·N6·N7·N8 잡힘). 남은 생존 변이는 아래 Q1·Q2 |
| A12 | 통과 | 발견 1 해소: 처리기 `silence`(EngineCommandHandler.java:792-803)가 올리는 명령을 막고, 모든 계좌 PANIC 세대를 올린 뒤 OFF로 내린다. 확인 뒤 `beginActivation` 직전에 다시 거절 여부를 본다(262-266행). 엔진은 서버와 처리기를 함께 멈춘다(EngineMain.java:171-177). 서버는 처리 뒤 `silenced`면 응답을 쓰지 않는다(ControlServer.java:314-318). 임시 시험 S1·S3에서 OPEN 0건 |
| A13 | 해당 없음 | 1차와 같음 |
| A14 | 통과 | D-142 C2·C6·남은 위험, ADR-006 2.2·2.8·4장·5장 시험 표가 고친 코드와 일치(늦은 엔진 OFF, 받은 시각 비교, 청산 중 거절, Windows 한계 `[추론]`, M23 시험 없음을 남은 위험으로 적음). Q1 OFF는 "추천안 채택(자율)"으로 기록됨(아래 R1) |
| B1 | 통과 | 위 직접 빌드 BUILD SUCCESSFUL(2,003건, 실패 0), CI Gradle build success |
| B6 | 통과 | 새 시험 7건(`EngineCommandHandlerTest` 5, `EngineSelfCheckTest` 1, `ControlChannelTest` 1), 건너뜀·삭제 없음. 1차 B6 실패 사유(M15·M21)가 해소됨 |

### 1차 발견·권고 처리
| 1차 | 처리 | 확인 |
|---|---|---|
| 발견 1 | 해소 | 위 A12. S1(2초 해시 확인 중 `silence`) → `final=OFF`, 응답 `이 실전 엔진은 응답을 멈춘 엔진이라 활성화를 받지 않습니다` |
| Q1 늦은 엔진 보호 모드 유지 | 반영(OFF로) | EngineSelfCheck.java:229 `shutdown`, 처리기 `silence`도 `shutdown`. M15b(OFF 대신 `deactivate`) 잡힘. D-142에 대가(늦은 엔진이 연 보유의 엔진 손절 없음) 기록 |
| Q2 받은 시각 비교 | 반영 | `panicReceived`(603-604행)와 `panicked`(396-402행). S2 → `act=INVALID_STATE final=OFF`. N5·N8 잡힘 |
| Q3 Windows 되찾기 | 문서 반영 | D-142 남은 위험, I1 3-R Windows 확인 항목 |
| Q4 생존 변이 | 일부 반영 | M10 잡힘. M2·M3·M5·M6·M23 여전히 생존(아래 Q2). M23은 D-142에 "시험 없음"으로 적음 |
| Q5 닫힘 창 | 반영 | 확인 뒤 `refuseRaising`이 `closing`도 봄(262행) |

### 변이 (재검토)
| # | 변이 | 결과 |
|---|---|---|
| M2 | PANIC 확인 제거(단계 통과 직전) | 생존(1차와 같음, PANIC 1단계 `deactivate`가 대신 막음) |
| M3 | PANIC 확인 제거(단계 직전) | 생존(1차와 같음) |
| M5 | 청산 시작 때 다시 고정 제거 | 생존 |
| M6 | 청산 미완료 때 다시 고정 제거 | 생존(이제 청산 중 활성화를 거절해 겹칠 길이 더 좁음) |
| M10 | 같은 계좌 청산 겹쳐 시작 | 잡힘 |
| M15 | 자가 확인이 계좌를 내리지 않음 | 잡힘 |
| M15b | 자가 확인이 OFF 대신 보호 모드로 | 잡힘 |
| M21 | 처리 뒤 `silenced` 재확인 제거 | 잡힘 |
| M23 | 해시 검증 직렬화 제거 | 생존(D-142에 기록) |
| N1 | 처리기 `silence`가 PANIC 세대를 올리지 않음 | **생존** (Q2) |
| N2 | 처리기 `silence`가 계좌를 내리지 않음 | 잡힘 |
| N3 | `EngineMain`의 silencer가 처리기 `silence`를 부르지 않음 | **생존** (Q1) |
| N4 | 확인 뒤 `refuseRaising` 재확인 제거 | 생존(PANIC 세대가 대신 막음) |
| N5 | 받은 시각 비교 제거 | 잡힘 |
| N6 | 청산 중 활성화 거절 제거 | 잡힘 |
| N7 | 처리기 `silenced` 표시 안 함 | 잡힘 |
| N8 | 받은 시각 비교 부등호 뒤집음 | 잡힘 |

17개 중 9개 잡힘, 8개 생존.

### 시나리오 (재검토)
- **S1 (발견 1 재현, 이번 경로)**: 2,000,000회 해시로 활성화를 보내고 150ms 뒤 처리기 `silence`와 자가 확인의 `shutdown`을 했다. 확인 중이었고(`stillVerifying=true`, 약 1.8초) 끝은 **OFF**, 응답은 거절. 통과.
- **S2 (Q2 재현)**: 받은 시각이 늦은 PANIC을 먼저 처리한 뒤 받은 시각이 앞선 활성화 → `INVALID_STATE`, **OFF**. 통과.
- **S3 (새, 겹침 간격 무작위)**: 시드 240, 40회. 활성화(기본 해시, 단계 3개 각 0~2ms)를 보내고 0~400ms 뒤 처리기 `silence`. 끝에 OFF가 아닌 경우 **0건**.
- **S4 (코드 읽기, `silence`와 `beginActivation` 사이)**: 확인 뒤 재확인(262행)을 통과한 직후 `silence`가 돌면, `silence`는 세대를 먼저 올리고 `shutdown`하므로 `beginActivation`이 그 앞이면 `shutdown`이 내리고, 뒤면 계좌 작업 스레드가 세대 변화로 ②에서 멈춘다. 다만 이 창을 지키는 것은 N1(세대 올림) 한 줄이고 시험이 없다(Q2) `[추론]`.

### 점검표 밖 발견 (재검토)
- **R1 (사용자 확인 권고)** 늦은 엔진 계좌를 OFF로 내리는 것(1차 Q1)은 "추천안 채택(자율)"으로 기록됐다(D-142). 장애 대응 규칙을 바꾸는 결정이라 지침 8장 2번의 멈출 조건에 가깝다. 대가는 늦게 뜬 엔진이 그 짧은 사이에 연 보유가 엔진 손절 없이 남는 것(서버 측 손절·먼저 뜬 엔진은 그대로)이다. 머지 때 이 선택을 직접 확인할 것.
- **Q1 (권고, 시험)** N3 생존: `EngineMain`에서 `commands.silence(reason)` 한 줄(EngineMain.java:176)을 지워도 빌드가 통과한다. 그러면 운영 엔진은 1차 발견 1 그대로 돌아간다(처리기 시험은 `silence`를 직접 부르므로 배선을 보지 않음). `EngineMain`이 만든 silencer를 불렀을 때 처리기가 활성화를 거절하는지 보는 시험 하나를 권한다. 지금 코드는 맞다(코드 읽기로 확인).
- **Q2 (권고, 시험)** N1 생존: 처리기 `silence`의 세대 올림(799행)을 빼도 시험이 잡지 못한다. S4의 좁은 창을 지키는 줄이다. M2·M3·M5·M6·N4는 다른 층이 대신 막는 중복 방어라 생존했다.

## 판정 (재검토 2026-10-26)
승인
