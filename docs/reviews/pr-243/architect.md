# architect 검토: 7단계 절차서 I1 보완(D-142 후속, 3-R 하트비트 폴더 변형, 해시 다시 만들기, C6·C10 현재형)
- 대상: PR #243 / 브랜치 `claude/project-thread-wqsuqu` / 커밋 dc697018(검토 시작 f811b227, 검토 중 risk-ops 수정 커밋 599e9d71이 들어와 그것까지 봄) / main 대비 diff(`git diff origin/main...HEAD`, 파일 4개: `docs/runbooks/stage-7-real-trading-tests.md`, `docs/team/stage-7-readiness-plan.md`, `docs/qa/pending.md`, `docs/reviews/pr-243/risk-ops.md`)
- 점검표: B
- 직접 실행한 것: `./gradlew build`(HEAD dc697018) — BUILD SUCCESSFUL, 138 tasks(19 executed, 119 up-to-date). 첫 두 번은 Maven Central 429와 같은 작업 폴더의 다른 검토자 빌드와 겹친 테스트 결과 쓰기 실패로 끝나 다시 돌렸다. 테스트 결과 XML 합계 2,003건, 실패 0·오류 0·건너뜀 0. 이 PR은 코드 변경이 없어 up-to-date 재사용이 결과를 바꾸지 않는다
- 검토 방법: 문서만 바뀐 PR이므로, 절차서가 인용한 코드 위치·동작·상수를 저장소 코드에서 하나씩 직접 열어 대조했다. PR 본문·커밋 메시지 설명은 근거로 쓰지 않았다.

## 인용 대조 (절차서 문구 ↔ 코드)

| 절차서 위치 | 주장 | 대조 결과 |
|---|---|---|
| 2장 P11 | `MIN_ITERATIONS` 100,000·`MAX_ITERATIONS` 2,000,000(`OperatorPasswordHash.java:26·31`), 범위 밖은 미설정 | 맞음. 26행 `MIN_ITERATIONS = 100_000`, 31행 `MAX_ITERATIONS = 2_000_000`, 64-65행 범위 밖이면 `Optional.empty()`. config-keys.md 35행과 일치 |
| 2장 P11 | 미설정 경고 문구 `real.js:76-78` | 맞음. 76행 `!st.passwordConfigured` 경고 "운영자 비밀번호가 설정되지 않아 …" |
| 2장 P11 | `hash-password` 뒤 엔진·실험실 모두 재시작(`EngineMain.java:312`) | 맞음. 312행 안내 문구 |
| 2장 P11 | PowerShell 경로 `.'real-trading'.'operator-password-hash'` | secrets.json 구조(config-keys.md 115-117행)와 맞음. 해시 전체를 출력하지 않고 반복 숫자만 보는 형태(B8 관점 문제 없음). 실행은 `[가정]`으로 표시됨 |
| 3장 N4 | 네 종류가 긴급(`EventRoutes.java:97-100`) | 맞음. 97 CONTROL_CHANNEL, 98 REAL_CONFIG_CHANGED, 99 ENGINE_STARTED_REAL, 100 OPERATOR_LOCKED 모두 `Severity.URGENT` |
| 3장 N4 | `ControlServer.java:362·420·448`(다시 만듦), 402·434(남이 잡음), 연결 상한 | 맞음. 362 RUN_DIR_RECREATED, 420 LOCK_RECREATED, 448 SOCKET_RECREATED, 402 LOCK_TAKEN, 434 SOCKET_TAKEN, 275 CONNECTIONS_SATURATED |
| 3장 N4 | `operator-locked`(`EngineMain.java:160-163`), `engine-started-real`, `real-config-changed` | 맞음. EngineMain 160-163, 186행, EngineSelfCheck 248·258행 |
| 6장 머리 | 1초 주기 자가 확인 | 맞음. `ControlServer.CHECK_INTERVAL = 1s`(64행) |
| 6장 머리 | 두 번째 엔진 기동 거부, 종료 코드 1, 문구 | 맞음. EngineMain 153-156 `IOException` → `StartFailure.of` IOException 분기 `EXIT_START_FAILED = 1`(61행) |
| 6장 0번 | `ProcessStartup.java:23-24`, `engine.hb`·`engine-<pid>.presence` | 맞음. ProcessStartup 23-24 INFO 기동 줄, `Heartbeat.SUFFIX=".hb"`, `EngineSelfCheck` PRESENCE_PREFIX/SUFFIX |
| 6장 5-3·9장 | 종료 훅 `EngineMain.java:78-81` | 맞음. 78-81 `addShutdownHook` → `engine.close()`만 |
| 6.1 머리 | 늦게 뜬 엔진 응답 멈춤·계좌 OFF(`EngineSelfCheck.java:219-237`), 최대 약 10초 | 맞음. 221-230 `iAmLate` → `silencer`, `guard.shutdown(…SYSTEM…)`. 하트비트·자가 확인 주기 5초(`HeartbeatWriter.DEFAULT_INTERVAL`)라 존재 파일 재기록 ≤5초 + 늦은 엔진 확인 ≤5초 ≈ 10초 `[추론]`, D-142와 같음 |
| 6.1 4번 | 신선도 15초(`EngineSelfCheck.java:58`) | 맞음. 58행 `FRESH = INTERVAL × 3` = 15초 |
| 6.1 4번 | 알림 문구 두 가지 | 맞음. EngineSelfCheck 232-237 문구와 일치 |
| 6.1 5번 POSIX | 먼저 뜬 엔진이 되찾음 | 맞음. `ControlServer.silence`(198-215)가 잠금을 풀고 소켓을 닫음 → 먼저 뜬 엔진 `checkLock`은 파일 키가 달라 `tryLock` 성공, `checkSocket`은 키가 달라 `isListening` 거짓 → 삭제 후 bind |
| 6.1 6번 | 감시 "engine 종료" 가능, 회복 알림 없음(`Watchdog.java:242-246`) | 맞음. ABSENT 전이에서 PROCESS_STOPPED, ABSENT→ALIVE는 회복 알림 없음(237-240은 STALE·UNREADABLE만) |
| 6장 4번 (b)·6.1 5번 | D-142 결정 4 되돌릴 조건 인용 | 맞음. D-142 표 4행 "3-R을 Windows 운영 PC에서 시행했을 때 합격하지 못하면 OS별 방식을 다시 본다" |
| 6.1 3번(599e9d71) | 잠금 검사 거부 문구 `ControlServer.java:142` | 맞음. 142행 "다른 실전 엔진이 이미 실행 중입니다(engine.lock 잠금)" |
| 6.1 5번(599e9d71) | `silence`는 파일을 지우지 않고 잠금만 풂(`ControlServer.java:203-219`), 실험실은 잠금 파일 PID가 죽었으면 NOT_RUNNING(`ControlClient.java:91-107`) | 맞음. 아래 A-1 |
| N4·6.1 머리(599e9d71) | `EngineSelfCheck.java:232-238`·`221-238` 줄 범위 | 맞음 |
| 8장 추적표 | PR #238 QA 재검토 R1 ①②③, PR #240 risk-ops R-3, PR #240 QA Q3 | 원문(`docs/reviews/pr-238/qa.md:111`, `pr-240/risk-ops.md:30`, `pr-240/qa.md:73`)과 일치. R1이 요구한 세 후속과 C6·C10 미래형 문구가 모두 반영됨 |

## B 점검표

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 직접 실행 `./gradlew build` BUILD SUCCESSFUL(HEAD dc697018), 테스트 XML 2,003건 실패 0 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음(diff 3개 파일 모두 `docs/`) |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. P11은 기존 키 `real-trading.operator-password-hash`의 범위(D-142, config-keys.md 35행)를 운영 확인 항목으로 옮긴 것이며 계약을 바꾸지 않음 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음 |
| B5 | 해당 없음 | 금액 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(문서). 테스트 삭제·건너뛰기 없음. 절차서가 컨테이너 시험(`EngineMainTest`, `ControlChannelTest`, `EngineSelfCheckTest`)을 인용하지만 이 PR은 시험을 바꾸지 않음 |
| B7 | 해당(risk-ops 검토 필요) | 장애 대응 절차(3-R, 실전 엔진 종료 조치, 운영자 비밀번호 해시)를 바꾸는 실전 관련 PR(지침 10장, 절차서 머리 "바꾸는 PR은 실전 관련 PR"). **risk-ops 검토 필요**, 독립 QA 요청은 `docs/qa/pending.md` 83행에 있음, 사용자 머지 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. P11 예시 명령은 해시 전체를 출력하지 않고 반복 숫자만 보게 하며 "해시 전체를 화면·기록에 남기지 않는다"를 명시 |

## 점검표 밖 발견

- **A-1 (처리됨 확인)** 6.1 5번 Windows 분기: 처음 본 f811b227에서는 "늦게 뜬 엔진만 끄고 1분 안에 돌아오는지 기록"이었다. 코드 읽기로는 돌아오지 않을 뿐 아니라 더 나쁘다: `checkLock`·`checkSocket`은 파일 키가 없으면(Windows) 파일이 있기만 하면 자기 것으로 보고(`lockKey == null`, `socketKey == null`), `silence`(203-219)는 파일을 지우지 않으므로 먼저 뜬 엔진은 되찾지 않는다. 늦은 엔진이 죽으면 `ControlClient.notRunningOrUnknown`(91-107)이 잠금 파일의 죽은 PID를 보고 NOT_RUNNING("가상")을 돌려준다 `[추론, 코드 근거]`. risk-ops R-1로 599e9d71이 "Windows에서 늦게 뜬 엔진만 끄지 않는다"로 고쳤고 6장 5번 조치(모두 종료)로 보낸다. 직접 대조해 맞음을 확인했다. **남은 것(코드, 이 PR 범위 밖)**: 먼저 뜬 엔진의 알림 문구 "그 엔진을 끄세요"(`EngineSelfCheck.java:236-237`)와 늦은 엔진 문구 "이 엔진을 끄세요"는 Windows 운영에서 사용자를 위 "가상" 상태로 이끈다. 8장 추적표 R-1 줄(묶음 E 전, 실전 관련 PR)로 넘겨진 것을 확인했고, 그 PR에서 잠금 PID가 죽은 것을 먼저 뜬 엔진이 보고 되찾는 보강과 함께 고칠 것을 architect 의견으로 남긴다.
- **A-2 (권고, 문구)** 6.1 5번 POSIX "먼저 뜬 엔진이 1초 안에 잠금·소켓을 되찾으므로": 되찾는 시점은 두 번째 엔진 기동 뒤 1초가 아니라 **늦은 엔진이 `silence`한 뒤** 1초다(그 전에는 `LOCK_TAKEN`·`SOCKET_TAKEN`으로 빼앗지 않음). 4번의 20초 대기 뒤에 누르게 되어 있어 절차상 문제는 없으나, "늦게 뜬 엔진이 자리를 내준 뒤 1초 안에"로 적으면 오해가 없다.
- 확인: 8장 추적표 새 줄 5개, 9장 대안 검토(6.1 공존 재현 방법 2안 비교·되돌릴 조건)·위험·한계 갱신이 지침 9장 형식을 갖췄다. 7단계 준비 계획 I1 행과 pending.md 줄은 절차서 변경과 맞는다.
- 확인: 이 문서는 새 합격 기준을 만들지 않는다고 명시하고(6.1 4번 "합격 기준값은 원본에 있다"), RealTradingGuard 기본 OFF·재기동 OFF 문구는 바뀌지 않았다.

## 판정
승인

(수정 필요 항목 없음. A-2는 권고로 다음 I1 PR에서 고쳐도 된다. 실전 관련 PR이므로 risk-ops 검토(dc697018 재검토 승인), 독립 QA(`docs/qa/pending.md` 83행), 사용자 머지가 남는다.)
