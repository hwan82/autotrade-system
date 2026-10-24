# architect 검토: LIVE01 시드머니 100만 원·종가 5만 원 이하 boards(D-138)
- 대상: PR #231 / 커밋 fe419015 / main 대비 diff(`git diff origin/main...HEAD`, 파일 6개 +105 −39)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline` → BUILD SUCCESSFUL. `:autotrade-sim:test`는 다시 돌았다(등록 파일이 입력). `StageSixRegistrationTest` 3건 통과, 건너뜀 0(`autotrade-sim/build/test-results/test/TEST-com.autotrade.sim.live.StageSixRegistrationTest.xml`). `:autotrade-architecture-test:test`도 다시 돌아 통과. 다른 모듈 test는 입력이 바뀌지 않아 UP-TO-DATE.
  - 진짜 LIVE01 등록 파일을 읽는 다른 시험이 있는지 grep: `LiveLabServiceTest`·`ExperimentGateTest`는 임시 폴더에 쓴 `EXP-TEST-LIVE01`·`EXP-20261015-LIVE01`만 쓴다. 진짜 파일을 읽는 시험은 `StageSixRegistrationTest`뿐이다.
  - 기준선 바구니 재계산(10-23 종가, 종목당 99,000원 내림): 합계 776,660원(77.7%)로 문서 값과 같다. 장중 상한 200,000원 기준 최소 수량은 403870의 4주.
  - 결정 번호: 원격 브랜치 전부에서 `D-138`·`D-139` 파일은 이 브랜치의 D-138 하나뿐이다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --offline` BUILD SUCCESSFUL, 종료 코드 0 |
| B2 | 해당 없음 | 운영 코드·모듈 의존·ArchUnit 규칙 변경 없음. 바뀐 Java는 `autotrade-sim` 시험 파일 하나이고 새 import `com.autotrade.domain.Money`는 sim이 이미 의존하는 domain |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. 등록 파일 키(`seed-capital`·`benchmark`·`boards`)는 기존 키의 값만 바뀜. 벤치마크가 boards 안이어야 하는 규칙(`LivePlan.java` 126~128행)을 지킨다(010170이 boards에 있음). 결정 기록 D-138과 결정 목록 README 한 줄, 계획(`docs/strategy/stage-6-plan.md` 2.1~2.3·4장)과 점검표(`docs/runbooks/stage-6-start-checklist.md` 1장·4.1)가 함께 바뀜. `-Pheap=10g`는 `runWatchlistCandidates`가 이미 받는 속성(`autotrade-app-experiment/build.gradle.kts` 302행) |
| B4 | 통과 | 실행 코드 변경 없음. 시드 `20261026`·기간 그대로(`experiments/EXP-20261026-LIVE01/experiment.yaml` 10~12행), `periodsMatchThePlan` 통과. LIVE01은 10-26 시작 전이라 사전 등록을 운용 중 고친 것이 아니다(D-138 바꾸는 것 줄) |
| B5 | 통과 | 시드머니는 문자열 `"1000000"` → `Money`, 시험도 `Money.of(1_000_000L, KRX 통화)`로 비교(`StageSixRegistrationTest.java` 49행) |
| B6 | 통과 | 지운 단언 두 줄(`trial.boards()`·`trial.seedCapital()`이 main과 같음)은 D-138이 두 등록을 일부러 다르게 만든 결과라 지운 것이고, 대신 LIVE01 시드머니·boards 10종목·벤치마크를 고정 값으로 단언한다(49~51·58행). 프로필·블랙리스트 동일 단언은 남았다(46~47행). `bothRegistrationsAssembleLikeALiveSession`이 새 등록으로 `LiveSetup.of`(프로필 검증·비용 표 범위)를 통과한다. 건너뛴 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·장애 대응·보호 경로(`scripts/ci/protected-paths.txt`) 파일 변경 없음. 블랙리스트 `035720`은 그대로이고 boards 안에 있음(시험 55~56행). 게이트웨이 `watchlist.json` 절차 변경은 사용자 PC 시세 구독 목록이며 주문과 무관 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. PowerShell 블록은 설정 폴더 경로·종목 코드만 다룸 |

## 점검표 밖 발견
- n1(문서, 막지 않음): `docs/strategy/stage-6-plan.md` 34행 끝 대안 "L1 비교 대상을 005930 한 종목 매수 후 보유로 바꿈(벤치마크와 같아지지만 …)"은 벤치마크가 010170으로 바뀐 뒤에는 "벤치마크와 같아지지만"이 맞지 않는다. 처음 등록 때 대안이라고 적거나 010170으로 고치면 된다.
- n2(문서, 막지 않음): 장중 프로필 최소 수량이 D-138 63행은 "한 번에 3주 이상", 계획 56행은 "4주 이상(403870만 3~4주)"으로 다르다. 10-23 종가로 floor(200,000 ÷ 47,700) = 4주다. 상한이 진입 때 평가금 기준이라 손실 뒤에는 줄 수 있으니 한쪽 표현으로 맞추면 좋다.
- n3(시험, 막지 않음): 두 등록을 묶던 단언을 지운 뒤 LIVE00 쪽 시드머니·boards는 어느 시험도 고정하지 않는다. LIVE00은 끝난 등록이라 바뀔 일은 적지만, "LIVE00은 그대로 둔다"를 지키려면 `trial.seedCapital()`(10,000,000원)과 `trial.boards().keySet()`을 고정 값으로 한 줄씩 단언하는 편이 낫다.
- n4(스타일): `com.autotrade.domain.market.Market`을 전체 이름으로 썼다(49행). 같은 파일 63행에도 같은 모양이 있어 일관은 하다.
- 참고: 벤치마크는 판정 기준(L1은 기준선 비교)이 아니라 리포트 열로만 쓰인다(`LiveJudgement.java` 810·825행). 010170(소형 KOSDAQ 한 종목)이 "시장 수익률" 대용으로는 약하므로, 계획 4장이 말하는 "시장 수익률"을 리포트에 지수로 따로 적을지는 strategy-researcher가 정할 일이다(이 PR 범위 밖).
- 종목 이름·소속부가 `[가정]`/사용자 PC 출력인 점은 D-138과 점검표 1장 3번이 첫날 전에 확인하고 다르면 시작하지 않도록 적어 두었다. 저장소 안에서는 확인할 수 없어 여기서는 검증하지 않았다(판정 대상 항목 아님).

## 판정
승인
