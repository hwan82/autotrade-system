# architect 검토: 실시간 시세 전달(게이트웨이 → 실험실)과 실시간 모의 세션 (D-076, ADR-010)
- 대상: PR #87 / 브랜치 `claude/project-thread-go4oua` 커밋 eb227f10cc1229868add5fe8456587b4ddd840b7 (355680c, 3bfe1f9 main 반영, eb227f1) / `git diff origin/main...HEAD` 전체(origin/main 56917c0, 브랜치가 main을 포함함 확인). 39개 파일 +3832 −237
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` 그대로: Maven Central 429(Too Many Requests)로 의존성 해석 실패(`--offline`은 캐시 없음으로 실패). 코드 결함이 아니라 컨테이너 네트워크 문제.
  - 저장소 파일은 바꾸지 않고, 같은 Maven Central 내용을 주는 Google 미러를 앞에 두는 임시 init 스크립트(스크래치 폴더, `-I`)로 `./gradlew build` 실행. 첫 두 번은 Gradle 데몬 여러 개가 겹쳐 결과 XML 쓰기·클래스 파일 쓰기 실패(`could not create parent directories`, Kryo `Buffer underflow`)가 났고, `./gradlew --stop` 뒤 `--max-workers=2`로 다시 돌린 `./gradlew build --rerun-tasks --continue` → **BUILD SUCCESSFUL, EXIT=0**(2분 55초). verifyPureDependencies 14개 모듈 실행.
  - 테스트 결과 XML 합계: **1048개, 실패 0, 오류 0, 건너뜀 0**. 새·바뀐 시험: FeedServerTest 8, JournalWriterTest 4, GatewayFeedTest 2, LiveSessionTest 6, LivePlanTest 2, LiveLabServiceTest 3, ExperimentGateTest 17(2개 추가). ArchitectureTest 18, ModuleOwnershipTest 13 통과.
  - 검토용 임시 시험(커밋 안 함, 실행 뒤 지움, `git status` 깨끗함 확인): 세션에 이벤트 1/3을 넣고 `flush` 뒤 20개를 더 넣은(flush 안 된 꼬리) 상태에서 입력 기록 폴더를 복사해 **비정상 종료**(gzip 멤버 미종료, 메타 미종료)를 흉내 내고, 복사본으로 세션을 다시 열어 나머지를 넣고 끝낸 뒤 `LiveReplayCheck.run` → "되살림 1031건", **같음**(상세 보고서 바이트 일치). ADR-010 2.5의 비정상 종료 주장을 확인.
  - `git diff`에서 삭제 파일 없음, `@Disabled` 추가·`@Test` 삭제 없음. 비밀값 패턴 검색: CSRF 헤더 이름(절차서 PowerShell 예시)만 걸림.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 실행. 미러 init 스크립트(저장소 밖)만 더한 `./gradlew build --rerun-tasks` EXIT=0, 1048/0/0/0. 429 우회만 했고 빌드 스크립트·테스트는 그대로 |
| B2 | 통과 | 새 패키지는 기존 소유 모듈 안: `com.autotrade.marketdata.feed`(autotrade-marketdata), `com.autotrade.sim.live`·`sim.run.RunStepper`(autotrade-sim), `com.autotrade.app.lab.live`(app-lab). 순수 모듈의 새 의존은 JDK(`java.nio` Unix 소켓)와 기존 Jackson뿐, 스프링 없음. 의존 방향 gateway·lab·sim → marketdata는 기존 방향. 게이트웨이(순수 Java 프로세스)에 스프링 없음. verifyPureDependencies·ArchitectureTest·ModuleOwnershipTest 통과, ArchUnit 규칙 변경 없음 |
| B3 | 실패(경미) | 새 계약(소켓 규약, `input: live` 등록 파일, `/api/live` REST, 입력 기록 폴더, 리플레이 확인 명령)은 ADR-010·D-076·ADR-003 5장·ADR-004·기획서 v14.24에 있음. 그러나 단일 목록 문서가 갱신되지 않음: ① `docs/config/config-keys.md`는 설정 폴더 안 파일(`run/engine-control.sock`, `recordings/`, 11·48행)을 적는 곳인데 새 `run/market-feed.sock`, `live-sessions/`, 실험실 시스템 속성 `autotrade.code-commit`·`autotrade.code-dirty`(LabWebConfiguration `liveLabService`, 기본 `unknown`/`true`)가 없음 ② `docs/adr/ADR-005-ui-dashboard.md` API 표(88행~)에 `/api/live/*` 4개가 없음(R2) |
| B4 | 통과 | 실행기 공통부 분리(`RunStepper`)는 기존 `runOne`·`submit`·`closeDay`·`settleAtEnd`·`feedWarmup`을 순서 그대로 옮김(diff 대조). 골든 `krx-golden.report` 쓰는 RecordedMarketDataSetTest·RecordingExperimentTest·SelectionCombinationRunnerTest 등 기존 테스트 수정 없이 통과. 실시간 세션 계좌는 이벤트 수신 시각의 `ReplayClock`으로만 움직이고(RunStepper.java:108,130), 주입 `Clock`은 기록 메타·flush 간격·끊김 공백 시각에만 씀(LiveSession.java:228,238). 입력 기록을 먼저 쓰고 쓴 줄을 기록 전체 위치 순번으로 계좌에 줌(LiveSession.java:226~236) → 정상 재기동(LiveSessionTest `liveResultEqualsJournalReplayAcrossLabRestartAndFeedGap`)과 비정상 종료(위 임시 시험) 모두 리플레이와 바이트 일치. 새 `HashMap`(FeedServer `overflowSince`, LiveLabService `overflowSince`)은 조회만, 순회 없음 |
| B5 | 통과 | 벤치마크 수익률 `BigDecimal`·`MathContext.DECIMAL128`(LiveSession.java:362), 시드머니 `Money`, 가격은 기존 `BigDecimal` 이벤트. double 없음 |
| B6 | 통과 | 새 동작마다 시험 있음(ADR-010 6장 목록과 실제 테스트 이름·개수 일치 확인). 게이트웨이 무대기: `FeedServerTest.slowSubscriberNeverBlocksPublishAndGetsOverflowGap`, 멈춘 받는 쪽 끊기, 상한·틀린 구독 줄, 소켓 600·폴더 700. 게이트웨이 녹화 줄 = 실험실 수신 줄: `GatewayFeedTest`. 기존 테스트 삭제·약화 없음. 다만 비정상 종료 재기동은 커밋된 시험이 없음(R4, 권고) |
| B7 | risk-ops 검토 필요 | 실전 주문 경로·RealTradingGuard·블랙리스트 코드·리스크 한도·보호 테스트는 바뀌지 않음. 그러나 7단계에 실전 엔진에 시세를 줄 **시세 게이트웨이 프로세스의 수신 스레드 경로**가 바뀜(MarketHub.java:215~238 `feed.publish`, Gateway.java close 순서, GatewayMain.java:102 `openFeed`). 절대 규칙 6(실전 매매는 다른 기능에 영향받지 않음)과 broker-and-fault-design 5장에 걸리므로 D-076도 risk-ops 검토를 요구함. 이 검토 시점에 `docs/reviews/pr-87/risk-ops.md` 없음 |
| B8 | 통과 | 키·계좌번호·토큰 없음. 새 로그는 소켓 경로, 받는 쪽 이름(`[a-z0-9-]{1,32}`), 예외 클래스 이름만. 구독 줄 오류 메시지에 원문을 넣지 않음(FeedProtocol.parseSubscribe). 입력 기록은 설정 폴더(저장소 밖), 절차서가 커밋 금지 명시 |

## 점검표 밖 발견

**R1 (수정 필요, 결과 정확성) 실시간 경로에 D-075 ④ 주식 호가 단위 검사가 없다.**
- [사실] 녹화 실험은 녹화에 상품 유형이 없어 모든 종목을 주식 호가 단위로 보므로, 그 단위에 맞지 않는 가격(ETF·ETN 5원 단위 등)이 있으면 시작 전에 거절한다(RecordingExperiment.java:109~114 `requireStockTicks`, D-075 ④, PR #74 architect R2).
- [사실] 실시간 세션과 리플레이 확인은 같은 가정(`KrxMarketRules(calendar, s -> InstrumentKind.STOCK)`, LiveSetup.java:46)을 쓰면서 이 검사를 어디서도 하지 않는다(`sim/live` 안에 `requireStockTicks` 호출 없음). LiveSetup 주석은 "녹화 실험(D-069)과 같은 규칙"이라고 적는다.
- [추론] `boards`에 ETF·ETN이 들어가면 가상 거래소가 주식 호가 단위로 지정가·슬리피지 반올림을 해 8주 운용 결과가 조용히 틀린다. 리플레이 확인도 같은 틀린 규칙을 쓰므로 "같음"으로 합격해 발견되지 않는다.
- 요청: 실시간 경로에도 같은 보호를 둔다. 예: `LiveSession.accept`에서 기록에 쓰기 전에 `requireStockTicks`로 보고 맞지 않으면 세션을 멈추고 이유를 남기기(또는 그 종목을 받지 않고 세기), `LiveReplayCheck`도 기록 전체에 같은 검사. 위반 시도가 막히는 시험 하나.

**R2 (수정 필요, 문서) 설정 폴더·API 단일 목록 갱신.** 위 B3. `config-keys.md`에 `run/market-feed.sock`(게이트웨이가 열고 폴더 700·소켓 600, 경로 약 100자 제한, 못 열면 녹화만), `live-sessions/<실험ID>/`(입력 기록·`session.lock`, 커밋 금지), `autotrade.code-commit`·`autotrade.code-dirty`(bootRun이 채움, 기본값, 실시간 변경 불가)를 추가하고, ADR-005 API 표에 `/api/live/status`, `/sessions/{id}/start`, `/stop`, `/finish`와 오류 코드(`CONFLICT` 409, `INVALID` 400)를 추가한다.

**R3 (권고, 낮음) 전달 서버 멈춤 판정의 오탐 창.** `checkStalls`(FeedServer.java:162~171)는 "큐가 비지 않음 && 마지막 쓰기 뒤 30초"로 끊는다. 30초 넘게 이벤트가 없던 뒤(장전·장후) 첫 묶음이 오면, 쓰기 스레드가 첫 줄을 꺼내 쓰고 `lastProgress`를 갱신하기 전 짧은 순간에 타이머가 돌면 정상 받는 쪽을 끊는다. 결과는 끊김 공백과 재연결뿐이라 결정론·게이트웨이에는 영향 없음 `[추론]`. 큐가 빈 상태에서 처음 넣을 때 `lastProgress`를 갱신하거나 "가장 오래된 대기 줄의 나이"로 판정하면 없어진다.

**R4 (권고) 비정상 종료 재기동 시험을 커밋한다.** 위 임시 시험과 같은 시나리오(flush 뒤 꼬리 유실, 메타 미종료 기록 복사 → 재개 → 리플레이 일치)를 LiveSessionTest에 넣으면 ADR-010 2.5·LiveSession 클래스 주석의 주장이 회귀 시험으로 지켜진다. 이번 검토에서는 통과를 직접 확인했다.

**R5 (참고) 재기동 복원이 HTTP 요청 스레드에서 서비스 잠금을 잡은 채 돈다.** `LiveLabService.start`(synchronized) 안의 `LiveSession.open → restore`가 기록 전체를 다시 돌린다. 몇 주 분량이면 시작 요청이 오래 걸리고 그동안 `/api/live/status`도 막힌다. 이 동안 실시간 이벤트는 받는 쪽이 아직 연결 전이라 게이트웨이 큐에도 쌓이지 않고 재기동 공백으로 덮이므로 결정론에는 영향 없음 `[추론]`. ADR-010 4장의 `[가정]`(재기동 시간 미측정)과 같은 항목이며, 측정 때 함께 본다.

**R6 (참고, 낮음)** ① `GatewayMain`이 `openFeed` 뒤 `Gateway.start`에서 예외가 나면 전달 서버가 닫히지 않는다(데몬 스레드라 프로세스는 끝나지만 소켓 파일이 남고, 다음 기동의 `removeStaleSocket`이 지운다). ② 입력 기록 날짜는 수신 시각으로 나누는데, 자정 근처에 수신 시각이 거꾸로 온 이벤트(실험실 시계로 만든 끊김 공백과 게이트웨이 수신 시각 차이 등)가 있으면 이전 날 파일에 다시 이어 써 실시간 순서와 리플레이(날짜 오름차순) 순서가 달라질 수 있다. KRX는 자정에 이벤트가 거의 없고, 생기면 리플레이 확인이 "다름"으로 잡으므로 숨겨지지 않는다 `[추론]`.

확인한 안전 관련 사실: `FeedServer.publish`는 받는 쪽마다 `ArrayBlockingQueue.offer`(기다리지 않음)만 하고 `RuntimeException`을 삼킴(FeedServer.java:110~122, 213~231). 인코딩·소켓 쓰기는 받는 쪽 전용 데몬 스레드. 받는 쪽은 구독 줄 하나만 보내고 그 뒤 무엇이든 오면 끊김(명령 경로 없음). 게이트웨이는 여전히 주문 API를 부르지 않음(안전 불변식 8). 녹화는 전달보다 먼저 넣음(MarketHub.java:235~236). 실험실 처리 스레드는 최저 우선순위, 1초마다 감시 정지 요청 파일 확인, 판단 불가도 멈춤(LiveLabService.java:185).

## 판정
수정 필요(R1 실시간 경로 주식 호가 단위 검사, R2 config-keys.md·ADR-005 API 표 갱신). R3·R4는 권고. B7은 risk-ops 검토가 별도로 필요하다(게이트웨이 수신 경로 변경, 지침 10장에 따라 머지 권한도 risk-ops 판정 뒤 정함).

## 재검토 2026-10-14
- 대상: PR #87 / 커밋 b4c9227f0620214b695a9ce3cb2fb05de805f920 (앞 검토 eb227f1 뒤에 3ecb2e8, 4286534, b4c9227) / `git diff origin/main...HEAD` 전체(origin/main 56917c0 기준 46개 파일 +4382 −256). origin/main이 4e32d75(#84, 문서)로 나아가 브랜치에 들어 있지 않지만 `git merge-tree --write-tree origin/main HEAD`로 충돌 없음 확인
- 점검표: B
- 직접 실행한 것:
  - 앞 검토와 같은 방식(Maven Central 429 회피용 미러 init 스크립트, 저장소 밖, `-I`)으로 `./gradlew build --rerun-tasks --continue --max-workers=2` → **BUILD SUCCESSFUL, EXIT=0**(2분 24초). 결과 XML 합계 **1055개, 실패 0, 오류 0, 건너뜀 0**. FeedServerTest 8, AcceptBackoffTest 2, GatewayFeedTest 2, LiveSessionTest 9, LiveLabServiceTest 5, ArchitectureTest 18 통과
  - `git diff eb227f1 b4c9227` 전부 읽음(22개 파일). 테스트 삭제·`@Disabled` 없음. GatewayFeedTest 변경은 try-with-resources를 명시 `close()`로 바꾼 것뿐이고 어서션 같음
  - 하루씩 되살리기의 순번 동치: `RecordedMarketDataSet`이 녹화일마다 `seen`·구간 경계를 따로 처리하고 순번만 `events.size()+1`로 이어 매김을 코드로 확인(RecordedMarketDataSet.java:136~172). 그래서 하루씩 읽어 `offset + e.sequence()`로 민 순번(LiveSession.java:189~199)이 전체 읽기와 같다. LiveSessionTest의 재기동·비정상 종료 시험이 이 경로로 리플레이와 바이트 일치

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 실행. EXIT=0, 1055/0/0/0 |
| B2 | 통과 | 새 운영 클래스 `AcceptBackoff`는 `com.autotrade.marketdata.feed`(autotrade-marketdata, 순수, JDK만). `RecordingExperiment.offStockTick` 공개는 sim 모듈 안. 실험실은 Spring 쪽만 바뀜. verifyPureDependencies·ArchitectureTest·ModuleOwnershipTest 통과 |
| B3 | 실패(경미) | 앞 R2는 대부분 반영: config-keys.md 11행 근처에 `run/market-feed.sock`·`live-sessions/` 설명, 표에 `autotrade.code-commit`·`code-dirty`, ADR-005 API 표에 `/api/live/*` 4행. 그러나 이번 커밋이 만든 계약이 빠짐: ① 새 설정 키 `autotrade.lab.live.min-free-gb`(LabWebConfiguration.java:106, 기본 20, 0 이상)가 config-keys.md 표에 없음(절차서·ADR-010·장애 문서에만 나옴) ② `POST /api/live/sessions/{id}/start` 응답이 `LiveSession.Status`에서 `LiveLabService.View`로 바뀌고 `View`에 `experimentId`·`restoring`이 더해졌는데(LiveSessionController.java, LiveLabService `View`) ADR-005 표의 status·start 행에 없음. 디스크 여유 부족(409), 되살리는 중 `finish`(409) 사유도 표에 없음(R2') |
| B4 | 통과 | 새 코드에 시스템 시계는 FeedServer의 `System.nanoTime()`(전달 멈춤 판정·로그 간격, 결과와 무관)과 LiveLabService `Clock`·디스크 조회뿐. 계좌 입력 경로는 그대로 "기록 먼저 → 쓴 줄"이고 호가 단위 검사는 기록 전에 하므로 기록에 없는 이벤트가 계좌에 가지 않음(LiveSession.java:247~253). 되살리기 순번 동치는 위 확인. 골든 테스트 그대로 통과 |
| B5 | 통과 | 새 가격 비교 `BigDecimal.compareTo`(RecordingExperiment `offStockTick`), 디스크 여유 `long` 바이트. 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 시험: 호가 단위 밖 가격 멈춤 `pricesOffTheStockTickStopTheSessionWithoutWritingThem`, 비정상 종료 `crashWithLostTailAndUnclosedMetaStillReplaysTheSame`(꼬리 20바이트 자름), 블랙리스트 `blacklistedSymbolIsNeverBoughtInTheLiveSession`(같은 시세 대조 포함), 디스크 하한 `lowDiskRefusesStartAndStopsARunningSession`, 되살리는 중 멈춤 `restoreCanBeStoppedAndResumesFromTheJournal`, `AcceptBackoffTest` 2개. 다만 전달 멈춤 판정 변경(R3 반영)에는 "오래 조용한 뒤 첫 이벤트에 끊지 않음" 시험이 없음(아래 권고) |
| B7 | risk-ops 검토 필요 | 게이트웨이 수신·연결 받기·기동 실패 정리 경로(FeedServer acceptLoop 백오프, Gateway.java:53~62 기동 실패 시 닫기)와 장애 문서(broker-and-fault-design 5.2 2번, 장애표 #9·#10)가 바뀜. D-076도 이 PR을 실전 관련(사용자 머지) 대상으로 바꿨다. `docs/reviews/pr-87/risk-ops.md`의 마지막 판정은 "수정 필요"이고 이 커밋 뒤 risk-ops 재검토 결과는 아직 커밋에 없음 |
| B8 | 통과 | 새 로그는 실패 횟수·예외 클래스 이름·버린 건수·디스크 GB뿐. 비밀값 없음 |

### 앞 검토 항목 처리
- **R1 해소.** `LiveSession.accept`가 기록 전에 `offStockTick`으로 검사해 맞지 않으면 기록하지 않고 세션을 멈춘다(LiveSession.java:247~253). `LiveReplayCheck`도 입력 기록 전체를 같은 함수로 검사(LiveReplayCheck.java:89~94). 녹화 실험 `requireStockTicks`는 같은 함수를 쓰도록 바뀌어 검사가 하나뿐. 시험 있음.
- **R2 대부분 해소, 남은 것은 위 B3(R2').**
- **R3 해소.** 판정 기준을 "한 줄 쓰기가 30초 넘게 막힘"(`writingSince`, FeedServer.java:190, 232, 296)으로 바꿔 조용한 뒤 첫 이벤트로 끊는 일이 없어짐. 읽지 않는 받는 쪽은 소켓 버퍼가 차 쓰기가 막히므로 `stalledSubscriberIsDisconnected`가 그대로 통과.
- **R4 해소.** 비정상 종료 시험 커밋됨(앞 검토의 임시 시험보다 강하게 gzip 꼬리를 잘라 봄).
- **R5 해소.** 되살리기를 최저 우선순위 별도 스레드에서 기록일 하나씩, 날마다 정지 요청·디스크 확인(LiveLabService.java:156~, `restoring` 상태). 상태 조회가 막히지 않음.
- **R6 ① 해소**(Gateway 기동 실패 시 연 것 닫기). ②는 그대로이며 리플레이 확인이 잡으므로 막지 않음.

### 이번 변경에서 새로 본 것
1. **(수정 필요, 문서) R2'** 위 B3.
2. (권고, 낮음) `writingSince = Math.max(1, System.nanoTime())`(FeedServer.java:296)는 0을 "쓰지 않음" 표시로 쓴다. `System.nanoTime()`은 명세상 음수일 수 있고, 음수면 1이 들어가 `now - 1`이 커져 정상 쓰기 중에도 바로 끊는다. Linux·Windows의 실제 JDK 구현은 부팅 기준 단조 시계라 양수로 보인다 `[추론]`, 컨테이너에서 확인 못 함. 별도 `volatile boolean writing`이나 `Long.MIN_VALUE` 같은 표시값을 쓰면 가정이 없어진다. 같이 "오래 조용한 뒤 첫 이벤트에 끊지 않음" 시험을 하나 두면 R3 반영이 회귀 시험으로 남는다.
3. (참고) 되살리는 동안 멈추면 그날 기록 하나를 다 읽을 때까지(기록일 경계) 스레드가 남고 세션 잠금을 쥔다. 그동안 시작은 "정리하는 중"으로 거부된다(LiveLabService `lingering`). 사용자가 다시 시도하면 되므로 막지 않음.
4. (참고) `bootRun` 힙 상한 4GB는 Gradle 작업 설정이라 `-PlabHeap`으로 바뀐다. 설정 키가 아니므로 config-keys.md 대상은 아니고 절차서·ADR-010에 적혀 있음.

### 재검토 판정
수정 필요(R2': config-keys.md에 `autotrade.lab.live.min-free-gb` 행, ADR-005 `/api/live` 행에 `View`의 `experimentId`·`restoring`과 디스크 부족·되살리는 중 409 사유). 코드 쪽 앞 항목(R1·R3·R4·R5·R6①)은 해소됐다. B7은 risk-ops 재검토가 따로 필요하고, D-076대로 사용자 머지 대상이다.
