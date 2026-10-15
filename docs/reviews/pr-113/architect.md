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

## 재검토 2026-10-15
- 대상: PR #113 / 커밋 `9e51c66` / main 대비 diff 전체(`git diff origin/main...HEAD`, merge-base `d0e9f3f`) 다시 읽음. 수정 커밋 `42b2524..9e51c66`(19개 파일, +288 −47)은 줄 단위로 대조. 이 파일(`architect.md`)의 앞 내용은 수정 커밋에서 바뀌지 않았다(`git diff 6cb8de1..9e51c66 -- docs/reviews/pr-113/architect.md` 빈 출력).
- 점검표: B
- 직접 실행한 것: 새 별도 clone(헤드 `9e51c66`)에서 `./gradlew build` → **BUILD SUCCESSFUL**(2분 28초, 130 작업). 테스트 XML 집계 1171건, 실패·오류 0, 건너뜀 0. 관련 테스트: `ExperimentJobTest` 13, `ExperimentJobsTest` 1(신규), `RecordingExperimentTest` 5(1건 추가), `ExperimentGateTest` 19, `RunProgressTest` 7, `ExperimentJobStatusTest` 7, `ExperimentJobControllerTest` 2, `ArchitectureTest` 19, `ModuleOwnershipTest` 13 모두 통과. `ArchitectureTest.java`·`ExperimentGateTest.java`는 diff 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 성공, 1171건 실패 0·건너뜀 0 |
| B2 | 통과 | 수정 커밋에 새 모듈 의존·새 패키지 없음. `ArchitectureTest`·`ModuleOwnershipTest` 변경 없음·통과 |
| B3 | 통과 | F3 해소: `docs/config/config-keys.md:19`에 "지금 이 키를 읽는 운영 코드는 없다 … `-PmaxParallelRuns`(D-089 결정 4)"를 적음. 새 동작(ID 형식 오류 시 1, 작업 폴더 불가·중복 실행이어도 정지면 4, 상태 파일 메시지의 `<설정 폴더>` 치환, 결정론 기록의 실제 병렬 수)이 D-089 결정 3(`D-089-…md:19-22`), 결정 로그 README, 기획서 v14.33, 운영 절차서(`docs/runbooks/README.md` 종료 코드 표 3·1, `stage-4-walkforward.md:95-96`)에 반영됨 |
| B4 | 통과 | F1 해소: 실제 병렬 수 `Math.min(plan.parallelism(), gate.maxParallelRuns())`를 실행기 인자·로그·결정론 문구에 같이 씀(`CombinationExperiment.java:166-180`, `RecordingExperiment.java:146-160`, `WalkForwardStudy.java:144-163`). 그 값이 1이면 두 번째 실행을 하지 않고 "확인 안 함(실제 병렬 수가 1이라 …)"으로 적는다. 종료 코드 판정은 `"다름"`/`Boolean.FALSE`만 보므로(`RecordingMain.java:74`, `WalkForwardMain.java:77`) 확인 안 함이 2로 잘못 끝나지 않는다. `RecordingExperimentTest#실행_명령_병렬_상한이_1이면_결정론_확인을_같음으로_적지_않는다` 통과. 골든·`RunProgressTest` 그대로 통과. 시스템 시계 사용 없음(`ExperimentJobs.modified`는 파일 수정 시각을 읽는 것이고 판정 시각은 주입 `Clock`) |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 바뀐 동작마다 테스트: `ExperimentJobTest#작업_폴더를_쓸_수_없어도_정지_요청이면_종료_코드_4다`, `#같은_실험이_도는_중이어도_정지_요청이면_종료_코드_4다`, 취소·정지 메시지에 설정 폴더 절대 경로 없음(기존 테스트 2건의 단언 강화), `ExperimentJobsTest#상한을_넘으면_가장_최근_파일을_남기고_자른다`. R4(ID 형식 오류 → 1)는 기존 `ExperimentGateTest`의 `"../EXP"`·ID 없음 → 1 단언이 그대로 지킨다. 테스트 삭제·`@Disabled` 없음 |
| B7 | 통과(risk-ops 검토 필요) | 정지 확인 배선 변경이라 risk-ops 검토가 따로 있다(`docs/reviews/pr-113/risk-ops.md`, 판정 수정 필요(A12 F1)). 그 F1은 `ExperimentMain.java:102`, `:108`의 `stoppedOr(gate, 1)`(`:122-129`)로 고쳐졌고 위 테스트 2건이 있지만, **risk-ops 재검토 원문은 아직 없다.** D-089 상태 줄이 이 PR을 실전 관련 PR(사용자 직접 머지, 독립 QA 관문)로 바꿨다(`D-089-…md:4-5`). 머지 전에 risk-ops 재검토 승인과 사용자 머지가 필요하다 |
| B8 | 통과 | 수정 커밋에 비밀값 없음. 상태 파일(REST로 나감) 메시지에서 설정 폴더 경로를 `<설정 폴더>`로 바꿈(`ExperimentJob.java:230`, `:252-255`) |

### 지난 지적 처리
- F1(결정론 기록의 병렬 수): 해소(B4).
- F2(risk-ops 검토): risk-ops 검토 원문이 생겼고 D-089 교차 검토·상태 줄이 맞춰졌다. risk-ops 재검토는 risk-ops 몫으로 남는다(B7).
- F3(`lab.max-parallel-runs` 문서): 해소(B3).
- R1(주석 위치): 해소(`LabWebConfiguration.java` 주석이 `snapshotList` 위로 돌아감).
- R2(REST 메시지의 절대 경로): 해소. 단, 아래 R5.
- R3(500개 상한 순서): 해소(`ExperimentJobs.java:90`, 수정 시각 내림차순 뒤 자름).
- R4(ID 형식 오류 때 본체 실행): 해소(`ExperimentMain.java:91-95`, 종료 코드 1).

### 새 권고(판정을 막지 않음)
- **R5** `withoutConfigDir`는 `files.dir().getParent().getParent()`(정규화한 절대 경로) 문자열을 바꾼다. 그런데 정지 요청 경로는 `configDir.resolve(STOP_REQUEST)`(`ExperimentMain.java:174`)로 정규화하지 않은 채 메시지에 들어간다. 기본 설정 폴더(`ConfigPaths.defaultLocation()`, 홈 아래 절대 경로)에서는 같지만, `AUTOTRADE_CONFIG_DIR`이 상대 경로이거나 `..`·끝 `/`를 포함하면 치환되지 않아 원래 경로가 REST로 나갈 수 있다. 실행 명령 진입점에서 `configDir`를 `toAbsolutePath().normalize()`해 두면 된다.
- **R6** 결정론 "확인 안 함(병렬 1)" 경로의 테스트는 녹화 실험만 있다. 조합·워크포워드도 같은 형태라 위험은 낮지만, 같은 단언을 하나씩 두면 이후 리팩터링에서 갈라지는 것을 막는다.

### 판정
승인(architect 범위. 머지 조건: risk-ops 재검토 승인, 실전 관련 PR로서 독립 QA와 사용자 직접 머지)

## 재검토 2026-10-15 (2차)
- 대상: PR #113 / 커밋 `9ffc8cc` / main 대비 diff 전체(`git diff origin/main...HEAD`, merge-base `d6fb6e7`, 파일 41개 +2,347 −49) 다시 읽음. 직전 재검토(`9e51c66`) 뒤 더해진 것은 독립 QA 보고서(`5cffde4`), main `d6fb6e7` 병합(`9e2221f`), QA 권고 반영(`9ffc8cc`, 7개 파일 +119 −13)이고 이 둘은 줄 단위로 대조했다. 이 파일의 앞 내용은 바뀌지 않았다(`git diff b6dab56 HEAD -- docs/reviews/pr-113/architect.md` 빈 출력).
- 점검표: B
- 직접 실행한 것:
  - 새 별도 clone(헤드 `9ffc8cc`)에서 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(3분 31초, 130 작업). 테스트 XML 집계 1,175건, 실패·오류 0, 건너뜀 0. `RunProgressTest` 8, `ExperimentJobTest` 15, `ExperimentJobsTest` 1, `ExperimentJobStatusTest` 7, `ExperimentJobControllerTest` 2, `RecordingExperimentTest` 5, `ExperimentGateTest` 19, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 8 모두 통과.
  - GitHub REST: PR #113 `mergeable: false`, `mergeable_state: dirty`, 헤드 `9ffc8cc` check run **0건**(PR CI가 돌지 않음).
  - 현재 main `b4ed7fc`(PR #114 머지) 시험 병합: **충돌 3파일** `docs/decisions/README.md`(D-089 행과 main의 D-090 행), `docs/qa/pending.md`(#113 줄과 #114 줄), `docs/spec/autotrade-spec.md`(버전 칸 v14.33 대 v14.34, 변경 이력 v14.33 행과 v14.34 행). 별도 worktree에서 두 행을 모두 남기고 버전 칸을 v14.34로 둔 뒤 `./gradlew build` → BUILD SUCCESSFUL, 1,211건 실패 0·건너뜀 0(운영 코드끼리는 충돌·호환 문제 없음). 이 시험 병합은 버렸다.
  - 변이(운영 코드 한 곳씩 고치고 시험 뒤 `git checkout`으로 복원, `git status` 깨끗함 확인):
    - M4 `SimulationExperimentRunner.java:161` `Math.min(parallelism, cap)` → `parallelism`: `:autotrade-sim:test --tests '*RunProgressTest'` **3회 모두 잡힘**. 실패한 시험은 기존 `병렬_상한_1이면_한_번에_Run_하나만_돈다`와 새 `병렬_상한_1이면_먼저_돈_Run이_오래_걸려도_다음_Run이_겹쳐_시작하지_않는다`("상한 1인데 Run 두 개가 함께 돌았다").
    - M7 `ExperimentMain.java:116` `control.afterCancel(dispatch(...))` → `dispatch(...)`: `:autotrade-app-experiment:test` **살아남음**(아래 R7).
    - M8 `ExperimentMain.java:59` `configDir.toAbsolutePath().normalize()` 제거: **살아남음**(아래 R8).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 헤드 `9ffc8cc` 빌드 성공 1,175건 실패 0. 현재 main과의 시험 병합 빌드도 성공(1,211건). 단 PR 자체는 main과 충돌해 CI가 돌지 않음(F1) |
| B2 | 통과 | `9ffc8cc`에 새 모듈 의존·패키지 없음(`java.util.PriorityQueue`, `java.util.concurrent` 시험용만). `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 변경 없음(main에서 온 `RealPathReflectionTest` 변경은 PR #110 것)·통과 |
| B3 | 통과 | 계약 변경 없음(REST·스키마·설정 키 그대로). 동작 변경(취소 뒤 정지 요청이면 4)이 D-089 결정 2(`D-089-…md:14`)와 검증 절(`:50-52`), 운영 절차서 종료 코드 표(`docs/runbooks/README.md:59` "취소와 겹치면 4가 이김")에 반영. 기획서 v14.33 행 문구("결과 없이 끝남(종료 코드 3)")는 겹침 규칙을 빼고 요약한 것이라 모순은 아님 |
| B4 | 통과 | `9ffc8cc`는 실행기 운영 코드를 바꾸지 않음. 골든 바이트 일치 시험(`RunProgressTest` 상한 1·2·64) 통과. 새 시험의 1초 대기는 시험 쪽 `CountDownLatch.await`이고 결과 값에 영향 없음. `ExperimentJobs`의 정렬은 수정 시각(파일마다 한 번 읽음) 내림차순 뒤 경로 오름차순으로 순서를 명시, 판정 시각은 주입 `Clock` |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험: `ExperimentJobTest#취소로_멈추는_사이_정지_요청이_생기면_종료_코드_4와_정지_이유를_남긴다`(4·`STOPPED`·메시지에 설정 폴더 경로 없음), `#정지_요청이_없으면_취소는_취소로_남고_다른_종료_코드는_그대로다`(0·2는 정지 요청이 있어도 그대로), `RunProgressTest` 겹침 시험(M4로 물림 확인). `ExperimentJobsTest` 그대로 통과(힙 교체 뒤에도 최근 500개 유지). 시험 삭제·`@Disabled` 없음(시험 diff는 추가만, `Recorder`의 `final` 제거는 익명 하위 클래스용). 배선·정규화의 시험 공백은 R7·R8 |
| B7 | 통과(risk-ops 검토 필요) | `afterCancel`은 장애표 #10 감지 단서(종료 코드 4)와 정지 순서 0번 경로를 바꾼다. risk-ops 원문(`docs/reviews/pr-113/risk-ops.md`)의 마지막 판정은 `9e51c66` 기준 승인이라 `9ffc8cc` 변경은 risk-ops가 다시 봐야 한다. 실전 관련 PR로 독립 QA 재검토와 사용자 직접 머지 대상 그대로 |
| B8 | 통과 | `9ffc8cc`에 비밀값 없음. 새 콘솔 출력(`ExperimentJob.java:229-230`)은 정지 이유 문자열만(설정 폴더 경로는 콘솔에는 그대로 두는 기존 기준과 같음). 상태 파일 메시지는 `finish`의 `withoutConfigDir`를 거침(`:249`), 진입점 정규화(`ExperimentMain.java:59`)로 R5 해소 |

### 지난 지적·QA 권고 처리
- QA A14(main `d6fb6e7` 충돌): `9e2221f`로 해소. 충돌 두 곳 모두 양쪽 행을 남겼고 기획서 버전 칸은 그 시점 main(v14.32)보다 하나 높은 v14.33. 내용 손실 없음(`git show --cc 9e2221f`). 그러나 그 뒤 main이 `b4ed7fc`로 움직여 다시 충돌한다(F1).
- QA Q1(상한 1 겹침 시험): 해소. M4를 직접 3회 돌려 새 시험이 잡는 것을 확인.
- QA R1(취소 뒤 정지 요청 표시): 해소(`ExperimentJob.java:220-232`, `ExperimentMain.java:116`). 실행 명령 본체 6개에서 3을 돌려주는 곳은 `e.exitCode()`뿐이라(grep `return [0-9];` 결과에 3 없음) 3이 취소 말고 다른 뜻으로 올라와 4로 바뀌는 경우는 없다. 0·2(결과를 이미 씀)는 바꾸지 않는다.
- QA R2(작업 목록 메모리): 해소(`ExperimentJobs.java:85-104`). 힙 비교자는 `newestFirst.reversed()`라 머리가 가장 오래된 것이고 넘치면 그것을 버린다. 수정 시각을 못 읽으면 `Instant.MIN`이라 먼저 버려진다.
- architect R5(설정 폴더 정규화): 해소(`ExperimentMain.java:58-59`). 단 시험 없음(R8).
- architect R6(조합·워크포워드의 "확인 안 함" 시험): 이번에도 반영 안 됨. 권고로 남김.

### 수정 필요
- **F1 현재 main `b4ed7fc`와 충돌, PR CI 미실행.** `docs/decisions/README.md`(D-089 행 대 D-090 행), `docs/qa/pending.md`(#113 줄 대 #114 줄), `docs/spec/autotrade-spec.md`(버전 칸, 변경 이력). GitHub `mergeable_state: dirty`, 헤드 check run 0건이라 CI(PostgreSQL 스키마 적용, gitleaks, 수집기 경계 검사 포함)의 통과를 확인하지 못했다. 고칠 방향: main을 병합하고 결정 로그는 D-089·D-090 두 행을 번호 순으로 모두 두며, 기획서 버전 칸은 **v14.34**(main 값, v14.33은 main의 v14.34 행이 이 브랜치 몫으로 비워 둔 번호)로 두고 v14.33 행은 v14.34 행 아래에 남긴다. 병합 뒤 CI 통과를 확인한다. 이 방식으로 시험 병합한 빌드는 위와 같이 통과했다.

### 새 권고(판정을 막지 않음)
- **R7** `afterCancel`의 배선(`ExperimentMain.java:116`)을 지워도 시험이 통과한다(M7). `ExperimentJob` 단위 시험은 메서드만 고정한다. 장애표 #10 감지 단서에 걸린 경로라, `ExperimentMain.run`에 가짜 본체(또는 기존 시험용 명령)를 넣어 "취소 요청 → 실행 중 정지 요청 생성 → 종료 코드 4·상태 `STOPPED`"를 끝까지 보는 시험이 있으면 좋다. risk-ops 판단에 따라 막는 항목으로 올릴 수 있다.
- **R8** 설정 폴더 정규화(`ExperimentMain.java:59`)를 지워도 시험이 통과한다(M8). `config.resolve("x/..")` 같은 비정규 경로로 `run`을 불러 상태 파일 메시지에 절대 경로가 없음을 보는 시험 하나로 고정할 수 있다.
- **R6**(지난 권고) 그대로 열림.

### 판정
수정 필요(F1 현재 main b4ed7fc와 docs/decisions/README.md·docs/qa/pending.md·docs/spec/autotrade-spec.md 충돌, PR CI 미실행. 코드 쪽 실패 항목은 없음. 병합 뒤 risk-ops 재검토(B7)와 독립 QA 재검토, 사용자 직접 머지 필요)

## 재검토 2026-10-15 (3차)
- 대상: PR #113 / 커밋 `998da51` / main 대비 diff 전체(`git diff origin/main...HEAD`, merge-base `b4ed7fc`, 파일 41개 +2,495 −48) 다시 읽음. 2차 재검토(`497c034`) 뒤 더해진 것은 main `b4ed7fc` 병합(`e000c7a`), risk-ops 2차 원문(`9c2998c`)과 그 병합(`8c9fb3f`, `docs/reviews/pr-113/risk-ops.md` +43만), 권고 반영(`998da51`, 4개 파일 +68 −4)이고 이것들은 줄 단위로 대조했다. 이 파일의 앞 내용은 바뀌지 않았다(`git diff 497c034 HEAD -- docs/reviews/pr-113/architect.md` 빈 출력).
- 점검표: B
- 직접 실행한 것:
  - 작업 트리와 다른 새 clone(헤드 `998da51`)에서 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(3분 9초, 136 작업). 테스트 XML 집계 1,213건, 실패·오류 0, 건너뜀 0. `ExperimentJobTest` 17, `RunProgressTest` 8, `ExperimentJobStatusTest` 7, `ExperimentJobsTest` 1, `ExperimentJobControllerTest` 2, `RecordingExperimentTest` 5, `ExperimentGateTest` 19, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 8 모두 통과.
  - GitHub REST(헤드 `998da51`): PR `mergeable: true`, `mergeable_state: unstable`. check run 3건 모두 `completed`/`success`: `Gradle build`(08:22:15Z), `DB schema (PostgreSQL 16)`(08:19:09Z), `Secret scan and collector boundary`(08:18:51Z). commit status API는 0건(`state: pending`)이라 `unstable`은 그 빈 status 때문으로 보인다 `[추론]`. 실패한 검사는 없다.
  - 검토 중 main이 `88212d3`(PR #115, D-091)으로 움직였다. `git merge-tree`로 시험 병합 → 충돌 없음(`docs/config/config-keys.md`, `docs/decisions/README.md` 자동 병합). 병합 결과에서 결정 로그 D-089·D-090·D-091이 번호 순, 기획서 버전 칸 v14.34(main), 변경 이력 v14.34 행 아래 v14.33 행. 별도 worktree에서 그 병합을 `./gradlew build --no-daemon` → BUILD SUCCESSFUL, 1,220건 실패 0·건너뜀 0. 시험 병합은 버렸다.
  - 변이(운영 코드 한 곳씩 고치고 `:autotrade-app-experiment:test --tests '*ExperimentJobTest'` 뒤 `git checkout`으로 복원, `git status` 깨끗함 확인):
    - M7 `ExperimentMain.java:125` `control.afterCancel(body.applyAsInt(control))` → `body.applyAsInt(control)`: **잡힘**(`실행_명령은_취소로_끝난_본체도_정지_요청이_있으면_종료_코드_4로_끝낸다`, 4 기대·3 실제).
    - M8 `ExperimentMain.java:60` 정규화 제거(`givenConfigDir` 그대로): **잡힘**(`설정_폴더는_상대_경로와_점점이_섞여도_정규화한_절대_경로로_쓴다`).
    - M9 `ExperimentMain.java:115` `runControlled(...)` 우회(`dispatch(..., control)` 직접): **잡힘**(`실행_명령은_입력_오류도_상태_파일에_남기고_잠금을_푼다` RUNNING으로 남음, `병렬_상한은_옵션으로_받고_설정_파일은_읽지_않는다` 2 기대·4 실제).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 헤드 `998da51` 빌드 성공 1,213건 실패 0. PR CI 3건 모두 success. 최신 main `88212d3`과의 시험 병합 빌드도 성공(1,220건) |
| B2 | 통과 | `998da51`의 운영 변경은 `ExperimentMain`(app-experiment) 안에서 `runControlled` 추출과 지역 변수화뿐, 새 import는 `java.util.function.ToIntFunction`(JDK). 새 모듈·패키지 없음. `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest`는 이 PR diff에 없음(main 쪽 변경만)·통과 |
| B3 | 통과 | 계약 변경 없음: `runControlled`는 패키지 전용 static이고 REST·상태 파일 형식·설정 키·실행 옵션 그대로. D-089 검증 절 시험 수 `ExperimentJobTest`(17)이 결과 XML 17건과 일치(`D-089-…md:52`). 병합 해소: 결정 로그 D-089 행과 D-090 행 모두 유지(`docs/decisions/README.md`), 기획서 버전 칸 v14.34·v14.33 행 유지(`docs/spec/autotrade-spec.md:12`, 변경 이력), `docs/qa/pending.md` #114 줄과 #113 줄·#113 재검토 줄 모두 유지. 내용 손실 없음(`git show --cc e000c7a`) |
| B4 | 통과 | 실행기·골든 운영 코드 변경 없음. `RunProgressTest` 상한 1·2·64 골든 바이트 일치 통과. 새 시험은 고정 `Clock`(`Clock.fixed(WEEKEND, …)`, `MovingClock`)만 씀 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 지난 R7·R8의 시험 공백이 메워짐: `ExperimentJobTest#실행_명령은_취소로_끝난_본체도_정지_요청이_있으면_종료_코드_4로_끝낸다`(`runControlled` 경로로 4·`STOPPED`·정지 이유·취소 요청 파일 삭제), `#설정_폴더는_상대_경로와_점점이_섞여도_정규화한_절대_경로로_쓴다`(`run`에 비정규 상대 경로를 넣고 콘솔 출력의 상태 파일·정지 요청 파일 경로가 정규화 절대 경로임). 위 M7·M8·M9를 모두 잡음. 시험 diff는 추가만, `@Disabled`·삭제 없음. `System.setOut` 교체는 `finally`에서 복원하고 JUnit 병렬 실행 설정이 없어(grep `junit-platform.properties` 없음) 다른 시험과 겹치지 않음 |
| B7 | 통과(risk-ops 검토 필요) | `runControlled`는 장애표 #10 감지 단서(종료 코드 4)와 정지 순서 0번 경로의 배선이다. 이 절을 쓰는 동안 원격 브랜치에 risk-ops 3차 원문(`d69303f`, `docs/reviews/pr-113/risk-ops.md` `## 판정 (3차 재검토)` "승인")이 올라왔다. 그 커밋은 그 파일 +41줄만 바꿔 이 검토 대상 코드에 영향이 없다(`git diff --stat 998da51 d69303f`). 실전 관련 PR로 독립 QA 재검토(`docs/qa/pending.md` "PR #113 재검토해줘" 줄 있음)와 사용자 직접 머지 대상 그대로 |
| B8 | 통과 | `998da51`에 비밀값 없음. 새 시험은 임시 폴더 경로만 씀. CI `Secret scan and collector boundary` success |

### 지난 지적 처리
- **F1**(main `b4ed7fc` 충돌, CI 미실행): 해소. `e000c7a`가 권고한 방식(두 행 유지, 버전 칸 v14.34)으로 풀었고 헤드 CI 3건 success. 최신 main `88212d3`과도 충돌 없음(위 시험 병합).
- **R7**(afterCancel 배선 시험): 해소. M7·M9가 잡힘.
- **R8**(설정 폴더 정규화 시험): 해소. M8이 잡힘. 상태 파일 메시지 대신 콘솔 출력 경로로 고정했지만, 메시지 가림(`withoutConfigDir`)은 같은 `configDir` 값을 쓰므로 정규화 여부를 고정하기에 충분하다 `[추론]`.
- **R6**(조합·워크포워드의 "확인 안 함" 시험): 이번에도 반영 안 됨. 권고로 남김.

### 새 권고(판정을 막지 않음)
- **R9** R8 시험의 `Path.of("").toAbsolutePath().relativize(config…)`는 Windows에서 작업 폴더와 임시 폴더의 드라이브가 다르면 `IllegalArgumentException`으로 시험이 깨진다(사용자 PC가 Windows, `CLAUDE.md` QA 폴더 `C:\…`). 저장소와 `%TEMP%`가 같은 드라이브면 문제없다 `[추론]`(Windows에서 돌려 보지 못함). 드라이브가 다를 때를 피하려면 `@TempDir` 아래에서 `config.resolve("sub").resolve("..")`처럼 절대 비정규 경로를 하나 더 쓰거나 `Assumptions`로 같은 루트일 때만 상대 경로 경우를 보게 할 수 있다.
- **R6**(지난 권고) 그대로 열림.
- 참고(risk-ops 3차 R9와의 차이): risk-ops 원문은 `run`이 `runControlled`를 거치지 않게 바꾸는 변이(M7b)를 시험이 못 잡는다고 적었다. 이 검토의 M9(`ExperimentMain.java:115`를 `return dispatch(args[0], forwarded, configDir, control);`로 바꿈)는 위와 같이 두 시험이 잡았다. 변이 모양이 다를 수 있어(예: 우회하면서 `afterCancel`·`finish`를 따로 부르는 경우) 두 결과는 모순이 아닐 수 있다 `[추론]`. 판정에는 영향 없음.

### 판정
승인(코드·문서 실패 항목 없음, F1·R7·R8 해소, CI 3건 success. 실전 관련 PR이라 독립 QA 재검토와 사용자 직접 머지 필요, risk-ops 3차 원문은 승인)
