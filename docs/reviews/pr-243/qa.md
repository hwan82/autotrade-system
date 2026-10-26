# 독립 QA 검토: 7단계 절차서 I1 보완: D-142 후속(3-R 하트비트 폴더 변형, 늦게 뜬 엔진, 해시 다시 만들기) [실전 관련]
- 대상: PR #243 / 브랜치 `claude/project-thread-wqsuqu` / 헤드 72f376e7 / main 대비 diff(병합 기준점 94ab11de, `git diff 94ab11de..72f376e7`: `docs/runbooks/stage-7-real-trading-tests.md`, `docs/team/stage-7-readiness-plan.md`, `docs/qa/pending.md`, `docs/reviews/pr-243/risk-ops.md`, `docs/reviews/pr-243/architect.md`). 기준점 뒤 main(53482a56)의 변경은 녹화·DART·검토 문서뿐이라 절차서가 인용한 코드(`autotrade-control`, `autotrade-app-engine`, `autotrade-ui`, `autotrade-notification-jar`, `autotrade-monitor-agent-jar`)는 같다.
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (72f376e7): 1회째 Maven Central 429(kotlin-script-runtime pom)로 의존성 해석 실패, 20초 뒤 2회째 BUILD SUCCESSFUL(138 tasks 모두 실행, 미러 없이). 테스트 결과 XML 합계 2,003건, 실패 0·오류 0·건너뜀 0. 문서만 바뀐 PR이라 빌드 통과는 회귀 없음만 뜻한다
  - 절차서 인용 줄을 코드에서 직접 대조: `EngineMain.java:78-81·153-156·160-163·312`, `ProcessStartup.java:23-24`, `EngineSelfCheck.java:58·221-238`, `ControlServer.java:142·203-219·362·402·420·434·448·626-657(close)`, `ControlClient.java:91-107`, `OperatorPasswordHash.java:26·31·64`, `real.js:76-78`, `EventRoutes.java:97-100`, `AtomicFile.java:18`, `HeartbeatWriter.DEFAULT_INTERVAL`(5초). 모두 문구와 맞다.
  - 보호 테스트(`autotrade-architecture-test`, Guard·GuardedOrderGateway 테스트, 장애 테스트) 변경 없음: diff는 `docs/`뿐.
  - 개발 쪽 검토 원문(risk-ops 재검토 승인, architect 승인)은 확인할 주장으로만 보고 같은 항목을 처음부터 다시 봤다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드·설정 변경 없음. 절차서 머리 "RealTradingGuard 기본 OFF, 재기동 시 OFF는 그대로" 유지. 6.1은 늦게 뜬 엔진의 계좌 OFF(`EngineSelfCheck.java:227-230` `guard.shutdown`)를 관측만 한다. 새 E6 추천안은 기준을 강하게 하는 쪽 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음(문서만) |
| A3 | 해당 없음 | 증권사 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재주문 변경 없음. 5장 4번 행 그대로 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 해당 없음 | 프로세스 분리 변경 없음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음. 6장 5-3 "서버 측 손절 조건주문은 남긴다" 유지 |
| A10 | 통과 | diff에 키·계좌번호·토큰·해시 값 없음. P11 명령은 `Split('$')[1]`로 반복 숫자만 보고(형식은 `OperatorPasswordHash.java:17`·91행), secrets.json 구조(`config-keys.md` 117행 `real-trading` 아래 `operator-password-hash`)와 맞음 |
| A11 | 해당 없음 | 테스트·보호 경로·고정 값 변경 없음 |
| A12 | **실패** | 발견 1: 6.1 5번 Windows 분기가 "늦게 뜬 엔진만 끄지 않는다"고 하면서 곧바로 6장 5번 조치(**늦은 엔진 먼저**, `Stop-Process -Id <PID>(두 번째 엔진 PID 먼저)`, 첫 엔진은 Ctrl+C로 끝내도 됨)로 보낸다. 이 순서는 절차서가 같은 문단에서 "3-R 금지 상태"라고 한 상태(첫 엔진에 OPEN 계좌가 있는데 화면 "가상")를 두 번의 종료 사이에 절차 스스로 만든다. 아래 점검표 밖 발견 1 |
| A13 | 해당 없음 | 해외 경로 변경 없음 |
| A14 | 통과(권고 Q2) | D-142 "남은 위험"의 "늦게 뜬 엔진을 끄면 풀린다" 정정은 8장 추적표(PR #243 risk-ops R-1 줄)로 다음 엔진 PR 몫에 넘겨져 있다. 다만 준비 계획 I1 행이 "시행 전 결정 E1~E5 남음"으로 E6을 빠뜨렸다(권고 Q2) |
| B1 | 통과 | 직접 실행 `./gradlew build --rerun-tasks`(72f376e7) BUILD SUCCESSFUL, 2,003건 실패 0. 429 실패 1회는 저장소 접속 문제로 빌드 실패로 보지 않음. CI 4개 검사도 성공 |
| B6 | 해당 없음 | 새 동작 없음(문서만). 테스트 삭제·건너뛰기·약화 없음 |

## 점검표 밖 발견

### 발견 1 (수정 필요, A12): Windows "확인 불가"에서 늦은 엔진을 먼저 끄는 순서가 화면 "가상"을 만든다
- 절차서 6.1 5번(Windows): "늦게 뜬 엔진만 끄지 않는다 … 이 상태에서 늦은 엔진이 죽으면 실험실은 잠금 파일의 PID(죽은 늦은 엔진)를 보고 NOT_RUNNING("가상")으로 판단한다 … 3-R 금지 상태다. 강제 종료(`Stop-Process`)는 종료 훅도 건너뛴다. 그러므로 "확인 불가"를 기록한 뒤 곧바로 **6장 5번 조치(늦은 엔진 먼저, 이어서 첫 엔진까지 모두 종료)**".
- 6장 5-1: "`Stop-Process -Id <PID>`(두 번째 엔진 PID 먼저). 첫 엔진은 그 창에서 Ctrl+C로 끝내도 된다."
- 코드로 따라가면(Windows, 파일 키 null, 6.1 공존 뒤 늦은 엔진 L이 `silence`한 상태):
  1. L이 `Stop-Process`로 죽는다 → 종료 훅 없음 → 소켓 파일·잠금 파일 그대로, 잠금 파일 내용은 L의 PID(`ControlServer.silence` 203-219행은 파일을 지우지 않음).
  2. 먼저 뜬 엔진 F의 `checkLock`(373-374행)·`checkSocket`(425행)은 `lockKey`·`socketKey`가 null이라 파일이 있기만 하면 "같음"으로 보고 아무것도 하지 않는다.
  3. 실험실 `ControlClient`: 소켓 연결 실패(듣는 쪽 없음) → `notRunningOrUnknown`(91-107행)이 잠금 파일 PID(L, 죽음)를 보고 `NOT_RUNNING` → 화면 "가상". F는 살아 있고 OPEN·PROTECTIVE 시험 계좌를 그대로 들고 있다.
  4. 이 상태는 사람이 F 창으로 가서 F를 끝낼 때까지 이어진다. 그 사이 F는 계속 돌고(묶음 E 뒤에는 주문 가능), 6장 7번 기록 "화면이 '가상'으로 바뀌지 않음"은 절차를 그대로 따른 시험에서 깨진다. 실전 운영에서 같은 일이 생겨 이 절차를 따르면 같은 상태가 실전 계좌에서 생긴다 `[추론, 코드 근거]`(Windows에서 직접 실행하지 못함).
- 반대 순서는 이 상태를 만들지 않는다: L은 이미 `silence` 상태라 명령 채널 자가 확인이 멈췄고(`checker.shutdownNow()` 211행) 처리기도 활성화를 받지 않으며 계좌는 모두 OFF다. F를 먼저 끝내면(Ctrl+C면 F의 `close`가 Windows에서 남은 소켓 파일을 지움, 650-652행) 실험실은 잠금 파일 PID(L, 살아 있음)를 보고 `UNKNOWN`("확인 불가")을 유지하고, 이어서 L을 끝내면 엔진이 하나도 없으므로 "가상"이 맞는 표시다. 6장 5-1이 늦은 엔진을 먼저 끄는 이유("첫 엔진이 먼저 내려가면 두 번째 엔진이 잠금을 넘겨받아 기동을 이어 갈 수 있다")는 기동 중인 두 번째 엔진에 대한 것이고, 이미 `silence`한 L에는 해당하지 않는다(`silence` 뒤에는 잠금을 다시 잡는 코드가 없음).
- 참고: L을 그 창의 Ctrl+C로 끝내면 L의 `close`가 Windows에서 소켓 파일을 지우고(650행 `key == null` 분기) F의 `checkSocket`이 다시 bind하므로 화면은 F로 돌아올 가능성이 높다. 그러나 절차는 L에 `Stop-Process`를 쓰게 한다.
- 고칠 방향(예): 6.1 5번 Windows "확인 불가" 분기에서 L의 응답 멈춤 알림·로그를 확인한 뒤 **첫 엔진을 먼저(또는 두 프로세스를 한 명령으로 함께) 끝내고, 이어서 늦은 엔진을 끝낸다**고 6장 5-1과 다르게 적고 이유를 남긴다. 두 종료 사이에 화면 표시를 기록하게 한다. 6장 5-1 순서(두 번째 먼저)는 기동이 끝나지 않은 두 번째 엔진에만 적용된다고 범위를 적는다.

### 시도한 시나리오
- S1(발견 1): Windows, 6.1 공존 → L `silence` → 화면 "확인 불가" → 절차대로 L `Stop-Process` 먼저 → 화면 "가상" + F OPEN 유지. 위 근거.
- S2: 6.1 재현 경로. run·하트비트 폴더를 지운 뒤 L이 공존하려면 F의 명령 채널 자가 확인(1초, `ControlServer.CHECK_INTERVAL`)이 잠금을 다시 만들기 **전에** L이 JVM 기동·설정 읽기·Guard·알림 준비를 마치고 잠금을 잡아야 한다(`EngineMain` 기동 순서: 설정 → Guard → 알림 → 하트비트 검사 → 명령 채널). 사람이 한 줄 명령으로 띄워도 대개 잠금 검사(`ControlServer.java:142`)에서 거부될 것이다 `[추론]`. 절차는 3번 거부면 "공존 재현 못 함"으로 두고 "거부도 합격 쪽 결과"라 하므로, 6.1이 보려던 Windows 공존·"확인 불가"·종료 순서(발견 1 포함)는 운영 PC에서 한 번도 보지 못한 채 넘어갈 수 있다. 권고 Q1.
- S3: 6.1 2번에서 하트비트 폴더를 지운 뒤 F의 존재 파일 다시 쓰기. `AtomicFile.write`가 `Files.createDirectories`(18행)로 폴더를 다시 만들고 F는 5초마다 쓰며 L은 5초 뒤 첫 확인을 하므로 공존은 대략 5~10초다. 절차서 "최대 약 10초"·"15초를 넘으면 멈춤"과 맞음. 문제 없음.
- S4: P11 해시 다시 만들기를 엔진 실행 중에 하면 `real-config-changed` 긴급 알림이 온다(`EngineSelfCheck.java:258`). 절차는 다시 만든 뒤 엔진·실험실을 모두 다시 시작하라고 하고 N4가 그 알림을 기대 목록에 두므로 혼동은 작다. 문제 없음.
- S5: 6.1 공존 구간에 화면 명령(활성화)이 L에 닿는 경우. L은 `silence` 때 진행 중 활성화를 멈추고 계좌를 OFF로 내린다(PR #240 재검토에서 확인한 동작). 절차는 20초 동안 단추를 누르지 않게 하고 공존 구간 표시 판정을 E6(사용자 결정)으로 올렸다. 기준 약화 없음.

### 권고 (판정에 넣지 않음)
- Q1 (S2) 6.1 3번: 3번 모두 거부되어 "공존 재현 못 함"이면 8장 추적표 ②(Windows "확인 불가"와 늦은 엔진 종료 뒤 상태) 줄을 "처리"로 바꾸지 않고 9장 되돌릴 조건(시험 전용 하네스)으로 Windows에서 다시 보게 하라. 거부 때는 L의 기동 로그 시각(기동 줄 → 거부 줄)을 함께 남기면 "1초 안에"가 왜 안 되는지 남는다.
- Q2 (A14) `docs/team/stage-7-readiness-plan.md` I1 행 "시행 전 결정 E1~E5 남음" → E6 추가.
- Q3 6.1 5번 "D-142 남은 위험의 '늦게 뜬 엔진을 끄면 풀린다'는 Windows에서 코드와 맞지 않으므로": Ctrl+C로 끄면 소켓은 풀린다(위 참고). 강제 종료일 때 맞지 않는다고 좁혀 적고, Ctrl+C 뒤에도 잠금은 다시 잡히지 않아 잠금 파일에 죽은 PID가 남는다는 점(다음 엔진 PR 몫)을 8장 줄에 함께 적어라.
- Q4 architect A-2(POSIX "1초 안에"는 늦은 엔진이 자리를 내준 뒤부터)는 다음 I1 PR로 넘겨져 있다. 확인만.

## 판정
수정 필요(A12: 발견 1)
