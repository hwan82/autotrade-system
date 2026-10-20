# architect 검토: ADR-014·D-131 LIVE02 등록 전 판정 계약(참고 계좌·대조군 시드 수·녹화 사전 검사·공시 입력 키)
- 대상: PR #204 / 커밋 d03d65fdc7eb9fe4ced6b5a207ccda31c0931f62 / main 대비 diff `git diff origin/main...HEAD`
- 변경 파일(전체): `docs/adr/ADR-014-live02-judgement-roles-precheck.md`(새 파일), `docs/adr/README.md`(ADR-014 행), `docs/decisions/D-131-live02-judgement-roles-precheck.md`(새 파일), `docs/decisions/README.md`(D-131 행). 코드·설정·스키마·보호 경로 변경 없음(`scripts/ci/protected-paths.txt` 패턴과 대조, 걸린 경로 없음).
- 점검표: B
- 직접 실행한 것:
  - `git diff --stat origin/main...HEAD`, diff 전문 읽음.
  - `./gradlew build`: **BUILD SUCCESSFUL**(커밋 d03d65f 작업 트리 깨끗한 상태). Maven Central이 429(Too Many Requests)를 돌려 의존성 내려받기에서 여러 번 실패했고, 60초 간격으로 다시 돌려 25번째 시도에서 `BUILD SUCCESSFUL in 1m 47s`(138 tasks). 앞 시도들은 코드가 아니라 내려받기 실패뿐이었다. 결과 XML 기준 테스트 1807건, `<failure>` 0건. `autotrade-architecture-test:test`·`autotrade-sim:test` 실행됨
  - ADR이 인용한 코드 줄·식별자를 저장소에서 직접 확인(아래 표).
  - LIVE02 N 표를 ADR 5.3 정의대로 다시 셈(python, 정확 이항 누적): m = 4 → q = 0, 0.05, 0.1, 0.3에서 83, 89, 96, 130, m = 2 → 41, 45, 49, 68. ADR 값과 같다. Clopper–Pearson x = 0, N = 199 → 1 − 0.05^(1/199) = 0.0149.
  - 원격 `claude/*` 브랜치 전부에서 `docs/adr/ADR-01[3-5]*`, `docs/decisions/D-1(29|3[0-2])*` 검색: D-129는 `claude/intraday-at-judgement-4rfkaw`, ADR-014·D-131은 이 브랜치뿐. 번호 충돌 없음(ADR-013·D-130은 아직 어느 브랜치에도 없음, 예약만).

## 인용 사실 대조

| ADR 주장 | 결과 | 근거 |
|---|---|---|
| 역할은 `logic`만 보고 기준선·대조군 아니면 판정 대상(`roles` 229~277행), 판정 대상 묶음에 대조군 없으면 거절(256~266행) | 맞음 | `autotrade-sim/.../live/LiveJudgement.java:229-277`, `:256-266` |
| N = 21m − 1 고정(548~549행) | 맞음 | `LiveJudgement.java:548-550` `nullSeeds(int m)` |
| 대조군 레인 ID `NULL<i>`·`S<k>-NULL<i>`, 시드 = 실험 시드 + i | 맞음 | `LiveJudgement.java:342-353` |
| 녹화 실험 키 목록(`RecordingPlan` 75~78행)에 대조군 분포 키 없음 | 맞음 | `RecordingPlan.java:75-79` |
| `selection-input.disclosures`를 지금 거절(`RegisteredSelections` 107~108행) | 맞음 | `RegisteredSelections.java:107-109`. 하위 키 집합에는 이미 있음(`:40` `INPUT_SUBKEYS`), 거절 시험 `LiveSelectionSessionTest.java:350-352` |
| `RegistrationYaml` 하위 키는 `[a-z][a-z-]*` | 맞음 | `RegistrationYaml.java:28` `SUB`. 따옴표 없는 값의 공백 뒤 `#` 주석도 지원(`:169-170`)이라 ADR 예시 주석은 읽힌다 |
| L10: p = (k+1)/(N′+1) ≤ 0.05/m, N′ + 1 < 20m이면 판정 불가 | 맞음 | `LiveJudgeCriteria.java:255-275`. 비교는 정수 곱 `(k + 1L) * 20L * m <= n + 1L`(`:272`) |
| `perTrip`, `RoundTrip.exitDate`, `OrderRejection.code`, `MAX_GROUPS`, `groups` | 있음 | `LiveJudgeCriteria.java:338`, `RoundTrip.java:14`, `OrderRejection.java:13`, `LiveJudgement.java:96, 380` |
| 명령 옵션 `-PrecordingsDir`·`-PsnapshotsDir`·`-PallowDuringSession`·`-PmaxParallelRuns`·`-Pheap` | 기존 녹화 실험 태스크와 같은 모양 | `autotrade-app-experiment/build.gradle.kts:39-99` |
| 공시 "조건이 없는데 있으면 거절"이 조합 실험·`SnapshotInputs` 규칙 | **틀림** | `SnapshotInputs.java:94-104`는 주어진 스냅샷을 읽기만 한다. 사용 여부와 입력 유무 일치 검사는 일봉 사전 검사 `SelectionEdgeStudy.java:147-151`에만 있다. `CombinationPlan`·`WalkForwardPlan`에는 없음(아래 M2) |
| 참고 계좌는 지연 레인(L8·L9)을 돌리지 않음(3.2 표) | **L8 틀림** | L8은 BASE 결과로 계산한다(`LiveJudgeCriteria.java:135` `l8(ev.base(), ...)`, `:220-238`). 지연 레인은 L0·L1500·L2000(`LiveJudgement.java:70, 94`)이고 L9가 2초 레인이다(아래 M2) |
| 참고 R1~R3에 변형·지연을 돌리면 "Run이 약 9개 늘고(변형 2 + 지연 3)" | **틀림** | 판정 대상 하나당 변형 2(`:331-333`) + 지연 3(`:338-341`) = 5 Run, 셋이면 15(아래 M2) |
| 보호 경로 변경 없음, ModuleOwnership 변경 없음 | 이 PR은 맞음 | 문서 4개만. 구현 PR B의 "세션 시작 확인"은 `autotrade-app-lab`(`LiveLabService.java:175`)에 닿는데 머리말 범위 문장에는 없음(아래 m3) |

## B 점검표

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행 결과(`./gradlew build` BUILD SUCCESSFUL, 실패 테스트 0). 문서만 바뀌어 빌드 영향은 없다 |
| B2 | 해당 없음 | 코드·빌드 스크립트·ArchUnit 규칙 변경 없음. ADR이 예고한 새 클래스는 기존 `com.autotrade.sim`·`com.autotrade.app.experiment` 패키지 안이라 `ModuleOwnershipTest.OWNERS` 변경이 필요 없다는 9장 4번 서술은 패키지 소유 규칙과 맞음 |
| B3 | 통과 | 등록 파일 키(`references`, `null-seeds`, `selection-input.disclosures`, 사전 검사 키), 새 `input` 값, 새 Gradle 명령을 구현 전에 ADR-014로 먼저 씀(PR #202 architect M2 이행). D-131과 두 README 행 갱신. 설정 키(`runtime-config.json`)·DB 스키마·REST API 변경은 없음. 다만 계약 서술에 사실 오류·모호한 곳이 있어 M1·M2를 고쳐야 한다 |
| B4 | 수정 필요(M1) | 계약 문서로서 결정론 조건: 5.2 "같은 입력이면 `precheck.md` 바이트 동일, 실행 시간은 로그만"은 맞는 방향. 그러나 5.3 끝 "p 값과 Clopper–Pearson 상한은 `double`로 계산해도 결정론적이지만(Java 17 이후 엄격 부동소수)"는 근거가 부족하고, P2 문턱 비교를 `double`로 해도 되는 것처럼 읽힌다(M1) |
| B5 | 통과(문서 범위) | 5.3 "금액 계산은 `BigDecimal`, 비교는 판정 명령과 같은 반올림 규칙". 왕복당 순손익은 `LiveJudgeCriteria.perTrip`(BigDecimal) 재사용 |
| B6 | 해당 없음 | 문서만. 9장이 PR A~C마다 시험 목록(거절 조건별, 바이트 동일 `cmp`, 병렬 1 대 4, 경계값, 고정값)을 미리 정해 둔 것은 적절하다 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·게이트웨이·보호 경로 변경 없음(diff 대조). 계약이 정하는 구현도 `autotrade-sim`·`autotrade-app-experiment`·`autotrade-app-lab`(세션 시작 확인)이라 보호 경로가 아니다. F7(전략 모듈)은 이 ADR 범위 밖으로 명시돼 있음(7장) |
| B8 | 통과 | diff 전문에 API Key·계좌번호·토큰 없음. 예시 값은 실험 ID·전략 ID·종목 코드(`005930`, `035720`)·자리표시자(`<스냅샷 ID>`)뿐 |

## 지적

### 수정 필요
- **M1 사전 검사 P2 비교와 결정론 서술(5.3)**: "p 값과 Clopper–Pearson 상한은 `double`로 계산해도 결정론적(Java 17 이후 엄격 부동소수)"을 고친다.
  - P2 통과 여부는 경계값이 시험 대상이다(9장 3번 "같음은 … P2 통과"). L10은 같은 판정을 정수 곱 `(k+1)·20m ≤ N′+1`로 한다(`LiveJudgeCriteria.java:272`). 사전 검사는 `alpha`가 등록 파일 소수 문자열이라 `double`로 `(k+1)/(N′+1) ≤ alpha/family-size`를 비교하면 경계에서 이진 표현 오차로 판정이 갈릴 수 있다. 계약에 "P2 판정과 판정 불가 경계(`N′+1 < ⌈family-size/alpha⌉`)는 `BigDecimal`(또는 유리수 정수 곱) `(k+1)·family-size ≤ alpha·(N′+1)`로 비교한다"를 적는다. `⌈family-size/alpha⌉`도 `BigDecimal`로 셈을 명시한다.
  - JEP 306(Java 17)이 엄격하게 만든 것은 사칙연산이고 `Math.pow`·`Math.log` 같은 `Math` 함수는 플랫폼별 1 ulp 차이를 허용한다. q̂ 이분법이 `Math` 함수를 쓰면 다른 PC에서 4자리 반올림 경계 값이 달라질 수 있다. "`StrictMath`를 쓰거나 `BigDecimal`/정수 누적으로 계산한다"로 바꾼다. (같은 PC 재현은 문제 없을 가능성이 높다 `[추론]`.)
- **M2 인용 사실 오류 셋**(계약 문서라 구현자가 그대로 따른다):
  1. 3.2 표 "PERTURB(L7)·지연(L8·L9) 레인": L8은 BASE로 계산한다(`LiveJudgeCriteria.java:135`). "지연(L9, 지연 표 STR-13)"으로 고치고, 참고 계좌 칸에서 L8이 BASE 값으로 나온다는 것(같은 표 마지막 줄 "–"는 L7·L9·L10뿐인 것과 일치)을 분명히 한다. 판정 결과 파일의 지연 표(`LiveJudgement.java:782-790`, 판정 대상 행만)에서 참고 계좌 행을 빼는지 "–"로 두는지도 한 줄로 정한다.
  2. 6장 "조건이 없는데 있으면 거절(조합 실험 등록 파일과 같은 규칙, `SnapshotInputs`)": 이 검사는 `SelectionEdgeStudy.java:147-151`(일봉 사전 검사)에만 있다. 근거를 그 위치로 고치고, 재사용할 것이 "스냅샷 읽기(`SnapshotInputs`)"와 "사용 여부 일치 검사(`SelectionEdgeStudy` 규칙, 선정 기준 `inputKinds()`에 `DISCLOSURE`)" 두 가지임을 나눠 적는다.
  3. 8장 참고 계좌 레인 행 "Run이 약 9개 늘고(변형 2 + 지연 3)": 계좌당 5 Run이라 R1~R3이면 15다.

### 고치면 좋은 것(판정에 넣지 않음)
- **m1 `null-seeds` 이름이 이미 다른 모양으로 쓰임**: `EdgePlan`(`EdgePlan.java:67, 140`, 스칼라 1~20000)과 `WalkForwardPlan`(`WalkForwardPlan.java:84, 196`, 스칼라, ADR-009 1~5000)이 같은 키를 스칼라로 받는다. ADR-014는 같은 이름을 목록(`"<묶음> <N>"`)·상한 999로 쓴다. 섞어 쓰면 `RegistrationYaml`이 "다음 줄부터 목록" 오류로 바로 거절하므로 안전 문제는 아니지만, 8장 키 모양 행에 기존 키와의 관계(같은 뜻·다른 모양을 고른 이유, 또는 `null-seeds-by-selection` 같은 다른 이름)와 상한 999가 기존 상한과 다른 이유를 적는 것이 좋다.
- **m2 빈 목록 키의 뜻**: 3.1은 "빈 목록 키는 허용(없는 것과 같음)"인데 3.2 머리말 줄·4.2 "대조군 품질" 장·4.3 세션 시작 확인은 "키가 있으면"으로 쓴다. `RegistrationYaml`은 빈 목록 키를 "키 있음 + 빈 목록"으로 돌려준다(`RegistrationYaml.java:20, 102`). 바이트 동일 원칙과 맞추려면 "빈 목록은 모든 곳에서 키 없음과 같다"를 한 줄로 못 박는다.
- **m3 범위 문장**: 머리말 "바뀌는 것은 `autotrade-sim` … 과 `autotrade-app-experiment`의 명령 연결뿐"인데 4.3 세션 시작 확인은 실험실(`LiveLabService.java:175`, `autotrade-app-lab`)에 닿는다. 보호 경로는 아니지만 범위 문장에 넣는다.
- **m4 역할 계산 공유와 기준선 요구 차이**: 판정 명령은 기준선이 꼭 하나 있어야 하고(`LiveJudgement.java:251-254`) 사전 검사는 0개도 허용한다(5.1). "같은 코드 한 곳"(2장 ⑤, 9장 `JudgeRoles`)이 이 차이를 매개변수로 받는다는 것을 적는다.
- **m5 P3 구간 끝 평가금액**: 구간 마지막 거래일에 일별 평가금액 점이 없을 때(녹화 결측일 등)의 규칙(그 전 마지막 점 사용 또는 판정 불가)을 정해 두면 구현 차이가 없다.

## 점검표 밖 발견
- 대안 검토(8장)는 쟁점 7개마다 채택·버린 안·되돌릴 조건을 갖췄고, 위험(10장)은 판정 시간 추정·체결 기준 한도 과소 추정을 `[추론]`으로 정직하게 남겼다. 9장 순서(F9 → PR #203 → F1·F3·F6 → F4)는 같은 파일 동시 작업 금지(지침 10장)와 맞다.
- 4.3 "두 키 중 하나라도 있으면 세션 시작 때 역할 계산으로 빨리 실패"는 8주 뒤에야 드러나는 등록 오류를 막는 좋은 설계다. 키 없는 등록(LIVE00·LIVE01)의 시작 동작을 바꾸지 않는 것도 맞다.
- 5.3 계산 정의(Clopper–Pearson 단측 상한, LIVE02 N 식)는 다시 셈한 값과 일치한다. 판정 계산이 계획 4.2와 같은지는 9장 4번대로 PR C에서 strategy-researcher 검토가 필요하다(이 검토는 계약 정합성까지만 봄).

## 판정
수정 필요(M1: 사전 검사 P2 경계 비교를 `BigDecimal`/정수 곱으로 명시하고 `double`·Java 17 엄격 부동소수 결정론 서술 정정, M2: 인용 사실 오류 셋(3.2 L8 레인, 6장 공시 일치 검사 위치, 8장 참고 레인 Run 수) 정정). m1~m5는 판정에 넣지 않음
