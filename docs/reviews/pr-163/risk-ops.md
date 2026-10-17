# risk-ops 검토: D-109 감시·모니터 로그 출력 실패가 판정·알림을 끊지 않게(PR #155·#157 후속)
- 대상: PR #163 / 헤드 커밋 91fa207(브랜치 `claude/project-thread-34spv7`) / main 대비 diff(`git diff origin/main...HEAD`, 머지 기준 4d75626 = 현재 origin/main, 파일 12개 +469 −66)
- 점검표: A
- 직접 실행한 것(별도 clone, 메인 작업 폴더와 분리):
  - `./gradlew -I <스크래치>/mirror.init.gradle build`(헤드 91fa207): **BUILD SUCCESSFUL in 3m 27s**, 종료 코드 0. init 스크립트는 Maven Central 429 회피용 공개 미러를 쓰는 저장소 밖 파일이며 빌드 내용은 바꾸지 않는다. `*/build/test-results/test/*.xml` 합계 1,563건, 실패·오류·건너뜀 0. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 62, `:autotrade-app-watchdog` 74, `:autotrade-monitor-agent-jar` 34, 모두 실패 0.
  - `git merge-tree --write-tree origin/main HEAD` 충돌 없음(머지 기준이 현재 main과 같음).
  - 변이 9개(한 번에 하나, 각 뒤 `git checkout -- .`로 원복, 마지막 `git status` 깨끗함). 범위 `:autotrade-app-watchdog:test :autotrade-monitor-agent-jar:test`:
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | M1 | `WatchdogLog.log`가 삼키지 않고 다시 던짐 | 잡힘(watchdog 1건) |
    | M2 | `writeSourceLogs`에서 실패한 줄을 `unsent`에 넣지 않음 | 잡힘(monitor 2건) |
    | M3 | 줄 번호 비교 제거(`if (true)`) | 잡힘(monitor 1건) |
    | M4 | 새 WARN·INFO 때 들고 있던 줄을 버리지 않음 | 잡힘(monitor 1건) |
    | M5 | 회복 본문을 `missingSinceRecovery` 대신 `warnedReadings`로 | 잡힘(watchdog 1건) |
    | M6 | 리스너 실패 로그를 `logQuietly` 대신 그냥 `LOG.log` | 잡힘(monitor 1건) |
    | M7 | 예약 작업 catch 로그를 그냥 `LOG.log` | 잡힘(monitor 1건) |
    | M8 | 제어 문자 대신 CR·LF만 치환 | 잡힘(watchdog 1건) |
    | M9 | `missingSinceRecovery.addAll(missing)` 삭제 | 잡힘(watchdog 1건) |
  - 탐침 1개(임시 시험, 실행 뒤 삭제): 감시 자기 하트비트 파일 경로를 비어 있지 않은 폴더로 만들어 `HeartbeatWriter.beat()`의 쓰기를 실패시키고, `com.autotrade.monitor.HeartbeatWriter` 로거에 던지는 핸들러를 붙인 뒤, 엔진 멈춤 + 드라이브 위험 + CPU 결측을 같은 점검에 넣었다. 결과: 첫 점검 `tick()`이 `IllegalStateException`으로 **통째로 끝나 알림 0건, 정지 요청 파일 없음**. 다음 점검(5초 뒤)에서 `reading-missing, process-stale, stop-request-on` 알림과 정지 요청 파일이 생김(아래 K1).

## 검토 범위와 영향
- 운영 코드 변경: 감시 프로세스 `Watchdog.java`·`HeartbeatWatch.java`·`StopRequests.java`·`DiskFreeSource.java`·새 `WatchdogLog.java`, 감시 에이전트 코어 `MonitorAgent.java`. 둘 다 보호 경로(`scripts/ci/protected-paths.txt:9`, `:17`)다. 나머지는 시험과 문서(D-109, 결정 색인, 기획서 v14.48).
- 실전 영향 경로 확인: 정지 요청 판정 `Watchdog.pressure`(Watchdog.java:183-206)와 `StopRequests.update`의 판정 분기는 바뀌지 않았고 로그 호출만 `WatchdogLog.log`로 감쌌다(StopRequests.java:80, :93, :142, :148, :161, :172). `reportMissing`(Watchdog.java:215-258)은 상태 갱신·알림·로그 순서만 바뀌었고 `pressure` 계산은 그대로 그 뒤(:114)다. 하트비트 판정(HeartbeatWatch)도 로그만 감쌌다.
- `MonitorAgent`는 감시와 실험실 모니터가 함께 쓴다. 실전 엔진(`autotrade-app-engine`)은 `MonitorAgent`를 쓰지 않고 `HeartbeatWriter`만 쓰며(EngineMain.java:143), `HeartbeatWriter`는 이 PR에서 바뀌지 않았다. 엔진 쪽은 `beatQuietly`(HeartbeatWriter.java:98-104)가 예약 작업을 지킨다.
- 실전 엔진·Guard·주문 경로·`autotrade-architecture-test`·`docs/team`·`scripts`·`.github` 변경 없음(`git diff --stat` 빈 결과).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·Guard 설정 로딩 변경 없음(diff에 `autotrade-risk`·`autotrade-app-engine` 없음). 회귀: `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. 감시·모니터 코어는 주문 API를 부르지 않는다. ArchUnit 규칙 변경 없음, `:autotrade-architecture-test:test` 46건 통과(`ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 포함). 새 클래스 `WatchdogLog`는 기존 소유 패키지 `com.autotrade.app.watchdog` 안이라 소유 표 갱신 불필요(빌드 통과로 확인) |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 실패 | 정지 순서 5.2 0번(broker-and-fault-design.md:143 이하, 장애표 #10)을 만드는 감시 판정 자체는 그대로이고, 이 PR로 감시 단계 안의 로그 출력 실패는 판정·알림·정지 요청을 끊지 않는다(`WatchdogTest#감시_로그가_던져도_같은_점검의_알림과_정지_요청은_그대로_한다` WatchdogTest.java:583 통과, 변이 M1로 실제로 잡는 것 확인). 그러나 같은 점검 루프의 첫 줄 `heartbeat.beat()`(Watchdog.java:108)는 어떤 `try`에도 들어 있지 않고, `HeartbeatWriter.beat()`의 상태 바뀜 로그(HeartbeatWriter.java:84, :90)가 던지면 `tick()` 전체가 끝난다. 탐침으로 확인: 드라이브 위험·엔진 멈춤이 겹친 점검에서 정지 요청 파일과 알림 3건이 그 점검에 나가지 않고 한 주기(기본 5초, WatchdogSettings.java:58) 늦게 나감. 하트비트 쓰기 실패는 디스크 부족과 함께 오기 쉬운 장애라 "자원 부족 시 정지 순서"가 이 PR이 막으려던 바로 그 이유(로그 출력 실패)로 늦는다. 영구 유실은 아니다(상태 `failing`을 로그 전에 바꿔 다음 점검은 로그를 내지 않음). 아래 K1 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새·바뀐 로그 문구는 프로세스 이름·하트비트 시각·경로·예외 클래스 이름·자원 이름뿐이다(HeartbeatWatch.java:94, :148-153, StopRequests.java:80-172, Watchdog.java:229, :255). 회복 알림 본문은 자원 이름 집합(Watchdog.java:225-228). 드라이브 원인은 로그에만 붙고 제어 문자를 공백으로 바꾼다(DiskFreeSource.java:79-86). 시험 픽스처는 가짜 경로·"주입" 문구뿐. gitleaks는 이 컨테이너에 없어 CI에 맡기고, 추가 줄을 `appkey|appsecret|secret|token|password|계좌번호 모양` 정규식으로 찾아 0건 |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard 테스트, 장애 테스트) 변경 없음. 보호 경로 안 기존 시험 중 기대값이 바뀐 것은 `MonitorAgentTest#WARN과_INFO를_내지_못하면_다음_주기에_다시_내고_그_주기_판정은_이어_간다`의 회복 INFO 횟수 "2회"→"7회" 하나다. 이는 약화가 아니라 실제 연속 실패 횟수를 세라는 결정(D-109 결정 1, PR #155 architect N5·risk-ops K3)의 반영이며, 시험 흐름(5주기 핸들러 예외 + 2주기)과 손 계산이 맞다. 새 동작마다 실패 경우 시험이 있다: MonitorAgentTest 새 5건, WatchdogTest 새 3건, DiskFreeSourceTest 1건. 변이 9개 모두 잡힘. 고정 값(지문·허용 목록·소유 표) 변경 없음 |
| A12 | 통과 | 장애 대응표 6장 17행 그대로(행 수 확인 17), `docs/team` 변경 없음. 바뀐 것은 로그 출력 실패 시 동작뿐이고 장애 테스트 8종의 전제(감시는 정지 요청 파일만 만들고 실전 엔진을 멈추지 않음)를 바꾸지 않는다. 해외 O1~O8 무관. K1은 대응표의 전제를 깨지 않고 그 이행을 한 주기 늦추는 결함이라 A8에 적었다 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 실패 | D-109 결정 3은 "모든 점검 중 로그는 출력 실패를 삼킴"이라 쓰고, 기획서 MON-02 행(v14.48)은 "점검 중 로그 출력 실패(로그 핸들러 예외)는 알림·판정·정지 요청을 끊지 않음"이라 쓴다. 감시 점검 중 `heartbeat.beat()`의 로그(K1)는 이 문장과 다르게 동작한다(탐침 결과). D-109 위험과 한계에도 이 경우가 없다. 나머지 문서(결정 색인 1줄, MON-05 행, 검증 절의 시험 이름)는 코드와 일치함을 확인했다 |

## 점검표 밖 발견
- **K1(수정 필요) — 감시 점검 첫 줄 자기 하트비트의 로그가 던지면 그 점검 전체가 빠짐**: `Watchdog.tick()`(Watchdog.java:106-108)은 `heartbeat.beat()`를 `try` 밖에서 부른다. `HeartbeatWriter.beat()`는 javadoc에 "예외를 던지지 않는다"(HeartbeatWriter.java:71)고 하지만, 쓰기 실패·복구 때의 `LOG.log`(:84, :90)가 던지면 그대로 나간다. 탐침 결과 그 점검의 자원 판정·결측 알림·하트비트 멈춤 알림·정지 요청 파일이 모두 한 주기 늦었다. 디스크 부족에서 하트비트 쓰기가 먼저 실패하기 쉬워, 정지 요청이 가장 필요한 때와 겹친다. 고치는 방법(둘 중 하나, 둘 다여도 됨):
  1. `tick()`에서 `heartbeat.beat()`를 다른 단계처럼 `try { … } catch (Throwable e) { log(…) }`로 감싼다(감시 모듈만 바뀜).
  2. `HeartbeatWriter`의 로그도 출력 실패를 삼키게 한다(모니터 코어, 엔진·게이트웨이도 함께 득을 본다. 엔진은 지금도 `beatQuietly`가 지키므로 필수는 아님).
  그리고 위 탐침과 같은 시험 1건(자기 하트비트 쓰기 실패 + `HeartbeatWriter` 로거 예외 + 같은 점검의 정지 요청·알림 확인)과, D-109 결정 3·MON-02 문장이 실제 범위와 맞게 되도록 문서를 함께 고친다.
- **K2(참고)** — D-109 위험 4번이 적은 대로, 결측 알림을 로그보다 먼저 넣는 순서 변경은 `WatchdogLog`가 삼키므로 시험이 잡지 않는 이중 방어다. 판정에 넣지 않는다.
- **K3(참고)** — `MonitorAgent`의 못 낸 줄 재출력은 상한이 없다(D-109 결정 2). 핸들러가 출력 뒤 던지는 경우 같은 줄이 매 주기 반복될 수 있으나 수집원마다 줄 하나·번호 하나만 들고 있어 메모리는 수집원 수로 묶인다(MonitorAgent.java:57-60). 반복은 로그 소음이며 판정·알림에는 영향이 없다. 6단계 운용에서 다시 본다는 되돌릴 조건이 적혀 있어 받아들인다.
- `writeSourceLogs`의 줄 번호 비교(`Long latest` 대 `long seq`, MonitorAgent.java:280-281)는 언박싱 비교라 참조 비교 문제 없음. `unsent`·`latestLine`·`lineSeq`는 모두 `lock` 안에서만 바뀜을 확인했다(:103-120, :278-284).
- 이전 검토 PR #155 risk-ops K1(감시 `tick()` catch 안 로그, `reportMissing` 알림 순서)과 K3(횟수 적게 셈), PR #157 risk-ops K2(지역 이름)·K3(회복 본문 값)은 위 K1 범위를 빼고 해결됨을 코드·시험·변이(M1, M5, M9)로 확인했다.

## 판정
수정 필요(A8·A14 / K1: 감시 `tick()`의 `heartbeat.beat()` 로그 출력 실패가 그 점검의 판정·알림·정지 요청을 건너뜀. 감싸기 또는 `HeartbeatWriter` 로그 삼킴 + 실패 경우 시험 + D-109·MON-02 문장 정정)
