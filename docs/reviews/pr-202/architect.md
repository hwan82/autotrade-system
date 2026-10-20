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
