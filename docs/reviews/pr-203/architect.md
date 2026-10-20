# architect 검토: 녹화 판정이 장중 순위 선정 계좌를 받음(D-128, D-127 후속)
- 대상: PR #203 / 커밋 f80aa93 / `git diff origin/main...HEAD` 전체(24개 파일, +696 −95)
- 점검표: B
- 직접 실행한 것: 별도 worktree(f80aa93, detached)에서 `./gradlew build --continue --init-script <저장소 밖 mirror.gradle>` → **BUILD SUCCESSFUL**, 테스트 결과 XML 합계 1810건 실패 0·오류 0·건너뜀 0. `LiveIntradayJudgementTest` 2/2, `RecordingSelectionTest` 7/7, `IntradayTrackerTest` 8/8, `SelectionProfileTest` 7/7, `LiveSelectionJudgementTest` 4/4, `RealPathReflectionTest` 10/10 통과.
  - 참고: 첫 실행은 빌드 도중 worktree 폴더가 바깥에서 지워져(같은 스크래치 폴더를 쓰는 다른 작업) `RealPathReflectionTest` 2건이 "클래스 파일을 읽지 못했다"로 실패했다. 코드와 무관한 환경 문제라 새 worktree에서 다시 돌린 결과를 판정에 쓴다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 1810건 실패 0 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-sim`, `autotrade-selection`뿐. 새 import는 같은 모듈 안(`SnapshotHeap`, `RunSelectionDay`, `SelectionCandidates`)과 기존 의존(`SelectionProfile`). `SelectionProfileTest`가 `ExchangeCalendar`를 쓰지만 시험 코드이고 빌드·ArchUnit(`ArchitectureTest`, `ModuleOwnershipTest`) 통과. ArchUnit 규칙·소유 표 변경 없음 |
| B3 | 통과 | 인터페이스·DB 스키마·설정 키·REST 변경 없음. 모듈 안 공개 API 변경(`SimulationExperimentRunner.intradayUniverse` → `withIntradayUniverse` SimulationExperimentRunner.java:196, `SelectionDay.allowsEntry(Symbol)` 삭제 SelectionDay.java:76, `SelectionProfile.AT_RANGES` 추가 SelectionProfile.java:160, `CollectionFreshness.KRX_DAILY` → `SnapshotHeap.KRX_DAILY`)은 호출처 전부 갱신(grep 확인, 남은 호출 없음)하고 D-128 7번에 기록. `judgement.md` 보고서 형식 추가(9/10장 장중 순위 표)는 D-128 5번·ADR-012 5.2·runbook `stage-6-live-session.md`에 반영 |
| B4 | 통과 | 시계·난수 새 사용 없음. 레인마다 새 추적기(SimulationExperimentRunner.java:636)에 같은 첫 읽기 이벤트를 같은 순서로 줌(:786). 보고서 순회는 `intradayProfiles`(해시순 TreeMap)×`journal.days()`(날짜순), 맵은 LinkedHashMap. `LiveIntradayJudgementTest#intradayAccountsAre…`가 병렬 4 대 1 보고서 바이트 일치(:175), `#everyLaneOfAnIntradayAccount…`가 레인 단독 실행 = 묶음 실행 확인. 기존 판정·녹화 실험 골든 기대값은 바뀌지 않고(시험 diff는 추가 단언·`allowsEntry(…, 0)` 시그니처 변경뿐) 통과 → 장중 선정 없는 등록 바이트 동일 주장과 맞음 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작 시험: 장전+장중 혼합 녹화 판정 끝단(`LiveIntradayJudgementTest` 2건), 모든 레인의 장중 계좌가 그날 후보만·적용 시작 순번 이후에만 매수, 혼합 녹화 실험(`RecordingSelectionTest#preOpenAndIntradaySelectionsTogether…`), 힙 검사 주입(`#recordingExperimentChecksTheHeapBeforeReadingTheSnapshot`), `AT_RANGES`와 캘린더 정규장 결합(`SelectionProfileTest#at_범위_표는…`), 09:00:00 끝 공백(`IntradayTrackerTest#gapEndingExactlyAtOpenIsStillSessionGap`). 삭제·비활성화된 시험 없음, 건너뜀 0 |
| B7 | 해당 없음 | 실전 경로·RealTradingGuard·블랙리스트 규칙·리스크·증권사·게이트웨이 변경 없음. 바뀐 파일 중 `scripts/ci/protected-paths.txt` 패턴에 걸리는 것 없음(`autotrade-sim`, `autotrade-selection`, docs 일반 문서). 블랙리스트는 기존 `SelectionCandidates.of(…, blacklist)` 경로 그대로 |
| B8 | 통과 | diff에 키·계좌·토큰 문자열 없음(추가 줄 grep), 로그 문구에 비밀값 없음 |

## 점검표 밖 발견
- n1(권고, 막지 않음): `LiveJudgement.intradayTable`의 "**다름**" 칸(LiveJudgement.java:959)과 "순위 없음(순위 시각 전에 기록이 끝남…)" 칸(:941)은 시험이 거치지 않는다. 끝단 시험은 입력 기록과 녹화가 같은 이벤트라 "같음"만 본다. 녹화 쪽에서 장 초반 이벤트를 뺀 기록 하나로 "다름"이 찍히는지 보는 시험을 후속으로 권한다. 판정 값(L1~L11)에는 영향이 없는 보고서 칸이다.
- n2(정보): `everyLaneOfAnIntradayAccount…`의 "레인 단독 = 묶음" 단언은 모든 레인이 같은 이벤트를 읽는 지금 구조에서는 추적기를 나눠 써도 통과한다. 레인 독립은 코드 구조(SimulationExperimentRunner.java:634-637, 레인마다 `new IntradayTracker`)로 확인했다. 레인마다 입력이 달라지는 변경이 오면 그때 구분하는 시험을 더한다.
- n3(정보): `LiveJudgement.intradayRanks`(LiveJudgement.java:559)는 실행기 `scanStream`의 기간 거르기(`spec.from..to`)를 `plan.from..to`로 다시 쓴다. `LiveSetup.spec`이 `plan.from()`·`plan.to()`를 쓰므로 지금은 같다. 끝단 시험이 표의 해시·적용 시작 순번을 레인 결과와 맞춰 보므로 어긋나면 깨진다.
- 확인: 정지 확인(D-063) — 판정이 입력 기록을 한 번 더 읽는 `intradayRanks`도 `StreamedRecordingDataSet`의 날마다 `gate::check`(LiveJudgement.java:150)를 거친다. 장중 선정이 없으면 읽지 않는다.

## 판정
승인
