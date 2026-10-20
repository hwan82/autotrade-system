# architect 검토: 녹화 판정이 참고 계좌·묶음별 대조군 시드 수를 받음(ADR-014 F1·F3·F6, D-131)
- 대상: PR #208 / 커밋 f04be40f52aa212de3b6408c418f2ed3f47ca99c / main(fbd1a41) 대비 diff `git diff origin/main...HEAD`
- 변경 파일(전체 12개): `autotrade-app-lab/.../live/LiveLabService.java`(+4), `LiveLabServiceTest.java`, `autotrade-sim/.../live/JudgeRoles.java`(새 파일), `LiveJudgeCriteria.java`, `LiveJudgement.java`, `LivePlan.java`, 시험 `JudgeRolesTest`(새 파일)·`LiveIntradayJudgementTest`·`LiveJudgementTest`·`LivePlanTest`·`LiveSelectionJudgementTest`, `docs/runbooks/stage-6-live-session.md`. `scripts/ci/protected-paths.txt` 패턴과 대조해 걸린 경로 없음(`autotrade-sim`·`autotrade-app-lab`·`docs/runbooks`).
- 점검표: B
- 직접 실행한 것:
  - diff 전문 읽음. ADR-014 2·3·4장, 9장 PR B 시험 목록과 대조.
  - `./gradlew build`(커밋 f04be40, 작업 트리 깨끗): **BUILD SUCCESSFUL in 1m 43s**, 138 tasks. 결과 XML 합계 테스트 1831건, 건너뜀 0, 실패·오류 0. 새 시험 `JudgeRolesTest`, `LivePlanTest`, `LiveJudgementTest`, `LiveLabServiceTest` 결과 파일이 이번 실행으로 생김. `autotrade-architecture-test:test` 실행됨.
  - **main 대비 바이트 동일 직접 확인**(ADR-014 9장 PR B "키 없는 등록의 `judgement.md`가 main과 바이트 동일"): `git archive`로 origin/main과 HEAD를 스크래치 폴더에 풀고, 각각의 `LiveJudgementTest`(LIVE01 모양: 기준선·대조군·orb·vwap, 선정 없음)와 `LiveSelectionJudgementTest`(장전 선정 조합 계좌)에 `report()`를 파일로 쓰는 임시 시험만 더해 `:autotrade-sim:test --tests ...zzDump`로 돌림. 두 보고서 모두 `cmp` 결과 **같음**(LIVE01 9387바이트, 선정 12480바이트). 저장소 파일은 고치지 않음.

## 계약·ADR 대조

| ADR-014 요구 | 결과 | 근거 |
|---|---|---|
| 역할·시드 수 계산 한 곳(`JudgeRoles`), 판정·세션 시작 확인이 같이 씀(2장 ⑤, 9장 PR B) | 맞음 | `JudgeRoles.java:115-208`, `LiveJudgement.java` `roles()`가 `JudgeRoles.of` 위임, `LiveLabService.java:182` `JudgeRoles.check` |
| `references`: 계좌 줄 글자 그대로, 없는 줄·중복 거절, 빈 목록 = 키 없음(3.1) | 맞음 | `LivePlan.java` 정규 생성자 `accountLines` 대조, `LivePlanTest#refusesReferencesThatAreNotRegisteredLinesOrRepeated`, `#readsReferencesAndGroupNullSeeds`(빈 목록 plan이 키 없는 plan과 `equals`) |
| 참고에 기준선·대조군 거절, 참고 빼고 판정 대상 0개 거절(3.1) | 맞음 | `JudgeRoles.java:155-165`, `JudgeRolesTest#refusesBaselineOrControlAsReferenceAndNoJudgedLeft` |
| 참고 계좌는 m·묶음 대조군 요구·PERTURB·지연·대조군 시드에서 빠짐, BASE·REC·L8은 그대로(3.2) | 맞음 | 레인 구성은 `roles.judged()`만 씀(`LiveJudgement.java` `lanes`), `JudgeRolesTest#referencesLeaveTheFamilyAndNeedNoControlOfTheirOwn`, `LiveJudgementTest#referenceAccountsAreReportedButNotJudgedAndGroupSeedsOnlyAddLanes`(Run 34 = BASE 4 + PERTURB 2 + 지연 3 + 대조군 25, 3장 참고 절에 L8·L11 있음) |
| 참고 칸 L7·L9·L10 "–", 판정 칸 "(참고) …", 머리말 줄, 지연 민감도 표에서 뺌(3.2) | 맞음 | `LiveJudgement.java:105`, `:621` `without`, 표 `mark` 분기, 같은 시험의 정규식·문자열 확인 |
| `Outcome.verdicts`에서 참고 계좌 뺌(3.2) | 맞음(문구는 m1) | `LiveJudgement.java:201-207` |
| `group-null-seeds` 모양 `<id@버전 \| none> <1~999>`, 중복·모양 틀림 거절(4.1) | 맞음 | `LivePlan.java` `GROUP_SEEDS`·`groupNullSeeds`, `LivePlanTest#refusesMalformedOrRepeatedGroupNullSeeds`(0, 1000, 083, 공백 둘, 대문자 등) |
| 묶음에 대조군 없음·판정 대상 없음·N < 21m − 1 거절, m은 참고 뺀 수(4.1) | 맞음 | `JudgeRoles.java:188-205`, `JudgeRolesTest#groupNullSeedsRaiseOnlyTheNamedGroupAndNeverBelowTwentyOneMMinusOne`(61 거절, 62 통과) |
| 레인 ID·시드 규칙 그대로라 N을 늘려도 앞 21m − 1개 레인 같음(4.1) | 맞음 | `LiveJudgement.java:302`, 시험이 `keyedLanes.nulls().subList(0, 20)`과 키 없는 레인 `equals` 확인 |
| 묶음별 N 머리말, 대조군 품질 장은 새 키가 있을 때만(4.2) | 맞음 | `LiveJudgement.java` 머리말 분기(`seedsByControl().isEmpty()`면 기존 문장), `:891` `plan.hasJudgeKeys()` 조건 |
| 두 키 중 하나라도 있으면 세션 시작 때 같은 계산, 없으면 보지 않음(4.3) | 맞음 | `JudgeRoles.check` → `LivePlan.hasJudgeKeys()`, `LiveLabServiceTest#judgementKeysAreCheckedBeforeTheSessionStarts`(거절 시 세션 없음), `#registrationsWithoutJudgementKeysStartWithoutRoleCheck`. 거절은 `IllegalArgumentException`이라 컨트롤러가 기존처럼 400으로 바꿈(`LiveSessionController.java:95-101`) |
| 키 없는 등록의 `judgement.md`가 main과 바이트 동일(9장 PR B) | 맞음(직접 확인) / 저장소 시험은 약함(m2) | 위 직접 실행. 저장소 시험 `emptyNewKeysLeaveTheJudgementByteForByteUnchanged`는 같은 코드 안에서 빈 키 대 키 없음만 비교 |
| F6 `LiveJudgeCriteria` 설명 문장 | 맞음 | 설명(45~48행)이 코드(`:131-132` L5 미달 `NOT_AVAILABLE`)와 일치 |

## B 점검표

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 직접 실행 `./gradlew build` BUILD SUCCESSFUL, 1831건 실패 0 |
| B2 | 통과 | 새 클래스 `com.autotrade.sim.live.JudgeRoles`는 기존 `autotrade-sim` 소유 패키지 안이라 `ModuleOwnershipTest.OWNERS` 변경 불필요. `JudgeRoles` import는 `com.autotrade.selection.profile.Combination`과 JDK뿐(Spring·DB·증권사 SDK 없음). `autotrade-app-lab` → `autotrade-sim` 의존은 기존(`LivePlan` 등 이미 사용). ArchUnit·순수 모듈 금지 의존 검사 빌드 통과 |
| B3 | 통과 | 등록 파일 키 `references`·`group-null-seeds`는 ADR-014(PR #204, main에 있음)와 D-131이 먼저 정했고 구현이 그대로 따름(위 표). `LivePlan` 클래스 문서의 등록 파일 예시와 런북 2장 8번 갱신. 설정 키(`runtime-config.json`)·DB 스키마·REST API 변경 없음(세션 시작 거절은 기존 400 경로) |
| B4 | 통과 | `JudgeRoles`는 등록 순서·`TreeSet`·`LinkedHashMap`만 쓰는 순수 계산(시계·난수 없음). 대조군 시드는 기존대로 실험 시드 + i. 병렬 4 대 1 보고서 동일 시험(`LiveJudgementTest` 참고 계좌 시험 끝의 `judge(parallel(1))` 비교), 기존 골든성 시험 전부 통과, main 대비 바이트 동일 직접 확인 |
| B5 | 통과 | 새 금액 계산 없음. 대조군 품질 비율은 `BigDecimal.divide(..., 6, HALF_EVEN)`, 왕복당 순손익은 기존 `LiveJudgeCriteria.perTrip`(BigDecimal). L10 판정 가능 여부는 정수 비교 `used + 1L >= 20L * m` |
| B6 | 통과 | 새 동작마다 시험이 있음(ADR-014 9장 PR B 목록: 거절 조건 하나씩, 참고 계좌 제외, "–", 묶음별 N 레인 수, 앞 21m − 1개 레인 동일, 세션 시작 거절). 지운·건너뛴 시험 없음(diff에서 기존 시험은 타입 이름 `LiveJudgement.Roles` → `JudgeRoles.Roles` 변경과 단언 추가뿐). 바이트 동일 시험은 m2 참고 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·게이트웨이·보호 경로 변경 없음(diff 대조). `LiveLabService` 변경은 실험실 세션 시작 전 프로필 읽기 계산 한 줄이고 실전 엔진 프로세스와 무관 |
| B8 | 통과 | 비밀값·계좌번호·토큰 없음(diff 전문 확인, 시험 등록 파일은 가짜 실험 ID·종목 코드뿐) |

## 점검표 밖 발견
막는 항목은 없다. 아래는 권고(m)와 참고(n)다.

- **m1 ADR 문구와 코드의 "기준선·대조군과 같은 대접" 불일치.** ADR-014 66행은 "`Outcome.verdicts`에 참고 계좌를 넣지 않는다. 기준선·대조군과 같은 대접이다"라고 쓰지만, 기준선·대조군은 지금도 `verdicts`에 들어 있다(`LiveJudgementTest.java:133` `containsOnlyKeys(A01..A04)`, `LiveJudgeMain.java:60`이 모두 로그에 찍음). 이 PR 코드는 앞 문장(참고 계좌 제외)만 따르므로, 실행 로그에는 기준선·대조군 판정은 보이고 참고 계좌 판정은 안 보인다. 결과 파일(`judgement.md`)에는 모두 남으므로 판정 영향은 없다. 다음 ADR-014 갱신 때 뒷문장을 고치거나, 참고 계좌를 "(참고) …" 값으로 맵에 남기는 쪽 중 하나로 맞추길 권한다.
- **m2 main 대비 바이트 동일을 저장소 시험이 고정하지 않음.** ADR-014 9장 PR B가 요구한 "키 없는 등록의 `judgement.md`가 main과 바이트 동일"은 이번 검토에서 직접 확인했지만(LIVE01·장전 선정 모양 둘 다 같음), 저장소의 `emptyNewKeysLeaveTheJudgementByteForByteUnchanged`는 같은 코드 안에서 빈 키 대 키 없음만 비교한다. 이후 PR C(F4)가 같은 파일을 고칠 때 LIVE01 보고서가 바뀌어도 이 시험은 잡지 못한다. LIVE01 모양 고정 보고서의 해시나 핵심 줄 전체를 시험에 고정하는 방안을 PR C 전에 더하길 권한다.
- **n1 범위 밖 변경이 함께 들어 있음.** 장중 계좌 L11 줄의 "(순위 다른 날 k/n일, 10장)"(`LiveJudgement.java:768` `rankNotes`)과 장중 머리말의 "m에는 장중 순위 선정 묶음의 판정 대상도 들어간다" 문장(`:719`)은 PR #203 strategy-researcher R2·n4 후속이고 ADR-014 PR B(F1·F3·F6) 범위가 아니다. 커밋 제목에 없다. 장중 순위 등록의 `judgement.md`만 바꾸고(판정 값 불변, 시험 `LiveIntradayJudgementTest`가 확인) LIVE01·장전 선정 보고서는 위처럼 바이트 같으며 아직 운용 중인 장중 등록 판정도 없으므로 문제는 없다. PR 본문에 포함 사실을 적기를 권한다.
- **n2 시험 주석 오류.** `LiveJudgementTest.java:195` "키가 없을 때의 레인(m = 2 → N = 41)"은 실제로 `references`가 남아 있어 m = 1 → N = 20이다(바로 아래 단언 `hasSize(20)`이 맞음). 주석만 틀림.
- **n3** `LiveJudgeCriteria.java:144` `without`의 `java.util.Collection` 전체 이름 사용. 다른 곳처럼 import로 두는 편이 일관된다.
- **n4** `MAX_GROUP_NULL_SEEDS = 999`와 하한 21m − 1이 m ≥ 48이면 겹쳐 어떤 값도 받지 못한다. 계획상 m ≤ 4라 실제 문제는 아니며 기록만 남긴다.
- **n5** `JudgeRoles.of(..., baselineRequired = false)`는 지금 시험만 부른다(PR C 녹화 사전 검사용 준비, ADR-014 5.1). PR C에서 사전 검사의 묶음 N 하한(`⌈family-size / alpha⌉ − 1`)과 "모든 대조군 묶음에 필수" 규칙을 이 경로에 더할 때 판정 명령 경로(`baselineRequired = true`)의 거절 문구·조건이 바뀌지 않는지 시험으로 고정해야 한다.

## 판정
승인
