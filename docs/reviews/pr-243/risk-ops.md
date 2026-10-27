# risk-ops 검토: 7단계 절차서 I1 보완: D-142 후속(3-R 하트비트 폴더 변형, 늦게 뜬 엔진 확인, 해시 다시 만들기), C6·C10 현재형 [실전 관련]
- 대상: PR #243 / 브랜치 `claude/project-thread-wqsuqu` / 커밋 f811b227 (185cea0b 포함) / `git diff origin/main...HEAD` 전체(3개 파일: `docs/runbooks/stage-7-real-trading-tests.md`, `docs/team/stage-7-readiness-plan.md`, `docs/qa/pending.md`). 문서만 바뀌었고, 절차서가 인용·기대하는 실전 경로 코드(`ControlServer`, `ControlClient`, `EngineSelfCheck`, `EngineMain`, `OperatorPasswordHash`, `OperatorAuthenticator`, `EventRoutes`, `AtomicFile`, `HeartbeatWriter`, `Watchdog`, `WatchdogSettings`, `ProcessStartup`, `real.js`)를 직접 읽어 대조했다.
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build` (f811b227): 첫 시도는 Maven Central 429(spring-core pom 내려받기)로 의존성 해석 실패, 다시 실행해 BUILD SUCCESSFUL(컴파일·ArchUnit·보호 테스트·순수 모듈 검사 포함). 시험 결과(XML 집계, 실패·오류·건너뜀 0): autotrade-risk 57, autotrade-architecture-test 46, autotrade-app-engine 109, autotrade-control 31, autotrade-notification-jar 86, autotrade-app-lab 269. 이 PR은 문서만 바꾸므로 빌드 통과는 회귀 없음만 뜻하고, 절차서의 Windows 기대 동작(R-1)은 확인하지 못했다(Windows 환경 없음)
  - 인용 줄 번호 대조(`sed -n`/`grep -n`): 아래 "인용 대조" 참고

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드 변경 없음. 절차서 머리(5행) "RealTradingGuard 기본 OFF, 재기동 시 OFF는 그대로", 6장 6-3 "모든 계좌 OFF로 시작하는지" 기록 유지. 새 6.1도 활성화를 만들지 않고 늦게 뜬 엔진의 계좌 OFF(`EngineSelfCheck.java:229` `guard.shutdown`)를 관측만 한다 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음(문서만) |
| A3 | 해당 없음 | 증권사 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재주문 변경 없음. 5장 4번 행(조회 먼저, 멱등 키) 그대로 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 해당 없음 | 프로세스 분리 변경 없음. 6.1 6번 "감시 알림은 판정에 쓰지 않는다"는 `Watchdog.java:242-245`(ABSENT → "engine 종료" 정보 알림)와 맞음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음. 6장 5-3 "서버 측 손절 조건주문은 남긴다" 유지 |
| A10 | 통과 | diff에 키·계좌번호·토큰·해시 값 없음. P11 확인 명령은 `Split('$')[1]`로 반복 숫자만 보이게 하고 "해시 전체를 화면·기록에 남기지 않는다"고 적음. 형식 `pbkdf2-sha256$<반복>$<salt>$<해시>`는 `OperatorPasswordHash.java:17`과 같아 `[1]`이 반복 칸이다 |
| A11 | 해당 없음 | 테스트·보호 경로 코드·고정 값 변경 없음(문서만). 새 동작 없음 |
| A12 | 실패 | 6.1(3-R 변형)의 절차·기대 결과가 코드와 원본 3-R 합격 기준에 비춰 두 곳이 어긋난다: R-1(Windows에서 늦게 뜬 엔진을 `Stop-Process`로 끄면 코드상 화면이 "가상"으로 바뀌는데 절차는 "풀리는지 기록"만 함), R-2(공존 구간 화면 상태를 원본 7장 3번 "첫 엔진의 OPEN 계좌를 OFF로 보이지 않음"에 대어 판정·기록하는 규칙이 없음) |
| A13 | 해당 없음 | 해외 경로 변경 없음 |
| A14 | 실패 | 6.1 5번 Windows 분기가 D-142 "남은 위험"의 "늦게 뜬 엔진을 끄면 풀린다"를 그대로 옮겼는데, 코드(`ControlServer.silence`·`checkLock`·`checkSocket`·`close`, `ControlClient.notRunningOrUnknown`)로는 강제 종료 때 성립하지 않는다(R-1). D-142 서술의 정정이 결정 로그에 필요하다 |

## 인용 대조 (모두 f811b227 기준)
- 맞음: `OperatorPasswordHash.java:26`(MIN 100,000)·`:31`(MAX 2,000,000), `real.js:76-78`(비밀번호 미설정 경고), `EngineMain.java:312`(hash-password 출력 "실전 엔진과 실험실을 모두 다시 시작"), `EngineMain.java:153-156`(기동 전 다른 엔진 거부), `:160-163`(operator-locked 알림), `:78-81`(종료 훅 `engine.close()`), `EventRoutes.java:97-100`(control-channel·real-config-changed·engine-started-real·operator-locked 모두 URGENT, 이름 문자열 71·73·75·77행과 일치), `ControlServer.java:362`(RUN_DIR_RECREATED)·`:402`(LOCK_TAKEN)·`:420`(LOCK_RECREATED)·`:434`(SOCKET_TAKEN)·`:448`(SOCKET_RECREATED), `ControlServer.CHECK_INTERVAL` 1초(64행), `EngineSelfCheck.java:58`(FRESH = 5초×3), `ProcessStartup.java:23-24`, `Watchdog.java:237-240`·`242-246`, `WatchdogSettings.java:58-59`.
- 범위가 조금 어긋남(판정 영향 없음): `EngineSelfCheck.java:219-237` → 실제 늦게 뜬 엔진 판정·침묵·OFF·알림은 221~238행, `:232-237` → 두 알림 `send`는 232·236행에서 시작해 238행에서 끝남.
- 존재 파일 다시 쓰기: `AtomicFile.write`가 `Files.createDirectories`(18행)로 지워진 `heartbeat` 폴더를 다시 만들므로 "먼저 뜬 엔진이 존재 파일을 다시 쓸 때까지(최대 약 10초)"는 코드와 맞음(쓰기 5초 주기 + 늦은 엔진 확인 5초 주기).
- P11 "범위 밖 해시는 미설정": `OperatorPasswordHash.parse` 64행이 범위 밖을 빈 값으로 → `EngineMain.java:286-290` 경고 로그 "형식이 올바르지 않아" → `OperatorAuthenticator.passwordConfigured()`(82행) false → `real.js:76` 경고. 맞음.

## 점검표 밖 발견
- **R-1 (수정 필요, A12·A14)** 6.1 5번 Windows 분기: "알림에 나온 PID로 **늦게 뜬 엔진만** 끝내고(`Stop-Process -Id <늦은 PID>`) 1분 안에 화면이 첫 엔진의 실제 상태로 돌아오는지 기록"은 코드로 보면 기대와 반대 결과를 낳고, 그 결과가 원본 3-R의 불합격 표시("가상")인데 절차가 그것을 불합격·즉시 조치로 잡지 않는다. 근거:
  1. `ControlServer.silence`(197~219행)는 소켓을 닫고 잠금을 풀지만 "파일은 지우지 않는다". 남은 `engine.lock`에는 늦게 뜬 엔진 PID가 적혀 있다(`writePid` 473행).
  2. Windows는 `fileKey`가 null이라 먼저 뜬 엔진의 `checkLock`(373~374행)·`checkSocket`(425행)이 "파일이 있으면 같은 것"으로 보고 아무것도 하지 않는다(그래서 D-142도 Windows는 되찾지 못한다고 적음).
  3. `Stop-Process`는 강제 종료라 JVM 종료 훅이 돌지 않아 `ControlServer.close`의 소켓 삭제(647~652행)도 없다. 소켓 파일·잠금 파일이 그대로 남고, 먼저 뜬 엔진은 2번 때문에 계속 아무것도 하지 않는다 → 화면 명령은 듣는 이 없는 소켓으로 간다.
  4. 실험실 `ControlClient.notRunningOrUnknown`(84~107행)은 소켓에 닿지 못하면 `engine.lock`의 PID가 살아 있는지 보는데, 그 PID는 방금 끈 늦은 엔진이라 죽어 있다 → `NOT_RUNNING`("실전 엔진이 실행 중이 아닙니다") → 화면이 첫 엔진의 OPEN 계좌가 있는데도 "가상"으로 보인다 `[추론]`(코드 읽기, Windows에서 직접 실행하지 못함).
  - 늦은 엔진 창에서 Ctrl+C로 끝내면 `close`가 소켓 파일을 지우므로(650행, Windows 분기) 먼저 뜬 엔진이 1초 안에 소켓을 다시 만들어 화면은 돌아올 가능성이 높다. 다만 잠금은 다시 잡지 않아(파일이 있어 "같음") 잠금 파일에 죽은 PID가 남는다 `[추론]`.
  - 고칠 방향(예): (가) 5번 Windows 분기에서 늦은 엔진을 **그 창의 Ctrl+C로** 끝내는 것을 먼저 하고, `Stop-Process`는 Ctrl+C가 안 될 때만 쓴다고 나눠 적는다. (나) 늦은 엔진을 끈 뒤 화면이 **"가상"이나 OFF로 보이면 불합격**으로 기록하고 곧바로 6장 5번 조치(첫 엔진 종료·미체결 취소)를 한다고 명시한다. (다) 잠금 파일 PID가 첫 엔진 PID인지 함께 기록한다. (라) D-142 "남은 위험"의 "늦게 뜬 엔진을 끄면 풀린다"가 강제 종료 때 성립하지 않는다는 점을 architect에 넘겨 결정 로그(새 번호 또는 D-142 후속)에 바로잡는다. 이 PR이 코드를 고칠 필요는 없지만, 절차서가 틀린 기대를 옮기면 안 된다.
- **R-2 (수정 필요, A12)** 6.1 4번 공존 구간(최대 약 10초)의 화면 판정이 없다. 이 구간에 늦게 뜬 엔진은 명령·상태에 응답하며(침묵 전), 그 엔진의 Guard는 기동 직후라 모든 계좌가 OFF다. 원본 7장 3번 3-R은 "어느 경우든 화면이 첫 엔진의 OPEN 계좌를 OFF로 보이지 않고", "닿지 못하면 두 번째 엔진이 명령·상태에 응답하지 않고"를 요구한다. 절차는 "단추를 누르지 않고 기다린다"만 적고 그동안 `#/real`이 OFF를 보였는지 기록하지 않으며, 그것이 원본 기준의 불합격인지 D-142가 받아들인 남은 위험인지 정하지 않는다("이 문서가 새 기준을 만들지 않는다"고만 함). 고칠 방향: 4번에 "공존 구간 동안 `#/real` 표시(첫 엔진 계좌가 OFF로 보였는지, 몇 초)를 기록"을 넣고, 이 결과를 원본 3-R 합격 판정에 어떻게 넣을지를 7장 시행 전 결정 모음에 E 항목(사용자 결정, 지침 8장 2번)으로 올린다.
- R-3 (권고) 6.1 3번 "거부됨: 첫 엔진이 존재 파일을 그사이 다시 썼다"는 거부 이유를 하나로 단정한다. 코드로는 거부가 두 가지다: 존재 파일·하트비트 검사(`EngineMain.java:155` "다른 실전 엔진(PID …)이 돌고 있습니다") 또는 잠금 검사(`ControlServer.java:142` "다른 실전 엔진이 이미 실행 중입니다(engine.lock 잠금)"). 먼저 뜬 엔진은 1초마다 지워진 run 폴더·잠금을 다시 만들므로(`ControlServer.CHECK_INTERVAL`), JVM 기동이 1초를 넘으면 두 번째 엔진은 대개 잠금 검사에서 거부된다 `[추론]`. 거부 메시지 원문을 기록하게 하고 원인을 그것으로 나누어 적으면, "공존 재현 못 함"이 어느 방어 덕분인지 남는다. 3번 모두 거부일 가능성이 높으므로 9장 되돌릴 조건(시험 하네스)으로 갈 공산이 크다는 점도 적어 두기 바란다.
- R-4 (권고) 9장·6장의 줄 번호 `EngineSelfCheck.java:219-237`·`232-237`을 실제 범위(221~238)로 맞춘다.

## 판정
수정 필요(R-1, R-2 / A12·A14)

## 재검토 2026-10-26
- 대상: PR #243 / 커밋 599e9d71 / `git diff origin/main...HEAD` 전체(1차 이후 바뀐 것은 `docs/runbooks/stage-7-real-trading-tests.md` 하나, 코드 변경 없음). 병합 기준점 뒤 main에 `autotrade-control`·`autotrade-app-engine`·`autotrade-ui`·`autotrade-notification-jar` 변경이 없어 인용 줄은 main 기준으로도 그대로다.
- 점검표: A
- 직접 실행한 것: `./gradlew build` (599e9d71) BUILD SUCCESSFUL(종료 코드 0). 인용 줄 다시 대조: `ControlServer.java:142`(잠금 거부 문구)·`:203-219`(`silence`, 파일 지우지 않음), `ControlClient.java:91-107`(`notRunningOrUnknown`), `EngineSelfCheck.java:221-238`·`232-238`, `EngineMain.java:153-156` 모두 맞음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 1차와 같음. 새 E6 추천안은 기준을 강하게 하는 쪽("원본 기준대로 불합격")이고 Guard 기본값에 닿지 않음 |
| A2 | 해당 없음 | 문서만 |
| A3 | 해당 없음 | 문서만 |
| A4 | 해당 없음 | 문서만 |
| A5 | 해당 없음 | 문서만 |
| A6 | 해당 없음 | 문서만 |
| A7 | 해당 없음 | 문서만 |
| A8 | 해당 없음 | 문서만 |
| A9 | 해당 없음 | 문서만. 6장 5-3 서버 측 손절 유지 문구 그대로 |
| A10 | 통과 | 새 문구에 키·계좌번호·토큰·해시 값 없음. 잠금 파일 PID 기록은 비밀값 아님 |
| A11 | 해당 없음 | 테스트·보호 경로 변경 없음 |
| A12 | 통과 | R-1: 6.1 5번 Windows 분기가 "늦게 뜬 엔진만 끄지 않는다"로 바뀌고, "확인 불가"면 잠금 파일 PID 기록 뒤 곧바로 6장 5번 조치(늦은 엔진 먼저, 첫 엔진까지 모두 종료)와 6번 정리로 간다. 근거로 `ControlServer.java:203-219`·`ControlClient.java:91-107`을 인용했고 코드와 맞다. 한 번에 두 엔진을 다 끄므로 "가상" 표시가 OPEN 계좌가 남은 채로 지속되는 상태를 절차가 만들지 않는다. R-2: 6.1 4번에 공존 구간 `#/real` 표시를 2초 간격으로 기록하고, E6 결정 전에는 그 구간 OFF 표시를 합격으로 적지 않게("E6 대기") 했다. 원본 7장 3번 기준을 약하게 하지 않고 사용자 결정(7장 E6)으로 올렸다 |
| A13 | 해당 없음 | 문서만 |
| A14 | 통과 | 이 PR은 결정 로그를 바꾸지 않지만, D-142 남은 위험의 "늦게 뜬 엔진을 끄면 풀린다"가 Windows에서 코드와 맞지 않는다는 점을 절차서에 명시하고 8장 추적표에 "PR #243 risk-ops R-1 … D-142 남은 위험 서술 정정, 엔진 보강, 늦은 엔진 알림 문구"를 다음 엔진 PR(실전 관련) 몫으로 남겼다. 절차서가 틀린 기대를 옮기지 않으므로 이 PR 범위에서는 통과로 본다. 정정 자체는 후속에서 확인한다 |

## 점검표 밖 발견 (재검토)
- R-1·R-2 해결. R-3(거부 경로 둘, 메시지 원문 기록)·R-4(줄 범위) 반영 확인.
- 남는 위험(후속, 이 PR 판정에 넣지 않음): 늦게 뜬 엔진의 알림 본문은 여전히 "이 엔진을 끄세요"(`EngineSelfCheck.java:233-234`)이고 먼저 뜬 엔진 알림도 "그 엔진을 끄세요"라, 운영 중 Windows에서 같은 일이 생기면 사용자가 알림대로 늦은 엔진만 끄고 "가상" 상태가 될 수 있다 `[추론]`. 절차서 8장 추적표에 들어 있으니, 다음 엔진 PR에서 문구 또는 되찾기 동작을 고치고 D-142를 새 번호나 후속 기록으로 바로잡는지 risk-ops가 확인한다.

## 판정
승인

## 재검토 2 2026-10-27
- 대상: PR #243 / 브랜치 `claude/project-thread-wqsuqu` / 헤드 46b90149 / `git diff origin/main...HEAD` 전체(병합 기준점 94ab11de, 7개 파일 모두 `docs/`). 1차 재검토(599e9d71) 뒤 바뀐 것은 46b90149(독립 QA 발견 1·Q1~Q3 반영: `docs/runbooks/stage-7-real-trading-tests.md` 6장 5-1·6.1 3·5·7번·8장 ②줄·R-1 줄, `docs/team/stage-7-readiness-plan.md` I1 행, `docs/qa/pending.md`, `docs/qa-out-status.md`). 코드 변경 없음. 기준점 뒤 main(f80f4caa)의 코드 변경은 수집기·`live.js`·LiveJudgementTest뿐이라 절차서가 인용한 `autotrade-control`·`autotrade-app-engine`·`real.js`는 같다.
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build` (46b90149): BUILD SUCCESSFUL(종료 코드 0, 138 tasks).
  - `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-control:test --rerun-tasks`: 종료 코드 0. 결과 XML: autotrade-risk 57, autotrade-architecture-test 46, autotrade-app-engine 109, autotrade-control 31, 실패·오류·건너뜀 0. 문서만 바뀐 PR이라 회귀 없음만 뜻한다.
  - 새 문구의 코드 근거를 직접 대조: `ControlServer.java:203-219`(`silence`: 211행 `checker.shutdownNow()`, 잠금 해제·`lock`/`lockChannel` null, 파일 안 지움), `:350-353`(`checkIntegrity`가 `silenced != null`이면 바로 돌아감 → 응답을 멈춘 엔진은 잠금·소켓을 다시 잡는 경로가 없음), `:373-374`·`:425`(Windows 파일 키 null이면 "있으면 같음"), `:626-657`(`close`: 649-652행 Windows 분기 소켓 파일 삭제, 잠금 파일은 남김), `ControlClient.java:91-107`(잠금 파일 PID 살아 있으면 UNKNOWN, 죽었으면 NOT_RUNNING, 읽기 실패도 UNKNOWN), `EngineSelfCheck.java:213-238`(늦은 엔진 `silenced`는 되돌리지 않음, 먼저 뜬 엔진이 사라지면 `otherEngine` 빈 값으로 아무것도 안 함). 모두 맞음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 코드·설정 변경 없음. 새 종료 순서는 Guard를 켜는 경로를 만들지 않는다. 늦은 엔진은 `silence` 뒤 계좌가 모두 OFF(`EngineSelfCheck.java:227-230`)이고 명령 채널이 닫혀(`ControlServer.java:211`·`:352`) 활성화를 받을 길이 없으므로, 첫 엔진을 먼저 끈 뒤 늦은 엔진이 살아 있는 구간에도 OPEN 계좌가 생기지 않는다 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음(문서만) |
| A3 | 해당 없음 | 증권사 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·재주문 변경 없음. 5장 4번 행 그대로 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 해당 없음 | 프로세스 분리 변경 없음. 6.1 6번 감시 알림 비판정 문구 그대로 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음. 6.1 5번이 6장 5-2·5-3(미체결 일반 주문 취소, 서버 측 손절 조건주문은 남김)으로 이어짐 |
| A10 | 통과 | 새 문구에 키·계좌번호·토큰·해시 값 없음. PID·시각 기록은 비밀값 아님 |
| A11 | 해당 없음 | 테스트·보호 경로·고정 값 변경 없음 |
| A12 | 통과 | 독립 QA 발견 1 해소: 6.1 5번 Windows "확인 불가" 분기가 첫 엔진 먼저(또는 `Stop-Process -Id <첫>,<늦은>` 한 명령)로 바뀌었고, 6장 5-1에 "두 번째 먼저"는 기동이 끝나지 않았거나 응답을 멈추지 않은 두 번째 엔진에만 적용된다는 범위가 붙었으며, 6.1 7번 정리도 같은 순서를 따른다. 코드로 따라가면 첫 엔진을 끈 뒤 잠금 파일 PID는 늦은 엔진(살아 있음)이라 실험실은 UNKNOWN("확인 불가")을 유지하고(`ControlClient.java:103-105`), 첫 엔진을 Ctrl+C로 끄더라도 `close`는 잠금 파일을 남기므로 같다(`ControlServer.java:622-623` 주석·649-653행). 늦은 엔진까지 끄면 엔진이 없으므로 "가상"이 맞다. 절차가 "OPEN 계좌가 있는데 가상" 상태를 만들지 않는다 `[추론, 코드 근거]`(Windows에서 직접 실행하지 못함). 두 종료 사이·뒤 화면 기록도 넣었다. Q1: 공존 재현 못 함이면 8장 ②줄을 처리로 바꾸지 않고 9장 하네스로 Windows에서 다시 보게 했고, 거부 때 기동 로그 시각을 남기게 했다 |
| A13 | 해당 없음 | 해외 경로 변경 없음 |
| A14 | 통과 | 결정 로그 변경 없음. D-142 남은 위험의 "늦게 뜬 엔진을 끄면 풀린다"가 강제 종료일 때 코드와 맞지 않고 Ctrl+C 뒤에도 죽은 잠금 PID가 남는다는 점(Q3)을 6.1 5번과 8장 R-1 줄에 좁혀 적고 다음 엔진 PR(실전 관련) 몫으로 남겼다. 준비 계획 I1 행 E1~E6(Q2) 반영. 정정 자체는 후속에서 확인한다 |

## 점검표 밖 발견 (재검토 2)
- 독립 QA 발견 1, 권고 Q1·Q2·Q3 반영 확인. Q4(architect A-2)는 다음 I1 PR 몫 그대로.
- R-5 (권고, 판정에 넣지 않음) 6장 5-1 끝의 명령 예시 "`Stop-Process -Id <PID>`(두 번째 엔진 PID 먼저)"는 그대로 남아 있다. 바로 앞 문장에 적용 범위와 6.1 5번 예외가 있으므로 틀린 지시는 아니지만, 명령만 보고 따르는 사람을 위해 묶음 I에서 명령을 채울 때 그 괄호에도 "6.1 Windows '확인 불가'면 첫 엔진 먼저"를 붙이기 바란다. 한 명령 `Stop-Process -Id <첫>,<늦은>`이 주어진 순서대로 끝내는지는 `[가정]`이다(두 프로세스가 거의 함께 끝나므로 어느 쪽이든 위험 상태가 오래 가지 않음). 시행 때 기록으로 확인한다.
- R-6 (참고) main이 기준점 뒤 `docs/qa-out-status.md`·`docs/qa/pending.md`에 줄을 더했다. 머지 때 표 끝 줄이 겹칠 수 있으니 두 쪽 줄을 모두 남기는지 확인한다(내용 판정과 무관).
- 남는 위험(후속, 1차 재검토와 같음): 늦은 엔진 알림 본문 "이 엔진을 끄세요"(`EngineSelfCheck.java:233-234`)는 그대로다. 운영 중 Windows에서 알림대로 늦은 엔진만 강제 종료하면 "가상" 상태가 될 수 있다 `[추론]`. 8장 R-1 줄의 다음 엔진 PR에서 문구·되찾기 동작과 D-142 정정을 risk-ops가 확인한다.

## 판정
승인
