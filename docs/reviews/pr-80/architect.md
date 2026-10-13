# architect 검토: Q-051 결정(D-073)과 4단계 사용자 PC 실행 절차서
- 대상: PR #80 / 브랜치 `claude/project-thread-pb3vv4` 커밋 `8821bf4` / main `f1b923c` 대비 diff 전체(`git diff f1b923c...HEAD`, 7개 파일 +206 −7). 별도 복제본 `/tmp/claude-0/review-pr80-architect`에서 확인. 복제본의 `origin/main`은 오래된 로컬 참조(`157420b`)라서, 원 저장소의 main(`f1b923c`)을 기준으로 삼았다(merge-base = `f1b923c`).
- 점검표: B
- 직접 실행한 것: `./gradlew build --no-daemon`은 **BUILD SUCCESSFUL in 2m 38s, 종료 코드 0**이다. `autotrade-sim` `WalkForwardStudyTest`는 6개 테스트에서 실패 0, 오류 0, 건너뜀 0이었고, 새 테스트 `사전_등록할_4단계_10조합은_시작_전_검증과_예열_300일을_통과한다`도 여기에 포함된다. 그 밖에 runbook의 명령, 경로, 종료 코드를 코드와 하나씩 대조했다(아래 근거 참조).

## 변경 요약(코드에서 확인한 것)
- 코드 변경은 테스트 1개다(`autotrade-sim/src/test/java/com/autotrade/sim/walkforward/WalkForwardStudyTest.java:190-251`). 운영 코드, 계약, 설정 키, 스키마, REST는 바뀌지 않았다.
- 문서 변경: D-073 신설, D-071 상태 줄 갱신, 결정 로그(Q-051을 미확정에서 닫힌 미확정으로 옮김), 기획서 v14.22, 4단계 계획 2·4장, 신규 `docs/runbooks/stage-4-walkforward.md`.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 복제본에서 직접 `./gradlew build`를 실행해 BUILD SUCCESSFUL(EXIT=0)을 얻었다. `autotrade-sim/build/test-results/test/TEST-com.autotrade.sim.walkforward.WalkForwardStudyTest.xml`: tests=6, failures=0, errors=0, skipped=0 |
| B2 | 통과 | 운영 코드 변경이 없다. 새 테스트는 `autotrade-sim` 테스트에서 `com.autotrade.strategy.catalog.CatalogStrategyFactory`를 쓰는데, 이는 운영 코드 `WalkForwardStudy.java:206`(`candidates(..., CatalogStrategyFactory)`)이 이미 쓰는 의존 방향과 같다. ArchUnit(`autotrade-architecture-test`)도 build에서 통과했다 |
| B3 | 해당 없음 | 인터페이스, 스키마, 설정 키, REST 변경이 없다. runbook의 `study.yaml` 키는 기존 `WalkForwardPlan`과 ADR-009 그대로이고, 새 테스트가 같은 본문을 `WalkForwardPlan.parse`로 파싱해 확인한다(`WalkForwardStudyTest.java:193-224`) |
| B4 | 통과 | 시스템 시계와 난수 추가가 없다. 결정론과 골든 성격의 기존 테스트(`같은_입력을_두_번_돌리면_결과가_같다`, `등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다`)가 그대로 통과한다 |
| B5 | 해당 없음 | 금액 계산 코드 변경이 없다 |
| B6 | 통과 | 새 테스트는 runbook 4장 등록 본문(m=10, 대조군 2)이 `candidates()` 검증을 통과하는지 확인한다. 10개 후보 모두 ±20% 변형(`low`)이 만들어지는지, `c10Possible(400, 10)`이 참인지도 본다(`WalkForwardJudge.java:320`, 401 ≥ 200). `warmup-days: 299`이면 `kr-52w-high-position-k120@1=300일` 메시지와 함께 거부되는지 확인해 300일 등록의 근거를 고정한다(`WalkForwardStudy.java:278-283`). 지우거나 건너뛴 테스트는 없다(diff는 추가만 있음) |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트, 장애 대응, 증권사 주문 연동을 건드리지 않는다. runbook은 기존 `-PallowDuringSession` 사용 조건("실전 엔진과 녹화 게이트웨이를 모두 끈 날")과 정지 요청 파일을 지우지 말라는 규칙(D-063)을 그대로 옮겼을 뿐 약화하지 않는다(`autotrade-app-experiment/build.gradle.kts:39-41`) |
| B8 | 통과 | diff에 키, 계좌번호, 토큰이 없다. 스냅샷 ID 자리는 자리표시자(`<일봉 스냅샷 ID>`, `krx-daily-placeholder`)로 되어 있다. runbook은 `detail.txt`(종목별 체결 원장)를 PC 밖으로 내보내지 않는다고 명시한다(D-050) |

## runbook과 코드 대조(맞는 것)
- 수집 명령 `krx-daily --from 2010-01-04` / `dart-financials --from 2016-01-01`: `CollectorMain.java:91-92, 288, 310`, 수집기 runbook 3장과 같다.
- 품질 리포트 `experiments/_data-quality/<스냅샷ID>.md`: `CollectorMain.java:261`이고, `run` 작업의 작업 폴더가 저장소 루트다(`autotrade-app-collector/build.gradle.kts:34`).
- 판정 명령, `-Pheap` 기본 8g, `-PverifyDeterminism`, `-PallowDuringSession`: `autotrade-app-experiment/build.gradle.kts:113-137`.
- 종료 코드 0/1/2/4: `WalkForwardMain.java:67-89`, ADR-009 2.8.
- 정지 시 결과 파일 없음: `WalkForwardStudy.runAndWrite`는 `run()`이 끝난 뒤에만 쓴다(`WalkForwardStudy.java:166-174`).
- 구간: first-test-from 2022-04-01, 3년+1년, 4구간이면 첫 학습 시작은 2019-04-01, 마지막 검증 끝은 2026-03-31이다. 스냅샷 범위 검사는 `WalkForwardWindows.checkCoverage`(`WalkForwardWindows.java:48-55`)가 하고, 예열 거래일이 모자라면 `DailyBarMarketDataSet.warmupStart`(`DailyBarMarketDataSet.java:241-243`)가 거부한다.
- m=10 구성과 null-seeds 400: 4단계 계획 5장과 같다.

## 수정 필요
- **R1 (runbook 사실 오류, 결과 취급에 영향)** `docs/runbooks/stage-4-walkforward.md:87`의 "다르면 종료 코드 2이고 결과를 쓰지 않는다"는 코드와 다르다. 결정론 확인이 불일치하면 `WalkForwardStudy.run`은 결과 파일 묶음을 그대로 돌려준다. 결정론 칸에는 "다름"이 채워진다(`WalkForwardStudy.java:141-150`). 이어 `runAndWrite`가 그 파일을 모두 쓰고(`WalkForwardStudy.java:166-174`), 그 뒤에 `WalkForwardMain`이 종료 코드 2를 돌려준다(`WalkForwardMain.java:76-81`). Run 실패 때도 같다. 지금 문구대로라면 사용자는 폴더에 `results.md` 등이 있는 것을 보고 "결과가 쓰였다 = 정상"으로 오해해 6장대로 올릴 수 있다. 재현되지 않은 판정표가 저장소에 들어갈 위험이 있다. 고칠 방향은 두 가지다. ① 문구를 "결과 파일은 쓰이지만 `inputs.yaml`·`results.md`의 결정론 확인이 '다름'이다. 이 결과는 올리지 말고 출력만 스레드에 붙인다"로 바꾸고, 6장 표 위에 "종료 코드 0일 때만 올린다"를 더한다. ② 코드를 바꿔 불일치 시 쓰지 않게 하려면 ADR-009 2.8 변경과 테스트가 필요하다. 이 PR 범위에서는 ①을 권한다.
- **R2 (경미, 같은 줄 근처)** `stage-4-walkforward.md:86`은 미국장 보호 구간을 "서머타임 중 21:30~05:30"만 적었다. 미국 서머타임은 2026-11-01에 끝나므로, 수집이 이틀 이상 걸린 뒤 판정을 돌리면 서머타임이 아닐 가능성이 크다. 그때 보호 구간인 22:30~06:30(수집기 runbook 3.5 표)도 함께 적는다. 동작은 코드의 캘린더 보호가 막으므로 안전 문제는 아니고, 안내가 정확하지 않은 문제다.

## 점검표 밖 발견
- N1 (참고) 새 테스트는 `com.autotrade.strategy.catalog.CatalogStrategyFactory`와 `com.autotrade.sim.synthetic.SyntheticKrxRules`를 import 없이 완전한 이름으로 쓴다(`WalkForwardStudyTest.java:223-224`). 동작에는 문제가 없지만 파일의 다른 부분과 맞춰 import로 바꾸는 편이 낫다. 필수는 아니다.
- N2 (참고) 등록 본문 question의 "후보 5개 x 선정 2개(m=10)"(`stage-4-walkforward.md:47`)는 실제로는 "전략 5개 × {none, 스타일별 선정 1개}"다(스윙은 `kr-vol-surge-quiet`, 중기는 `kr-fin-quality-growth`). 사전 등록 문구로 고정되므로 4단계 계획 5장의 표현("스윙 후보 3 × {none, kr-vol-surge-quiet@1} + 중기 후보 2 × {none, kr-fin-quality-growth@1}")으로 맞추기를 권한다. 등록 파일을 커밋하기 전이므로 지금 고치는 비용이 가장 작다.
- N3 (범위 밖, 확인 못 함) D-073의 "사용자 결정" 근거(프로젝트 스레드 2026-10-13T15:46:28Z "진행하십시오")는 저장소 밖 대화라 검토자가 확인할 수 없다. architect 점검표 B의 판정 대상은 아니며, 결정 출처 확인은 pm-lead나 사용자 몫이다. 메시지가 "Q-051 추천안에 대한 답"을 포함한 묶음 질문에 대한 답이었는지는 원문으로 확인하기를 권한다.
- N4 (기존 코드, 이 PR 밖) `requireWarmup`(`WalkForwardStudy.java:270-285`)은 전략 프로필의 필요 일수만 보고 선정 프로필의 조회 기간(`kr-vol-surge-quiet`은 20+20거래일)은 보지 않는다. 지금 값(40일)은 300일 안에 들어 영향이 없다. 더 긴 선정 프로필을 추가할 때 다시 본다.

## 판정
수정 필요(R1, R2)

## 재검토 2026-10-13
- 대상: PR #80 / 브랜치 `claude/project-thread-pb3vv4` 새 헤드 `29a32c1`(이전 `8821bf4`에 `29a32c1` 1개 추가) / main `f1b923c` 대비 diff 전체(9개 파일 +272 −8). 복제본에서 `git fetch ... && git checkout FETCH_HEAD`로 받음
- 점검표: B
- 직접 실행한 것: `./gradlew build --no-daemon`은 **BUILD SUCCESSFUL in 40s, EXIT=0**. `WalkForwardStudyTest`는 tests=6, failures=0, errors=0, skipped=0이며, 테스트 결과 타임스탬프가 새로 찍혀 실제로 다시 실행됐다. `git diff 8821bf4 HEAD`로 바뀐 부분을 하나씩 대조했다.
- 참고: 새 커밋은 `docs/reviews/pr-80/architect.md`(1차 검토 원문)를 저장소에 넣었다. 검토자가 쓴 파일과 `diff`로 비교해 **바이트가 같음**을 확인했다(원문 무수정). `docs/reviews/pr-80/strategy-researcher.md`도 추가됐다(다른 검토자 원문이라 내용은 판정하지 않음).

### 이전 지적 처리
| # | 결과 | 근거 |
|---|---|---|
| R1 | 해결 | `docs/runbooks/stage-4-walkforward.md`는 이제 5장에서 "다르면 결과 파일은 쓰되 `inputs.yaml`의 결정론 칸이 '다름'이 되고 종료 코드 2로 끝난다. 이 결과는 쓰지 않는다"고 안내하고, 6장 첫 줄에 "**종료 코드 0으로 끝났을 때만** 올린다"를 두었다. 이 안내는 코드 동작(`WalkForwardStudy.java:141-150, 166-174`, `WalkForwardMain.java:76-81`)과 일치한다 |
| R2 | 해결 | 5장 "언제"에 서머타임이 아닐 때의 22:30~06:30과 미국 서머타임 종료일(2026-11-01)이 추가됐다. 수집기 runbook 3.5 표와 같다 |
| N1 | 해결 | 테스트가 `CatalogStrategyFactory`와 `SyntheticKrxRules`를 import로 쓴다 |
| N2 | 해결 | 등록 question이 "스윙 후보 3 x {none, kr-vol-surge-quiet@1}, 중기 후보 2 x {none, kr-fin-quality-growth@1}, m=10"으로 바뀌어 4단계 계획 5장과 같다 |
| N3 | 변동 없음 | 저장소 밖 대화라 이번에도 확인하지 못했다. B 범위 밖이다 |
| N4 | 변동 없음 | 기존 코드라 이 PR 범위 밖이다 |

### 새 변경 확인
- 테스트(`WalkForwardStudyTest.java` `사전_등록할_4단계_10조합은_시작_전_검증과_예열_300일을_통과한다`)는 YAML을 복사해 두지 않고 절차서의 첫 ```yaml 블록을 직접 읽는다. 자리표시자 4개를 채운 뒤 `doesNotContain("<")`로 남은 자리표시자가 없는지 확인한다. 이렇게 하면 절차서와 테스트가 어긋날 수 없다. 이 방식은 좋다. 경로 `../docs/...`는 기존 `Path.of("..")`(모듈 작업 폴더 기준)과 같은 전제다. YAML 줄 끝 주석(`parallelism: "4"   # ...`)도 `WalkForwardPlan.parse`가 받는다. 테스트 통과로 확인했다.
- `seed: "20261013"` 고정: 결과를 보기 전에 시드를 정하는 사전 등록 원칙에 맞다. 병렬 수가 결과에 영향을 주지 않는다는 주석은 기존 테스트 `등록_파일에서_결과_다섯_파일까지_돌고_병렬_1과_N의_결과가_바이트까지_같다`가 뒷받침한다.
- `git diff --exit-code origin/main -- experiments/EXP-<등록일>-WF01/study.yaml`: `gitDirty`가 `experiments/`를 빼고 검사한다는 설명(`autotrade-app-experiment/build.gradle.kts:36`)과 맞다. 등록 파일이 로컬에서 바뀌었거나 지워졌으면 차이가 나타난다. 원격에도 로컬에도 파일이 없으면 출력이 없다. 그래도 그 경우는 판정 명령이 "등록 파일이 없습니다"로 종료 코드 1을 낸다(`WalkForwardMain.java:67-71`). 구멍이 없다.
- 주말 안에 끝나지 않았을 때의 안내("이어 돌리기 없음, 결과 파일 없음, 처음부터 다시"): `runAndWrite`는 `run()`이 끝난 뒤에만 쓰고 이어 돌리기 기능이 없는 것과 맞다. 대조군 수는 새 실험 ID로만 바꾼다는 규칙도 사전 등록 원칙에 맞다.
- "2026-04-01 이후 데이터 보존"은 절차 규칙이다. 판정 실행이 쓰는 마지막 날은 마지막 검증 끝 2026-03-31이다(`WalkForwardStudy.java:107`의 `spanTo`, 지수 `MarketIndex.build(..., spanTo)`). 코드가 그 뒤 데이터를 판정에 쓰지는 않는다. 사전 실행 금지는 코드로 막을 수 없으며 절차로만 지킨다는 점을 남은 위험으로 적어 둔다.
- `docs/decisions/README.md` Q-013 행 갱신(스윙 선발은 D-073으로 확정)은 D-073 내용과 맞다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 실행한 `./gradlew build`가 BUILD SUCCESSFUL(EXIT=0)이다. 헤드 `29a32c1` |
| B2 | 통과 | 운영 코드 변경이 없다. 테스트 import는 기존 의존 방향 그대로이고 ArchUnit도 통과했다 |
| B3 | 해당 없음 | 계약, 스키마, 설정 키, REST 변경이 없다 |
| B4 | 통과 | 시계와 난수 추가가 없다. 결정론과 골든 성격의 기존 테스트가 그대로 통과한다. 등록 시드가 고정됐다 |
| B5 | 해당 없음 | 금액 코드 변경이 없다 |
| B6 | 통과 | 새 테스트가 절차서 등록 본문을 직접 검증하고 예열 299일 거부를 확인한다. 테스트를 지우거나 건너뛰지 않았다 |
| B7 | 해당 없음 | 실전 경로를 건드리지 않는다. `-PallowDuringSession` 조건과 정지 요청 파일 규칙은 그대로다 |
| B8 | 통과 | 비밀값이 없다. 스냅샷 ID는 자리표시자다. `detail.txt`는 PC 밖으로 내보내지 않는다 |

### 판정
승인
