# architect 재검토: 선정 조합 계좌를 녹화 실험·실시간 모의에 연결(D-124, ADR-012 배치표 4번)
- 대상: PR #197 / 커밋 3b6a360f306f6bdad5ecf7e2faf87c3d7c75c9c4 / `git diff origin/main...HEAD` 전체(첫 검토 [architect.md](architect.md)의 6a57bef 뒤로 ed726ea·8a9f039·3b6a360 세 커밋 추가)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(작업 폴더 HEAD = 3b6a360, `git status` 깨끗함 확인): 처음 4번은 Maven Central 429로 의존성 받기에서 실패했고, 다섯 번째에 **BUILD SUCCESSFUL in 2m 20s, 138 actionable tasks**.
  - 로컬 시험 결과 XML 합계: tests 1703, skipped 0, failures 0, errors 0. 이 PR 관련: SessionSelectionsTest 6/6, LiveSelectionSessionTest 7/7(`sessionDoesNotOpenWhenAppliedRecordOfRecordedDayIsMissing` 포함), RecordingSelectionTest 2/2, LiveLabServiceTest 16/16, ExperimentGateTest 22/22, ArchitectureTest 19/19, ModuleOwnershipTest 13/13. 골든 스냅샷 시험이 있는 SimulationExperimentRunnerTest 21/21, RunProgressTest 8/8, RecordingExperimentTest 7/7, LiveSessionTest 13/13.
  - CI를 GitHub API로 직접 확인(3b6a360): Gradle build success(check run 118557327935, 로그 "BUILD SUCCESSFUL in 3m 44s, 138 actionable tasks: 138 executed"), Lab screens (Playwright)·DB schema·Secret scan 모두 success.
  - `scripts/ci/protected-paths.txt`와 diff 파일 목록 대조: 보호 경로 없음.
  - 6a57bef 뒤 세 커밋을 `git show`로 직접 읽음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 로컬 `./gradlew build` 성공(3b6a360), CI Gradle build 성공 |
| B2 | 통과 | ArchitectureTest 19·ModuleOwnershipTest 13 통과, `:autotrade-sim:verifyPureDependencies` 통과(빌드 성공). sim에 넣은 jackson-databind는 pure 금지 그룹이 아니고 marketdata와 같은 판(autotrade-sim/build.gradle.kts:11-13). 새 의존은 app-lab·app-experiment → sim 방향뿐 |
| B3 | 통과 | REST `POST /api/live/selections/prepare`, `View.prepare`, `Status.selections`: ADR-005 갱신. 등록 키 `selections`·`selection-input`, 결과 폴더·`applied.json` 모양, `live-prepare` 명령과 종료 코드: D-124 1·4·7항, ADR-010·ADR-012 갱신. N1 동작(기록일에 적용 기록이 없으면 실패)은 D-124 9항에 반영(8a9f039). runtime-config 설정 키 변경 없음 |
| B4 | 통과 | 시간은 주입 clock만 씀(LiveLabService `prepareLoop`), 밖 명령은 `--date`가 필수, 녹화 실험 Producer는 만든 시각을 null로 둠. 적용 기록은 그날 첫 이벤트를 기록하기 전에 쓰고, 이제 하드 링크로 놓아 이미 있으면 먼저 놓인 쪽을 따름(SessionSelections `apply`·`writeAtomic`, 3b6a360). 선정 계산은 cutoff = 직전 거래일까지만 읽음(SelectionCalculator.java:96-104). 첫 검토 N1(적용 기록이 사라진 기록일을 조용히 MISSING으로 봄)은 고쳐짐: 확인 지점은 `LiveReplayCheck.requireApplied`, LiveSession 되살리기(이벤트가 있는 기록일마다), LiveReplayCheck.run, LiveWeeklyCheck. 시험은 LiveSelectionSessionTest#sessionDoesNotOpenWhenAppliedRecordOfRecordedDayIsMissing. 골든 시험(SimulationExperimentRunnerTest, RunProgressTest)도 그대로 통과. 선정 없는 등록은 `SelectionSchedule.empty()` 경로 그대로(LiveSession 생성자) |
| B5 | 해당 없음 | 새 금액 계산 없음. 기존 Money 경로를 씀 |
| B6 | 통과 | 새 동작마다 시험이 있고 모두 통과(위 숫자). ed726ea는 시험 고정 데이터만 바꿈: 시작일을 `krx.coverageFrom()`으로 자르고, 30거래일이 안 되면 예외를 던짐(SelectionFixtures.dailySnapshot). 기대값을 느슨하게 하지 않았다. diff에 시험 삭제나 `@Disabled` 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트 규칙·리스크 한도·장애 대응·보호 경로를 바꾸지 않음. 선정 후보에서 등록 파일 blacklist를 빼는 것(SelectionCandidates.of)은 모의 계좌의 진입 후보를 거르는 것이고, 주문 직전 RiskChain 블랙리스트 검사는 그대로다 |
| B8 | 통과 | CI gitleaks 성공. diff에 키·토큰·계좌번호가 없음. 예외 메시지에는 세션 폴더 기준 상대 경로만 들어감 |

## 첫 검토 항목 처리
- N1(수정 필요) → **해결.** 8a9f039의 `requireApplied`가 입력 기록에 줄이 있는 거래일마다 `applied.json`을 요구한다. 없으면 세션을 열지 않고 리플레이 확인·주간 점검도 실패한다. 줄이 없는 날은 요구하지 않는다(시험에 있음).
- N2(수정 필요) → **해결.** `ExperimentJobStatus.COMMANDS`에 `live-prepare` 추가(6a57bef), 캘린더 범위 밖 고정 데이터 수정(ed726ea). 로컬과 CI 모두 전체 빌드 통과.
- R1(권고) → **반영.** 덮어쓰지 않는 쓰기를 `Files.createLink`로 바꿔, 이미 있으면 `FileAlreadyExistsException`으로 false를 돌려준다. `apply`는 다른 쪽이 먼저 쓴 기록을 다시 읽어 따른다. 하드 링크가 안 되는 파일 시스템에서만 확인 뒤 이름 바꾸기가 남고, 그 한계는 D-124 위험 절에 적혀 있다.
- R2~R4(권고) → D-124 위험 절에 후속으로 기록됨. 막을 정도는 아니다.

## 점검표 밖 발견(이번 재검토)
- **R5 (참고)** LiveSession 되살리기의 `requireApplied`는 입력 기록 폴더 이름(받은 날짜)을 날짜로 쓰고, 적용 기록은 거래일(`rules.tradingDate(exchangeTime)`) 기준이다. KRX는 받은 날과 거래일이 같아 지금은 문제가 없다. 다만 자정을 넘겨 받은 이벤트 같은 경우엔 이 확인이 실제보다 엄격하게, 즉 열지 않는 쪽으로 틀린다. 안전한 쪽이라 막지 않는다. 미국 시장(서머타임, 현지 날짜와 다를 수 있음)을 붙일 때는 거래일 기준으로 바꿔야 한다.
- **R6 (참고)** `writeAtomic`의 하드 링크 경로에는 "이미 있으면 덮어쓰지 않음"을 직접 보는 시험이 없다. 지금은 apply/writeResult가 이미 있는 파일을 그대로 두는 시험을 간접 근거로 삼는다. 사용자 PC가 Windows(NTFS)라, 처음 준비하는 날 `selections/<T>/`에 `.tmp-` 파일이 남지 않는지 한 번 확인하길 권한다.

## 판정
승인
