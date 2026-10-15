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

## 재검토 2026-10-15
- 대상: PR #119 / 커밋 c11f655e62c304ff777cf0e75779550cb4456d6c / `git diff origin/main...HEAD` (merge base 6fe3101, 파일 5개: `MonitorAgent.java`, `MonitorAgentTest.java`(보호 경로), `docs/qa/pending.md`, `docs/reviews/pr-119/architect.md`, `docs/reviews/pr-119/risk-ops.md`)
- 점검표: A
- 앞 검토 뒤 바뀐 것(c11f655): ① `MonitorService.java`·`MonitorServiceTest.java`를 main과 같게 되돌림(K1 반영, `git diff origin/main...HEAD -- autotrade-app-lab` 0줄로 확인) ② `MonitorAgent.trackFailure`가 같은 실패 반복 때 DEBUG가 꺼져 있으면 로그 문자열을 만들지 않고 null을 돌려줌(`MonitorAgent.java:208-212`). `before.count++`(`:208`)는 그 검사보다 앞이라 DEBUG가 꺼져도 연속 횟수는 센다 ③ `docs/qa/pending.md`에 PR #119 독립 QA 요청 줄 추가.
- 영향 범위로 함께 본 실전 경로 코드: `MonitorAgent`를 쓰는 운영 코드는 실험실 `MonitorService`와 감시 `Watchdog.java:54`뿐(`grep MonitorAgent autotrade-app-*` 비시험 코드), 실전 엔진·게이트웨이는 쓰지 않음(앞 검토와 같음). origin/main이 merge base 뒤 PR #117로 움직였으나 monitor·watchdog 파일은 건드리지 않았고 `git merge-tree origin/main HEAD` 충돌 없음.
- 직접 실행한 것:
  - 작업 폴더가 아닌 스크래치 clone에서 c11f655로 `./gradlew build --no-build-cache` → **BUILD SUCCESSFUL, 136 tasks 모두 실행, 테스트 1255개 실패 0 / 오류 0 / 건너뜀 0**(앞 검토 1256개에서 되돌린 실험실 시험 1개가 빠진 수). 모듈별: autotrade-risk 55, autotrade-architecture-test 43, autotrade-app-engine 52, autotrade-app-watchdog 23, autotrade-app-gateway 50, autotrade-monitor-agent-jar 23, autotrade-monitor-sources 6, autotrade-app-lab 181 모두 통과.
  - 변이 1: `MonitorAgent.java`를 merge base 버전으로 되돌리면 `MonitorAgentTest > 같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다() FAILED`(23개 중 1개 실패) → 새 시험이 바뀐 동작을 지킨다.
  - 변이 2: `before.count++`를 DEBUG 검사 뒤로 옮겨(DEBUG가 꺼지면 횟수를 세지 않게) 돌리면 monitor-agent-jar 시험 23개 모두 **통과** → DEBUG 꺼짐 경로는 시험이 가르지 못한다(아래 K5). 확인 뒤 되돌렸고 clone `git status` 깨끗함.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·Guard 설정·시험 변경 없음(파일 5개 모두 monitor·문서). 격리 빌드에서 autotrade-risk 55개, autotrade-app-engine 52개 통과 |
| A2 | 해당 없음 | 주문 출구 코드 변경 없음. `autotrade-architecture-test` 변경 없음, 격리 빌드에서 43개(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest 포함) 통과 |
| A3 | 해당 없음 | 증권사·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 경로 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진·게이트웨이는 `MonitorAgent`를 쓰지 않는다(`EngineMain.java:17`, `GatewayMain.java:19`는 `HeartbeatWriter`만). 감시(`Watchdog.java:54`)에 닿는 변경은 로그 등급과 수집원별 실패 맵(`MonitorAgent.java:44,188-213`)뿐이고, 이번 추가분 `LOG.isLoggable(DEBUG)`(`:209`)는 `lock` 안에서 불리지만 JDK 로거 조회일 뿐 에이전트로 되돌아오는 호출이 없어 교착 경로가 없다. 수집 주기·스레드 우선순위·임계 판정·정지 요청 판단(`Watchdog.java:61-83`)에 영향 없음. 실험실 `MonitorService`는 main과 같아져 앞 검토의 종료 순서 변경 논점이 사라졌다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 로그 문구는 수집원 id, 예외 클래스 단순 이름, 횟수만(`MonitorAgent.java:195-212`). `getMessage()`를 남기지 않음. 시험 픽스처 문자열은 "OSHI 없음"·"다른 실패"뿐. `pending.md` 추가 줄에 비밀값 없음 |
| A11 | 통과 | 보호 경로 변경은 `MonitorAgent` 동작과 그 시험 1개 추가뿐, 기존 보호 시험 삭제·비활성화·약화 없음(diff에 `-@Test`·`@Disabled` 없음). 고정 값(지문·허용 목록·소유 표) 변경 없음, `HashMap`·`System.Logger.isLoggable`이 실전 경로 JDK 허용 목록(D-086) 안임을 `RealPathReflectionTest` 통과로 확인. 새 동작에 실패하는 경우를 포함한 시험 `MonitorAgentTest#같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다`가 있고 변이 1로 변별력 확인. 되돌린 실험실 시험 `종료와_겹쳐_도착한_표본은_예외_없이_버린다`는 이 PR이 추가했던 것이며 main에 없던 것이라 보호 시험 삭제가 아니다. DEBUG 꺼짐 경로 시험 공백은 로그 횟수 문구에만 영향이 있어 K5 권고로 둔다 |
| A12 | 통과 | 장애표 #10(PC 자원 고갈, `broker-and-fault-design.md:169`) 감지 수단인 임계 판정(`MonitorAgent.java:92-104`), 스냅샷 `failedSources`(`:94`), `lastSampleAt`은 바뀌지 않았다. 감시 기본 로그 수준(JUL INFO)에서 지속 실패는 처음 WARN 한 줄과 회복 INFO만 남고 반복은 문자열도 만들지 않으며, 이는 앞 검토에서 받아들인 정책과 같다. 장애 테스트 8종·해외 O1~O8의 전제와 무관 |
| A13 | 해당 없음 | 시장·시간대·통화 계산 변경 없음 |
| A14 | 통과 | 확정 결정과 충돌 없음(D-090의 "수집원 실패가 계속 남는 것"은 스냅샷 `failedSources`와 화면으로 성립). `pending.md`에 PR #119 독립 QA 요청이 기록되어 프로젝트 지침 11장 관문 절차와 맞다. 로그 정책을 D-090에 적는 일(앞 K2)은 아직 반영되지 않았고 권고로 남는다 |

## 점검표 밖 발견 (재검토)
- K1 해소: 실험실 쪽 변경을 뺐으므로 도달하지 않는 catch·사실과 다른 주석·변별력 없는 시험 문제가 없어졌다.
- K2 유지(권고): D-090 또는 MON-05 설명에 수집원 실패 로그 정책(처음·종류 바뀜 WARN, 반복 DEBUG, 회복 INFO, 주기별 실패는 `failedSources`)을 한 줄 적기를 권한다. 7단계 감시 확장 PR에서 "연속 N회 실패를 #10 이상으로 판정"과 "지속 실패 중 주기적 WARN 재기록"을 risk-ops 검토 항목으로 넘기는 것도 그대로다. K3·K4도 앞 검토 그대로.
- K5 (권고, 판정에 넣지 않음): 새 시험은 JUL 수준을 ALL로 올려서만 돌므로 운영 기본값(DEBUG 꺼짐) 경로를 시험하지 않는다. 변이 2처럼 `count++`가 DEBUG 검사 뒤로 밀려도 통과해, 그 경우 회복 INFO가 "1회 연속 실패 뒤"로 잘못 찍힌다. 영향은 로그 문구의 횟수뿐이고 스냅샷·임계·정지 요청에는 없다. 같은 시나리오를 JUL 수준 INFO로 한 번 더 돌려 DEBUG 기록 0건과 회복 문구 "IllegalStateException 101회 연속 실패 뒤"(또는 종류 바뀜 뒤 횟수)를 확인하는 시험을 추가하기를 권한다.
- 참고: 새 시험은 전역 JUL 로거 수준을 바꿨다가 finally에서 되돌린다. 저장소에 JUnit 병렬 실행 설정이 없어(`junit-platform.properties`의 parallel 설정 없음) 다른 시험과 겹치지 않는다.

- 머지 절차: 보호 경로(`autotrade-monitor-agent-jar/*`) 변경이므로 실전 관련 PR이다. 이 판정은 risk-ops 역할 검토 결과일 뿐이며 사용자 직접 확인 머지와 머지 전 독립 QA(`docs/qa/pending.md`의 "PR #119 검토해줘")가 필요하다.

## 판정
승인
