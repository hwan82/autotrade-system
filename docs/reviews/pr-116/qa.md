# 독립 QA 검토: PR #116 감시 프로세스 1차: 하트비트·드라이브 여유·정지 요청 파일(MON-02, D-092)
- 대상: PR #116 / 헤드 93d7747 / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 40개 +2,247 −31: 감시 `Watchdog`·`WatchdogMain`·`WatchdogSettings`·`StopRequests`·`HeartbeatWatch`·`DiskFreeSource`, 코어 `Heartbeat`·`HeartbeatWriter`·`AtomicFile`, 실전 엔진 `EngineMain`과 의존 1줄, 게이트웨이·실험실 하트비트, `StopRequestPathTest`, CI 기동 확인, D-092·ADR-011·config-keys·장애 설계·runbook·기획서, 개발 쪽 검토 원문 3개). 현재 origin/main 88212d3과 `git merge-tree` 충돌 없음
- 점검표: A 전체 + B1, B6 + 과금 검사 (실전 관련 PR: 실전 엔진 코드·의존 변경, 장애 대응 5.2 0번·장애표 #8·#9·#10, 보호 테스트 모듈의 `StopRequestPathTest` 변경)
- 개발 쪽 원문(architect 1·2차, risk-ops)의 주장과 시험 결과는 확인할 주장으로만 봤다. 판정은 아래 직접 실행 결과와 코드로 냈다
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 93d7747, 별도 worktree): 429 재시도 뒤 2회째 **BUILD SUCCESSFUL**. 결과 XML 합계 **1,217건**, 실패·오류·건너뜀 0(`RealPathReflectionTest`, `ArchitectureTest`, `verifyProcessIsolation` 포함)
  - CI(헤드 93d7747): Gradle build(기동 확인 단계 포함), DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - 설치본 탐침(`installDist` 뒤 임시 설정 폴더, 끝나고 지움): T1~T4 아래
  - 변이 M1~M7(운영 코드 한 곳씩 고치고 해당 모듈 `test`, 매번 원복, `git status` 깨끗함 확인): 아래

## 탐침과 변이 결과
| # | 시도 | 결과 |
|---|---|---|
| T1 | 감시와 실전 엔진 설치본을 띄우고 감시 로그의 `engine ABSENT → ALIVE`를 본 뒤 엔진을 `kill -9` | 약 20초 뒤 감시가 `engine … ALIVE → STALE (마지막 기록 19초 전 …, 장애표 #8 …)`를 **SEVERE(ERROR)**로 남김. `heartbeat/engine.hb`는 남아 있음(비정상 종료는 파일을 남긴다는 D-092 1번과 일치). 감시는 엔진을 띄우거나 다른 조치를 하지 않음 |
| T2 | `watchdog.properties`에 `disk.free-gb=99999:99998:1`(실제 여유 약 29GB가 위험) | 첫 점검에 `collector-state/stop-request`·`experiment-state/stop-request` 둘 다 생성. 내용 `requested-at`·`confirmed-at`·`reason=disk.free-gb:<경로> 29GB CRITICAL`. 5초 뒤 수정 시각이 5초 늘어 점검마다 갱신됨 |
| T3 | T2 상태에서 감시를 `kill -9` → 파일 남음 확인 → 정상 설정(`disk.free-gb=1:1:1`, `release-hold-seconds=0`)으로 다시 띄움 | 기동 때 두 파일을 이어받는다는 WARNING 2줄, 다음 점검에 두 파일 모두 지움. 남은 엔진 하트비트(T1)는 기동 때 바로 STALE(ERROR)로 보고 |
| T4 | `heartbeat/engine.hb`를 64MB 쓰레기로 채우고 감시 기동 | `engine … → UNREADABLE` WARNING, 감시는 계속 점검. 예외·종료 없음(아래 R1) |
| M1 | `StopRequests` 해제 유지 시간 비교를 항상 참으로 | 잡힘(`StopRequestsTest` 2건, `WatchdogTest` 1건 FAILED) |
| M2 | 경고·판단 불가가 해제 유지 시간을 다시 세지 않게 | 잡힘 |
| M3 | 드라이브 값이 빠진 점검을 `UNKNOWN` 대신 `CLEAR`로 | 잡힘 |
| M4 | 하트비트 나이에서 `.abs()` 제거(미래 시각을 살아 있음으로) | 잡힘(`기록_시각이_기준보다_많이_미래면_멈춤으로_본다`) |
| M5 | `EngineMain.Engine.close()`에서 `heartbeat.close()` 제거 | 잡힘(`EngineMainTest` `기동하면_하트비트를_쓰고_닫으면_지운다`) |
| M6 | 위험이 이어질 때 `reason` 갱신 제거 | 잡힘 |
| M7 | `StopRequests.release()`에서 파일 하나라도 못 지우면 켜진 상태를 유지하는 조건(`if (ok)`)을 항상 참으로 | **살아남음**(watchdog 23건 모두 통과). 아래 Q1 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 코드 변경 없음. 엔진은 Guard를 만든 뒤 하트비트를 시작하고, `EngineMainTest`가 하트비트 폴더를 막아도 기동·Guard 모두 OFF·상태 응답을 단언. Guard 시험 전체 통과(빌드 1,217건) |
| A2 | 통과 | `ArchitectureTest`·`RealPathReflectionTest` 변경 없음. `StopRequestPathTest` 변경은 추가만(감시가 만드는 두 경로 = 수집기 `CollectorMain.Options.stopRequest()`·실험 `ExperimentGate.STOP_REQUEST`). 실전 엔진에 들어온 `autotrade-monitor-agent-jar`는 `RealPathReflectionTest`의 JDK 허용 목록 검사와 런타임 구성 고정 시험을 그대로 통과 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 실전 엔진에 더해진 것은 데몬 스레드 하나의 5초 주기 파일 쓰기다. `HeartbeatWriter`는 `IOException`·`RuntimeException`을 잡아 상태가 바뀔 때만 로그를 남기고, 예약 작업은 `Throwable`도 삼켜 다음 주기를 잃지 않는다. 엔진 업무 경로는 하트비트를 기다리지 않는다(`EngineMain.start`, 닫을 때만 `heartbeat.close()`). 감시는 프로세스를 죽이거나 띄우지 않는다(T1, `WATCHDOG_KILLS_NO_PROCESS` 통과). 정지 요청 파일은 위험에서만 켜고, 판단 못 하면 새로 켜지도 풀지도 않는다(M3) |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음. 하트비트 내용은 프로세스 이름·PID·시각·순번, 정지 요청 이유는 드라이브 경로와 GB 값뿐. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·약화 0건. 새 시험 감시 23건·코어 6건·엔진 2건 등, 변이 M1~M6을 잡는다. M7은 Q1 |
| A12 | 통과 | 장애표 #8·#9 감지 단서(하트비트 15초, 엔진은 ERROR)와 #10 감시 생존 확인(정지 요청 파일 수정 시각)이 코드와 맞음(T1·T2). 대응란의 자동 재기동은 D-092 범위 밖으로 적혀 있고 지금은 로그만이다. 장애 테스트 8종의 전제(실전 엔진·Guard 동작)는 바뀌지 않음 |
| A13 | 해당 없음 | 시장별 코드 없음. 시각은 `Instant`(UTC) |
| A14 | 통과 | D-092·ADR-011·config-keys "감시 프로세스 설정 파일"·장애 설계 5.1·5.2·6장·runbook 3.5·기획서 MON-02(v14.35)가 코드와 맞음(표본: 기본 5초·15초·50/20GB·300초 = `WatchdogSettings` 상수, 틀린 설정 종료 코드 2 = `WatchdogMain.EXIT_BAD_SETTINGS`와 CI 단계, 해제 기준 경고 경계 = `StopRequests.update`) |
| B1 | 통과 | 직접 빌드 1,217건 통과, CI 3건 success |
| B6 | 통과 | 실패 주입 시험(쓰기 실패, 읽기 실패, 틀린 설정, 미래 시각) 있음. 건너뛴 시험 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 서비스·라이브러리 없음(새 의존은 우리 모듈 `autotrade-monitor-agent-jar`뿐). 알림 발송은 범위 밖 |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **실전 엔진이 죽었는데 아무도 모름**: T1에서 20초 안에 ERROR. 다만 지금은 로그뿐이라 사람이 감시 창을 봐야 안다(알림은 MON-06 후속, D-092 범위 밖에 적힘).
2. **디스크가 차는데 수집·실험이 계속 돎**: T2에서 첫 점검에 두 정지 요청 파일 생성, 명령들이 보는 경로와 같음(`StopRequestPathTest`). 감시가 죽으면 파일이 남아 명령은 계속 멈춘 쪽으로 남고(T3 앞부분), 새 감시가 이어받아 정상일 때만 지운다(T3).
3. **하트비트 쓰기 실패가 실전 엔진을 멈춤**: `EngineMainTest`(폴더 막음)와 코드 읽기로 아니다. 남는 한계는 R3.
4. **정지 요청 파일 하나만 지워지고 하나는 남음**: 코드 주석과 D-092는 "둘은 늘 함께 켜지고 함께 꺼진다"고 하지만, 그 갈래를 지켜 주는 시험이 없다(M7 생존). 지금 코드는 맞게 동작한다 `[추론, 코드 읽기]`.

## 점검표 밖 발견(권고)
- **Q1 (시험) 부분 삭제 실패 갈래 시험 없음(M7 생존)**: `release()`가 파일 하나라도 못 지우면 켜진 상태를 유지해 다음 점검에 다시 쓰고 다시 지우는 동작을 시험이 고정하지 않는다. 이 조건이 빠지면 한 파일이 남은 채 감시는 꺼진 것으로 보고 더는 다시 쓰지도 지우지도 않아, 한 명령만 감시 재기동 때까지 막히고 수정 시각도 멈춘다(감시 생존 확인 단서가 "감시 죽음"으로 잘못 보임). 한 파일 삭제만 실패시키는 주입 시험을 권한다.
- **R1 (낮음) 하트비트·정지 요청·감시 설정 파일을 크기 상한 없이 읽는다**(`HeartbeatWatch.check`, `StopRequests.requestedAt`, `WatchdogSettings.load`). T4의 64MB는 "읽을 수 없음"으로 끝났지만 감시 힙보다 큰 파일이면 `OutOfMemoryError`다. 점검 루프는 `Throwable`을 삼키지만 기동 때(`adoptLeftovers`)는 감시가 뜨지 못한다. 쓰는 쪽이 우리 프로세스뿐이라 위험은 낮다. 읽기 상한(예: 4KB)을 권한다(PR #37 F1과 같은 원칙).
- **R2 (참고) 로그 한글이 비 UTF-8 콘솔에서 `??`로 보인다**: 이 컨테이너(POSIX 로캘)에서 감시·엔진 로그의 한글이 모두 `?`였다. 기존 `ProcessStartup` 로그도 같아 이 PR의 문제는 아니다. 장애표 #8 ERROR를 사람이 로그로 읽어야 하는 지금은, 6단계 첫 주 Windows 점검 항목(D-092 검증하지 못한 것)에 로그 한글 표시를 더하길 권한다.
- **R3 (참고) `HeartbeatWriter.close()`는 진행 중인 쓰기를 같은 잠금으로 기다린다**: 설정 폴더 드라이브가 멈추면(네트워크 드라이브 등) 엔진 정상 종료가 그 쓰기만큼 늦어진다. 업무 경로에는 영향이 없고 D-092가 로컬 드라이브를 권장한다.

## 판정
승인 (빌드 1,217건·CI 통과, main 충돌 없음. 엔진 kill -9 감지·디스크 위험 정지 요청·남은 파일 이어받기를 설치본으로 직접 확인. 변이 M1~M6 잡힘. 권고 Q1(M7 생존 시험), R1, 참고 R2·R3. 실전 관련이라 머지는 사용자)
