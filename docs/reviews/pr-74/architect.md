# architect 검토: PR #74 단일가·장외 체결 규칙 BOOK-V3, 짧은 기간 지표 판정 불가, 녹화 실험 명령 (D-069)
- 대상: PR #74 / 브랜치 `claude/project-thread-jyo985` / 커밋 `cf93632` / `git diff origin/main...HEAD` 전체(28파일, +1343 −126, 커밋 `360ec5f`·`cf93632`)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline`(Maven Central 429 때문에 캐시 사용): **BUILD SUCCESSFUL**, 130 tasks. 테스트 결과 XML 합계 941건, 실패 0, 오류 0, 건너뜀 0. ArchUnit·순수 모듈 금지 의존 검사 포함.
  - 탐침 P1·P2(임시로 `VirtualExchangeTest`에 붙여 돌리고 원상 복구, `git status` 깨끗함 확인): 아래 F1.
  - 탐침 COV(임시 테스트, 실행 뒤 삭제): `new KrxMarketRules(ExchangeCalendar.bundled(KRX), ...).sessionPhase(2025-06-02T01:00Z)` → `CalendarCoverageException: KRX 캘린더에 2025-06-02 데이터가 없습니다(수록 범위 2026-01-01 ~ 2027-12-31)`. 아래 F2.
  - `MetricsCalculatorTest` 새 기대값 샤프 1.432721을 Python `decimal`(정밀도 40)로 다시 계산: 1.43272057… 일치.
  - diff에서 비밀값 패턴(appkey, secret, token, password, 계좌번호 모양) grep: 없음. gitleaks는 컨테이너에 없어 돌리지 못함(CI에 맡김).
- PR 본문·커밋 메시지·D-069 "검증" 절은 사실로 받지 않고 코드와 위 실행 결과로만 판정했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --offline` 실행, BUILD SUCCESSFUL, 941 tests / 0 failures / 0 errors / 0 skipped |
| B2 | 통과 | `SessionPhase`는 `autotrade-domain`의 순수 enum(`autotrade-domain/.../SessionPhase.java`), `MarketRules.sessionPhase`는 기본 구현이 있는 메서드(`MarketRules.java:41-47`). sim은 기존 의존(marketdata, risk, strategy)만 쓰고 build 파일 의존 변경 없음(`autotrade-app-experiment/build.gradle.kts`는 JavaExec 작업 추가뿐). 새 패키지 없음(`com.autotrade.sim.recording`, `com.autotrade.sim.run` 기존). ArchUnit·금지 의존 검사 빌드에서 통과 |
| B3 | 통과(문서 정확성 지적은 F3) | 계약 변경은 `MarketRules.sessionPhase` 추가, 체결 규칙 `BOOK-V3`, 지표 null 의미 변경, 등록 파일 모양(`input: recording`), Gradle 작업. ADR-004 2.2 12번·4장, D-069, 기획서 v14.18, `RunMetrics` 주석(`RunMetrics.java:12-16`)에 반영됨. REST·DB 스키마·설정 키 변경 없음(`ResultBundle.java:260` `dec(null)` → null, `ExperimentViews.java:347` 동일, UI는 null을 그대로 받음) |
| B4 | 통과 | 새 코드에 `now()`·시드 없는 난수 없음. `RecordingMain.configDir()`가 시스템 속성·환경 변수를 읽지만 입력 경로 결정용이며 결과에 들어가지 않음. 결과 파일에 실행 시각 없음(`RecordingExperiment.inputs`). 골든 `krx-golden.report`는 체결·손익 줄 그대로이고 머리 줄 `BOOK-V3`와 지표 줄(`observation-days=2`, `cagr=-`, `sharpe=-`)만 바뀜(diff 확인), `SimulationExperimentRunnerTest`·골든 테스트 통과. 결정론과 별개인 회귀는 F2 |
| B5 | 통과 | 단일가 체결은 `BigDecimal` 체결가·`BigDecimal.ZERO` 슬리피지(`VirtualExchange.java:282-307`), 지표는 기존 `DecimalMath`. `double` 금액 없음 |
| B6 | **실패** | 새 동작 테스트는 있음(`VirtualExchangeTest` 단일가 5개, `MarketRulesImplTest` 매매 단계, `MetricsCalculatorTest` 판정 불가, `RecordingExperimentTest` 3개). 삭제·비활성 테스트 없음(diff의 `-` 줄에 `@Test`·`@Disabled` 없음, `MetricsCalculatorTest`의 3점 샤프 기대값은 판정 불가 규칙 때문에 60점 데이터로 바꿨고 새 값은 독립 계산과 일치). 그러나 (1) 실녹화에서 늘 생기는 "개장 뒤 첫 체결 전에 접속 매매 시각 호가가 먼저 오는" 경우 테스트가 없고 그 경우 코드가 D-069 규칙과 다르게 동작함(F1, 탐침 P1·P2), (2) 캘린더 수록 범위 밖 날짜(2026-01-01 이전)로 도는 조합 실험 경로 테스트가 없어 회귀를 못 잡음(F2), (3) `ExperimentMain recording`의 정지 요청·장 시간 보호(종료 코드 4)와 `RecordingMain` 인자 처리 테스트가 없음(`ExperimentGateTest`는 `combination`·`study`만, `:86-144`). `RecordingExperimentTest`는 `RunGate.NONE`만 씀 |
| B7 | 해당 없음 | 실전 주문 경로, `RealTradingGuard`, `GuardedOrderGateway`, 블랙리스트·수동 보유 코드, 리스크 한도, 장애 대응, 증권사 주문 연동 파일 변경 없음(diff 파일 목록). `RiskChain`·`BlacklistSnapshot`은 녹화 실험에서 호출만 함(`RecordingExperiment.java` 실행부). `MarketRules`에 기본 메서드 추가는 실전 쪽 기존 구현 동작을 바꾸지 않음. 보호 테스트 변경 없음 |
| B8 | 통과 | diff grep에서 비밀값 패턴 없음. 등록 파일 예시는 종목 코드·실험 값뿐. gitleaks 로컬 실행은 못 함(CI 결과로 확인 필요) |

## 점검표 밖 발견

- **F1 [수정 필요] 시가 단일가 참여 주문이 단일가 체결 전에 접속 매매 호가로 체결된다(QA S2 위험이 실녹화 조건에서 남음).**
  - [사실] `VirtualExchange.onEvent`는 `CONTINUOUS` 시각의 호가 스냅샷이면 `matchBook`을 부르고(`VirtualExchange.java:147-150`), `matchBook`은 `o.auction` 표시를 보지 않는다(`VirtualExchange.java:385-428`). 단일가 체결(`matchAuction`)은 TradeTick에서만 일어난다(`:154`).
  - [사실] 탐침 P1: 08:50 시장가 매수 10주 → 08:50:02 호가 → **09:00:01 호가(매도 70,100)** → 09:00:20 체결 70,200. 결과: 09:00:01 호가에서 `10@70100` 체결, 09:00:20 단일가 체결에서는 체결 없음. D-069 결정 2와 ADR-004 2.2 12번("시가는 접속 매매 첫 체결을 단일가 체결로 보고 참여 주문을 그 체결가로")과 다르다.
  - [사실] 탐침 P2: 09:00:01 호가 뒤 시장가 매수 → 09:00:02 호가에서 바로 `10@70100` 체결. 첫 체결(단일가 체결) 전 호가로 체결된다.
  - [사실] 2026-10-12 실녹화의 첫 체결은 09:00:03~09:00:24, 마지막 정규장 체결은 15:30:09~15:30:22이고, 005930 호가는 하루 104,015건이다(`experiments/stage-2-replay/2026-10-12.md:44,98-100`). 개장 뒤 첫 체결 전에 호가가 먼저 올 가능성이 매우 높아, 실녹화 실험에서는 시가 단일가 규칙이 시장가에 사실상 적용되지 않는다. PR의 테스트(`장전_예상_호가로는_체결하지_않고_시가_단일가_체결가로_체결한다`)는 09:00 뒤 첫 이벤트가 체결 틱인 경우만 본다.
  - [추론] 첫 체결이 09:00:00이 아니라 09:00:03~09:00:24, 15:30:09~15:30:22에 흩어진 것은 KRX 단일가 "랜덤 엔드"(단일가 접수 마감을 최대 30초 안에서 임의로 늦춤)와 맞는다. 그렇다면 09:00:00~첫 체결 사이 호가는 아직 예상 호가다. `[가정]` 랜덤 엔드 규정은 이 검토에서 원문을 확인하지 않았다. 확인 방법: 한국거래소 유가증권시장 업무규정 시행세칙의 단일가매매 시간 임의 연장 조항, 거래소 "매매제도" 안내.
  - 고칠 방향(추천): 종목별 "그날 시가 단일가 체결됨" 상태를 두고, 개장 뒤 그 종목 첫 체결 틱 전까지는 `CONTINUOUS` 시각이어도 호가로 체결하지 않는다(시가 단일가 참여 주문과 새 주문 모두). 첫 체결 틱이 시가 단일가 체결이다. 종가 쪽은 지금처럼 폐장 뒤 첫 체결로 맞다. 대안: `KrxMarketRules`가 시가 단일가를 09:00:30까지로 잡는 방법은 랜덤 엔드 시점을 맞추지 못해(09:00:03 체결도 있음) 버린다. 규칙이 바뀌므로 위 P1·P2를 손 계산 테스트로 넣는다. BOOK-V3가 아직 main에 없으므로 버전은 그대로 두어도 된다.
- **F2 [수정 필요] 캘린더 수록 범위 밖 날짜에서 모든 Run이 실패한다(조합 실험 회귀).**
  - [사실] `VirtualExchange.onEvent`가 모든 이벤트마다 `rules.sessionPhase`를 부르고(`VirtualExchange.java:138`), `KrxMarketRules.sessionPhase`는 `calendar.session(...)`을 부르며(`KrxMarketRules.java:79`), 번들 캘린더는 2026-01-01~2027-12-31 밖이면 예외를 던진다(`ExchangeCalendar.java:116-120`, `krx.json` coverageFrom/To). 탐침 COV로 2025-06-02에서 `CalendarCoverageException` 확인.
  - [사실] 조합 실험은 실제 `KrxMarketRules(bundled calendar)`로 돈다(`CombinationExperiment.java:115,170,199`). 일봉 거래일 유도는 수록 범위 밖 기간을 일부러 받아 준다(`SnapshotTradingDays.java:78-96`). 문서 예시 기간도 `2025-01-02`부터다(`docs/strategy/combination-experiments.md:61`). 실행기는 예외를 Run FAILED로 바꾼다(`SimulationExperimentRunner.java:375-379`). 따라서 이 PR 뒤 2026년 이전 구간을 포함한 조합 실험(D-066 워크포워드 학습 3년 + 검증 1년)은 모든 Run이 FAILED가 된다. PR 전에는 `VirtualExchange`가 캘린더를 부르지 않아 돌았다.
  - [사실] `CombinationExperimentTest` 고정 데이터는 2026-01-02부터라(`CombinationExperimentTest.java:36`) 이 회귀를 못 잡는다.
  - [사실] 함께 볼 것: 일봉 경로 칸 시각은 09:00·09:10·09:20 고정이다(`DailyBarMarketDataSet.java:62-63`). 2026-01-02(10:00 개장, `krx.json` specialSessions) 같은 날은 BOOK-V3에서 이 세 칸이 `CLOSED`라 시가 칸 체결이 사라진다. 경로 모델이 그날 개장 시각을 따르게 하거나(경로 모델 버전 상향), 일봉 실행은 매매 단계를 보지 않는 규칙으로 돌리는 것 중 하나를 정해야 한다.
  - 고칠 방향(추천): 수록 범위 밖 날짜의 `sessionPhase`를 어떻게 할지 ADR-004 12번에 적고 테스트로 묶는다. 안 A(추천): 범위 밖은 기본 시간표(09:00~15:30, 단일가 30분·10분)로 판단하고 그 사실을 결과 `inputs`에 남긴다(2016-08 이전 15:00 폐장은 `[가정]`으로 표시). 안 B: 일봉 조합 실험은 단일가 구간이 없는 규칙(기본 구현)을 쓰는 감싼 `MarketRules`로 돌린다. 어느 쪽이든 2025년 날짜가 들어간 조합 실험 테스트를 더한다.
- **F3 [수정 필요, 문서] 아직 없는 증거를 있는 것처럼 적었다.**
  - [사실] ADR-004 5장은 "실험 두 번 실행은 `experiments/EXP-20261013-REC01/`"로 확인했다고 적는다(`docs/adr/ADR-004-simulation-engine.md:148`). 그러나 그 폴더에는 `experiment.yaml`만 있다(결과 `inputs.yaml`·`results.md`·`detail.txt` 없음). D-069 결정 5도 "사용자 PC 실행 뒤 커밋"이라 아직 하지 않은 일이다.
  - [사실] `docs/qa/pending.md:14`에 3단계 재판정 요청을 지금 올렸다. 증거 파일이 없으면 QA C1 (가)안의 두 번째 조건을 채우지 못해 같은 판정이 다시 나온다.
  - 고칠 방향: ADR-004 5장은 "실녹화 두 번 실행: 사용자 PC 실행 뒤 `EXP-20261013-REC01`에 커밋 예정(미검증)"으로 고친다. 재판정 요청은 증거 커밋 뒤에 올리거나 표에 "증거 커밋 뒤 처리"를 적는다. 또한 F1·F2 수정 뒤의 코드로 실녹화를 돌려야 하므로 실행 순서는 F1·F2 머지 → 사용자 PC 실행 → 증거 커밋 → 재판정이다.
- R1 [권고] `CLOSED` 시각 체결 틱도 `lastTrade`를 갱신한다(`VirtualExchange.java:158`). 녹화가 16:00 이후 시간외 단일가까지 들어오면 거래일 마감 평가 가격이 정규장 종가가 아니라 시간외 가격이 된다(±10% 범위). 지금 녹화는 15:43에 끝나 영향이 작지만, 평가 가격은 정규장(단일가 체결 포함) 체결로만 갱신하는 것이 맞다고 본다.
- R2 [권고] 녹화 실험은 모든 종목을 `InstrumentKind.STOCK`으로 보고(`RecordingExperiment.java` 규칙 생성부 주석), 소속부는 KOSPI·KOSDAQ만 받는다(`KrxBoard`). ETF가 녹화 종목에 들어오면 호가 단위가 틀린다. 등록 파일에 종류를 적게 하거나 ETF를 거절하는 검사를 더하기를 권한다.
- R3 [권고] `RecordingExperimentTest`는 실제 `KrxMarketRules`로 돌면서 Run 실패가 없다는 것만 본다. 골든 고정 데이터 위에서 체결 건수가 0이 아님(또는 골든과 같은 체결 원장)을 함께 확인하면 매매 단계 판정이 모두 `CLOSED`로 빠지는 회귀를 잡을 수 있다.
- 확인한 것(문제 없음): 종가 단일가 경로(15:20 뒤 참여 표시 → 폐장 뒤 첫 체결 틱으로 일괄 체결, 남은 수량 `endOfDay` 만료, `VirtualExchange.java:217-221,282-307`), 단일가 구간 공백 시 참여 표시 해제(`:257-266`), 지정가 단일가 범위 판정 방향, 지표 null을 쓰는 곳 모두 null 안전(`CombinationReport.java:282-288`, `ResultBundle.java:260`, `StudyReportWriter`, `ScreeningCriteria.java:65`, `ExperimentViews.java:347`), `RegistrationYaml` 추출 뒤 `CombinationPlanTest` 통과.

## 판정
수정 필요(B6, F1, F2, F3)
