# architect 검토: D-116 6단계 녹화 판정 명령 runLiveJudgement (stage-6 T2)
- 대상: PR #180 / 브랜치 `claude/project-thread-v72tda` / 커밋 df495ea / `git diff origin/main...HEAD`(merge-base 59e225a = origin/main 59e225a) 전체 15파일 +2013 −46
- 점검표: B
- 직접 실행한 것:
  - 저장소에서 `./gradlew build --no-daemon -q` → **종료 코드 0**(미러 init 스크립트 없이 통과).
  - 시험 결과 XML(이번 실행 2026-10-19T14:31Z 생성) 집계: `autotrade-sim` 342건, `autotrade-architecture-test` 46건, 전 모듈 실패 0·오류 0·건너뜀 0. 이 PR 관련: `LiveJudgeCriteriaTest` 12, `LiveJudgementTest` 9, `SimulationExperimentRunnerTest` 21, `LiveWeeklyCheckTest` 10, 모두 통과.
  - `git merge-tree --write-tree origin/main HEAD` → 충돌 없음. `HEAD..origin/main` 0커밋(main 최신 위).
  - diff 추가 줄에서 `double`/`float`/`now()`/`currentTimeMillis`/`nanoTime`/`new Random`/`Math.random`/`parallelStream`/`HashMap<`/`HashSet<` 검색 → 0건(`LinkedHashMap`만). 비밀값 단어(appkey·secret·token·password·계좌번호) → 0건.
  - 바뀐 파일과 `scripts/ci/protected-paths.txt` 대조 → 걸리는 파일 없음(`autotrade-sim`, `autotrade-app-experiment`, `docs/adr`·`docs/decisions`·`docs/runbooks`만).

## 읽은 것(코드로 직접 확인)
- `SimulationExperimentRunner.runLanes`/`streamLanes`(SimulationExperimentRunner.java:512~670): 레인마다 `validate`(스냅샷·비용 모델·시장·비용 표 범위·조합 상한)를 먼저 모두 통과해야 하고 기간이 다르면 거부, 그 뒤에만 입력을 읽는다. 첫 읽기(`scanStream(window)`)는 첫 레인 명세로 하는데, 기간이 같음을 검사했으므로 `inWindow`·거래일·벤치마크가 레인과 상관없다. Run 생성은 레인 명세·레인 체결 설정으로(`new RunStepper(lane.spec(), i, …, lane.execution(), …, schedules[l])`), 선정 일정은 레인별. 실패 격리(`begin`/`onEvent`/`finish` 각각)·정지 예외 재던짐·둘째 읽기 순서·건수 불일치 거부·예열 거부는 그대로다. `ExperimentResult`는 레인 명세 ID·레인 체결 ID로 만든다. `gaps`는 레인들이 같은 목록을 넘기지만 `SimulationReport`가 `List.copyOf`로 복사한다(SimulationReport.java:18). 단일 실험 경로(`runStreamed`)는 레인 하나짜리로 바뀌었고 기존 시험이 그대로 통과한다.
- `LiveJudgement.run`(LiveJudgement.java:99-165): 세션 잠금 확인(`ensureSessionClosed`) → 입력 기록 열기(호가 단위 검사) → 역할(기준선·대조군 하나씩, 판정 대상 1개 이상, :185-211) → 레인 구성(:230-268) → 묶음 병렬(:290-338) → I3(BASE 레인 렌더 대 `live-detail.txt`, code 줄 제외) → 녹화 리플레이(L11, :350-382) → 판정·렌더. 정지 확인은 시작·묶음 전·녹화 전·실행기 안·녹화일마다. 정지 예외는 결과 파일을 쓰기 전에 올라간다(`runAndWrite`는 `run`이 끝난 뒤에만 씀, :168-179).
- 묶음 병렬의 공유 객체: `StreamedRecordingDataSet.forEach`는 호출마다 지역 변수로 파일을 새로 열어 읽고 공유 가변 상태가 없다(StreamedRecordingDataSet.java:92-112). 실행기는 묶음마다 새로 만든다(`runner(...)`, 병렬 1). 공유하는 `factory`·`risk`는 기존 병렬 Run과 같은 스레드 안전 계약이다. 진행 보고를 받는 `ExperimentJob.onProgress`/`onExperimentsPlanned`는 `synchronized`(ExperimentJob.java:171, 185)이고 `experiments-started`는 STARTED마다 1씩이라 묶음 수 + 1(녹화)과 맞는다(ExperimentJobStatus.withProgress). 묶음 수 = min(4, `gate.maxParallelRuns()`)이고 묶음마다 한 스레드라 SIM-14 상한을 넘지 않는다.
- 결정론: 레인 순서·묶음 배정(`groups`, Run 수 최소·동률이면 앞 묶음)이 고정이고 결과는 실험 ID 열쇠의 `LinkedHashMap`으로 모은다. 대조군 시드 = 실험 시드 + i. 판정 계산은 모두 `BigDecimal`(`MathContext` 40자리 HALF_EVEN, 왕복당 순손익은 DECIMAL64). `TreeMap`·정렬 순서 명시.
- `LiveJudgeCriteria`: L10 판정 `(k+1)·20·m ≤ N+1`은 p ≤ 0.05/m과 같은 정수 비교이고 N + 1 < 20m이면 판정 불가(:252-272). L6 경계(시작일 + 28일 미만/이상), L8 월요일 주, L11 경계 포함(≤), L1 기준선 자기 비교는 판정 불가. 장중 노출은 체결 시각을 장 시각으로 자르고 밤새 보유를 다음 날로 넘긴다(:369-405).
- `LiveWeeklyCheck.BoardsRecording.read`: 주간 점검 하루 대조와 같은 필터(`onBoards`·`inPlan`)·같은 지문 검사(`fingerprintOf`→`ensureStable`)다.
- `ExperimentMain`: 명령 목록·분기에 `live-judge`만 더함. 작업 잠금·장 시간 보호·정지 요청·병렬 상한 처리는 공통 경로 그대로(ExperimentMain.java:71-176). `build.gradle.kts` `runLiveJudgement`는 다른 live 명령과 같은 인자 순서다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build --no-daemon -q` 실행, 종료 코드 0. 전 모듈 시험 실패·오류·건너뜀 0(위 집계) |
| B2 | 통과 | 새 운영 클래스는 `autotrade-sim`의 `com.autotrade.sim.live`(기존 소유 패키지), `autotrade-app-experiment`는 분기 한 줄. 새 의존 없음(`autotrade-sim`은 기존대로 marketdata 녹화 읽기만). ArchUnit(`autotrade-architecture-test` 46건)·순수 모듈 금지 의존 검사 통과. 규칙 변경 없음 |
| B3 | 통과 | 공개 계약 추가: `SimulationExperimentRunner.runLanes`·`Lane` → ADR-004 입력 계약 절에 레인 경로 추가(docs/adr/ADR-004-simulation-engine.md:67). 새 명령·인자·종료 코드 → D-116 결정 1번, docs/runbooks/README.md 표 2곳. 설정 키·DB 스키마·REST 변경 없음 |
| B4 | 통과 | 시스템 시계·시드 없는 난수 없음(diff 검색 0건). 묶음 수와 결과 무관: `LiveJudgementTest#judgesEveryAccountFromTheJournalAndTheReportDoesNotDependOnGrouping`(1·2·4묶음 보고서가 묶음 수 표기만 빼고 같음). 레인 대 단독 실행 바이트 일치: `SimulationExperimentRunnerTest#레인_여럿을_한_번_읽기에_돌려도_레인마다_따로_돈_결과와_같다`, `LiveJudgementTest#stressLatencyColumnEqualsASeparateRunWithThatLatency`. 기존 D-115 골든·격리 시험 통과(SimulationExperimentRunnerTest 21건) |
| B5 | 통과 | LiveJudgeCriteria.java·LiveJudgement.java의 금액·비율 계산이 모두 `BigDecimal`(:58 `MathContext`, :340 DECIMAL64). `double`/`float` 0건 |
| B6 | **실패** | 판정 계산(LiveJudgeCriteriaTest 12건)·판정 흐름(LiveJudgementTest 9건)·레인 경로(SimulationExperimentRunnerTest 신규 3건)는 실패 경우를 포함해 있다. 삭제·비활성화된 시험 없음(시험 diff에서 지운 줄은 `runner` 도우미 인자 한 줄뿐, 오버로드로 대체). 그러나 **새 프로세스 진입 명령 `live-judge`의 명령 단 시험이 없다**: 형제 명령 `live-check`·`live-weekly`는 `ExperimentGateTest`에서 장 시간 보호 → 4, 정지 요청(해제 옵션 있어도) → 4, 인자 오류(인자 부족·`../EXP`·모르는 옵션·없는 등록) → 1, 결과 파일 없음을 고정한다(ExperimentGateTest.java:184-250). `live-judge`는 이 중 아무것도 없고 `LiveJudgeMain`의 종료 코드 2(무사고 깨짐) 대응도 시험되지 않는다 → R1 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동·보호 테스트를 건드리지 않는다(바뀐 파일이 보호 경로에 없음). 장 시간 보호·정지 요청 관문(D-063)은 쓰기만 하고 동작을 바꾸지 않는다 |
| B8 | 통과 | diff 추가 줄 비밀값 단어 0건. 로그·예외 메시지는 경로·실험 ID·프로필 ID·수치만 담는다(LiveJudgement.java, LiveJudgeMain.java) |

## 수정 요청
- **R1(B6)**: `ExperimentGateTest`에 `live-judge`용 시험을 형제 명령과 같은 수준으로 더한다. 최소: (a) 장 시간 보호 구간 → 4, 정지 요청이 있으면 `--allow-during-session`이어도 → 4, 둘 다 `judgement.md`를 쓰지 않음, (b) 인자 부족·`../EXP`·모르는 옵션·등록 파일 없음 → 1. 가능하면 (c) `LiveJudgeMain`이 무사고 깨짐을 2로 돌려주는지(예: `LiveJudgementTest#differentLiveReportIsAnIncident`와 같은 준비로 `LiveJudgeMain.run` 호출). 이 명령은 실전 엔진과 같은 PC에서 장 시간 보호에 기대는 실험 명령이라, 분기 추가만으로 관문이 걸린다는 것을 시험으로 고정해야 한다.

## 점검표 밖 발견(차단 아님)
- N1(strategy-researcher 확인 권장): 대조군 시드 수를 정확히 20m − 1로 두므로 대조군 하나라도 실패하거나 왕복 0건이면 N + 1 < 20m이 되어 **모든 계좌의 L10이 판정 불가 → 보류**가 된다(LiveJudgement.java:137, LiveJudgeCriteria.java:255). LIVE00(4일)처럼 짧은 세션에서 일어날 수 있다. 여유 시드를 더 돌리거나(결과는 앞 20m − 1개만 쓰지 말고 완료분 전체) D-116 위험과 한계에 적기를 권한다.
- N2: `runGroups`는 `Future`를 순서대로 `get()`하므로 뒤 묶음이 먼저 묶음 고유 예외로 죽어도 앞 묶음이 끝날 때까지(최장 수 시간) 기다린 뒤 실패를 올린다(LiveJudgement.java:320-332). 정지·취소는 모든 묶음이 같은 관문을 보므로 바로 멈추고 결과에는 영향이 없다. 개선하려면 `ExecutorCompletionService`로 먼저 끝난 실패를 받는다.
- N3: 보고서 머리말은 "다시 돌리면 같은 내용이 나온다"인데 "N묶음" 표기가 병렬 상한에 따라 달라진다(LiveJudgement.java:475-476). 판정 내용은 같다(시험으로 확인). 문구를 "판정 내용"으로 좁히거나 묶음 수를 뺄 것을 권한다.
- N4: docs/runbooks/README.md:38 "`-PallowDuringSession`(다섯 명령 모두 …)"은 이 PR 전부터 명령 수와 맞지 않았고 이번에 하나 더 늘었다. 숫자 대신 "실험 명령 모두"로.
- N5: D-116 자체가 교차 검토로 strategy-researcher(판정 기준 계산, L5 탈락/보류 `[가정]`)를 지정한다. `docs/reviews/pr-180/`에 그 원문이 아직 없다. 지침 9장에 따라 머지 전에 받는다.

## 판정
수정 필요(R1)
