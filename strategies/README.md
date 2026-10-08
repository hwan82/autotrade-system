# 전략 프로필 (YAML)

매매 기법 하나 = 프로필 ID 하나(STR-01). 파일 배치는 `strategies/<프로필ID>/v<버전>.yaml`입니다([ADR-005](../docs/adr/ADR-005-ui-dashboard.md) 4장). 대시보드의 전략 프로필 화면이 이 폴더를 읽고 씁니다. 담당: strategy-researcher.
읽기는 UI가 `StrategyProfileStore`(app-lab), 실험 실행기가 `ProfileYaml`(autotrade-strategy)이고, 두 읽기가 같은 해시를 내는지 app-lab `ProfileFileCompatibilityTest`가 확인합니다. 해석·검증은 `CatalogStrategyFactory`가 합니다. 결정: [D-026](../docs/decisions/D-026-ui-frontend.md)(배치), [D-027](../docs/decisions/D-027-strategy-lab-protocol.md)(키와 실험 절차).

- 저장된 버전 파일은 고치지 않습니다. 바꾸려면 새 버전을 만듭니다(새 해시). 실험 결과가 해시로 프로필을 가리키기 때문입니다.
- 버전 사이에 `market`·`style`은 바꿀 수 없습니다. 다른 시장·스타일은 복제해 새 ID로 만듭니다.
- 파라미터는 점으로 이은 키와 문자열 값입니다(예: `entry.signal: range-breakout`, `exit.stop-loss-pct: "3"`).

## 형식

```yaml
id: "kr-orb-30"
version: 1
market: "KRX"          # KRX, US
style: "INTRADAY"      # INTRADAY, SWING
created-at: "2026-10-08T00:00:00Z"
parameters:
  logic: "orb"
  exit.stop-loss-pct: "1.5"
```

- 최상위 키는 `id`, `version`, `market`, `style`, `based-on`, `created-at`, `note`, `parameters`뿐입니다. `based-on`·`created-at`·`note`는 기록용이라 해시에 들어가지 않습니다.
- 손으로 쓸 때도 `parameters` 아래 평평한 점 키(공백 2칸 들여쓰기)로 씁니다. 목록·앵커·흐름 표기(`[ ]`, `{ }`)·블록 문자열(`|`, `>`)은 쓰지 않습니다. 벗어나면 `ProfileYaml`이 줄 번호와 함께 거절합니다.
- 값은 큰따옴표로 감쌉니다(UI도 항상 감쌉니다). 값은 문자열 그대로 해시에 들어가므로 `1.5`와 `1.50`은 다른 프로필입니다.
- 비율 파라미터는 소수점을 붙여 씁니다(`"3.0"`). 실험 실행기의 ±20% 변형이 소수점 없는 값을 정수(개수·분·일)로 보고 반올림하기 때문입니다.
- 로직이 쓰지 않는 키는 거절합니다(오타가 해시에만 섞여 결과 추적이 어긋나는 것을 막음). 예외: `hypothesis`, `study.*`, `notes.*`.

## 공통 키 (장중·스윙 `ManagedStrategy` 로직)

| 키 | 뜻 | 범위 |
|---|---|---|
| `hypothesis` | 엣지 가설 한 문장(시장·조건·보유 기간·기대 효과, STR-02) | 필수 |
| `logic` | 진입 로직(아래 표) | 필수 |
| `entry.window.start`, `entry.window.end` | 신규 진입 허용 시각(거래소 현지, `HH:mm`, 끝은 제외) | 필수 |
| `entry.expiry-seconds` | 주 신호가 확인 신호를 기다리는 최대 시간(STR-07 만료) | 필수, 1~86400 |
| `entry.confirm.volume-ratio`, `entry.confirm.window-minutes` | 확인 신호: 최근 N분 거래량 ≥ 오늘 앞선 같은 길이 평균 × 비율. 있으면 최소 동시 신호 2개 | 선택(0 = 없음) |
| `universe.min-price` | 진입 최소 가격 | 선택 |
| `sizing.risk-per-trade-pct` | 거래당 위험(평가금액 %). 수량 = 위험 금액 / (가격 × 손절폭) | 0.01~5 |
| `sizing.max-position-pct` | 종목당 투입 상한(평가금액 %) | 1~100 |
| `sizing.max-positions` | 동시 보유 종목 수(미체결 진입 포함) | 1~50 |
| `sizing.cash-buffer-pct` | 남겨 둘 현금(평가금액 %) | 0~50 |
| `limits.max-entries-per-day` | 하루 신규 진입 주문 수 | 1~1000 |
| `limits.reentry-cooldown-minutes` | 같은 종목 청산 후 재진입 금지 | 0~ |
| `limits.daily-loss-limit-pct` | 당일 시작 평가금액 대비 손실이 이만큼이면 그날 신규 진입 중단 | 0.1~50 |
| `limits.max-consecutive-stops` | 연속 손절이 이 횟수면 그날 신규 진입 중단 | 1~100 |
| `exit.stop-loss-pct` | 평균 단가 대비 손절(STOP_LOSS 의도) | 필수 |
| `exit.take-profit-pct`, `exit.trailing-pct`, `exit.time-exit-minutes` | 익절, 보유 중 최고가 대비 추적 손절, 시간 청산 | 선택(0 = 없음) |
| `exit.eod` | 장 마감 정책 `FLATTEN`(장중 필수) 또는 `CARRY`(스윙) | 필수 |
| `exit.eod-time` | `FLATTEN`일 때 청산 시각. 진입 허용 끝 이후 | FLATTEN이면 필수 |
| `study.key-param` | 실험의 ±20% 민감도 검사 대상 키(STR-04) | 선택 |

한도 값은 모의 실험용 프로필 파라미터다. 실전 계좌 한도는 risk-ops 제안 후 사용자가 정한다(Q-008).
진입·청산은 모두 시장가이고, 신호 다음 이벤트부터 체결되는 것은 실행기가 보장한다(ADR-004).

## 진입 로직 (STR-06 카탈로그)

| `logic` | 스타일 | 주 신호 | 로직 키 |
|---|---|---|---|
| `buy-and-hold` | SWING | 종목마다 첫 호가에 같은 금액 매수, 끝까지 보유(기준선). 공통 키 대신 `sizing.slots`, `sizing.cash-buffer-pct`만 | `sizing.slots` |
| `orb` | INTRADAY | 첫 체결부터 N분 고가를 범위가 닫힌 뒤 처음 돌파(하루 1번) | `signal.range-minutes` |
| `vwap-reclaim` | INTRADAY | VWAP 아래 x% 이탈 후 VWAP 회복 | `signal.dip-pct` |
| `prior-day-high` | INTRADAY | 갭 없이 시작한 날 전일 고가 + x% 돌파(하루 1번) | `signal.margin-pct` |
| `volume-surge` | INTRADAY | 최근 N분 거래량 ≥ 앞선 평균 × r, 같은 기간 가격 x% 이상 상승 | `signal.window-minutes`, `signal.surge-ratio`, `signal.min-rise-pct` |
| `book-imbalance` | INTRADAY | 상위 5단계 매수 잔량 ≥ 매도 잔량 × r가 K번 연속 | `signal.ratio`, `signal.snapshots` |
| `pullback` | INTRADAY | 시가 대비 x% 오른 날, 고가 대비 y% 밀린 뒤 눌림 저점에서 z% 반등 | `signal.trend-pct`, `signal.pullback-pct`, `signal.bounce-pct` |
| `ma-trend` | SWING | 전일까지 종가의 단기 평균 > 장기 평균이면 진입, 반대면 로직 청산(하루 첫 체결에 한 번 판단) | `signal.fast-days`, `signal.slow-days` |
| `random` | INTRADAY | 체결마다 p/1000 확률(대조군, Run 시드 사용) | `signal.per-mille` |

아직 없는 것: 장전 후보 풀·스크리너 점수화(STR-03·08·10·11, 일봉 스냅샷 필요), 미국 프로필(US 규칙·지정가 여유폭).
지금 유니버스는 실험 데이터에 있는 종목 전체다.

## 현재 프로필

| 프로필 | 역할 |
|---|---|
| `kr-bh-baseline` | 기준선(매수 후 보유) |
| `kr-orb-30`, `kr-vwap-reclaim`, `kr-pdh-breakout`, `kr-volume-surge`, `kr-book-imbalance`, `kr-pullback` | 장중 후보 |
| `kr-ma-trend` | 스윙 후보 |
| `kr-random-control` | 대조군(무작위 진입) |

첫 비교 실험: [EXP-20261008-STR01](../experiments/EXP-20261008-STR01/report.md).
