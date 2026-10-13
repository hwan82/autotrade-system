# architect 검토: PR #79 일봉 워크포워드 판정 명령 runWalkForwardStudy(ADR-009, D-072)와 일봉 후보 로직 3개
- 대상: PR #79 / 브랜치 `claude/project-thread-pb3vv4` 커밋 `6ee1698` / main `98354fb` 대비 diff(`git diff 98354fb...HEAD`, 커밋 4개, 50파일 +3834 −85)
- 점검표: B
- 직접 실행한 것: 별도 복제본(`/tmp/claude-0/review-pr79-architect`)에서 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL (3m 16s), 종료 코드 0**. 테스트 결과 XML 합계 994건, 실패 0, 오류 0, 건너뜀 0. 새 테스트: WindowWarmupSettleTest 5, WalkForwardStudyTest 4, WalkForwardJudgeTest 6, WalkForwardPlanTest 4, WalkForwardWindowsTest 5, MarketIndexTest 4, DailyCloseLogicsTest 11, ExperimentGateTest 15(1건 추가). 보호·기존: ArchitectureTest 18, ModuleOwnershipTest 13, SimulationExperimentRunnerTest 11, RecordingExperimentTest 3, CombinationExperimentTest 5, RepositoryProfilesTest 1 모두 통과.
- 참고: 메인 Claude 설명·PR 본문·커밋 메시지는 판정 근거로 쓰지 않았다. 아래 근거는 저장소 코드와 위 빌드 결과다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 994건 실패 0 |
| B2 | 통과 | 새 운영 클래스는 `com.autotrade.sim.walkforward`(autotrade-sim 소유, ModuleOwnershipTest.OWNERS의 `com.autotrade.sim` 접두, ModuleOwnershipTest.java:110)와 `com.autotrade.strategy.catalog`. `autotrade-sim/build.gradle.kts`는 별칭 작업만 추가(의존 변경 없음). Spring·증권사 SDK·DB 드라이버 import 없음(diff grep). ArchitectureTest 18건·ModuleOwnershipTest 13건 통과. 실전 엔진·게이트웨이·감시·risk·broker·domain 모듈 diff 없음 |
| B3 | 통과 | 계약 변경(`MarketDataSet.tradingDays()/firstEventIndex()` 기본 메서드 MarketDataSet.java:36-52, `SimulationExperimentRunner` 생성자 인자 `warmupDays`·`settleAtWindowEnd`, `RunReport.windowEndLots` 레코드 성분, `CostModel.withSlippage`, 등록 파일 `study.yaml`, Gradle 명령)이 ADR-009 2.1~2.8과 D-072, docs/adr/README.md, docs/decisions/README.md, combination-experiments.md 6장, experiments/README.md, strategies/README.md에 반영됨. 설정 키·DB 스키마·REST API 변경 없음. 단, ADR-009 2.2 문구 부정확(아래 R1) |
| B4 | 통과 | diff에 `now()`·`new Random`·`System.currentTimeMillis`·`double`·`float` 추가 없음(grep). 결과에 영향 주는 순회는 TreeMap/TreeSet/List(`VirtualAccount.quantities()`는 TreeMap, VirtualAccount.java:187), HashMap·HashSet은 조회 전용. 대조군 시드는 `plan.seed()+i`(WalkForwardStudy.java:348). 골든 `krx-golden.report` 쓰는 SimulationExperimentRunnerTest·RecordingExperimentTest·SelectionCombinationRunnerTest·RunGateTest·RecordedMarketDataSetTest 통과. WalkForwardStudyTest `등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다`, `같은_입력을_두_번_돌리면_결과가_같다` 통과 |
| B5 | 통과 | 금액·비율 모두 BigDecimal(MathContext 40 HALF_EVEN, WalkForwardJudge.java·Pooled.java·MarketIndex.java), 구간 끝 정리 가격은 `rules.roundToTick(..., FLOOR)`(SimulationExperimentRunner.java:568-569), 비용은 `cost.fees`. 손 계산 테스트 `구간_끝_정리는_종가_슬리피지와_비용을_뺀_왕복으로_들어간다`(−60원, 9,999,940원) 통과 |
| B6 | 실패 | 삭제·비활성화된 테스트 없음(`git diff --diff-filter=D` 없음, 삭제된 `@Test`·추가된 `@Disabled` 없음). 그러나 실제 운영 경로의 새 동작에 직접 테스트가 없다: (1) `DailyBarMarketDataSet`이 `tradingDays()`를 `@Override`하게 되어 **기존 조합 실험을 포함한 모든 일봉 실험이 새 `sliceByDayIndex` 경로**(SimulationExperimentRunner.java:272-273, 345-)로 바뀌었는데, ADR-009 2.3 "같은 입력에서 두 경로의 결과는 같다"를 확인하는 동등성 테스트가 없다. (2) 예열 계약 테스트(WindowWarmupSettleTest)는 `InMemoryMarketDataSet`(선형 경로)만 쓰고, 워크포워드가 실제로 쓰는 일 색인 경로의 예열(전략만 봄·체결/평가 없음·미래 누출 없음·부족 시 거부)은 WalkForwardStudyTest 통합 실행으로 간접 통과만 한다. 아래 M2 |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트 코드, 리스크 한도, 장애 대응, 증권사 주문 연동, 보호 테스트를 건드리지 않음(해당 모듈 diff 없음). 실험 블랙리스트는 기존 `BlacklistSnapshot`·`RiskChain.builder`를 그대로 사용(WalkForwardStudy.java:116-118). 실험 프로세스 정지 관문은 기존 `ExperimentGate`(D-063) 경유, ExperimentGateTest `워크포워드_판정도_정지_요청과_장_시간에는_시작하지_않고_결과를_쓰지_않는다` 통과 |
| B8 | 통과 | diff에서 appkey·secret·token·password·계좌번호 패턴 없음(grep). 새 프로필·문서에 비밀값·자리표시자 외 값 없음 |

## 점검표 밖 발견

### 수정 필요
- **M1 예열 일수 충분성 검사 없음, 그리고 `kr-donchian-swing@1`의 `study.warmup-days`가 1일 모자람.**
  - [사실] 워크포워드는 등록 파일의 `warmup-days` 하나만 쓰고(WalkForwardStudy.java:262, 462) 후보·±20% 변형이 신호에 필요한 거래일 수와 비교하지 않는다. 모자라면 오류 없이 각 구간 앞부분에서 신호가 나지 않는다(DailyCloseStrategy는 종가가 모자라면 판단하지 않음).
  - [사실] `DonchianBreakout`은 종가 `lookback + 1`개가 필요하다(DonchianBreakout.java:23). 그런데 `strategies/kr-donchian-swing/v1.yaml:27`은 `study.warmup-days: "20"`(lookback 20)이다. ±20% 변형 k120은 lookback 24라 25일, `kr-52w-high-position` k120은 lookback 300이라 300일이 필요하다. 문서 예시 `warmup-days: "260"`(combination-experiments.md 6장)으로는 52주 고가 k120 변형이 학습 구간마다 약 40거래일 신호를 못 내서 C7이 탈락 쪽으로 기운다.
  - [추론] 등록 파일은 결과를 본 뒤 고칠 수 없고(ADR-009 2.4), 프로필 v1은 머지 뒤 고칠 수 없다(strategies/README 규칙). 그래서 머지 전에 고쳐야 한다.
  - 요청: (a) `WalkForwardStudy.candidates` 단계에서 후보·변형·대조군 프로필이 필요로 하는 예열 일수(최소한 `study.warmup-days`, 바람직하게는 로직별 필요 종가 수를 팩토리가 알려 주는 방식)보다 `warmup-days`가 작으면 시작을 거부하거나, 적어도 results.md·inputs.yaml에 "예열 부족" 경고를 남기고 C7을 판정 불가로 처리한다. 실패하는 테스트 포함. (b) `kr-donchian-swing/v1.yaml`의 `study.warmup-days`를 21로 바로잡는다(머지 전이라 v1 수정 가능). 대안: 변형 lookback까지 덮도록 프로필마다 `study.warmup-days`를 k120 기준으로 둔다.
- **M2 일 색인 자르기 경로의 동등성·예열 계약 테스트.**
  - 요청: (a) 같은 `DailyBarMarketDataSet`을 `tradingDays()`를 숨긴 래퍼(선형 경로)와 원본(일 색인 경로)으로 각각 돌려 `ReportWriter.render` 출력이 바이트까지 같음을 확인하는 테스트(warmupDays 0과 N>0 둘 다). (b) 일 색인 경로에서 WindowWarmupSettleTest와 같은 단언(예열 날짜를 전략만 봄, 자산 곡선·체결은 `from` 이후, `to` 뒤 시세 미전달, 부족 시 "예열 거래일이 모자랍니다")을 하는 테스트. ADR-009 2.1 "기존 골든이 증거"는 선형 경로(InMemory) 골든만 덮고, 경로가 바뀐 기존 조합 실험은 CombinationExperimentTest의 동작 단언과 자기 일치로만 확인된다.

### 권고(머지를 막지 않음)
- **R1 ADR-009 2.2 문구.** "가상 전용 우회 없음"이라고 하지만 구간 끝 정리 체결은 `VirtualExchange`·주문 상태머신·`RiskChain`을 거치지 않고 `VirtualAccount.apply`에 직접 들어간다(SimulationExperimentRunner.java:557-). 성과 집계용 회계 처리라 실전 경로 공유 원칙(지침 5장)과 충돌한다고 보진 않지만, ADR에 "주문이 아니라 평가용 정리 체결이며 리스크 체인·상태머신을 지나지 않는다, 진입이 이미 블랙리스트를 지났으므로 블랙리스트 종목 보유는 생기지 않는다"고 정확히 적을 것.
- **R2 대조군 Run 실패가 종료 코드에 반영되지 않음.** `anyFailed`는 학습·검증·스트레스만 갱신하고(WalkForwardStudy.java:313, 332, 341) 대조군은 `NullSummary.failed`로만 센다(353, 386-388). 대조군이 대량 실패해도 종료 코드 0이다. 실패 시드 비율이 일정 이상이면 종료 코드 2로 하거나 results.md 상단에 경고를 둘 것.
- **R3 상태 문구.** ADR-009와 docs/adr/README.md가 architect 교차 검토 전에 "채택(D-072)"으로 적혀 있다. 이 검토의 수정 필요가 풀린 뒤 채택으로 남기는 것이 다른 ADR(제안 → 채택)과 맞다.
- **R4 공개 범위 확대.** `ScreeningCriteria.pct/bp/num/won/seconds/profitFactor/threshold`, `CombinationExperiment.board/loadStrategy/loadSelection`, `StrategyStudy.Perturbation.scale`, `CombinationPlan.Ref.parse`를 패키지 간 재사용을 위해 public으로 열었다. 공용 도구 클래스(예: `sim.study.Format`, `sim.combo.Profiles`)로 옮기면 API 표면이 덜 퍼진다.
- **R5 사소한 것.** 정리 체결의 `signalEventSeq/fillEventSeq`가 실제 없는 `lastSeq+1`을 가리킨다(SimulationExperimentRunner.java 정리 체결 생성부), 원장 해석 도구가 sequence로 이벤트를 찾으면 어긋난다. `WalkForwardPlan.java:89` 주석의 접미어 예 `-W12-NULL1234`가 실제 `-W12-N1234`와 다르다. `SnapshotInputs.load`가 `Market`을 받으면서 경로는 `krx-daily`로 고정이다(현재 KRX만 지원이라 동작 문제는 없음).

## 판정
수정 필요(B6, M1, M2)

## 재검토 2026-10-13
- 대상: PR #79 / 브랜치 `claude/project-thread-pb3vv4` 새 헤드 `fc7e4fc`(직전 검토 `6ee1698` 뒤 커밋 1개, 25파일 +881 −39) / main `98354fb` 대비 전체 diff(`git diff 98354fb...HEAD`)를 다시 봄
- 점검표: B
- 직접 실행한 것: 복제본에서 `git fetch /home/claude/autotrade-system claude/project-thread-pb3vv4 && git checkout FETCH_HEAD` 뒤 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL (1m 40s), 종료 코드 0**. 테스트 1004건, 실패 0, 오류 0, 건너뜀 0(직전 994건 + 10). 새·바뀐 테스트: DailySlicePathTest 3, WalkForwardJudgeEvaluateTest 4, WalkForwardStudyTest 5(+1), DailyCloseLogicsTest 13(+2), RepositoryProfilesTest 1(검사 추가). 보호·기존: ArchitectureTest 18, ModuleOwnershipTest 13, SimulationExperimentRunnerTest 11, WindowWarmupSettleTest 5, CombinationExperimentTest 5, ExperimentGateTest 15 통과.
- 참고: 커밋에 들어간 이 파일(`docs/reviews/pr-79/architect.md`)의 직전 내용은 내가 쓴 원문과 바이트까지 같음을 확인했다(diff 없음). 커밋 메시지·PR 본문은 판정 근거로 쓰지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 1004건 실패 0 |
| B2 | 통과 | 새 운영 클래스 `EntryFunnel`은 `com.autotrade.strategy.catalog`(autotrade-strategy 소유), sim은 기존 의존(strategy)만 사용. 빌드 스크립트·의존 변경 없음. ArchitectureTest 18·ModuleOwnershipTest 13 통과. 실전 엔진·게이트웨이·감시·risk·broker·domain 모듈 diff 없음 |
| B3 | 통과 | 새 공개 API `CatalogStrategyFactory.requiredHistoryDays`, `ManagedStrategy.requiredHistoryDays()/entryFunnel()`, `runs.tsv` 열 5개, results.md "진입 한도 영향" 표, 종료 코드 2에 대조군 실패 포함이 ADR-009 2.1·2.3·2.7·2.8·5장과 combination-experiments.md·stage-4-plan.md에 반영됨. ADR-009 2.2가 "주문이 아니라 평가용 정리 체결이며 가상 거래소·상태머신·리스크 체인을 지나지 않는다"로 고쳐짐(직전 R1 해소) |
| B4 | 통과 | 추가된 `now()`·`new Random`·`double`·`float` 없음(grep). `EntryFunnel`은 TreeMap, 판정에 쓰지 않는 측정이고 전략 신호·수량에 영향 없음(ManagedStrategy.entry에서 카운트만 추가). `FunnelCapture`는 ConcurrentHashMap에 (프로필 해시, Run 시드)로 넣고 Run 종료 뒤 읽음, Run 시드는 실행기와 같은 `RunSeeds.derive(spec.randomSeed(), combination)`(SimulationExperimentRunner.java:455). 골든 테스트와 WalkForwardStudyTest 병렬 1 vs N 바이트 일치·두 번 실행 일치 통과 |
| B5 | 통과 | 새 금액 계산 없음. 비율 표기는 BigDecimal(WalkForwardReport.funnelRow) |
| B6 | 통과 | 삭제된 테스트 파일·`@Test`·단언 없음, `@Disabled` 없음(98354fb 대비 grep). 직전 M2: `DailySlicePathTest#일_색인_경로와_선형_경로의_보고서가_바이트까지_같다`(같은 DailyBarMarketDataSet을 색인 숨긴 래퍼와 원본으로, 예열 0·21일 ReportWriter 출력 동일, `tradingDays()`가 실제로 채워져 있음을 단언), `#일_색인_경로에서도_예열은_전략만_보고_체결과_평가는_시작일부터이며_구간_끝_뒤_시세는_없다`, `#일_색인_경로에서도_앞선_거래일이_모자라면_시작하지_않는다` 추가. 직전 M1: `WalkForwardStudyTest#예열_일수가_후보나_변형의_필요량보다_작으면_모자란_프로필을_적고_시작하지_않는다`, `DailyCloseLogicsTest#예열_필요량은_로직이_판단을_시작하는_종가_수와_같다`, RepositoryProfilesTest의 `study.warmup-days ≥ requiredHistoryDays` 검사 추가. 판정식 손 계산 `WalkForwardJudgeEvaluateTest` 4건, 깔때기 집계 `DailyCloseLogicsTest#진입_깔때기는_진입_한도_막힘_수량_0을_거래일별로_센다` |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트 코드, 리스크 한도, 장애 대응, 증권사 주문 연동, 보호 테스트 변경 없음(해당 모듈 diff 없음). 실험 정지 관문(D-063)은 그대로 |
| B8 | 통과 | 추가 줄에 appkey·secret·token·password 패턴 없음(이 검토 원문의 점검표 문구만 걸림) |

## 직전 지적 처리 확인
- **M1 해소.** `WalkForwardStudy.requireWarmup`(WalkForwardStudy.java:259에서 호출)이 후보·대조군·±20% 변형마다 `factory.requiredHistoryDays`를 구해 `warmup-days`보다 큰 프로필을 모두 적고 거부한다. 로직별 필요량은 판단 조건과 같은 값(`DonchianBreakout` lookback+1이 entryCondition과 같은 `requiredCloses()`를 씀, `NearHigh` L, `ShortReversal` max(M, K+1, X), `MovingAverageTrend` slow로 `trendUp`의 `closes.size() < slow`와 일치, `PriorDayHighBreakout` 1). `kr-donchian-swing/v1.yaml`의 `study.warmup-days`가 21로 바로잡힘. 문서 예시는 `warmup-days: "300"`(52주 고가 k120 300일 충족).
- **M2 해소.** 위 B6 근거.
- **R1 해소**(ADR-009 2.2 문구). **R2 해소**(대조군 실패도 `anyFailed`, WalkForwardStudy.java:384, ADR 2.8 반영). **R5 일부 해소**(`-W12-N1234` 주석, sequence 설명을 ADR에 적음).
- **R3 미처리**: ADR-009 상태와 docs/adr/README.md가 여전히 "채택(D-072)". 이 재검토로 architect 교차 검토가 승인되므로 남은 문제는 아니다(strategy-researcher 검토 상태는 그 원문을 따른다).
- **R4 미처리**(헬퍼 public 확대). 머지를 막지 않는다.

## 점검표 밖 발견(권고, 머지를 막지 않음)
- **R6 깔때기 연결이 0으로 조용히 떨어질 수 있음.** `FunnelCapture.totals`는 키가 맞지 않으면 `Totals.ZERO`를 돌려준다. WalkForwardStudyTest는 표 머리와 열 이름만 확인하고 값이 0이 아님은 확인하지 않는다. 판정에 쓰지 않는 측정이라 결과 판정에는 영향이 없지만, 실행기의 Run 시드 파생이 바뀌면 표가 소리 없이 0이 된다. 합성 통합 테스트에 후보 검증 `signals > 0` 단언 하나를 더하거나, 키를 못 찾으면 예외로 바꿀 것을 권한다.
- **R7** `CatalogStrategyFactory.requiredHistoryDays`는 전략 인스턴스를 하나 만들어 값을 읽는다(create 비용이 작아 문제는 아님). 장중 로직이 지난 날 종가를 쓰게 되면 해당 클래스가 `requiredHistoryDays()`를 함께 올려야 한다는 점을 ManagedStrategy 주석이 이미 적고 있다. 추가 조치 불필요.

## 판정
승인
