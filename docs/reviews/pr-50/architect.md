# architect 검토: 일봉 리플레이와 조합 실험 실행 명령: 수집 스냅샷 → (가)(나) × 매매지표 실험 (D-061)
- 대상: PR #50 / 커밋 e02edd9d794abc18c0c0eee15ef2fa042ac40d2e (GitHub REST로 PR head와 같음을 확인) / `git diff origin/main...HEAD`(origin/main = 6f50711, 커밋 2개 bed2b83·e02edd9, 17파일 +1966 −6)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL (캐시 재사용분이 있어 아래로 다시 확인)
  - `./gradlew build --rerun-tasks --no-daemon` → BUILD SUCCESSFUL in 2m 15s. 전 모듈 테스트 결과 XML 합계 836건, 건너뜀 0, 실패·오류 0. `ArchitectureTest` 17건 통과, `ModuleOwnershipTest` 통과, `verifyPureDependencies` 통과
  - 새 테스트: `CombinationExperimentTest` 4, `CombinationPlanTest` 2, `DailyBarMarketDataSetTest` 5, `SnapshotTradingDaysTest` 4, 모두 통과
  - diff 전체에서 비밀값 문자열 검색(api key·secret·token·password·account): 해당 없음. 로컬에 gitleaks가 없어 gitleaks 자체는 실행하지 못함(CI에 맡김)
  - PR 본문·커밋 메시지는 판정 근거로 쓰지 않았다. 아래 근거는 코드와 직접 실행 결과다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `--rerun-tasks` 전체 빌드 성공, 836건 실패 0 |
| B2 | 통과 | 새 코드는 모두 `autotrade-sim`(순수 모듈)의 `com.autotrade.sim.combo`·`com.autotrade.sim.daily` 패키지. 의존은 기존 선언(autotrade-sim/build.gradle.kts:5-11: domain·strategy·selection api, marketdata·risk implementation) 안이고 build.gradle.kts 의존 변경 없음. Spring·DB 드라이버·증권사 SDK 참조 없음. `ArchitectureTest`(NO_SYSTEM_TIME 포함) 17건 통과. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 공개 인터페이스(`MarketDataSet`, `ExperimentRunner`) 시그니처 변경 없음. `SimulationExperimentRunner.validateAndSlice`(SimulationExperimentRunner.java:194-231)는 내부 구현 변경. 새 등록 파일 형식(`experiment.yaml`)·결과 파일(`inputs.yaml` 등)·Gradle 실행 명령은 D-061, CombinationPlan.java:19-48 주석, docs/strategy/combination-experiments.md 5장, experiments/README.md에 문서화. DB 스키마·설정 키·REST 변경 없음 |
| B4 | 통과 | 시계: 새 코드에 `now()`·시스템 시계 없음(이벤트 시각은 거래일 + 고정 칸 시각, DailyBarMarketDataSet.java:165). 난수 없음. 순회 순서: 종목 `TreeSet`→코드 순, 거래일 `TreeMap`, 칸 고정 순(DailyBarMarketDataSet.java:119-176). `HashMap periods`(CombinationExperiment.java:133)는 조회 전용. 결정론: `CombinationExperimentTest#같은_입력이면_결과_파일이_바이트까지_같다`, 병렬 2 vs 1 상세 보고서 일치(`...선정_있는_계좌는_선정된_날만_산다`의 `determinism: "같음`). 골든 파일 diff 없음, 기존 골든 테스트 통과 |
| B5 | 통과 | 가격 BigDecimal, 권리 변동 잇기 `MathContext.DECIMAL64`(DailyBarMarketDataSet.java:187), 보고서 반올림 `RoundingMode.HALF_UP` 명시(CombinationReport.java:536-542), 시드머니 `Money.of(long, currency)`(CombinationPlan.java:208). double 금액 없음 |
| B6 | 통과 | 새 동작마다 테스트 있음(위 15건). 손 계산 기대값: 09:20 시가 × 1.0015 호가 올림(10,075→10,100, 10,750→10,770, 10,395→10,420)을 직접 다시 계산해 맞음(CombinationExperimentTest.java:144-157). 권리 변동 잇기·거래정지·상장폐지·범위 밖 테스트(DailyBarMarketDataSetTest). 삭제·`@Disabled` 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·GuardedOrderGateway·블랙리스트 코드·리스크 한도·장애 대응 코드·보호 테스트 변경 없음(diff 17파일 확인). `RiskChain`·`BlacklistSnapshot`은 기존 공개 API를 호출만 함(CombinationExperiment.java:158-161). 단 자원 부족 정지 순서(장애 대응 규칙)와의 관계는 아래 F1 |
| B8 | 통과 | diff에 비밀값 없음. 테스트 manifest URL은 `https://example.invalid`. 로그·예외 메시지에는 경로·스냅샷 ID·날짜만 나옴(CombinationMain.java, CombinationExperiment.java) |

## 점검표 밖 발견

### F1 (수정 필요): 새 단발 일괄 명령이 D-058 정지 순서 밖에 있는데 추적 항목이 없다
- [사실] `runCombinationExperiment`는 기본 힙 6GB의 단발 일괄 명령이다(autotrade-sim/build.gradle.kts의 `maxHeapSize = ... "6g"`). D-058과 broker-and-fault-design.md 5.2 0번은 "단발 일괄 명령"을 수집기·선정 일괄 명령 두 개로만 적고, 이 명령은 정지 요청 파일도 장 시간 보호도 보지 않는다(CombinationMain.java·CombinationExperiment.java에 해당 코드 없음).
- [사실] D-061 "위험과 한계"는 이 공백을 적고 "7단계 전 ... risk-ops와 정한다"고만 한다. 결정 로그 미확정 표(Q-xxx)나 ADR-004 4장 후속 표에는 항목이 없어 추적되지 않는다.
- [추론] D-061이 든 이유("순수 모듈은 벽시계를 읽지 못한다")는 장 시간 보호에만 맞다. 정지 요청 파일 확인은 파일이 있는지만 보면 되어 벽시계가 필요 없고, 순수 모듈에서도 할 수 있다. 다만 이 명령용 파일 경로와 감시의 생성 규칙은 D-058을 넓히는 일이라 risk-ops 결정이 필요하다.
- 지금 실전 엔진이 돌지 않으므로 코드 구현을 이 PR에 요구하지는 않는다. 요구 사항:
  1. 결정 로그 미확정 표에 Q 항목을 새로 등록한다(담당 risk-ops, sim-engine). 내용: 조합 실험 명령(과 같은 처지의 `runStrategyStudy`)을 5.2 0번에 넣을지, 넣는다면 정지 요청 파일 경로, 시작 전과 Run 사이 확인 지점, 7단계 실전 활성화 전 마감 조건.
  2. D-061 "위험과 한계"의 이유 문장을 위 [추론]에 맞게 고친다(정지 요청 파일 확인에는 벽시계가 필요 없다는 점, 남은 것은 D-058 확장 결정이라는 점).
  3. ADR-004 4장 후속 표에 같은 행을 넣거나 Q 번호로 링크한다.

### F2 (권고): 설정 폴더 규칙이 다른 프로세스와 다르다
- [사실] `ConfigPaths`(autotrade-config/.../ConfigPaths.java:27-33)와 수집기(CollectorMain.java:345)는 "시스템 속성 `autotrade.config-dir` → `AUTOTRADE_CONFIG_DIR` → ~/.autotrade" 순서를 쓴다. CombinationMain.java:50-53은 시스템 속성을 건너뛴다.
- 영향: 시스템 속성으로 설정 폴더를 바꾼 사용자는 이 명령이 다른 스냅샷 폴더를 본다. 폴더 이름과 manifest ID를 대조하므로(CombinationExperiment.java:93-96) 잘못된 데이터로 조용히 도는 일은 없고 "없음" 오류로 멈춘다. 시스템 속성도 읽게 하거나 `-PsnapshotsDir`만 쓰라고 문서에 적기를 권한다.

### F3 (권고): 권리 변동 뒤 거래량 단위가 가격 단위와 맞지 않는다
- [사실] 가격은 첫 일봉 단위로 잇지만(`price()`, DailyBarMarketDataSet.java:183-188), 거래량은 원 거래량 그대로 나눈다(같은 파일 144-145행). 1:2 분할 뒤에는 가격이 ×2인데 거래량은 분할 뒤 주식 수다.
- 영향: 시장가 체결은 틱 거래량으로 제한하지 않아(VirtualExchange.java:385-393) 지금 일봉 전략(`ma-trend`·`random-daily`, 둘 다 틱 거래량을 안 씀)에는 영향이 없다. 지정가 체결 상한(`tickLeft`, VirtualExchange.java:380·402)과 `SymbolState`의 거래량·VWAP에는 단위 불일치가 들어간다. `DAILY-PATH-V1`은 버전이 붙은 경로 모델이라 나중에 고치면 버전을 올려야 하므로, 지금 거래량에 역비율(`adjFactor(첫 일봉) ÷ adjFactor(D)`)을 곱하거나 D-061 한계에 적어 두기를 권한다.

### F4 (권고): 실험 ID 확인 전에 경로를 만든다
- [사실] CombinationMain.java:56은 `-Pexperiment` 값을 검증 없이 `experiments/<id>`로 합친다. `runAndWrite`가 폴더 이름과 `ExperimentId` 형식을 대조한 뒤에만 쓰므로(CombinationExperiment.java:204-206) 쓰기는 막히지만, `../x` 같은 값이면 `experiments` 밖의 `x/experiment.yaml`을 읽고 그 폴더에 결과를 쓸 수 있다. 로컬 명령이라 위험은 낮다. `new ExperimentId(id)`로 먼저 검증하기를 권한다.

### 확인한 사항 (문제 없음)
- 미래 정보: 가격 잇기 비율 `adjFactor(D) ÷ adjFactor(첫 일봉)`은 ADR-008 2.3 계수 정의(D 뒤 이벤트 비율의 곱)에서 첫 일봉과 D 사이 이벤트의 곱이 되어 D 뒤 권리 변동을 쓰지 않는다. `DailyBarMarketDataSetTest#권리_변동_뒤는_...`가 분할 뒤에 시작하면 원가격임을 확인한다. 고가→저가 순서는 종가와 무관하게 고정된다(DailyBarMarketDataSet.java:239-243). `MovingAverageTrend`는 하루 첫 판단에서 끝난 거래일 종가만 쓴다(MovingAverageTrend.java `decide`·`trendUp`).
- 실행기 변경: 연속 구간이면 `subList` 뷰, 아니면 예전처럼 거름(SimulationExperimentRunner.java:194-231). sequence 오름차순 검증은 전체 이벤트에 그대로 남고, 반환 목록은 읽기 전용이며 호출부(`selectionSchedule`, `benchmarkReturn`, Run)는 읽기만 한다. 연속 판정 `last != i - 1`이 맞다.
- 지연 이벤트 목록: `Events.get`은 불변 배열만 읽어 병렬 Run에서 안전하다. 거래일마다 행이 1개 이상이라 `dayEventStart` 이진 탐색이 겹치지 않는다(DailyBarMarketDataSet.java:154·230-234).
- 스냅샷 보관: 수집기 `SnapshotRetention.referencedIds`는 `experiments/` 아래 텍스트 파일에 스냅샷 ID가 나오는지만 보므로 `experiment.yaml`과 `inputs.yaml` 모두 보관 대상이 된다(SnapshotRetention.java:81-103).
- 결정 번호: 열린 PR #49가 D-060을 쓰고 있어(GitHub REST로 확인) D-061 사용이 맞다. 두 PR 모두 docs/decisions/README.md 표를 고치므로 나중에 머지하는 쪽은 충돌을 풀어야 한다.

## 판정
수정 필요(F1)
