# 독립 QA 검토: PR #113 실험 실행기 진행 보고·취소·병렬 상한(SIM-14, D-089)
- 대상: PR #113 / 헤드 c7f5661 (merge-base d0e9f3f, 검토 시점 main d6fb6e7) / main 대비 diff 전체(`git diff origin/main...HEAD`, 파일 40개 +2,185 −49: `autotrade-app-experiment` `ExperimentJob`·`ExperimentMain`·build.gradle.kts, `autotrade-app-lab` `ExperimentJobs`·`ExperimentJobController`·`LabWebConfiguration`, `autotrade-sim` `RunGate`·`SimulationExperimentRunner`·`ExperimentJobFiles`·`ExperimentJobStatus`·`ExperimentProgress`·`ExperimentCancelledException`·`ExperimentStoppedException`와 실행 명령 본체 6개, 시험 6개, D-089·ADR-004·ADR-005·config-keys·runbooks·기획서·결정 README·pending.md, 개발 쪽 검토 원문 2개)
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 장애 설계 5.2 정지 순서 0번·장애표 #10의 정지 확인이 지나가는 실험 실행 명령의 관문 배선 변경)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 c7f5661, 별도 worktree): Maven Central 429로 5회 실패 뒤 6회째 `BUILD SUCCESSFUL`. 결과 XML 합계 1,171건, 실패·오류·건너뜀 0
  - 병합 확인: `git merge --no-commit origin/main`(d6fb6e7) → **충돌 2파일** `docs/qa/pending.md`(#110 줄과 #113 줄), `docs/spec/autotrade-spec.md`(변경 이력 v14.33 줄과 main의 v14.30 "번호 미사용" 줄). GitHub `mergeable_state: dirty`
  - CI(헤드 c7f5661): check run 0건(충돌로 병합 ref가 없어 PR CI가 돌지 않음)
  - 탐침 P1(시험 소스에 임시 시험을 두고 실행 뒤 삭제): 실행기 병렬 2, 문이 세 번째 확인에서 `ExperimentCancelledException`, 그 뒤 모든 확인에서 `ExperimentStoppedException`(정지 요청 파일이 뒤에 생긴 경우) → 올라온 예외 `ExperimentCancelledException`, `exitCode()=3`, 문 확인 3회에서 끝
  - 변이 시험 M1~M6(운영 코드 한 곳씩 고치고 해당 모듈 `test`, 각 실험 뒤 원문 복원, `git status` 깨끗함 확인)

## 탐침과 변이 결과
| # | 시도 | 결과 |
|---|---|---|
| M1 | `ExperimentJob.check()`에서 취소 확인을 정지 확인 앞으로 | 잡힘(app-experiment 32건 중 1 FAILED) |
| M2 | `ExperimentMain` 작업 폴더 `IOException` 갈래 `stoppedOr(gate, 1)` → `return 1` | 잡힘(1 FAILED) |
| M3 | `ExperimentJob.start`의 남은 취소 요청 지우기 제거 | 잡힘(1 FAILED) |
| M4 | `SimulationExperimentRunner` 생성자 `Math.min(parallelism, cap)` → `parallelism`(실행기 쪽 상한 무시) | **살아남음**(sim 전 시험 통과). 아래 Q1 |
| M5 | `AlreadyRunningException` 갈래 `stoppedOr(gate, 1)` → `return 1` | 잡힘(1 FAILED) |
| M6 | 진행 보고 예외 시 `off = true` 대신 다시 던짐 | 잡힘(sim 240건 중 1 FAILED) |
| P1 | 취소를 먼저 본 뒤 정지 요청이 생김 | 종료 코드 3(취소). 정지 이유는 출력·상태 파일에 안 남음. 아래 R1 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 기본값 변경 없음. diff 파일 목록에 engine·gateway·watchdog·guard·broker 경로 없음(ADR-004 문서 제외) |
| A2 | 통과 | `ArchitectureTest`·`RealPathReflectionTest` 변경 없음. 새 운영 코드는 sim·app-experiment·app-lab 안이고 주문 출구를 만들지 않음. 전체 빌드에서 ArchUnit 통과 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트 경로 변경 없음. 모의 Run의 `RiskChain`(블랙리스트 스냅샷) 생성 경로 그대로, 골든 바이트 일치(`RunProgressTest` 진행_보고와_병렬_상한은_골든_결과를_바꾸지_않는다 통과) |
| A7 | 해당 없음 | 실전 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 정지 확인 순서 유지: `ExperimentJob.check()`(ExperimentJob.java:149)가 감시 정지 요청·장 시간 보호(`ExperimentGate.stopReason`)를 취소보다 먼저 보고 종료 코드 4(M1로 시험이 물림). 작업 폴더를 못 쓰거나 같은 실험이 이미 돌 때도 정지 이유와 종료 코드 4(ExperimentMain.java:102·108, M2·M5로 물림). 실험실은 취소 요청 파일만 만들고 프로세스에 신호를 보내지 않음(ExperimentJobs.java `cancel`), 파일 이름은 실험 ID 형식(`[A-Z0-9][A-Z0-9-]{0,47}`) + `.cancel-request`라 감시 정지 요청 파일을 만들 수 없음. 취소 POST는 기존 `LocalOnlyRequestFilter`(루프백 Host·Origin·CSRF) 아래. 병렬 기본 4로 줄어드는 쪽이라 자원 사용은 늘지 않음. 프로세스는 취소·정지 어느 쪽이든 결과 없이 끝남(P1). 정지 원인 표시는 R1 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(전체 diff 읽음). 상태 파일 메시지에서 설정 폴더 절대 경로를 `<설정 폴더>`로 바꿈(ExperimentJob.java:252). 경로 정규화가 다르면 안 바뀌는 점은 개발 쪽 권고(architect R5·risk-ops R3)로 남음, 비밀값은 아님 |
| A11 | 통과 | 시험 diff는 추가만(삭제 줄 0). 보호 테스트 변경 없음. 새 동작 시험 6개 파일 +791줄, 변이 M1~M3·M5·M6이 잡힘. 단 M4는 안 잡힘(Q1) |
| A12 | 통과 | 장애표 #10(broker-and-fault-design.md:169)의 "실험 실행 명령 4" 감지 단서는 정지 요청이 있을 때 유지됨(종료 코드 4 + 정지 이유, 작업 폴더 실패·중복 실행 갈래 포함). 5.2 0번(:144)의 확인 지점(준비 단계·선정 계산·Run 시작 전·거래일)은 그대로이고 같은 지점에 취소 확인이 더해짐. 장애 테스트 8종의 전제(실전 엔진·게이트웨이·감시 코드) 변경 없음 |
| A13 | 해당 없음 | 시장별 규칙 변경 없음. 진행 보고의 거래일 수는 `rules.tradingDate`로 셈(결과 영향 없음) |
| A14 | **실패** | 결정 D-089·ADR-004·ADR-005·config-keys·runbooks·기획서 v14.33은 코드와 맞음(표본: 종료 코드 3 `ExperimentCancelledException.exitCode`, `-PmaxParallelRuns` 기본 4 `ExperimentMain.DEFAULT_MAX_PARALLEL_RUNS`, REST 409 조건 `ExperimentJobs.cancel`). 그러나 **현재 main d6fb6e7과 충돌**: `docs/spec/autotrade-spec.md` 변경 이력(main의 v14.30 "번호 미사용" 줄, PR #111)과 `docs/qa/pending.md`(main의 PR #110 줄). 병합하지 않은 상태라 기획서 단일 원본의 변경 이력이 main과 맞지 않고, PR CI도 돌지 않음 |
| B1 | 통과 | 헤드 c7f5661 `./gradlew build` BUILD SUCCESSFUL, 1,171건 실패 0(429 재시도는 빌드 실패로 보지 않음). main 병합 뒤 빌드는 충돌 때문에 확인하지 않음(재검토 때 확인) |
| B6 | 통과 | `RunProgressTest` 7, `ExperimentJobStatusTest` 7, `ExperimentJobTest` 13, `ExperimentJobControllerTest` 2, `ExperimentJobsTest` 1, `RecordingExperimentTest` +1. 건너뜀 0. 실패 경우(중복 실행 거부, 범위 밖 상한, 깨진 상태 파일, CSRF 없음 403, 409) 포함 |

## 점검표 밖 발견
시도한 시나리오(잘못된 주문·중복 실행·보호 공백 관점):

1. **취소 뒤 정지 요청(P1)**: 실행기는 처음 받은 정지 예외 하나만 남긴다(SimulationExperimentRunner.java:209·490 `stopped.compareAndSet`). 사람이 취소한 직후 감시가 자원 부족으로 정지 요청 파일을 만들면 명령은 종료 코드 3과 취소 메시지로 끝나고, 상태 파일도 `CANCELLED`다. 프로세스는 결과 없이 끝나 자원 보호 자체는 됨. 다음 실행은 정지 요청 파일로 종료 코드 4를 낸다. → R1
2. **같은 실험 ID 동시 실행**: OS 파일 잠금으로 뒤 명령 거부(종료 코드 1, 정지 요청이 있으면 4). 강제 종료(kill) 직후 60초 안에는 상태가 `RUNNING`으로 보여 실험실 취소가 202로 받아지지만, 다음 실행이 잠금을 쥔 뒤 남은 취소 요청을 지우므로(ExperimentJob.java:120, M3로 물림) 새 실행을 멈추지 않음. 문제 없음.
3. **디스크 고갈(장애표 #10) 중 시작**: 잠금 파일·상태 파일 쓰기 실패 → 정지 요청이 있으면 종료 코드 4와 이유, 없으면 1로 시작하지 않음. 실행 중 쓰기 실패는 경고 한 번 뒤 계속(정지 확인은 파일 쓰기와 무관하게 계속). 문제 없음.
4. **진행 보고가 Run을 멈추거나 늦추는 경우**: 진행 보고는 실행기 한 잠금 안에서 `ExperimentJob.onProgress`(동기화, 1초에 한 번 파일 쓰기)를 부른다. 잠금 순서는 Progress → ExperimentJob 한 방향이고 심장박동은 ExperimentJob 잠금만 잡아 교착 없음 `[추론]`(코드 읽기). 받는 쪽 예외는 보고만 끔(M6로 물림).

권고:
- **Q1 (시험)**: `병렬_상한_1이면_한_번에_Run_하나만_돈다`는 실행기의 `Math.min(parallelism, cap)`을 지워도 통과한다(M4 살아남음). Run 3개짜리 골든이 짧아 스레드 풀 8에서도 겹쳐 돌지 않는 경우가 많아서다 `[추론]`. 운영 호출 지점(조합·녹화·워크포워드)은 미리 `Math.min`을 해서 넘기므로 지금 실제 영향은 없지만, D-089 검증 절의 "상한 1이면 동시에 Run 하나"는 시험으로 고정돼 있지 않다. 래치 등으로 Run을 겹치게 만드는 시험이나 `parallelism` 접근자 단언이 필요하다.
- **R1 (표시)**: 취소가 먼저 걸린 뒤 생긴 정지 요청은 종료 코드·상태 파일에 나타나지 않는다(P1). 끝날 때 `ExperimentGate.stopReason()`을 한 번 더 보고 있으면 4로 올리거나 메시지에 덧붙이면 장애표 #10 감지 단서가 그 회차에도 남는다.
- **R2 (실험실 목록)**: `ExperimentJobs.list()`는 읽기는 500개로 자르지만 디렉터리의 `*.status` 경로는 전부 메모리에 모은다(ExperimentJobs.java:86). 실험 ID마다 파일 하나라 현실적 위험은 낮다 `[추론]`.
- 개발 쪽 남은 권고(architect R5·R6, risk-ops R3)는 그대로 열려 있음.

## 판정
**수정 필요(A14)**: main d6fb6e7과 `docs/spec/autotrade-spec.md`·`docs/qa/pending.md` 충돌(PR CI 미실행 포함). main을 병합해 충돌을 풀고 CI·빌드 통과를 확인한 뒤 재검토. 코드 쪽 실패 항목은 없음(A8·A12 통과). 권고 Q1·R1·R2는 막지 않음.
