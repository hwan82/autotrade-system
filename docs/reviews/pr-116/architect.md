# architect 검토: 감시 프로세스 1차: 하트비트·드라이브 여유·정지 요청 파일(MON-02, D-092)
- 대상: PR #116 / 브랜치 claude/project-thread-0ganrn / 커밋 9cd4330 / `git diff origin/main...HEAD` 전체(30파일, +1777 −23)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL. 다만 대부분 UP-TO-DATE(이전 실행 캐시)라 아래로 다시 확인.
  - `./gradlew build --rerun-tasks` 1회차 → `:autotrade-app-lab:test` 실패: "Could not write XML test results"(같은 저장소에서 다른 검토자의 Gradle 실행이 동시에 돌아 build 폴더가 겹침. 테스트 단언 실패가 아님). 동시 실행이 끝난 뒤 재실행.
  - `./gradlew build --rerun-tasks` 2회차 → **BUILD SUCCESSFUL, 136 tasks 모두 실행**. 결과 XML 집계: app-watchdog 22건, monitor-agent-jar 22건, app-engine 52건, architecture-test 42건, 실패·오류·건너뜀 0.
- 참고: 작업 트리에 커밋되지 않은 `docs/qa/pending.md` 변경(`PR #(번호)` → `PR #116`)이 있다. 검토 대상은 커밋 9cd4330이고, 그 커밋에는 자리표시자 `PR #(번호)`가 남아 있다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --rerun-tasks` 실행, BUILD SUCCESSFUL(136 executed). 1회차 실패는 동시 Gradle 실행에 따른 결과 파일 쓰기 오류로, 재실행에서 해소 |
| B2 | 통과 | 순수 모듈(domain·strategy·sim·risk) 변경 없음. `autotrade-monitor-agent-jar`는 JDK 타입만 씀(AtomicFile·Heartbeat·HeartbeatWriter). engine·gateway·lab·watchdog `verifyProcessIsolation` 통과, watchdog `forbiddenProjects` 변경 없음(autotrade-app-watchdog/build.gradle.kts). `RealPathReflectionTest` 변경 없이 통과(`ProcessHandle`은 기존 `ALLOWED_JDK_CLASSES`, RealPathReflectionTest.java:270). `ArchitectureTest`·`ModuleOwnershipTest` 변경 없음, 새 클래스는 기존 소유 패키지(`com.autotrade.monitor`, `com.autotrade.app.watchdog`) 안 |
| B3 | 실패 | 새 계약 세 가지가 ADR 없이 결정 로그(D-092)와 config-keys.md로만 들어왔다: ① 프로세스 간 파일 계약 `<설정 폴더>/heartbeat/<이름>.hb`(다섯 줄 형식, 정상 종료 시 삭제 의미) ② 새 설정 파일 `watchdog.properties`(일반 설정 체계 "깨진 파일은 빼고 기동"과 반대로 기동 거부) ③ 모듈 의존 변경: engine·gateway·lab이 `autotrade-monitor-agent-jar`에 새로 의존하고 코어에 Heartbeat·HeartbeatWriter·AtomicFile이 생겼으나 ADR-001 모듈 표(docs/adr/ADR-001-module-structure.md:57 "MetricSource, Reading, Threshold, ThresholdEvaluator, MonitorAgent")와 프로세스 표는 그대로다. 아래 A1 |
| B4 | 통과 | 비즈니스 코드는 주입된 `Clock`만 씀(Watchdog.java:62, HeartbeatWriter 생성자, HeartbeatWatch.check(now)). 시스템 시계는 진입점에서만(WatchdogMain.java `Clock.systemUTC()`). 난수 없음. 시뮬레이션 코드·골든 테스트 변경 없음, 전체 재실행 통과 |
| B5 | 해당 없음 | 금액 계산 없음. 드라이브 GB는 `double`(Threshold 계약이 double)이며 금액이 아니다. 설정 파싱은 `BigDecimal` 경유(WatchdogSettings.java:118-119) |
| B6 | 실패 | 감시 쪽은 테스트가 충실하다(HeartbeatTest 6, WatchdogSettingsTest 5, HeartbeatWatchTest 2, StopRequestsTest 5, WatchdogTest 5, WatchdogMainTest 3, StopRequestPathTest 1 추가, 모두 통과). 그러나 **하트비트를 쓰는 쪽의 연결이 시험되지 않는다**: EngineMain.start가 `heartbeat/engine.hb`를 쓰고 `Engine.close()`가 지우는지(EngineMain.java:91, :163), 실험실 `startHeartbeat`(AutotradeLabApplication.java:26-38), 게이트웨이 종료 훅(GatewayMain.java:70-74)에 테스트가 없다(`grep heartbeat` → engine·gateway·lab 테스트 0건). 장애표 #8 감지는 실전 엔진이 이 파일을 쓰는 것에 전부 기댄다. 아래 A2. 지우거나 건너뛴 테스트는 없음 |
| B7 | risk-ops 검토 필요 | 실전 엔진 프로세스(`autotrade-app-engine`)의 기동·종료 경로와 의존이 바뀌고(EngineMain.java:91-93, :156-164, build.gradle.kts 하트비트 의존), 장애 대응(5.2 0번, 장애표 #8·#9·#10)과 보호 테스트 인접 파일(`StopRequestPathTest`)을 바꾼다. 지침 10장에 따라 사용자 머지 대상 |
| B8 | 통과 | 비밀값 없음. 로그는 파일 경로·예외 클래스 이름만(HeartbeatWriter, StopRequests.java:287·306, Watchdog.java:71-81). config-keys.md 예시는 `<사용자>` 자리표시자. 감시 설정 파일에 비밀값을 넣지 않는다고 명시 |

## 지적 (수정 필요)

### A1. 계약 변경에 ADR이 없다 (B3)
- 하트비트 파일 형식·위치·"정상 종료면 삭제" 의미는 engine·gateway·lab(쓰는 쪽)과 watchdog(읽는 쪽) 사이 계약이다. 앞으로 7단계 자동 재기동·업무 진행 신호(D-092 위험 1번)가 같은 파일에 실릴 예정이라 형식 버전·확장 규칙이 필요하다.
- `watchdog.properties`는 ADR-001 설정 체계(내장 default → runtime-config.json → secrets.json, 깨진 파일은 빼고 기동)에 없는 네 번째 설정 원천이고, 실패 시 동작이 반대(기동 거부)다. 이유(K2)는 D-092 7번에 있으나 설정 체계의 예외로 ADR에 남아야 한다.
- ADR-001 모듈 표(:57)와 프로세스 의존 설명에 engine·gateway·lab → monitor-agent-jar 의존과 코어의 새 클래스(Heartbeat, HeartbeatWriter, AtomicFile)를 반영해야 한다.
- 요청: ADR-001 보강(모듈 표·의존) + 하트비트 파일 계약과 감시 설정 파일을 다루는 ADR(새 ADR-011 또는 ADR-001 새 절). D-092는 그 ADR을 링크.

### A2. 쓰는 쪽 하트비트 연결 테스트 없음 (B6)
- 최소: EngineMainTest에 "start 뒤 `<dir>/heartbeat/engine.hb`가 생기고 process=engine, close 뒤 없어진다". 실전 엔진에서 하트비트 쓰기 실패가 기동을 막지 않는지(예: `heartbeat` 자리에 파일을 두고 `EngineMain.start`가 성공하고 Guard·명령 채널이 정상) 한 건.
- 실험실 `AutotradeLabApplication.startHeartbeat`: 컨텍스트 닫힘에서 파일이 지워지는지 한 건(스프링 컨텍스트 또는 가짜 `ConfigurableApplicationContext`).
- 게이트웨이는 종료 훅 안이라 단위 시험이 어렵다면 그 사유를 D-092 검증 절에 "검증하지 못한 것"으로 적는다.

### A3. 감시 설정 검증의 빈틈 (K2 원칙과 어긋남)
- `disks`에 같은 경로를 두 번 쓰면 거부하지 않는다(WatchdogSettings.java:174-199). 그러면 `DiskFreeSource.read()`가 같은 이름 값을 두 번 내고, `ThresholdEvaluator.evaluate`가 같은 상태를 한 주기에 두 번 세어 `disk.free-gb`의 연속 횟수(sustain) 의미가 깨진다(sustain=2가 한 번의 점검으로 충족). 중복 경로는 문제로 모아 기동 거부하거나 정규화 후 중복 제거한다. 테스트 한 건.
- `processes=`(빈 값)를 받아들여 아무 하트비트도 보지 않는 감시가 조용히 뜬다(WatchdogSettings.java:157-172는 빈 목록에 문제를 더하지 않음). 의도한 "끄기"라면 config-keys.md에 적고, 아니면 거부한다.

### A4. 감시 생존 판정 "15초"가 설정과 묶여 있지 않다 (문서)
- runbook(docs/runbooks/collector.md:60), 장애표 #10(broker-and-fault-design.md:169), D-092 6번은 "정지 요청 파일 시각이 15초 넘게 앞이면 감시가 죽음"으로 고정한다. 그러나 `interval-seconds`는 1~60까지 허용된다(WatchdogSettings.java:101). 주기를 20초 이상으로 두면 살아 있는 감시가 문서 기준으로는 죽은 것으로 보인다. 문서를 "`heartbeat-stale-seconds`(기본 15초)"로 바꾸거나, 설정에서 `interval-seconds × 3 ≤ heartbeat-stale-seconds`를 강제한다.

### A5. 커밋에 남은 자리표시자
- 커밋 9cd4330의 `docs/qa/pending.md` 마지막 행이 `PR #(번호) 검토해줘`, `docs/reviews/pr-(번호)/qa.md`이다. 작업 트리에만 고친 내용이 있다. 커밋해야 QA 대기 목록이 맞는다.

## 점검표 밖 발견 (권고, 판정에 넣지 않음)
- R1. `Engine.close()`는 `server.close()` 다음에 `heartbeat.close()`를 부른다(EngineMain.java:160-164). `ControlServer.close`가 런타임 예외를 던지면 하트비트 파일이 남아 정상 종료가 "멈춤"(ERROR, 장애표 #8)으로 보인다. 하트비트를 먼저 닫거나 try/finally.
- R2. `StopRequests.adoptLeftovers`는 남은 파일의 `requested-at`을 감시 기동 시각으로 덮어쓴다(StopRequests.java:232-241). 사람이 "언제부터 켜져 있었나"를 알 수 없게 된다. 남은 파일의 `requested-at`을 읽어 보존하는 편이 낫다.
- R3. `StopRequests.release`에서 한 파일만 지우고 다른 파일 삭제가 실패하면 같은 `update` 안에서 `refresh`가 지운 파일을 다시 만든다(StopRequests.java:260-267, 298-317). 결과적으로 "켜짐 유지"로 일관되므로 안전 쪽이지만, 의도라면 주석으로 남긴다.
- R4. `HeartbeatWatch`는 `startedAt`이 바뀐 재기동(살아 있음 → 살아 있음)을 알리지 않는다. 장애표 #8 "재기동 후 대사"를 사람이 확인하려면 재기동 사건 로그가 있으면 좋다(후속).
- R5. 하트비트 임시 파일 이름이 `<이름>.hb.tmp` 하나라, 같은 설정 폴더로 같은 프로세스를 두 개 띄우면 서로 덮어쓴다(Q-018 두 번째 엔진과 엮임). 지금은 해당 없음, 후속에서 pid를 붙이는 것을 검토.

## 판정
수정 필요(A1, A2, A3, A4, A5)
