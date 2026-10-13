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

## 재검토 2026-10-13
- 대상: PR #74 / 커밋 `9ac248e` / `git diff origin/main...HEAD` 전체(33파일, +1714 −128). 직전 검토 뒤 바뀐 것은 커밋 `9ac248e` 하나(18파일)이며, 판정은 diff 전체를 기준으로 다시 했다.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline --rerun-tasks`(캐시를 쓰지 않고 130개 작업 모두 실행): **BUILD SUCCESSFUL**(2분 5초), 테스트 결과 XML 합계 952건, 실패 0, 오류 0, 건너뜀 0.
  - 탐침(임시로 `VirtualExchangeTest`에 붙여 돌리고 원상 복구, `git status` 깨끗함 확인). 이번 수정에 들어간 `SESSIONS` 규칙(`opensWithAuction() = true`)을 썼다.
    - P1(08:50 시장가 → 09:00:01 호가 → 09:00:20 체결 70,200): 호가에서는 체결 없음, 체결 틱에서 `10@70200`, 슬리피지 0, FILLED. 직전 F1 재현이 사라졌다.
    - P2(09:00:01 호가 뒤 시장가 → 09:00:02 호가 → 09:00:20 체결): 호가에서는 체결 없음, `10@70200`. 사라졌다.
    - P4(그날 첫 체결 뒤 시세 공백 → 14:19:47 재기동 호가 → 시장가): 다음 호가에서 `10@70100`. 같은 날 이미 열린 종목은 공백 뒤에도 접속 매매로 체결된다.
    - P3(그날 녹화가 장중 11:00에 처음 시작, 공백 표시 없음): 아래 R4.
  - 커밋된 `docs/reviews/pr-74/architect.md` 첫 절이 내가 쓴 원문과 같은지 확인했다(48줄, 고친 줄 번호 인용 포함). 메인이 고친 흔적은 없다.
- 커밋 메시지와 D-069 "검증" 절은 사실로 받지 않고 코드·테스트·탐침으로만 확인했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --offline --rerun-tasks` 실행, BUILD SUCCESSFUL, 952 tests / 0 failures / 0 errors / 0 skipped |
| B2 | 통과 | 추가된 계약 `MarketRules.opensWithAuction()`(domain 기본 메서드, `MarketRules.java:49-55`)과 `MarketDataSet.exchangeTimed()`(sim 기본 메서드, `MarketDataSet.java:27-34`)는 둘 다 순수 Java이고 새 의존이 없다. ArchUnit·금지 의존 검사가 빌드에서 통과했다 |
| B3 | 통과 | 두 기본 메서드, `VirtualExchange`의 7인자 생성자, BOOK-V3 규칙 보강이 ADR-004 2.2 12번, D-069(결정·대안 G·H·위험·검증), 결정 로그 표, `ExecutionSettings.java:18-22` 주석에 들어갔다. 직전 F3의 문서 문구도 고쳤다. ADR-004 5장은 "실녹화 실험 두 번 실행은 아직 하지 않았다 … 그 전까지 미검증"으로 바뀌었고, `docs/qa/pending.md`의 재판정 요청 줄은 지웠다(`qa-out-status.md`에 "증거 커밋 뒤 올림"). REST·DB 스키마·설정 키 변경은 없다 |
| B4 | 통과 | `opened` 상태는 Run마다 새로 만드는 `VirtualExchange` 안의 가변 맵이다(`VirtualExchange.java:104`). 계좌끼리 공유하지 않고, 결과에 영향을 주는 순회도 없다. 시스템 시계·난수는 쓰지 않는다. 골든은 `TestKrxRules`(`opensWithAuction` 기본 false)라 그대로 통과한다. 합성 시세 실험은 이벤트가 09:00~15:20 안에 있어(`SyntheticMarket.java:57-58`) 기본 단계 판정으로 `CONTINUOUS`이므로 결과가 바뀌지 않는다 |
| B5 | 통과 | 새 코드에 금액 계산이 없다. 체결 경로는 직전과 같이 `BigDecimal`을 쓴다 |
| B6 | 통과 | 직전 지적 세 가지에 모두 테스트가 생겼다. (1) F1: `VirtualExchangeTest`에 시가 첫 체결 테스트 5개(`개장_뒤_첫_체결_전_호가로는_…`, `개장_뒤_첫_체결_전에_낸_주문도_…`, `사이_이벤트_없이_…`, `시가_단일가_체결_전_시세_공백_뒤에는_…`, `매매_단계를_보지_않는_거래소는_…`), `MarketRulesImplTest.KRX만_시가_단일가로_연다`. (2) F2: `DailyBarSessionPhaseTest`가 실제 `KrxMarketRules`(번들 캘린더)로 돈다. `캘린더_수록_범위_밖_날짜의_일봉_실험도_Run이_끝까지_돈다`(2025-06), `개장이_늦은_날도_시가_칸에서_체결된다`(2026-01-02). (3) 녹화 명령: `ExperimentGateTest`에 정지 요청 테스트, 장 시간 종료 코드 4·결과 파일 없음 테스트, 인자 오류 1 테스트. R3: `RecordingExperimentTest`가 체결이 나는지 본다. 삭제하거나 끈 테스트는 없다(커밋 diff의 `-` 줄에 `@Test`·`void` 없음) |
| B7 | 해당 없음 | 실전 주문 경로, Guard, 블랙리스트·수동 보유, 리스크 한도, 장애 대응, 증권사 주문 연동, 보호 테스트 파일 변경이 없다(diff 파일 목록). `MarketRules`에 기본값 false인 메서드를 더한 것은 실전 쪽 기존 동작을 바꾸지 않는다 |
| B8 | 통과 | 새 diff에 비밀값 패턴이 없다(직전과 같은 grep). gitleaks는 로컬에 없어 CI에 맡긴다 |

### 직전 지적 대응 확인
- F1 해결. `VirtualExchange.phase()`(`:193-213`)는 `opensWithAuction` 시장에서 그 종목의 그날 첫 `CONTINUOUS` 체결 틱 전까지를 `OPENING_AUCTION`으로 돌려준다. 그래서 `matchBook`이 불리지 않고 새 주문도 참여 표시가 붙는다. 첫 체결 틱은 `openingPrint`로 처리한다. 그 직전까지 유효해진 주문을 모두 참여시킨 뒤(`:162-165`) `matchAuction`으로 그 체결가에 체결한다. 탐침 P1·P2로 확인했다.
- F2 해결. 일봉 경로는 `exchangeTimed() == false`(`DailyBarMarketDataSet.java:103-107`)이고, 실행기가 이 값을 거래소에 넘긴다(`SimulationExperimentRunner.java:331-332`). 그러면 `phase()`가 캘린더를 부르지 않고 `CONTINUOUS`를 돌려준다. 생산 코드에서 `VirtualExchange`를 만드는 곳은 이 한 곳뿐이다(grep). `DailyBarSessionPhaseTest`로 2025년 날짜와 10:00 개장일 둘 다 확인했다. 녹화 실험은 시작 전에 모든 녹화일이 캘린더 거래일인지 확인한다(`RecordingExperiment` 실행부). 그래서 수록 범위 밖 날짜로 들어오면 Run을 시작하기 전에 입력 오류로 끝난다.
- F3 해결(위 B3).
- R1·R2는 D-069 "위험과 한계"에 후속 과제로 올라갔다. R3은 반영됐다.

### 점검표 밖 새 발견
- R4 [권고, 후속] 그날 녹화가 장중에 공백 표시 없이 처음 시작하면 그 종목의 첫 체결 틱을 시가 단일가 체결로 본다. 이때 시장가 매수가 최우선 매도 호가보다 싼 체결가로 슬리피지 0에 체결된다.
  - [사실] 탐침 P3: 11:00:00 호가 → 시장가 매수 100주 → 11:00:01 호가(매도 70,100)에서 체결 없음 → 11:00:05 체결 틱(70,000 × 3)에서 `3@70000`, 슬리피지 0 → 다음 호가에서 나머지 체결(`60@70100`, `37@70200`).
  - 영향: 첫 체결 틱 수량 안으로 한정되고 낙관 방향이다. 2026-10-12 녹화는 08:50에 시작하므로(`experiments/stage-2-replay/2026-10-12.md:24`) `EXP-20261013-REC01`에는 영향이 없다.
  - 권고: 다음 실녹화 실험 전에 처리한다. 방법은 둘 중 하나다. ① 그 종목의 그날 첫 이벤트가 개장 + 30초(랜덤 엔드 상한 `[가정]`)보다 늦으면 이미 열린 것으로 본다. ② 녹화 어댑터가 개장 뒤 시작한 날의 첫 이벤트 앞에 공백을 끼운다(data-engineer 경계라 ①을 추천한다).
- 참고: 그날 한 번도 체결되지 않은 종목은 그날 내내 접속 매매로 넘어가지 않는다. D-069 "위험과 한계"에 보수적 방향으로 적혀 있다. 동의한다.
- 3단계 완료 판정과의 관계: 이 PR은 QA C1 (가)안의 코드 조건(단일가·장전 처리, 녹화 실험 명령)을 채운다. 증거 조건(실녹화 두 번 실행 결과 커밋)은 아직 남아 있고, 문서에도 그렇게 적혀 있다. 이 판정은 코드 PR에 대한 것이며 단계 완료 판정이 아니다.

## 판정 (재검토 2026-10-13)
승인
