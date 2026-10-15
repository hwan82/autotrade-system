# architect 검토: 실험 실행기 진행 보고·취소·병렬 상한(SIM-14, D-089)
- 대상: PR #113 / 브랜치 `claude/project-thread-4t6q46` / 커밋 `4015e5813aa3ab9b9a40e6ccae8755dd9cbeef7e` / main 대비 diff(merge-base `d0e9f3f`, 28개 파일, +1856 −31, 삭제 파일 없음)
- 점검표: B
- 직접 실행한 것:
  - 작업 트리와 다른 별도 clone(스크래치 폴더)에서 `./gradlew build` → **BUILD SUCCESSFUL**(2분 26초, 130 작업). 테스트 결과 XML 집계: 1167건, 실패·오류 0, 건너뜀 0.
  - 새 테스트: `RunProgressTest` 7, `ExperimentJobStatusTest` 7, `ExperimentJobTest` 11, `ExperimentJobControllerTest` 2 모두 통과. 보호 테스트 `ArchitectureTest` 19, `ModuleOwnershipTest` 13 통과(두 파일 diff 없음).
  - `git diff origin/main...HEAD` 전체 읽음. 호출처 대조: `RunGate` 구현·래퍼(grep `RunGate` 운영 코드), 정지 확인 호출(`gate.check()` 24곳), 실행 명령 본체 6개의 실험 ID 검사, `plan.parallelism()` 사용처, `lab.max-parallel-runs` 소비처.
  - (참고) 처음 연 clone 폴더를 다른 프로세스가 같은 이름으로 동시에 쓰고 있어 그 빌드 로그는 버리고, 이름이 다른 새 clone에서 다시 돌린 결과만 적었다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공, 1167건 실패 0·건너뜀 0 |
| B2 | 통과 | 새 운영 클래스는 이미 있는 패키지에만 들어감(`com.autotrade.sim.run` 4개, `com.autotrade.app.experiment.ExperimentJob`, `com.autotrade.app.lab.experiment` 2개). sim 새 클래스는 JDK만 쓴다(`ExperimentJobFiles.java:3-10`, `ExperimentJobStatus.java:3-9`). 실행 명령은 설정 로더를 쓰지 않음(`ExperimentMain.java` import에 `autotrade-config` 없음, `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG` 통과). `ArchitectureTest`·`ModuleOwnershipTest` 변경 없음·통과 |
| B3 | 실패 | 계약 변경(① `RunGate` 기본 메서드 2개, ② `ExperimentStoppedException` 비 final화와 `exitCode()`, ③ 상태 파일 형식 `experiment-job-v1`, ④ REST `/api/experiment-jobs` 3개, ⑤ 실행 명령 옵션 `--max-parallel-runs`/`-PmaxParallelRuns`)은 D-089·ADR-004 4장 표·ADR-005 5장 표·기획서 SIM-14에 적혔다. 그러나 설정 키 문서가 실제와 어긋난 채 남는다: `docs/config/config-keys.md:19`의 `lab.max-parallel-runs`는 "즉시(새 Run부터)·실험실"로 효과가 있다고 적혀 있지만 운영 코드에 이 키를 읽는 곳이 없다(grep 결과 `ConfigLoaderTest`뿐). 이 PR이 실제 상한을 `-PmaxParallelRuns`로 정했으므로(D-089 결정 4) 이 행에 "아직 어떤 실행에도 적용되지 않음, 실험 실행 명령은 `-PmaxParallelRuns`(D-089)"를 적어야 한다. 아래 F3 |
| B4 | 통과 | sim 새 코드는 벽시계를 읽지 않는다(`ExperimentJobStatus.stale(Instant now)`는 인자로 받음, `NO_SYSTEM_TIME` 통과). 실행 명령·실험실은 주입된 `Clock` 사용(`ExperimentJob.java:47`, `ExperimentJobs.java:36`). 진행 보고는 Run 상태를 건드리지 않음(`SimulationExperimentRunner.java:250-313`의 `Progress`는 자기 필드만 바꿈). `RunProgressTest#진행_보고와_병렬_상한은_골든_결과를_바꾸지_않는다`(상한 1·2·64, 골든 `krx-golden.report` 바이트 일치), `#진행_보고를_받는_쪽이_예외를_던져도_실험은_끝까지_돌고_결과가_같다` 통과. 기존 골든 테스트 전부 통과. 단, 결정론 확인 **문구**가 실제 병렬 수와 어긋날 수 있음(F1, 결과 값이 아니라 증거 기록 문제) |
| B5 | 해당 없음 | 금액 계산 없음. 진행 값은 Run·거래일 개수(`int`/`long`), 진행률은 정수 천분율(`ExperimentProgress.java:54-61`) |
| B6 | 통과 | 새 동작마다 테스트가 있고 실패 경우를 포함한다: 취소 → 종료 코드 3(`RunProgressTest#취소는_취소_예외로_올라오고_종료_코드는_3이다`), 상한 범위 밖 거부, 같은 실험 두 번 시작 거부, 정지 요청이 취소보다 먼저, 상태 파일 쓰기 실패에도 계속, 상태 파일 형식·키 끼워 넣기·크기 상한 거부, REST 403/404/400/409. 삭제·`@Disabled` 없음(diff에 삭제 파일 없음, 건너뜀 0) |
| B7 | 실패 | **risk-ops 검토 필요.** 실전 주문 경로·Guard·블랙리스트·리스크 한도·실전 엔진·게이트웨이·감시 코드는 바뀌지 않았다. 그러나 실행 명령이 실행기에 넘기는 정지 확인 문(D-063, broker-and-fault-design 5.2 정지 순서 0번, 장애표 #10)의 배선이 바뀌었다: `ExperimentMain`이 이제 `ExperimentGate`를 `ExperimentJob`으로 감싸 넘기고(`ExperimentMain.java:89-113`), `ExperimentJob.check()`가 `RunGate.check()`를 덮어쓴다(`ExperimentJob.java:148-159`). 정지 요청이 취소보다 먼저이고 `ExperimentGate`(있는지 모르면 멈춤)는 그대로라 약화는 보이지 않지만, 같은 장치를 다룬 PR #53에서 risk-ops가 이를 "장애 대응" 규칙으로 보고 A 점검표를 적용했다(`docs/reviews/pr-53/risk-ops.md` "실전 관련 PR 여부"). D-089는 교차 검토를 architect만 적었다(`D-089-experiment-progress-cancel-parallel-cap.md:4`). 브랜치에 `docs/reviews/pr-113/risk-ops.md`가 없다 |
| B8 | 통과 | 새 코드·문서·테스트에 키·계좌번호·토큰 없음. 상태 파일 쓰기 실패 경고는 예외 클래스 이름만 출력(`ExperimentJob.java:208`, `:237`). 상태 파일 읽기 오류 메시지에 파일 내용을 싣지 않음(`ExperimentJobStatus.java:170-216`) |

## 점검표 밖 발견

### 수정 필요
- **F1 결정론 확인 기록이 실제 병렬 수가 아니라 등록 파일 값을 적는다.** 이 PR로 실행기의 스레드 수는 `min(parallelism, gate.maxParallelRuns())`가 되었는데(`SimulationExperimentRunner.java:156-161`), 결과 파일에 남는 결정론 확인 문구와 로그는 여전히 `plan.parallelism()`을 쓴다:
  - `CombinationExperiment.java:165`, `:176` → `inputs.yaml`의 `determinism:`과 `results.md`의 "결정론 확인"(`CombinationReport.java:103`, `:122`)
  - `RecordingExperiment.java:145`, `:156`
  - `WalkForwardStudy.java:144`, `:157`, 그리고 소요 시간 추정 `WalkForwardReport.java:306`

  예: 등록 파일 `parallelism: "4"`에 `-PmaxParallelRuns=1`이면 두 실행이 모두 직렬인데 커밋되는 결과 파일에는 "같음(병렬 4 vs 1, 상세 보고서 바이트 일치)"이 남는다. SIM-09(병렬 수와 무관한 결과)의 증거가 실제로는 확인되지 않았는데 확인된 것처럼 기록된다. 기본 상한 4에서도 `parallelism`이 5 이상인 등록 파일이면 같은 어긋남이 생긴다. 고칠 방향: 실제 쓴 병렬 수(`Math.min(plan.parallelism(), gate.maxParallelRuns())` 또는 실행기가 내주는 값)를 로그와 문구에 쓰고, 그 값이 1이면 "병렬 1 vs 1이라 병렬 결정론은 확인하지 않음"처럼 확인 안 됨으로 적는다. 이 경우를 고정하는 테스트를 더한다.
- **F2(B7)** risk-ops 검토를 받는다. 결과에 따라 지침 10장의 사용자 머지 대상인지 정한다. D-089 "담당·교차 검토" 줄도 그에 맞춘다.
- **F3(B3)** `docs/config/config-keys.md:19` `lab.max-parallel-runs` 행에 지금 적용되는 곳이 없다는 것과 실험 실행 명령의 상한은 `-PmaxParallelRuns`(D-089 결정 4, 기본 4)라는 것을 적는다. 사용자가 실험실 설정 화면에서 이 값을 바꾸면 효과가 있다고 믿게 된다.

### 권고(이번 판정을 막지 않음)
- **R1** `LabWebConfiguration.java:63`: 새 빈을 `snapshotList`의 Javadoc과 그 메서드 사이에 넣어 "수집 스냅샷 폴더" 주석이 `experimentJobs` 위에 붙어 있다. 주석 위치를 바로잡는다.
- **R2** REST 응답 `message`에 절대 경로가 실린다: 취소면 `ExperimentJob.java:167`(취소 요청 파일 경로), 정지면 `ExperimentGate.stopReason()`의 정지 요청 파일 경로가 상태 파일 → `GET /api/experiment-jobs`로 나간다. `/api/live/status`의 `stopReason`은 절대 경로를 담지 않기로 했다(ADR-005 5장). 같은 원칙으로 파일 이름만 남기는 편이 일관된다.
- **R3** `ExperimentJobs.list()`의 500개 상한은 `DirectoryStream` 순서(순서 보장 없음)로 자른다(`ExperimentJobs.java:85-89`). 500개를 넘으면 최신 작업이 빠질 수 있다. 상태 파일은 지우는 곳이 없어 계속 쌓인다. 지금 규모에서는 문제가 아니지만 정리 규칙이나 수정 시각 기준 선별을 후속으로 남긴다.
- **R4** `ExperimentMain.jobId()`가 null이면(실험 ID가 `ExperimentId` 형식 `[A-Z0-9][A-Z0-9-]{0,47}`이 아님) 잠금·상태 파일·병렬 상한 없이 본체를 돌린다(`ExperimentMain.java:89-92`, 기본 `RunGate.maxParallelRuns()` 64). 본체의 ID 패턴은 더 넓지만(`CombinationMain.java:23` 등 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`), 실험 ID를 받는 5개 명령 모두(합성 비교 `study`는 고정 ID) 등록 파일 `id`를 `ExperimentId`로 읽고 폴더 이름과 같아야 하므로(`CombinationExperiment.java:219`, `RecordingExperiment.java:214`, `WalkForwardStudy.java:190`, `LiveReplayCheck.java:77`, `LiveWeeklyCheck.java:148`) 실제로는 실행 전에 입력 오류로 끝난다. 이 암묵 결합 대신 실행 명령에서 바로 종료 코드 1로 끝내거나, 이 경로를 고정하는 테스트를 두는 편이 안전하다.

### 확인한 것(문제 없음)
- 취소 예외 전파: 실행기의 모든 정지 확인이 `gate.check()`를 거치고(`SimulationExperimentRunner.java:482-493`), 처음 받은 예외 객체를 그대로 다시 던져 `ExperimentCancelledException` 타입이 보존된다. `RunGate`를 감싸는 다른 구현이 없다(grep).
- 잠금 순서: `Progress` 모니터 → `ExperimentJob` 모니터 한 방향뿐이고 심장박동·`finish`는 `ExperimentJob` 모니터만 잡는다. 교착 없음. `finish` 뒤 심장박동 쓰기는 `finished` 검사로 막힌다.
- 같은 실험 동시 실행 차단은 OS 파일 잠금이라 프로세스가 죽으면 풀린다. 남은 취소 요청은 잠금을 쥔 뒤에만 지운다(`ExperimentJob.java:119-120`).
- 실험실은 상태 파일 읽기와 `<ID>.cancel-request` 만들기만 한다. 파일 이름은 `ExperimentId` 검사를 거쳐 `stop-request` 등 다른 파일을 만들 수 없다(`ExperimentJobFiles.java:38-48`). PID로 아무것도 하지 않는다.

## 판정
수정 필요(F1 결정론 확인 기록의 병렬 수, F2 risk-ops 검토 필요(B7), F3 `config-keys.md`의 `lab.max-parallel-runs` 적용 범위(B3))
