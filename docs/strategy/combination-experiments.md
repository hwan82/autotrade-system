# 조합 실험 절차 (STR-17·18, 배치표 2번)

- 담당: strategy-researcher. 날짜: 2026-10-10. 결정: [D-057](../decisions/D-057-selection-catalog.md)(추천안 채택(자율)). 중기 워크포워드·괴리 수치는 사용자 결정 [D-066](../decisions/D-066-position-selection-thresholds.md)(2026-10-12)으로 확정됐다(3장).
- 근거: 기획서 STR-04·05·17·18, SIM-09·10·14, [D-041·D-043](../decisions/D-041-selection-x-signal-combinations.md), [ADR-007](../adr/ADR-007-selection-combination.md) 2.4, `autotrade-strategy-lab` 스킬 2~5장
- 선정 조건은 [선정 조건 카탈로그](selection-catalog.md)

## 1. 절차

1. **질문과 등록**: 실험 전에 조합 목록(선정 `id@버전` 또는 `none` × 전략 `id@버전`)을 `experiments/<실험ID>/experiment.yaml`에 적고 커밋한다. 실행 중 조합 추가는 없다(`ExperimentSpec`이 등록 목록, STR-17). 조합 수 상한 128(`SimulationExperimentRunner.MAX_COMBINATIONS`, SIM-14).
2. **같은 조건**: 모든 조합이 같은 기간·시드·비용 모델·체결 모델·데이터 스냅샷(일봉·재무)을 쓴다(지침 5장). 조합끼리 난수열은 `run-seed-v2`로 독립(ADR-007 2.4).
3. **대조군 두 축**(스타일마다):
   - **같은 선정 + 무작위 진입**: 선정 효과만 남기고 진입 정보를 없앤 기준. 단타 `kr-random-control`(체결마다 추첨), 스윙 `kr-random-daily-swing`, 중기 `kr-random-daily-position`(종목·거래일마다 한 번 추첨, 청산·비중·최대 보유일은 그 스타일 후보와 같게).
   - **같은 매매지표 + 선정 없음**: 매매지표 효과만 남기고 선정을 없앤 기준(유니버스 전체, 실험 전용, STR-01).
   - 기준선 `kr-bh-baseline`(매수 후 보유)과 지수 수익률을 함께 둔다.
4. **표 모양**: 선정 m개 × 지표 n개 교차표에 대조군 행·열을 더한다. 예: (나) 선정 × {`kr-ma-trend`, `kr-random-daily-swing`} + {`none`} × 같은 지표 = 4 계좌.
5. **다중 비교 보정**: 리포트에 비교한 조합 수 m을 적고, "대조군보다 낫다"는 판단은 조합마다 무작위 대조군 분포(시드만 바꾼 N회, `StrategyStudy` null 분포)에서 순위 백분위가 1 − 0.05/m 이상일 때만 한다(본페로니). N은 m/0.05보다 커야 판단할 수 있으므로 조합 수를 작게 시작한다(예: m = 4면 N ≥ 80). 판단을 못 하면 "보류"로 적는다.
6. **결정론 확인**: 같은 실험을 병렬 수 1로 다시 돌려 조합별 거래 내역·지표·날짜별 선정 결과 해시가 같은지 본다(SIM-09, `run_selection_day`).
7. **선정 품질 기록**: 날짜별 선정 종목 수, 탈락 사유별 건수, 정정 대체 규칙 적용 건수(`restatement_fallback_count`)를 결과와 함께 남긴다. 실험실은 `CalculatedSelectionSource.results()`로 다시 계산하지 않고 읽는다.

## 2. 스타일별 매매지표 카탈로그 (STR-18 "스타일마다 1개 이상")

| 스타일 | 후보 | 대조군 | 최대 보유일 |
|---|---|---|---|
| 단타 INTRADAY | `kr-orb-30` 외 5개 | `kr-random-control` | 당일 청산(`FLATTEN`) |
| 스윙 SWING | `kr-ma-trend`(3/8일), `kr-donchian-swing`(20일 채널 돌파), `kr-short-reversal-swing`(100일 추세 위 3일 연속 하락 반전) | `kr-random-daily-swing` | 새 버전부터 `exit.max-hold-days` 2~20 필수(대조군 10) |
| 중기 POSITION | `kr-ma-trend-position`(20/60일), `kr-52w-high-position`(250일 고가 95% 근접) | `kr-random-daily-position` | `exit.max-hold-days` 20~120 필수(둘 다 120) |

- 최대 보유일: 진입한 거래일이 0일째이고, 보유 거래일 수가 상한에 이른 날 첫 이벤트에서 시간 청산(`ExitReason.TIME_EXIT`, 주문 의도 `EXIT`). 손절이 먼저다.
- 스윙 검사의 적용 방식(STR-18 "strategy-researcher가 결정"): 카탈로그는 스윙 최대 보유일을 선택 키로 두어 `kr-ma-trend@1`·`kr-bh-baseline@1` 해시와 동작을 바꾸지 않고, 저장소 검사(`RepositoryProfilesTest`)가 이 두 버전 밖의 스윙 프로필에 키를 요구한다.
- 판단은 일봉(전일까지 종가), 체결은 다음 이벤트(리플레이에서는 다음 거래일 장 시작 직후)다. 진입 허용 시간을 09:00~09:30으로 둔 것은 "다음 날 시가 체결"(SIM-06)에 가깝게 하려는 것이다.

## 3. 중기 선발 기준 수치 (D-066, Q-013 중기 부분)

아래 표는 사용자 결정 [D-066](../decisions/D-066-position-selection-thresholds.md)(2026-10-12, 추천안 그대로)으로 확정됐다. 제안 당시 녹화·실데이터 결과가 없어 근거는 데이터 기간과 표본 수 계산뿐이었다. 다시 제안할 조건은 D-066에 있다.

| 항목 | 기준 | 근거 |
|---|---|---|
| 워크포워드 구간 | 학습 3년 + 검증 1년, 1년씩 굴림, 최소 3구간 | 재무 선정 백테스트가 2019년 4월부터라 2026년까지 검증 4구간(2022~2025년 4월 시작)이 나온다 `[추론]`. 스킬 2장 "최소 3구간" |
| 검증 구간 최소 거래 수 | 구간 합 30건 이상 | 스킬 5장 선발 기준과 같음. 보유 20~120거래일·동시 8종목이면 1년에 대략 16~100건 `[추론]` |
| 선발 판정 | 스킬 5장 기준 전부(비용 차감 후 벤치마크 초과, PF ≥ 1.3, MDD ≤ 20%, 샤프 ≥ 1.0, 검증/학습 ≥ 60%, 주요 파라미터 ±20% 변경 시 성과 급락 없음, 초과수익이 한 기간에 몰리지 않음)을 검증 구간 전체에서, 그리고 구간의 3/4 이상(3구간이면 모두)에서 벤치마크 초과 | 초과수익이 한 구간에 몰리지 않게 |
| 8주 실시간 모의와 백테스트 괴리 | 같은 8주를 일봉 리플레이로 다시 돌려 ① 진입 (종목, 거래일) 일치율 ≥ 90% ② 체결가 괴리 평균 ≤ 0.5% ③ 누적 수익률 차이 ≤ 2%p | 8주 동안 중기 거래가 적어 수익률 비교만으로는 판단할 수 없어 일치율을 먼저 본다 |
| 8주 모의 무결성 | 주문 처리 오류 0건(STR-05 그대로) | D-043 |

## 4. 남은 일

- 실제 일봉·재무 스냅샷(사용자 키 발급 뒤)으로 (가)(나) × 스윙·중기 조합 첫 실험. 실행 명령은 [D-061](../decisions/D-061-daily-replay-combination-runner.md)으로 준비됐다(아래 5장).
- 2025년 전 매매 구간: 비용 표(증권거래세 연혁)를 공식 자료로 넣어야 중기 워크포워드(2019년~)를 돌릴 수 있다(sim-engine).
- 일봉용 매수 후 보유 기준선: `kr-bh-baseline@1`은 호가 이벤트에서만 사서 일봉 데이터에서는 거래가 없다(strategy-researcher 새 버전).
- 다중 비교 판정과 교차표 리더보드 구현(배치표 4번 ui-dev, 리포트 쓰기는 sim-engine).
- 화면 스타일 표기 `POSITION`(배치표 4번).

## 5. 실행 (D-061)

1. 수집기로 일봉(필요하면 재무) 스냅샷을 만든다([수집기 runbook](../runbooks/collector.md)). 스냅샷은 `<설정 폴더>/snapshots/krx-daily/<ID>/`에 생긴다.
2. `experiments/<실험ID>/experiment.yaml`을 쓰고 커밋한다(형식은 `CombinationPlan` 주석). 예:

```yaml
id: "EXP-20261012-CMB01"
question: "(나) 선정이 같은 스윙 지표의 선정 없음보다 비용 차감 후 나은가"
market: "KRX"
from: "2025-01-02"
to: "2026-10-08"
seed: "20261012"
seed-capital: "10000000"
bars-snapshot: "krx-daily-2026-10-08-1a2b3c4d"
financials-snapshot: "dart-financials-2026-10-08-5e6f7a8b"   # 재무 조건 선정이 없으면 생략
benchmark: "none"
parallelism: "4"
combinations:
  - "kr-vol-surge-quiet@1 x kr-ma-trend@1"
  - "kr-vol-surge-quiet@1 x kr-random-daily-swing@1"
  - "none x kr-ma-trend@1"
  - "none x kr-random-daily-swing@1"
```

3. `./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=EXP-20261012-CMB01 -PverifyDeterminism`
   (스냅샷 폴더가 기본 위치가 아니면 `-PsnapshotsDir=...`, 힙은 `-Pheap=8g`). 실전 엔진과 같은 PC라 국내·미국 장 시간 보호 구간(정규장 60분 전~폐장 30분 뒤)과 감시의 정지 요청 파일(`<설정 폴더>/experiment-state/stop-request`)이 있으면 시작하지 않고, 실행 중이면 거래일 경계에서 멈춰 결과 파일 없이 종료 코드 4로 끝난다([D-063](../decisions/D-063-experiment-command-stop-gate.md)). 실전 엔진을 끈 날에만 `-PallowDuringSession`(값 없음 또는 `true`, 그 밖의 값은 입력 오류)으로 장 시간 보호를 푼다(정지 요청 파일은 풀리지 않는다). `gradle.properties`에 넣어 두지 않는다. 시작 출력에 확인 위치와 해제 여부가 나온다. 결과는 같은 폴더의 `inputs.yaml`, `results.md`, `runs.tsv`, `selection-days.tsv`, `detail.txt`와 실험실 화면용 `result-bundle.jsonl.gz`(커밋하지 않음, D-062).
4. 실험실(`./gradlew :autotrade-app-lab:bootRun`)의 "실험" 화면 아래 "실험 폴더"에서 그 실험을 "불러오기"하면 계좌 비교, 선정 × 매매지표 교차표, 날짜별 선정 기록, 선정 결과(평가 행)를 화면에서 본다. 실험실은 묶음을 커밋된 `detail.txt`와 대조한 뒤 올리고 실험을 다시 돌리지 않는다. 실험실을 다시 띄우면 다시 불러온다.
5. 결과 해석·보고(`report.md`)는 1장 5·7번과 [experiments/README.md](../../experiments/README.md) 커밋 규칙을 따른다.
