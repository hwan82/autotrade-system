# ADR-013 전략 문맥으로 신규 진입 허용 여부를 미리 알림(F7, 선정 계좌의 막힌 신호)

- 상태: 제안(D-130, 실전 관련 PR이라 사용자 머지)
- 날짜: 2026-10-20
- 담당: sim-engine. 검토: architect(계약), risk-ops(보호 경로 `autotrade-strategy`)
- 근거: [LIVE02 계획](../strategy/live02-preregistration-plan.md) 3.1·8장 F7·2장 G1, [D-124](../decisions/D-124-selection-accounts-live-recording.md)(선정 밖 신규 진입 차단), [D-127](../decisions/D-127-intraday-at-selection.md)(순위 시각 전 신규 진입 0건), [ADR-004](ADR-004-simulation-engine.md)(전략 실행 계약)

## 1. 문제

`[사실]` 선정 조합 계좌에서 전략은 boards 전 종목 이벤트를 받는다. `ManagedStrategy`는 신규 진입 신호를 낼 때 하루 진입 수(`entriesToday`)를 올리고 대기 진입 금액(`pendingEntryNotional`)을 잡는다(고치기 전 `ManagedStrategy.java` 264~266행). 그 뒤 `RunStepper`가 그날 후보 밖 신호를 주문 전에 버린다(고치기 전 142~148행). 그래서 후보 밖 신호가 먼저 오면

- 하루 진입 수 상한(`limits.max-entries-per-day`)이 차서 후보 종목에 들어가지 못하고,
- 버려진 신호의 대기 진입 금액이 그 종목 다음 이벤트까지 남아 쓸 수 있는 현금에서 빠져 후보 종목 수량이 줄거나 0이 된다.

선정 없는 계좌(LIVE00·LIVE01)는 이 거름이 없어 영향이 없다.

## 2. 결정: F7-a

1. `StrategyContext`에 기본 메서드 `admitsEntry(Symbol)`을 더한다. 기본값 true(거르지 않음). 실행기가 지금 그 종목의 신규 진입 신호를 주문으로 받는지를 알린다. 청산 신호에는 쓰지 않는다.
2. 계약: 전략은 신규 진입 신호를 내기로 정한 순간에 **한 번만** 묻고, false면 신호를 내지 않으며 자기 한도(하루 진입 수, 대기 진입 금액, 진입 깔때기)를 쓰지 않는다. 실행기는 false를 돌려준 횟수를 "막은 신규 진입"으로 센다.
3. `ManagedStrategy.entry`는 확인 신호를 통과하고 신호를 소비한 바로 뒤(`armedAt.remove`), 한도 확인 앞에서 묻는다. false면 빈 목록을 돌려준다. 그래서 하위 클래스 `trigger`·`observe` 상태 변화는 고치기 전과 같고, 달라지는 것은 한도·대기 금액·깔때기를 쓰지 않는다는 것뿐이다.
4. `RunStepper`의 문맥은 `admits(symbol, 이벤트 순번)`으로 답한다. 기준은 주문 전 거름과 같다(`SelectionDay.allowsEntry(symbol, sequence)`, 장중 순위 시각 전이면 false). 선정 없는 계좌는 늘 true다.
5. 주문 전 거름(`RunStepper.onEvent`)은 그대로 둔다. 묻지 않는 전략(시험용 고정 전략, `BuyAndHold`)의 안전망이고, 묻는 전략은 그 줄에 오지 않으므로 두 번 세지 않는다.

## 3. 대안

| 안 | 내용 | 고르지 않은 이유 |
|---|---|---|
| **F7-a(채택)** | 문맥이 미리 알림, 전략이 신호를 내지 않음 | - |
| F7-b | 실행기가 막은 신호를 전략에 알리는 콜백(`Strategy.onBlocked`)으로 하루 진입 수·대기 금액을 되돌림 | 한도 확인이 막힌 신호 기준으로 이미 돌아서 부수 효과가 남는다(예: 일 손실 한도 확인이 `haltedToday`를 켬). 되돌릴 상태를 전략마다 정확히 알아야 하고, 되돌림을 빠뜨리는 새 상태가 생기면 같은 결함이 조용히 돌아온다. `Strategy` 인터페이스에 메서드가 하나 늘어 모든 전략이 영향받는다 |
| F7-c | 받아들이고 문서화 | LIVE02 계획 G1을 풀지 않음(P2·L10이 다른 귀무가설을 잼) |
| F7-d | 선정 계좌용 프로필에서 하루 진입 수 상한을 크게 | 대기 진입 금액이 남아 후보 수량이 여전히 줄어듦(architect PR #202 재검토 2 R2-1) |

되돌릴 조건: 실전 엔진이 전략을 직접 돌리게 될 때 실전 쪽 문맥이 다른 진입 거름(블랙리스트·수동 보유 등)을 이 메서드로 미리 알리고 싶어지면, 이 메서드가 "거름을 앞당길 뿐 대신하지 않는다"는 계약을 그대로 두고 새 ADR로 범위를 넓힌다.

## 4. 바뀌지 않는 것

- 블랙리스트·수동 보유 거절은 그대로 주문 전 위험 검사(`RiskChain`)가 한다. 이 메서드는 블랙리스트를 보지 않는다. 블랙리스트에 오른 후보 종목은 고치기 전처럼 위험 검사에서 거절된다(`SelectionEntryQuotaTest`).
- RealTradingGuard·`GuardedOrderGateway`·실전 엔진: `[사실]` 실전 엔진은 `StrategyContext`를 구현하지 않는다(`grep StrategyContext` 결과 `autotrade-sim`·시험만). 기본 메서드라 구현하지 않은 문맥은 고치기 전과 같다.
- 선정 없는 계좌: 문맥이 늘 true라 `ManagedStrategy` 경로가 같다. `admitsEntry`를 넘기지 않는 문맥(도입 전과 같음)으로 돌린 보고서와 바이트 단위로 같은지 시험한다(`SelectionEntryQuotaTest`).

## 5. 위험과 한계

- 선정 계좌의 "막은 신규 진입"(`blocked-entries`) 값 뜻이 바뀐다. 고치기 전에는 한도·수량을 통과한 신호만 셌고, 이제는 한도 확인 앞에서 센다(하루 상한에 묶이지 않아 더 커질 수 있음). 이미 머지된 D-124·D-126 선정 계좌 결과는 판정에 쓴 것이 없다 `[추론]`(LIVE00·LIVE01은 선정 없음).
- 진입 깔때기(`EntryFunnel`)는 선정 밖으로 막힌 신호를 세지 않는다(판정에는 쓰지 않는 해석용).
- 막힌 신호 때문에 켜지던 일 손실 한도 확인(`haltedToday`)은 이제 후보 신호가 올 때 그때 평가금액으로 한다. 같은 날 평가금액이 회복되면 예전보다 늦게 멈추거나 멈추지 않을 수 있다. 계좌 수준 상태를 후보 밖 신호가 바꾸지 않게 하는 것이 이 결정의 목적이라 받아들인다. → 후속 [D-135](../decisions/D-135-blocked-signal-daily-loss-check.md)(사용자 결정): 받지 않은 확인 신호에서도 일 손실 한도만 확인한다.
- `BuyAndHold`는 묻지 않는다. 선정 조합에 쓰면 후보 밖 종목이 `sizing.slots`를 쓴다(고치기 전과 같음). LIVE02 기준선은 선정 없는 `kr-bh-baseline@3`라 영향 없다. 선정 × 매수 후 보유 조합이 필요해지면 그때 고친다.
