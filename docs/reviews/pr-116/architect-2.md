# architect 재검토: 감시 프로세스 1차: 하트비트·드라이브 여유·정지 요청 파일(MON-02, D-092)
- 대상: PR #116 / 브랜치 claude/project-thread-0ganrn / 커밋 e75267a / `git diff origin/main...HEAD` 전체. 1차 검토(커밋 9cd4330, [architect.md](architect.md)) 뒤 커밋 ed34018·bf337f0·568e718·e75267a를 포함한다
- 점검표: B
- 형식 메모: docs/reviews/README.md는 재검토를 같은 파일 아래 `## 재검토 <날짜>`로 덧붙이라고 한다. 이번에는 조율자 지시에 따라 원문 architect.md를 그대로 두고 이 파일에 따로 썼다. 원문 architect.md는 e75267a에 커밋됐고, 내가 쓴 52줄과 줄 수와 판정 줄이 같다(변경 흔적 없음)
- 직접 실행한 것:
  - 다른 Gradle 실행이 끝난 것을 확인한 뒤 `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL, 136 tasks 모두 실행**. 결과 XML 집계: app-watchdog 23, monitor-agent-jar 22, app-engine 52, app-gateway 50, app-lab 178, architecture-test 42건. 실패·오류·건너뜀 0
  - 새 테스트가 실제로 돌았는지 결과 XML에서 확인: `EngineMainTest#기동하면_하트비트를_쓰고_닫으면_지운다`, `EngineMainTest#하트비트를_쓰지_못해도_엔진은_뜨고_Guard는_OFF이며_상태에_응답한다`, `GatewayMainTest#하트비트를_쓰고_닫으면_지운다`, `LabHeartbeatTest#설정_폴더에_하트비트를_쓰고_컨텍스트가_닫히면_지운다`, `HeartbeatWatchTest`의 미래 시각·재기동 2건, `WatchdogSettingsTest#처음_쓰는_PC라_설정_폴더가_없으면_만들고_기본값으로_뜬다`
  - GitHub REST로 e75267a의 CI 체크 조회: `Gradle build` completed/success, `Secret scan and collector boundary` success, `DB schema (PostgreSQL 16)` success. CI의 프로세스 기동 단계(ci.yml에 새로 들어간 틀린 감시 설정 종료 코드 2, 감시·엔진 하트비트 생성·삭제, 감시 로그의 엔진 `ABSENT → ALIVE`·`ALIVE → ABSENT`)는 이 체크 안에서 돌았다. 로컬에서 따로 재현하지는 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --rerun-tasks` 실행, BUILD SUCCESSFUL(136 executed). CI `Gradle build`도 e75267a에서 success |
| B2 | 통과 | 순수 모듈 변경 없음. 새 코드는 기존 소유 패키지 안(`com.autotrade.monitor`, `com.autotrade.app.{engine,gateway,lab,watchdog}`). `AtomicFile`이 새로 쓰는 `ProcessHandle.current().pid()`는 기존 허용 목록 안(RealPathReflectionTest.java:270). monitor-agent-jar는 이미 실전 경로 검사 범위에 있다(RealPathReflectionTest.java:91, :120). `verifyProcessIsolation`·ArchUnit·`RealPathReflectionTest` 변경 없이 통과 |
| B3 | 통과 | 1차 A1 해소. [ADR-011](../../adr/ADR-011-watchdog-heartbeat.md)이 ① 하트비트 파일 계약(위치·형식·쓰기·실패·정상 종료 삭제·읽는 쪽·시작 지점) ② `watchdog.properties`가 기존 설정 체계의 예외(틀리면 기동 거부)임과 그 이유 ③ 모듈 의존(engine·gateway 직접, lab은 monitor-sources의 `api` 경유; autotrade-monitor-sources/build.gradle.kts:9로 확인)을 적었다. ADR-001 모듈 표(:54, :57) 갱신, ADR 목록(docs/adr/README.md) 추가, D-092에서 링크. config-keys.md에 새 거부 조건(빈 `processes`, 같은 폴더 두 번)과 설정 폴더 자동 생성 반영 |
| B4 | 통과 | 새 코드도 주입된 `Clock`만 씀(HeartbeatWatch는 `now` 인자, 시험은 `TestClock`·`FIXED`). 시스템 시계는 진입점과 시험(`Clock.systemUTC()`)에서만. 시뮬레이션 코드 변경 없음, 골든 테스트 포함 전체 재실행 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 1차 A2 해소: 실전 엔진 기동 때 `engine.hb` 생성과 종료 때 삭제, 하트비트 폴더를 막아도 엔진이 뜨고 Guard가 OFF이며 상태 조회에 응답하는지(EngineMainTest), 게이트웨이 `startHeartbeat`로 뽑아낸 함수(GatewayMain.java:79-82, GatewayMainTest), 실험실 컨텍스트 닫힘에서 삭제(LabHeartbeatTest)를 시험한다. 1차 A3 해소분(같은 폴더 두 번·빈 `processes` 거부, 설정 폴더 생성)도 WatchdogSettingsTest에 단언이 있다. 지우거나 건너뛴 테스트 없음 |
| B7 | risk-ops 검토 필요 | 실전 엔진 기동·종료 경로(EngineMain.java:91-93, `Engine.close` try/finally :160-167)와 의존, 장애 대응(5.2 0번, 장애표 #8·#9·#10), CI 기동 확인 단계를 바꾼다. docs/reviews/pr-116/risk-ops.md가 있다(그 판정은 이 검토의 근거로 쓰지 않았다). 지침 10장에 따라 사용자 머지 대상 |
| B8 | 통과 | 비밀값 없음. 새 로그(재기동 감지, HeartbeatWatch.java)는 프로세스 이름과 시작 시각만. ci.yml은 임시 폴더와 잘못된 키 이름만 씀 |

## 1차 지적 처리
| 1차 | 처리 | 근거 |
|---|---|---|
| A1 ADR 없음 | 해소 | ADR-011 신설, ADR-001 :54·:57, docs/adr/README.md, D-092 "계약" 줄 |
| A2 쓰는 쪽 연결 시험 없음 | 해소 | EngineMainTest 2건, GatewayMainTest 1건, LabHeartbeatTest 1건(결과 XML에서 실행 확인) |
| A3 설정 검증 빈틈 | 해소 | WatchdogSettings.java `processes`가 비면 문제 추가, `disks`는 `toAbsolutePath().normalize()` 뒤 중복이면 문제 추가. WatchdogSettingsTest가 `dir`와 `dir/.` 중복, `processes= `를 거부하는지 단언 |
| A4 "15초" 고정 | 해소 | runbook 3.5, 장애표 #10, D-092 6번이 "점검 주기(`interval-seconds`)의 3배(기본 15초)"로 바뀌었고, 파일 시각이 UTC이며 감시가 마지막으로 다시 쓴 시각이라는 점도 적었다 |
| A5 pending.md 자리표시자 | 해소 | docs/qa/pending.md 마지막 행이 `PR #116 검토해줘`, `docs/reviews/pr-116/qa.md` |
| R1 close 순서 | 반영 | EngineMain.java `Engine.close`가 try/finally로 하트비트를 닫음 |
| R2 requested-at 보존 | 반영 | StopRequests `requestedAt` 읽기, 가장 이른 값을 이어 씀. StopRequestsTest 단언 추가 |
| R3 부분 삭제 실패 | 반영(주석) | StopRequests.release에 "두 파일은 함께 켜지고 함께 꺼진다" 주석 |
| R4 재기동 알림 | 반영 | HeartbeatWatch가 `startedAt` 바뀜을 WARNING으로 남김(아래 N1) |
| R5 임시 파일 겹침 | 반영 | AtomicFile 임시 파일 이름에 PID(아래 N3) |

## 새로 본 것 (권고, 판정에 넣지 않음)
- N1. `HeartbeatWatchTest#점검_사이_재기동은_상태가_그대로여도_시작_시각으로_알아본다`는 상태 변화가 없고 ALIVE인 것만 단언한다. 재기동 로그가 나오는지는 시험하지 않는다(로그만 남기는 기능이라 비차단). 7단계 자동 재기동·대사 절차가 이 신호를 쓰게 되면 `check` 결과에 재기동 사건을 넣고 단언한다.
- N2. 기본 `disks`(설정 폴더)가 없으면 감시가 폴더를 만들고 뜬다(WatchdogSettings `disks`, ed34018). `AUTOTRADE_CONFIG_DIR`를 잘못 적으면 감시가 엉뚱한 빈 폴더를 지키며 엔진·게이트웨이·실험실을 계속 ABSENT(INFO 로그)로 본다. 기동 로그에 드라이브 경로가 찍히므로 사람이 알아볼 수는 있다. 감시 기동 로그에 "지키는 설정 폴더" 한 줄을 따로 두고, 처음 점검에서 세 프로세스가 모두 ABSENT면 WARNING을 한 번 남기는 것을 권한다.
- N3. PID가 붙은 임시 파일(`<이름>.hb.<pid>.tmp`, 정지 요청 파일 `stop-request.<pid>.tmp`)은 쓰는 도중 프로세스가 죽으면 남고, 다음 실행은 PID가 달라 거두지 않는다. 크기가 작고 판정에는 영향이 없다. 정리 규칙은 선택.
- N4. 장애표 #8 행(broker-and-fault-design.md:167)은 여전히 "`engine.hb`가 15초 넘게 오래되면"으로 고정 값을 쓴다. 기준은 `heartbeat-stale-seconds`(기본 15초)라고 적는 편이 정확하다(문서 표현만).

## 판정
승인
