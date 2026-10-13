# ADR-004 시뮬레이션 엔진: 전략 실행 계약, 호가 기반 가상 체결, 비용 모델, 실험 실행기, 성과 지표

- 상태: 제안 (sim-engine). 교차 검토: architect(계약·모듈 경계), strategy-researcher(체결 가정·지표 정의)
- 날짜: 2026-10-08
- 단계: 로드맵 3단계 실험실 MVP(team-design.md 6장)
- 기획서: v12 SIM-01~15, OVS-02·03·06, TST-02. v13(PR #8) D-023 공통 원칙 3("가상 엔진도 같은 규칙")은 4장 후속으로 둔다
- 근거 결정: D-003(증권사 모의투자 미사용), D-004(다중 모의 계좌), D-019(불변 데이터 스냅샷), D-021(시드·비용 표), D-025(이 ADR의 자율 채택 항목)
- 번호: ADR-002는 main의 RealTradingGuard와 PR #10(시세)이 같은 번호를 쓰고 있어, 3번은 그쪽 정리에 남기고 4번을 쓴다
- 영향 역할: strategy-researcher(전략 구현은 `Strategy`로), risk-ops(검증 체인 연결), data-engineer(녹화 어댑터, Q-012), ui-dev(결과 표시), architect(DB 열·설정 키 제안)

## 1. 배경

[사실] 1단계 뼈대(PR #7)는 `ExperimentRunner`·`ExperimentSpec`·`RunResult` 계약만 두고 구현을 3단계로 넘겼다(`autotrade-sim/.../ExperimentRunner.java`). `StrategyProfile`은 데이터만 있고 "실행 로직은 3·4단계에서 정의"로 남아 있었다.
[사실] 비용은 D-021과 Q-004 조사(docs/research/q-004-trading-costs.md)가 "적용 시작일이 있는 표, 체결 건 단위 항목별 반올림"으로 정했다.
[사실] 이 ADR 작성 당시 2단계 시세(PR #10)는 열려 있었고, 2026-10-09 main에 머지됐다(`3ffa851`). 이 ADR의 실행기는 시세 소스 대신 불변 이벤트 목록(`MarketDataSet`)을 받고, 테스트는 손으로 만든 녹화 데이터를 쓴다. #10 머지 후 녹화 형식 어댑터를 더한다(4장).

## 2. 결정

### 2.1 전략 실행 계약 (`autotrade-strategy`)

| 타입 | 역할 |
|---|---|
| `Strategy` | `List<TradeSignal> onEvent(MarketEvent, StrategyContext)`. Run 하나 전용, 단일 스레드. 예외는 그 Run만 실패 |
| `StrategyContext` | `now()`, `account()`(AccountSnapshot), `hasOpenOrder(Symbol)`. 읽기 전용 |
| `TradeSignal` | 종목·방향·의도·유형·지정가·수량. OrderRequest와 같은 검증(공매도 없음) |
| `StrategyFactory` | `create(profile, runSeed)`. 스레드 안전. 모르는 프로필은 `IllegalArgumentException` → 그 Run만 FAILED |

멱등 키는 전략이 아니라 실행기가 붙인다. 전략이 키를 만들면 "새 키는 발급기만 만든다"(IdempotencyKey 주석)를 어기게 된다.

### 2.2 가상 체결 규칙 `BOOK-V3` (`autotrade-sim`, `exchange.VirtualExchange`)

`BOOK-V1`(1~10번)에 시세 공백 처리(11번)를 더한 것이 `BOOK-V2`다([D-068](../decisions/D-068-recording-adapter-gap-handling.md)). 공백이 없는 데이터의 결과는 두 버전이 같다. 여기에 매매 단계(12번)를 더한 것이 `BOOK-V3`다([D-069](../decisions/D-069-session-phases-recording-experiment.md)). 단일가 구간이 없는 규칙(정규장 내내 접속 매매)으로 돌린 결과는 BOOK-V2와 같다.

`VirtualExchange`는 계좌 하나의 `OrderGateway` 가상 구현이다. 증권사 주문 API를 호출하지 않는다(SIM-01, ArchUnit `시뮬레이션은_증권사_플러그인을_모른다`).

1. **유효 시점**: 주문은 신호 이벤트(T)보다 sequence가 크고, 수신 시각이 `신호 수신 시각 + orderLatency` 이상인 이벤트부터 체결에 쓴다. `Fill` 생성자가 `fillEventSeq > signalEventSeq`를 강제한다(미래 정보 금지).
2. **시장가**: 유효해진 뒤 첫 호가 스냅샷의 반대편을 최우선부터 잔량만큼 소진. 남은 수량은 다음 스냅샷에서 이어서. 같은 스냅샷에서 이 계좌의 앞선 주문이 먹은 잔량은 빼고 본다.
3. **지정가**: 유효해질 때 지정가 이내 반대편 호가만큼 즉시 체결. 남은 수량은 대기하며 같은 가격의 기존 잔량을 "앞선 잔량"으로 둔다. 그 가격 체결(TradeTick)이 앞선 잔량부터 줄이고 넘친 만큼 체결. 지정가보다 유리한 가격의 체결은 앞선 잔량과 무관하게 체결. 스냅샷에서 그 가격 잔량이 앞선 잔량보다 작아지면(취소) 앞선 잔량도 줄인다. 대기 체결가는 지정가. 지정가가 표시된 호가 범위 밖이면 앞선 잔량을 정하지 않고, 그 가격이 처음 표시될 때 그 잔량으로 정한다. 정해지기 전에는 지정가보다 유리한 체결로만 체결한다. 대기 중이던 주문이 나중 스냅샷에서 반대편 호가와 교차하면 지정가로 체결하고, 같은 가격(locked)은 교차로 보지 않는다(교차 검토 반영).
4. **호가가 없는 종목**(체결 데이터만): 시장가는 다음 체결가 × (1 ± 슬리피지율)을 호가 단위로 불리하게 올림·내림한 가격으로 전량, 지정가는 유리한 체결이 나올 때만 지정가로(보수적).
5. **거래 상태**: TRADING이 아니면(VI, LULD, 정지) 체결 없음, 주문은 유지(OVS-06, 장애 매트릭스 #13).
6. **접수 검증**: 시장 불일치, 수량 단위, 호가 단위(`MarketRules.roundToTick`), 보유 − 미체결 매도 초과, 예약 현금 부족, 시장가 매수의 기준 가격 없음 → REJECTED(사유 코드 `OrderRejectedReason`). 같은 멱등 키 재요청은 새 주문 없이 `duplicateKey=true`.
7. **현금**: 매수는 접수 시 `기준가 × 수량 + 매수 비용`을 예약하고, 체결 직전 `현금 − 다른 주문 예약`으로 살 수 있는 수량까지만 체결, 모자란 잔량은 취소(`CASH_SHORT_AT_FILL`).
8. **당일 유효**: 거래일이 바뀌면 남은 주문 소멸. 주문 상태는 `EXPIRED`(ORD-07, 2026-10-09 QA 보고서 A14 반영 전에는 `CANCELLED`), 사유 코드 `DAY_END`. 체결 직전 현금 부족 잔량 정리(7번)와 `cancel` 호출은 `CANCELED`.
9. **슬리피지 기록**: 호가 기반 체결은 체결가에 이미 시장 충격이 들어 있으므로 D-021의 고정 0.15%를 더하지 않는다. 기록용 `slippage` 열에는 최우선 호가 대비 더 먹은 금액을 남긴다. 고정 슬리피지는 4번(호가 없는 데이터)에만 쓴다.
10. **주문 지연 기본값 0.5초** `[가정]`. 체결 규칙 버전과 지연은 `ExecutionSettings.id()`(예: `BOOK-V2;latency=PT0.5S`)로 결과에 남기고 같은 ID끼리만 비교한다.
11. **시세 공백**(`DataGap`, 원인 무관, RECORDER_RESTART 포함): 그 종목의 마지막 호가 스냅샷을 체결과 시장가 매수 예약에 쓰지 않고, 그 종목 대기 지정가의 앞선 잔량을 "모름"으로 되돌린다. 공백 자체로는 체결하지 않는다. 다음 스냅샷 전까지 그 종목 시장가는 체결되지 않고 대기 지정가는 지정가보다 유리한 체결로만 체결되며, 새 스냅샷의 같은 가격 잔량으로 앞선 잔량을 다시 잡는다. 주문은 남기고, 평가 가격(마지막 체결가, 없으면 마지막 스냅샷 최우선 매수 호가)과 거래 상태는 그대로 쓴다.
12. **매매 단계**(`MarketRules.sessionPhase`, 이벤트의 거래소 시각 기준): 접속 매매(`CONTINUOUS`) 이벤트로만 1~11번대로 체결한다. 단일가 접수 구간(`OPENING_AUCTION`, `CLOSING_AUCTION`) 이벤트는 체결에 쓰지 않고(예상 호가), 그때 유효한 그 종목 미체결 주문을 그 단일가 참여 주문으로 표시한다. 구간이 끝난 뒤 그 종목 첫 체결(시가는 접속 매매 첫 체결, 종가는 폐장 뒤 첫 체결)을 단일가 체결로 보고 참여 주문을 접수 순서대로 그 체결가 하나로, 그 체결 수량 안에서 체결한다(시장가는 무조건, 지정가는 체결가가 지정가 이내일 때, 슬리피지 0). 시가 단일가에서 남은 수량은 접속 매매 주문으로 이어지고 종가 단일가에서 남은 수량은 장 마감에 만료된다. 장 밖(`CLOSED`) 이벤트로는 단일가 체결 말고는 체결하지 않는다. 단일가 구간 중 그 종목 시세 공백이 오면 참여 표시를 지운다. 시가 단일가로 여는 시장(`MarketRules.opensWithAuction`, KRX)은 단일가 접수 마감이 임의로 늦춰지므로(랜덤 엔드 `[가정]`), 개장 시각이 지나도 그 종목 그날 첫 체결 전까지의 이벤트를 시가 단일가 접수로 본다: 호가로 체결하지 않고, 그 사이 새 주문도 참여하며, 첫 체결 직전까지 유효해진 주문은 모두 그 체결가로 체결된다. 그 사이 시세 공백이 오면 열린 것으로 보고 공백 뒤 새 호가부터 접속 매매로 체결한다. 이벤트 시각이 합성인 데이터(`MarketDataSet.exchangeTimed() == false`, 일봉 경로)는 매매 단계를 보지 않는다(BOOK-V2와 같음): 칸 시각이 그날 실제 개장 시각을 따르지 않고 캘린더 수록 범위 밖 과거도 있기 때문이다.

### 2.3 비용 모델 (`autotrade-sim`, `cost`)

- `CostModel` = 비용 항목(`Levy`) 목록 + 체결가 기반 체결용 슬리피지율 + 세그먼트 판별 함수.
- 항목 종류는 두 가지: `RateLevy`(체결 금액 × 세그먼트별 요율, 국내 수수료·거래세·농특세, 미국 수수료·SEC fee)와 `PerShareLevy`(주당 부과액, 체결 건당 상한, FINRA TAF). 항목마다 적용 방향(매수·매도·양쪽), 반올림 방식, 적용 시작일이 있는 표(`PeriodTable`)를 갖는다.
- 체결 엔진은 항목 종류를 분기하지 않는다. 시장 차이는 항목 구성(`CostModels.krDefault/usDefault/usToss`)으로만 나타난다(OVS-02).
- 계산: 체결 건 하나마다, 항목마다 계좌 통화 소수 자릿수로 따로 반올림(국내 원 미만 절사, 미국 수수료 HALF_UP·SEC CEILING·TAF HALF_UP). 구간은 체결 이벤트의 거래소 현지 날짜로 고른다(Q-004 조사 4.3).
- 표 검증: 만들 때 정렬·겹침·빈틈·열린 구간 위치를 검사해 어기면 예외(로드 실패). 실험 기간이 표 밖이면 실행기가 실험 시작을 거부한다(SIM-07).
- ID·해시: `id()` = 프로필 ID + 표 전체 정규화 SHA-256 앞 12자(예: `KR-DEFAULT-xxxxxxxxxxxx`)로 내용이 바뀌면 바뀐다(`ExperimentSpec.costModelId`). `tableHash(from, to)`는 실험 기간과 겹치는 구간만 넣어, 미래 구간이 추가돼도 과거 실험 해시가 그대로다(D-021 3항).
- 거래 기록 열: 수수료는 `commission`, 국내 거래세·농특세와 미국 SEC fee·TAF는 `tax`에 넣는다(virtual_trade_log에 규제 수수료 열이 없음). 항목별 금액은 `FeeBreakdown.items()`에 있다.
- 국내 소속부(KOSPI·KOSDAQ·KONEX)는 주입한 판별 함수로 정한다. 시점별 이력은 Q-012(data-engineer)이고, 판별 함수는 해시에 들어가지 않는다.
- 표 시작일: 국내 2025-01-01, 미국 2026-01-01(TAF 표가 그때부터 확인됨). 더 이전 데이터 실험은 연혁 확인 후 표에 구간을 더한다.

### 2.4 실험 실행기 (`run.SimulationExperimentRunner`)

- 입력: `MarketDataSet`(불변 이벤트 목록과 스냅샷 ID. 구현은 테스트용 `InMemoryMarketDataSet`, 일봉 `DailyBarMarketDataSet`(D-061), 2단계 녹화 `RecordedMarketDataSet`(D-068)), `MarketRules`, `CostModel`, `StrategyFactory`, `RiskChain`(D-032, 승인만 하는 정책은 넣을 수 없음), `ExecutionSettings`, `CodeVersion`, 병렬 수, 벤치마크 종목(선택).
- 시작 검증(어기면 Run 없이 `IllegalArgumentException`): `clockMode == REPLAY`(실시간 모의는 6단계), 데이터 스냅샷 ID 일치, 비용 모델 ID 일치, 시장 일치, 비용 표 범위, 이벤트 sequence 오름차순.
- Run마다 새로 만드는 것: `VirtualAccount`, `VirtualExchange`, `ReplayClock`, `Strategy`, 멱등 키 발급기. 공유하는 것은 불변 입력과 스레드 안전 계약을 가진 팩토리·정책뿐이다. 그래서 병렬 수가 결과에 영향을 주지 않는다(테스트로 확인).
- 이벤트 처리 순서: (거래일이 바뀌면 전날 마감) → 시계 이동 → 거래소가 기존 주문 체결 → 전략 신호 → 신호마다 키 발급 → `RiskChain` → 거래소 접수.
- 거래일 마감: 남은 주문 취소, 자산 곡선 한 점(평가 가격은 마지막 체결가, 없으면 최우선 매수 호가, 없으면 원가), 계좌 불변식 검사(SIM-15, `LedgerCheck`). 위반이면 그 Run만 FAILED.
- 녹화 실험 명령(`recording.RecordingExperiment`, D-069): `runRecordingExperiment`가 등록 파일(`input: "recording"`)의 기간·프로필·소속부·제외 종목으로 실녹화를 실제 `KrxMarketRules` 위에서 돌리고, `inputs.yaml`에 녹화일별 입력 품질(`DayInfo`)과 `detail.txt` SHA-256을 남긴다.
- 녹화 입력(`recording.RecordedMarketDataSet`, D-068): 기간 안 녹화일을 날짜 오름차순으로 잇는다. sequence는 녹화일 → 녹화 순번 순의 데이터셋 위치(1부터)이고 `origin(sequence)`가 (녹화일, 녹화 순번)을 돌려준다(끼워 넣은 줄은 0). 리플레이 소스의 "sequence = 녹화 순번"(ADR-003 2.4)은 그대로다. 공백 표시 없는 이전 녹화의 재기동 경계에는 녹화기와 같은 규칙으로 `RECORDER_RESTART` 공백을 끼운다. 버전은 `REC-<시장>-<첫 날>..<끝 날>-<해시 16자>`(어댑터 버전 `REC-ADAPTER-V1`, 시장, 날짜별 events 파일 SHA-256)이고, 읽는 동안 파일이 바뀌면 열지 않는다.
- 식별자: `runId = accountId = <실험ID>-A01, -A02 …`(프로필 목록 순서).
- Run 시드: `SHA-256("run-seed-v1|" + randomSeed + "|" + profileHash)` 앞 8바이트(big-endian long). 바꾸면 접두어 버전을 올린다.
- 실패 격리: Run 안의 모든 `RuntimeException`은 그 Run의 FAILED와 사유(클래스명 + 메시지, 500자)로 끝나고 다른 Run은 계속 돈다.
- 결과: `ExperimentResult`(코드 커밋·dirty, 비용 표 해시, **체결 모델 ID**, Run 요약)와 `RunReport`(체결 원장, 자산 곡선, 왕복 거래, 거절 기록). `SimulationReport.dataGaps()`는 실험 기간 입력의 공백 표시(모든 Run 공통)이고, 정규 보고서 머리에 `data-gap seq=… <종목> <원인> <시작>..<끝>` 줄로 남는다(공백이 없으면 줄 없음). `ReportWriter.render`는 실행 시각 같은 메타 필드가 없는 정규 텍스트로, 골든 스냅샷과 `experiments/<실험ID>/` 요약에 쓴다.

### 2.5 성과 지표 정의 (`metrics.MetricsCalculator`, SIM-11)

비율은 소수(0.05 = 5%), 소수 6자리 HALF_EVEN. 계산은 BigDecimal(40자리)로만 하고, CAGR의 거듭제곱은 BigDecimal 로그·지수(`DecimalMath`)로 한다(double 미사용, 결정론).

| 지표 | 정의 | 정의 불가 시 |
|---|---|---|
| 총수익률 | 최종 평가금액 / 시드 − 1 | |
| CAGR | (최종/시드)^(365 / 실험 달력 일수) − 1 | 최종 ≤ 0이면 −1 |
| MDD | 일별 평가금액의 고점(시드 포함) 대비 최대 하락률, 양수 | |
| 샤프 | 일별 수익률 평균 / 표본 표준편차 × √252, 무위험 수익률 0 `[가정]` | 거래일 2일 미만, 변동 0 → null |
| 승률 | 수익 왕복 / 왕복 수 | 왕복 0 → null |
| 손익비 | 수익 왕복 손익 합 / \|손실 왕복 손익 합\| | 손실 왕복 0 → null |
| 평균 보유일 | 왕복의 진입일~청산일 달력 일수 평균(당일 0) | 왕복 0 → null |
| 거래 수 | 체결 건수와 왕복 수를 따로 | |
| 회전율 | (매수 금액 + 매도 금액) / 2 / 평균 일별 평가금액 | |
| 비용 | 수수료 + 세금·규제 수수료. 슬리피지는 체결가에 들어 있어 빼고 센다 | |
| 비용 비중 | 총비용 / (순손익 + 총비용) | 비용 전 손익 ≤ 0 → null |
| 벤치마크 대비 | 총수익률 − 벤치마크 종목 매수 후 보유 수익률(첫 체결가 → 마지막 체결가, 비용 제외) | 벤치마크 없음 → null |

왕복 거래 = 한 종목 보유가 0에서 다시 0이 될 때까지(분할 매수·매도 포함). 매도 체결의 실현 손익은 매수 비용을 포함한 평균 원가로 계산한다.

## 3. 대안 비교

| 선택 | 대안 | 판단 |
|---|---|---|
| 실행기 입력 = 불변 이벤트 목록(`MarketDataSet`) | A. Run마다 `MarketDataSource`를 구독 | 버림. 계좌 수만큼 리플레이가 돌고, 소스 스레드 모델이 결과 순서에 끼어든다. B(채택)는 한 번 읽고 같은 목록을 모든 Run이 독립적으로 순회해 결정론과 병렬화가 쉽다. 실시간 모의(6단계)는 같은 처리 순서를 라이브 이벤트에 붙이는 별도 실행기로 한다 |
| 지정가 대기: 앞선 잔량 모델 | A. 지정가 닿으면 즉시 전량 체결 | 버림. 대기 주문 체결을 크게 과대평가한다(team-design.md 3장 "앞선 잔량 소멸 후 체결"과도 다름) |
| 우리 주문의 시장 영향은 스냅샷 사이에 이어지지 않음 | A. 먹은 잔량을 다음 스냅샷까지 빼 둠 | 버림(이번 판). 다음 스냅샷은 이미 실제 시장 상태라 차감 기준이 모호하고, 개인 계좌 규모(시드 1,000만 원)에서는 영향이 작다 `[추론]`. 큰 주문 전략이 나오면 되돌린다 |
| 호가 기반 체결에 고정 슬리피지를 더하지 않음 | A. D-021 0.15%를 항상 더함 | 버림. 호가를 먹는 가격에 이미 스프레드·충격이 들어 있어 이중 계산이다. 고정 슬리피지는 호가 없는 데이터에만 쓴다(D-025) |
| 비용을 `sim.cost`의 항목 목록으로 | A. Q-004 조사 4.4 제안대로 세금은 `MarketRules`, 수수료는 `BrokerFeeSchedule` | 버림(이번 판). `MarketRules`에 메서드를 더하면 PR #10의 `KrxMarketRules`·`UsMarketRules`를 모두 고쳐야 하고, 비용 표는 실험실 안에서만 쓴다(Q-004 4.2 "실전 엔진은 비용 표에 의존하지 않는다"). 항목 목록 방식도 엔진 코드에 시장 분기가 없다 |
| Run 시드 = SHA-256 파생 | A. `new SplittableRandom(seed).split()`을 순서대로 | 버림. 프로필 순서가 바뀌면 시드가 바뀐다 |
| 지표 계산 BigDecimal | A. double | 버림. 플랫폼·JIT에 따라 마지막 자리가 달라질 수 있어 골든 비교가 깨진다. 프로젝트 규칙(ARC-08)과도 맞춘다 |

## 4. 이 PR에 넣지 않은 것 (후속 작업)

| 항목 | 담당 | 비고 |
|---|---|---|
| 녹화 형식 어댑터(`RecordedDay` → `MarketDataSet`)와 같은 골든을 녹화 형식으로 다시 돌리는 테스트 | sim-engine | **[D-068](../decisions/D-068-recording-adapter-gap-handling.md)으로 끝남**(`RecordedMarketDataSet`, 녹화 형식 골든 바이트 일치). 실험 명령의 녹화 입력 옵션은 **[D-069](../decisions/D-069-session-phases-recording-experiment.md)로 끝남**(`runRecordingExperiment`). 3단계 완료 기록(결정 로그)은 독립 QA 판정 뒤에 한다 |
| 실제 `KrxMarketRules`·`UsMarketRules`로 실행 | sim-engine | **KRX는 녹화 실험(D-069)과 조합 실험(D-061)에서 끝남.** US는 미국 녹화·일봉이 생기면. 골든 테스트는 같은 호가 단위표의 테스트 규칙을 쓴다 |
| 검증 체인(블랙리스트 → 리스크 → 멱등 키 → 상태머신)을 가상·실전이 공유(SIM-12), ORD-06 신호 경과 폐기, ORD-07 상태머신 | risk-ops | 실행기는 공통 체인 `RiskChain`(D-032)만 받아 모든 모의 주문에 적용한다(블랙리스트·리스크 단계 반영됨, 멱등 키·상태머신 단계는 남음). 가상 거래소 상태는 domain `OrderState` 값만 쓴다 |
| 일봉(다음 날 시가)·분봉(다음 봉 시가) 체결 해상도(SIM-06) | sim-engine | **일봉은 [D-061](../decisions/D-061-daily-replay-combination-runner.md)으로 끝남**(`DailyBarMarketDataSet`, 경로 모델 `DAILY-PATH-V1`). 분봉은 분봉 데이터가 생기면 |
| 실험 단발 명령(`runCombinationExperiment`, `runStrategyStudy`)의 장 시간 보호·정지 요청 파일(D-058) | risk-ops, sim-engine | **[D-063](../decisions/D-063-experiment-command-stop-gate.md)으로 끝남**(`autotrade-app-experiment`, `RunGate`). 감시 쪽 파일 생성·해제는 D-058 ④ 감시 구현 후속 과제 |
| 고정 슬리피지 0.15%와 호가 기반 체결의 오차 측정(D-021 위험 항목) | sim-engine, strategy-researcher | 실제 녹화일이 생긴 뒤 같은 신호를 두 방식으로 체결해 비교 |
| DB 저장(experiment·experiment_run·virtual_account·virtual_trade_log·equity_curve)과 `experiment.execution_model_id` 열 추가 | architect(스키마), sim-engine(저장) | 변경 제안: `execution_model_id VARCHAR(64) NOT NULL`. 같은 체결 모델끼리만 비교하려면 필요 |
| 비용 표 설정 키(`cost.profiles.*`, Q-004 조사 4장) | architect | 지금은 `CostModels`의 코드 기본값. 설정에서 읽어도 `CostModel` 구조는 같다 |
| 실험 진행 보고·취소, 실험실 자원 상한(SIM-14) | sim-engine, app-lab | `lab.max-parallel-runs`를 `parallelism`으로 넘긴다 |
| 실시간 모의 대 리플레이 차이 측정(SIM-13), 실시간 실행기 | sim-engine | 6단계 |
| 장 시작·마감 단일가 구간(KRX 08:30~09:00, 15:20~15:30) 처리 | sim-engine, data-engineer, architect | **[D-069](../decisions/D-069-session-phases-recording-experiment.md)로 끝남**(`MarketRules.sessionPhase`, `BOOK-V3` 단일가 일괄 체결). 시간외 매매 모의, 특별 운영일 단일가 시간 공지 대조는 남음 |
| PR #10 `DataGap` 처리: 그 종목 앞선 잔량·호가 무효화, Run 보고서에 공백 표시, 거래일 판정에서 제외 | sim-engine | **호가·앞선 잔량 무효화(`BOOK-V2`)와 보고서 `data-gap` 줄은 [D-068](../decisions/D-068-recording-adapter-gap-handling.md)으로 끝남.** 남은 것: 공백이 있는 거래일을 거래일 판정·지표에서 다루는 방법, 실험실 결과 묶음(D-062)에 공백 표시 |
| 체결 모델 ID를 `ExperimentSpec.executionModelId`로 옮겨 명세만으로 재현, 벤치마크 종목도 결과에 기록 | sim-engine, architect | DB 저장 작업과 함께(architect 검토 2) |
| 장 마감 시 VI·정지 상태와 호가 초기화 여부, 유효 시점·비용 날짜 기준 통일(`receivedAt + latency`, `exchangeTime`) | sim-engine | architect 검토 4·5. 동작이 바뀌므로 `RULES_VERSION` 상향과 함께 |
| 지표 보강: ~~관측 일수와 짧은 기간 샤프·CAGR 판정 불가 표시~~(D-069로 끝남: 20일·60일 미만 null, 보고서 `observation-days`), 장중 MDD와 최장 회복 기간, 미청산 포지션 수·평가손익, 평균 손익비, 충격 금액 합계와 비율, 왕복별 참여율 경고 | sim-engine, strategy-researcher | strategy-researcher 검토 4~8. 4단계 전략 비교 전 필요 |

## 5. 위험과 한계

- 주문 지연 0.5초는 근거 없는 기본값이다. 짧을수록 단타 성과가 좋게 나온다. 승격 전 0·1.5·2초 민감도(STR-13)로 다시 돌리고, 6단계 실측으로 바꾼다.
- 호가 스냅샷 간격이 길면(초 단위 이상) 그 사이의 체결 기회를 놓치거나 늦게 잡는다. 앞선 잔량은 TradeTick이 있어야 줄어드므로, 체결 이벤트가 빠진 녹화에서는 대기 주문이 덜 체결된다(보수적 방향).
- 체결 틱의 매수·매도 주체를 모르므로, 지정가 가격 체결을 대기 중인 쪽 체결로 본다. 실제로는 반대편 호가가 먹힌 체결일 수도 있어 대기 주문 체결을 약간 과대평가할 수 있다 `[추론]`.
- 체결 데이터만 있는 종목의 시장가 전량 체결은 깊이를 모른 채 고정 슬리피지만 더한다. 저유동 종목에서는 과소평가다.
- 상하한가: 호가 기반 체결에서는 반대편 호가가 비면 체결되지 않으므로 자연스럽게 막히지만, 체결 데이터만 있는 종목에서는 가격 제한을 검사하지 않는다. `MarketRules`에 가격 제한이 더해지면(2단계 ADR 범위) 반영한다.
- 평균 원가 배분은 소수 4자리 반올림이다. 분할 매도 시 왕복 손익 합계는 정확하지만(마지막 매도가 남은 원가 전부를 가져감) 중간 매도 건의 실현 손익은 1원 미만 차이가 날 수 있다.

## 6. 되돌릴 조건

- 6단계 실측에서 가상 체결과 실제(실시간 모의 대비 녹화 리플레이) 차이가 크면 체결 규칙을 고치고 `ExecutionSettings.RULES_VERSION`을 올린다. 이전 결과는 지우지 않고 다른 체결 모델 ID로 남는다.
- 비용 구조를 `MarketRules`·`BrokerFeeSchedule`로 옮겨야 할 이유(실전 화면 추정 비용 등)가 생기면 새 ADR로 옮긴다. 이 경우에도 비용 표 해시 규칙은 유지한다.

## 7. 검증

- `./gradlew build` 통과(컴파일 경고 0, ArchUnit 포함).
- 손 계산: Q-004 조사 5장(국내 23,000원, 미국 50.21달러), TAF 구간·상한, 국내 항목별 절사(`CostModelTest`). 시장가 호가 소진, 앞선 잔량, VI 정지, 체결 시점 현금 부족(`VirtualExchangeTest`). 평균 원가 실현 손익(`VirtualAccountTest`). 지표(`MetricsCalculatorTest`, 기대값은 Python `decimal`로 별도 계산).
- 골든: 손으로 만든 이틀치 KRX 녹화(`fixtures/krx-golden.events`) + 고정 프로필 3개 → `golden/krx-golden.report`와 바이트 단위 일치. 주요 수치는 `손_계산과_맞는다` 테스트가 따로 확인한다.
- 결정론: 같은 실험 두 번, 병렬 수 1·3 결과 동일. 계좌 격리: 실패 Run을 섞어도 다른 Run의 체결·요약이 혼자 돌린 결과와 같음.
- 검증하지 못한 것: 실전 체결 기록과의 오차. (실제 녹화 데이터: 어댑터 읽기는 2026-10-12 녹화로 D-068 "검증"에서 확인. 실녹화 실험 두 번 실행은 아직 하지 않았다. 사용자 PC 실행 뒤 `experiments/EXP-20261013-REC01/`에 결과를 커밋할 예정이며 그 전까지 미검증이다.)
