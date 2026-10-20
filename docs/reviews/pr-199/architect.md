# architect 검토: 녹화 판정이 선정 조합 계좌를 세션 고정 선정 파일로 판정(D-126, D-124 후속), PR #197 R2·R4
- 대상: PR #199 / 커밋 472c0dfb6b6127b20e225109d435a15e27c5c317 / main(6713ae8) 대비 diff 전체(`git diff origin/main...HEAD`, 14개 파일, +764 −121)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew --max-workers=2 cleanTest build` → BUILD SUCCESSFUL(3m 1s). 테스트 결과 XML 합계 1,758건, 실패 0, 건너뜀 0. LiveSelectionJudgementTest 4/4, LiveSelectionSessionTest 8/8, RecordingSelectionTest 4/4, LiveJudgementTest 17/17, ArchitectureTest 19/19, ModuleOwnershipTest 13/13 통과
  - 선정 없는 등록의 판정 파일이 바이트로 같은지: origin/main과 HEAD를 각각 임시 worktree로 꺼내 `LiveJudgementTest#writesTheJudgementNextToTheRegistration`이 만든 `judgement.md`를 파일로 떨궈 `cmp` → **같음**(9,387바이트, 130줄). 임시 worktree는 지움(작업 트리는 바꾸지 않음)
  - 보호 경로(`scripts/ci/protected-paths.txt`)와 바뀐 파일 대조 → 겹치는 것 없음(바뀐 코드는 모두 `autotrade-sim`)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `cleanTest build` 직접 실행, 1,758건 실패 0 |
| B2 | 통과 | 운영 코드 변경은 `autotrade-sim`(com.autotrade.sim.live·recording·selection)뿐. 새 클래스 `SnapshotHeap`은 `com.autotrade.sim.selection`(sim 소유 패키지)이고 이미 쓰던 `com.autotrade.marketdata.daily`만 import(SnapshotHeap.java:3-4, 예전 LiveSelectionPrep.dailyBarRows가 쓰던 것을 옮김). Spring·증권사·DB 의존 없음. ArchitectureTest·ModuleOwnershipTest 통과, 규칙 변경 없음 |
| B3 | 통과 | 바뀐 계약: ① 녹화 판정이 `selections` 등록을 받음(LiveJudgement.java 예전 거절 분기 제거) ② 대조군 규칙(묶음마다 하나, 판정 대상 묶음 필수)과 레인 ID `<ID>-S<k>-NULL<i>` ③ `judgement.md` 계좌 이름·6장 소제목·9장 선정 표 ④ 사유 파일 `<hash>.problem.json`에 선택 필드 `inputs` 추가(없으면 빈 값으로 읽어 다시 계산, SessionSelections.java `readProblem`) ⑤ 녹화 실험 힙 검사·`selections/` 정리. 모두 D-126 1~8항, ADR-012 3.6 마지막 줄, D-124 10항·위험 절, runbook stage-6-live-session 3번에 반영. 적용 기록(`applied.json`) 형식은 불변(LiveSelectionSessionTest#preparationDoesNotReloadTheSnapshotAfterFailingWithTheSameInputs 마지막 단언). REST·runtime-config 설정 키·DB 스키마 변경 없음 |
| B4 | 통과 | 시계·난수: 새 코드에 `now()` 없음, 대조군 시드는 `plan.seed() + i`(LiveJudgement.java `lanes`, LiveSelectionJudgementTest가 `S1-NULL1` 시드 = 실험 시드 + 1 확인). 순회 순서: 대조군 묶음은 계좌 순서(`TreeSet` → `nullControls`), `S<k>`는 그 순서로 매김, 맵은 `LinkedHashMap`. 묶음 나누기 무관: `judge(1).report()`가 `judge(4)`와 같음(LiveSelectionJudgementTest#selectionAccountsAreJudgedWith...). 미래 정보: 모든 레인이 세션 고정 파일만 읽고 다시 계산하지 않음(`LiveReplayCheck.selectionSource` 묶음마다·REC 레인, LiveJudgement.java:408·583), 확인은 `verifiedSelections`+`requireApplied`(LiveJudgement.java:154-156, 리플레이 확인과 같은 함수). 선정 없는 등록 불변: 위 바이트 비교로 직접 확인. 골든 시험(SimulationExperimentRunnerTest 등) 포함 sim 시험 묶음 통과. `Runtime.maxMemory()`는 녹화 실험 입력 거절에만 쓰고 결과 값에 들어가지 않음(RecordingExperiment.java:242) |
| B5 | 통과 | 대조군 분포는 `BigDecimal` 왕복당 순손익을 묶음별로 정렬(LiveJudgement.java `nullPerTrip`). 새 금액 계산 없음. 힙 추정은 바이트 수 `long` |
| B6 | 통과 | 새 동작마다 시험: 선정 계좌 판정 전 경로·묶음별 분포·9장 표·L11(LiveSelectionJudgementTest 1번), 모든 레인의 선정 계좌가 그날 후보만 삼(2번, BASE 2 + PERTURB 2 + 지연 3 + 선정 대조군 3 = 10 Run), 같은 선정 대조군 필수·판정 대상 없는 묶음은 분포 안 돌림(3번), 결과 파일 바뀜·적용 기록 삭제 시 판정 거절(4번), 같은 입력 키로 실패한 날 다시 안 읽음·키 다르면 다시 읽음(LiveSelectionSessionTest), 이전 `selections/` 정리·힙 부족 안내(RecordingSelectionTest 2건). 지우거나 끈 시험 없음(diff의 test 변경은 추가뿐). 빈틈은 아래 R3 |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트 규칙, 리스크 한도, 장애 대응, 증권사 연동, 보호 테스트·보호 경로 변경 없음. 바뀐 것은 모의 판정·선정 준비(실험실·명령 프로세스)뿐 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 로그·예외 문구는 스냅샷 ID, 행 수, 힙 MB, 상대 경로만 담음(SnapshotHeap.java `requireFits`, RecordingExperiment `removeStaleSelections`) |

## 점검표 밖 발견

막을 결함은 찾지 못했다. 코드로 따로 본 것:
- 변형 Run을 프로필 ID 대신 레인 안 순서로 찾음(`Pair.lowIndex/highIndex`): 같은 전략이 선정 없음·선정 묶음 양쪽에서 판정될 때 예전 `run(perturb, id)`는 첫 번째 것을 잘못 집었을 것이다. 순서는 `variants`에 넣는 순서 = PERTURB 레인 계좌 순서라 맞다. `lowId == null`이면 인덱스(−1)를 쓰지 않는다(LiveJudgement.java `judge`).
- `inputsKey`의 스냅샷 ID가 내용으로 고정되는지: 수집기가 ID 끝에 내용 SHA 앞 8자리를 붙인다(autotrade-app-collector SnapshotWriter.java:84). 같은 ID = 같은 입력이라 같은 키로 건너뛰는 근거가 선다.
- `removeStaleSelections`는 `experiments/<ID>/selections/` 아래만 지우고, 실행이 예외로 끝나면 부르지 않는다(RecordingExperiment.java:394). 이 폴더는 이 명령이 만들고 커밋하지 않는 생성 파일이라 지침 8장 3번(실험 결과 삭제)에 걸리지 않는다고 본다 `[추론]`.

권고(머지를 막지 않음):
- **R1 (권고) L10 통계 설계의 교차 검토.** 선정 계좌의 L10을 같은 선정 묶음 분포로 보고, m은 전체·N은 묶음마다 21m − 1로 둔 것은 판정 통계(D-114) 쪽 결정이다. ADR-012 3.3 "같은 선정 + 무작위 진입"과 맞고 보수적인 쪽(전체 m)이라 코드상 문제는 없지만, D-126은 교차 검토를 architect만 적었다. 지침 9장대로 LIVE02 등록 전 strategy-researcher 확인을 남기길 권한다(같은 폴더에 strategy-researcher.md가 있으나 이 판정의 근거로 쓰지 않았다).
- **R2 (권고) 같은 입력 건너뛰기에 계산 코드 버전이 없다.** 키가 `daily=…;financials=…`뿐이라 계산 코드 버그를 고친 뒤에도 사유 파일을 손으로 지워야 다시 계산한다(runbook에 적혀 있음, D-126 위험 절). 키에 계산 코드 버전(커밋 또는 선정 계산기 버전)을 넣으면 손 작업이 없어진다.
- **R3 (참고) 녹화 실험 힙 검사 연결은 시험이 없다.** `SnapshotHeap.requireFits` 단위 시험은 있으나 `RecordingExperiment`가 실제로 읽기 전에 부르는지(RecordingExperiment.java:242)는 작은 힙을 줄 수 없어 시험하지 않았다. 코드로만 확인했다. `heapFits`를 주입하는 LiveSelectionPrep처럼 판단 함수를 주입하면 시험할 수 있다.
- **R4 (참고)** `SnapshotHeap.KRX_DAILY`와 `CollectionFreshness.KRX_DAILY`가 같은 값 `"krx-daily"`를 따로 둔다. 하나로 모으는 것이 좋다.
- **R5 (참고)** 대조군 Run이 약 (1 + g) × (21m − 1)로 는다(D-126 위험 절). LIVE02 등록 전 판정 시간을 다시 재는 후속이 D-126에 적혀 있음을 확인했다.

## 판정
승인
