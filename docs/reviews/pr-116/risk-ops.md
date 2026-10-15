# risk-ops 검토: 감시 프로세스 1차: 하트비트·드라이브 여유·정지 요청 파일(MON-02, D-092)
- 대상: PR #116 / 브랜치 `claude/project-thread-0ganrn` / 커밋 `9cd4330`, `ed34018`(HEAD) / `origin/main...HEAD` diff 전체(31개 파일)
- 점검표: A
- 직접 실행한 것:
  - 검토를 `9cd4330`에서 시작했고, 검토 중 브랜치에 `ed34018`(CI 기동 확인 추가, 설정 폴더가 없으면 감시가 만듦, D-092·pending.md 갱신)이 올라왔다. 판정은 `ed34018` 기준이며 두 커밋을 모두 읽었다.
  - `ed34018`에서 `./gradlew build --offline --no-build-cache --rerun-tasks` → **BUILD SUCCESSFUL**. 전체 시험 1211건, 실패 0, 오류 0, 건너뜀 0. 모듈별: `:autotrade-risk:test` 55/0/0/0, `:autotrade-architecture-test:test` 42/0/0/0, `:autotrade-app-engine:test` 50/0/0/0, `:autotrade-app-watchdog:test` 21/0/0/0, `:autotrade-monitor-agent-jar:test` 22/0/0/0, `:autotrade-app-gateway:test` 49/0/0/0, `:autotrade-app-lab:test` 177/0/0/0(test-results XML 집계).
  - `9cd4330`에서도 같은 모듈을 `cleanTest`로 다시 돌려 모두 통과(감시 20건).
  - 실전 엔진 하트비트 확인 시험(검토용, 별도 worktree에 임시 시험 파일을 두고 실행한 뒤 worktree째 지움. 저장소 작업 폴더는 바꾸지 않음): `EngineMain.start`가 `<설정 폴더>/heartbeat/engine.hb`를 쓰는지, `Engine.close()` 뒤 파일이 지워지는지, `heartbeat` 자리에 **파일을 두어 하트비트 폴더를 못 만들게 한 상태**에서도 엔진이 뜨고 명령 채널 `status` 요청에 응답하며 `heartbeat().beat()`가 예외 없이 `false`인지 → **통과**.
  - `git diff origin/main...HEAD`에서 키·계좌번호·토큰 형태 문자열 grep → 없음. gitleaks는 이 환경에 설치돼 있지 않아 로컬에서 돌리지 못했다(CI 단계에서 실행됨).

## 바뀐 범위와 실전 경로 영향
- 실전 엔진(`autotrade-app-engine`)에 바뀐 것은 하나다: `EngineMain.start`가 Guard(모두 OFF) → 명령 채널 다음에 `HeartbeatWriter`를 시작하고, `Engine.close()`가 명령 채널을 닫은 뒤 하트비트 파일을 지운다(EngineMain.java:83-93, 158-165). 엔진 빌드에 `autotrade-monitor-agent-jar` 의존이 늘었다(autotrade-app-engine/build.gradle.kts:26-27). 이 jar는 이미 실전 경로 검사 대상 모듈 목록에 들어 있어(RealPathReflectionTest.java:88-91, 120) 새로 들어간 `Heartbeat`·`HeartbeatWriter`·`AtomicFile`도 허용 목록 검사를 받았고 통과했다.
- `HeartbeatWriter`는 데몬 스레드 하나에서 고정 지연으로 쓰고(HeartbeatWriter.java:60-66), 쓰기 실패는 `IOException | RuntimeException`을 잡아 상태가 바뀔 때만 로그(85), 예약 작업 밖으로는 `Throwable`도 내보내지 않는다(96). `start()`는 호출 스레드에서 파일 입출력을 하지 않으므로 기록 실패가 엔진 기동을 막을 수 없다. 위 임시 시험으로 직접 확인했다.
- 감시(`autotrade-app-watchdog`)는 프로세스를 죽이거나 띄우지 않고(`WATCHDOG_KILLS_NO_PROCESS`, RealPathReflectionTest `ALLOWED_PROCESS_LAUNCHERS` 빈 목록 유지, 둘 다 변경 없음), 수집기·실험 정지 요청 파일 두 개만 만들고·다시 쓰고·지운다(StopRequests.java). 실전 엔진에는 아무 신호도 보내지 않는다. 엔진 하트비트가 오래되면 ERROR 로그뿐이다(HeartbeatWatch.java 로그 분기).
- 게이트웨이·실험실은 하트비트 기록만 더했다(GatewayMain.java:69-74, AutotradeLabApplication.java:19-36).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·설정 로더 변경 없음(diff 파일 목록). 엔진 기동 순서는 Guard(모두 OFF) 먼저, 하트비트는 마지막(EngineMain.java:83-93). 하트비트 파일에는 `process·pid·started-at·written-at·seq`만 있고 엔진이 이 파일을 읽지 않는다(Heartbeat.java `format`, 엔진 쪽 읽기 코드 없음). `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다` 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 변경 없음. `ArchitectureTest`·`RealPathReflectionTest` 파일 변경 없음(diff에서 바뀐 시험은 `StopRequestPathTest`와 감시·코어 시험뿐). `ArchitectureTest#실전_주문_출구는_Guard_팩토리만_연다`, `#Guard를_올리는_조작은_명령_채널에서만_한다` 포함 `:autotrade-architecture-test:test` 42건 통과. 엔진에 새로 들어온 코어 클래스도 실전 경로 허용 목록 검사 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. 하트비트는 프로세스 단위이고 계좌 상태에 닿지 않는다 |
| A8 | 통과 | 하트비트 전달은 파일이라 감시가 죽거나 멈춰도 쓰는 쪽(엔진)은 기다리지 않는다(HeartbeatWriter.java 전체, 소켓·대기 없음). 기록 실패가 엔진을 멈추지 않음: `HeartbeatTest#쓰지_못해도_던지지_않고_고쳐지면_다시_쓴다` + 위 엔진 임시 시험(하트비트 폴더 차단 상태에서 엔진 기동·명령 채널 응답). 감시는 5.2 0번(단발 일괄 명령 정지 요청 파일)만 하고 실전 엔진을 건드리지 않는다(Watchdog.java:61-83). 정지 순서 1~6번과 "보유 종목 보호 끝까지 유지"는 바뀌지 않았다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드와 문서(6.1) 변경 없음(diff 파일 목록, broker-and-fault-design.md diff는 5.1 표·5.2 0번·6장 #8·#9·#10 감지 칸만) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(grep). 설정 예시는 자리표시자(`C:\Users\<사용자>\.autotrade`, config-keys.md). 로그·예외 문구는 경로·예외 클래스 이름만 담는다(HeartbeatWriter.java:85-90, StopRequests.java `refresh`·`release`, WatchdogSettings 문제 목록). 시험은 `@TempDir`만 쓴다. gitleaks는 로컬 미실행(CI 실행) |
| A11 | 실패 | 보호 시험 삭제·약화 없음: `StopRequestPathTest`는 `constant()`를 필드 이름 인자로 일반화하고 시험 1건을 더했을 뿐 기존 단정은 그대로다(diff에서 지운 줄은 `getDeclaredField("STOP_REQUEST")` 한 줄, 같은 동작으로 대체). 감시·코어의 새 동작에는 실패 경우 시험이 있다(`HeartbeatTest` 6건 중 쓰기 실패 주입, `StopRequestsTest#쓰지_못해도_던지지_않고_다음_점검에_다시_쓴다`, `WatchdogTest#드라이브를_읽지_못하면_판단_못_함이라_정지_요청을_풀지_않는다`, `WatchdogSettingsTest#틀린_값은_모두_모아_거부한다`). **그러나 실전 엔진의 새 동작(하트비트 시작·정상 종료 때 삭제·기록 실패에도 엔진 기동 유지)에 엔진 단위 시험이 없다**(`autotrade-app-engine/src/test`에 heartbeat 참조 0건). CI 단계가 생성·삭제는 보지만 실패 경우는 보지 않는다. → K1 |
| A12 | 통과 | 장애표 6장 17종(#1~#17 행 수 확인)과 해외 O1~O8의 행 수·대응은 그대로이고, #8·#9·#10의 **감지** 칸만 하트비트 파일·정지 요청 파일 수정 시각으로 구체화됐으며 그 감지에 시험이 있다(`HeartbeatWatchTest#살아_있음_멈춤_꺼짐을_가르고_바뀔_때만_알린다`, `WatchdogTest#점검마다_자기_하트비트를_쓰고_다른_프로세스_하트비트를_판정한다`, `StopRequestsTest#위험이면_두_명령의_정지_요청_파일을_만들고_점검마다_확인_시각을_갱신한다`, `StopRequestPathTest#감시가_만드는_정지_요청_파일은_명령들이_보는_파일과_같다`). #8 "자동 재기동"은 구현되지 않은 채 D-092 범위 밖 표에 7단계 후속으로 남아 있고, 이 PR이 그 전제를 약화하지 않는다. 장애 시험 8종(7장)의 시나리오·합격 기준 변경 없음 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 하트비트·정지 요청 시각은 프로세스 생존용 `Instant`(UTC)이며 거래소 시간 계산에 쓰이지 않는다 |
| A14 | 실패 | 결정(D-092)·기획서(MON-02 v14.35)·장애표·config-keys는 코드와 대체로 맞는다(감시 설정 키 6개, 기본값 5/15/engine,gateway,lab/설정 폴더/50:20:1/300이 WatchdogSettings.java:40-56과 일치, 선정 일괄 명령 파일 미생성도 D-092 5번에 이유와 함께 기록). 그러나 (1) D-092의 ADR-007 링크가 없는 파일을 가리킨다: `../adr/ADR-007-selection-profile-and-combination.md`(D-092 6행·근거 줄), 실제 파일은 `docs/adr/ADR-007-selection-combination.md` → K2. (2) 감시 생존 판단 절차(runbook collector.md:60, broker-and-fault-design.md #10)가 거부 메시지의 시각을 "지금보다 15초 넘게 앞이면"으로 비교하라고 하지만, 그 시각은 UTC `Instant`(끝 `Z`, RunGuard.java:79-81, ExperimentGate·ReplayCheckGate 같음)이고 문구는 "만든 시각"인데 실제 값은 감시가 마지막으로 다시 쓴 시각이며, 15초는 `interval-seconds`(1~60 설정 가능)에 따라 달라진다 → K3 |

## 점검표 밖 발견
- **K1(수정 필요, A11)**: 실전 엔진 하트비트 연결에 엔진 단위 시험을 더한다. 최소: ① `EngineMain.start` 뒤 `<설정 폴더>/heartbeat/engine.hb`가 생기고 `Engine.close()` 뒤 없어진다, ② 하트비트 폴더를 만들 수 없게 막아도(예: `heartbeat` 자리에 파일) `EngineMain.start`가 성공하고 Guard는 모두 OFF이며 명령 채널이 `status`에 응답한다. 검토 중 임시 시험으로 지금 코드가 둘 다 지킨다는 것은 확인했으므로 코드 수정은 필요 없고, 회귀 방지 시험만 필요하다(뒤에 누가 `start()`에서 첫 기록을 동기로 바꾸면 엔진 기동이 디스크 상태에 묶인다).
- **K2(수정 필요, A14)**: D-092의 ADR-007 링크를 `../adr/ADR-007-selection-combination.md`로 고친다.
- **K3(수정 필요, A14)**: 수집기 runbook 3.5(collector.md:60)와 장애표 #10 감지 문구에 ① 거부 메시지의 시각은 UTC(끝 `Z`, 한국 시각 = +9시간)이고 ② 메시지에 "만든 시각"으로 찍히지만 감시가 켜져 있으면 마지막 확인(다시 쓴) 시각이며 ③ 기준은 `interval-seconds`의 3배(기본 15초)라는 점을 적는다. 이 절차가 #10의 유일한 사람 쪽 감지 수단이라, UTC를 한국 시각으로 읽으면 감시가 늘 죽은 것으로 판단하게 된다. 명령 문구를 바꾸는 것도 방법이지만 D-092는 명령 코드를 바꾸지 않기로 했으므로 문서 수정으로 충분하다.
- R1(권고): `HeartbeatWatch`는 `written-at`이 미래면 나이가 음수라 늘 "살아 있음"이다(HeartbeatWatch.java:67-68). 시계가 뒤로 크게 움직인 뒤 엔진이 죽으면 그 차이만큼 멈춤 판정이 늦어진다. 음수 나이가 기준을 넘으면 "읽을 수 없음"으로 보거나 경고 로그를 남기는 것을 권한다.
- R2(권고): 실전 엔진 멈춤(#8)의 알림이 아직 감시 로그 ERROR 한 줄뿐이다(D-092 범위 밖 표에 MON-06 후속으로 기록). 6단계 운영 절차서에 감시 로그 확인을 넣고, 7단계 실전 활성화 전 조건(알림 채널 연결)으로 남겨 둔다.
- R3(권고): `disks`에 직접 적은 폴더가 기동 때 없으면(예: 외장 녹화 드라이브가 늦게 붙음) 감시 전체가 뜨지 않아 하트비트 감시까지 사라진다(WatchdogSettings.java:202, PR #115 K2를 따른 의도된 선택). runbook에 "감시가 종료 코드 2로 안 뜨면 그동안 하트비트·디스크 감시도 없다"를 적어 두기를 권한다.
- R4(권고): 게이트웨이·실험실 하트비트 연결에는 시험이 없다(CI도 엔진·감시 하트비트만 확인). 실전 경로는 아니므로 비차단.
- R5(참고): `ed34018`에서 `WatchdogSettings.parse`가 기본 드라이브일 때 설정 폴더를 만든다(WatchdogSettings.java:180). 검사만 하는 `WatchdogMain.run`도 폴더를 만든다. 해롭지는 않으나 "파싱"에 부수 효과가 생긴 점을 클래스 문서에 남기면 좋다.
- R6(참고): `HeartbeatWriter.close()`는 잠금을 잡은 채 기다리므로, 느린 드라이브에서 기록이 막혀 있으면 엔진 정상 종료가 그만큼 늦어진다(HeartbeatWriter.java:72, 111). 설정 폴더는 로컬 드라이브 권장(D-092 위험과 한계)과 같은 조건이라 비차단.
- 확인: 검토 시작 때 작업 폴더에 커밋되지 않은 `docs/qa/pending.md` 수정(`PR #(번호)` → `PR #116`)이 있었고, `ed34018`에 커밋되어 지금 브랜치의 대기 목록 행은 `PR #116`이다.

## 판정
수정 필요(K1: 실전 엔진 하트비트 엔진 단위 시험 추가, K2: D-092의 ADR-007 링크 수정, K3: runbook·장애표 #10 감시 생존 판단 문구에 UTC·실제 의미·기준 주기 명시)

## 재검토 2026-10-15
- 대상: PR #116 / 브랜치 `claude/project-thread-0ganrn` / 커밋 `9cd4330`, `ed34018`, `bf337f0`, `568e718`, `e75267a`(HEAD) / `origin/main...HEAD` diff 전체. 1차 검토 뒤 바뀐 부분(`ed34018..e75267a`, 22개 파일)은 한 줄씩 읽었고, 판정은 PR 전체를 기준으로 다시 냈다.
- 기록 위치: 요청은 `risk-ops-2.md`에 쓰라는 것이었다. 그러나 검토 규칙상 원문은 docs/reviews/README.md 형식을 따르고, 그 형식은 재검토를 같은 파일 아래 `## 재검토 <날짜>`로 덧붙이며 앞 내용은 지우지 않는다고 정한다. 그래서 이 파일에 덧붙였다. 위의 1차 원문은 한 글자도 바꾸지 않았다. 커밋 `568e718`의 이 파일과 1차로 쓴 내용의 md5가 같다(`a7a5358…`).
- 점검표: A
- 직접 실행한 것:
  - `e75267a`에서 `./gradlew build --offline --no-build-cache --rerun-tasks`를 돌렸고 **BUILD SUCCESSFUL**이다. 전체 시험 1217건, 실패 0, 오류 0, 건너뜀 0. 모듈별 결과(시험/실패/오류/건너뜀)는 아래와 같다.
    - `:autotrade-risk:test` 55/0/0/0
    - `:autotrade-architecture-test:test` 42/0/0/0
    - `:autotrade-app-engine:test` 52/0/0/0
    - `:autotrade-app-watchdog:test` 23/0/0/0
    - `:autotrade-monitor-agent-jar:test` 22/0/0/0
    - `:autotrade-app-gateway:test` 50/0/0/0
    - `:autotrade-app-lab:test` 178/0/0/0
  - K1 시험이 회귀를 실제로 잡는지 변이 시험으로 확인했다. `e75267a`를 별도 worktree에 두고 운영 코드를 바꾼 뒤 시험을 돌렸고, 끝나면 worktree째 지웠다. 저장소 작업 폴더는 깨끗하다(`git status` 빈 결과).
    - (a) `Engine.close()`의 `heartbeat.close()`를 주석으로 막으면 `EngineMainTest#기동하면_하트비트를_쓰고_닫으면_지운다`가 **실패**한다.
    - (b) `EngineMain.start`에 "첫 기록이 실패하면 `IOException`"을 넣어 기록 실패가 엔진 기동을 막게 만들면 `EngineMainTest#하트비트를_쓰지_못해도_엔진은_뜨고_Guard는_OFF이며_상태에_응답한다`가 **실패**한다.

### 1차 지적 처리
| 지적 | 처리 | 근거 |
|---|---|---|
| K1 엔진 하트비트 엔진 단위 시험 | 해소 | 시험 2건을 더했다. 1번은 생성과 닫을 때 삭제를 보고 `written-at`까지 확인한다. 2번은 하트비트 폴더를 막아도 엔진이 기동하고 `beat()`가 false이며, 상태 응답에서 계좌가 OFF이고 `guard().state`도 OFF인지 본다(EngineMainTest.java:110-142). 위 변이 (a)(b)를 모두 잡는다. 덧붙여 `Engine.close()`가 `try/finally`로 바뀌어 채널 닫기가 실패해도 하트비트 파일은 지운다(EngineMain.java:160-167) |
| K2 D-092 ADR-007 링크 | 해소 | `../adr/ADR-007-selection-combination.md`로 고쳤다. D-092와 새 ADR-011의 상대 링크가 모두 실제 파일을 가리키는지 확인했고 빠진 링크는 없다 |
| K3 감시 생존 판단 문구 | 해소 | runbook collector.md:60에 세 가지를 적었다: 시각은 UTC(끝 `Z`, 한국 시각은 +9시간)이고, "만든 시각"은 실제로 마지막으로 다시 쓴 시각이며, 기준은 `interval-seconds`의 3배(기본 15초)다. `watchdog.hb`의 `written-at`도 UTC라는 점과, 파일이 없으면 감시가 꺼진 것이라는 점도 적었다. 장애표 #10(broker-and-fault-design.md)과 D-092 6번도 같은 내용으로 고쳤다 |
| R1 미래 `written-at` | 해소 | 나이를 절댓값으로 본다(HeartbeatWatch.java:69). `HeartbeatWatchTest#기록_시각이_기준보다_많이_미래면_멈춤으로_본다`(9시간 미래면 STALE) |
| R3 `disks` 폴더가 없으면 감시 전체가 안 뜸 | 문서화 | runbook에 "감시는 설정이 틀리거나 `disks`에 적은 폴더가 없으면 뜨지 않는다"를 적었다 |
| R2·R4·R5·R6 | R4 해소, 나머지 비차단 유지 | R4는 `GatewayMainTest#하트비트를_쓰고_닫으면_지운다`와 `LabHeartbeatTest#설정_폴더에_하트비트를_쓰고_컨텍스트가_닫히면_지운다`로 해소됐다. R2(엔진 멈춤 알림 채널)는 D-092 범위 밖 표의 MON-06 후속으로 남는다. 7단계 실전 활성화 전에 확인할 항목이다 |

### 이번에 바뀐 코드의 실전 영향
- `HeartbeatWatch`에 재기동 감지를 넣었다(같은 프로세스의 `started-at`이 바뀌면 WARNING, HeartbeatWatch.java:77-83). 로그만 남기고 아무 동작도 하지 않는다.
- `StopRequests.adoptLeftovers`는 남은 파일의 `requested-at`을 이어 쓴다(StopRequests.java:72-76, 109-120). 읽지 못하면 지금 시각을 쓴다. 이 값은 해제 판단에 쓰이지 않는다. 해제는 `clearSince` 기준이라 정지 요청 해제 조건은 바뀌지 않았다.
- `WatchdogSettings`는 빈 `processes`와 같은 폴더를 두 번 적은 `disks`도 기동 거부 대상에 넣었다. 틀린 설정을 더 잡는 쪽(fail-closed)이다.
- `AtomicFile`의 임시 파일 이름에 PID를 붙였다(AtomicFile.java:20). `ProcessHandle.current().pid()`는 실전 경로 허용 목록 안이다. `RealPathReflectionTest` 통과, `WATCHDOG_KILLS_NO_PROCESS`는 `destroy*`만 막으므로 해당 없음.
- CI는 감시가 엔진 하트비트의 `ABSENT → ALIVE`를 본 뒤에 엔진을 끄고, `ALIVE → ABSENT`를 기다리도록 바뀌었다. 고정 `sleep 6`을 조건 대기로 바꿨다.
- 실전 주문 경로, Guard, 블랙리스트, 리스크 한도, 보호 시세 경로에는 변경이 없다. 엔진 쪽 운영 코드 변경은 `Engine.close()`의 `try/finally` 하나뿐이다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·설정 로더 변경 없음. 엔진 기동 순서는 Guard(모두 OFF) → 명령 채널 → 하트비트다(EngineMain.java:83-93). 하트비트 기록이 실패해도 Guard가 OFF임을 엔진 시험으로 고정했다(`EngineMainTest#하트비트를_쓰지_못해도_엔진은_뜨고_Guard는_OFF이며_상태에_응답한다`). `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`·`#재기동하면_열려_있던_계좌도_OFF로_시작한다` 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`ArchitectureTest`·`RealPathReflectionTest` 파일 변경 없음. 엔진에 새로 들어온 monitor 코어(PID를 붙인 `AtomicFile` 포함)도 허용 목록 검사를 통과했다. `:autotrade-architecture-test:test` 42건 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. 하트비트는 프로세스 단위다 |
| A8 | 통과 | 하트비트는 파일 전달이고 데몬 스레드에서 쓰며, 실패해도 엔진에 닿지 않는다. HeartbeatWriter.java:60-66과 85-96, 그리고 변이 (b)로 확인한 엔진 시험이 근거다. 감시는 5.2 0번 정지 요청 파일만 다루고 엔진에는 신호를 보내지 않는다(Watchdog.java `tick`). 정지 순서 1~6번과 보유 종목 보호 유지 원칙은 바뀌지 않았다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드와 6.1 문서 변경 없음 |
| A10 | 통과 | 추가된 diff에도 키·계좌번호·토큰 없음(grep). 로그는 프로세스 이름·시각·경로만 남긴다(재기동 감지 로그 HeartbeatWatch.java:81). 시험은 `@TempDir`만 쓴다. gitleaks는 로컬에 없어 CI에서 실행된다 |
| A11 | 통과 | 보호 시험 삭제·약화 없음(`StopRequestPathTest`는 1차와 같고, 기존 단정을 유지한 채 시험 1건이 늘었다). 새 동작마다 실패 경우를 포함한 시험이 있다. 엔진은 K1 시험 2건으로 변이를 잡는다. 게이트웨이·실험실은 생성과 삭제를 시험한다. 감시 쪽은 미래 시각, 점검 사이 재기동, 빈 `processes`·같은 폴더 두 번 거부, 남은 파일의 `requested-at` 보존을 시험한다 |
| A12 | 통과 | 장애표 6장 17종과 O1~O8의 행 수·대응은 그대로다. #10 감지 문구만 실제 동작(UTC, 마지막으로 다시 쓴 시각, 점검 주기 3배)에 맞췄다. 장애 시험 8종(7장)의 시나리오·합격 기준 변경 없음 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 하트비트·정지 요청 시각은 프로세스 생존 판단용 UTC `Instant`다 |
| A14 | 통과 | D-092의 링크 오류를 고쳤다. D-092 3·6번, 검증 목록, broker-and-fault-design.md 5.1 표와 장애표 #8~#10, config-keys(`processes` 비우면 거부, `disks` 중복이면 거부), runbook 3.5가 코드와 맞는다. 하트비트 파일 계약과 감시 설정 원천은 ADR-011(제안, 사용자 머지로 확정)과 ADR-001 표에 기록됐다 |

### 점검표 밖 발견(비차단)
- N1(참고): 임시 파일 이름에 PID가 붙어서, 쓰는 도중에 프로세스가 죽으면 `<이름>.hb.<pid>.tmp`가 남는다. 다음 실행은 PID가 달라 이 파일을 덮어쓰지 않으므로 계속 남는다. 수백 바이트 파일이라 디스크 영향은 없다. 감시는 `<프로세스>.hb` 이름만 읽으므로(HeartbeatWatch `check`) 판정에도 영향이 없다. 나중에 정리 규칙을 넣을지는 선택이다.
- N2(참고): 재기동 감지 로그(HeartbeatWatch.java:77-83)는 시험이 "상태 변화 없음"만 확인하고 로그가 나오는지는 확인하지 않는다. 로그만 남기는 기능이라 비차단이다.
- R2(유지): 실전 엔진 멈춤(#8)은 감시 로그 ERROR로만 알린다. 7단계 실전 활성화 전 조건(알림 채널 연결, MON-06)으로 남긴다.

### 판정
승인
