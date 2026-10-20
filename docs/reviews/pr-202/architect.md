# architect 검토: LIVE02 사전 등록 계획 초안(D-128, Q-056)
- 대상: PR #202 / 커밋 be14c617e5081e2ff54345a761bc19bfe998d401 / main(a93e3ed) 대비 diff `git diff origin/main...HEAD`
- 변경 파일(전체): `docs/decisions/D-128-live02-preregistration-plan.md`(새 파일), `docs/decisions/README.md`(D-128 행, Q-056 행), `docs/strategy/live02-preregistration-plan.md`(새 파일). 코드·설정·스키마·보호 경로 변경 없음(`scripts/ci/protected-paths.txt` 패턴과 대조, 걸린 경로 없음).
- 점검표: B
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD`, diff 전문 읽음.
  - **`./gradlew build`는 돌리지 않았다.** 이 검토를 맡긴 쪽이 같은 작업 트리를 다른 검토자가 쓰고 있다며 Gradle 실행을 금지했다. 검토 규칙상 직접 빌드가 필요하므로 B1은 "확인 못 함"이다. 참고로 `gh api .../commits/be14c61.../check-runs` 조회 시점에 CI "Gradle build"는 `in_progress`, "DB schema", "Lab screens", "Secret scan and collector boundary"는 `success`였다(판정 근거로 쓰지 않음).
  - 문서가 인용한 코드 줄을 저장소에서 직접 확인(아래 표).
  - KRX 캘린더(`autotrade-marketdata/src/main/resources/com/autotrade/marketdata/calendar/krx.json`의 `holidays`)로 거래일을 다시 셈: 2026-12-21~2027-02-19 = 40일, 2027-01-18 앞 17일·뒤 23일, 2026-10-26~12-18 = 40일, 4구간 각 10일. 문서 값과 같다.
  - 열린 원격 브랜치 119개에서 D-128·Q-056 번호 충돌 검색: 이 브랜치 말고 없음.

## 인용 사실 대조

| 문서 주장 | 결과 | 근거 |
|---|---|---|
| 실험실 세션은 한 번에 하나(`LiveLabService` 53행) | 맞음 | `autotrade-app-lab/.../live/LiveLabService.java:53` |
| 2026-01-02 결제분부터 열린 세율 구간(`CostModels` 69행) | 맞음 | `autotrade-sim/.../cost/CostModels.java:69` `Period.open(c2026, ...)` |
| 선정 계좌도 전략은 모든 이벤트를 받고 후보 밖 신규 진입 신호만 막힘(`RunStepper` 142~148행) | 맞음 | `autotrade-sim/.../run/RunStepper.java:142-148` |
| buy-and-hold 종목당 (첫 현금 × 99%) ÷ slots(`BuyAndHold` 37~49행) | 맞음 | `BuyAndHold.java:37-49`, `strategies/kr-bh-baseline/v2.yaml` `sizing.cash-buffer-pct: "1"`. slots 상한 100(`BuyAndHold.java:32` `p.count("sizing.slots", 1, 100)`)이라 v3 slots 100은 허용 경계값 |
| N = 21m − 1 고정(`LiveJudgement` 164~167행) | 맞음 | `LiveJudgement.java:164-167`, `:548-550` |
| INTRADAY_AT 등록은 녹화 판정 거절(140~144행) | 맞음 | `LiveJudgement.java:140-144` |
| 기준선·대조군 아닌 계좌는 모두 판정 대상(`roles` 229~277행) | 맞음 | `LiveJudgement.java:229-277`. 선정 없는 판정 대상에 선정 없는 대조군이 없으면 예외(`:262-266`)라 F1 없이 R1·R2를 넣으면 판정 명령이 아예 실패한다. 문서의 "F1이 없으면 R1~R3을 빼고 등록"과 맞음 |
| 왕복 0건 시드는 L10 분포에서 빠짐 | 맞음 | `LiveJudgeCriteria.java:94` |
| `LiveJudgeCriteria` 설명 "L5 미달은 탈락"이 코드와 다름(F6) | 맞음 | 설명 `LiveJudgeCriteria.java:45-46`, 코드 `:129-132`는 NOT_AVAILABLE |
| 녹화 실험(`RecordingPlan`)에 대조군 시드 분포 키 없음 | 맞음(검색 범위 안) | `autotrade-sim/.../recording/` 안에 해당 키 없음. 녹화 실험 자체는 INTRADAY_AT를 받는다(`RecordingExperiment.java:179-201, 342`) |
| D-114는 LIVE01 기준, m = 6 | 맞음 | `D-114-...md:10, 14` |
| ADR-012 3.3 m ≤ 4, 배치표 5번 | 맞음 | `ADR-012-intraday-selection.md:63, 179` |
| 프로필·선정 파일 존재, key-param | 맞음 | `strategies/kr-orb-30/v1.yaml:25,30`, `strategies/kr-volume-surge/v1.yaml:24,30`, `selections/kr-intraday-{hot,news,news-nodisc,open-momentum,open-all}/v1.yaml`. `kr-bh-baseline` v3 없음(F5로 남김, 맞음) |
| 2027-01-04 10:00 개장, 11-19 수능일 | 맞음(`[가정]` 표기 맞음) | `krx.json` `specialSessions`, `verified: false` |

## 점검표 B

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 검토를 맡긴 쪽 지시로 `./gradlew build`를 직접 돌리지 않았다. 변경은 문서 3개뿐이라 빌드 결과에 영향이 있을 코드 변경은 없다(`git diff --stat`) |
| B2 | 해당 없음 | Java·Gradle·ArchUnit 변경 없음 |
| B3 | 해당 없음(이 PR) / 후속 조건 있음 | 이 PR은 계약을 바꾸지 않는다. 다만 8장 F1(등록 파일 `references:` 키), F3(등록 파일 묶음별 N 키), F4(새 실행 명령과 결과 파일)는 등록 파일 형식·실행 명령 계약 변경이라 구현 PR보다 ADR(ADR-012 개정 또는 새 ADR)이 먼저다. 문서에 이 조건이 없다 → 아래 M2 |
| B4 | 해당 없음(이 PR) | 코드 변경 없음. 계획 내용은 결정론 원칙과 맞는다: 대조군 시드 = 실험 시드 + i(`LiveJudgement.java:350-351`와 같음), 스냅샷 ID 고정 후 결과 전 커밋, 결과 파일 바이트 재현 요구 |
| B5 | 해당 없음 | 코드 변경 없음 |
| B6 | 해당 없음 | 코드 변경 없음, 테스트 삭제·건너뜀 없음 |
| B7 | 해당 없음(이 PR) | 실전 주문 경로, RealTradingGuard, 블랙리스트 규칙, 리스크 한도, 게이트웨이, 보호 경로 변경 없음. 8장 F1~F4·F6이 `autotrade-sim`·실험 명령 범위라는 문서의 `[추론]`(8장 머리, 9.2 표)은 확인함: 해당 코드는 `autotrade-sim/.../live/`·`recording/`에 있고 보호 경로가 아니다. 단, 아래 M1의 해결이 `autotrade-strategy`(보호 경로 `autotrade-strategy/*`)나 `autotrade-domain`의 `StrategyContext`를 고치게 되면 그 후속 PR은 실전 관련 PR이며 risk-ops 검토 필요 |
| B8 | 통과 | diff에서 API Key·토큰·계좌번호 형태 검색 결과 없음. 종목 코드(035720, 005930)와 실험 시드만 있음 |

## 점검표 밖 발견

### M1 (수정 필요) 막힌 신규 진입 신호가 전략의 하루 진입 한도를 소진한다: `[추론]`이 아니라 `[사실]`이고, LIVE02 결과를 크게 바꾼다

3.1 둘째 글머리는 "후보 밖 종목에서 막힌 신호가 전략 안의 상태를 쓰는지는 전략마다 다를 수 있다 `[추론]`. 4장 사전 검사 결과의 막힌 신규 진입 수로 확인한다"라고 미뤘다. 코드로는 이미 정해져 있다.

- `[사실]` `kr-orb-30`(`OpeningRangeBreakout`), `kr-volume-surge`(`VolumeSurge`), `kr-random-control`(`RandomEntry`)은 모두 `ManagedStrategy`를 상속한다(각 파일 `extends ManagedStrategy`).
- `[사실]` `ManagedStrategy.entry`는 신호를 낼 때 `entriesToday++`를 하고 주문 신호를 돌려준다(`autotrade-strategy/.../catalog/ManagedStrategy.java:264`). 한도 검사는 `entriesToday >= rules.maxEntriesPerDay()`(`:279`). `armedAt`도 그때 지운다(`:248`).
- `[사실]` 막는 쪽은 전략 바깥 `RunStepper`라(`RunStepper.java:142-148`) 전략은 신호가 막혔는지 모른다. 전략 쪽 맥락(`StrategyContext`)에 후보 여부가 없다(`autotrade-strategy`·`autotrade-domain`에서 candidate/selection 검색 결과 없음).
- 세 프로필 모두 `limits.max-entries-per-day: "6"`(`strategies/kr-orb-30/v1.yaml:22`, `kr-volume-surge/v1.yaml:20`, `kr-random-control/v1.yaml:20`).

`[추론]` 영향: LIVE02는 `boards` 100종목에 후보가 하루 몇 개(C4는 최대 10개, `selections/kr-intraday-open-momentum/v1.yaml` `max-symbols: "10"`)다. 후보 밖 90여 종목의 돌파·급증 신호가 먼저 나면 그날 6건 한도가 후보 밖 신호로 차고, 후보 종목은 진입을 못 한다. 그러면
1. P0(왕복 ≥ 45)와 L5(왕복 ≥ 30)가 가설과 상관없이 줄어든다(판정 불가·사전 검사 탈락 쪽으로 치우침).
2. 판정 대상의 진입이 "후보 중 그날 가장 먼저 신호 난 종목"이 아니라 "100종목 중 6번째 안에 신호 난 후보"로 바뀐다. 이는 프로필 가설(3.1 표 "왜 이 짝인가")이 말하는 진입 규칙이 아니다.
3. L10 대조군(같은 선정 × `kr-random-control`)도 같은 소진을 겪지만, 무작위 진입의 신호 빈도·시각 분포가 ORB·거래량 급증과 달라 소진 정도가 다르다. 그래서 L10이 "진입 규칙의 몫"이 아니라 "한도 소진 정도 차이"를 섞어 본다.
4. P3(C4 대 R3 `kr-intraday-open-all`)는 R3의 후보가 boards 전부라 소진이 없고 C4만 소진된다. 선정 효과 비교가 한쪽만 깎인 비교가 된다(PR #200 m1이 걱정한 "한도 때문에 못 산 종목"과 같은 종류이지만 더 크다).
5. L7 변형(±20%)·L8·L9 레인도 같은 소진을 겪어 비교는 같은 조건이지만, 기본 레인 표본 자체가 줄어든다.

고칠 것(이 PR의 문서 수정):
- 3.1 글머리를 `[사실]`로 바꾸고 위 코드 근거를 적는다.
- 8장에 후속 항목을 더한다(예: F8 "선정 계좌에서 후보 밖이라 막힌 신규 진입이 전략의 하루 진입 수·신호 상태를 쓰지 않게 함"). 담당과 계약: 해결 방식 후보는 (가) `StrategyContext`에 그날 신규 진입 가능 여부를 묻는 메서드를 더해 `ManagedStrategy.entry`가 한도 계산 전에 거름(`autotrade-domain`·`autotrade-strategy` 계약 변경, 보호 경로라 실전 관련 PR, risk-ops 검토·ADR 필요), (나) `RunStepper`가 막은 신호를 전략에 되돌려 알림(새 콜백, 역시 전략 계약 변경), (다) 고치지 않고 받아들임. (다)를 고르면 프로필 가설과 측정 대상이 다르다는 것을 3.1·6.1·11장에 적고, 사전 검사에서 "한도 도달 날 수 중 후보 밖 신호로 찬 날"을 따로 세도록 4.3에 칸을 더해야 한다. 추천은 문서 작성자(strategy-researcher)가 정하되, 어느 쪽이든 결과를 보기 전에 정해 G1에 넣는다.
- 기존 선정 조합 녹화 실험(D-124)·판정(D-126)에도 같은 영향이 있으므로 그 결과를 해석할 때도 적용됨을 11장 위험에 한 줄 적는다(그 문서들을 고치라는 것은 아님).

### M2 (수정 필요) F1·F3·F4는 계약 변경이라 구현 전 ADR이 필요하다는 조건을 적을 것

8장 표는 F1(등록 파일에 `references:` 키), F3(등록 파일에 묶음별 N 키), F4(새 실행 명령과 결과 파일 형식)를 "sim-engine(architect 검토)"로만 둔다. 등록 파일 키와 실행 명령은 모듈 간 계약이라(지침·`autotrade-java-dev` 작업 절차 2번) 구현 PR 전에 ADR-012 개정이나 새 ADR이 먼저다. 8장 머리나 G1에 "F1·F3·F4는 ADR 먼저"를 적는다. 설계 의견:
- F1: 새 최상위 키보다 기존 `profiles`·`selections` 줄에 역할 표시를 붙이는 안과 비교해 ADR에서 고른다. 어느 쪽이든 `roles()`(`LiveJudgement.java:229`)가 참고 계좌를 판정 대상·m·대조군 요구에서 빼야 하고, 리플레이 확인(`LiveReplayCheck`)·실시간 세션의 계좌 번호 규칙(5장 "profiles 다음 selections 순서")이 바뀌지 않아야 한다.
- F3: 지금 `nullSeeds`는 한 값으로 모든 묶음에 쓴다(`LiveJudgement.java:167, 346-351`). 묶음별 N을 받으면 결과 파일 6장·머리 줄(`:692`) 형식도 바뀐다. "선정 없는 등록은 바이트로 같음"(D-126) 같은 회귀 고정 조건을 ADR에 적는다.
- F4: 판정 명령의 레인 구성을 녹화 입력에 재사용하는 안(문서 `[추론]`)에 동의한다. 그렇게 하면 F2(INTRADAY_AT 녹화 판정 레인 추적기)가 F4의 선행 조건이므로 G1의 순서를 F2 → F4로 적는다.

### m1 (경미) 3.2 대조군 Run 수 계산

`[사실]` 대조군 레인은 판정 대상이 있는 대조군 묶음(`roles.nullControls`)마다 N개다(`LiveJudgement.java:346-351`). LIVE02(F1 적용)에는 선정 없는 대조군이 없으므로 C1~C4면 g = 3, 대조군 Run = 3 × 83 = 249다. 문서의 "4 × 83 = 332"는 D-126 위험의 (1 + g) 식을 그대로 쓴 것으로 보인다. 판정 시간 위험을 크게 잡는 쪽이라 해는 없지만 고친다.

### m2 (경미) 6.2 결정 순서

q̂_k ≥ 0.5로 묶음을 빼면 m이 줄고 N_k 식의 m도 바뀐다. "① q̂_k ≥ 0.5 묶음 제외 → ② m 확정 → ③ 남은 묶음마다 N_k"처럼 순서를 적는다. 사전 검사의 m_pre = 4·N_pre = 83은 그대로 두어도 된다.

### m3 (경미) 11-19 수능일과 (마)

2.1은 2027-01-04(10:00 개장)에 (마) 후보 0을 적었지만, 사전 검사 기간의 2026-11-19도 `krx.json` `specialSessions`상 10:00 개장이라 같은 이유로 (마)·R3 후보가 0이다. 4.1 구간 설명에 적어 P0·P3 해석에 넣는다.

### m4 (경미) C4의 L7 변형은 선정 시각과 비대칭

C4의 L7은 `signal.range-minutes` ±20%(24분·36분)인데 선정 시각 `at: "09:30"`은 흔들지 않는다(D-126 5번). 24분이면 09:24~09:30 돌파 신호가 선정 전이라 모두 막히고(M1에 따라 진입 수도 소진), 36분이면 09:36부터 진입한다. 그래서 C4의 −20% 쪽은 "범위 길이"가 아니라 "선정 전 막힘"을 본다 `[추론]`. 6.1 L7 행에 적거나, strategy-researcher가 C4의 key-param 처리를 정한다.

### m5 (경미) 실험실 이벤트 경로 부하

100종목 boards에 INTRADAY_AT 추적기와 계좌 최대 11개가 실험실 실시간 세션의 이벤트 경로에 함께 도는 것은 LIVE02가 처음이다. D-127 대안 표는 "boards가 커져 순위 계산이 큐를 막는 것이 측정되면 경로 밖 + 순번 고정으로"를 되돌릴 조건으로 두었다. 실전 엔진은 별도 프로세스라 실전 영향은 없지만, 받는 큐 지연·넘침은 I1~I5(사고)로 이어질 수 있다. G5에 "100종목 + 계좌 구성 그대로 실험실 세션 받는 큐 지연 실측(LIVE01 운용과 겹치지 않는 시간에 녹화 재생 등으로)"을 더하는 것을 권한다.

### 확인한 것(문제 없음)
- 일정·거래일 수·L6 분할·4구간 값(캘린더로 다시 셈).
- D-128·Q-056 번호, README 결정 행·미확정 행 형식.
- 실전 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·게이트웨이를 바꾸지 않음. 등록 `blacklist: ["035720"]`은 기존 등록 값 재사용.
- 다른 역할 산출물(stage-6-plan.md, sim 코드, strategies/ 새 버전)을 이 PR에서 고치지 않고 제안으로 남김(지침 3장).
- N_k 식: 기대 N′ ≈ (N_k)(1 − q̂_k) ≥ 21m − 1 근처로 여유 m개를 유지하는 식이라 L10 문턱(N′ + 1 ≥ 20m)과 맞는다. 통계 타당성 판단은 strategy-researcher 몫.

## 판정
수정 필요(M1 막힌 신규 진입의 하루 진입 한도 소진을 `[사실]`로 고치고 후속 항목·G1 반영, M2 F1·F3·F4의 ADR 선행 조건 명시; m1~m5는 같이 고치기를 권함). 또한 B1 `./gradlew build`를 직접 실행하지 못해 확인 못 함이 남아 있어 그 자체로도 승인할 수 없다. 재검토 때 빌드를 직접 돌릴 수 있게 해 달라.

판정: 수정 필요

## 재검토 2026-10-20
- 대상: PR #202 / 커밋 4584e06(`origin/claude/live02-preregistration-plan-1g6tsw`와 같음) / main 대비 diff `git diff origin/main...HEAD`(5파일: D-128, decisions/README, live02 계획, docs/reviews/pr-202/architect.md·strategy-researcher.md). 이전 검토 뒤 바뀐 것은 `git diff be14c61 4584e06`로 따로 읽음. 코드·설정·스키마·보호 경로 변경 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --console=plain`(작업 폴더): **실패**. build-logic 의존성 해석에서 Maven Central `429 Too Many Requests`(kotlin-compiler-embeddable 2.0.21 등). 코드와 무관한 환경 문제.
  - 그래서 B1은 같은 커밋의 CI 결과로 판정했다: `gh api repos/hwan82/autotrade-system/commits/4584e06/check-runs` → "Gradle build" `success`(run 39444266217, job 118701889375), "DB schema" `success`, "Lab screens" `success`, "Secret scan and collector boundary" `success`. 컨테이너 429 때 CI로 빌드를 확인한 선례: D-124 57행, docs/qa/stage-1-completion-check.md 21행.
  - 6.2 이항 표 재계산(Python, N을 21m − 1부터 늘리며 이항(N, 1 − q) 하위 1% 분위 ≥ 20m − 1인 첫 N): m = 4 → 83·89·96·130, m = 2 → 41·45·49·68. 문서 표와 같음.
  - P2 산수: N′ = 199면 (k+1)/200 ≤ 0.0125 ⇔ k ≤ 1. 왕복 0건 120개면 N′ + 1 = 80. 문서와 같음.
  - 새 인용 확인: `ManagedStrategy.java:264-266`(`entriesToday++`, funnel, `pendingEntryNotional.put`), `LiveJudgeCriteria.java:337-344` `perTrip` = (최종 평가금액 − 시드) ÷ 왕복 수, `scripts/ci/protected-paths.txt:13`(`autotrade-domain/*`)·`:15`(`autotrade-strategy/*`), `LiveJudgement.java:346-351`, `krx.json` `specialSessions` 2026-11-19 10:00 개장(2구간 11-09~11-20 안).
  - 지금까지 선정 계좌로 판정한 실험이 없다는 주장: `experiments/` 아래 `selections:`가 있는 등록 파일 없음(EDGE01 `edge.yaml`은 선정 엣지 검사라 해당 없음).

### 이전 지적 처리

| 항목 | 결과 | 근거 |
|---|---|---|
| M1 막힌 신호의 하루 진입 한도 소진 | 해결 | 계획 3.1 둘째·셋째 글머리가 `[사실]`로 바뀌고 코드 근거·L10·P2·P3 영향을 적음. 8장 F7(안 (가)(나)(다), 보호 경로·사용자 머지 명시), G1 선행 조건, 11장 "F7 일정" 위험, D-128 7번·README 행 반영 |
| M2 F1·F3·F4 ADR 선행 | 해결 | 8장 머리 "F1·F3·F4는 … ADR을 먼저 쓴다", G1 "ADR을 먼저 쓴 뒤 구현·머지, 순서 F2 → F4", D-128 7번 |
| m1 대조군 Run 수 | 해결 | 3.2 "g × N", 3 × 83 = 249, `LiveJudgement.java` 346~351행 근거 |
| m2 6.2 결정 순서 | 해결 | 6.2 1~3번(묶음 빼기 → m 확정 → N_k) |
| m3 11-19 (마) 후보 0 | 해결 | 4.1 구간 글머리 |
| m4 C4 L7 비대칭 | 해결 | 6.1 L7 행 |
| m5 실험실 받는 큐 실측 | 해결 | G5 행 |

### 점검표 B

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI 근거) | 로컬 `./gradlew build`는 Maven 429로 실패(환경). 같은 커밋 4584e06 CI "Gradle build" success(run 39444266217). 변경이 문서뿐이라 빌드 결과에 영향 줄 코드 변경도 없음 |
| B2 | 해당 없음 | Java·Gradle·ArchUnit 변경 없음 |
| B3 | 해당 없음(이 PR) | 계약 변경 없음. 후속 계약 변경(F1·F3·F4·F7)의 ADR 선행 조건이 문서에 들어감 |
| B4 | 해당 없음(이 PR) | 코드 변경 없음. 계획의 시드 규칙(실험 시드 + i)·결과 전 고정·바이트 재현 요구는 결정론 원칙과 맞음 |
| B5 | 해당 없음 | 코드 변경 없음 |
| B6 | 해당 없음 | 코드·테스트 변경 없음 |
| B7 | 해당 없음(이 PR) | 실전 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·게이트웨이·보호 경로 변경 없음. 후속 F7 (가)(나)는 보호 경로(`autotrade-strategy/*` 등)라 그 PR에서 risk-ops 검토 필요(문서에 적힘) |
| B8 | 통과 | 수정분 포함 diff에 API Key·토큰·계좌번호 없음. 종목 코드·실험 시드만 |

### 점검표 밖 발견(경미, 머지를 막지 않음)
- n1: G1이 "F7 … 머지"를 조건으로 두는데, F7 안 (다)(받아들이고 문서화)는 머지할 코드가 없다. "F7 안 결정(ADR), (가)(나)면 머지"로 다듬으면 좋다. 등록 전 다음 문서 수정 때 같이.
- n2: 6.2의 "기대값만 맞추는 식은 m = 4, q = 0.3에서 판정 불가 확률이 약 20%"는 내 재계산(N = ⌈84 ÷ 0.7⌉ − 1 = 120, P(N′ ≤ 78), N′ ~ 이항(120, 0.7))으로 약 13.7%다. q 추정 불확실성을 넣으면 커질 수 있다. 판정 수치가 아니라 이유 설명이고, 통계 판단은 strategy-researcher 몫이라 참고로만 적는다.
- n3: F7 "(가)(나)는 `autotrade-strategy`·`autotrade-domain`을 바꾼다"에서 `StrategyContext`는 `autotrade-strategy/src/main/java/com/autotrade/strategy/StrategyContext.java`에 있어 (가)는 `autotrade-strategy`만 바꿀 수도 있다(첫 검토의 내 문구도 같은 부정확함). 어느 쪽이든 보호 경로라 결론(실전 관련 PR·사용자 머지)은 같다.

## 판정
승인(n1~n3은 경미, 후속 문서 수정 때 반영 권함)

판정: 승인

## 재검토 2 2026-10-20
- 대상: PR #202 / 커밋 3d74ca7(`origin/claude/live02-preregistration-plan-1g6tsw`와 같음) / main 대비 diff `git diff origin/main...HEAD`(5파일, 문서만). 4584e06 뒤 바뀐 것은 `git diff 4584e06 3d74ca7`로 따로 읽음: 계획 문서 G1·P3·L7·6.2 3번·F3·F7·11장, 검토 원문 2개. 이 파일(architect.md)의 변화는 내가 쓴 재검토 절을 덧붙인 것뿐임(삭제 줄 없음). D-128·decisions/README는 4584e06 뒤 바뀌지 않음. 코드·설정·스키마·보호 경로 변경 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --console=plain`(작업 폴더): 다시 **Maven Central 429**로 build-logic 의존성 해석 실패(환경 문제).
  - 그래서 B1은 3d74ca7의 CI로 판정: `gh api repos/hwan82/autotrade-system/commits/3d74ca7/check-runs` → Gradle build, DB schema, Lab screens, Secret scan and collector boundary 모두 `success`(run 39445398167).
  - 6.2 3번의 새 숫자 재계산: N = ⌈84 ÷ 0.7⌉ − 1 = 119(84 ÷ 0.7 = 120이 딱 떨어짐), P(이항(119, 0.7) ≤ 78) = 0.168. 문서의 "약 17%"가 맞다. **재검토 1의 내 n2(N = 120, 13.7%)가 틀렸다**(올림을 잘못 함). 정정한다.
  - L7 정정 확인: `strategies/kr-orb-30/v1.yaml:12` `entry.window.start: "09:30"`, `ManagedStrategy.java:229-233`(진입 창 밖이면 `trigger`를 부르지 않고 바로 빈 목록). 그래서 24분 변형도 09:30 전에는 신호가 없다. **첫 검토의 내 m4("09:24~09:30 돌파 신호가 선정 전으로 막힌다")가 틀렸고**, 새 문장(09:30 뒤 첫 이벤트에서 이미 넘은 범위 고가를 추격)이 맞다.

### 이전 지적 처리

| 항목 | 결과 | 근거 |
|---|---|---|
| n1 G1의 F7 "머지" 문구 | 해결(더 엄격하게) | G1이 "(가)·(나)·(라) 중 하나로 해결한 뒤에만", (다)는 G1을 풀지 않음(strategy-researcher 재검토 N1과 함께 반영) |
| n2 판정 불가 확률 | 해결(내 숫자가 틀렸고 문서가 맞음) | 위 재계산 |
| n3 `StrategyContext` 모듈 | 해결 | F7 (가) "`StrategyContext`, `autotrade-strategy` 모듈", "(안에 따라 `autotrade-domain`도)" |

### 새로 본 것

**R2-1 (수정 필요) F7 안 (라)는 F7이 스스로 정한 목표의 반만 고친다: 막힌 신호의 대기 금액이 남는다**

- `[사실]` F7 제목은 "막힌 신호가 하루 진입 한도·**대기 금액**을 쓰지 않게"다. 안 (라)는 `limits.max-entries-per-day`만 크게 둔다.
- `[사실]` `ManagedStrategy`는 신호를 낼 때 `pendingEntryNotional.put(symbol, 가격 × 수량)`을 하고(`ManagedStrategy.java:266`), 다음 진입 수량을 계산할 때 그 합을 쓸 수 있는 현금에서 뺀다(`:311-316` `quantity`의 `subtract(pending)`). 이 금액은 **그 종목에 다음 이벤트가 와서 열린 주문이 없을 때**만 지운다(`:139-142`). 막힌 신호는 주문이 없으므로 그 종목의 다음 이벤트까지 남는다.
- `[추론]` boards 100종목에서 09:30 직후 후보 밖 종목 여러 개가 거의 동시에 신호를 내면, 각각 계좌의 최대 20%(`sizing.max-position-pct: 20`)까지 대기 금액이 잡혀 그 사이 후보 종목의 진입 수량이 줄거나 0이 된다(`funnel.sizeBlocked`). (라)로 하루 한도를 크게 두면 막힌 신호 수가 늘어(특히 무작위 진입 대조군) 이 효과가 오히려 커질 수 있다. 다음 이벤트가 몇 초 안에 오는 유동 종목이면 짧지만, 몇 초 차이로 같은 시각에 몰리는 ORB·무작위 진입에서는 겹칠 수 있다.
- 그래서 (라)만으로 G1을 풀면 P2·L10·P3가 여전히 "후보 밖 신호가 낸 회계 효과"를 섞는다(재검토 N1이 (다)를 막은 이유와 같은 종류).
- 고칠 것: F7 (라) 설명과 G1에 다음 중 하나를 적는다. ① (라)는 대기 금액 효과를 고치지 않으므로 G1을 풀려면 (가)·(나)가 필요하다(즉 (라)는 G1 해결안에서 뺀다), 또는 ② (라)를 쓰려면 사전 검사에서 "후보 밖 막힌 신호의 대기 금액 때문에 후보 진입 수량이 줄거나 막힌 건수"를 세는 칸(F4 결과)을 두고, 그 건수가 0이 아니면(또는 사전에 정한 작은 문턱을 넘으면) G1이 안 풀린 것으로 본다. 둘 중 무엇이든 결과를 보기 전에 정한다. 판단(통계상 허용 문턱)은 strategy-researcher 몫이다.

**R2-2 (수정 필요, 경미) 결정 로그가 계획과 어긋남**

- D-128 7번과 decisions/README D-128 행은 4584e06 문구 그대로 "F7 … 전략·도메인 모듈은 보호 경로라 실전 관련 PR·사용자 머지"라고만 적는다. 3d74ca7 계획은 (라)(보호 경로 아님)를 더했고 (다)는 G1을 풀지 않는다고 바꿨다. 기획서·결정 로그를 단일 원본으로 두는 원칙(지침 2장)상 R2-1을 고친 결과에 맞춰 D-128 7번과 README 행을 같이 고친다(예: "F7은 (가)(나)[와 R2-1 조건을 단 (라)]로 해결해야 하며 (다)는 선행 조건을 풀지 않음, (가)(나)는 보호 경로라 사용자 머지").

### 점검표 B

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI 근거) | 로컬은 Maven 429로 실패(환경). 3d74ca7 CI Gradle build success(run 39445398167). 변경은 문서뿐 |
| B2 | 해당 없음 | Java·Gradle·ArchUnit 변경 없음 |
| B3 | 해당 없음(이 PR) | 계약 변경 없음. F1·F3·F4·F7 (가)(나)의 ADR 선행 조건이 계획에 있음. (라)는 `strategies/` 새 버전 파일이라 계약 변경은 아니다 |
| B4 | 해당 없음(이 PR) | 코드 변경 없음 |
| B5 | 해당 없음 | 코드 변경 없음 |
| B6 | 해당 없음 | 코드·테스트 변경 없음 |
| B7 | 해당 없음(이 PR) | 실전 경로·보호 경로 변경 없음. F7 (가)(나) 후속 PR은 보호 경로라 risk-ops 검토 필요(계획에 적힘). (라)는 보호 경로 밖이지만 가상 계좌 한도를 바꾸는 것뿐이라 실전 영향 없음 `[추론]`(`strategies/` 버전 파일은 실전 계좌 프로필로 고르지 않는 한 실전 엔진에 쓰이지 않음) |
| B8 | 통과 | diff에 비밀값 없음 |

## 판정
수정 필요(R2-1 F7 안 (라)가 대기 금액 효과를 남기므로 G1 해결안에서 빼거나 측정·문턱 조건을 달 것, R2-2 D-128 7번·decisions/README 행을 계획과 맞출 것). 그 밖의 지적은 모두 해결됨.

판정: 수정 필요

## 재검토 3 2026-10-20
- 대상: PR #202 / 커밋 6391aec(`origin/claude/live02-preregistration-plan-1g6tsw`와 같음) / main 대비 diff `git diff origin/main...HEAD`(5파일, 문서만). 3d74ca7 뒤 바뀐 것은 `git diff 3d74ca7 6391aec`로 따로 읽음: D-128 7번, decisions/README D-128 행, 계획 G1·F7·9.2 표·11장, 검토 원문 2개. 이 파일의 변화는 내가 쓴 재검토 2 절을 덧붙인 것뿐임(삭제 줄 0). 코드·설정·스키마·보호 경로 변경 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --console=plain`(작업 폴더): 다시 Maven Central 429(kotlinx-coroutines-core-jvm 1.6.4 등)로 실패(환경 문제).
  - B1은 6391aec CI로 판정: `gh api repos/hwan82/autotrade-system/commits/6391aec/check-runs` → Gradle build, DB schema, Lab screens, Secret scan and collector boundary 모두 `success`(run 39446482421).
  - 계획·D-128·README에서 F7의 옛 표기(`(가)(나)`, 안 `(라)`) 남은 곳 검색: 없음. 선정 이름 (다)(라)는 F7과 무관한 원래 뜻으로만 남음.
  - F7 새 인용 줄 확인: `ManagedStrategy.java:266`(`pendingEntryNotional.put`), `:139-142`(다음 이벤트에서 열린 주문 없으면 지움), `:312-313`(쓸 수 있는 현금에서 대기 금액 뺌). 맞음.

### 이전 지적 처리

| 항목 | 결과 | 근거 |
|---|---|---|
| R2-1 F7 안 (라)가 대기 금액을 남김 | 해결 | 계획 F7: F7-d(프로필 한도만 늘리기)를 "G1을 풀지 않음"으로 두고 코드 근거를 적음. G1: "F7-a나 F7-b로 해결해 머지한 뒤에만", F7-c·F7-d는 G1을 풀지 않음. 11장 F7 일정도 맞춤. F7-a(신호 전 거름)는 한도와 대기 금액이 둘 다 생기지 않고, F7-b(콜백)는 둘 다 되돌리므로 F7 목표를 채운다 `[추론]`(구체 설계는 그 PR의 ADR에서 검토) |
| R2-2 결정 로그 불일치 | 해결 | D-128 7번과 README D-128 행이 F7-a·F7-b만 해결안, 보호 경로라 사용자 머지, F7-c·F7-d는 선행 조건을 풀지 않음으로 계획과 같아짐 |

### 점검표 B

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(CI 근거) | 로컬은 Maven 429로 실패(환경). 6391aec CI Gradle build success(run 39446482421). 변경은 문서뿐 |
| B2 | 해당 없음 | Java·Gradle·ArchUnit 변경 없음 |
| B3 | 해당 없음(이 PR) | 계약 변경 없음. F1·F3·F4·F7-a·F7-b의 ADR 선행 조건이 계획에 있음 |
| B4 | 해당 없음(이 PR) | 코드 변경 없음 |
| B5 | 해당 없음 | 코드 변경 없음 |
| B6 | 해당 없음 | 코드·테스트 변경 없음 |
| B7 | 해당 없음(이 PR) | 실전 경로·보호 경로 변경 없음. 후속 F7-a·F7-b는 보호 경로(`autotrade-strategy/*`, 안에 따라 `autotrade-domain/*`)라 그 PR에서 risk-ops 검토·독립 QA·사용자 머지가 필요하고 계획·결정 로그에 적혀 있음 |
| B8 | 통과 | diff에 비밀값 없음 |

### 점검표 밖 발견
- 경미(머지를 막지 않음): G1 괄호 설명이 "막힌 신호가 하루 진입 한도를 쓰지 않게"로 대기 금액을 빼고 적었다. F7 제목과 같게 "하루 진입 한도·대기 금액"으로 맞추면 좋다. 다음 문서 수정 때 같이.

## 판정
승인(경미 1건은 다음 수정 때 반영 권함)

판정: 승인
