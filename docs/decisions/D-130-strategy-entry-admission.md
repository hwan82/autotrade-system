# D-130 선정 계좌에서 막힌 신규 진입 신호가 하루 진입 한도·대기 금액을 쓰지 않게(F7-a, ADR-013)

- 날짜: 2026-10-20
- 상태: 제안 · **추천안 채택(자율)**(F7-a·F7-b 중 고르기는 sim-engine 판단, 지침 8장). 보호 경로 `autotrade-strategy`를 바꾸므로 **실전 관련 PR**: architect·risk-ops 검토, 독립 QA, 사용자 머지(지침 10·11장). 실전 주문 경로, RealTradingGuard, 블랙리스트 규칙, 리스크 한도, 게이트웨이는 바꾸지 않는다.
- 담당: sim-engine. 교차 검토: architect, risk-ops
- 근거: [ADR-013](../adr/ADR-013-strategy-entry-admission.md), [LIVE02 계획](../strategy/live02-preregistration-plan.md) 8장 F7·2장 G1, [D-128](D-128-live02-preregistration-plan.md). 번호: D-129는 열린 PR #203이 써서 D-130을 쓴다.

## 결정

1. `StrategyContext.admitsEntry(Symbol)` 기본 메서드(기본 true). 전략이 신규 진입 신호를 내기 직전에 한 번 묻고, false면 신호도 내지 않고 하루 진입 수·대기 진입 금액·진입 깔때기도 쓰지 않는다. 청산은 묻지 않는다.
2. `ManagedStrategy`(모든 카탈로그 매매지표 중 `buy-and-hold` 밖 전부)가 확인 신호 통과 뒤·한도 확인 앞에서 묻는다.
3. `RunStepper`는 그날 선정 판정(`SelectionDay.allowsEntry(종목, 이벤트 순번)`)으로 답하고, false마다 그날 막은 신규 진입 수를 하나 센다. 주문 전 거름은 묻지 않는 전략의 안전망으로 남긴다.
4. 선정 없는 계좌(LIVE00·LIVE01)는 결과가 바이트 단위로 같다. 블랙리스트 거절은 위험 검사가 그대로 한다.

## 고른 이유

F7-b(막은 뒤 되돌림)는 한도 확인이 남긴 부수 효과를 되돌려야 하고 되돌릴 상태가 늘면 결함이 조용히 돌아온다. F7-a는 막힐 신호가 처음부터 상태를 바꾸지 않아 되돌릴 것이 없다. 자세한 비교는 ADR-013 3장.

## 시험

- `CatalogStrategyTest`: 받지 않는 종목의 신호가 먼저 와도 하루 1건 한도가 후보에 남음, 대기 진입 금액을 잡지 않아 후보 수량이 혼자일 때와 같음, 청산은 묻지 않음. 앞의 둘은 고치기 전 코드에서 실패함을 확인했다.
- `SelectionEntryQuotaTest`(실행기 + 실제 `random` 매매지표): 후보 밖 신호가 먼저 와도 후보가 체결됨(고치기 전 0건), 막은 수 2, 블랙리스트 후보는 위험 검사 거절, 선정 없는 계좌는 먼저 온 종목이 한도를 씀, 선정 없는 계좌 보고서가 `admitsEntry`를 넘기지 않는 문맥과 바이트 단위로 같음.

## 후속

- LIVE02 계획 2장 G1 중 F7 조건은 이 PR 머지로 풀린다(F1~F4는 따로).
- 선정 × `buy-and-hold` 조합이 필요해지면 `BuyAndHold`도 묻게 한다(ADR-013 5장).

## 후속 반영(PR #205 QA Q1·Q2)

- Q1: `SelectionEntryQuotaTest`에 장중 순위 선정(`INTRADAY_AT` 09:30) 시험을 더했다. 순위 전 후보 밖 신호가 하루 1건 한도를 쓰지 않고, 순위 뒤 후보가 들어간다. 실행기가 이벤트 순번을 무시하는 변이(QA P1)에서 실패함을 확인했다.
- Q2: `CatalogStrategyTest`에 막힌 신호가 진입 깔때기(신호·진입)에 세지지 않는 시험을 더했다. 막혀도 `funnel.signal`을 세는 변이(QA P10)에서 실패함을 확인했다.
