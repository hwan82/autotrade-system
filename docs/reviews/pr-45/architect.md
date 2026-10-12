# architect 검토: 선정 계산과 조건 카탈로그(배치표 2번, D-057): selection.calc, (가)(나) 선정 프로필, 중기 검증·일봉 무작위 대조군
- 대상: PR #45 / 브랜치 `claude/project-thread-5jfdgg` / head `42e74ae` / main(`6209c77`, PR #44 머지 후) 대비 `git diff origin/main...HEAD` 전체(36개 파일, +2870 −16). merge-base = `origin/main`이고 `git merge-tree`로 main과 충돌 없음을 확인했다.
- 점검표: B
- 직접 실행한 것:
  - 공유 작업 트리 `./gradlew build`: BUILD SUCCESSFUL(대부분 캐시). 캐시에 기대지 않으려고 `42e74ae`를 별도 worktree로 고정해 `./gradlew build --no-daemon --rerun-tasks`: **BUILD SUCCESSFUL in 2m 5s, 117 tasks 모두 실행**. 테스트 결과 XML 집계 702개, 실패·오류·건너뜀 0(autotrade-selection 26, autotrade-sim 92, autotrade-strategy 30, autotrade-architecture-test 29 포함). `verifyPureDependencies`·`verifyProcessIsolation` 통과.
  - 재현 탐침 1(F1): worktree에 임시 테스트를 넣어 `FixtureData`(일봉이 2026-03-13에서 끝남)로 `kr-vol-surge-quiet`를 T = 2026-04-20에 계산. 결과: 예외 없이 `selected=0`, 모든 종목 `GATE_01_NO_DATA`·`GATE_02_NO_DATA`, 그런데 `inputs.barsLastTradingDate = 2026-04-17`(데이터에 없는 날). 탐침 파일은 실행 뒤 지웠다(worktree `git status` 깨끗).
  - 재현 탐침 2(F2): `SelectionCalculator.volumeRatio`와 같은 계산(거래량 ÷ 수정 계수를 `DECIMAL64`로 나누고 더함)을 최근 구간 거래량 = 앞 구간 × 3으로 90개 조합에 돌림. 수정 계수 1·0.5는 정확히 3, 0.7·0.3333333333·0.9876543210123에서는 **21/90건이 2.999999999999…로 GE 3.0에서 탈락**(예: f=0.7, v=123456789, w=60 → `2.999999999999991`).
  - 실전 경로 diff 확인: `autotrade-risk`, `autotrade-app-engine`, `autotrade-broker-*`, `autotrade-domain`, `autotrade-config`, `autotrade-control`, 보호 테스트(ArchitectureTest, ModuleOwnershipTest, Guard·GuardedOrderGateway, 장애 테스트) 변경 0건. `ManagedStrategy`·`TradingRules`·`CatalogStrategyFactory`는 엔진 클래스패스의 `autotrade-strategy`에 있지만 `autotrade-app-engine` 운영 코드에서 참조 0건(`grep`, 사용처는 sim 테스트·`StudyMain`뿐).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. `--rerun-tasks` 격리 빌드 성공, 702개 통과 |
| B2 | 통과 | `autotrade-selection/build.gradle.kts:12`의 `api(project(":autotrade-marketdata"))`는 ADR-007 2.1 "의존: domain, marketdata, strategy"(ADR-007:67)와 같다. `SelectionData`가 `DailyBar`·`InstrumentPeriod`·`FinancialFiling`을 공개 시그니처에 쓰므로 `api`가 맞다. 순수 모듈 검사(`verifyPureDependencies`) 통과, ArchUnit `PURE`에 `com.autotrade.selection..` 있음(`ArchitectureTest.java:63`). 게이트웨이·감시 `forbiddenProjects`에 `:autotrade-selection` 유지(`autotrade-app-gateway/build.gradle.kts:12`, `autotrade-app-watchdog/build.gradle.kts:12`). 실전 엔진은 selection 모듈 의존이 없어(`autotrade-app-engine/build.gradle.kts:18-27`) `selection.calc` 참조가 Gradle에서 막힌다 |
| B3 | **실패** | 선정 조건 문법·지표·`exit.max-hold-days`·`POSITION` 검증은 ADR-007 2.1·2.3 범위 안이고 D-057·카탈로그·`strategies/README.md`에 문서화됐다. 그러나 이 PR이 지금 정의하는 결과 계약 `SelectionResult.inputs`가 ADR-007 2.6과 manifest 계약에 어긋난다: `SelectionCalculator.java:126-128`이 `bars.last_trading_date`와 `shares.as_of_date`를 스냅샷 기준일이 아니라 **항상 `cutoff`(= `previousTradingDay(apply_from)`)로 쓴다**. `SnapshotManifest.java:17-18`은 "선정 결과의 입력 데이터 기준일(inputs)이 이 값(`coverage`)을 옮긴다"고 정했고, ADR-007 2.6 판정 5 `STALE_INPUT`(ADR-007:189)은 바로 이 두 값이 `previousTradingDay(apply_from)`보다 이른지를 본다. 지금 구현이면 그 판정은 구조적으로 절대 걸리지 않는다 → F1 |
| B4 | 통과 | 시스템 시계 0건. `RandomDailyEntry`는 `SplittableRandom(runSeed)`만 쓰고 (거래일, 종목) 첫 체결마다 정확히 한 번 추첨(`RandomDailyEntry.java:41-45`, `observe`는 보유 여부와 무관하게 매 이벤트 호출됨 `ManagedStrategy.java:115`). 결과에 영향 주는 순회는 `TreeSet` 유니버스·`TreeMap` 평가·명시 정렬(점수 → 종목 코드, `SelectionCalculator.java:108-114`). `HashMap`/`HashSet`은 조회 전용. 기존 골든(`StrategyStudyTest`, sim 골든) 그대로 통과, `kr-ma-trend@1`·`kr-bh-baseline@1` 파일 불변(해시 불변). 결정론 테스트 `SelectionCalculatorTest#같은_입력이면_결과와_내용_해시가_같다`, `CalculatedSelectionSourceTest#매일_갱신은_거래일마다_계산하고_같은_입력이면_같은_결과_해시다` 통과 |
| B5 | 통과 | 금액·비율 모두 `BigDecimal`(`MathContext.DECIMAL64`), 시가총액은 `close × shares` 정확 곱(`SelectionCalculator.java:281`), 값 표기 `toPlainString`. `double` 0건. 단, 나눗셈 반올림이 경계 포함 규칙을 깨는 경우가 있다 → F2(권고) |
| B6 | **실패** | 새 계산·문법·결과 해시·POSITION·최대 보유일·`random-daily`에 손 계산 골든과 거부 테스트가 있고 삭제·비활성 테스트는 없다(테스트 diff는 추가만, 925줄). 그러나 실제 스냅샷을 읽는 어댑터 `SnapshotSelectionData`(manifest 파일 SHA 찾기, 상장주식수 스냅샷 ID, 재무 `coverage.lastCollectedAt`, 캘린더 요약)는 **직접 테스트가 0건**이고(`calendarDigest` 정적 메서드만 `CalculatedSelectionSourceTest:112`에서 호출), 스냅샷 수록 범위가 기준 데이터일보다 짧은 경우의 테스트도 없다. 이 클래스가 생산 일괄 명령과 실험실이 공유한다고 선언된 실데이터 경로다(`SnapshotSelectionData.java:29`) → F1에 포함 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동·보호 테스트 변경 없음(위 확인). `autotrade-strategy` 변경분은 엔진 운영 코드가 쓰지 않는다. 다만 F1은 이후 실전 관련 PR(코덱·`SelectionValidity`·엔진 읽기)의 `STALE_INPUT` 판정 전제를 이 PR이 정하는 것이라, 그 PR의 risk-ops 검토에서 이 원문의 F1 처리 결과를 함께 볼 것을 권한다 |
| B8 | 통과 | diff에 키·계좌번호·토큰 값 없음(`git diff`에서 secret/token/key 패턴은 결정 로그 문구뿐). 테스트 픽스처 해시는 `"a".repeat(64)` 같은 자리표시자 |

## 점검표 밖 발견

### F1 (차단) 선정 결과 `inputs`가 실제 입력 기준일이 아니라 계산 기준일을 적는다. 스냅샷이 모자라면 조용히 빈 선정을 낸다
- 위치: `autotrade-selection/src/main/java/com/autotrade/selection/calc/SelectionCalculator.java:94-95, 125-135`, `SnapshotSelectionData.java:174-207`.
- 사실: `barsLastTradingDate`에 `cutoff`, `sharesAsOfDate`에 `cutoff`를 그대로 넣는다. 일봉 스냅샷 manifest의 `coverage.lastTradingDate`·`coverage.sharesAsOf`는 읽지도 비교하지도 않는다. `SnapshotListedShares.asOf`는 `floorEntry`라(`SnapshotListedShares.java:50-57`) 상장주식수 파일이 오래돼도 그 옛 값을 쓰면서 기준일은 `cutoff`로 적힌다.
- 실패 시나리오: (1) 수집기가 전날 돌지 않아 일봉 스냅샷이 D−2까지만 있음 → 계산은 예외 없이 거의 모든 종목 `NO_DATA`로 빈 선정(탐침 1에서 재현), 결과 파일은 `last_trading_date = D−1`로 "신선"하다고 적힘 → 이후 `SelectionValidity`의 `STALE_INPUT`이 통과시켜 실시간 모의·실전 계좌가 그날 빈(또는 상장주식수가 낡은) 선정을 `VALID`로 고정한다. 신규 진입만 영향받아 보유 보호는 그대로지만, ADR-007이 둔 "낡은 입력이면 닫힘" 장치가 무력화된다. (2) 실험실 리플레이 기간이 스냅샷 수록 범위를 넘으면 그 날들은 경고 없이 "선정 0종목"으로 돌아 조합 성과가 왜곡된다(실험 결과로는 구분 불가).
- 고칠 방향(둘 중 하나, 추천은 a):
  - a. `SelectionData`에 수록 기준일(`barsCoveredThrough()`, `sharesAsOf()`)을 더하고, `calculate`가 `coveredThrough < cutoff`(상장주식수를 쓰는 선정이면 `sharesAsOf < cutoff`도)면 예외로 실패한다. 통과하면 지금처럼 `cutoff`를 적는다(실제로 그날까지 데이터가 있음을 확인한 값이 된다). manifest `coverage`가 null인 옛 스냅샷은 실패로 본다.
  - b. 실패시키지 않고 `inputs`에 manifest 값을 그대로 적는다(ADR-007 2.6·manifest 주석 문구 그대로). 그러면 백테스트(스냅샷이 cutoff 훨씬 뒤까지 있음)에서 `last_trading_date`가 cutoff보다 늦어 "계산에 쓴 마지막 날"이라는 `Inputs` 주석과 어긋나므로, a가 낫다.
- 테스트: 임시 폴더에 manifest·CSV를 써서 `SnapshotSelectionData`로 계산하는 테스트(파일 SHA·스냅샷 ID·재무 수집 시각이 결과 `inputs`에 그대로 옮겨지는지), 수록 범위 부족·`coverage` 없음 → 실패 테스트.

### F2 (권고) 수정 계수가 1이 아닌 창에서 거래량 비율 경계값이 깨진다
- 위치: `SelectionCalculator.java:285-307`. 종목별 거래량을 `÷ adjFactor`(DECIMAL64 반올림) 한 뒤 더해, 같은 계수가 곱해진 두 합의 비가 정확히 3이어야 할 때 `2.99999999999999x`가 된다(탐침 2: 0.7·0.333…·0.98…에서 21/90). 카탈로그 3장·D-057이 적은 "GE는 경계 포함"(`docs/strategy/selection-catalog.md:31`)이 배당락 등 계수 ≠ 1 종목에서 성립하지 않는다. 골든 테스트는 계수 1과 0.5(정확히 나누어짐)만 써서 드러나지 않았다.
- 실제 데이터에서 정확히 경계에 걸릴 확률은 낮지만 문서화된 규칙이라 고치길 권한다: 중간 계산을 `MathContext.DECIMAL128`로 하고 최종 비율만 DECIMAL64로 반올림하거나, 창 안 계수가 모두 같으면 원거래량 비율을 쓴다. 계수 0.7 같은 비종결 소수 경계 테스트를 더한다. `return`·`avg-turnover`는 단일 나눗셈이라 해당 없음.

### F3 (권고) 최대 보유일 상태가 메모리에만 있고 "이벤트를 받은 날 수"로 센다
- 위치: `ManagedStrategy.java:59-64, 104, 136, 186-188`. `dayIndex`는 이 전략 인스턴스가 이벤트를 받은 날 수라, 이벤트가 하나도 없는 거래일(데이터 결측·전 종목 정지)은 세지 않아 보유가 길어지고, 재기동하면 `openedDay`가 다시 0부터 센다. 리플레이 실험실에서는 문제 없으나(이벤트가 매 거래일 옴, `openedAt`도 같은 성질), 실전 엔진이 이 코드를 쓰게 될 때(배치표 5번·7단계)는 거래 캘린더 기준 일수와 체결 기록에서 진입 거래일을 복원해야 한다. 그때 risk-ops 검토 항목으로 넘길 것.

### F4 (권고) 주간 선정을 매 거래일 다시 계산
- `CalculatedSelectionSource.select`(`CalculatedSelectionSource.java:34-37`)는 같은 `applyFrom`이어도 매일 `calculate`를 다시 부른다. 결과는 같아 결정론 문제는 없고 비용만 5배다(전 종목 × 40거래일). (선정 해시, `applyFrom`) 캐시를 권한다.

### F5 (권고) 계약 문서 정합
- `SelectionResult.Inputs.financials`가 `snapshot_id`·`sha256`·`last_collected_at` 세 칸을 정의했는데(`SelectionResult.java:237-241`) ADR-007 2.6 예시는 `"financials": null`뿐이다. D-057 ⑥에 결정은 있으니, 코덱 PR 전에 ADR-007 2.6 예시에 재무 칸 모양을 적어 두길 권한다.
- `SelectionResult`는 `selected` 종목 집합과 `evaluations`의 `passed=true` 집합이 같은지 검사하지 않는다. 코덱·`SelectionValidity`의 `MALFORMED` 조건에 넣을 것(코덱 PR 몫).
- ADR-007 2.7의 `ENGINE_DOES_NOT_COMPUTE_SELECTIONS` ArchUnit 규칙은 아직 없다. 지금은 엔진에 selection 모듈 의존이 없어 Gradle이 막으므로 차단 아님. 엔진에 selection 의존을 더하는 PR에서 규칙을 같이 넣어야 한다.

### 확인한 것(문제 없음)
- 재무 시점 규칙 `FinancialsAt`: 접수일 `< T` 필터, 사업보고서(11011)만, 연도별 가장 늦은 공시 값 모름 → 직전 연도 대체 + 표시, 비교 연도 값 모름 → 대체 없이 `NO_DATA`. 카탈로그 4장·D-057 3번과 일치. 테스트 `#가_재무_4조건_골든_T1`, `#가_재무_4조건_T2_접수일_다음_거래일부터_새_공시와_정정을_쓴다`.
- 정규 JSON(`CanonicalJson`): 키 사전순(UTF-16 코드 단위 = `TreeMap` 자연 순서), 공백 없음, 수치는 정수 외 문자열만, 허용 타입 밖은 예외. `selected` 순위순·`evaluations` 코드순 정렬과 중복 거부는 레코드 생성자에서(`SelectionResult.java:130-145`). 고정 문자열 테스트 `SelectionResultTest`.
- 문법 거부: 모르는 키, `gate.00`, 기간 키 불일치, 범위 밖, 재무 지표의 비KRX 사용, 조건 없는 선정(`SelectionCriteriaTest`). 저장소 `selections/*.yaml` 전부 해석 테스트.
- `TradingRules`: INTRADAY는 `exit.max-hold-days`를 소비하지 않아 `requireAllUsed`가 거부, SWING 2~20 선택, POSITION 20~120 필수·`CARRY`만. 저장소 검사 `RepositoryProfilesTest`가 기존 두 스윙 버전 밖 새 스윙 프로필에 키를 요구.
- 결정 번호 D-057: main에 D-053~056 없음. 다른 열린 브랜치가 D-053·D-054를 쓰는 기록(`docs/reviews/pr-39/qa.md:16`)이 있어 건너뛴 것으로 본다 `[추론]`.

## 판정
수정 필요(B3·B6 → F1: 선정 결과 `inputs` 기준일을 스냅샷 수록 범위로 확인하고, 모자라면 실패시키며, `SnapshotSelectionData` 실데이터 경로 테스트를 더할 것. F2~F5는 권고)
