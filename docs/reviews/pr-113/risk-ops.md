# risk-ops 검토: 실험 실행기 진행 보고·취소·병렬 상한 (SIM-14, D-089)
- 대상: PR #113 / 커밋 4015e5813aa3 / main 대비 diff `git diff origin/main...HEAD` 전체(merge-base d0e9f3f, 28개 파일: `autotrade-app-experiment` build.gradle.kts·ExperimentMain·ExperimentJob(신규)·ExperimentJobTest(신규), `autotrade-app-lab` LabWebConfiguration·ExperimentJobController(신규)·ExperimentJobs(신규)·ExperimentJobControllerTest(신규), `autotrade-sim` RunGate·SimulationExperimentRunner·ExperimentStoppedException·ExperimentCancelledException(신규)·ExperimentJobFiles(신규)·ExperimentJobStatus(신규)·ExperimentProgress(신규)·실행 명령 본체 6개(종료 코드)·RunProgressTest(신규)·ExperimentJobStatusTest(신규), 문서 D-089·결정 로그·ADR-004·ADR-005·기획서). 이 변경이 지나가는 실전 보호 경로로 `ExperimentGate`(D-063 정지 요청 파일·장 시간 보호, broker-and-fault-design 5.2 0번, 장애표 #10), `ArchitectureTest.EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`·`APPS_RUN_EXPERIMENTS_THROUGH_GATE`, `StopRequestPathTest`, `LiveLabService`의 정지 요청 확인을 함께 봤다.
- 점검표: A
- 직접 실행한 것 (별도 clone, 헤드 4015e58, 작업 트리 깨끗함 확인):
  - `./gradlew build --no-daemon`: **BUILD SUCCESSFUL**(2분 52초). `:autotrade-risk:test` 55/실패 0/건너뜀 0, `:autotrade-architecture-test:test` 40/0/0(`ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest`·`StopRequestPathTest`), `:autotrade-app-engine:test` 50/0/0, `:autotrade-sim:test` 239/0/0(`RunProgressTest`·`ExperimentJobStatusTest` 포함), `:autotrade-app-experiment:test` 30/0/0(`ExperimentGateTest`·`ExperimentJobTest`), `:autotrade-app-lab:test` 158/0/0(`ExperimentJobControllerTest`), `:autotrade-app-gateway:test` 49/0/0, `:autotrade-app-watchdog:test` 1/0/0.
  - 실제 Gradle 작업 연결(`AUTOTRADE_CONFIG_DIR`=임시 폴더, 실행 시각 2026-10-15T06:32Z = KST 15:32, 국내 보호 구간 안):
    - 정지 요청 파일 + `-PallowDuringSession -PmaxParallelRuns=2`로 `runRecordingExperiment` → 종료 코드 4, "감시 프로세스가 자원 부족으로 정지를 요청했습니다(… 만든 시각 …)" 출력, 상태 파일 `STOPPED`·`max-parallel-runs: 2`, **정지 요청 파일 그대로 남음**.
    - 정지 요청 없이 `runStrategyStudy`(보호 구간) → 종료 코드 4, "KRX 장 시간 보호 구간(08:00~16:00 Asia/Seoul)", 상태 파일 `STOPPED`, 병렬 상한 기본 4.
    - `-PmaxParallelRuns=0` → 종료 코드 1, 작업을 시작하지 않음.
    - **정지 요청 파일 + 작업 폴더를 쓸 수 없음**(`experiment-state/jobs`를 일반 파일로 만들어 재현) + `-PallowDuringSession`으로 `runRecordingExperiment` → **종료 코드 1**, "작업 폴더(…)를 쓸 수 없어 시작하지 않습니다: FileAlreadyExistsException"만 출력, 정지 요청 메시지 없음(아래 F1).
  - 변이 시험(되돌림, 되돌린 뒤 `:autotrade-app-experiment:test` 통과 확인):
    - `ExperimentJob.check()`에서 취소 확인을 정지 요청 확인보다 앞에 두면 → `ExperimentJobTest#감시의_정지_요청이_취소보다_먼저다` 실패(1건).
    - `ExperimentJob.check()`가 `ExperimentGate`를 묻지 않게 하면 → `ExperimentGateTest` 9건 + `ExperimentJobTest` 1건 실패(정지 요청·장 시간 보호가 새 래퍼를 지나서도 테스트로 지켜짐).
  - `git diff --name-status origin/main...HEAD`: 수정(M)은 운영 코드·빌드 스크립트·문서뿐, 기존 테스트 파일 수정·삭제 없음(테스트 4개는 모두 신규 A).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff가 `autotrade-risk`·`autotrade-app-engine`·`autotrade-control`·설정 로더를 건드리지 않음(`git diff --name-status`). `:autotrade-risk:test` 55/0, `:autotrade-app-engine:test` 50/0 통과. 새 코드는 Guard 타입·설정 키를 읽거나 쓰지 않음 |
| A2 | 통과 | `ArchitectureTest.java` 변경 없음, `:autotrade-architecture-test:test` 40/0. 실험 명령 패키지는 `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`(ArchitectureTest.java:273-282)로 `com.autotrade.broker..`·`com.autotrade.domain.order..`·설정 로더 의존 금지 그대로이고 새 `ExperimentJob`도 이 규칙 안에서 통과. 실험실 새 클래스(`ExperimentJobs`·`ExperimentJobController`)는 파일 읽기와 `cancel-request` 생성만 함(ExperimentJobs.java:118-135) |
| A3 | 해당 없음 | 증권사 주문 연동·플러그인 코드 변경 없음. 실험 명령은 증권사 패키지 의존 금지(ArchitectureTest.java:281) |
| A4 | 해당 없음 | 실전 주문·타임아웃 처리 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음. 모의 실행기(`RunStepper`)는 바뀌지 않았고 `RunProgressTest#진행_보고와_병렬_상한은_골든_결과를_바꾸지_않는다`가 상한 1·2·64·진행 보고 유무에서 골든 바이트 일치를 확인 |
| A6 | 통과 | 블랙리스트·수동 보유 필터와 `RiskChain`은 바뀌지 않음. `SimulationExperimentRunner` 생성자의 `risk` 인자·검증(SIM-12) 그대로이고 바뀐 곳은 병렬 수 상한(SimulationExperimentRunner.java:156-161)과 진행 보고(같은 파일 `Progress`)뿐. 진행 보고는 Run 상태를 건드리지 않음(골든 일치 테스트) |
| A7 | 해당 없음 | 실전 계좌 주문 큐·토큰 버킷·킬스위치 코드 변경 없음 |
| A8 | 통과 | 실전 엔진·게이트웨이·감시 코드 변경 없음. 실험 명령의 병렬 Run이 기본 4로 묶여(ExperimentMain.java `DEFAULT_MAX_PARALLEL_RUNS`, SimulationExperimentRunner.java:160) 실험 쪽 스레드 사용이 줄어든다(자원 측면 개선). 상태 파일 심장박동 스레드는 데몬·`MIN_PRIORITY`·5초 주기(ExperimentJob.java:79-86). 실험실은 프로세스에 신호를 보내지 않고(ExperimentJobs.java 주석·코드에 `ProcessHandle` 없음, PID는 표시용) 실험 ID 형식(`ExperimentId`)으로만 `experiment-state/jobs/<ID>.cancel-request`를 만들어 감시의 `experiment-state/stop-request`를 만들거나 지울 수 없음(ExperimentJobFiles.java:38-48, ExperimentJobs.java:128·137-138). 5.2 0번 정지 확인은 새 래퍼를 지나서도 정지 요청이 취소보다 먼저이고 파일을 지우지 않음(ExperimentJob.java:149-158, 실제 Gradle 실행과 변이 시험으로 확인). 단, 작업 폴더를 못 쓰면 정지 확인 전에 종료 코드 1로 끝나는 문제는 A12(F1) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(diff 추가 줄에서 `appkey|secret|password|token|계좌번호` 검색: 테스트의 CSRF 토큰 변수 이름만 나옴). 상태 파일·REST에는 실험 ID, 명령 종류, PID, 진행 수치, 정지·취소 이유 한 줄만 들어감(ExperimentJobStatus.java `encode`). 정지 이유에 설정 폴더 절대 경로가 들어가는 점은 비밀값은 아니므로 점검표 밖 R1로 둠 |
| A11 | 통과 | 기존 테스트 수정·삭제 없음(`git diff --name-status`에서 테스트는 모두 신규). 보호 테스트(`ArchitectureTest`, Guard·GuardedOrderGateway 테스트, `ExperimentGateTest`, `StopRequestPathTest`) 그대로 통과. 새 동작에 실패 경우 테스트 있음: 같은 실험 동시 시작 거부, 정지 요청이 취소보다 먼저·정지 요청 파일 유지(`ExperimentJobTest#감시의_정지_요청이_취소보다_먼저다`), 옵션 범위 밖 거부, 상태 파일 형식·키 끼워 넣기·크기 상한 거부(`ExperimentJobStatusTest`), CSRF 없으면 403·응답 없는 명령 취소 409(`ExperimentJobControllerTest`). 변이 시험 2종에서 테스트가 실제로 잡음 |
| A12 | **실패** | F1: 장애표 #10(broker-and-fault-design.md:169)은 감시가 죽거나 멈춘 채 정지 요청 파일이 남은 경우의 감지 수단을 "일괄 명령의 거부 메시지(파일 시각 포함)와 종료 코드(… 실험 실행 명령 4 …)"로 적고 있다. 이 PR은 `ExperimentMain`에서 `ExperimentJob.start`(작업 폴더 생성·잠금 파일·상태 파일 쓰기)를 정지 확인보다 먼저 하고, 여기서 `IOException`이 나면 정지 확인 없이 종료 코드 1로 끝낸다(ExperimentMain.java:95-103). 직접 재현: 정지 요청 파일 + 작업 폴더 쓰기 불가 → 종료 코드 1, 정지 요청 메시지 없음. 쓰기 실패가 가장 그럴 법한 경우는 디스크 고갈, 즉 #10 자체다. 실험은 돌지 않으므로(안전 쪽) 실전 보호가 약해지지는 않지만, #10의 감지 전제(정지 요청이 있으면 종료 코드 4와 파일 시각이 담긴 메시지)가 이 경우 깨지고 대응표·테스트는 함께 바뀌지 않았다. 장 시간 보호 구간에서도 같은 경우 4 대신 1이 나온다 |
| A13 | 통과 | 진행률의 거래일 수는 `rules.tradingDate(e.exchangeTime())`로 셈(SimulationExperimentRunner.java `tradingDayCount`), 즉 `MarketRules`의 거래소 현지 날짜. 시장 분기 추가 없음. 장 시간 보호(`SessionProtection`, 국내·미국 캘린더)는 바뀌지 않음 |
| A14 | 통과 | D-089(docs/decisions/D-089-…md), 결정 로그 README 한 줄, 기획서 v14.33 SIM-14·이연 표, ADR-004 4장·ADR-005 5장·9장이 코드와 일치(종료 코드 3, 기본 상한 4, `/api/experiment-jobs` 3개, 409 조건, 정지 요청 우선). D-089 상태 줄의 "실전 엔진·게이트웨이·감시 코드 변경 없음, D-058·D-063 그대로"는 diff로 확인함. 운영 절차서의 종료 코드 안내 보강은 점검표 밖 R2 |

## 점검표 밖 발견
- **F1 (A12, 수정 필요)**: 정지 확인을 작업 파일 준비보다 앞에 두거나, 작업 폴더를 못 쓸 때 정지 확인 결과를 먼저 보고 정지면 종료 코드 4와 원래 정지 메시지로 끝내도록 고친다. 예: `ExperimentMain.run`에서 `ExperimentJob.start` 전에 `gate.stopReason()`을 한 번 보고 있으면 기존 본체 경로(`dispatch(args[0], forwarded, configDir, gate)`)로 넘겨 기존 정지 메시지·종료 코드 4를 그대로 쓰거나, `IOException` catch에서 같은 처리를 한다. 시험: 정지 요청 파일 + 작업 폴더 쓰기 불가 → 종료 코드 4·정지 메시지, 장 시간 보호 + 작업 폴더 쓰기 불가 → 종료 코드 4. 정지 요청이 없고 작업 폴더만 못 쓰는 경우를 1로 둘지 4로 둘지는 sim-engine 판단에 맡기되, 지금처럼 시작하지 않는 쪽은 유지한다.
- R1 (권고): 정지 이유 문자열(`ExperimentGate.stopReason`)에는 설정 폴더 절대 경로가 들어가고, 이 문자열이 상태 파일 `message`를 거쳐 실험실 REST `/api/experiment-jobs`로 나간다(ExperimentJob.java `finish`, ExperimentJobs.java `view`). 비밀값은 아니고 실험실 REST는 로컬 전용이지만, ADR-005의 `/api/live/status` `stopReason`은 절대 경로를 담지 않는다고 정했으므로 같은 기준으로 맞추기를 권한다(취소 메시지 `cancelMessage()`의 경로도 같음).
- R2 (권고): 종료 코드 3(취소)과 "같은 실험이 이미 실행 중이면 1"이 운영 절차서(docs/runbooks/README.md 50행 근처, stage-4-walkforward.md 91·100행)에 아직 없다. 예약 실행 결과를 보는 사람이 3을 장애로 오해하지 않도록 한 줄 보강을 권한다. 장애표 #10의 "실험 실행 명령 4"는 자원 보호 정지에만 쓰이므로 바꿀 필요는 없다.
- 관찰(문제 아님): 실험실은 `experiment-state/` 아래에 처음으로 파일을 만드는 프로세스가 됐다. 만드는 것은 `jobs/<실험ID>.cancel-request`뿐이고 실험 ID 정규식으로 경로 문자가 막혀 `stop-request`를 만들거나 지울 수 없다(ExperimentJobTest#작업_ID는_실험_ID_인자에서_정한다의 `../x` 거부, ExperimentJobStatusTest 경로 문자 거부). 취소 요청은 정지 요청과 달리 "판단 못 함"을 취소로 보지 않지만(ExperimentJob.java:161-164), 자원 보호는 정지 요청 파일이 계속 맡으므로 5.2 정지 순서를 약하게 하지 않는다.
- 관찰(문제 아님): 같은 실험 ID 동시 실행을 OS 파일 잠금으로 막아 결과 폴더 덮어쓰기를 없앤다. 프로세스가 죽으면 OS가 잠금을 풀어 다음 실행을 막지 않는다(`ExperimentJobTest#시작하면_실행_중_상태를_쓰고_같은_실험은_두_번_시작하지_않는다`).

## 판정
수정 필요(A12: F1 — 작업 폴더를 쓸 수 없을 때 정지 요청·장 시간 보호 확인 전에 종료 코드 1로 끝나 장애표 #10의 감지 전제(종료 코드 4와 정지 메시지)가 깨짐. 고친 뒤 같은 검토자 재검토. R1·R2는 권고)

- 실전 관련 PR 여부: 이 PR은 broker-and-fault-design 5.2 0번(장애표 #10)의 정지 확인이 지나가는 실험 실행 명령의 관문 배선(`ExperimentMain`의 `RunGate`를 `ExperimentJob`으로 감쌈)을 바꾼다. 규칙 자체(정지 요청 우선, 감시만 파일 삭제, 장 시간 보호)는 바뀌지 않았고 보호 테스트도 그대로지만, 장애 대응 경로의 코드를 건드리므로 같은 경로를 바꾼 PR #105(D-085)의 선례대로 지침 10장의 실전 관련 PR(사용자 직접 머지, 독립 QA 관문)로 다루기를 권한다.

## 재검토 2026-10-15
- 대상: PR #113 / 커밋 9e51c66 / main 대비 diff `git diff origin/main...HEAD` 전체(merge-base d0e9f3f). 직전 검토(4015e58) 뒤 바뀐 것은 9e51c66 한 커밋(17개 파일): `ExperimentMain`·`ExperimentJob`·`ExperimentJobTest`, 실험실 `ExperimentJobs`·`LabWebConfiguration`·`ExperimentJobsTest`(신규), sim `CombinationExperiment`·`RecordingExperiment`·`WalkForwardStudy`·`WalkForwardReport`·`RecordingExperimentTest`, 문서 D-089·결정 로그·기획서·config-keys·runbooks 2개.
- 점검표: A
- 직접 실행한 것 (별도 clone, 헤드 9e51c66, 작업 트리 깨끗함 확인):
  - `./gradlew build --no-daemon`: **BUILD SUCCESSFUL**(1분 41초). `:autotrade-risk:test` 55/실패 0/건너뜀 0, `:autotrade-architecture-test:test` 40/0/0, `:autotrade-app-engine:test` 50/0/0, `:autotrade-sim:test` 240/0/0, `:autotrade-app-experiment:test` 32/0/0, `:autotrade-app-lab:test` 159/0/0, `:autotrade-app-gateway:test` 49/0/0, `:autotrade-app-watchdog:test` 1/0/0.
  - 실제 Gradle 작업(`AUTOTRADE_CONFIG_DIR`=임시 절대 경로, 2026-10-15T06:41Z = KST 15:41, 국내 보호 구간 안):
    - 정지 요청 파일 + 작업 폴더 쓰기 불가(`experiment-state/jobs`를 일반 파일로) + `-PallowDuringSession`, `runRecordingExperiment` → **종료 코드 4**, 작업 폴더 경고 다음 줄에 "멈췄습니다(결과 파일을 쓰지 않음): 감시 프로세스가 자원 부족으로 정지를 요청했습니다(… 만든 시각 …)" 출력. 정지 요청 파일 그대로.
    - 정지 요청 없음 + 작업 폴더 쓰기 불가 + 보호 구간, `runStrategyStudy` → **종료 코드 4**, "KRX 장 시간 보호 구간(08:00~16:00 Asia/Seoul)" 출력.
    - 정지 요청 파일 + 정상 작업 폴더, `runRecordingExperiment -PallowDuringSession` → 종료 코드 4, 상태 파일 `message`가 `<설정 폴더>/experiment-state/stop-request`로 절대 경로가 빠짐, 정지 요청 파일 그대로.
  - 변이 시험(되돌림, 되돌린 뒤 `:autotrade-app-experiment:test` 통과 확인): `ExperimentMain`의 `stoppedOr(gate, 1)` 두 곳을 `return 1`로 되돌리면 `ExperimentJobTest#작업_폴더를_쓸_수_없어도_정지_요청이면_종료_코드_4다`·`#같은_실험이_도는_중이어도_정지_요청이면_종료_코드_4다` 2건 실패. 직전 검토의 변이 2종(취소를 정지보다 앞에, 관문 무시)은 해당 코드가 바뀌지 않아 다시 돌리지 않음.
  - `git diff --name-status origin/main...HEAD`: 기존 테스트 파일 수정은 `RecordingExperimentTest` 하나이고 추가만 있음(삭제 줄 0). `autotrade-architecture-test`·`autotrade-risk`·`autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-app-gateway`·증권사 플러그인 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·엔진·설정 로더 변경 없음(위 diff 확인). `:autotrade-risk:test` 55/0, `:autotrade-app-engine:test` 50/0 |
| A2 | 통과 | `ArchitectureTest.java` 변경 없음, `:autotrade-architecture-test:test` 40/0. 실험 명령 패키지의 `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`(ArchitectureTest.java:273-282) 그대로 통과. 실험실은 상태 파일 읽기와 `cancel-request` 생성만(ExperimentJobs.java `cancel`·`read`) |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음. 실험 명령은 `com.autotrade.broker..` 의존 금지(ArchitectureTest.java:281) |
| A4 | 해당 없음 | 실전 주문·타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음. `RunProgressTest#진행_보고와_병렬_상한은_골든_결과를_바꾸지_않는다` 통과(골든 바이트 일치) |
| A6 | 통과 | 블랙리스트·`RiskChain` 변경 없음. 이번 수정의 `CombinationExperiment`·`RecordingExperiment`·`WalkForwardStudy` 변경은 실행기에 넘기는 병렬 수(`Math.min(plan.parallelism(), gate.maxParallelRuns())`)와 결정론 기록 문구뿐이고 `risk`·블랙리스트 인자는 그대로 |
| A7 | 해당 없음 | 실전 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진·게이트웨이·감시 변경 없음. 5.2 0번 정지 확인은 래퍼를 지나서도 정지 요청이 취소보다 먼저이고 파일을 지우지 않으며(ExperimentJob.java:149-158), 작업 폴더를 못 쓰거나 같은 실험이 이미 돌 때도 정지 확인 결과로 4를 냄(ExperimentMain.java:102·108·122). 병렬 Run 기본 상한 4 유지. 실험실은 `stop-request`를 만들거나 지울 수 없음(실험 ID 정규식) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 비밀값 없음. 상태 파일(실험실 REST로 나감) 메시지의 설정 폴더 절대 경로를 `<설정 폴더>`로 바꿈(ExperimentJob.java:230·252), `ExperimentJobTest`가 `doesNotContain(config.toString())`로 확인하고 실제 실행에서도 확인함 |
| A11 | 통과 | 보호 테스트(`ArchitectureTest`, Guard·GuardedOrderGateway 테스트, `ExperimentGateTest`, `StopRequestPathTest`) 변경 없이 통과. 기존 테스트 수정은 `RecordingExperimentTest`에 1건 추가뿐(삭제 0). 새 동작마다 실패 경우 테스트가 있고(F1 테스트 2건, 경로 제거, 병렬 1 결정론 기록, 500개 상한), F1 수정은 변이 시험으로 테스트가 실제로 잡음을 확인 |
| A12 | 통과 | 직전 F1 해소. 장애표 #10(broker-and-fault-design.md:169)의 감지 전제(정지 요청이 있으면 실험 실행 명령은 종료 코드 4와 파일 시각이 담긴 메시지)가 작업 폴더 쓰기 불가·같은 실험 실행 중일 때도 유지됨(`ExperimentJobTest` 새 2건, 실제 Gradle 실행 2종). 종료 코드 3(취소)은 사람의 취소에만 쓰이고 자원 보호 정지는 4 그대로라 #10 문구는 바꿀 필요 없음. 실험 ID 인자가 없거나 틀리면 이제 본체를 돌리지 않고 1로 끝나는데, 이는 기존에도 본체가 정지 확인 전에 내던 입력 오류(1)와 같은 분류다(직전 검토에서 등록 파일 없음 → 1을 실행으로 확인) |
| A13 | 통과 | 진행률 거래일 수는 `rules.tradingDate`(MarketRules, 거래소 현지 날짜). 장 시간 보호(`SessionProtection`) 변경 없음 |
| A14 | 통과 | D-089 상태 줄이 "실전 관련 PR(지침 10장)로 처리, 사용자 직접 머지로 확정"으로 바뀌고 교차 검토에 risk-ops가 들어감. 결정 3의 새 하위 항목(작업 폴더 못 써도 4, 경로 제거, 병렬 1이면 결정론 "확인 안 함")이 코드와 일치. runbooks README 종료 코드 표에 3(취소)과 "같은 실험 실행 중이면 1"이 들어가고, stage-4-walkforward.md도 갱신(직전 R2 해소). config-keys.md의 `lab.max-parallel-runs`에 "지금 이 키를 읽는 운영 코드는 없다"가 적힘 |

## 점검표 밖 발견 (재검토)
- F1 해소(위 A12). R1(상태 파일의 절대 경로) 해소, R2(운영 절차서 종료 코드) 해소.
- R3 (권고, 막지 않음): `ExperimentJob.withoutConfigDir`는 정규화된 절대 경로(`files.dir().getParent().getParent()`)를 문자열로 지운다. `ExperimentGate`는 `configDir.resolve(STOP_REQUEST)`를 정규화하지 않으므로, `AUTOTRADE_CONFIG_DIR`가 상대 경로이거나 `..`를 담으면(직전 검토 첫 실행에서 `…/autotrade-app-experiment/../tmp…` 형태를 실제로 봄) 메시지의 경로가 그대로 남는다. 비밀값이 아니고 실험실 REST는 로컬 전용이라 안전 문제는 아니다. 기본값(`~/.autotrade`)과 절대 경로 설정에서는 동작함을 실제 실행으로 확인했다. 맞추려면 `ExperimentGate`에 넘기는 경로도 `toAbsolutePath().normalize()`로 통일하면 된다.
- 결정론 기록(`inputs.yaml`)의 병렬 수 수정(architect F1)은 sim 결과 기록 문제이고 실전 경로와 무관하다. `RecordingExperimentTest#실행_명령_병렬_상한이_1이면_결정론_확인을_같음으로_적지_않는다`로 확인됨.

## 판정 (재검토)
승인 (점검표 A 전 항목 통과 또는 해당 없음, 확인 못 함 없음. 직전 F1·R1·R2 해소, R3은 막지 않는 권고. 실전 관련 PR이므로 지침 10장·11장에 따라 사용자 직접 머지·독립 QA 관문 대상)
