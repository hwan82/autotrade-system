# ADR-014 LIVE02 등록 전 판정 계약: 참고 계좌 역할(F1), 대조군 시드 수 키(F3), 녹화 사전 검사 명령(F4), 공시 입력 키(F9)

- 상태: 채택(D-131, 추천안 채택(자율)). 이 문서는 계약만 정한다. 구현은 sim-engine이 9장 순서로 따로 한다.
- 날짜: 2026-10-20
- 담당: architect(계약), strategy-researcher(사전 검사 판정 명세). 교차 검토: architect, strategy-researcher
- 근거: [LIVE02 사전 등록 계획](../strategy/live02-preregistration-plan.md)(이하 "계획") 2.2 G1·4장·5장·6.2·7장·8장 F1~F4, [D-128](../decisions/D-128-live02-preregistration-plan.md) 7번, [D-126](../decisions/D-126-live-judgement-selection-accounts.md)(선정 계좌 녹화 판정), [D-116](../decisions/D-116-stage-6-recording-judgement.md)(녹화 판정 명령), [D-114](../decisions/D-114-intraday-live-promotion-criteria.md)(L1~L11), [D-124](../decisions/D-124-selection-accounts-live-recording.md)(선정 조합 계좌·등록 파일), [D-127](../decisions/D-127-intraday-at-selection.md)(장중 순위 선정), [ADR-010](ADR-010-live-feed-and-session.md) 2.4, [ADR-012](ADR-012-intraday-selection.md) 3.3·3.5·배치표 5번, 검토 원문 [PR #202 architect](../reviews/pr-202/architect.md) M2(계약 변경이라 ADR 먼저)
- 번호: main과 열린 브랜치 전부에서 ADR은 ADR-012가 가장 크고, F7 스레드(선정 밖 신규 진입 미리 거름)가 ADR-013·D-130을 쓰기로 해서 ADR-014·D-131을 쓴다. D-129는 열린 PR #203이 쓴다.
- 실전 경로, RealTradingGuard, 블랙리스트 규칙, 리스크 한도, 게이트웨이, 전략·도메인 모듈(보호 경로)은 바꾸지 않는다. 바뀌는 것은 `autotrade-sim`의 등록 파일 읽기·판정 명령, `autotrade-app-experiment`의 명령 연결, `autotrade-app-lab`의 실험실 세션 시작 확인(4.3, 프로필만 읽는 순수 계산)뿐이다.
- 검토 반영: [architect](../reviews/pr-204/architect.md) M1·M2·m1~m5, [strategy-researcher](../reviews/pr-204/strategy-researcher.md) R1~R6·참고 3건.

## 1. 문제

계획 8장이 LIVE02 사전 검사·등록 전에 필요하다고 적은 후속 가운데 등록 파일 키와 실행 명령을 더하는 셋(F1·F3·F4)은 실험 계약 변경이라 구현 전에 ADR을 쓴다(architect PR #202 M2). 이 ADR을 쓰면서 넷째 빈틈(F9)도 찾았다.

- `[사실]` 녹화 판정의 계좌 역할은 프로필 `logic`만 본다. 기준선(`buy-and-hold`)·대조군(`random`)이 아니면 모두 판정 대상이다(`LiveJudgement.java` 229~277행 `roles`). 판정 대상이 있는 선정 묶음에는 같은 묶음 대조군이 있어야 하고 없으면 판정을 거절한다(같은 곳 256~266행). 그래서 계획 5장의 참고 계좌 R1 `kr-orb-30@1`·R2 `kr-volume-surge@1`(선정 없음)·R3 `kr-intraday-open-all@1 x kr-orb-30@1`(대조 선정)을 등록하면 m이 4에서 7로 늘고, R1·R2에는 선정 없는 대조군까지 필요하다.
- `[사실]` 대조군 시드 수는 N = 21m − 1로 고정이다(`LiveJudgement.java` 548~549행). 계획 6.2는 사전 검사에서 잰 왕복 0건 비율로 묶음마다 N을 늘리라고 정했는데 그것을 적을 자리가 없다.
- `[사실]` 녹화 실험(`RecordingExperiment`, 등록 `input: recording`)은 계좌별 성과와 선정 표만 내고, 대조군 시드 분포·구간 성과·P0~P3 판정이 없다(`RecordingPlan` 키 목록, `RecordingPlan.java` 75~78행).
- `[사실]` 실시간·녹화 등록 파일은 `selection-input.disclosures`를 아직 받지 않는다("공시 스냅샷 수집 뒤", `RegisteredSelections.java` 107~108행). 공시 스냅샷은 PR #196(D-123)으로 생겼고 조합·워크포워드·일봉 사전 검사 등록 파일은 이미 받는다(`SnapshotInputs`, `EdgePlan`). 그래서 계획 3.1 C3((라) `kr-intraday-news@1`, `disclosure-count` 사용)은 지금 녹화 사전 검사에도 LIVE02에도 등록할 수 없다. 계획 8장 표에 없던 빈틈이라 **F9**로 이름 붙인다(F8은 계획 메모에서 동시 세션 이름으로 이미 씀).

## 2. 결정 요약

| 번호 | 내용 |
|---|---|
| ① F1 | 실시간·사전 검사 등록 파일에 목록 키 `references`를 더한다. 항목은 `profiles`·`selections`의 줄을 글자 그대로 다시 적는다. 그 계좌는 계좌 번호·세션 동작을 바꾸지 않고, 판정 명령에서만 "참고" 역할이 되어 m·변형·지연·대조군 시드에서 빠진다 |
| ② F3 | 실시간·사전 검사 등록 파일에 목록 키 `group-null-seeds`를 더한다. 항목은 `"<묶음> <N>"`(묶음 = 선정 `id@버전` 또는 `none`). 적힌 묶음만 그 N으로 돌리고, 없는 묶음은 지금처럼 21m − 1이다. N < 21m − 1은 거절한다 |
| ③ F4 | 새 명령 `runRecordingPrecheck`와 새 등록 형식 `input: recording-precheck`(녹화 실험 키 + 사전 검사 키). 녹화를 한 번 읽어 레인(BASE + 묶음별 대조군 시드)을 함께 돌리고 `precheck.md`(P0~P3, q̂, 커버리지, 한도 도달, LIVE02용 N 표)를 쓴다 |
| ④ F9 | 실시간·녹화·사전 검사 등록 파일의 `selection-input`이 `disclosures`를 받는다. 값 규칙은 `daily`와 같다(실시간만 `latest`, 나머지는 스냅샷 ID). 읽기는 조합 실험의 `SnapshotInputs` 규칙을 재사용한다 |
| ⑤ 공통 | 새 키가 없는 등록 파일은 판정·녹화 실험 결과 파일이 지금과 바이트로 같다(LIVE01 판정 보호). 역할·시드 수 계산은 판정 명령과 사전 검사 명령이 같은 코드 한 곳을 쓴다 |

## 3. F1 참고 계좌 역할

### 3.1 키

```yaml
profiles:
  - "kr-bh-baseline@3"
  - "kr-orb-30@1"
  - "kr-volume-surge@1"
selections:
  - "kr-intraday-open-momentum@1 x kr-random-control@1"
  - "kr-intraday-open-momentum@1 x kr-orb-30@1"
  - "kr-intraday-open-all@1 x kr-orb-30@1"
references:                      # 선택. 판정하지 않는 계좌. profiles·selections 줄을 그대로 다시 적는다
  - "kr-orb-30@1"
  - "kr-volume-surge@1"
  - "kr-intraday-open-all@1 x kr-orb-30@1"
```

- 받는 등록 파일: `input: live`(`LivePlan`)와 `input: recording-precheck`(5장). 판정이 없는 `input: recording`(녹화 실험)과 조합·워크포워드 등록 파일은 모르는 키로 거절한다(지금 `RegistrationYaml` 규칙 그대로).
- 거절(등록 파일 읽을 때): `profiles`·`selections`에 없는 줄, 같은 줄 두 번, 빈 목록 키는 허용하고 키가 없는 것과 똑같이 다룬다(결과 바이트 동일 판단에서도 "키 없음", architect m2).
- 거절(역할 계산 때, 프로필을 읽은 뒤): 기준선(`logic: buy-and-hold`)이나 대조군(`logic: random`) 계좌를 참고로 적음. 참고를 빼고 판정 대상이 0개.
- 계좌 번호와 세션: `references`는 계좌를 더하지도 빼지도 않는다. 계좌 번호는 지금처럼 `profiles` 다음 `selections` 순서다(D-124). 실시간 세션·입력 기록·리플레이 확인·주간 점검은 이 키를 읽지 않으므로 결과가 키 유무와 상관없다.

### 3.2 판정 명령에서 하는 일

| 항목 | 판정 대상 | 참고 계좌 |
|---|---|---|
| m에 셈 | 예 | 아니오 |
| 선정 묶음 대조군이 필요 | 예(없으면 거절, 지금 규칙) | 아니오 |
| BASE 레인·녹화 리플레이(L11) | 예 | 예(계좌가 세션에 있으므로 그대로 돈다) |
| PERTURB(L7)·지연(L9, 0·1.5·2초) 레인 | 예 | 아니오(Run을 아낀다) |
| L8(BASE 결과로 계산, `LiveJudgeCriteria.java` 135행) | 예 | 예(참고로 냄) |
| 대조군 시드 | 그 묶음 시드를 돌림 | 그 묶음에 판정 대상이 없으면 돌리지 않음 |
| `judgement.md` 표 | 역할 "판정 대상", 판정 굵게 | 역할 **"참고"**, 판정 칸 "(참고) …"(기준선·대조군과 같은 모양). 돌리지 않은 L7·L9·L10은 "?"가 아니라 **"–"(돌리지 않음)**. 5장 지연 민감도 표에는 참고 계좌 행을 넣지 않는다(지금도 판정 대상만, `LiveJudgement.java` 786~790행) |

- 판정 결과 맵(`Outcome.verdicts`)과 "후보" 집계에 참고 계좌를 넣지 않는다. 기준선·대조군과 같은 대접이다.
- 참고 계좌가 대조군 있는 묶음에 있으면(예: (마) 묶음에 R3 같은 줄을 둔 경우) L10 칸은 "–"다. 참고 계좌의 L10을 같은 묶음 분포로 내는 것은 판정처럼 읽혀 쓰지 않는다.
- 머리말에 한 줄을 더한다: "참고 계좌 r개(판정하지 않음, m에 넣지 않음, 등록 파일 `references`)". `references`가 없으면 이 줄은 없다(바이트 동일).
- 계획 7.1의 나란히 보기 표(판정 대상 ↔ 참고 계좌)는 `judgement.md`가 아니라 strategy-researcher 판정 리포트(`report.md`)가 만든다. 값의 출처: 총수익률·왕복·왕복당 순손익·장중 노출은 4장 "함께 적을 것" 표, PF는 2·3장 L2 근거, 하루 평균 후보 수는 선정 표(9장)다. 참고 계좌도 BASE 레인에 있어 모든 장에 같은 칸으로 나온다(strategy-researcher R6).

## 4. F3 대조군 시드 수 키

### 4.1 키

```yaml
group-null-seeds:                    # 선택. 묶음별 L10 대조군 시드 수. 없는 묶음은 21m − 1
  - "kr-intraday-hot@1 96"
  - "kr-intraday-open-momentum@1 130"
  - "none 83"                    # 선정 없는 묶음
```

- 좁은 YAML 모양(`RegistrationYaml`: 묶음 하위 키는 `[a-z][a-z-]*`라 `@`·숫자를 못 씀)을 지키려고 묶음 키 대신 목록 항목 한 줄로 쓴다(`selections`와 같은 방식).
- 이름: 일봉 사전 검사(`EdgePlan`, 상한 20000)와 워크포워드(`WalkForwardPlan`, 1~5000)가 이미 스칼라 키 `null-seeds`(모든 대조군 같은 N)를 쓴다. 같은 이름을 목록 모양·다른 상한으로 쓰면 등록 파일을 옮겨 적다 헷갈리므로 **`group-null-seeds`**로 구분한다(architect m1). 상한 999가 그 둘보다 작은 것은 이 값이 판정 Run 수(묶음당 N개, 100종목 녹화)를 직접 늘리기 때문이다.
- 빈 목록 키는 키가 없는 것과 똑같이 다룬다(m2).
- 항목 모양: `<선정 id@버전 | none> <N>`, N은 1~999 정수. 999는 오타(예: 1300) 방지 상한이다 `[가정]`. 계획 6.2 표의 최대가 130이다.
- 거절(등록 파일 읽을 때): 모양이 틀림, 같은 묶음 두 번.
- 거절(역할 계산 때): 그 묶음에 대조군이 없거나 판정 대상이 없음(적은 N이 쓰이지 않는 것은 실수로 본다), **N < 21m − 1**(m은 참고 계좌를 뺀 판정 대상 수). 줄이는 쪽은 받지 않는다. 계획 6.2 "늘리는 쪽만".
- 시드: 지금처럼 실험 시드 + i, i = 1..N_k. 묶음마다 같은 시드열을 쓴다(지금과 같음). 레인 ID도 지금 그대로(`<ID>-NULL<i>`, `<ID>-S<k>-NULL<i>`)라 N을 늘려도 앞 21m − 1개 레인의 결과는 키가 없을 때와 같다.
- L10 계산은 바뀌지 않는다: p = (k+1)/(N′+1) ≤ 0.05/m, N′ + 1 < 20m이면 판정 불가(`LiveJudgeCriteria`). N만 묶음별이다.

### 4.2 보고

- 머리말 "판정 대상 m = …, 대조군 시드 N = …" 줄: `group-null-seeds`가 없으면 지금 문장 그대로. 있으면 "대조군 시드 N(묶음별): 선정 없음 83, kr-intraday-open-momentum@1 130(등록 파일 `group-null-seeds`), 나머지 21m − 1 = 83"처럼 묶음마다 적는다.
- 3장 L10 근거 줄은 이미 그 계좌 묶음 분포를 쓰므로(PR #203 시험 "L10 줄의 N이 그 묶음 N") N 값만 묶음별로 바뀐다.
- 계획 7.3 대조군 품질(묶음별 N, 왕복 0건 시드 수·비율, N′, L10 판정 가능 여부)을 `judgement.md` 새 장 "대조군 품질"로 낸다. 대조군 시드 레인이 있으면 늘 쓴다. **이것은 선정 없는 LIVE01 판정 결과도 바꾸므로**, 키가 없는 등록에서는 쓰지 않고 `group-null-seeds`나 `references`가 있을 때만 쓴다(5장 바이트 동일 원칙 우선).

### 4.3 빨리 실패

`references`·`group-null-seeds`는 8주를 돌린 뒤 판정 때 처음 틀린 것이 드러나면 되돌릴 수 없다(등록 파일은 운용 중 고치지 않는다). 그래서 **두 키 중 하나라도 있으면 실험실 세션 시작 때 같은 역할 계산을 한 번 돌려** 거절 조건(3.1·4.1)에 걸리면 세션을 시작하지 않는다. 두 키가 없는 등록(LIVE00·LIVE01)은 지금처럼 시작 때 역할을 보지 않는다(기준선 없는 시험 등록을 막지 않으려고). 세션 시작 확인은 프로필만 읽는 순수 계산이라 실시간 경로·게이트웨이에 영향이 없다.

## 5. F4 녹화 사전 검사 명령

### 5.1 등록 파일(`input: recording-precheck`)

녹화 실험 등록 파일(`RecordingPlan`)의 키를 모두 받고(`selection-input`은 스냅샷 ID만, `latest` 거절), 아래를 더한다. 사전 검사 판정 값은 코드에 두지 않고 등록 파일에 적는다. 계획 4.2가 이 실험용으로 정한 수치라서다.

```yaml
id: "EXP-20261219-PRE02"
question: "LIVE02 후보 조합이 넓힌 watchlist 녹화 40거래일에서 P0~P3를 통과하는가"
input: "recording-precheck"
market: "KRX"
from: "2026-10-26"
to: "2026-12-18"
seed: "20261219"
seed-capital: "10000000"
benchmark: "005930"
parallelism: "4"
profiles: [...]                  # 참고 R1·R2(선정 없음). 기준선은 없어도 된다
boards: [...]                    # 100종목
blacklist: ["035720"]
selections: [...]                # C1~C4, 묶음별 kr-random-control, R3
selection-input:
  daily: "<12-18 저녁 스냅샷 ID>"
  disclosures: "<스냅샷 ID>"     # C3이 (라)일 때(F9)
references: [...]                # F1
group-null-seeds:                    # F3. 사전 검사는 모든 대조군 묶음에 적어야 한다(기본값 없음)
  - "kr-intraday-hot@1 83"
  - "kr-intraday-news@1 83"
  - "kr-intraday-open-momentum@1 199"
family-size: "4"                 # m_pre. 빠진 조합도 센다(계획 4.1)
alpha: "0.05"                    # P2 문턱 = alpha / family-size
min-round-trips: "45"            # P0
p2:                              # P2를 판정할 계좌(나머지 판정 대상은 p만 참고로 적음)
  - "kr-intraday-open-momentum@1 x kr-orb-30@1"
p3:                              # "<판정 대상> vs <참고 계좌>"
  - "kr-intraday-open-momentum@1 x kr-orb-30@1 vs kr-intraday-open-all@1 x kr-orb-30@1"
p3-min-segments: "3"             # P3 구간 중 d > 0 이어야 하는 수
segments:                        # P3 구간(거래일, 양 끝 포함). 겹치거나 기간 밖이면 거절, 기간을 빈틈없이 덮어야 함
  - "2026-10-26 2026-11-06"
  - "2026-11-09 2026-11-20"
  - "2026-11-23 2026-12-04"
  - "2026-12-07 2026-12-18"
```

- 역할: 기준선은 선택(0개 또는 1개), 대조군은 묶음마다 하나까지, 참고는 `references`, 나머지가 판정 대상. 판정 대상이 있는 묶음에는 대조군이 있어야 한다(판정 명령과 같은 계산, 2장 ⑤). 공유하는 역할 계산은 "기준선 필수 여부"를 매개변수로 받는다(판정 명령·세션 시작 확인은 필수, 사전 검사는 선택, architect m4).
- 사전 검사는 `group-null-seeds`가 대조군이 있는 모든 묶음에 있어야 한다. 21m − 1 기본값을 쓰지 않는 이유: 사전 검사의 m(= `family-size`)과 시드 수는 계획 4.1이 묶음마다 따로 정했다(83·199). 하한 21m − 1 거절은 판정 명령용이라 사전 검사에는 걸지 않고, 대신 P2 판정 계좌의 묶음 N은 `⌈family-size / alpha⌉ − 1` 이상이어야 한다(P2가 판정 가능할 수 있는 최소).
- 거절: `p2`·`p3` 계좌가 판정 대상이 아님, `p2` 계좌의 묶음에 대조군이 없음, `p2`·`p3` 같은 줄 두 번, `p3` 오른쪽이 참고 계좌가 아님, `family-size` < 실제 판정 대상 수, `alpha`가 (0, 1) 밖, `min-round-trips` < 1, `p3-min-segments`가 1~구간 수 밖, 구간이 기간을 빈틈없이 덮지 않음(거래일 기준, 거래소 캘린더로 셈)(strategy-researcher R5).
- 판정에 쓰는 수(`alpha`·`family-size`·`min-round-trips`·`p3-min-segments`)는 문자열을 `BigDecimal`·정수로 읽고 `double`로 바꾸지 않는다.

### 5.2 실행

- 명령: `./gradlew :autotrade-app-experiment:runRecordingPrecheck -Pexperiment=<실험ID> [-PrecordingsDir=폴더] [-PsnapshotsDir=폴더] [-PallowDuringSession] [-PmaxParallelRuns=N] [-Pheap=…]`. `ExperimentMain`의 새 하위 명령 `recording-precheck`. 장 시간 보호·정지 요청 파일(D-063·D-085), 병렬 상한 파일(D-094), 명령 전체 진행률(D-098)은 녹화 판정과 같은 `RunGate`로 받는다.
- 입력: 녹화 실험과 같은 녹화 읽기(`StreamedRecordingDataSet`, 기간과 상관없는 메모리, D-115·D-117)와 같은 선정 준비(일봉·공시 스냅샷으로 날마다 장전 선정, `selections/` 폴더에 쓰기, D-124). 장중 순위 선정은 레인마다 추적기(D-129)다.
- 레인: **BASE**(등록 계좌 전부, 기본 지연 0.5초) + **대조군 시드**(묶음마다 `group-null-seeds` N개, 시드 = 실험 시드 + i, 레인 ID는 판정 명령과 같은 규칙). 변형·지연·녹화 대 입력 기록 레인은 없다(P0~P3에 쓰지 않음). 레인 묶음 나누기·병렬은 판정 명령의 `groups`를 그대로 쓴다(결과에 영향 없음).
- 결정론: 같은 등록 파일·같은 녹화·같은 스냅샷이면 `precheck.md`가 바이트로 같다. 실행 시간처럼 매번 달라지는 값은 결과 파일에 쓰지 않고 로그에만 쓴다(계획 4.3 "판정 시간"은 로그 끝 줄 "걸린 시간 hh:mm:ss"로 잰다). 병렬 대 직렬 확인 옵션은 녹화 실험의 `--verify-determinism`과 같은 뜻으로 받는다.

### 5.3 계산(계획 4.2·4.3 그대로)

| 칸 | 정의 |
|---|---|
| 왕복당 순손익 | (기간 끝 평가금액 − 시드머니) ÷ 왕복 수(`LiveJudgeCriteria.perTrip`과 같은 함수). 왕복 0건이면 없음 |
| P0 | 왕복 수 ≥ `min-round-trips` |
| P1 | 왕복당 순손익 > 0. 왕복 0건이면 판정 불가 |
| P2 | 같은 묶음 대조군의 왕복당 순손익 분포(완료·왕복 1건 이상, N′개)에서 k = 그 계좌 값 이상인 시드 수, p = (k+1)/(N′+1). `p2` 계좌는 **(k+1)·family-size ≤ alpha·(N′+1)**이면 통과(같음은 통과), N′ + 1 < ⌈family-size ÷ alpha⌉이면 판정 불가. 두 비교 모두 `BigDecimal`·정수로 하고 `double`을 쓰지 않는다(L10의 정수 곱 `LiveJudgeCriteria.java` 272행과 같은 방식, architect M1·strategy-researcher R2). 표시용 p만 `BigDecimal` 나눗셈 후 소수 5자리. 다른 판정 대상은 p만 "(참고)"로 적음 |
| P3 | 구간 s마다 d_s = (판정 대상 구간 순손익 ÷ 구간 왕복 수) − (참고 계좌 같은 값). 구간 순손익 = 구간 마지막 거래일 일별 평가금액 − 직전 구간 마지막 거래일 일별 평가금액(첫 구간은 시드머니). 일별 평가금액은 그날 마지막 `EquityPoint`이고, 그날 점이 없으면(녹화 없는 거래일 등) 그 날 이전 가장 가까운 점을 쓴다(점이 하나도 없으면 시드머니, architect m5). 장 마감 청산을 못 해 밤을 넘긴 포지션이 있으면 손익과 왕복이 다른 구간에 들어갈 수 있어, 구간 경계를 넘긴 보유 건수를 4장 구간 표에 적는다(strategy-researcher 참고). 구간 왕복 = 청산일(`RoundTrip.exitDate`)이 구간 안인 왕복. 어느 한쪽이 구간 왕복 0건이면 그 구간은 d > 0이 아님. 전체 기간 d(왕복당 순손익 차) > 0이고 d_s > 0인 구간 수 ≥ `p3-min-segments`이면 통과 |
| 결과 | 판정 대상마다 "통과(LIVE02 후보)": 그 계좌에 걸린 P가 모두 통과, "탈락": 하나라도 탈락, "판정 불가": 탈락 없이 판정 불가가 있음 |
| q̂ | 묶음마다 대조군 시드 중 왕복 0건 비율 x/N과 단측 95% Clopper–Pearson 상한(x = N이면 1, 아니면 P(이항(N, q) ≤ x) = 0.05인 q. 이항 누적을 `BigDecimal`(유효숫자 34자리) 정확 합으로 세고 q를 이분법으로 1e-9까지 좁힌다. `Math.pow`·`exp`·`log`는 플랫폼마다 끝자리가 다를 수 있어 쓰지 않는다, architect M1·strategy-researcher R3). 결과 파일에는 소수 4자리로 **올림**해 적고, N 표 계산은 반올림 전 상한을 쓴다(R4) |
| LIVE02 N 표 | 묶음마다 q = 위 상한(반올림 전), m = 1~4 각각에 대해 N_k = 21m − 1 이상인 가장 작은 N 중 P(이항(N, 1−q) ≤ 20m − 2) ≤ 0.01인 것(계획 6.2 3번, 같은 정확 산술). 점추정 x/N ≥ 0.5인 묶음은 계산하지 않고 "묶음 빼기(계획 6.2 1번)"로 적고, N이 999(키 상한)를 넘으면 계산을 멈추고 "999 초과"로 적는다(q가 1에 가까우면 끝나지 않으므로, strategy-researcher R1). 왕복 0건 시드가 0개여도 상한은 0이 아니다(N = 83이면 약 0.035, m = 4에서 N = 87)이라 LIVE02에는 F3가 사실상 필요하다. 시험 고정값: q = 0, 0.05, 0.1, 0.3에서 m = 4 → 83, 89, 96, 130, m = 2 → 41, 45, 49, 68(계획 6.2 표와 같음, 이 ADR 작성 때 같은 식으로 다시 셈) |
| 구독 커버리지 | 장전 선정 묶음마다 날마다 (선정 ∩ boards) ÷ 선정 수, `NOT_SUBSCRIBED`·`BLACKLISTED`·`SELECTION_MISSING` 날 수, 구간 평균. 4구간 평균이 50% 미만이면 결과 파일에 "ADR-012 7장 되돌릴 조건 알림" 줄(계획 4.3) |
| 한도 도달 | 판정 대상·참고 계좌마다 신규 진입 체결 수가 프로필 `limits.max-entries-per-day`에 닿은 날 수, 그날 어느 체결 시점이든 보유 종목 수(체결 직후 열린 종목 수)가 `sizing.max-positions`에 닿은 날 수, 거절 사유(`OrderRejection.code`)별 건수. **체결 기준**이라 신호가 한도에 막힌 날은 세지 못한다 `[추론]`(전략 안 한도는 실행 결과에 기록이 없다). 한도 키가 없는 프로필은 "–" |

- 비용 차감 성과만 쓴다(지침 5장). 금액 계산은 `BigDecimal`, 비교는 판정 명령과 같은 반올림 규칙이다.
- 판정 비교와 q̂·N 표는 모두 정확 산술(`BigDecimal`·정수)이다. Java 17의 엄격 부동소수(JEP 306)는 사칙연산만 플랫폼과 상관없게 하고 `Math.pow`·`log` 같은 함수는 1 ulp 차이를 허용하므로 결정론 근거로 쓰지 않는다(architect M1). 결과 파일에는 정해진 자리수 문자열만 쓴다.

### 5.4 결과 파일

등록 파일 옆에 쓴다. 저장소에 커밋하는 것은 `precheck.md`와 `inputs.yaml`, `selections/`는 녹화 실험처럼 커밋하지 않는다(크고, 내용 해시가 `inputs.yaml`에 있다).

- `inputs.yaml`: 녹화 실험 `inputs.yaml`과 같은 모양(데이터셋 버전, 녹화일별 파일 SHA-256·줄 수·종료 상태·재기동 공백, 선정 입력·적용 기록 해시). 같은 함수를 쓴다.
- `precheck.md` 장: 1. 머리말(기간, 코드 커밋, 데이터 버전, 비용·체결, 레인·Run 수, m_pre·alpha·문턱, 참고 계좌 수) 2. 판정 요약(계좌 | 선정 x 전략 | 역할 | 결과 | P0 | P1 | P2 | P3) 3. 항목별 근거 4. 구간 표(계좌 × 구간: 순손익, 왕복, 왕복당) 5. 대조군 품질·q̂·LIVE02 N 표 6. 구독 커버리지 7. 한도 도달·거절 사유 8. 선정 표(녹화 실험 `selectionTable`·장중 순위 표 재사용). 맨 앞 인용 줄에 "사전 검사 통과는 승격 근거가 아니다(계획 4.2)"와 투자 자문 아님을 적는다.
- 종료 코드: 계산을 끝내면 0(결과에 탈락이 있어도 0), 입력이 틀리면 1, 정지 요청으로 멈추면 녹화 판정과 같은 코드. 결과 파일은 끝까지 계산했을 때만 쓴다.

## 6. F9 공시 입력 키

- `selection-input.disclosures`를 받는다. 값 규칙은 `daily`와 같다: 실시간 등록(`LivePlan`)은 `latest` 또는 스냅샷 ID, 녹화 실험·사전 검사는 스냅샷 ID만(`latest` 거절, ADR-012 3.5).
- 선정 프로필에 공시 지표(`Metric.Kind.DISCLOSURE`) 조건이 있는데 `disclosures`가 없으면 등록을 거절하고, 조건이 없는데 있으면 거절한다. 이 일치 검사는 지금 일봉 사전 검사에만 있으므로(`SelectionEdgeStudy.java` 147~151행) 같은 판단을 녹화·실시간 선정 준비에 옮긴다. 스냅샷 읽기 자체는 조합 실험의 `SnapshotInputs`를 재사용한다. 공시 스냅샷 coverage가 T − 1 미만이면 그날 선정은 `STALE_INPUT`으로 실패하고 그날 그 묶음 신규 진입은 0이다(ADR-012 3.2, 지금 실패 처리 그대로).
- 실시간 준비 작업(`LiveSelectionPrep`, D-124)은 `latest`를 "준비 시점에 T − 1을 덮는 가장 최근 공시 스냅샷"으로 푼다. 일봉 `latest`와 같은 규칙이다. 고른 스냅샷 ID는 적용 기록에 남고 리플레이 확인·판정은 그것을 읽는다(D-124 결정론 그대로).
- 지금 `disclosures`를 거절하는 메시지와 그 시험은 이 변경으로 바뀐다. 공시 없는 등록의 결과 파일은 바이트로 같다.

## 7. 다른 작업과의 관계

- **F7(D-130·ADR-013, 별도 스레드, 실전 관련)**: 선정 밖 신규 진입을 전략 문맥(`StrategyContext`)으로 신호 전에 거른다(F7-a). 이 ADR은 F7-a가 머지된 코드를 전제로 사전 검사를 돌린다(계획 G1). F1·F3·F4·F9는 `ManagedStrategy`·`RunStepper`·`StrategyContext`를 고치지 않으므로 F7 PR과 같은 파일을 만지지 않는다. F7-a 뒤에는 `RunStepper`의 "막힌 신규 진입" 수가 0에 가까워질 수 있다 `[추론]`. 사전 검사 7장(한도 도달)은 체결·거절 기록으로만 세므로 그 숫자에 기대지 않는다.
- **F2(D-129, PR #203, 검토 중)**: 장중 순위 계좌 녹화 판정과 레인마다 추적기. F4는 이 레인 구성을 그대로 쓰므로 **F2 머지 뒤** 구현한다. F1·F3의 `LiveJudgement` 변경도 PR #203과 같은 파일을 고치므로 PR #203 머지 뒤 시작한다(같은 파일 동시 작업 금지, 지침 10장).
- **F6**(`LiveJudgeCriteria` 설명 문장): F1·F3 구현 PR에 함께 넣어도 된다(같은 패키지, 결과 영향 없음).
- 실험실 화면(#/live)의 계좌 목록에 참고 역할 표시는 이 ADR 범위 밖이다. 세션·주간 점검이 `references`를 읽지 않으므로 화면도 지금처럼 모든 계좌를 같은 모양으로 보인다. 필요하면 ui-dev 후속으로 남긴다.

## 8. 대안 검토

| 쟁점 | 채택 | 버린 안과 이유 | 되돌릴 조건 |
|---|---|---|---|
| 참고 계좌 표시 방법 | 등록 파일 목록 `references`에 계좌 줄을 다시 적음 | (가) `profiles` 줄 뒤에 꼬리표(`"kr-orb-30@1 reference"`): 지금 모든 등록 파일 읽기의 줄 모양과 계좌 번호 규칙을 바꾸고 조합·워크포워드 읽기와 갈라진다. (나) 전략 프로필 파라미터(`study.role: reference`): 역할은 실험마다 다른데 프로필은 실험 사이에 같이 쓰고, 프로필 해시가 바뀌어 같은 전략이 새 버전이 된다. (다) 참고 계좌를 등록하지 않고 사전 검사 결과로만 7.1 표를 씀(계획 F1 없을 때 안): LIVE02 실시간 결과에 나란히 볼 계좌가 없어 선정의 몫을 같은 8주로 못 본다 | 역할 종류가 셋을 넘게 늘면 `roles` 묶음 키(하위 키 = 계좌 번호)를 새 ADR로 |
| 참고 계좌 레인 | BASE·L11만 | 판정 대상처럼 변형·지연까지 돌려 참고로 L7·L9도 냄: 계좌당 5개(변형 2 + 지연 3)라 R1~R3 셋이면 Run이 15개 늘고 참고 칸에 쓰지 않는 값이다 | 리포트가 참고 계좌의 L7을 요구하면 |
| 시드 수 키 모양 | 목록 `null-seeds: "<묶음> <N>"` | (가) 묶음 키 `null-seeds:` 아래 하위 키: 하위 키 모양이 `@`·숫자를 못 받아 선정 ID를 못 씀(`RegistrationYaml` SUB 패턴), 모양을 넓히면 모든 등록 파일 읽기가 바뀜. (나) 단일 스칼라 `null-seeds: "130"`(모든 묶음 같은 N): 계획 6.2가 묶음별 q_k로 다르게 정함, 큰 쪽에 맞추면 Run이 묶음당 수십 개 낭비 | — |
| N 하한 | 21m − 1 미만 거절 | 1 이상 아무 값: 줄이면 L10이 판정 불가로 떨어질 위험이 지금보다 커져 사전 등록 취지와 반대 | 사전 검사 m_pre처럼 판정 명령 m과 다른 가족을 쓰는 등록이 생기면 |
| 사전 검사 위치 | 새 명령·새 `input` 값 | (가) 녹화 실험(`runRecordingExperiment`)에 키를 더해 판정까지: 녹화 실험 결과 파일이 계좌별 표 하나라는 계약(D-069)이 바뀌고, 대조군 시드 수백 Run이 녹화 실험에 섞여 `--verify-determinism`이 두 배로 무거워짐. (나) `runLiveJudgement`에 녹화 입력 옵션: 판정 명령은 끝난 세션의 입력 기록·실시간 보고서를 전제로 한 무사고 I1~I3·L11이 핵심이라 녹화에는 맞지 않음, 대신 역할·레인 구성 코드만 공유 | 사전 검사가 LIVE02 한 번으로 끝나지 않고 매 실험 반복되면 판정 명령과 한 명령으로 합치는 것을 다시 봄 |
| 사전 검사 문턱 위치 | 등록 파일 값(`alpha`·`min-round-trips`·`p3-min-segments`) | 코드 상수: 계획 4.2 수치는 이 실험용 `[가정]`이 섞여 있어(P0 1.5배 여유), 다음 실험에서 바꾸려면 코드 변경이 필요해짐 | — |
| 공시 입력 | 실시간·녹화 등록 파일이 `disclosures`를 받음(F9) | C3을 LIVE02에서 빼고 등록: 계획 3.1 사전 고정 짝을 줄이는 것이라 결과와 무관하게 m이 줄어듦. 선정 프로필 (라)의 공시 조건을 뺀 새 버전: D-125 4장 규칙(공시 대조 `nodisc`와 짝)과 섞임 | 공시 스냅샷 수집이 멈추면 C3은 계획 3.1 규칙대로 빠짐 |

## 9. 구현 순서와 시험(sim-engine)

1. **PR A(F9, PR #203과 무관)**: `RegisteredSelections`·`LiveSelectionPrep`·녹화 실험 선정 준비가 `disclosures`를 받음. 시험: 실시간 `latest`·녹화 스냅샷 ID 읽기, `latest` 거절(녹화), 조건 없는데 공시 입력 있음 거절, 공시 조건 있는데 없음 거절, coverage 부족 날 `STALE_INPUT`, 공시 없는 등록 결과 파일 바이트 동일(main과 `cmp`).
2. **PR B(F1·F3·F6, PR #203 머지 뒤)**: 역할·시드 수 계산을 한 곳(예: `com.autotrade.sim.live.JudgeRoles`)에 두고 `LiveJudgement`·세션 시작 확인이 같이 씀. 시험: 3.1·4.1 거절 조건 하나씩, 참고 계좌가 m·대조군 요구·변형·지연에서 빠짐, 참고 칸 "–", 묶음별 N 레인 수, 앞 21m − 1개 대조군 레인 결과가 키 없을 때와 같음, **`references`·`group-null-seeds` 없는 등록의 `judgement.md`가 main과 바이트 동일**(LIVE01 모양 고정 등록), 키가 있으면 세션 시작 때 거절되는지(실험실 시작 확인).
3. **PR C(F4, A·B 뒤)**: `RecordingPrecheck`(가칭)·`PrecheckPlan`·`ExperimentMain recording-precheck`·Gradle `runRecordingPrecheck`. 시험: 5.1 거절 조건, P2 경계를 `alpha` 소수 문자열로(예: 0.05·4·N′+1 = 80에서 k+1 = 1 통과, 0.1 같은 이진 표현 안 되는 값), P0~P3 경계(같음은 P0 통과·P1 탈락·P2 통과, 구간 왕복 0건은 d > 0 아님, 구간이 기간을 덮지 않으면 거절), Clopper–Pearson 고정값(x = 0, N = 199 → 0.0150(올림), x = N → 1), q̂ ≥ 0.5 "묶음 빼기"·"999 초과" 표시, LIVE02 N 표 고정값(5.3), 같은 입력 두 번 바이트 동일, 병렬 1 대 4 바이트 동일, 장중 순위 묶음 포함 끝단 시험, 정지 요청에서 결과 파일 없음.
4. 각 PR은 architect 검토, PR C는 strategy-researcher 검토(판정 계산이 계획 4.2와 같은지)도 받는다. 보호 경로를 바꾸지 않으므로 실전 관련 PR이 아니다(`scripts/ci/protected-paths.txt`). 실험 명령 쪽이 `autotrade-app-experiment`라 ModuleOwnership 표 변경은 없다(새 클래스는 기존 `com.autotrade.sim`·`com.autotrade.app.experiment` 패키지 안).

## 10. 위험과 한계

- **판정 시간**: 사전 검사 Run 수 ≈ 등록 계좌 약 10 + 대조군 83 + 83 + 199 = 약 375개, 100종목 40거래일 녹화. LIVE01 판정(163 Run, 10종목) 추정의 몇 배가 걸릴 수 있다 `[추론]`. 12-18 저녁에 시작해 12-19~20 안에 끝나야 LIVE02 등록이 그대로 간다. PR C 머지 뒤 지금 쌓인 녹화(10-26 이후 며칠)로 시험 실행해 시간을 재고, 너무 길면 결과를 바꾸지 않는 최적화(묶음 수 상한 `MAX_GROUPS` 조정 등)를 따로 한다.
- **한도 도달은 체결 기준**: 전략 안 한도는 실행 결과에 남지 않아 신호가 막힌 날을 못 센다. 계획 4.3의 "한도 때문에 못 산 종목"은 과소 추정이다 `[추론]`. 정확히 재려면 전략이 한도 도달을 보고하는 계약이 필요한데 전략 모듈(보호 경로) 변경이라 이 ADR에 넣지 않는다.
- **참고 계좌 L7·L9 없음**: 참고 계좌는 변형·지연을 돌리지 않는다. 판정 대상과 참고 계좌를 L7·L9로 나란히 비교할 수 없다.
- **N 하한 고정**: 판정 명령에서 N은 늘리기만 한다. 사전 검사 q̂가 크게 나와 N이 수백이 되면 판정 시간이 늘어난다. 계획 6.2 1번(q̂ ≥ 0.5 묶음 빼기)이 상한 역할을 한다.
- **F9 실시간 준비**: 공시 `latest` 풀이가 장전 준비 시각에 공시 수집이 끝나 있다는 가정에 기댄다. 수집이 늦으면 그날 (라) 묶음은 `STALE_INPUT`으로 진입 0이다(안전한 쪽 실패, 표본만 줄어듦).
- 이 결과는 투자 자문이 아니다.

## 11. 되돌릴 조건

- 사전 검사가 LIVE02 뒤에도 반복해 쓰이면 판정 명령과의 공통 부분을 더 묶는 새 ADR을 쓴다.
- 참고 계좌가 화면·주간 점검에서도 구분돼야 하면(예: 리더보드에서 빼기) 세션 결과 계약을 바꾸는 것이라 ADR-010 대체 ADR로 다룬다.
