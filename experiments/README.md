# 실험 결과 (experiments/<실험ID>/)

실험 하나 = 폴더 하나. 담당: strategy-researcher. 실패한 실험도 지우지 않는다(같은 실험 반복 방지).

| 파일 | 내용 | 누가 |
|---|---|---|
| `experiment.yaml` | 실험 정의(사전 등록): 질문, 데이터 스냅샷, 구간, 시드, 비용 모델, 체결 모델, 프로필 해시, 선발 기준 수치 | 합성 실험은 실행기가 생성, 조합 실험은 사람이 실행 전에 씀 |
| `inputs.yaml`, `selection-days.tsv`, `detail.txt` | 조합 실험의 입력 스냅샷 ID·해시, 날짜별 선정 결과, 체결 원장까지 담은 정규 보고서 | 실행기가 생성 |
| `results.md` | 리더보드, 지연 민감도, ±20% 민감도, 대조군 분포, 선발 기준 판정, 일별 평가금액. 같은 입력이면 바이트 단위로 같다 | 실행기가 생성 |
| `runs.tsv` | Run별 지표(UI·추가 분석용) | 실행기가 생성 |
| `report.md` | 결론, 해석, 과최적화 검사, 이상 사례, 다음 실험 | 사람(strategy-researcher) |

## 실행

```bash
# 코드를 먼저 커밋한다(결과에 깨끗한 커밋이 남는다. experiments/ 밖에 변경이 있으면 dirty로 기록)
./gradlew :autotrade-sim:runStrategyStudy
```

결과에 남는 코드 커밋은 **main에 있는 커밋**이어야 한다. PR은 squash 머지라 브랜치 커밋이 main 이력에 남지 않는다(PR #14 architect 검토 M1). 그래서 결과 파일은 main(또는 main과 코드가 같은 깨끗한 작업 트리)에서 돌려 만들고, 브랜치에서 돌린 결과는 머지 뒤 main에서 다시 돌려 커밋 줄을 갱신한다. 다시 돌린 결과가 커밋 줄 말고 바이트 일치하는지 확인하고 report.md에 적는다.

조합 실험(선정 × 매매지표, 수집 스냅샷 위 일봉 리플레이, D-061)은 등록 파일 `experiment.yaml`을 사람이 먼저 쓰고 커밋한 뒤 돌린다. 실행기는 같은 폴더에 `inputs.yaml`(입력 스냅샷 ID·해시. 수집기 `--keep-snapshots`가 이 스냅샷을 지우지 않는다), `results.md`, `runs.tsv`, `selection-days.tsv`, `detail.txt`를 쓴다. 절차는 [조합 실험 절차](../docs/strategy/combination-experiments.md) 5장.

```bash
./gradlew :autotrade-sim:runCombinationExperiment -Pexperiment=<실험ID> -PverifyDeterminism
```

합성 시세 실험(`runStrategyStudy`)의 설계는 `StudyMain`의 상수로 고정한다. 결과를 본 뒤 설계를 바꿔 같은 데이터로 다시 평가하지 않는다. 바꾸려면 새 실험 ID를 쓴다.

## 목록

| 실험 | 데이터 | 질문 | 결론 |
|---|---|---|---|
| [EXP-20261008-STR01](EXP-20261008-STR01/report.md) | 합성 시세(파이프라인 검증용) | 프로필 9개를 같은 조건으로 비교하는 절차가 끝까지 도는가, 선발 기준이 대조군과 후보를 가르는가 | report.md |
