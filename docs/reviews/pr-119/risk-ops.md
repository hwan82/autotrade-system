# risk-ops 검토: 모니터 수집원 실패 WARN을 상태 변화 때만, 종료 시 송신 거절 경고 제거(MON-05 후속)
- 대상: PR #119 / 커밋 17dd9ac94df32cd0c6967d2dac47860b431869ef / `git diff origin/main...HEAD` (파일 4개, +157 −6)
  - `autotrade-monitor-agent-jar/src/main/java/com/autotrade/monitor/MonitorAgent.java` (보호 경로, `scripts/ci/protected-paths.txt:17`)
  - `autotrade-monitor-agent-jar/src/test/java/com/autotrade/monitor/MonitorAgentTest.java` (보호 경로)
  - `autotrade-app-lab/src/main/java/com/autotrade/app/lab/monitor/MonitorService.java`
  - `autotrade-app-lab/src/test/java/com/autotrade/app/lab/monitor/MonitorServiceTest.java`
- 점검표: A
- 영향 범위로 함께 본 실전 경로 코드: `MonitorAgent`를 쓰는 곳 전체(`autotrade-app-watchdog/.../Watchdog.java:39,54,67`, `DiskFreeSource.java` `read()`, `StopRequests.java:94`), monitor-agent-jar를 의존하는 실전 엔진·게이트웨이(`EngineMain.java:17`, `GatewayMain.java:19`, 둘 다 `HeartbeatWriter`만 씀), 실험실 `MonitorService` 전체.
- 직접 실행한 것:
  - 작업 폴더에서 `./gradlew build` → BUILD SUCCESSFUL. 그러나 같은 폴더에서 다른 검토자의 Gradle 빌드가 동시에 돌고 있어(PID 4551) 이어서 `cleanTest` 후 재실행한 `:autotrade-architecture-test:test`가 `build/` 파일 경합으로 실패(`클래스 파일을 읽지 못했다`, `Could not write XML test results`). 코드 결함이 아니라 동시 빌드 간섭이므로 아래 격리 실행으로 판정했다.
  - 커밋 17dd9ac를 스크래치 폴더에 따로 clone해 `./gradlew build --no-build-cache` → **BUILD SUCCESSFUL, 136 tasks 모두 실행, 테스트 1256개 실패 0 / 오류 0 / 건너뜀 0**. 모듈별: autotrade-risk 55, autotrade-architecture-test 43, autotrade-app-engine 52, autotrade-app-watchdog 23, autotrade-app-gateway 50, autotrade-monitor-agent-jar 23, autotrade-monitor-sources 6, autotrade-app-lab 182 모두 통과.
  - 변이 확인(같은 clone, 확인 뒤 되돌림):
    1. `MonitorAgent.java`를 main 버전으로 되돌리면 `MonitorAgentTest#같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다` **실패** → 새 시험이 바뀐 동작을 실제로 지킨다.
    2. `MonitorService.push`·`close`를 main 동작(closing 검사 없음, try/catch 없음, 옛 종료 순서)으로 되돌려도 `MonitorServiceTest` 8개 모두 **통과**, 새 시험 `종료와_겹쳐_도착한_표본은_예외_없이_버린다`도 통과 → 이 시험은 바뀐 동작을 가르지 못한다(아래 K1).
  - JDK 동작 확인: `ThreadPoolExecutor.DiscardOldestPolicy`는 실행기가 shutdown이면 `RejectedExecutionException` 없이 조용히 버린다(독립 실행 코드로 `shutdownNow()` 뒤 `execute()` → 예외 없음 확인).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·Guard 설정·테스트 변경 없음(`git diff --name-only origin/main...HEAD` 4개 파일 모두 monitor). 격리 빌드에서 autotrade-risk 55개, autotrade-app-engine 52개 시험 통과 |
| A2 | 해당 없음 | 주문 출구 코드 변경 없음. ArchUnit 규칙 파일(`autotrade-architecture-test`) 변경 없음, 격리 빌드에서 43개(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest 포함) 통과 |
| A3 | 해당 없음 | 증권사·주문 라우팅 코드 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진·게이트웨이는 monitor-agent-jar에서 `HeartbeatWriter`만 쓰고 `MonitorAgent`를 쓰지 않는다(`EngineMain.java:17`, `GatewayMain.java:19`, `grep MonitorAgent` 결과에 engine·gateway 없음). 감시는 `MonitorAgent`를 쓰지만 이번 변경은 로그 등급과 수집원별 상태 맵(수집원 수만큼, `MonitorAgent.java:44,188-210`)뿐이라 수집 주기·스레드·정지 요청 판단(`Watchdog.java:61-83`, `pressure()`)에 영향 없음. 실험실 `MonitorService.close` 순서 변경(`MonitorService.java:168-175`)은 실험실 프로세스 안이고 실전 엔진과 무관. 새 잠금 구간(`MonitorAgent.java:78-83`)은 수집 스레드 하나와 조회 메서드 사이의 짧은 맵 갱신이다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 새 로그 문구는 수집원 id와 예외 클래스 단순 이름, 횟수만 담는다(`MonitorAgent.java:76,195-209`). 예외 메시지(`getMessage()`)를 남기지 않으므로 수집원 예외에 경로·값이 담겨도 로그로 나가지 않는다. 시험 픽스처의 문자열은 "OSHI 없음"·"다른 실패"뿐(`MonitorAgentTest.java` 새 시험). 비밀값 없음 |
| A11 | 통과 | 보호 경로 변경은 `MonitorAgent`와 그 시험 추가뿐이며 기존 보호 시험 삭제·비활성화·약화 없음(diff에 `-@Test`·`@Disabled` 없음). 고정 값(지문·허용 목록·소유 표) 변경 없음, `HashMap`·`System.Logger.Level.DEBUG` 사용이 실전 경로 JDK 허용 목록(D-086) 안임을 `RealPathReflectionTest` 통과로 확인. 새 동작에 실패하는 경우 포함 시험 있음: `MonitorAgentTest#같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다`(처음 실패 WARN, 100회 반복 중 99회 DEBUG, 종류 바뀜 WARN, 회복 INFO)이고, main 코드로 되돌리면 실패함을 직접 확인(변이 1). 실험실 쪽 시험의 변별력 문제는 보호 경로 밖이라 K1로 둔다 |
| A12 | 통과 | 장애표 #10(PC 자원 고갈, `broker-and-fault-design.md:169`)의 감지 수단은 monitor-agent 임계치와 감시 하트비트·정지 요청 파일이고, 이번 변경은 임계 판정(`MonitorAgent.java:92-104`)·스냅샷 `failedSources`(`:94`)·`lastSampleAt`을 바꾸지 않는다. 감시의 디스크 수집원은 경로별 실패를 스스로 삼켜 값을 빼므로(`DiskFreeSource.read()`) 에이전트 수준 "수집원 실패" 로그는 감시에서 `Error`일 때만 나며, 그때도 처음 WARN과 회복 INFO는 남는다. 상태 변화 때만 로그를 남기는 방식은 D-092 하트비트 판정과 같다(`D-092-watchdog-heartbeat-and-stop-requests.md:16`). 장애 테스트 8종·해외 O1~O8의 전제(주문·시세·재기동·서버 측 손절)와 무관 |
| A13 | 해당 없음 | 시장·시간대·통화 계산 변경 없음 |
| A14 | 통과 | 바뀐 것은 로그 등급 정책이며 확정 결정과 충돌하지 않는다. D-090은 "수집원 실패가 계속 남는 것으로 드러난다"(`D-090-monitor-agent-and-screen.md:37`)고 적는데, 이는 스냅샷 `failedSources`와 화면 "읽지 못한 수집원"(같은 문서 :35)으로 그대로 성립한다(`MonitorAgent.java:94`, `MonitorService.java:183`). 로그 문구 정책이 D-090에 적혀 있지 않은 점은 K2로 권고 |

## 점검표 밖 발견
- K1 (권고, 실험실, 판정에 넣지 않음): `MonitorService.push`의 `RejectedExecutionException` catch(`MonitorService.java:139-143`)는 도달하지 않는 코드다. `sender`의 거절 정책이 `DiscardOldestPolicy`(`:61`)이고, 이 정책은 실행기가 shutdown이면 예외 없이 버린다(직접 실행으로 확인). 따라서 architect PR #114 A7이 말한 "닫는 순간 `RejectedExecutionException`이 리스너 실패 경고로 남는" 현상은 실제로 일어나지 않으며, 주석(`:142`)과 시험 주석(`MonitorServiceTest.java` 새 시험 첫 줄)이 사실과 다르다. 새 시험 `종료와_겹쳐_도착한_표본은_예외_없이_버린다`는 main 동작으로 되돌려도 통과한다(변이 2). 동작 자체는 무해하다(종료 뒤 표본을 버리는 것은 전과 같음). 주석을 "DiscardOldestPolicy는 종료 뒤 조용히 버린다. 거절 정책을 바꿀 때를 대비한 방어"로 고치거나 catch를 빼고, 시험은 `closing` 검사를 가르도록(예: `closeStreams()` 뒤 열린 스트림이 남은 상태에서 push가 송신 작업을 넣지 않음을 `sender.getCompletedTaskCount()`/큐 크기로 확인) 바꾸기를 권한다. architect 영역(B6)이다. 또 시험을 위해 `sender` 필드와 `push`를 패키지 공개로 연 것(`:59`, `:134`)은 같은 패키지 안이라 실전 영향은 없다.
- K2 (권고): D-090(또는 MON-05 설명)에 "수집원 실패 로그는 처음 실패·종류 바뀜 WARN, 반복 DEBUG, 회복 INFO. 주기별 실패는 스냅샷 `failedSources`에 남음"을 한 줄 적어 두면 운영자가 로그에서 반복 경고가 없는 것을 "회복"으로 오해하지 않는다. 로그가 회전되면 처음 WARN 한 줄이 사라져 지속 실패의 흔적이 로그에 남지 않을 수 있다. 감시가 이 코어로 자원 판단을 넓히는 7단계 PR에서는 PR #114 K4대로 "같은 수집원 연속 N회 실패"를 감시 자신이 #10 이상으로 판정하고, 필요하면 실패가 이어지는 동안 일정 간격(예: 1시간)마다 WARN 한 줄을 다시 남기는 것을 그 PR의 risk-ops 검토 항목으로 넘긴다.
- K3 (기존 문제, 이 PR 범위 밖): 감시의 `DiskFreeSource.read()`는 경로별 `IOException | RuntimeException`을 조용히 삼키고 값만 빼므로, 드라이브 여유를 계속 못 읽어 판단 못 함(UNKNOWN, `StopRequests.java:94`)이 이어져도 감시 로그에 아무것도 남지 않는다. 이번 변경과 무관하게 전부터 그렇다. 7단계 감시 구현 때 장애표 #10 감지 열과 함께 "판단 못 함이 상태로 바뀔 때 WARN"을 넣기를 권한다.
- K4 (참고): `failing` 맵 갱신은 수집원마다 따로 잠그므로(`MonitorAgent.java:78-83`) 시험이 `sampleOnce()`를 수집 스레드와 동시에 부르면 연속 횟수가 섞일 수 있다. 로그 문구의 횟수만 영향받고 스냅샷·임계 판정에는 영향이 없어 문제 삼지 않는다.
- 검토 환경 메모: 같은 작업 폴더에서 여러 검토자가 Gradle을 동시에 돌리면 `build/` 경합으로 보호 시험이 거짓 실패한다. 검토 결과는 커밋을 따로 clone한 폴더에서 낸 것이다.

- 머지 절차: 이 PR은 보호 경로(`autotrade-monitor-agent-jar/*`)를 바꾸므로 프로젝트 지침 10·11장에 따라 실전 관련 PR이다. 이 승인은 risk-ops 역할 검토 결과일 뿐이며, 사용자 직접 확인 머지와 머지 전 독립 QA 요청이 필요하다.

## 판정
승인
