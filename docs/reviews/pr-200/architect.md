# architect 검토: 장중 순위 선정 INTRADAY_AT (D-127, ADR-007 2.1 개정, ADR-012 5.2)
- 대상: PR #200 / 브랜치 `claude/intraday-at-selection-bs1xvu` / 커밋 `d867bcb`(코드 기준 `a902fb5`, 그 뒤는 strategy-researcher 원문 커밋뿐) / `git diff origin/main...HEAD` 42개 파일 +1797 −64
- 점검표: B
- 직접 실행한 것
  - 커밋 `d867bcb`를 따로 꺼낸 깨끗한 worktree에서 `./gradlew --init-script <mirror> clean build` → **BUILD SUCCESSFUL**(161 tasks). 테스트 결과: autotrade-selection 57, autotrade-sim 399, autotrade-architecture-test 46, autotrade-app-lab 256, 실패·건너뜀 0.
    - 공용 작업 폴더에는 이 PR에 커밋되지 않은 수정(SelectionProfile, IntradayTracker, 시험, 문서, 새 `selections/kr-intraday-open-all/`)이 있어서, 그 폴더에서 돌린 첫 빌드는 판정 근거로 쓰지 않았다. 판정은 커밋된 내용만 본다.
  - PostgreSQL 16(로컬)에 빈 DB를 만들고 V1→V2→V3를 순서대로 적용한 뒤 `scripts/ci/schema-smoke.sql` 실행 → 통과. V3 관련 NOTICE 4개(`intraday-at selection accepted`, `swing intraday-at rejected`, `intraday-at without at rejected`, `at on pre-open rejected`)를 확인했다. V2의 자동 이름 제약 `selection_profile_check`·`selection_profile_refresh_check`를 DROP하는 것이 PG16에서 맞게 동작한다.
  - 시험 클래스를 임시로 만들어(worktree 안에서만, 커밋 안 함, 실행 뒤 지움) IntradayTracker에 아래 F1·F2 시나리오를 넣어 봤다. 결과는 각 항목에 적었다.
  - GitHub check-run 조회: 커밋 `a902fb5`·`d867bcb`에 아직 결과 없음(CI 결과는 확인 못 함. 로컬 build와 스키마 smoke로 대신 확인함).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 깨끗한 worktree(`d867bcb`)에서 `clean build` 성공, 위 테스트 수. 새 시험 `SessionSelectionCalculatorTest`(4), `IntradayTrackerTest`(4), `LiveIntradaySessionTest`(4), `RecordingSelectionTest`(5, 장중 1건 포함), `SelectionCriteriaTest`·`SelectionProfileTest` 통과 |
| B2 | 통과 | 순수 모듈 `autotrade-selection`에 새 의존 없음(`SessionSelectionCalculator`·`SessionStats`·`SessionSelectionResult`는 java.* 와 같은 모듈만 import). sim은 domain·marketdata·selection만 씀. ArchUnit(`ArchitectureTest`, `ModuleOwnershipTest`) 통과. 보호 경로(`scripts/ci/protected-paths.txt`) 해당 파일 없음 |
| B3 | **실패** | ADR-007 2.1·2.5 표(`at` 키, refresh 규칙 개정), ADR-012 5.2·배치표, D-127, selection-catalog, runbook은 갱신했다. 하지만 **REST 계약 `GET /api/live/status`의 응답이 바뀌었는데 ADR-005 API 표(docs/adr/ADR-005-ui-dashboard.md:117)에 반영이 없다**: `LiveSession.Status`에 `intraday` 목록(`IntradayStatus{selection, selectionHash, at, state AWAITING·RANKED, candidates, blacklisted, selectedCount, activeFrom, resultHash}`)이 더해졌고(LiveSession.java Status 레코드), 이것이 `View.session`으로 그대로 직렬화된다(LiveLabService.java:335). runbook(stage-6-live-session.md:62)이 이미 `session.intraday`를 운영 확인 항목으로 쓰고 있다 → F3 |
| B4 | 수정 필요(F1·F2) | 시스템 시계·난수 없음. 순회는 TreeMap/TreeSet(해시순·코드순), `contentHash`는 정렬 JSON(`CanonicalJson`이 키를 TreeMap으로 정렬). 골든 테스트 그대로 통과, 선정 없는 등록은 바이트로 같음(`LiveIntradaySessionTest` plain 비교). 실시간 계좌 ↔ 리플레이 확인 동일(같은 날 재기동 포함)은 시험으로 확인. 다만 IntradayTracker가 (1) 날을 넘긴 재기동 공백을 누락으로 보지 않아 빠진 장 초반 체결로 순위를 매기고(F1), (2) 거래일이 거꾸로 가는 이벤트 뒤에 이미 매긴 순위를 다시 매겨 덮어쓴다(F2). (2)는 실시간 계좌가 쓴 순위와 리플레이 일정이 달라지는 경로다 |
| B5 | 통과 | 장중 지표는 `BigDecimal`(중간 `DECIMAL128`, 값만 마지막에 `DECIMAL64`, SessionSelectionCalculator `value`). 거래대금은 원가격 × 수량 `BigDecimal`, 수량 합은 `Math.addExact`(SessionStats.plus). double 없음 |
| B6 | 통과(보강 권고 R3) | 새 동작마다 시험이 있음: 손 계산(`SessionSelectionCalculatorTest#손_계산대로_순위를_매기고_자른다`), 늦은 체결·장전·boards 밖·누락·blacklist(`IntradayTrackerTest#ranksAtFirstEventAtOrAfterAt...`), 순위 시각 전 재기동(`LiveIntradaySessionTest#restartBeforeAtMarksEveryBoardSymbolAsGap`), at에 이르지 않는 날(`#dayThatNeverReachesAtBuysNothing`), 입력 규칙(`#intradayOnlyRegistrationRejectsSelectionInput`, `RecordingSelectionTest`). 지우거나 끈 시험 없음(`LiveSelectionSessionTest`는 Status 생성자 인자만 바뀜). 장전+장중 섞인 등록의 끝단 시험은 없다(R3) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·게이트웨이·보호 경로 변경 없음. 바뀐 곳은 실험실(`autotrade-sim`, `autotrade-selection`, app-lab DB 이전, UI 문구). 신규 진입 막기만 더하고 보호 주문(청산)은 그대로 통과(RunStepper.java:144 `isProtective`). blacklist는 후보에서 빼고(IntradayTracker `SelectionCandidates.of`) 주문 직전 리스크 체인 검사는 그대로다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(추가 줄에서 key/secret/token/password/account 패턴 검색 0건). 로그는 선정 ID·날짜·종목 수·순번만 남김(RecordingExperiment `intradayResults`) |

## 점검표 밖 발견

### 수정 필요

**F1. 날을 넘긴 재기동 공백이 `SESSION_GAP`이 되지 않아, 장 초반 체결이 빠진 채 순위를 매긴다** (IntradayTracker.java:134-136)
- 누락 표시 조건이 `g.gapStart()`의 **현지 시각만** `at`과 비교한다: `g.gapStart().atZone(zone).toLocalTime().isBefore(at)`.
- 실험실을 밤에 끄고 다음 날 정규장이 시작된 뒤(예: 09:10) 다시 띄우면, 입력 기록이 새 날 첫 줄 앞에 재기동 공백을 남긴다. 그 공백은 `gapStart = 앞 날 마지막 수신(예: 15:35)`, `gapEnd = 오늘 09:10`이다(DayWriter.carryRestartFrom, JournalWriter.java:75-76, D-065·QA PR #91 R1).
- 이 공백은 `gapStart` 현지 시각 15:35가 09:30보다 늦어서 표시되지 않는다. 결과로 09:00~09:10 체결이 빠진 거래대금·수익률로 순위를 매기고, 그 종목이 선정된다.
- 재현(임시 시험, 커밋 안 함): 공백(005930, gapStart D0 15:35, gapEnd D1 09:10, `RECORDER_RESTART`) → 체결 09:11·09:20 → 09:31 이벤트. 결과는 005930 `reasons=[]`, `passed=true`였다(기대값 `SESSION_GAP`).
- D-127 결정 5("`at` 전에 시작한 시세 누락이 있던 종목은 SESSION_GAP")와 runbook 2장 7번("다시 띄우면 그 공백이 boards 전 종목에 누락으로 붙어 후보 0")이 이 경우 사실이 아니다.
- 고칠 방향: 시각(Instant)으로 비교한다. 예: `g.gapStart().isBefore(today의 at Instant)`. 또는 `gapStart`의 거래일이 오늘보다 앞이면 표시한다. 이 경우를 `IntradayTrackerTest`와 `LiveIntradaySessionTest`(날을 넘긴 재기동)에 시험으로 더한다.

**F2. 거래일이 거꾸로 가는 이벤트 하나로 이미 매긴 그날 순위가 다시 매겨져 덮어써진다** (IntradayTracker.java:105-108, 151)
- `onEvent`는 거래일이 `today`와 **다르기만 하면** `windows`를 비우고 새로 만든다. `rank`는 `days.get(h).put(d, …)`로 덮어쓴다.
- 그래서 D 순위 뒤에 거래소 시각이 D−1인 이벤트가 하나 오면 이런 일이 생긴다. D−1 창이 새로 생기고(at 이상이면 D−1을 다시 매김), 이어 오는 D 이벤트에서 D 창이 다시 빈 상태로 생긴다. 그러면 at 이상인 첫 D 이벤트에서 **빈 요약으로 D를 다시 매기고**, 결과 해시와 `activeFrom`이 바뀐다.
- 재현(임시 시험): D 09:00·09:20 체결 → 09:31(순위, activeFrom 3) → D−1 15:00 이벤트 → D 09:40 이벤트. 결과 `ranked=2`, 같은 날 해시가 바뀜, `activeFrom 3 → 5`.
- 영향: 실시간 계좌는 처음 순위로 그날 앞부분을 돌렸다. 리플레이 실행기는 일정을 다 읽은 뒤의(덮어쓴) 값을 그날 처음부터 쓴다. 그래서 상세 보고서가 달라지고, 녹화 실험 결과 파일도 나중 값이 된다. D-127 결정 4("그 뒤에 늦게 온 체결은 넣지 않는다", 순위 고정)와 7(실시간·리플레이 동일)이 깨지는 경로다.
- 도달 가능성: 입력 기록은 **수신 시각** 날짜로 파일을 나누고(JournalWriter.java:24) 추적기는 **거래소 시각** 날짜를 쓴다. 그래서 증권사 거래소 시각 오류나 자정 근처 이벤트로 날짜가 거꾸로 가는 일을 막는 장치가 이 경로에 없다 `[추론]`.
- 고칠 방향: `today`보다 앞선 거래일 이벤트는 추적기가 무시한다. 그리고 (선정, 날짜)를 한 번 매기면 다시 매기지 않는다(`days.get(h).containsKey(d)`면 건너뜀). 시험을 하나 더한다.

**F3. REST 계약 변경을 ADR-005에 반영** (B3)
- `GET /api/live/status`의 `session.intraday` 필드(모양, `state` 값 `AWAITING`·`RANKED`, `resultHash`가 순위 전 null, `activeFrom` 0의 뜻)를 ADR-005 117줄 표에 더한다. 같은 표에는 `session.selections`(D-124)가 적혀 있다.
- 같은 줄에 `POST /api/live/selections/prepare`가 장중 순위 선정만 있는 세션에서 409라는 점도 적는다. 이것은 지금 코드(`hasSelections()`가 장전 선정만 셈) 그대로이며, 문서에 남기려는 것이다.

**F4. runbook·D-127의 "처음 열기" 설명이 코드와 다르다** (stage-6-live-session.md:61, D-127 결정 5·위험)
- runbook은 "`at` 전에 세션을 **열거나** 다시 띄우면 공백이 붙어 후보 0"이라고 쓴다. 하지만 입력 기록이 없는 세션을 정규장 중에 처음 열면 재기동 공백이 생기지 않는다. 그러면 장 초반이 빠진 요약으로 그대로 순위를 매긴다 `[추론]`(JournalWriter는 `resumedAfter`가 있을 때만 재기동 공백을 쓴다, JournalWriter.java:75).
- 고칠 방향: 문서를 사실대로 고친다. 또는 코드로 막는다(그날 첫 이벤트가 정규장 시작보다 늦으면 전 종목 `SESSION_GAP`). strategy-researcher 원문(같은 PR)의 M2와 겹치니 한 번에 처리해도 된다.

### 권고(판정에 넣지 않음)
- **R1** `SelectionDay.allowsEntry(Symbol)`(SelectionDay.java:73)은 `Long.MAX_VALUE`로 `activeFrom` 검사를 늘 통과한다. 운영 코드에서 쓰는 곳은 없고 시험만 쓴다. 장중 판정에 잘못 쓰면 순위 시각 전 진입이 열린다. 지우거나, `activeFrom > 0`인 날에는 예외를 던지게 한다.
- **R2** `SelectionProfile.parseAt`(SelectionProfile.java:146-147)에 시장 분기와 장 시간이 하드코딩돼 있다. 시장 차이는 `MarketRules`로만 다룬다는 원칙(지침 4장 9번)에 비춰, 최소한 상수 표 한 곳으로 모으고 MarketRules 정규장 시간과 맞는지 시험으로 묶는다. 미국 상한 16:00은 정규장 끝이라 그 시각에 순위를 매기면 진입할 시간이 없다. 15:59 이하로 하거나 문서에 적는다. 특수일 개장 지연(예: 수능일 10:00 개장)에 `at` 09:30이면 체결 0건으로 순위를 매겨 후보 0이 된다. 보수적인 동작이지만 D-127 위험 절에 적는다(SelectionProfile 주석 "실행 때 정규장 판정으로 다룬다"는 이 결과를 말하지 않는다).
- **R3** 장전 선정(`PRE_OPEN_DAILY`)과 장중 순위 선정이 한 등록에 함께 있는 경우의 끝단 시험(실시간 + 리플레이 확인 동일, 녹화 실험 `inputs.yaml`의 `selection-input`·`intraday-selections` 둘 다)이 없다. 코드 경로(LiveSelectionDays가 files·intraday를 함께 받음, SelectionSchedule.withIntraday)는 따로 분기되므로 시험 하나를 더하길 권한다.
- **R4** 코드 안에 패키지 전체 이름을 직접 쓴 곳이 있다: CombinationExperiment.java:151, LiveSession `IntradayStatus.of`, RecordingExperiment(`java.util.Set`, `com.autotrade.sim.run.RunSelectionDay`). import로 정리한다.
- **R5** `SimulationExperimentRunner.intradayUniverse`(SimulationExperimentRunner.java:78)는 생성 뒤에 설정하는 가변 필드다. 지금은 실행 전에 한 번만 부르고 한 스레드에서 쓰므로 문제가 없다. 다만 실행기를 여러 번 쓰는 호출자가 늘면 생성자 인자나 불변 복사본(`withIntradayUniverse`)이 안전하다.
- **R6** 스트림 경로(`runLanes`, SimulationExperimentRunner.java:615)는 추적기 하나를 레인 0 창의 이벤트로만 채우고 모든 레인이 나눠 쓴다. 지금은 녹화 판정이 장중 계좌를 거절해서(LiveJudgement) 문제가 생기지 않는다. 후속으로 녹화 판정을 지원할 때 지연 레인 등 이벤트가 다른 레인은 레인마다 추적기를 따로 둬야 한다(D-127 후속에 적힌 대로).

## 판정
수정 필요(F1, F2, F3, F4)

## 재검토 2026-10-20
- 대상: PR #200 / 커밋 `e7a5a46`(직전 검토 `d867bcb` 이후 `762d718` strategy-researcher M1·M2·R1·R2 반영, `e7a5a46` architect F1~F4 반영) / `git diff origin/main...HEAD` 전체를 다시 보고, `git diff d867bcb e7a5a46`(17개 파일)는 줄 단위로 봤다
- 점검표: B
- 직접 실행한 것
  - `e7a5a46`를 따로 꺼낸 깨끗한 worktree에서 `./gradlew --init-script <mirror> clean build` → **BUILD SUCCESSFUL**(161 tasks). selection 57, sim 402(+3: `IntradayTrackerTest#dayFirstObservedAfterOpenGraceMarksEveryBoardSymbolAsGap`, `#gapOverlappingWindowMarksSymbolEvenIfItEndsAfterClose`, `#eventOfEarlierTradingDayDoesNotReopenRankedDay`), architecture-test 46, app-lab 256, 실패·건너뜀 0
  - SQL·스키마 smoke는 이번 수정에서 바뀌지 않았다(직전 검토의 PG16 V1→V3 + smoke 통과가 그대로 유효)
  - 임시 시험(worktree 안, 커밋 안 함, 실행 뒤 지움)으로 직전 F1 시나리오를 다시 돌렸다
    - 날을 넘긴 재기동, 09:10 재개(gapStart D0 15:35, gapEnd D1 09:10) → 두 종목 모두 `SESSION_GAP`(직전에는 통과·선정. 고쳐짐)
    - 개장 전 08:50 재개 → 표시 안 함, 정상 선정(맞음)
    - `kr-intraday-open-all@1`이 `SelectionProfile`·`SelectionCriteria` 검증을 통과해 읽힘

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `clean build` |
| B2 | 통과 | 새 의존은 sim 안의 `VirtualExchange.AUCTION_PRINT_GRACE`(같은 모듈)뿐. ArchUnit 통과 |
| B3 | 통과 | ADR-005 `GET /api/live/status`에 `session.intraday` 모양·순위 전 값(빈 목록·0·null)을 적었고, `POST /api/live/selections/prepare`의 409 조건에 "장전 선정 조합 계좌 없음(장중만 포함)"을 더했다. 코드와 맞다: `LiveSelectionPrep.hasSelections`(LiveSelectionPrep.java:112-115)가 `daily().isPresent()`까지 보고, `LiveLabService.java:227`이 이것으로 준비 스레드를 정한다. 시험은 `LiveIntradaySessionTest` 133줄. `at` 범위 변경(KRX 09:01~14:30, 미국 09:31~15:00)은 SelectionProfile·ADR-007·D-127·카탈로그가 같다 |
| B4 | 통과 | F1·F2 해소(아래). 판정은 이벤트만의 순수 함수라 실시간·리플레이가 같은 값을 낸다(개장 + 60초 판정도 이벤트 거래소 시각만 씀). 골든 테스트 통과 |
| B5 | 통과 | 바뀐 금액 계산 없음 |
| B6 | 통과 | 위 시험 3개 추가, `SelectionProfileTest`의 범위 경계 갱신(14:30 허용, 14:31·15:20 거부). 지우거나 끈 시험 없음 |
| B7 | 해당 없음 | 실전 경로·보호 경로 변경 없음(17개 파일 모두 selection·sim·문서·선정 프로필) |
| B8 | 통과 | 비밀값 없음 |

### 직전 지적 처리
- **F1 해소**: `overlapsWindow`(IntradayTracker.java:156)가 `gapStart`를 오늘 `at`의 Instant와 비교한다. 그래서 전날 시작한 공백도 정규장 안에서 끝나면 표시된다. 장중에 처음 연 날은 개장 + 60초 판정(117줄)으로 전 종목이 표시된다. 위 임시 시험으로 확인했다.
- **F2 해소**: 지난 거래일 이벤트는 무시하고(108줄), (선정, 거래일)은 `putIfAbsent`로 한 번만 정한다(174줄). 시험 `#eventOfEarlierTradingDayDoesNotReopenRankedDay`는 순위 결과·`activeFrom` 20이 그대로임을 확인한다. `RunStepper`가 거꾸로 가는 이벤트에서 날을 다시 여는 것은 기존 동작이다. 이때도 추적기가 같은 값을 돌려주므로 실시간과 리플레이가 같다.
- **F3 해소**: 위 B3.
- **F4 해소**: runbook 2장 7번이 "처음 받는 이벤트가 09:01 뒤이거나 09:00 ~ `at` 사이 재기동이면 후보 0"으로 고쳐져 코드(117줄·`overlapsWindow`)와 맞다. D-127 결정 5에도 같은 내용과 개장 지연일(후보 0) 설명이 들어갔다.

### 남은 것(권고, 판정에 넣지 않음)
- 직전 R1(인자 하나짜리 `allowsEntry`), R3(장전 + 장중이 섞인 등록의 끝단 시험), R4(패키지 전체 이름 import), R5(실행기 가변 필드), R6(스트림 레인끼리 추적기 공유, 녹화 판정 지원 때 처리)은 그대로 남았다. R2는 `at` 상한 조정과 개장 지연일 문서화로 대부분 처리됐다. 시장 분기 하드코딩(SelectionProfile.java:146-148)만 남았다.
- 새 관찰 n1: 재기동 공백이 **정확히 09:00:00**에 끝나도(`gapEnd`가 정규장 첫 순간) `isRegularSession(gapEnd)`가 참이라 `SESSION_GAP`이 된다. 임시 시험으로 확인했다. 실제로 잃은 체결이 없을 수 있는 경계라 표본만 빠지는 보수적 동작이다. 안전 쪽이라 판정과는 상관없고, 필요하면 "끝이 개장 + 유예 안이면 제외"로 다듬는다.

## 판정
승인
