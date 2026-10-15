# architect 검토: D-094 실험실 병렬 상한 설정을 실험 실행 명령에 잇기(상한 파일)
- 대상: PR #121 / 커밋 d62e977817f31dae2ec85c0a35354115554518a0 / `git diff origin/main...HEAD` 전체(17파일, +488 −18: 운영 코드 4, 테스트 5, 문서 8)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew --max-workers=2 build` → BUILD SUCCESSFUL, 종료 코드 0(136 작업. 테스트 작업 일부가 UP-TO-DATE라 아래로 다시 돌림)
  - `./gradlew --max-workers=2 :autotrade-sim:cleanTest :autotrade-sim:test :autotrade-app-lab:cleanTest :autotrade-app-lab:test :autotrade-app-experiment:cleanTest :autotrade-app-experiment:test :autotrade-architecture-test:cleanTest :autotrade-architecture-test:test` → BUILD SUCCESSFUL. JUnit XML 집계: autotrade-sim 250/실패 0/건너뜀 0, autotrade-app-lab 191/0/0, autotrade-app-experiment 39/0/0, autotrade-architecture-test 43/0/0. 새 시험 `ExperimentLimitsTest`(7)·`ExperimentLimitsPublisherTest`(2) 모두 실행됨
  - `scripts/ci/protected-paths.txt`와 변경 파일 목록 대조 → 보호 경로 변경 없음

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 종료 코드 0, 변경 모듈 테스트 캐시 없이 재실행 실패 0 |
| B2 | 통과 | `ExperimentLimits`는 순수 모듈 `autotrade-sim`의 `com.autotrade.sim.run`(기존 `ExperimentJobFiles`와 같은 패키지, JDK만 씀) → `verifyPureDependencies`·`ModuleOwnershipTest` 통과. 실행 명령은 `com.autotrade.config.ConfigLoader`·`RuntimeConfig*`를 새로 참조하지 않음(ExperimentMain.java 전체, ArchitectureTest.java:273 `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG` 통과). Spring 연결은 app-lab(LabWebConfiguration.java:80)에만. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 프로세스 간 새 파일 형식(experiment-limits-v1)과 설정 키 의미 변경을 D-094, config-keys.md `lab.max-parallel-runs` 행, ADR-004 이연 표 행, D-089 위험 줄, 기획서 SIM-14·v14.37, 런북에 반영. 같은 폴더의 상태 파일 형식(D-089)과 같은 선례(결정 문서 + ADR 행)로 다룸. 설정 화면 응답의 적용 시점 문자열만 바뀌고 REST 모양은 그대로(SettingsController.java:295) |
| B4 | 통과 | 새 코드의 시각은 주입 `Clock`만 씀(ExperimentLimitsPublisher.java:43, ExperimentMain은 인자 `clock`). `updatedAt`은 표시용이고 결과에 들어가지 않음. 병렬 수는 결과를 바꾸지 않는다는 기존 골든·결정론 시험(autotrade-sim 250건) 재실행 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험: 형식·범위·크기·폴더 거부(ExperimentLimitsTest 7건), 쓰기·실패 시 false(ExperimentLimitsPublisherTest 2건), 화면 저장 시 파일 갱신(SettingsControllerTest#즉시_반영_키를_바꾸면_파일에_먼저_쓰고_메모리를_바꾼다, 같은 클래스의 다른 시험은 2를 쓰지 않아 기동 때 쓴 4와 구별됨), 옵션 우선·파일·기본값·깨진 파일 종료 코드 1·정지 요청 4(ExperimentJobTest 2건). 지우거나 끈 시험 없음(SettingsDiskGuardTest는 생성자 인자 추가만). 단, 아래 F1 경로에 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·보호 테스트·보호 경로 변경 없음(변경 파일 17개 모두 protected-paths.txt 밖). 실험 실행 명령의 정지 확인 순서는 바꾸지 않고 새 오류 경로도 `stoppedOr(gate, 1)`로 4를 먼저 봄(ExperimentMain.java:112, ExperimentJobTest#상한_파일이_깨졌으면_기본값으로_넘지_않고_입력_오류다) |
| B8 | 통과 | 상한 파일은 형식 줄·정수·시각 세 줄뿐(ExperimentLimits.java encode, ExperimentLimitsPublisherTest가 3줄 확인). 거부 메시지에 파일 내용을 싣지 않음(ExperimentLimitsTest#형식이_틀리면_내용을_싣지_않고_거부한다). 읽기 실패는 예외 클래스 이름만 출력(ExperimentMain.java maxParallelRuns). 로그는 설정 폴더 아래 경로와 정수만 |

## 점검표 밖 발견

- **F1 (수정 필요) 깨진 `runtime-config.json`으로 기동한 실험실이 사용자가 낮춘 상한을 기본 4로 덮어쓴다.**
  `ConfigLoader`는 `runtime-config.json`이 깨지면 내장 default로 기동한다(ConfigLoader.java:66~70·134, `ConfigSource.DEFAULT_FILE_INVALID`). 실험실 빈은 출처를 보지 않고 기동 때 무조건 `publisher.publish(holder.current())`를 부른다(LabWebConfiguration.java:83). 그래서 사용자가 1로 낮춰 둔 뒤 설정 파일이 손상되면(손 편집 오타 포함) 실험실 기동 한 번으로 상한 파일이 4로 바뀌고, 실행 명령은 "실험실 설정 lab.max-parallel-runs, <시각> 저장"이라는 출처로 4를 쓴다. D-094가 결정 3과 대안 ⑤에서 막으려는 "사용자가 자원 보호로 낮춘 값을 조용히 넘기"가 상한 파일 손상 대신 설정 파일 손상으로 그대로 일어나며, 출처 표시도 사실과 다르다 `[추론: 코드 경로 대조, 재현 시험은 돌리지 않음]`.
  고칠 방향: 기동 때 `LoadedConfig.source()`가 `DEFAULT_FILE_INVALID`면 상한 파일을 쓰지 않고(지난 파일 유지) 경고 로그를 남긴다. `DEFAULT_NO_FILE`(파일 없음)과 `RUNTIME_FILE`은 지금처럼 쓴다. 시험 1건(깨진 runtime-config로 기동해도 기존 상한 파일 값 유지)과 D-094 결정 2·위험 줄 보강.

- **F2 (수정 필요, 낮음) 겹친 저장에서 오래된 값이 마지막에 쓰일 수 있고, D-094 결정 2의 설명과 코드가 다르다.**
  SettingsController.java:132는 `limits.publish(holder.current())`로, `current()`를 publisher 잠금(ExperimentLimitsPublisher.java:33 `synchronized`) **밖에서** 먼저 평가한다. 저장 A(값 2)가 `current()`=2를 읽은 뒤, 저장 B(값 3)가 갱신·발행을 끝내고, 그다음 A가 잠금을 얻어 2를 쓰면 설정은 3인데 상한 파일은 2로 남는다(다음 저장이나 재기동까지). D-094 결정 2는 "쓰는 순간의 `RuntimeConfigHolder.current()`를 쓴다(잠금 하나)"라고 적었지만 코드는 그렇지 않다. 로컬 단일 사용자 화면이라 빈도는 낮다 `[추론]`.
  고칠 방향: publisher가 `Supplier<RuntimeConfig>`(또는 holder)를 받아 `synchronized` 안에서 읽게 하거나, 설명을 실제 동작에 맞게 고친다. 앞쪽을 권한다.

- (참고, 비차단) `autotrade-app-experiment/build.gradle.kts:42` 주석이 여전히 "-PmaxParallelRuns=N(1~64, 기본 4)"다. 이 PR이 바꾸지 않은 파일이지만 이제 기본은 "상한 파일, 없으면 4"이므로 같이 고치면 좋다. 옵션은 속성이 있을 때만 넘겨(같은 파일 64행 등 `orNull?.let`) 상한 파일 경로가 실제로 살아 있음은 확인했다.
- (참고, 비차단) Windows에서 실험실이 바꿔 넣는 순간 실행 명령이 파일을 열면 `AccessDeniedException` 등으로 읽기 실패 → 종료 코드 1이 될 수 있다(ExperimentLimits.java:99·117). 안전 쪽 실패이고 다시 실행하면 되지만, D-089 위험 줄의 같은 현상처럼 D-094 위험과 한계에 한 줄 적어 두면 좋다 `[추론, Windows에서 확인 못 함]`.
- (확인함) 상한 파일 읽기는 링크·폴더 거부, 1 KiB 상한, 모르는·중복·빠진 키 거부, 범위 1~64를 레코드 생성자에서 강제한다. 옵션이 있으면 파일을 아예 읽지 않아 손상된 파일이 있어도 `-PmaxParallelRuns`로 우회된다(ExperimentJobTest). 대안 비교(①~⑤)와 되돌릴 조건이 D-094에 있다.

## 판정
수정 필요(F1, F2)
