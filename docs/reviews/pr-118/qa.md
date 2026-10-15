# 독립 QA 검토: PR #118 감시 보강: 파일 읽기 상한·남은 임시 파일 정리·부분 삭제 실패 시험·설정 폴더 경고(PR #116 후속)
- 대상: PR #118 / 헤드 1be34c0 / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 19개 +483 −23: 새 `SmallFile`, `HeartbeatWriter`·`HeartbeatWatch`·`StopRequests`·`WatchdogSettings`·`WatchdogMain`, 엔진·감시 `build.gradle.kts`의 `processIsolation`, 시험 4개 파일, ADR-011·config-keys·D-092·장애 설계 6장 #8·pending.md, 개발 쪽 검토 원문 2개). 현재 origin/main 792c8ff와 `git merge-tree` 충돌 없음(config-keys.md 자동 병합)
- 점검표: A 전체 + B1, B6 + 과금 검사 (실전 관련 PR: 실전 엔진 빌드 설정 `processIsolation`, 실전 엔진에 실리는 `autotrade-monitor-agent-jar`의 `HeartbeatWriter`, 감시·장애 대응 #8·#10)
- 보호 테스트(`autotrade-architecture-test` 모듈) 변경: 없음. 고정 값(지문·허용 목록·소유 표) 변경 없음
- 개발 쪽 원문(architect·risk-ops 1차·재검토)과 PR 본문의 주장은 확인할 주장으로만 봤다. 판정은 아래 직접 실행 결과와 코드로 냈다
- 직접 실행한 것:
  - `./gradlew build`(헤드 1be34c0, 별도 worktree): Maven Central 429 재시도 뒤 11회째 **BUILD SUCCESSFUL**. 결과 XML 합계 **1,261건**, 실패·오류·건너뜀 0(`RealPathReflectionTest`, `ArchitectureTest`, `ModuleOwnershipTest`, `verifyProcessIsolation` 포함)
  - `./gradlew build`(헤드 + origin/main 792c8ff 병합본, 별도 worktree): **BUILD SUCCESSFUL**, **1,285건**, 실패·오류·건너뜀 0
  - CI(헤드 1be34c0): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - QA 탐침 시험 P1~P4(커밋하지 않은 `QaProbeTest`), 변이 V1~V8(운영 코드·빌드 설정 한 곳씩 고치고 해당 모듈 시험·`verifyProcessIsolation`, 매번 원복, `git status` 깨끗함 확인), 설치본 탐침 T1~T3(`installDist` 뒤 임시 설정 폴더, 끝나고 지움): 아래

## 탐침과 변이 결과
| # | 시도 | 결과 |
|---|---|---|
| P1 | `heartbeat/engine.hb`를 3GB 희소 파일로 만들고 `HeartbeatWatch.check` | `UNREADABLE`, 0.005초. 힙을 쓰지 않음(상한+1바이트까지만 읽음) |
| P2 | `collector-state/stop-request`를 3GB 희소 파일로 남기고 `StopRequests.adoptLeftovers` | 켜진 상태로 이어받음, 예외 없음 |
| P3 | `heartbeat/engine.hb` 자리가 폴더 | `UNREADABLE`(예외로 점검이 끊기지 않음) |
| P4 | 감시를 먼저 띄우고(모두 꺼짐) 이후 10회 점검 | 첫 점검에만 `firstCheckAllAbsent=true`, 이후 점검은 바뀜 없음·경고 없음. 아래 R1 |
| V1 | `SmallFile.read`의 `readNBytes(MAX_BYTES + 1)`를 `readAllBytes()`로(크기 검사는 그대로) | **기존 시험에서 살아남음**(`HeartbeatTest`·`HeartbeatWatchTest`·`StopRequestsTest`·`WatchdogSettingsTest` 모두 통과). P1이 잡음: 시험 실행기가 `OutOfMemoryError: Required array size too large: 3221225472`로 중단. 아래 Q1 |
| V2 | `StopRequests.adoptLeftovers`의 `deleteLeftoverTemps` 제거 | 잡힘 |
| V3 | `HeartbeatWriter` 생성자의 `deleteLeftoverTemps` 제거 | 잡힘 |
| V4 | 임시 파일 이름 가운데가 숫자인지 보는 조건을 항상 참으로 | 잡힘(`engine.hb.backup.tmp`, `stop-request.note.tmp` 지워짐) |
| V5 | 상한 비교를 `> MAX_BYTES + 1`로(경계 한 칸 밀기) | 잡힘 |
| V6 | 첫 점검 판정을 `allMatch(ABSENT)` → `anyMatch(ABSENT)` | 잡힘 |
| V7 | 첫 점검 WARNING 로그 블록을 `if (false)`로 | **살아남음**(로그를 단언하는 시험 없음). PR 본문 비차단 항목에 이미 적힘 |
| V8 | 엔진·감시 각각 `implementation(project(":autotrade-monitor-sources"))` 추가 → `verifyProcessIsolation`, 다시 `forbiddenProjects`에서 monitor-sources를 빼고 실행 | 둘 다 실패로 잡힘: `[:autotrade-monitor-sources, com.github.oshi:oshi-core, com.github.oshi:oshi-common, net.java.dev.jna:jna, net.java.dev.jna:jna-platform]`, 프로젝트 금지를 빼도 OSHI·JNA 그룹 4건으로 잡힘. 이 검사는 `check`에 걸려 `build`에서 돈다(`autotrade.process-conventions.gradle.kts`) |
| T1 | 설치본 감시, `watchdog.properties` 5,000바이트 | 종료 코드 **2**, `IOException: 파일이 너무 큽니다(상한 4096바이트 초과)`를 SEVERE·표준 오류로 |
| T2 | 설치본 감시, `heartbeat/watchdog.hb.999.tmp`·`engine.hb.999.tmp`, `collector-state/stop-request.777.tmp`·`stop-request.note.tmp`, 4KB 넘는 `experiment-state/stop-request`(내용상 2026-10-01 요청) | 시작 로그에 `설정 폴더 <절대 경로>`, 첫 점검 WARNING(모두 꺼짐 + 폴더 경로). `watchdog.hb.999.tmp`·`stop-request.777.tmp` 지움, `engine.hb.999.tmp`(남의 이름)·`stop-request.note.tmp`(숫자 아님) 남김. 큰 남은 파일은 이어받아 `requested-at`=감시 기동 시각(2026-10-01을 읽지 않음), 두 파일이 함께 켜짐 |
| T3 | 설치본 실전 엔진, `heartbeat/engine.hb.999.tmp`·`gateway.hb.999.tmp` | 엔진 정상 기동(SEVERE 0), 자기 임시 파일만 지우고 `gateway.hb.999.tmp`는 남김, SIGTERM 뒤 `engine.hb` 지움 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 코드·시험 변경 없음. 엔진에서 바뀐 것은 `HeartbeatWriter` 생성자의 임시 파일 정리뿐이고, Guard를 만든 뒤(`EngineMain.start:86` → `:91`) 실행된다. `SmallFile.deleteLeftoverTemps`는 `RuntimeException`·`IOException`을 모두 삼킨다(`SmallFile.java:39-51, 56-73`). Guard 시험 포함 빌드 1,261건 통과, T3 정상 기동 |
| A2 | 통과 | `ArchitectureTest`·`RealPathReflectionTest`·`ModuleOwnershipTest` 변경 없음. 실전 엔진에 실리는 새 `SmallFile`(`InputStream`, `DirectoryStream`, `Files`)이 `RealPathReflectionTest` JDK 허용 목록 검사를 그대로 통과(빌드). 주문 경로 변경 없음 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 엔진·감시 `processIsolation`에 `:autotrade-monitor-sources`, `com.github.oshi`, `net.java.dev.jna` 직접 금지가 더해져 격리가 강해짐(V8로 실제 동작 확인). 엔진 기동에 더해진 일은 하트비트 폴더 한 번 훑기이고 실패해도 기동을 막지 않음(T3, 코드). 감시는 큰 파일로 힙이 차지 않음(P1·P2·T1). 정지 요청 두 파일은 함께 켜지고 함께 꺼짐(`StopRequestsTest` 새 시험, T2) |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음. 새 로그는 설정 폴더 절대 경로와 파일 경로뿐. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·약화 0건, 고정 값 변경 없음. 새 시험 7건(`StopRequestsTest` 2, `HeartbeatWatchTest` 2, `WatchdogSettingsTest` 1, `HeartbeatTest` 2)이 V2~V6을 잡고, PR #116 QA Q1(M7) 갈래를 고정함. 상한이 "메모리 상한"인지는 고정하지 못함(V1 생존) → Q1 권고 |
| A12 | 통과 | 장애표 #8 감지란을 `heartbeat-stale-seconds`(기본 15초)로 바꾼 것은 `WatchdogSettings.DEFAULT_HEARTBEAT_STALE_SECONDS=15`·설정 키와 맞음. #10(정지 요청 파일·감시 생존 확인) 동작 변경은 큰 남은 파일을 지금 시각으로 이어받는 것뿐이고, 남은 파일이 있으면 켜진 상태로 이어받는다는 D-092 5번 전제는 그대로(T2). 장애 테스트 8종의 전제(실전 엔진·Guard) 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 없음. 시각은 `Instant`(UTC) |
| A14 | 통과 | D-092 7번(4KB 넘는 설정 기동 거부)·검증 목록·범위 밖 표 행 삭제, ADR-011 1~3장(4KB 상한, 임시 파일 정리, `forbiddenProjects`·`forbiddenGroups`), config-keys "감시 프로세스 설정 파일"이 코드와 맞음(`SmallFile.MAX_BYTES=4*1024`, T1·T2·V8). main 병합본에서 config-keys.md 자동 병합 결과도 두 변경을 모두 가짐 |
| B1 | 통과 | 직접 빌드 헤드 1,261건·main 병합본 1,285건 통과, CI 3건 success |
| B6 | 통과 | 새 동작마다 시험 있음(상한 경계 4096/4097, 큰 하트비트·정지 요청·설정, 남의 임시 파일 보존, 부분 삭제 실패 주입). 건너뛴 시험 없음. 빈 곳은 Q1(메모리 상한)·V7(경고 로그) |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 서비스·라이브러리 없음. 빌드 설정 변경은 금지 목록 추가뿐 |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **비정상으로 큰 파일 하나로 감시를 못 뜨게 하거나 점검을 멈춤**: 하트비트 3GB(P1)·남은 정지 요청 3GB(P2)·설정 5,000바이트(T1) 모두 힙을 쓰지 않고 정해진 결과(읽을 수 없음 / 지금 시각으로 이어받음 / 종료 코드 2)로 끝남. 다만 이 성질을 지키는 시험이 없음(Q1).
2. **임시 파일 정리가 남의 파일을 지우거나 실전 엔진 기동을 막음**: 엔진은 `engine.hb.<숫자>.tmp`만 지우고 다른 프로세스·숫자 아닌 이름은 남김(T3, V4). 정리 실패는 삼켜져 기동을 막지 않음(코드). 감시를 두 개 띄우는 잘못된 설정에서 서로의 쓰는 중 임시 파일을 지울 수 있고 그 주기 쓰기 하나만 실패함 `[추론, AtomicFile.write 코드 읽기]`(PR 본문 비차단에 적힘). 정지 요청을 처음 켜는 주기였다면 한 주기(기본 5초) 늦게 켜진다 `[추론]`.
3. **설정 폴더를 잘못 적어 감시가 빈 폴더를 지킴**: 이 경우 정지 요청 파일도 명령들이 보지 않는 폴더에 써져 자원 부족에서 수집·실험이 멈추지 않는 보호 공백이 된다 `[추론, ConfigPaths·StopRequests 경로 읽기]`. 이 PR은 시작 로그의 폴더 경로와 첫 점검 WARNING 한 줄을 더했지만, 감시를 먼저 띄우는 순서에서는 그 경고가 "아무것도 띄우지 않았다면 정상"인 상태와 구분되지 않고 이후 다시 알리지 않는다(P4). R1.
4. **OSHI·JNA가 간접 의존으로 실전 엔진·감시에 들어옴**: V8에서 프로젝트 금지와 그룹 금지가 각각 독립으로 잡음.

## 점검표 밖 발견(권고)
- **Q1 (시험) 읽기 상한이 메모리 상한인지 고정하는 시험이 없다(V1 생존)**: `SmallFile.read`를 `readAllBytes()` 뒤 크기 검사로 바꿔도 기존 시험이 모두 통과한다. 그 경우 큰 하트비트·정지 요청·설정 파일이 다시 감시 힙을 채운다(PR #116 QA R1의 원래 위험). 희소 파일(예: `RandomAccessFile.setLength(3GB)`)로 `SmallFile.read`가 `IOException`을 던지는지 보는 시험을 권한다. QA 탐침 P1은 디스크를 거의 쓰지 않고 0.005초에 V1을 잡았다.
- **R1 (낮음) 설정 폴더 오기 경고가 첫 점검 한 번뿐이다(P4, V7 생존)**: 감시를 먼저 띄우고 다른 프로세스를 나중에 띄우는 순서면 첫 경고는 정상 상태와 구분되지 않고, 그 뒤 프로세스들이 다른 폴더에서 떠도 감시는 계속 ABSENT(INFO)만 본다. 시나리오 3의 보호 공백이 로그 한 줄로만 드러난다. 모든 프로세스가 일정 시간(예: 몇 분) 계속 꺼짐이면 다시 WARNING을 남기거나, 7단계 절차서에 "감시 시작 로그의 설정 폴더 = 엔진·게이트웨이·실험실 설정 폴더" 확인 단계를 두길 권한다. 경고 로그 자체를 단언하는 시험도 없다(PR 본문에 적힘).
- **R2 (참고) 로그 한글이 비 UTF-8 콘솔에서 `??`로 보인다**: T1의 SEVERE·표준 오류 메시지가 이 컨테이너 기본 로캘에서 모두 `?`였다(`-Dfile.encoding=UTF-8` 등을 주면 정상). PR #116 QA R2와 같은 기존 문제로 이 PR 때문이 아니다. 기동 거부 사유를 사람이 읽어야 하므로 6단계 첫 주 Windows 점검 항목에 남겨 둔다.

## 판정
승인
