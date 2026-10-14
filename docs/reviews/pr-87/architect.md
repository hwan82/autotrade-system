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
