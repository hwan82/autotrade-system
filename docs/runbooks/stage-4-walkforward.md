# 4단계 일봉 워크포워드 판정 실행 절차 (사용자 PC)

[4단계 계획](../strategy/stage-4-plan.md) 2장 4·5번, D-071·D-072·D-073, [ADR-009](../adr/ADR-009-daily-walkforward-runner.md). 실데이터 수집과 판정 실험은 사용자 PC에서 돌리고, 저장소에는 등록 파일과 집계 결과만 들어간다. 일봉·재무 원자료와 체결 원장(`detail.txt`)은 PC 밖으로 내보내지 않는다(D-050 제3자 제공 금지).

## 순서 한눈에

| 단계 | 누가 | 무엇 |
|---|---|---|
| 1 | 사용자 | 수집 키 준비와 하루치 시험 수집 |
| 2 | 사용자 | 전체 수집(KRX 2010년부터, OpenDART 2016년부터). 이틀 이상 |
| 3 | 사용자 → 스레드 | 스냅샷 ID 두 개와 품질 리포트를 알려 준다 |
| 4 | Claude | 등록 파일 `experiments/EXP-<등록일>-WF01/study.yaml`을 커밋한다(사전 등록, 이후 고치지 않음) |
| 5 | 사용자 | `git pull` 뒤 판정 명령 실행(결정론 확인 포함) |
| 6 | 사용자 → 스레드 | 결과 파일 네 개를 올린다 |
| 7 | Claude | 결과 커밋, 리포트 작성, 4단계 완료 QA 관문 요청 |

## 1. 수집 준비

[수집기 runbook](collector.md) 1장(키 발급, `collector-secrets.json`)과 2장(하루치 시험 수집)을 그대로 따른다. 시험 수집에서 오류가 나면 메시지를 스레드에 붙인다(키 값은 메시지에 나오지 않는다).

## 2. 전체 수집

저장소 루트에서, 서울 18:30~21:30(서머타임이 아니면 22:30 전) 또는 주말에 돌린다(장 시간 보호, [수집기 runbook](collector.md) 3.5).

```
git pull
./gradlew :autotrade-app-collector:run --args="krx-daily --from 2010-01-04"
./gradlew :autotrade-app-collector:run --args="dart-financials --from 2016-01-01"
```

- 종료 코드 3(하루 호출 상한)이나 4(장 시간 보호)로 끝나면 다음 실행 시간에 **같은 명령을 다시** 돌린다. 받은 날은 캐시에 남아 이어 받는다. KRX 2010년부터는 이틀쯤 걸린다.
- 끝나면 각 명령이 스냅샷 ID(`krx-daily-...`, `dart-financials-...`)와 품질 리포트 경로를 출력한다.
- 수정 계수 표본 대조(분할 사례 전후 수정 종가와 HTS 수정주가 차트 비교)는 [수집기 runbook](collector.md) 3장 끝 항목대로 한 번 한다.

## 3. 스레드에 알릴 것

1. 일봉 스냅샷 ID와 재무 스냅샷 ID.
2. 품질 리포트 두 개(`experiments/_data-quality/<스냅샷ID>.md`). 집계 건수만 있어 저장소에 넣어도 된다.
3. 수정 계수 표본 대조 결과(맞음·다름과 종목·날짜).

## 4. 사전 등록 (Claude)

받은 스냅샷 ID로 아래 등록 파일을 커밋한다. 후보·대조군·구간·시드 수는 [4단계 계획](../strategy/stage-4-plan.md) 3·5장에서 이미 정했고, 결과를 본 뒤에는 고치지 않는다(바꾸려면 새 실험 ID).

```yaml
id: "EXP-<등록일>-WF01"
question: "국내 일봉 조합 10개(스윙 후보 3 x {none, kr-vol-surge-quiet@1}, 중기 후보 2 x {none, kr-fin-quality-growth@1}, m=10) 중 비용 차감 후 일봉 선발 기준(D-071 5번, D-073)을 통과하는 조합이 있는가"
market: "KRX"
bars-snapshot: "<일봉 스냅샷 ID>"
financials-snapshot: "<재무 스냅샷 ID>"
seed: "20261013"
seed-capital: "10000000"
parallelism: "<PC 코어 수 - 2>"   # 결과에 영향 없음(병렬 1과 N이 바이트까지 같음, 결정론 확인으로 검사)
first-test-from: "2022-04-01"
train-years: "3"
test-years: "1"
windows: "4"
warmup-days: "300"
null-seeds: "400"
combinations:
  - "none x kr-ma-trend@1"
  - "kr-vol-surge-quiet@1 x kr-ma-trend@1"
  - "none x kr-donchian-swing@1"
  - "kr-vol-surge-quiet@1 x kr-donchian-swing@1"
  - "none x kr-short-reversal-swing@2"
  - "kr-vol-surge-quiet@1 x kr-short-reversal-swing@2"
  - "none x kr-ma-trend-position@1"
  - "kr-fin-quality-growth@1 x kr-ma-trend-position@1"
  - "none x kr-52w-high-position@1"
  - "kr-fin-quality-growth@1 x kr-52w-high-position@1"
controls:
  - "SWING kr-random-daily-swing@1"
  - "POSITION kr-random-daily-position@1"
```

- 시드는 지금 고정한다(20261013). 자리표시자는 스냅샷 ID 두 개와 병렬 수뿐이다.
- **표본 외 보존**: 2026-04-01 이후 데이터는 이 실험에 쓰지 않고 나중 표본 외 확인용으로 남긴다. 등록 파일을 커밋하기 전에는 실데이터로 판정 명령이나 조합 실험을 시험 삼아 돌리지 않는다(결과를 보고 등록 내용을 고치는 일을 막기 위해).
- 4구간이면 마지막 검증 구간이 2026-03-31에 끝난다. 일봉 스냅샷이 그날까지 덮어야 한다(명령이 시작 전에 확인한다).
- 첫 학습 구간(2019-04-01)보다 예열 300거래일 앞(2018년 초)까지 일봉이 있어야 한다. 2010년부터 수집하면 충분하다.

## 5. 판정 실행

```
git pull
git diff --exit-code origin/main -- experiments/EXP-<등록일>-WF01/study.yaml
./gradlew :autotrade-app-experiment:runWalkForwardStudy -Pexperiment=EXP-<등록일>-WF01 -PverifyDeterminism
```

- **언제**: 장 시간 보호 구간(국내 08:00~16:00, 미국 서머타임 중 21:30~05:30·서머타임이 아닐 때 22:30~06:30, 미국 서머타임은 2026-11-01에 끝남)에는 시작하지 않고, 돌던 중 들어서면 결과 없이 종료 코드 4로 멈춘다. Run이 수천 개라 몇 시간 걸릴 수 있으므로 **토요일 아침(미국장 보호가 끝난 뒤)에 시작**하기를 추천한다. 월요일 08:00 전까지는 막히지 않는다. `-PallowDuringSession`은 실전 엔진과 녹화 게이트웨이를 모두 끈 날에만 쓴다.
- 둘째 줄은 등록 파일이 커밋된 그대로인지 본다. 아무것도 출력하지 않고 끝나야 한다. 차이가 나오면 실행하지 말고 스레드에 알린다(명령의 작업 트리 검사는 `experiments/`를 보지 않는다).
- 주말 안에 끝나지 않아 월요일 08:00에 종료 코드 4로 멈추면 결과 파일은 없다. 다음 주말에 같은 명령을 처음부터 다시 돌린다(이어 돌리기는 없고, 결과는 같은 입력이면 같다). 두 번 모두 못 끝나면 출력의 진행 줄(구간·대조군 시드)을 스레드에 붙인다. 대조군 수를 줄이는 것은 새 실험 ID로만 한다.
- `-PverifyDeterminism`은 전체를 병렬 1로 한 번 더 돌려 결과가 바이트까지 같은지 본다(시간이 두 배 넘게 걸린다). 다르면 결과 파일은 쓰되 `inputs.yaml`의 결정론 칸이 "다름"이 되고 종료 코드 2로 끝난다. 이 결과는 쓰지 않는다: 출력과 `inputs.yaml`만 스레드에 붙인다.
- 메모리: 힙 기본 8GB. `OutOfMemoryError`가 나면 PC 메모리 안에서 `-Pheap=12g`처럼 올려 다시 돌린다(실전 엔진이 돌지 않는 날만).
- 예열 일수가 모자라거나 구간이 스냅샷 밖이면 종료 코드 1로 바로 끝난다. 메시지를 스레드에 붙인다.
- 종료 코드: 0 완료, 1 입력 오류(같은 실험이 이미 실행 중 포함), 2 Run 실패(대조군 포함)나 결정론 불일치, 3 실험실에서 취소(결과 파일 없음, D-089), 4 정지(결과 파일 없음).
- 진행 상황은 `<설정 폴더>/experiment-state/jobs/<실험ID>.status`(실험실 `GET /api/experiment-jobs`, 실험 목록 화면의 "실행 중인 실험")에서 본다. 막대는 명령 전체 진행률(실험 수 기준, 콘솔 "계산 시작" 줄의 "실험 N회")이고 지금 실험의 진행은 그 아래 줄이다(D-097). 실험실과 실행 명령은 같은 커밋에서 띄운다(이전 실험실은 새 상태 파일 형식을 읽지 못하고 "읽지 못한 상태 파일"로 센다). 동시 Run 수는 `-PmaxParallelRuns`(없으면 실험실 설정 화면의 병렬 상한, 실험실을 한 번도 띄우지 않았으면 4, D-094)와 등록 파일 `parallelism` 중 작은 쪽이고, 실제 병렬 수가 1이면 `-PverifyDeterminism`은 "확인 안 함"으로 남는다(D-089).
- 감시의 정지 요청 파일(`<설정 폴더>/experiment-state/stop-request`)은 직접 지우지 않는다(D-063).

## 6. 스레드에 올릴 것

**종료 코드 0으로 끝났을 때만** 올린다(2면 Run 실패나 결정론 불일치라 결과를 판정에 쓰지 않는다. 출력만 붙인다). `experiments/EXP-<등록일>-WF01/`에 생긴 파일 중:

| 파일 | 올리나 |
|---|---|
| `results.md` | 예(판정표·리더보드·대조군 분포·진입 한도 영향·계산량) |
| `windows.tsv` | 예 |
| `inputs.yaml` | 예(스냅샷 해시·코드 커밋·결정론 확인 결과) |
| `runs.tsv` | 예(Run별 지표) |
| `detail.txt` | **아니오**(종목별 체결가 원장이라 원자료에 가깝다. PC에 둔다) |

명령이 출력한 실행 시간(시작·끝 시각)도 함께 적어 주면 계산량 추정을 실측으로 고친다.

## 7. 그다음 (Claude)

- `inputs.yaml`의 `study-sha256`이 커밋된 등록 파일의 SHA-256과 같은지 `git show origin/main:experiments/EXP-<등록일>-WF01/study.yaml | sha256sum`으로 확인한다. 값은 줄바꿈을 LF로 맞춘 내용 기준이라 사용자 PC의 CRLF 사본으로 돌려도 같다. 다르면 결과를 쓰지 않는다(D-083).
- 결과 파일을 커밋하고 `report.md`(요약, 조건, 리더보드, 과최적화 검사, 이상 사례, 다음 실험)를 쓴다.
- 세율 `[가정]` 대조(Q-052)가 닫히기 전에는 리포트 결론에 `[가정]`을 붙인다(D-071 7번).
- 4단계 완료 판정은 독립 QA 관문이다. 사용자에게 QA 요청 문구를 드리고 `docs/qa/pending.md`에 올린다.
