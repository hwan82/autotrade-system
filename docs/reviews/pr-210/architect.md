# architect 검토: 녹화 사전 검사 명령 runRecordingPrecheck(ADR-014 F4)
- 대상: PR #210 / 커밋 a8ae6511254cff4b2f7ad9f6c47e18e31b455e45 / main(f571a38) 대비 diff `git diff origin/main...HEAD`(커밋 935b664·9a92f04·a8ae651)
- 변경 파일(검토 원문 빼고 19개): `autotrade-app-experiment/build.gradle.kts`, `ExperimentMain.java`, `ExperimentGateTest.java`, `autotrade-sim/.../live/` `LiveJudgement.java`·`LivePlan.java`·`PrecheckMain.java`(새)·`PrecheckMath.java`(새)·`PrecheckPlan.java`(새)·`RecordingPrecheck.java`(새), `.../recording/RecordingExperiment.java`·`RecordingPlan.java`, `.../run/ExperimentJobStatus.java`·`RegistrationYaml.java`, 시험 `LiveJudgementTest`·`PrecheckMathTest`(새)·`RecordingPrecheckTest`(새)·`golden/live-judgement-live01.md`(새), `docs/runbooks/README.md`·`recording-precheck.md`(새). `scripts/ci/protected-paths.txt` 패턴과 대조해 걸린 경로 없음(`autotrade-sim`·`autotrade-app-experiment`·`docs/runbooks`·`docs/reviews`).
- 점검표: B
- 직접 실행한 것:
  - diff 전문 읽음. ADR-014 5장(5.1~5.4)과 9장 PR C 시험 목록에 대조.
  - `./gradlew cleanTest build`(커밋 a8ae651, 작업 트리 깨끗): **BUILD SUCCESSFUL in 3m 15s**. 결과 XML 합계 테스트 1851건, 건너뜀 0, 실패·오류 0. `RecordingPrecheckTest` 9건, `PrecheckMathTest` 3건, `LiveJudgementTest` 20건, `ExperimentGateTest#녹화_사전_검사도_정지_요청과_장_시간에는_시작하지_않고_인자가_틀리면_입력_오류다` 통과. `autotrade-architecture-test` 46건 통과. (검토 중 브랜치에 a8ae651이 올라와 935b664에서 한 빌드는 버리고 a8ae651에서 다시 돌림.)
  - **LIVE01 고정 파일이 main 코드의 출력인지 직접 확인**: origin/main(f571a38)을 스크래치 폴더 worktree로 풀고 이 PR의 `LiveJudgementTest.java`와 `golden/live-judgement-live01.md`만 복사해 `:autotrade-sim:test --tests com.autotrade.sim.live.LiveJudgementTest` 실행 → `live01ShapedJudgementMatchesTheCommittedGolden` 통과(실패 0). 즉 고정 파일은 리팩터링 전 main 코드의 출력과 같고, 이 PR의 `LiveJudgement` 변경(`nullLanes`·`runGroups` 추출, 진행 ID 인자)이 LIVE01 모양 판정 결과를 바꾸지 않는다. worktree는 지움. 저장소 파일은 고치지 않음.

## 계약·ADR 대조

| ADR-014 요구 | 결과 | 근거 |
|---|---|---|
| 5.1 녹화 실험 키 전부 + 사전 검사 키, `selection-input`은 스냅샷 ID만 | 맞음 | `PrecheckPlan.java:211-251`(`RecordingPlan.SCALARS/LISTS/MAPS` 재사용, `RecordingPlan.build(y, INPUT)`), 시험 `refusesRegistrationErrorsOfAdr014SectionFiveOne`(`latest` 거절) |
| 5.1 판정 수는 문자열 → `BigDecimal`·정수, `double` 없음 | 맞음 | `PrecheckPlan.java:67-68,257-269`(정규식 후 `Integer.parseInt`·`new BigDecimal`), diff 전체에 `double`·`Math.pow` 없음 |
| 5.1 역할 계산 공유, 기준선 선택(`baselineRequired=false`) | 맞음 | `RecordingPrecheck.java:236` `JudgeRoles.of(..., false)`, 시험 `r.baseline() == -1` |
| 5.1 거절 조건(p2·p3 판정 대상 아님, p2 묶음 대조군 없음, 중복, p3 오른쪽 참고 아님, family-size < m, alpha (0,1) 밖, min-round-trips < 1, p3-min-segments 범위, 구간 덮기, 대조군 묶음 시드 수 누락, P2 묶음 N ≥ ⌈family/alpha⌉ − 1) | 맞음, 시험 있음 | `PrecheckPlan.java:101-182`, `RecordingPrecheck.java:234-270`, 시험 `refusesRegistrationErrorsOfAdr014SectionFiveOne` |
| 5.2 `ExperimentMain recording-precheck`, `RunGate`(장 시간·정지·병렬 상한·진행률) | 맞음 | `ExperimentMain.java:81,185`, `build.gradle.kts` `runRecordingPrecheck`, `RecordingPrecheck.java:151,158,173,182,196`, 시험 `ExperimentGateTest` 새 시험 |
| 5.2 입력: `StreamedRecordingDataSet`·녹화 실험 선정 준비·장중 추적기 | 맞음 | `RecordingPrecheck.java:156-162,183`(`SelectionStage.prepare` 공유), 시험 `baseLaneMatchesTheRecordingExperimentOfTheSameAccounts`(BASE 상세 보고서 해시가 녹화 실험과 같음) |
| 5.2 레인 = BASE + 묶음별 대조군 시드, 레인 ID·시드 판정 명령과 같음 | 맞음 | `LiveJudgement.nullLanes` 공유(`LiveJudgement.java` `nullLanes`), 시험 `nullLanesUseTheJudgementIdsAndSeeds` |
| 5.2 결정론: 실행 시간은 로그에만, 병렬 대 직렬 확인 | 맞음 | `RecordingPrecheck.java:112,127`(nanoTime은 로그 줄만), `:189-206`, 시험 `writesPrecheckAndInputsDeterministically`(묶음 2 대 1 바이트 같음, `걸린 시간` 없음) |
| 5.3 P2 정수·`BigDecimal` 비교, 같음 통과, 판정 불가 조건 | 맞음 | `RecordingPrecheck.java:469-475`, 시험 `p2UsesIntegerComparisonWithEqualityPassing`(80·160 경계, alpha 0.07 올림) |
| 5.3 q̂ Clopper–Pearson 정확 산술·이분법 1e-9·표시 올림, N 표·999 상한·묶음 빼기 | 계산 맞음 | `PrecheckMath.java`(`BigInteger` 이항계수·`DECIMAL128`), 시험 `PrecheckMathTest`(m = 1~4 고정값, x = 0·N = 199 → 0.0150, x = N → 1, q = 0.9 → 빈 값). 단 "묶음 빼기"·"999 초과" **표시**는 시험 없음(아래 M1) |
| 5.3 P0·P1·P3·결과 합치기 | 구현 맞음, **경계 시험 없음** | `RecordingPrecheck.java:401-402,422-440,481-527`. 아래 M1 |
| 5.4 결과 파일·장 구성·끝까지 계산했을 때만 씀 | 맞음 | `RecordingPrecheck.java:110-128,634-751`, 시험 `stopWritesNothingAndInputErrorsStartNoRun` |
| 5.4 종료 코드 | **문구와 다름**(실패한 Run → 2) | `PrecheckMain.java:65-68`. 런북 4장에 자율 결정으로 적음. 아래 m1 |
| 9장 4번: ModuleOwnership 표 변경 없음, 보호 경로 아님 | 맞음 | 새 클래스는 기존 `com.autotrade.sim.live`·`com.autotrade.app.experiment` 패키지, `ModuleOwnershipTest` 통과 |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | `./gradlew cleanTest build` 커밋 a8ae651에서 BUILD SUCCESSFUL, 1851건 실패 0 |
| B2 | 통과 | 새 코드가 `autotrade-sim`(순수 모듈) 안이고 Spring·DB 의존 없음. `verifyPureDependencies`·`ArchitectureTest`·`ModuleOwnershipTest` 통과. `RecordingExperiment.SelectionStage`·`removeStaleSelections`·`intradayInputs`·`intradayResultsTable`, `RecordingPlan.SCALARS/LISTS/MAPS/build` 공개는 같은 모듈 안 재사용용이고 `Set.of`·`Map.of`라 바뀌지 않는다 |
| B3 | 통과(m1 참고) | 새 등록 키·명령·결과 파일은 ADR-014 5장에 있다. `ExperimentJobStatus.COMMANDS`에 `recording-precheck` 추가(`ExperimentJobStatus.java:57-60`)는 5.2 새 하위 명령에 따름. `RegistrationYaml` 최상위 키 숫자 허용(`RegistrationYaml.java:26-27`)은 ADR 5.1 키 이름(`p2`·`p3`)을 받기 위함이고 모르는 키 거절은 그대로. 종료 코드 2만 ADR 5.4 문구와 다르다(m1) |
| B4 | 통과 | 시스템 시계는 `System.nanoTime`을 로그 줄에만 씀(`RecordingPrecheck.java:112,127`). 대조군 시드 = 실험 시드 + i. 출력 맵은 `LinkedHashMap`·`TreeMap`(`HashMap`은 `limits`의 보유 수 셈에만 쓰고 순서가 출력에 안 나감). 확률 계산은 정확 산술. 같은 입력·묶음 수 다름 바이트 동일 시험 통과. LIVE01 고정 파일이 main(f571a38) 코드로도 통과함을 직접 확인 |
| B5 | 통과 | 금액·왕복당·구간 값 `BigDecimal`(`RecordingPrecheck.java:537-565`), P2 비교 `BigDecimal`·`long`, q̂·N 표 `BigDecimal`·`BigInteger` |
| B6 | **실패** | 새 판정 함수 중 P2·구간 값·한도·등록 거절에는 시험이 있으나, ADR-014 9장 3번이 정한 "P0~P3 경계(같음은 P0 통과·P1 탈락)", P3 항목 통과·탈락(d > 0 그리고 d_s > 0 구간 수 ≥ 기준), 결과 합치기(통과/탈락/판정 불가), "묶음 빼기"·"999 초과" 표시 시험이 없다(M1). 건너뛰거나 지운 시험은 없음(`@Disabled` 없음, 기존 시험 삭제 없음) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·장애 대응·보호 경로를 건드리지 않음(`protected-paths.txt` 대조). 실험 명령(`autotrade-app-experiment`)과 `autotrade-sim`만 바뀜 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 시험 자료는 고정 녹화·가짜 스냅샷 ID |

## 점검표 밖 발견

### M1. ADR-014 9장 3번이 정한 P0·P1·P3·결과 합치기 경계 시험과 N 표 표시 시험을 더할 것(수정 필요)
- `RecordingPrecheck.p0`(422행)·`p1`(430행)·`p3`(481행)·결과 합치기(401-402행)·`combine`(520행)을 직접 부르는 시험이 없다(`RecordingPrecheckTest`에서 `.p0(`·`.p1(`·`.p3(` 검색 0건). 끝단 시험 `writesPrecheckAndInputsDeterministically`는 결과가 세 값 중 하나인지만 보고(`isIn(CANDIDATE, FAIL, NOT_AVAILABLE)`) 어떤 값인지 고정하지 않아, 예컨대 P1의 `> 0`이 `>= 0`으로 바뀌거나 P3의 `d.signum() > 0` 조건이 빠져도 모든 시험이 통과한다.
- 더할 것(최소): P0 왕복 = 기준이면 통과·기준 − 1이면 탈락, 실패한 Run 판정 불가 / P1 왕복당 0원이면 탈락·왕복 0건 판정 불가 / P3 전체 d ≤ 0이면 d_s 구간이 충분해도 탈락, d > 0이고 d_s > 0 구간 = 기준이면 통과·기준 − 1이면 탈락, 전체 왕복 0건 판정 불가 / 결과 합치기(탈락 우선, 그다음 판정 불가, P2는 `p2` 계좌에만 걸림) / 5장 표에서 x/N ≥ 0.5이면 "묶음 빼기", 상한이 커 999 초과면 "999 초과"(`RecordingPrecheck.java:799-807`), 커버리지 평균 < 50%면 알림 줄(875행). `RunReport`는 `limitsCountFillDaysAgainstTheProfileLimits`처럼 손으로 만들 수 있다.
- 이유: P0~P3은 LIVE02 등록 후보를 거르는 판정이고 `precheck.md`는 커밋 후 고치지 않는 결과라, 경계 하나가 틀리면 사전 등록 후보가 조용히 바뀐다. ADR이 시험 목록으로 못 박은 항목이다.

### m1. 종료 코드 2를 ADR-014 5.4에 반영할 것(권고, 막지 않음)
`PrecheckMain.java:65-68`은 실패한 Run·결정론 불일치에 2를 낸다. ADR 5.4는 "계산을 끝내면 0, 입력 1, 정지 요청 같은 코드"라 문구와 다르다. 녹화 실험과 같고 실험실 작업 상태(확인 필요)와도 맞아 판단은 동의하지만, 계약 원본이 ADR이므로 런북 4장 표만이 아니라 ADR-014 5.4 또는 D-131 후속에 한 줄 남긴다(strategy-researcher R3와 같은 지적).

### m2. 실험실 작업 화면 이름표(권고, ui-dev 변경 제안)
`autotrade-ui/src/main/resources/static/js/pages/jobs.js:12-15` `COMMAND`에 `recording-precheck`가 없어 작업 목록에 원문 `recording-precheck`로 보인다(동작은 `|| j.command`로 문제없음). `selection-edge` 추가 때(PR #198)는 같은 PR에서 이름표를 더했다. 이 PR에 넣거나 ui-dev 후속으로 남긴다.

### m3. 병렬 비교 시험 묶음 수(참고)
ADR 9장 3번은 "병렬 1 대 4 바이트 동일"인데 시험은 등록 `parallelism: "2"`로 2 대 1이다. 고정 녹화 레인 수(9)로 4묶음도 만들 수 있으니 `parallelism: "4"` 한 번 더 돌리는 것을 권한다. 묶음 나누기 함수는 녹화 판정과 공유(`LiveJudgement.groups`)라 위험은 낮다.

### 참고(판정에 넣지 않음)
- 묶음 수 계산이 녹화 판정(`maxParallelRuns`만)과 달리 등록 `parallelism`도 본다(`RecordingPrecheck.java:169-170`). 녹화 실험 등록 키를 받는다는 5.1과 맞고 결과에 영향이 없다.
- 한도 표의 진입 셈을 주문 키 수로 고친 a8ae651(`RecordingPrecheck.java:599-621`)은 `Fill.key()`(멱등 키) 기준이라 부분 체결을 한 건으로 센다. 시험 `limitsCountFillDaysAgainstTheProfileLimits`가 D1 7체결 = 3주문, D2 6주문 = 한도 도달을 고정한다.
- 결과 쓰기 중 `IOException`이 나면 일부 파일만 쓰고 종료 코드 1이 된다(`RecordingPrecheck.java:116-124`, `PrecheckMain.java:75-77`). 녹화 실험과 같은 방식이라 이 PR 범위 밖으로 둔다.

## 판정
수정 필요(M1: ADR-014 9장 3번의 P0·P1·P3·결과 합치기 경계 시험과 "묶음 빼기"·"999 초과"·커버리지 알림 표시 시험 추가)

## 재검토 2026-10-20
- 대상: PR #210 / 커밋 d775333(코드는 e4d0718과 같음. d775333은 strategy-researcher 검토 원문만 더함) / main(f571a38) 대비 diff `git diff origin/main...HEAD` 전체
- 점검표: B
- 직접 실행한 것:
  - 앞 검토(a8ae651) 이후 바뀐 것 `git diff 56eb626 HEAD` 전문 읽음: `RecordingPrecheck.java`(판정 합치기 `result`, LIVE02 N 표 칸 `liveSeedsCell`, 커버리지 알림 `coverageAlert`를 함수로 꺼냄, `combine` 패키지 공개. 동작은 그대로), `RecordingPrecheckTest.java`(시험 4개 추가, 등록 `parallelism` 2 → 4). PR 전체 변경 파일 범위는 그대로(`autotrade-sim`·`autotrade-app-experiment`·`docs/runbooks`·`docs/reviews`, 보호 경로 없음).
  - `./gradlew cleanTest build`(d775333, 작업 트리 깨끗): **BUILD SUCCESSFUL in 3m 14s**. 결과 XML 합계 1855건, 건너뜀 0, 실패·오류 0. `RecordingPrecheckTest` 13건 모두 통과(새 시험 `p0AndP1BoundariesFollowAdr014NineThree`, `p3NeedsOverallDAboveZeroAndEnoughPositiveSegments`, `resultAndPairCombinationRules`, `liveSeedsCellAndCoverageAlert` 포함).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드, 1855건 실패 0 |
| B2 | 통과 | 바뀐 코드는 `autotrade-sim` 안 함수 추출뿐. `verifyPureDependencies`·`ArchitectureTest`·`ModuleOwnershipTest` 통과 |
| B3 | 통과(m1 남음) | 계약 변경 없음. 종료 코드 2는 여전히 ADR-014 5.4 문구와 다르고 런북 4장에만 있다(아래) |
| B4 | 통과 | 추출 함수는 같은 식(`2L * x >= n`, `mean(...) < 0.5`, 탈락 → 판정 불가 → 후보 순). `writesPrecheckAndInputsDeterministically`가 이제 묶음 4 대 1 바이트 일치(`determinism: "같음(묶음 4 vs 1, …)"`)를 고정하고, 두 번째 실행(묶음 1)과도 같다. LIVE01 고정 시험 통과 |
| B5 | 통과 | 바뀐 부분도 `BigDecimal`·정수만 |
| B6 | 통과 | M1 해소: P0 = 기준 통과·기준 − 1 탈락·실패 판정 불가, P1 0원 탈락·+10원 통과·왕복 0건/실패 판정 불가, P3 전체 d = 0 탈락·d_s 구간 부족 탈락(왕복 0건 구간 포함)·왕복 0건/실패 판정 불가·통과, 결과 합치기와 P3 쌍 합치기 순서, "묶음 빼기"(x/N = 0.5 포함)·"999 초과"·정상 칸(83), 커버리지 평균 50% 같음은 알리지 않음·미만 알림·빈 목록. 지운 시험·건너뛴 시험 없음 |
| B7 | 해당 없음 | 실전 경로·보호 경로 변경 없음 |
| B8 | 통과 | 비밀값 없음 |

### 앞 검토 항목 처리
- M1: 해소(위 B6).
- m3: 해소(병렬 4 대 1).
- m1(종료 코드 2를 ADR-014 5.4나 D-131 후속에 기록): 이번 diff에 없음. 막지 않는 권고로 남긴다. 머지 뒤 ADR·결정 로그 후속으로 처리한다.
- m2(`jobs.js` 이름표 `recording-precheck`): 이번 diff에 없음. 막지 않는 ui-dev 후속 제안으로 남긴다.

### 판정
승인
