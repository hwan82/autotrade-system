# architect 검토: ADR-016 G0 시세 수신 간격 측정 명령 feedGaps(D-147)
- 대상: PR #256 / 커밋 153cb169 (브랜치 claude/project-thread-59jk1g) / `git diff origin/main...HEAD` 전체(8개 파일, +1320 −1. 같은 브랜치의 b145526d는 strategy-researcher 검토 원문만 더함)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL(3분 56초, 138 tasks). `FeedGapStatsTest` 13건 통과(실패·건너뜀 0, `build/test-results/test/TEST-com.autotrade.app.gateway.FeedGapStatsTest.xml`). ArchUnit(`autotrade-architecture-test`)·`verifyPureDependencies`·`processIsolation` 포함 통과.
  - 확인용 임시 시험(커밋 안 함, 실행 뒤 지움) `FeedGapStatsArchitectProbeTest` 2건을 `:autotrade-app-gateway:test --tests`로 돌림. 결과는 아래 R1.
  - `scripts/ci/protected-paths.txt` 패턴에 diff 파일 이름을 맞춰 봄 → 걸리는 파일 0개.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, BUILD SUCCESSFUL. `FeedGapStatsTest` 13/13 |
| B2 | 통과 | 새 모듈·새 의존 없음. `FeedGapStats`는 이미 의존하던 `autotrade-domain`·`autotrade-marketdata`(calendar·recording·rules·symbols)만 씀(FeedGapStats.java:3-18). `autotrade-app-gateway/build.gradle.kts`의 `processIsolation`·dependencies 변경 없음, Gradle 작업 `feedGaps`만 추가(build.gradle.kts:45-57, 작업 등록 48). 시스템 시계는 진입점 `FeedGapStatsMain.java:17`에서만 만듦(`ReplayCheckMain`과 같은 방식, ArchUnit `NO_SYSTEM_TIME` 통과). ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST API 변경 없음. 새 것은 점검 명령(CLI 인자·종료 코드)과 Gradle 작업뿐이며 D-147, 사용 절차 `docs/runbooks/feed-gap-stats.md`, ADR-016 5장 G0 행(ADR-016-real-engine-market-feed.md:204), 결정 목록 README 행으로 문서화됨. 녹화 형식·`ReplayCheckGate`·`ReplayCheck` 상수는 읽기만 함 |
| B4 | 통과 | 시뮬레이션 코드가 아님. `FeedGapStats`는 현재 시각을 쓰지 않고(Clock은 정지 관문에만 넘김, FeedGapStats.java:141) 순서 있는 `TreeMap`·`LinkedHashMap`만 써 같은 녹화면 같은 보고서(`보고서는_같은_녹화면_같고_표와_후보표와_한계_절을_낸다`). 기존 골든 테스트는 전체 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액을 다루지 않음(간격은 `long` 마이크로초). `BigDecimal`은 시험 체결 가격에만 |
| B6 | 통과 | 새 동작에 시험 13건(단계 경계, 단계별 분리·초과 경계, 시작 → 첫 이벤트 제외, 단계 내내 없음, 끊김·재기동 제외, VI, 소스·날짜별, 히스토그램, 결정성, 명령 범위·파일, 장 시간 보호, 실행 중 정지, 입력 오류). 지우거나 `@Disabled`한 시험 없음. 다만 R1의 경우(끊김·재기동이 단계 하나를 통째로 덮음)는 시험이 없음 |
| B7 | 해당 없음 | 보호 경로(`scripts/ci/protected-paths.txt`)에 걸리는 파일 0개. 바뀐 운영 코드는 `autotrade-app-gateway`의 새 클래스 2개(녹화 파일 읽기 전용 점검 명령)이고 게이트웨이 실행 경로(`GatewayMain`)·실전 엔진·주문·RealTradingGuard·블랙리스트를 건드리지 않음. 정지 요청 파일·장 시간 보호 관문(D-085)을 그대로 씀(FeedGapStats.java:141-150, 시험 2건) |
| B8 | 통과 | diff에 키·계좌·토큰 값 없음(`git diff`에서 appkey·secret·token·password·account 검색 시 기존 D-145 문구 맥락 행만). 보고서는 종목 코드·이름·시각·통계만 냄(FeedGapStats.java:726-846) |

## 점검표 밖 발견

### R1(수정 필요) 끊김·녹화 재기동이 한 매매 단계를 통째로 덮으면 그 단계를 "단계 내내 없음"(조용함)으로 분포에 넣는다
- [사실] `Chain.finish`의 "단계 내내 없음" 조건(FeedGapStats.java:458-461)은 `eventsIn[w] == 0`, `firstT < start`, `!resetIn[w]`, `!day.globalResetIn[w]`, 녹화가 단계를 덮음만 본다. `resetIn[w]`·`globalResetIn[w]`는 끊김 표시·재기동 첫 줄의 **수신 시각이 속한 단계**에만 켜지고(FeedGapStats.java:337-344, 400-409), `DataGap`의 `gapStart`·`gapEnd`(DataGap.java:21-22)는 쓰지 않는다. 그래서 끊김이 단계 w 앞에서 시작해 w 뒤에 끝나면(끊김 표시나 재기동 첫 줄이 w 다음 단계 또는 장 뒤에 찍힘) w는 "끊김 없이 조용했던 단계"로 셈된다.
- [사실] 임시 시험으로 재현(커밋 안 함):
  - 녹화 재기동 15:18:20 → 15:35(종가 단일가 15:20~15:30 전체가 꺼진 동안): `silent[종가 단일가]=1`, `main[종가 단일가]`에 600초 간격 1건, 소스 단위도 `silent[종가 단일가]=1`.
  - 같은 구간 `GAP(DISCONNECTED)`(gapStart 15:18:30, 줄 수신 15:35): 종목 `silent[종가 단일가]=1`, 600초 간격 1건.
- 영향: D-147 6번("끊김 표시·녹화 재기동 경계를 지나는 간격은 장애이지 조용함이 아님, 분포에서 뺌")과 보고서 "측정 방법과 한계" 절(FeedGapStats.java:837-838)이 말하는 것과 다른 값을 낸다. 단계가 짧은 시가(30분)·종가(10분) 단일가에서 장 막판 게이트웨이 재기동·재연결 한 번이면 그 날 녹화의 모든 종목·소스에 단계 길이 간격이 들어가, 이 명령이 근거로 내려는 risk-ops PR #245 N3(단일가 짧은 기준)·소스 단위 단일가 행의 최대·`>Ns`를 장애 쪽으로 키운다. 접속 매매 전체(6시간 20분)를 덮는 끊김이면 T_quiet 후보표에도 들어간다.
- 고칠 방향(제안): 종목·소스 이어 보기가 마지막 이벤트 뒤로 끊김·재기동을 겪었으면(`valid == false`) 그 뒤 이벤트가 없는 단계를 "단계 내내 없음"으로 세지 않고 뺀 간격(또는 따로 "끊김으로 모름")으로 센다. 또는 `DataGap.gapStart`~`gapEnd`(재기동은 `gapStart`가 앞 구간 끝)와 단계가 겹치면 그 단계의 reset 표시를 켠다. 위 두 경우(재기동·끊김 표시가 단계를 통째로 덮음)를 시험으로 고정한다.

### N1(참고, 머지 막지 않음) 끊김 뒤 VI 상태를 잃는다
- [사실] `Chain.reset`이 `inVi = false`로 둔다(FeedGapStats.java:405). VI 중에 재연결(GAP)되고 VI가 계속되면 그 뒤 간격이 VI 칸이 아니라 본 분포로 들어간다. [추론] 빈도는 낮고 영향은 본 분포를 약간 길게 하는 쪽. 바꿀 필요가 있으면 R1과 같이 본다.

### N2(참고) 녹화일 폴더 인자에 상위 폴더가 없으면 오류 메시지가 `null`
- [사실] `resolve`가 `dir.getParent().getFileName()`을 바로 부른다(FeedGapStats.java:204). `feedGaps 2026-10-08` 같은 날짜가 아닌 상대 폴더 이름 하나만 주면 NPE → `catch RuntimeException`으로 종료 코드 2와 "수신 간격 측정 실패: null". 결과는 안전(입력 오류)하지만 메시지가 원인을 말하지 않는다.

### N3(참고) 끊김 표시만 있는 종목도 종목·일 수에 들어간다
- [사실] `DataGap` 줄도 `symbols.computeIfAbsent`로 종목 이어 보기를 만들고(FeedGapStats.java:355) 하루 끝 `dayDone`에서 `symbolDayCount`를 올린다(FeedGapStats.java:556-559). 그날 이벤트가 하나도 없고 끊김 표시만 있는 종목은 종목별 표에 간격 0건 행으로 나오고 T_quiet 후보표 "종목·일당 평균"의 분모를 키운다. [추론] 재기동 표시는 앞 구간에 나온 종목에만 남으므로(DayWriter.java:40) 실제로는 드물다.

### 확인한 것(문제 없음)
- 단계 경계를 `KrxMarketRules.sessionPhase`로 1초씩 훑어 찾아 단계 판정 원본을 하나로 둠(FeedGapStats.java:251-275), 시험으로 08:30/09:00/15:20/15:30 확인.
- 초과 횟수는 "넘게"(`>`)로 정확히, 백분위는 칸 위 경계로 올리고 최대를 넘지 않음(FeedGapStats.java:631-687).
- 메모리는 종목·소스 수 × 단계 × 781칸으로 녹화 기간과 무관. 녹화는 `RecordedDayStream.read`로 줄 단위 한 번 읽음.
- 장 시간 보호·정지 요청 관문을 시작 전, 녹화일마다, `ReplayCheck.CHECK_EVERY`(50,000)줄마다 확인하고 멈추면 보고서를 쓰지 않음(종료 코드 4).
- Gradle `feedGaps`는 `replayCheck`와 같은 `allowDuringSession` 속성을 같은 방식으로 넘김(build.gradle.kts:33, 41, 55). 사용 절차 문서의 `-PallowDuringSession`과 일치.

## 판정
수정 필요(R1: 끊김·녹화 재기동이 매매 단계 하나를 통째로 덮으면 그 단계를 "단계 내내 없음"으로 분포에 넣음 — D-147 6번과 어긋남, 고친 뒤 재현 시험 추가)
