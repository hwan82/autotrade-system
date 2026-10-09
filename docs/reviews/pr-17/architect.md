# architect 검토: PR #14 검토 반영: 선발 기준 C1~C10 손 계산 테스트, 실험 결과 커밋을 main 커밋으로 갱신 (#17)
- 대상: PR #17 / 브랜치 `claude/project-thread-z19xbf` HEAD d018b4c / `git diff origin/main...HEAD` 전체(merge-base 0ea575d, 커밋 2개, 6개 파일, +415 −2). origin/main은 그 뒤 c50ab6f(PR #16, `docs/reviews/pr-12/` 두 파일만 추가)로 나아갔고 `git merge-tree --write-tree origin/main HEAD`는 충돌 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (작업 트리 = HEAD d018b4c, 깨끗함): BUILD SUCCESSFUL, 86 actionable tasks, EXIT=0. `verifyPureDependencies`와 ArchUnit 포함.
  - `./gradlew :autotrade-sim:test --rerun`: EXIT=0. 테스트 XML 집계 sim 75건(이전 59건 + `ScreeningCriteriaTest` 16건), 실패·오류·건너뜀 0.
  - 변이 검사(별도 git worktree, 검토 후 제거): `ScreeningCriteria.java`의 경계·묶음 로직을 하나씩 바꾸고 `ScreeningCriteriaTest`만 돌렸다. C1 `>`→`>=`, C2 `>=`→`>`, C3 `<=`→`<`, C5 `>=`→`>`, C6 비율 `>=`→`>`, C6 예열 빼기 제거, C6 학습 손실 `<=0`→`<0`, C7 변형 PF `>=`→`>`, C8 주 묶음→날짜 묶음, C9 수익 `>0`→`>=0`, C10 `>`→`>=` 11개 모두 테스트 1건 실패로 잡혔다(EXIT=1, "16 tests completed, 1 failed"). 살아남은 것은 C8 주 시작을 일요일로 바꾼 변이 하나인데, 정규장 데이터에 주말 시점이 없어 결과가 같은 동치 변이다.
  - `./gradlew :autotrade-sim:runStrategyStudy`로 EXP-20261008-STR01 재실행(HEAD): EXIT=0, "결정론 확인: 같음". `git diff experiments/`는 results.md 코드 줄(0ea575d → d018b4c) 한 줄만 다르다. 확인 후 `git checkout -- experiments/`로 되돌림.
  - `git merge-base --is-ancestor 0ea575d1bb62… origin/main` → 조상임(main 이력에 있음). `git diff --name-only 0ea575d HEAD`는 테스트 파일 1개와 문서·실험 파일 5개뿐이라 main 코드가 0ea575d와 같다.
  - diff 전체에서 키·토큰·계좌번호 패턴 grep: 걸린 것 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` BUILD SUCCESSFUL(EXIT=0), sim 테스트 `--rerun` 75건 통과 |
| B2 | 통과 | 새 코드는 `autotrade-sim` 테스트 소스(ScreeningCriteriaTest.java) 하나. import는 `java.*`, `com.autotrade.{domain,sim,strategy}`, AssertJ, JUnit뿐(ScreeningCriteriaTest.java:3-23). build.gradle.kts·settings·architecture-test 무변경 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음. main 코드 변경 없음. D-027 표에 더한 한 줄(D-027-strategy-lab-protocol.md:49)은 후속 변경 제안이지 계약 변경이 아니다. `experiments/README.md`의 커밋 줄 절차는 실험 운영 문서 |
| B4 | 통과 | 테스트는 시계·난수를 쓰지 않는다. 고정 날짜(2026-04-06 월 등)와 고정 금액만 쓴다(ScreeningCriteriaTest.java:178-190, 277-287). 실험 재실행이 커밋 줄 빼고 바이트 일치. 기존 골든 테스트 무변경·통과 |
| B5 | 통과 | 기대값과 입력은 모두 `BigDecimal` 문자열 생성과 `Money`(ScreeningCriteriaTest.java:257-262, 217-225). double/float 없음 |
| B6 | 통과 | C1~C10마다 경계값 양쪽과 판단 불가 경로를 손 계산 주석과 함께 검사한다: C1 같음=미달(:41-49), C2 PF 1.3/1.29와 PF null 세 경로(:54-71), C3 0.20/0.2001(:76-82), C4 1.0/0.99/null(:85-93), C5 30/29(:96-102), C6 정확히 0.6배·예열 8일·예열이 검증 일수 이상·학습 손실과 0(:107-141), C7 50%·PF 1.0 경계·변형 실패·원래 손실·key-param 없음(:146-169), C8 주 묶음과 금요일/월요일 경계, 날짜 묶음과 구별되는 사례(:174-193), C9(:198-208), C10 같음=미달·초과·대조군 없음(:213-228), 분위수(:231-240), 전체 통과 시 후보·기준선/대조군 비후보(:243-252). 손 계산은 내가 다시 계산해 맞았고(예: C10 대조군 왕복당 −20…−1원, ceil(0.95×20)=19번째 = −2원), 변이 11개를 모두 잡았다. 기존 테스트 삭제·`@Disabled` 없음 |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트 구현, 리스크 한도, 장애 대응, 증권사 연동을 건드리는 파일이 없다(바뀐 파일 6개: sim 테스트 1, D-027, 실험 문서 3, 검토 원문 1). D-027에 더한 approve-all `RiskPolicy` 교체 과제는 등록만이고 코드 변경은 없다. 그 과제를 실제로 구현하는 PR은 risk-ops 검토 대상 |
| B8 | 통과 | diff grep에서 비밀값 없음. 테스트 데이터는 가상 ID(`bh`, `cand`, `EXP-TEST`)와 금액뿐 |

## 점검표 밖 발견
- **N1 (권고) 남은 미검증 경로.** C0(검증 구간 Run 실패, ScreeningCriteria.java:55-58), C7 "학습 구간 Run 없음"(:194-196), C9 PF가 null인 분기(:245), C10 후보 왕복 0건(:258-260), C8 전체 손익이 0 이하인 경우가 테스트에 없다. 판정을 가르는 핵심 경계는 모두 덮였으므로 막지 않는다. 다음에 이 파일을 고칠 때 더한다.
- **N2 (권고) 커밋 줄 절차가 실행 위치 두 곳에 아직 없다.** 절차는 `experiments/README.md:19`에 들어갔지만 `autotrade-sim/build.gradle.kts:17-19` 주석은 여전히 "코드를 먼저 커밋하고 돌려야"만 말하고, `.claude/skills/autotrade-strategy-lab/SKILL.md:40, 66`의 "커밋" 항목도 main 커밋이어야 한다는 말이 없다. 다음 실험(녹화 데이터)에서 같은 일이 되풀이되지 않게 두 곳에 README 링크를 단다.
- **N3 (참고) 브랜치가 main보다 한 커밋 뒤다.** origin/main c50ab6f(PR #16)는 `docs/reviews/pr-12/`만 더해 이 PR과 겹치지 않고 merge-tree 결과 충돌 없음. 머지 전에 CI가 최신 main 기준으로 돌았는지만 확인한다.
- **N4 (참고) L1은 등록만 됐고, "녹화 데이터로 돌지 않게 막기"는 지금 구조로 지켜진다.** StudyMain.java:77이 `SyntheticMarket`만 만들고 데이터 종류를 `SYNTHETIC`로 고정해 쓴다(:141). 녹화 데이터 입력 경로를 더하는 PR이 D-027:49 과제(공유 리스크·블랙리스트 체인)를 같이 하거나, 그 전까지 합성 스냅샷이 아니면 시작을 거절해야 한다.

## 판정
승인. 점검표 B 전 항목 통과 또는 해당 없음이고 확인 못 함은 없다. 실전 경로를 건드리지 않아 risk-ops 검토 대상이 아니다. N1·N2는 막지 않는 권고다.
