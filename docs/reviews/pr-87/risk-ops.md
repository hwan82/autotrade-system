# risk-ops 검토: 실시간 시세 전달(게이트웨이 → 실험실)과 실시간 모의 세션(D-076, ADR-010)
- 대상: PR #87 / 브랜치 `claude/project-thread-go4oua` / 커밋 `3ecb2e873ca6ad29f6a70dc4075ef39eefcaff4e` / `git diff origin/main...HEAD` 전체(39파일, +3832 −237)
  - 검토 중 브랜치에 `3ecb2e8`(GatewayFeedTest 시험 코드만, try 자원 경고 고침)이 더해져 이 커밋을 기준으로 판정했다. 주장 내용은 바뀌지 않았다(어서션 동일).
- 점검표: A
- 직접 실행한 것:
  - 저장소 폴더 `./gradlew build`: Maven Central 429로 `:build-logic` 의존성 받기 실패(`--offline`도 캐시 부족으로 실패). 검토용 init 스크립트(저장소 밖, Maven Central의 Google 미러 `maven-central.storage-download.googleapis.com`를 앞에 둠, 저장소 파일 변경 없음)로 다시 돌렸다.
  - 저장소 폴더에서 돌린 두 번은 다른 세션의 빌드가 같은 `build/` 출력을 동시에 쓰면서 `Could not write XML test results`, `NoClassDefFoundError`, 번들 캘린더 `EOFException` 등 환경 오류로 실패했다. 그래서 같은 커밋을 검토용 폴더에 복제해 데몬 없이 돌렸다.
  - 복제본 `./gradlew --no-daemon -I <init> build --rerun-tasks --continue` → **BUILD SUCCESSFUL, 130개 작업 모두 실행**. 모듈별 테스트(건너뜀 0, 실패 0): risk 55, architecture-test 31(ArchitectureTest 18, ModuleOwnershipTest 13), app-engine 48, marketdata 126, app-gateway 40, sim 196, app-lab 143, app-experiment 17, 그 밖의 모듈 전부 통과. 새 테스트: FeedServerTest 8, JournalWriterTest 4, GatewayFeedTest 2, LiveSessionTest 6, LivePlanTest 2, LiveLabServiceTest 3, ExperimentGateTest 17(2개 추가). RealTradingGuardTest·GuardedOrderGatewayTest 통과.
  - 코드 확인: 게이트웨이(`Gateway`, `GatewayMain`, `MarketHub`), `FeedServer`/`FeedClient`/`FeedSocket`/`FeedProtocol`, `DayWriter`/`JournalWriter`/`EventSequences`, `RunStepper`와 `SimulationExperimentRunner` 분리 전후, `LiveSession`/`LiveSetup`/`LiveReplayCheck`/`LiveCheckMain`, `LiveLabService`/`LabWebConfiguration`, 실전 엔진 의존(`autotrade-app-engine/build.gradle.kts`), 감시(`WatchdogMain`), 정지 요청 파일 소비자 전부(grep), 문서 ADR-010·D-076·절차서·ADR-003/004·기획서 diff.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`·RealTradingGuard·설정 로더 변경 없음(`git diff --stat`). `RealTradingGuardTest` 통과(risk 55건 중) |
| A2 | 통과 | diff에 실전 주문 출구·`RealOrderGateways`·ArchUnit 규칙 변경 없음(`autotrade-architecture-test` 파일 변경 0). ArchitectureTest 18건·ModuleOwnershipTest 13건 통과. 새 코드 어디에도 `OrderGateway`·증권사 주문 호출 없음(diff grep, `broker`는 주석 문구뿐) |
| A3 | 통과 | 게이트웨이 변경은 녹화 옆에 전달 출구를 붙인 것뿐(`MarketHub.java` subscribe 람다, `recordGap`). 주문·증권사 선택 코드 변경 없음. 게이트웨이는 여전히 주문 API를 부르지 않음(MD-01, `GatewayMain` 주석과 코드) |
| A4 | 해당 없음 | 실전 주문 송신 코드 변경 없음. 가상 주문 제출은 `SimulationExperimentRunner`에서 `RunStepper.java:219-233`로 옮겨졌고 내용 동일(diff 대조) |
| A5 | 해당 없음 | 실전 멱등 키 경로 변경 없음. 가상 키 `SimOrderKeys`는 그대로 `RunStepper`로 옮겨짐(`RunStepper.java:111,220`) |
| A6 | 통과 | 실전 블랙리스트 코드 변경 없음. 실시간 모의 세션도 녹화 실험과 같은 `RiskChain.builder(blacklist)`(`LiveSetup.java:59-62`)를 거쳐 `RunStepper.submit`(`RunStepper.java:223`)에서 평가됨. 다만 세션 쪽 블랙리스트 연결을 직접 시험하는 테스트는 없음(점검표 밖 N2) |
| A7 | 해당 없음 | 실전 계좌 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | **실패** | 좋은 점: `FeedServer.publish`는 기다리지 않고 예외를 삼킴(`FeedServer.java:110-122`), 받는 쪽마다 고정 큐·전용 쓰기 스레드·30초 멈춤 끊기, 전달 소켓을 못 열어도 녹화 계속(`GatewayMain.openFeed`, `GatewayFeedTest.전달_소켓을_열지_못해도_게이트웨이는_녹화만_한다`, `FeedServerTest.slowSubscriberNeverBlocksPublishAndGetsOverflowGap` 통과). 실전 엔진은 sim·lab·gateway를 의존하지 못함(`autotrade-app-engine/build.gradle.kts` processIsolation). 그러나 아래 R1·R2·R3이 자원 부족 시 정지 순서(broker-and-fault-design 5.2)와 "실험실은 자원을 빼앗지 않는다"(SIM-14)를 깬다 |
| A9 | 통과 | 실전 엔진·보호 시세 경로·서버 측 손절 코드 변경 없음. 게이트웨이 수신·녹화 경로는 전달이 실패해도 그대로(`MarketHub.java` subscribe: 녹화 먼저, 전달은 큐에 넣기만), `GatewayFeedTest` 2건 통과 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(`git diff` 추가 줄에서 appkey/secret/token/account/password grep 0건). 소켓은 폴더 700·파일 600(`FeedSocket.java`, `FeedServerTest.socketIsOwnerOnlyAndSecondServerIsRefused`). 구독 줄 오류는 원문을 메시지에 넣지 않음(`FeedProtocol.parseSubscribe`) |
| A11 | 통과 | 보호 테스트(ArchitectureTest, RealTradingGuard·GuardedOrderGateway 테스트, 장애 테스트) 삭제·변경 없음. 새 동작마다 실패 경우 포함 테스트 있음(받는 쪽 상한·틀린 구독 줄 거부, 멈춘 받는 쪽 끊기, 세션 잠금, 정지 요청 시 시작 거부, 리플레이 확인 "다름" 검출). R1~R3 고칠 때 각각 실패 테스트가 함께 와야 함 |
| A12 | **실패** | 이 PR은 장애 대응표의 전제를 바꾸는데 `docs/team/broker-and-fault-design.md`가 바뀌지 않았다(diff에 없음): ① 5.2의 정지 요청 파일 `experiment-state/stop-request`(0번, "실험 실행 명령" 전용으로 적힘)를 이제 실험실 프로세스의 실시간 세션도 읽는다(`LiveLabService.java:48,184-190`) ② 장애표 #9(게이트웨이 크래시)의 실험실 대응이 이제 "전달 끊김 → `FEED-CLIENT` DISCONNECTED 공백"이고 ③ #10(PC 자원 고갈)에 장중 내내 쓰는 새 디스크 소비자(입력 기록)가 생겼다. R4 |
| A13 | 해당 없음 | 실시간 세션은 KRX만 받는다(`LivePlan.java:80-81`). 시간은 캘린더 현지 시간대(`JournalWriter`가 `ExchangeCalendar.bundled(market).zone()`을 받음), 금액은 기존 `RunStepper` 그대로 |
| A14 | **실패** | D-076은 "장애 대응 규칙은 바꾸지 않는다"고 적었지만 위 A12처럼 정지 순서·장애표 전제가 바뀐다. 또 ADR-010 안에서 2.6("기록 전체를 메모리에 올리므로 무거워 실험실 안이 아니라 실험 명령으로만, 장 시간 보호")과 2.5("재기동은 기록을 처음부터 다시 돌림" = 실험실 안에서 장중에 같은 일)가 서로 어긋난다(R2) |

## 수정이 필요한 항목

### R1. 실시간 세션 입력 기록에 디스크 여유 자체 확인이 없다 (A8, A12)
- [사실] 세션은 장중 내내 `<설정 폴더>/live-sessions/<ID>/journal/`에 게이트웨이 녹화와 비슷한 양을 한 번 더 쓴다(ADR-010 4장 "디스크"). 쓰기는 `JournalWriter.append`(동기)이고 디스크 여유를 보는 코드가 없다(`LiveLabService.pump` `LiveLabService.java:233-258`는 정지 요청 파일만 봄).
- [사실] 감시 프로세스에는 자원 임계치 코드가 없다(`autotrade-app-watchdog`에 `stop-request`·임계치 코드 0건, D-058 ④ "감시 쪽 구현은 후속"). 그래서 지금 이 세션을 디스크 부족에서 멈출 주체가 없다.
- [사실] 같은 이유로 수집기는 스스로 디스크 여유를 본다: "실전 엔진의 상태 기록·DB 쓰기가 같은 디스크를 쓸 수 있어서"(ADR-008 2.7, `RunGuard.java:39`).
- [추론] 8주 운용 중 디스크가 차면 게이트웨이 녹화와 7단계 실전 엔진 상태 기록(장애표 #8 재기동 복구 전제)이 함께 실패한다. 실험실이 먼저 물러나야 한다는 5.2 순서와 반대다.
- 요구: 세션 시작 때와 처리 스레드의 1초 점검마다 입력 기록 폴더의 디스크 여유가 기준(수집기와 같은 기본값 `[가정]` 20GB 또는 설정 키) 미만이면 세션을 멈추고 `stopReason`에 남긴다. 판단할 수 없으면 멈추는 쪽. 기준 미만 시작 거부·운용 중 멈춤 테스트를 추가한다. ADR-010 2.7과 절차서에 적는다.

### R2. 재기동 복구가 입력 기록 전체를 실험실 메모리에, 장중에, 요청 스레드에서 올린다 (A8, A14)
- [사실] `LiveSession.restore`(`LiveSession.java:167-181`)가 `RecordedMarketDataSet.fromDirs`로 운용 기간 전체 기록을 `List<MarketEvent>`로 올린 뒤(`RecordedMarketDataSet.java:65,113`) 처리한다. 이 일은 `LiveLabService.start`(`LiveLabService.java:87-121`, `synchronized`) 안의 `LiveSession.open`에서, HTTP 요청 스레드(보통 우선순위)로 돈다. 복구 중에는 정지 요청 파일을 보지 않는다.
- [사실] ADR-010 2.6은 같은 일(기록 전체를 메모리에)을 "무거우므로 실험실 안이 아니라 실험 명령으로만, D-063 장 시간 보호"로 정했다. 2.7은 "세션 처리 스레드는 가장 낮은 우선순위"라 했지만 복구는 그 스레드가 아니다.
- [사실] 실험실 `bootRun`에는 힙 상한이 없다(`autotrade-app-lab/build.gradle.kts`에 `maxHeapSize`·`-Xmx` 없음, build-logic에도 없음). SIM-14의 "힙·스레드 상한"이 실험실 프로세스에 걸려 있지 않다.
- [추론] 몇 주 분량(받는 종목 수에 비례)을 장중 재기동으로 올리면 실험실이 PC 메모리를 크게 쓰고 GC로 CPU를 쓴다. 기본 최대 힙은 물리 메모리의 1/4이라 실전 엔진과 메모리·CPU를 다툰다. ADR-010 4장도 재기동 시간을 미측정 `[가정]`으로 둔다.
- 요구(둘 다): ① 복구를 날짜 폴더 하나씩 읽어 처리하고 버리는 방식으로 바꾸고(같은 순번 규칙 유지), 날짜마다 정지 요청 파일(그리고 R1의 디스크 기준)을 확인해 멈출 수 있게 한다. 복구는 요청 스레드가 아니라 최저 우선순위 처리 스레드에서 돌린다. ② 실험실 프로세스의 힙 상한을 명시하거나(예: `bootRun` `maxHeapSize`), 상한을 둘 수 없으면 그 이유와 위험을 ADR-010 4장·장애표 #10에 적는다. 복구 도중 정지 요청으로 멈추는 테스트를 추가한다.

### R3. 게이트웨이 전달 서버의 연결 받기 실패가 쉬지 않고 돈다 (A8)
- [사실] `FeedServer.acceptLoop`(`FeedServer.java:138-160`)는 `server.accept()`가 `ClosedChannelException`이 아닌 `IOException`을 던지면 경고를 남기고 바로 `continue`한다. 기다림이 없다.
- [추론] 파일 핸들 고갈(EMFILE)처럼 계속 실패하는 상황에서 이 데몬 스레드가 CPU 한 코어를 쓰며 경고 로그를 초당 수만 줄 남긴다(로그 디스크도 소비). 이 프로세스는 7단계에 실전 엔진에 시세를 줄 게이트웨이이고, 파일 핸들 고갈은 바로 자원 부족 상황이라 그때 상황을 더 나쁘게 만든다. 전달은 부가 기능이라는 ADR-010 2.1의 전제("수신·녹화를 막지 않는다")와 어긋난다.
- 요구: 실패 시 짧은 대기와 지수 백오프(예: 100ms→최대 5초), 같은 오류 로그는 횟수만 세어 간격을 두고 남긴다. 연속 실패 시 대기가 걸리는지 보는 테스트(가짜 채널 또는 주입 가능한 accept 실패)를 추가한다.

### R4. 장애 대응 문서 갱신 (A12, A14)
- `docs/team/broker-and-fault-design.md` 5.2 0번(또는 2번 "실험실 일시정지")에 실험실 실시간 세션이 `experiment-state/stop-request`를 읽고 멈춘다는 것과 해제 기준(파일이 없어지면 사용자가 다시 시작, 자동 재개 없음)을 적는다.
- 장애표 #9 실험실 대응에 "게이트웨이 전달 끊김은 받는 쪽 재접속(1~30초 백오프)과 `FEED-CLIENT` DISCONNECTED 공백", #10 감지·대응에 R1의 입력 기록 디스크 기준과 R2의 복구 메모리를 적는다.
- D-076 상태 줄의 "장애 대응 규칙은 바꾸지 않는다"를 실제와 맞게 고치고(정지 순서 소비자 추가), ADR-010 2.5와 2.6의 어긋남을 R2 수정 내용으로 해소한다.

## 점검표 밖 발견
- N1 (architect·data-engineer 참고, 차단 아님): ADR-010 2.3과 D-076은 전달이 녹화와 "같은 순서"라고 하지만, `MarketHub` subscribe 람다의 `recorder.offer` → `feed.publish`는 원자적이지 않아 SHARDED 모드처럼 소스 스레드가 둘 이상이면 녹화 파일 순서와 전달 순서가 다를 수 있다 `[추론]`. 실험실 결정론은 입력 기록이 기준이라 깨지지 않지만, 6단계 주간 점검의 "실시간 대 게이트웨이 녹화 리플레이" 차이 해석에 이 요인을 넣어야 한다. 문구를 "소스별 순서는 같다"로 고치거나 테스트(`GatewayFeedTest`는 소스 하나만 봄)를 두 소스 동시로 넓힐 것.
- N2 (권고): 실시간 세션에서 블랙리스트 종목이 `boards`에 있어도 주문이 `RISK`로 거절되는지 보는 테스트가 없다. 연결 코드(`LiveSetup.java:59-62`)는 녹화 실험과 같지만 별도 복사본이라 회귀 위험이 있다. SIM-12 취지로 세션 단위 테스트 하나를 권한다.
- N3 (권고): 게이트웨이가 전달에서 버린 수(`FeedServer.droppedCount()`)와 받는 쪽 수를 어디에도 남기지 않는다(`autotrade-app-gateway`에서 호출 0건). 녹화기 통계처럼 주기 로그나 상태에 넣어야 6단계 주간 점검에서 실험실 입력 부족 원인을 가를 수 있다.
- N4 (참고): `Thread.MIN_PRIORITY`(`LiveLabService.java:115`)는 Linux 기본 JVM 설정에서 무시된다. Windows에서는 반영된다. 사용자 PC가 Windows라 문제는 작지만, 우선순위에 기대는 문구(ADR-010 2.7)는 "Windows에서만 효과"로 적을 것.
- N5 (7단계 전 확인): 실전 엔진은 `autotrade-marketdata`를 의존하므로 이제 `FeedServer`/`FeedClient`가 엔진 클래스패스에 있다. 지금 쓰지 않지만 ADR-010 4장 마지막 줄처럼 엔진 전달은 기준이 달라야 하므로, 7단계 결정 전까지 엔진 패키지에서 `com.autotrade.marketdata.feed` 사용을 막는 ArchUnit 규칙을 검토할 것.
- N6 (참고): 실험실이 같은 OS 사용자의 다른 프로세스라면 소켓 600 권한으로는 막지 못하고 4자리를 차지할 수 있다(구독 전 5초 제한, 상한 `MAX_SUBSCRIBERS`). 받는 쪽이 4개를 넘으면 실험실 세션이 끊긴 채 재시도만 한다. 게이트웨이에는 영향 없음. 위험 낮음, 기록만.

## 판정
수정 필요(A8: R1·R2·R3, A12·A14: R4)

## 재검토 2026-10-14
- 대상: PR #87 / 브랜치 `claude/project-thread-go4oua` / 커밋 `b4c9227` / `git diff origin/main...HEAD` 전체. 앞 검토(`3ecb2e8`) 뒤 커밋 `4286534`(architect 검토 반영)와 `b4c9227`(risk-ops 검토 반영)을 포함해 다시 봤다.
- 점검표: A
- 직접 실행한 것:
  - 다른 세션의 빌드와 출력 폴더가 겹치지 않게 `b4c9227`을 검토용 폴더에 새로 복제하고, 앞 검토와 같은 저장소 밖 init 스크립트(Maven Central 429 회피용 Google 미러)로 `./gradlew --no-daemon build --rerun-tasks --continue`를 돌렸다 → **BUILD SUCCESSFUL, 130개 작업 모두 실행**. 건너뜀 0, 실패 0. 모듈별: risk 55(RealTradingGuardTest 31, GuardedOrderGatewayTest 8), architecture-test 31(ArchitectureTest 18), app-engine 48, marketdata 128, app-gateway 40, sim 199, app-lab 145, app-experiment 17, 나머지 모듈 전부 통과.
  - 새 테스트와 바뀐 테스트: AcceptBackoffTest 2, LiveSessionTest 9(`blacklistedSymbolIsNeverBoughtInTheLiveSession`, `pricesOffTheStockTickStopTheSessionWithoutWritingThem`, `crashWithLostTailAndUnclosedMetaStillReplaysTheSame` 추가), LiveLabServiceTest 5(`lowDiskRefusesStartAndStopsARunningSession`, `restoreCanBeStoppedAndResumesFromTheJournal` 추가), FeedServerTest 8, GatewayFeedTest 2. 모두 통과.
  - 코드 확인: `3ecb2e8..b4c9227` 전체 diff(21파일), 바뀐 `FeedServer`·`AcceptBackoff`·`Gateway.start` 정리 경로, `LiveLabService`(되살리기 스레드, 디스크 하한, 멈춤·정리 경쟁), `LiveSession.restore`(기록일 단위 순번 이어 매기기), `RecordingExperiment.offStockTick`, 문서(broker-and-fault-design 5.2·#9·#10, D-076, ADR-010, config-keys, 절차서).
  - 이 파일 앞부분(첫 검토)은 `b4c9227`에 커밋된 내용이 작업 트리와 같고, 판정 줄도 그대로임을 확인했다.

### 앞 검토 항목 처리
| 항목 | 결과 | 근거 |
|---|---|---|
| R1 디스크 하한 | 해결 | `LiveLabService.stopReason()`이 세션 폴더 디스크 여유를 보고(`FileStore.getUsableSpace`), 확인할 수 없으면 멈추는 쪽으로 처리한다. 기본 20GB이고 `autotrade.lab.live.min-free-gb`로 바꿀 수 있다(`LabWebConfiguration`). 확인 지점은 시작 전, 처리 스레드의 1초 점검, 되살리기의 기록일마다다. `LiveLabServiceTest.lowDiskRefusesStartAndStopsARunningSession`: 19GB면 시작 거부, 운용 중 5GB로 떨어지면 멈추고 `stopReason`에 남음 |
| R2 되살리기 메모리·스레드 | 해결 | `LiveSession.restore`가 기록일 하나씩 `fromDirs(List.of(day))`로 읽고, 앞 날짜 이벤트 수만큼 순번을 밀어 처리한다. 날마다 `beforeEachDay`로 정지 요청·디스크·취소를 확인한다. 되살리기는 요청 스레드가 아니라 `live-restore` 스레드(MIN_PRIORITY)에서 돌고, 그동안 `restoring=true`이며 `finish`는 거부한다. 멈춘 되살리기가 끝날 때까지 다시 시작하지 못한다(`lingering`). 실험실 `bootRun`에 `maxHeapSize` 4g(`-PlabHeap`)가 생겼다. 순번이 전체 읽기와 같다는 것은 `liveResultEqualsJournalReplayAcrossLabRestartAndFeedGap`이 보여 준다(이틀치 중 2/3 지점 재기동, 즉 하루 넘게 되살린 뒤 전체 읽기 리플레이와 보고서 바이트 일치). 멈춤·재개는 `restoreCanBeStoppedAndResumesFromTheJournal`이 확인한다 |
| R3 accept 실패 반복 | 해결 | `FeedServer.acceptLoop`이 실패 때 `AcceptBackoff`로 10ms→1s 쉬고, 로그는 2의 거듭제곱 번째 실패에만 남긴다. 닫힌 뒤면 바로 끝난다. 정책은 `AcceptBackoffTest` 2건으로 확인했다. 루프 자체를 실패 주입으로 돌리는 시험은 없지만, 루프는 그 정책을 `Thread.sleep(backoff.delayMillis())`로 그대로 쓴다(코드 확인) |
| R4 장애 문서 | 해결 | broker-and-fault-design 5.2 2번: 실시간 세션이 `experiment-state/stop-request`와 디스크 하한으로 멈춘다는 내용 추가. 장애표 #9: 전달 버림·끊기·소켓 실패 시 녹화만 함, 실험실 공백 처리 추가. #10: 세션 자체 정지, 입력 기록 크기, 되살리기, 힙 상한 추가. D-076 상태 줄은 실전 관련 PR(사용자 머지 대상)로 고쳐졌다. ADR-010 2.5~2.7과 4장이 서로 맞는다 |
| N1~N6 | 반영 또는 후속 등록 | N1: ADR-010 2.3 문구를 "한 소스 안 순서만 같음"으로 고침. N2: `blacklistedSymbolIsNeverBoughtInTheLiveSession` 추가. N3: 버린 수를 1분에 한 번까지 게이트웨이 로그에 남김(`FeedServer.checkStalls`). N4: D-076 7번에 "Linux에서는 효과 없음" 명시. N5·N6: ADR-010 5장 후속 표에 7단계 전 확인 항목으로 등록 |

### 점검표 A (b4c9227 기준)
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`·RealTradingGuard·설정 로더 변경 없음. `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 실전 주문 출구·ArchUnit 규칙 변경 없음(`autotrade-architecture-test` 변경 0). ArchitectureTest 18·ModuleOwnershipTest 13 통과. 새 코드에 `OrderGateway`·증권사 주문 호출 없음 |
| A3 | 통과 | 게이트웨이 변경은 전달 출구, 기동 실패 정리(`Gateway.start` catch에서 연 것 닫기), accept 백오프뿐이다. 주문·증권사 선택 코드 변경 없음 |
| A4 | 해당 없음 | 실전 주문 송신 코드 변경 없음. 가상 제출은 `RunStepper.submit`으로 옮겨졌고 내용 동일 |
| A5 | 해당 없음 | 실전 멱등 키 경로 변경 없음. 가상 `SimOrderKeys` 그대로 |
| A6 | 통과 | 실전 블랙리스트 코드 변경 없음. 실시간 세션도 같은 `RiskChain`을 쓰고(`LiveSetup.java:59-62`), 블랙리스트 종목을 사지 않는 것을 `LiveSessionTest.blacklistedSymbolIsNeverBoughtInTheLiveSession`이 확인한다 |
| A7 | 해당 없음 | 실전 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 게이트웨이: `publish`는 기다리지 않고, 받는 쪽마다 큐·쓰기 스레드가 따로 있고, 한 줄 쓰기가 30초 넘게 막히면 끊는다(`writingSince`). accept 실패에는 백오프가 걸리고, 소켓을 못 열면 녹화만 한다(`GatewayFeedTest` 2, `FeedServerTest` 8). 실험실: 정지 요청 파일과 디스크 하한으로 시작 거부·운용 중 정지, 되살리기는 기록일 단위·최저 우선순위 스레드에서 날마다 정지 확인, 힙 상한 4g(R1·R2 근거). 실전 엔진은 sim·lab·gateway를 의존하지 못한다(`autotrade-app-engine/build.gradle.kts` processIsolation, `verifyProcessIsolation` 통과) |
| A9 | 통과 | 실전 엔진·보호 시세 경로·서버 측 손절 코드 변경 없음. 게이트웨이 수신·녹화 경로는 전달 실패와 무관하다(녹화 먼저, 전달은 큐에 넣기만) |
| A10 | 통과 | 추가된 줄에 키·계좌번호·토큰 없음(grep). 소켓 폴더 700·파일 600. 오류 메시지에는 예외 클래스 이름과 경로만 남는다 |
| A11 | 통과 | 보호 테스트 삭제·약화 없음. 새 동작마다 실패 경우를 포함한 테스트가 있다(디스크 하한, 되살리기 중 멈춤, 호가 단위 밖 가격 정지, 블랙리스트, 백오프 정책) |
| A12 | 통과 | 바뀐 동작이 broker-and-fault-design 5.2 2번과 장애표 #9·#10에 반영됐다(R4). 장애 테스트 8종의 전제(실전 엔진 격리, 시세 전환, 엔진 재기동)를 깨는 변경은 없다. 게이트웨이 쪽은 기다리지 않는 성질이 유지된다 |
| A13 | 해당 없음 | 실시간 세션은 KRX만 받는다(`LivePlan.java:80-81`). 시간대는 캘린더에서 받고 금액은 기존 실행기 그대로 |
| A14 | 통과 | D-076 상태·결정 7개 항목, ADR-010 2장·4장·5장·6장, config-keys, 절차서가 코드와 맞다. 문서와 코드를 대조한 곳: 디스크 기본 20GB(`LiveLabService.DEFAULT_MIN_FREE_GB`·`@Value` 기본값), 힙 4g(`build.gradle.kts`), accept 10ms→1s(`AcceptBackoff`), 멈춤 판정은 한 줄 쓰기 30초(`FeedServer.STALL_LIMIT`·`writingSince`) |

### 점검표 밖 발견(차단 아님)
- M1: 멈춤 판정의 표시값 `writingSince = Math.max(1, System.nanoTime())`에서 0은 "쓰는 중 아님"을 뜻한다. `System.nanoTime()`의 기준점은 JVM이 정하므로 음수가 될 수 있다는 것이 명세상 허용된다. 그러면 표시값이 1로 고정되고, `now - 1`이 30초를 넘어 정상 받는 쪽이 끊길 수 있다 `[추론]`. Linux·Windows HotSpot에서는 단조 시계가 양수라 실제 영향은 낮다 `[추론]`. 끊겨도 게이트웨이에는 영향이 없고, 실험실은 재접속하며 공백으로 남긴다. 표시값을 별도 boolean으로 두기를 권한다(data-engineer).
- M2: 되살리기 중 멈춤은 기록일 사이에서만 확인한다. 하루치가 큰 날에는 정지 요청 뒤 그날 처리가 끝날 때까지 실험실이 계속 계산한다(최저 우선순위 스레드, 힙 상한 안). 하루 안 N개 이벤트마다 확인하도록 넓히는 것은 6단계 첫 재기동 측정(ADR-010 4장 `[가정]`) 뒤 정해도 된다.
- M3: `AcceptBackoff`는 정책 단위 시험만 있고, `acceptLoop`에 실패를 주입하는 시험은 없다. 코드가 정책을 그대로 쓰므로 차단하지 않는다.
- 머지 절차: D-076 상태 줄에 적힌 대로 이 PR은 실전 관련 PR(프로세스 격리·장애 대응 문서)이다. 이 승인은 risk-ops 역할 검토에 한한다. 지침 10·11장에 따라 독립 QA 검토와 **사용자 직접 머지**가 남아 있다.

### 판정
승인
