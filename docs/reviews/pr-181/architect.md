# architect 검토: 6단계 문서에 녹화 판정(D-116) 반영 (PR #181)
- 대상: PR #181 / 커밋 fb3b7276 / `git diff origin/main...HEAD`(origin/main = e5b04df7, PR #180 머지 뒤)
- 점검표: B
- 직접 실행한 것: `./gradlew clean build --no-daemon` → BUILD SUCCESSFUL(5m 1s, 161 작업). 테스트 결과 XML 합계 1640건, 건너뜀 0, 실패 0, 오류 0. `autotrade-architecture-test`(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest, StopRequestPathTest, NotificationSetupMappingTest) 실행됨.

## 변경 범위 [사실]
diff 전체가 문서 5개다(코드·빌드 스크립트·스키마·설정·보호 경로 변경 없음).
- `docs/decisions/D-113-stage-6-live-operation.md`: 위험(L7·L9·L10 명령 없음)과 후속 T2에 "D-116으로 해소/끝남" 표시
- `docs/runbooks/stage-6-live-session.md` 4장: 리플레이 확인 0 뒤 `runLiveJudgement` 단계, 종료 코드, 커밋 대상에 `judgement.md`
- `docs/runbooks/stage-6-start-checklist.md`: LIVE00 뒤 판정 1회 실행·시간 측정, LIVE01 12-18 뒤 순서에 판정 추가
- `docs/spec/autotrade-spec.md`: v14.52 변경 이력 한 줄
- `docs/strategy/stage-6-plan.md`: 4.2 표 L7·L9·L10·L11 계산 가능 여부, 판정 규칙 문단, 4.3 근거 파일, 5장 T2 끝남

## 문서 주장과 코드 대조 [사실]
| 문서 주장 | 근거 | 결과 |
|---|---|---|
| 명령 `:autotrade-app-experiment:runLiveJudgement -Pexperiment=<ID>` | autotrade-app-experiment/build.gradle.kts:207-228 (`live-judge`, `-Pexperiment` 없으면 GradleException) | 일치 |
| 종료 코드 0·1·2, 3·4는 관문 | autotrade-sim/.../live/LiveJudgeMain.java:30-72(1, 2, 0, `e.exitCode()`), D-116 결정 1번, ExperimentGateTest | 일치 |
| 대조군 시드 125 = 21m − 1 | LiveJudgement.java:348-349 `21 * m - 1`, m = 6 | 일치 |
| LIVE01 Run 163개 = 8 + 12 + 18 + 125 | 등록 프로필 8개(experiments/EXP-20261026-LIVE01/experiment.yaml:14-22), 판정 대상 6개 모두 `study.key-param` 있음(strategies/kr-*/v1.yaml), 6×2 변형, 6×3 지연 | 일치 |
| LIVE00도 같은 명령으로 돌 수 있음 | LIVE00 등록 프로필이 LIVE01과 같음(기준선 `buy-and-hold` 1, 대조군 `random` 1: strategies/kr-bh-baseline/v2.yaml:12, strategies/kr-random-control/v1.yaml:22) → LiveJudgement.java:192-208 역할 식별 통과 | 일치 `[추론]`(실행은 LIVE00 데이터가 없어 못 함) |
| 판정 규칙(L4 20일 미만·L5 미달·L10 N+1<20m은 판정 불가, 탈락 > 보류 > 후보) | D-116 결정 5번, LiveJudgeCriteriaTest | 일치(수치 변경 없음, D-114 그대로) |
| 1.5~2시간 | D-116 위험과 한계의 `[추론]`과 같고 문서도 `[추론]` 표시 | 일치 |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 `./gradlew clean build` 직접 실행: BUILD SUCCESSFUL, 1640 테스트 실패·건너뜀 0 |
| B2 | 해당 없음 | 코드·모듈 의존·ArchUnit 규칙 변경 없음(diff가 docs/ 5개). ArchUnit 테스트는 그대로 통과 |
| B3 | 통과 | 계약 변경 없음. 문서가 참조하는 명령·Gradle 속성·종료 코드는 PR #180(D-116)에서 이미 정해진 것과 같음(위 대조표). 스펙 버전 v14.52와 변경 이력 추가, 결정 색인 D-116 행 있음(docs/decisions/README.md:122) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 골든·결정론 테스트(SimulationExperimentRunnerTest, LiveJudgementTest 1·2·4묶음 동일)는 빌드에서 통과 |
| B5 | 해당 없음 | 금액 계산 코드 변경 없음 |
| B6 | 해당 없음 | 새 동작 없음(문서). 테스트 삭제·비활성 없음(skipped 0) |
| B7 | 해당 없음 | 실전 경로·보호 경로 변경 없음. scripts/ci/protected-paths.txt의 docs 항목(project-instructions, qa/README, qa/checklists, D-086, ADR-002)과 겹치는 파일 없음. 판정 명령은 실험 명령(`autotrade-sim`, `autotrade-app-experiment`)이고 장 시간 보호·정지 요청 관문(D-063)을 그대로 거침 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(diff 검색). 입력 기록·녹화는 커밋하지 않는다는 기존 문구 유지(stage-6-live-session.md 4장 4번) |

## 점검표 밖 발견 (차단 아님)
1. **점검표 LIVE00 블록이 종료 코드 3(취소)을 다루지 않는다.** stage-6-start-checklist.md 새 줄은 "0이면 … 2면 … 1·4면 출력 마지막 줄을 올린다"만 적는다. LiveJudgeMain은 `e.exitCode()`로 3을 낼 수 있다. 사용자가 중간에 멈춘 경우 "3이면 다시 돌린다" 한 마디를 더하면 좋다.
2. **"판정 결과는 쓰지 않는다(LIVE00은 성과 판정 안 함)" 문구가 같은 블록의 "`judgement.md`도 함께 올린다"와 읽기에 따라 엇갈린다.** 뜻은 "성과 판단에 쓰지 않는다"로 보인다 `[추론]`. "판정 결과로 성과를 판단하지 않는다(시간 재기와 실행 확인용)"처럼 바꾸길 제안한다.
3. **main의 Gradle 주석이 옛 수치다**(이 PR 범위 밖, PR #180 잔재): autotrade-app-experiment/build.gradle.kts:204 "무작위 대조군 20m−1 시드"는 코드(LiveJudgement.java:349 `21 * m - 1`)·D-116과 다르다. 다음 sim-engine PR에서 주석을 21m−1로 고치길 제안한다(동작 영향 없음).
4. LIVE01 판정 1.5~2시간은 합성 시세 실측의 외삽 `[추론]`이다. 문서가 LIVE00으로 다시 재도록 한 것은 적절하다. 판정이 12시간을 넘으면 D-116 되돌릴 조건(대조군 레인 저장·묶음 상한 상향)으로 가야 하는데, 점검표에는 그 기준이 없다. LIVE00 결과를 받은 뒤 추정에 반영하면 된다.

## 판정
승인
