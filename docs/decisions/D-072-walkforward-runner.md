# D-072 일봉 워크포워드 판정 실행 명령 `runWalkForwardStudy` (ADR-009)와 일봉 후보 로직 3개

- 날짜: 2026-10-13
- 상태: 추천안 채택(자율)
- 담당: sim-engine(실행기), strategy-researcher(후보 프로필·판정 해석)
- 근거: [D-071](D-071-stage-4-plan.md), [ADR-009](../adr/ADR-009-daily-walkforward-runner.md), [4단계 계획](../strategy/stage-4-plan.md) 3~5장

## 결정

1. [ADR-009](../adr/ADR-009-daily-walkforward-runner.md)를 채택한다. 실험 실행기에 선택 인자 두 개(예열 `warmupDays`, 구간 끝 정리 `settleAtWindowEnd`)를 더하고 기본값은 기존 동작과 바이트까지 같다. 등록 파일 `experiments/<ID>/study.yaml`, 명령 `./gradlew :autotrade-app-experiment:runWalkForwardStudy -Pexperiment=<ID>`(D-063 정지 확인 그대로), 결과 파일 `results.md`·`runs.tsv`·`windows.tsv`·`inputs.yaml`·`detail.txt`.
2. 판정 세부(계획 4장을 구현에서 정한 것): C5 미달이면 보류. C10의 N은 왕복이 1건 이상인 완료 대조군 수(0건 시드는 빼고 수를 기록), 같은 값은 "이상"으로 센다, 판정 가능 조건 N + 1 ≥ 20m. 구간 중 상장폐지 종목도 구간 끝에 정리한다.
3. 일봉 진입 로직 `breakout-days`(SWING·POSITION), `short-reversal`(SWING), `near-high`(POSITION)와 프로필 `kr-donchian-swing@1`, `kr-short-reversal-swing@1`, `kr-52w-high-position@1`을 카탈로그에 더한다. 판단은 전일까지 종가, 체결은 다음 이벤트. `kr-52w-high-position`의 ±20% 대상은 `signal.lookback-days`(진입 비율 ×1.2가 1을 넘어 변형이 무효가 되므로).
4. 실험실 화면용 결과 묶음(`result-bundle.jsonl.gz`)은 워크포워드 명령에서 아직 만들지 않는다(5단계 UI 후속).

## 대안

| 선택 | 대안 | 버린 이유 |
|---|---|---|
| 실행기에 예열 인자 | 구간 시작을 예열만큼 앞당기고 그동안 진입만 금지 | 예열 기간 평가금액·자산 곡선이 섞여 MDD·샤프·C6 분모가 바뀐다 |
| 구간 끝 종가 정리 | 미청산은 평가손익으로만 | PF·왕복 수는 청산분, C10은 평가금액을 세게 되어 같은 계좌에서 기준끼리 대상이 달라진다(PR #78 M2) |
| 구간·단계마다 데이터셋 | 전체 기간 데이터셋 하나 | 2010년부터 전 종목을 한 번에 올리면 힙이 모자랄 수 있다(ADR-008 4장) `[추론]` |

## 한계와 되돌릴 조건

- 실제 KRX 전체 스냅샷·대조군 400 규모의 실행 시간과 메모리는 실측하지 않았다(계산량은 초당 100만 이벤트 `[가정]`으로 추정). 사용자 PC 첫 실행에서 재고, 모자라면 대조군 수 또는 병렬 수를 조정하는 새 결정을 쓴다.
- 상장폐지 마지막 거래일 뒤 손실, 배당은 반영하지 않는다(가격 지수, 낙관 방향).
- 번들 거래소 캘린더는 2026~2027년만 수록하므로 과거 구간 거래일은 스냅샷 일봉 날짜에서 이끈다(D-061 ③).
