# architect 검토: PR #12 3단계 실험실 MVP

> 보관 메모(메인 Claude 작성, 원문 아님): 이 검토는 프로젝트 지침 11장이 생기기 전인 2026-10-08에 했기 때문에 `docs/reviews/README.md` 형식이 아니다. 아래 "원문" 절은 검토 서브에이전트가 보낸 보고를 고치지 않고 그대로 옮긴 것이다. 대상은 커밋 7da04b2이고, 지적을 반영한 커밋은 5558dcf다(PR #12에 squash 머지되어 main 6459d6f). 재검토 결과는 검토자가 이 파일 아래에 `## 재검토 <날짜>`로 직접 덧붙인다.

## 원문 (2026-10-08)

## 판정: 수정 필요 (차단 1건. 나머지는 권장·참고라서 차단 1건만 고치면 승인)

검토 대상은 7da04b2이고, origin/main 대비 이 커밋 하나만 봤습니다. 소스만 읽었고 gradle은 돌리지 않았습니다. PR #10은 `gh pr diff 10`으로 읽었습니다.

**경계와 금지 패턴은 문제 없습니다**
- [사실] `autotrade-strategy`는 `api(project(":autotrade-domain"))`에만 의존합니다.
- [사실] sim 쪽에는 Spring, SDK, DB 의존이 없고 `now()`, double, Random, parallelStream도 없습니다(grep으로 확인).
- [사실] `VirtualExchange`는 domain의 `OrderGateway`만 구현하고 플러그인은 참조하지 않습니다.
- [사실] `Money`는 정규화된 값이라 `LedgerCheck`의 equals 비교가 안전합니다.
- [사실] `open`은 LinkedHashMap이고 Future는 순서대로 수집하므로 순회 순서가 정해져 있습니다.

**발견 사항**

1. [차단] VirtualExchange.java:313-327. 대기 중인 지정가 주문이 나중 스냅샷에서 교차될 때 반대편 호가 가격(`level.price()`)으로 체결됩니다. 지정가보다 유리한 가격이라 결과가 낙관적으로 나오고, ADR-004 2.2-3 "대기 체결가는 지정가"와도 어긋납니다. 실제 시장에서는 매도 호가가 대기 매수 지정가 이하로 내려오면 그 가격의 매수 잔량이 먼저 체결됩니다.
   - 수정안: 이미 대기에 들어간 주문(`queueAhead != null`)은 `fillPrice = limit`로 체결합니다.
   - 같은 가격(locked)은 즉시 체결로 보지 말고 앞선 잔량 규칙으로 처리합니다.
   - 즉시 교차 체결은 처음 유효해지는 스냅샷에서만 허용합니다.
   - 테스트를 하나 추가하고 골든을 갱신합니다.
2. [권장] ExperimentSpec, SimulationExperimentRunner.java:131-132. 체결 모델 ID가 실험 명세가 아니라 실행기 생성자에서 나옵니다. 그래서 PENDING 시점의 DB 행이 이 값을 알 수 없고, 명세만으로 재현할 수도 없습니다.
   - 수정안: `costModelId`처럼 `ExperimentSpec.executionModelId`를 추가하고 `validateAndSlice`에서 `execution.id()`와 맞는지 검사합니다. 계약이 바뀌므로 ADR-004에 적습니다.
   - 벤치마크 종목(:75, :111)도 지표에 영향을 주므로 결과에 함께 남깁니다.
3. [권장, PR #10 머지 시] VirtualExchange.java:131, SimulationExperimentRunner.java:205, Strategy.java:26. sealed 계층에 대한 exhaustive switch가 없어서 컴파일은 깨지지 않습니다(instanceof만 씀). PR #10의 `MarketRules` 인터페이스도 바뀌지 않아 TestKrxRules 그대로 동작합니다. 다만 동작 면에서 세 가지가 걸립니다.
   - (a) PR #10 `DataGap` 주석은 "구간에 걸친 체결·신호를 표시한다"고 하는데, 지금은 그냥 무시합니다. 갭 뒤에도 그 종목의 `queueAhead`와 `status`가 오래된 값으로 남습니다. 수정안: DataGap을 받으면 그 종목의 `queueAhead=null`, books 무효화, Run 보고서에 갭 표시를 넣습니다.
   - (b) DataGap의 `exchangeTime`은 시스템 시각(=gapEnd)입니다. 그런데 거래일 경계와 slice가 이 값을 씁니다. 수정안: 거래일 판정에서 DataGap은 제외합니다.
   - (c) `Strategy` Javadoc에 "알 수 없는 이벤트 타입(예: DataGap)도 전달되며 무시해도 된다"를 명시합니다.
4. [권장] VirtualExchange.java:185-189. `endOfDay`가 `status`/`books`를 비우지 않습니다. 장 마감 때 VI·정지 상태였던 종목은 다음 날 상태 이벤트가 올 때까지 체결되지 않습니다. 다음 날 첫 스냅샷이 오기 전 시장가 매수의 예약 기준가도 전날 매도 호가를 씁니다. 수정안: 마감 때 status와 books를 비우거나, 이 동작을 계약으로 명시합니다.
5. [권장] VirtualExchange.java:148-149, 269 vs 407. 시간 기준이 섞여 있습니다.
   - `activeFrom`은 `clock.instant()`(지금까지의 최댓값) 기준인데 `signalTime`은 `currentReceivedAt` 기준입니다.
   - 예약 날짜는 `receivedAt`, 체결 날짜는 `exchangeTime`으로 고릅니다.
   - 수정안: 유효 시점은 `currentReceivedAt + latency` 하나로 통일하고(ADR 문구와 일치), 비용 구간 날짜는 `exchangeTime` 하나로 통일합니다.
6. [참고] SimulationExperimentRunner.java:190-193. `profileHash`, `RunSeeds`, `new VirtualAccount`가 try 밖에 있습니다. 여기서 예외가 나면 실험 전체가 실패합니다. 지금 입력으로는 불가능하지만 try 안으로 옮기는 편이 격리 계약에 맞습니다.
7. [참고] SimulationExperimentRunner.java:258-262. `CASH_SHORT_AT_FILL` 거절이 실제 취소 이벤트가 아니라 하루 마지막 seq로 기록됩니다. 거래소에서 취소 seq를 받아 쓰면 됩니다.
8. [참고] VirtualExchange.java:386-390. 같은 가격에 우리 주문이 둘 이상 있으면 두 번째 주문의 앞선 잔량에 첫 번째 주문이 들어가지 않습니다. 소액 전제라 영향은 작습니다. 주석으로 한계를 남기면 됩니다.
9. [참고] VirtualExchange.java:326, CostModel.fees. 시장가가 호가 여러 단계를 먹으면 단계마다 Fill이 생기고 비용도 따로 계산됩니다. 체결 건 단위 규칙과는 맞지만, 앞으로 주문당 최소 수수료가 있는 프로필이 생기면 과다 계산됩니다. 그때 Levy에 주문 단위 옵션이 필요합니다.
10. [참고] CostModel.java:29-30. `segmentOf`가 해시에 들어가지 않아 소속부 판별이 달라도 `costTableHash`가 같습니다. 문서에는 적혀 있지만, Q-012가 해결될 때 판별 데이터 버전을 해시나 데이터 스냅샷 ID에 포함해야 합니다.
11. [참고] 새 계약의 Javadoc
    - `Strategy`(Run 하나 전용, 단일 스레드, 결정론), `StrategyFactory`(스레드 안전), `TradeSignal` 검증은 충분합니다.
    - `CostModel`의 "불변·스레드 안전" 계약에 "주입한 `segmentOf`도 순수·스레드 안전해야 한다"를 추가합니다.
    - VirtualAccount의 원가 계산은 확인했습니다. 마지막 매도가 남은 원가를 모두 가져가고, 원장 재계산 결과와 현금 계산 순서가 같습니다.

**DB 열과 설정 키에 대한 의견**
- `experiment.execution_model_id VARCHAR(64) NOT NULL`: 받아들일 수 있습니다. 2번처럼 ExperimentSpec에 넣는 것과 함께 진행하길 권합니다.
  - V2 마이그레이션에서는 기존 행을 고려해 `ADD COLUMN ... NOT NULL DEFAULT 'BOOK-V1;latency=PT0.5S'`로 넣고 곧바로 `DROP DEFAULT` 합니다.
  - 비교 조회 조건은 (data_snapshot_version, cost_table_hash, execution_model_id)가 같은 것으로 명시합니다.
- 비용 설정 키(`cost.profiles.*`)를 뒤로 미루는 것: 받아들일 수 있습니다. 코드 기본값이어도 `CostModel.id()`가 내용 해시를 포함하므로 재현성이 유지됩니다. 키를 추가할 때는 ADR, 그리고 기본값·범위·실시간 변경 불가(실험 시작 시 고정)를 config-keys.md에 함께 적어야 합니다.

**검증하지 못한 것**: 빌드와 테스트는 실행하지 않았습니다(지시에 따름). 1번 수정 뒤 골든 스냅샷이 얼마나 바뀌는지도 확인하지 못했습니다.

관련 파일
- /home/claude/autotrade-system/autotrade-sim/src/main/java/com/autotrade/sim/exchange/VirtualExchange.java
- /home/claude/autotrade-system/autotrade-sim/src/main/java/com/autotrade/sim/run/SimulationExperimentRunner.java
- /home/claude/autotrade-system/autotrade-sim/src/main/java/com/autotrade/sim/ExperimentSpec.java
- /home/claude/autotrade-system/autotrade-strategy/src/main/java/com/autotrade/strategy/Strategy.java
- /home/claude/autotrade-system/autotrade-sim/src/main/java/com/autotrade/sim/cost/CostModel.java
- /home/claude/autotrade-system/docs/adr/ADR-004-simulation-engine.md

## 재검토 2026-10-09

# architect 검토: PR #12 3단계 실험실 MVP (재검토)
- 대상: PR #12 / squash 머지 커밋 6459d6f / `git diff ae4343d 6459d6f` 전체(59개 파일, +4025 −5). 앞선 검토 대상 7da04b2, 지적 반영 커밋 5558dcf. 작업 트리는 origin/main(0ea575d, 이후 PR #14·#8·#15 포함)이며 파일은 작업 트리 최신본을 직접 읽었다.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → BUILD SUCCESSFUL (EXIT=0, 1m 30s)
  - build에서 sim·strategy 테스트가 UP-TO-DATE(캐시)였으므로 `./gradlew :autotrade-sim:cleanTest :autotrade-sim:test :autotrade-strategy:cleanTest :autotrade-strategy:test`로 다시 실행 → BUILD SUCCESSFUL. 결과 XML 집계: autotrade-sim 59개(건너뜀 0, 실패 0), autotrade-strategy 25개(건너뜀 0, 실패 0). autotrade-architecture-test 12개(실패 0)는 build에서 실제 실행됨
  - diff에 대한 grep: `now(`, `new Random`, `Math.random`, `double`/`float`, `parallelStream`, `currentTimeMillis`, `nanoTime`, `@Disabled`, 비밀값 키워드(appkey, secret, password, token, 계좌번호)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, BUILD SUCCESSFUL. sim·strategy 테스트는 cleanTest 후 재실행해 59+25개 전부 통과 |
| B2 | 통과 | autotrade-strategy/build.gradle.kts는 `api(project(":autotrade-domain"))`만. autotrade-sim/build.gradle.kts 변경은 `tasks.test`의 `golden.update` 시스템 속성 전달뿐(의존 추가 없음). diff는 autotrade-architecture-test를 건드리지 않았고 ArchUnit 12개 통과. sim 코드에 Spring·SDK·DB 의존 없음(diff grep) |
| B3 | 통과 | 새 계약 `Strategy`, `StrategyContext`, `StrategyFactory`, `TradeSignal`, `ExperimentResult.executionModelId`, `RunResult.metrics`가 docs/adr/ADR-004-simulation-engine.md에 정의되고 docs/adr/README.md:9에 등재. 설정 키는 추가하지 않았고 docs/config/config-keys.md 32-33행에 보류 사유와 담당을 적음. 결정은 D-025(docs/decisions/README.md). DB 스키마·REST 변경 없음. 단 ADR-004 상태가 머지 뒤에도 "제안"으로 남아 있음(점검표 밖 발견 3) |
| B4 | 통과 | diff에 `now()`·시드 없는 난수·double 계산·parallelStream 없음. 시각은 `ReplayClock`(SimulationExperimentRunner.java:198, :211), 매수 체결 비용 날짜는 `rules.tradingDate(e.exchangeTime())`(VirtualExchange.java:423). 결과 순회는 LinkedHashMap `open`(VirtualExchange.java:76), TreeMap `dailyOrders`(VirtualAccount.java:45). HashMap `takenAsk/takenBid`(VirtualExchange.java:302-303)는 순회하지 않고 조회만 함. 골든 테스트 `SimulationExperimentRunnerTest#골든_스냅샷과_정확히_일치한다()` 재실행 통과 |
| B5 | 통과 | 금액은 `Money`(BigDecimal+통화), 가격 BigDecimal, 수량 long. 요율 문자열→BigDecimal(diff 1030행 주석, cost 패키지), 지표 로그·지수도 `DecimalMath`로 BigDecimal 계산 |
| B6 | 통과 | 새 동작 테스트: VirtualExchangeTest, VirtualAccountTest, CostModelTest, MetricsCalculatorTest, RunSeedsTest, SimulationExperimentRunnerTest, TradeSignalTest. 앞선 차단 지적 1의 회귀 테스트 3개 존재·통과: `대기_중이던_지정가가_나중_호가와_교차하면_지정가로_체결된다()`, `대기_중인_지정가와_같은_가격의_반대편_호가는_즉시_체결이_아니다()`, `표시_범위_밖_지정가는_그_가격이_표시될_때까지_앞선_잔량을_정하지_않는다()`. diff의 삭제 줄 5개는 모두 시그니처·주석 교체이고 테스트 삭제 없음. sim·strategy에 `@Disabled`·`assumeTrue` 없음 |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트 구현, 리스크 한도, 증권사 연동 파일을 바꾸지 않음. `VirtualExchange`는 domain `OrderGateway`의 가상 구현이고 플러그인을 참조하지 않음. 리스크 정책은 호출만 함(SimulationExperimentRunner.java:243, 테스트 `리스크_정책은_모든_모의_주문에_적용된다()`) |
| B8 | 통과 | diff 전체에서 비밀값 키워드 grep 결과 없음. 테스트 픽스처(krx-golden.events)는 종목코드·가격·수량뿐. 예외 메시지에 계좌 ID 외 비밀값 없음 |

### 앞선 지적의 처리 상태
- 1 [차단] 대기 지정가의 교차 체결가: **해결**. VirtualExchange.java:321-323(대기 중 같은 가격은 교차로 보지 않음), :330(대기 주문은 지정가로 체결), :331(충격 0), :338(첫 유효 스냅샷 뒤 `resting=true`, 즉시 교차는 그 전에만). ADR-004 2.2-3에 반영. 골든 스냅샷은 바뀌지 않았고 재실행 통과(골든 데이터에 대기 후 교차 사례가 없다는 뜻이며, 회귀는 위 단위 테스트 3개가 맡음).
- 2 [권장] 체결 모델 ID: 일부 반영. `ExperimentResult.executionModelId`로 결과에는 남지만 `ExperimentSpec`에는 아직 없음. ADR-004 4장 후속 과제 표에 담당(sim-engine, architect)과 시점(DB 저장 작업과 함께)이 기록됨.
- 3 [권장] DataGap: 3(c)는 Strategy.java Javadoc에 반영. 3(a)(b)는 ADR-004 4장 후속 과제(PR #10 머지 후, sim-engine)로 기록.
- 4·5 [권장] 장 마감 상태 초기화, 시간 기준 통일: 미반영. ADR-004 4장 후속 과제로 기록(`RULES_VERSION` 상향과 함께). VirtualExchange.java:149는 여전히 `clock.instant()` 기준.
- 6·7·8·9 [참고]: 8은 주석 반영(VirtualExchange.java:402). 6·7·9는 미반영, 차단 아님.
- 10·11 [참고]: CostModel Javadoc에 `segmentOf` 순수·스레드 안전 조건 추가(5558dcf). Q-012 해시 포함은 후속.

## 점검표 밖 발견
1. [참고] VirtualExchange.java:298-299, :338. `resting`은 거래 가능한 호가 스냅샷을 한 번 지나야 켜진다. 지정가가 유효해진 뒤 첫 스냅샷이 VI·정지 중이라 `matchBook`이 바로 return하거나, 체결 틱만 먼저 지나간 경우에는 이미 기다린 주문인데도 다음 스냅샷에서 반대편 호가 가격(지정가보다 유리)으로 즉시 교차 체결된다. 빈도는 낮고 영향은 앞선 차단 지적과 같은 방향(낙관)이다. 수정안: `resting`을 "유효해진 뒤 처음 본 이벤트"에서 켜거나, 상태 비거래로 건너뛴 스냅샷에서도 켠다. 4·5번 후속(`RULES_VERSION` 상향)과 함께 처리하길 권한다. 차단 아님.
2. [참고] 위 4·5번이 실제 녹화 데이터 첫 실험 전에 처리되지 않으면 VI 상태가 다음 거래일까지 이어질 수 있다. ADR-004 4장에 시점이 "RULES_VERSION 상향과 함께"로만 되어 있어, 4단계 비교 실험 결과를 해석할 때 이 한계를 명시해야 한다.
3. [참고] docs/adr/README.md:9와 ADR-004 머리말 상태가 머지 뒤에도 "제안"이다. 채택으로 바꾸거나 남은 후속 과제 때문에 제안으로 두는 이유를 적는다.

## 판정
승인
