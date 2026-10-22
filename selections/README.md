# 선정 프로필 (YAML)

종목 선정 조건 하나 = 선정 프로필 ID 하나(STR-15). 파일 배치는 `selections/<선정ID>/v<버전>.yaml`이고 형식은 전략 프로필과 같은 YAML 부분집합이다([ADR-007](../docs/adr/ADR-007-selection-combination.md) 2.1, [strategies/README.md](../strategies/README.md) "형식"). 담당: strategy-researcher.
읽기는 `SelectionProfileYaml`, 조건 해석과 계산은 `selection.calc`(`SelectionCriteria`, `SelectionCalculator`)가 한다. 키와 지표 정의는 [선정 조건 카탈로그](../docs/strategy/selection-catalog.md), 조합 실험 방법은 [조합 실험 절차](../docs/strategy/combination-experiments.md).

- 저장된 버전 파일은 고치지 않는다. 바꾸려면 새 버전을 만든다(새 해시).
- 선정과 전략 프로필은 시장·스타일이 같아야 조합이 된다(STR-02).
- 선정 결과는 신규 진입 후보만 정한다. 선정에서 빠진 보유 종목은 청산 사유가 아니다(STR-15, D-044).

## 현재 프로필

| 프로필 | 스타일·갱신 | 역할 |
|---|---|---|
| `kr-fin-quality-growth` | POSITION, 주간 | 예시 (가) 재무 4조건(D-043) |
| `kr-vol-surge-quiet` | SWING, 매일 | 예시 (나) 거래량 급증·가격 정체(D-043) |
| `kr-intraday-hot` | INTRADAY, 장전 매일 | 예시 (다) 전일 관심 종목(ADR-012 3.3, D-121) |
| `kr-intraday-news` | INTRADAY, 장전 매일 | 예시 (라) 소식 있는 종목: 전일 공시(B·I) 1건 이상(ADR-012 3.3, D-123). 공시 스냅샷 필요 |
| `kr-intraday-news-nodisc` | INTRADAY, 장전 매일 | (라)에서 공시 조건만 뺀 대조 선정. 선정 엣지 사전 검사(배치표 2b) EDGE03 전용(D-125) |
| `kr-intraday-hot-lag1` | INTRADAY, 장전 매일(하루 늦춘 일봉 `PRE_OPEN_DAILY_LAG1`) | (다)의 하루 늦춘 판: 조건은 같고 기준 데이터일이 T−2(D-137). KRX 일봉이 장 전까지 게시되지 않아 실시간 모의에 쓰는 판 |
| `kr-intraday-news-lag1` | INTRADAY, 장전 매일(하루 늦춘 일봉) | (라)의 하루 늦춘 판(D-137). 공시 스냅샷 필요 |
| `kr-intraday-news-nodisc-lag1` | INTRADAY, 장전 매일(하루 늦춘 일봉) | (라) 공시 대조의 하루 늦춘 판(D-137). 하루 늦춘 판의 일봉 사전 검사 대조용 |
| `kr-intraday-open-momentum` | INTRADAY, 장중 순위(`INTRADAY_AT` 09:30) | 예시 (마) 장 초반 거래대금 상위: 09:30 전 체결로 boards 안 순위(ADR-012 5.2, D-127). 녹화 실험·실시간 모의만 |
| `kr-intraday-open-all` | INTRADAY, 장중 순위(`INTRADAY_AT` 09:30) | (마)의 대조 선정: 09:30 전 체결이 있는 boards 전 종목(D-127, strategy-researcher PR #200 M1) |
| `kr-watchlist-turnover` | INTRADAY, 장전 매일 | 감시 종목 후보(20거래일 평균 거래대금 상위 `STOCK`). 매매 선정이 아니라 `runWatchlistCandidates`만 쓴다(ADR-012 배치표 0번) |
