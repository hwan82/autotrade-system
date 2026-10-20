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
