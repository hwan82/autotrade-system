# architect 검토: 실험 실행 명령의 장 시간 보호와 정지 요청 파일 (D-063, Q-049)
- 대상: PR #52(호출자 지정 번호) / 브랜치 `claude/project-thread-5nka5j` 커밋 426862114a10831a816e0fe0103e35fd9055a238 / `git diff origin/main...HEAD`(커밋 1개, 29파일 +752 −115)
- 점검표: B
- 직접 실행한 것:
  - 저장소 작업 트리에서 `./gradlew build --no-daemon` → BUILD SUCCESSFUL(EXIT 0, 130 tasks 중 19 실행, 나머지 캐시)
  - 캐시를 피하려고 같은 작업 트리에서 `--rerun-tasks`, `clean build`를 돌렸으나 둘 다 실패했다. 원인은 이 PR이 아니라 **같은 작업 트리에서 다른 Gradle 빌드가 동시에 돌고 있었던 것**이다(`ps`로 같은 경로의 gradlew·sim 테스트 워커 확인. 실패 내용: 이미 컴파일된 `com.autotrade.marketdata.calendar` 패키지를 못 찾음, 테스트 결과 XML 쓰기 실패 — 둘 다 출력 폴더 경쟁의 전형). 그래서 아래로 판정했다
  - 격리 복제본(스크래치 폴더에 `git clone` 뒤 4268621 체크아웃, 다른 빌드 없음)에서 `./gradlew clean build --no-daemon` → **BUILD SUCCESSFUL**(EXIT 0, 152 tasks, 130 실행). 테스트 결과 XML 합계 tests 880, failures 0, errors 0, skipped 0. `verifyPureDependencies`(순수 모듈 전부), `verifyProcessIsolation`(engine·gateway·watchdog·collector·experiment) 실행·통과
  - 새·관련 테스트: `ExperimentGateTest` 9, `RunGateTest` 3, `RunGuardTest` 8(수정 없음), `ArchitectureTest` 17, `ModuleOwnershipTest` 13, 모두 통과
  - CI 경계 스크립트: `scripts/ci/check-collector-keys.sh` EXIT 0, `scripts/ci/check-python-collectors.sh` EXIT 0
  - 실제 명령 동작(격리 복제본, `AUTOTRADE_CONFIG_DIR`=임시 폴더): `<설정 폴더>/experiment-state/stop-request`를 만들고 `:autotrade-app-experiment:runStrategyStudy -PallowDuringSession` → `[study] 멈췄습니다(결과 파일을 쓰지 않음): 감시 프로세스가 자원 부족으로 정지를 요청했습니다(...)`, 작업 실패(종료 코드 비 0), 작업 트리 변경 없음, 정지 요청 파일 남음. `:autotrade-sim:runCombinationExperiment --dry-run`이 `:autotrade-app-experiment:runCombinationExperiment`를 먼저 부르는 것 확인(별칭 동작)
  - jshell로 `SessionProtection.check` 경계 확인: `2026-12-30T15:00:00Z`(서울 12-31 00:00) → `Unknown[market=KRX, date=2027-01-01]` (아래 N1)
  - PR 본문·커밋 메시지·D-063 문서 설명은 판정 근거로 쓰지 않았다. 아래 근거는 코드와 직접 실행 결과다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 복제본 `clean build` 성공(위). 작업 트리의 실패 2건은 동시 빌드 경쟁 때문이며 격리 환경에서 재현되지 않음 |
| B2 | 통과 | 새 모듈 `autotrade-app-experiment`는 `autotrade.process-conventions`, 의존은 domain·marketdata·sim·config뿐(autotrade-app-experiment/build.gradle.kts:110-115). `forbiddenProjects`에 lab·ui·control·broker-*·다른 app-*(:102-108), engine·gateway·watchdog 쪽에 `:autotrade-app-experiment` 추가(각 build.gradle.kts:13·12·12). ArchUnit: `PLAIN_PROCESSES`에 `com.autotrade.app.experiment..`(ArchitectureTest.java:74), `PROCESSES_ARE_INDEPENDENT`가 `com.autotrade.app.(*)..` 슬라이스로 자동 포함, `ModuleOwnershipTest.OWNERS`에 행 추가(:124). 시스템 시계는 `ExperimentMain.main`(ExperimentMain.java:30, `CLOCK_SOURCE`가 허용하는 `com.autotrade.app.*Main`)에서만 만들고, sim은 `RunGate` 인터페이스만 안다(벽시계 없음). `SessionProtection`은 순수 모듈 marketdata에 있고 `Instant`를 인자로 받는다(SessionProtection.java:54). ArchUnit 규칙은 범위 추가뿐(약화 없음) |
| B3 | 통과 | 새 계약: `RunGate`·`ExperimentStoppedException`(sim), 종료 코드 4, 정지 요청 파일 경로, Gradle 속성 `-PallowDuringSession`, 작업 이름 이동. ADR-001 프로세스 표에 새 모듈 행(docs/adr/ADR-001-module-structure.md:30), ADR-004 후속 표 갱신, D-063(결정·대안 5개·되돌릴 조건·위험), 장애 설계 5.2 0번·장애표 #10, 기획서 v14.15(RSK-07·SIM-14), 절차 문서(combination-experiments.md, experiments/README.md) 갱신. 설정 키(runtime-config) 추가 없음. 이전 작업 이름은 별칭으로 남겨 호환(autotrade-sim/build.gradle.kts:20-29) |
| B4 | 통과 | 문(gate)은 결과에 영향이 없다: 멈추지 않으면 상세 보고서 바이트 동일(`RunGateTest#멈추지_않는_문은_결과를_바꾸지_않는다`, 병렬 2), 멈추면 결과 없음. 벽시계는 `ExperimentGate`가 주입 `Clock`으로만 읽음(ExperimentGate.java:55). sim 골든·결정론 테스트 변경 없이 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음 |
| B6 | 통과 | 새 동작마다 테스트: 시작 시 멈춤·Run 도중 멈춤·남은 Run 미시작(`RunGateTest`), 정지 요청 파일이 해제 옵션으로 안 풀리고 지워지지 않음·국내/미국 경계·캘린더 범위 밖·조합/합성 실험 종료 코드 4와 결과 파일 미작성·모르는 명령(`ExperimentGateTest`). 수집기 `RunGuardTest`는 손대지 않고 통과(장 시간 판정 이동의 회귀 확인). diff에 테스트 삭제·`@Disabled` 없음. `CombinationMainTest`는 `run(String[])`(RunGate.NONE)로 입력 오류만 시험해 그대로 통과 |
| B7 | risk-ops 검토 필요 | 장애 대응 규칙(`broker-and-fault-design.md` 5.2 정지 순서 0번, 6장 장애표 #10)과 수집기 실전 PC 보호 코드(`RunGuard` 장 시간 판정을 `SessionProtection`으로 이동)를 바꾸고 보호 테스트 `ArchitectureTest`를 고친다(범위 추가, 약화 아님). 지침 10장 머지 권한의 "장애 대응" 항목에 해당하므로 risk-ops A 점검표 검토가 필요하고 머지는 사용자가 한다. 이 검토 시점에 `docs/reviews/pr-52/risk-ops.md`는 저장소(브랜치·작업 트리)에 없다 |
| B8 | 통과 | 새 출력은 정지 요청 파일 경로·파일 시각·시장·현지 시간 구간뿐(ExperimentGate.java:48-50, :57-63). diff `+` 줄에 키·토큰·계좌 패턴 없음, 커밋 메시지 확인 |

## 점검표 밖 발견

### N1 (권고, 문서 사실 오류): 캘린더 끝으로 막히기 시작하는 시점은 2027-01-01이 아니라 서울 2026-12-31 00:00이다
- [사실] `SessionProtection.check`는 현지 날짜의 전날·당일·다음날 세션을 본다(SessionProtection.java:56). KRX 번들 캘린더 `coverageTo`는 2026-12-31(krx.json:5)이라, 서울 12-31에 다음날 2027-01-01 조회가 `CalendarCoverageException` → `Unknown`이 된다. jshell 확인: `2026-12-30T15:00:00Z` → `Unknown[market=KRX, date=2027-01-01]`.
- D-063 "위험과 한계"(D-063-experiment-command-stop-gate.md:36)의 "2027-01-01부터"를 "서울 2026-12-31 00:00부터(다음날 조회가 수록 범위 밖)"로 고친다. 수집기도 같은 판정이므로 함께 적으면 좋다. 지금(2026-10-11)부터 약 11주 뒤라 캘린더 갱신 후속 과제의 기한으로 적어 두기를 권한다.

### N2 (권고): "정지 확인 없이 돌릴 길을 없앤다"는 `main` 삭제로만 보장되고 빌드 규칙은 없다
- [사실] 문 없는 겹치기가 public으로 남아 있다: `CombinationExperiment.run(...)`(CombinationExperiment.java:101), `runAndWrite(...)`(:230), `SimulationExperimentRunner` 10인자 생성자(:117, `RunGate.NONE`), `StrategyStudy` 8인자 생성자. 지금 운영 코드에서 이것을 부르는 `com.autotrade.app..` 클래스는 없다(실험실 `ExperimentFolders`는 결과 묶음만 읽음).
- [추론] 나중에 다른 `*Main`이나 실험실이 문 없는 겹치기로 실험을 돌려도 빌드가 막지 않는다. ArchUnit 규칙("`com.autotrade.app..`은 `RunGate.NONE`과 문 없는 겹치기를 부르지 않는다") 하나로 D-063 결정 1의 문장을 빌드로 강제할 수 있다. 이 PR의 차단 항목은 아니다.

### N3 (권고): 병렬 정지 경로 테스트가 없다
- [사실] Run 도중 멈춤 테스트는 병렬 1뿐이다(`RunGateTest#Run_도중_멈추라고_하면_...`, runner(1, …)). 병렬 > 1에서 한 Run이 멈춘 뒤 `stopped` 깃발로 다른 진행 중 Run이 다음 거래일 경계에서 끝나고, 대기 Run이 `cancel(false)`(SimulationExperimentRunner.java:178)로 시작하지 않으며, `ExecutorService.close()`가 오래 기다리지 않는 경로는 코드 읽기로만 맞다고 본다.
- 병렬 2 이상, 둘째 Run이 진행 중일 때 멈추는 테스트를 더하기를 권한다(예외 종류 `ExperimentStoppedException`, 실행기 자체 오류로 바뀌지 않음 확인).

### N4 (권고): 실험 명령의 설정 로더·주문 포트 접근을 ArchUnit으로 막지 않는다
- [사실] 새 모듈은 `autotrade-config`에 의존하고(build.gradle.kts:114) `ConfigPaths`만 쓴다. 수집기에는 `COLLECTOR_READS_ONLY_ITS_SECRETS`(ArchitectureTest.java:226-236)가 `ConfigLoader`·`LoadedConfig`·`RuntimeConfig*`·`ProcessStartup`·`domain.order`를 막지만, `com.autotrade.app.experiment..`에는 같은 규칙이 없다. Gradle 격리는 broker-*를 막지만 config의 `secrets.json` 로더는 막지 않는다.
- [추론] 실험 명령이 `secrets.json`을 읽을 이유가 없으므로 같은 규칙을 실험 명령 패키지에도 걸면 지침 4장 4번(비밀값) 경계가 빌드로 유지된다. 이 PR의 차단 항목은 아니다.

### 확인한 것(문제 없음)
- 실행기 예외 흐름: `runOne`이 `ExperimentStoppedException`을 Run 실패(FAILED)로 삼키지 않고 다시 던진다(SimulationExperimentRunner.java:364, `RuntimeException` 잡기보다 앞). 실행기 자체 오류(`ExecutionException`의 다른 원인)는 여전히 `IllegalStateException`. 결과 파일은 `runAndWrite`·`StudyMain.study`가 실험이 끝난 뒤에만 쓰므로 멈추면 파일이 생기지 않는다(CombinationExperiment.java:250-255, StudyMain.java:108-111).
- `ExperimentGate`는 상태가 없어 여러 Run 스레드에서 불러도 안전하고, 파일 존재를 판단할 수 없으면 멈춘다(`!Files.notExists`, ExperimentGate.java:47). 정지 요청 파일은 `--allow-during-session`보다 먼저 본다.
- 수집기 `RunGuard`의 판정·문구는 `SessionProtection`으로 옮긴 뒤에도 같다(구간 계산 코드가 그대로 옮겨졌고 `RunGuardTest` 무수정 통과).
- D-061 결정 문서의 예전 작업 이름(`:autotrade-sim:runCombinationExperiment`)은 결정 기록이라 고치지 않았고 별칭이 동작하므로 문제 없음.

## 판정
수정 필요(B7: 장애 대응 규칙 변경이므로 risk-ops 검토 원문 `docs/reviews/pr-52/risk-ops.md`가 필요. architect가 요구하는 코드 수정은 없고 N1~N4는 권고다(N1은 문서 사실 오류라 고치기를 권함). risk-ops 판정이 "승인"이면 architect 쪽 막는 항목은 없다. 지침 10장·11장에 따라 독립 QA 뒤 사용자가 머지한다)

## 재검토 2026-10-11
- 대상: 커밋 5116758e4cd5e8bb536a348c5b38a2be55086591(`git diff 4268621 5116758`: 7파일 +206 −2)과 PR 전체 현재 상태(`git diff origin/main...5116758`)
- 점검표: B
- 직접 실행한 것:
  - 이번에도 같은 작업 트리에서 다른 빌드가 돌 수 있어서, 격리 복제본(스크래치 폴더)을 5116758로 체크아웃해 `./gradlew clean build --no-daemon`을 돌렸다 → **BUILD FAILED**(EXIT 1). 실패는 `:autotrade-sim:test`의 `RunGateTest#병렬로_돌던_Run도_다음_거래일에_끝나고_결과_없이_멈춘다` 1건이다
  - `./gradlew build --continue --no-daemon` → 실패는 같은 1건뿐(테스트 883, 실패 1, 오류 0). `ArchitectureTest` 18, `ModuleOwnershipTest` 13, `ExperimentGateTest` 10은 모두 통과
  - 같은 테스트 클래스를 `--rerun`으로 6번 더 돌렸다 → 통과 4번, 실패 2번(run4, run6). clean build까지 합치면 **7번 중 3번 실패**. 실패 메시지: `Expecting message to be: "정지 요청 파일" but was: "다른 Run이 정지 요청을 받아 멈춥니다"`(at `SimulationExperimentRunner.checkGate`:295 ← `runOne`:331)
  - `docs/reviews/pr-52/architect.md`의 커밋 본(5116758)과 작업 트리 본이 첫 검토 원문과 바이트 같음을 확인했다(`diff` 일치). 메인이 고친 곳 없음
  - `docs/reviews/pr-52/risk-ops.md`를 읽었다. 4268621 대상 판정은 수정 필요(F1)이고, 5116758 대상 risk-ops 재검토 원문은 이 시점에 아직 없다

### 앞 지적 처리
| 지적 | 처리 | 근거 |
|---|---|---|
| N1 캘린더 끝 시점 | 반영됨 | D-063 "위험과 한계"가 "서울 2026-12-31 00:00부터"로 바뀌었다(D-063-experiment-command-stop-gate.md:37). 코드 판정(jshell 확인)과 맞다 |
| N2 게이트 없는 입구 | 반영됨 | `APPS_RUN_EXPERIMENTS_THROUGH_GATE`(ArchitectureTest.java:247-263): `com.autotrade.app..`이 `RunGate` 인자 없는 `SimulationExperimentRunner`·`StrategyStudy` 생성자나 `CombinationExperiment.run`·`runAndWrite`를 부르면 실패한다. 위반 고정 예제 `RunsWithoutGate`에서 위반 3건을 잡고, 운영 코드에서는 통과한다. 게이트 없는 오버로드를 지우지 않은 이유와 되돌릴 조건은 D-063 대안 F에 적혔다 |
| N3 병렬 정지 테스트 | **새 테스트가 불안정함(아래 R1)** | `RunGateTest`:83-94 |
| N4 설정 로더·주문 포트 | 반영됨 | `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`(ArchitectureTest.java:266-275). 수집기 규칙과 같은 대상이고, 위반 예제에서 `ConfigLoader`·`LoadedConfig`·`OrderGateway`를 잡는다 |
| risk-ops F1 판단 불가 | 반영됨 | `ExperimentGateTest#정지_요청이_있는지_판단할_수_없으면_해제_옵션과_상관없이_멈춘다`: 부모 경로를 일반 파일로 만들어 `exists`·`notExists`가 둘 다 false인 상태에서 해제 옵션을 켜도 멈춘다. 통과 |

| # | 결과 | 근거 |
|---|---|---|
| B1 | 실패 | 격리 복제본 clean build 실패. `RunGateTest`의 새 병렬 테스트가 스레드 순서에 따라 7번 중 3번 실패한다(위). CI에서도 같은 확률로 깨진다 `[추론]` → R1 |
| B2 | 통과 | 이번 커밋은 운영 코드를 바꾸지 않았다. ArchUnit 규칙 2개가 추가됐고 약화된 것은 없다. 고정 예제는 `autotrade-architecture-test`의 테스트 소스(`com.autotrade.app.experiment.archfixture`)에 있고, 수집기·감시 예제와 같은 방식이다. `ModuleOwnershipTest` 통과 |
| B3 | 통과 | D-063 결정 6과 대안 F에 새 규칙이 적혔다. 계약 변경은 없다 |
| B4 | 통과 | 앞 근거와 같다. `RunGateTest#멈추지_않는_문은_결과를_바꾸지_않는다`와 골든 테스트가 통과한다 |
| B5 | 해당 없음 | 금액 변경 없음 |
| B6 | 실패 | 테스트를 지우거나 건너뛴 곳은 없다. 다만 새 병렬 정지 테스트가 결정적이지 않고(R1), 테스트가 기대하는 동작(처음 멈춘 이유가 올라옴)을 운영 코드가 보장하지 않는다 |
| B7 | risk-ops 검토 필요 | 장애 대응 규칙 변경(5.2 0번, 장애표 #10), 보호 테스트 `ArchitectureTest` 추가 변경. `risk-ops.md`의 4268621 판정은 수정 필요(F1)이고, F1은 5116758에 반영됐지만 risk-ops 재검토 원문은 아직 없다. 머지는 지침 10장에 따라 사용자가 한다 |
| B8 | 통과 | 이번 커밋에 새 출력 없음. 고정 예제는 실행하지 않는 코드이고 비밀값이 없다 |

### R1 (수정 필요, B1·B6): 병렬 정지 때 올라오는 이유가 스레드 순서에 따라 "다른 Run이 정지 요청을 받아 멈춥니다"로 바뀐다
- [사실] `runDetailed`는 `futures`를 제출 순서대로 `get()`하고, 처음 만난 `ExecutionException`의 원인을 그대로 던진다(SimulationExperimentRunner.java:172-181). 병렬 2에서 Run B가 문(gate)에서 진짜 이유("정지 요청 파일")를 받아 `stopped`를 켜고, 그 뒤 Run A가 거래일 경계에서 깃발만 보고 `checkGate`:295의 일반 문구로 끝나면, 앞 순서인 A의 예외가 올라간다. 테스트는 `hasMessage("정지 요청 파일")`(RunGateTest.java:90)을 기대하므로 이 순서일 때 실패한다(직접 재현 3/7).
- [추론] 운영 영향도 있다. 실행 명령은 이 메시지를 `[combo] 멈췄습니다…`·`[study] 멈췄습니다…`로 출력하는데(CombinationMain.java:85, StudyMain.java:79), 병렬 실험에서는 운영자가 실제 이유(장 시간 보호 구간인지, 감시 정지 요청인지, 캘린더 범위 밖인지)와 해제 방법 안내 대신 "다른 Run이 정지 요청을 받아 멈춥니다"만 보게 된다. 장애표 #10은 이 거부 메시지를 감지 수단으로 쓴다.
- 요구 사항: 처음 멈춘 이유를 실행기에 남겨(예: `AtomicReference<ExperimentStoppedException>`에 첫 예외를 compare-and-set하고, 깃발만 보고 끝난 Run과 `ExecutionException` 처리에서 그 예외를 다시 던짐) 어떤 스레드 순서에서도 진짜 이유가 올라오게 한다. 그러면 테스트의 `hasMessage`가 결정적으로 맞는다. 테스트는 고친 뒤 반복 실행(예: `@RepeatedTest` 또는 여러 번 `--rerun`)으로 안정성을 확인한다. 테스트 쪽 기대만 느슨하게 바꾸는 것(메시지 비교를 빼는 것)은 운영 메시지 문제를 남기므로 받지 않는다.
- 덧붙임(권고): 이 테스트는 "이미 돌던 Run이 다음 거래일에 끝나는" 경로를 항상 지나지는 않는다. Run A가 Run B 시작 전에 하나뿐인 거래일 경계를 지나 정상 완료하는 순서도 가능하다(이때 호출 수 4). 거래일이 셋 이상인 데이터를 쓰거나 문이 Run 시작은 통과시키고 거래일 경계에서만 멈추게 하면 의도한 경로를 확실히 시험할 수 있다.

### 판정
수정 필요(R1: 병렬 정지에서 실제 정지 이유가 스레드 순서에 따라 일반 문구로 바뀌고, 그 때문에 새 테스트가 불안정해 `./gradlew build`가 7번 중 3번 실패한다. 실행기에 첫 정지 이유를 남겨 결정적으로 고칠 것. B7은 risk-ops의 5116758 재검토 원문 판정 "승인"이 필요하다. 앞 지적 N1·N2·N4와 risk-ops F1은 반영 확인)

## 재검토 2 2026-10-11
- 대상: 커밋 8254e03f686758ac094fea4196ef574e9fd388c1(`git diff 5116758 8254e03`: 운영 코드 `SimulationExperimentRunner.java` +12 −10, `RunGateTest.java` 주석, 검토 원문 2개)과 PR 전체 현재 상태(`git diff origin/main...8254e03`)
- 점검표: B
- 직접 실행한 것:
  - 스크래치 폴더에 새로 `git clone`한 뒤 8254e03을 체크아웃했다(다른 빌드와 출력 폴더를 같이 쓰지 않음). `./gradlew clean build --no-daemon` → **BUILD SUCCESSFUL**(EXIT 0, 152 tasks 중 130 실행). 테스트 결과 XML 합계 883건, 실패 0, 오류 0, 건너뜀 0
  - 같은 복제본에서 `./gradlew :autotrade-sim:test --tests com.autotrade.sim.run.RunGateTest --rerun`을 **25번** 반복했다 → 25번 모두 통과, 실패 0. 5116758에서는 7번 중 3번 실패했으므로, 실패율이 그대로라면 25번 연속 통과할 확률은 0.57^25 ≈ 10^-6 수준이다 `[추론]`
  - 작업 트리의 `docs/reviews/pr-52/architect.md`가 8254e03에 들어간 본과 바이트까지 같다(`diff` 일치). 메인이 원문을 고친 곳은 없다
  - `docs/reviews/pr-52/risk-ops.md`를 읽었다. 5116758 대상 재검토 판정은 수정 필요(F2: 이 검토의 R1과 같은 결함)이고, 8254e03 대상 risk-ops 재검토 원문은 이 시점에 아직 없다(동시 검토 중)

### R1 처리
- [사실] 실행기는 이제 처음 받은 정지 예외 자체를 `AtomicReference<ExperimentStoppedException>`에 남긴다(SimulationExperimentRunner.java:163).
  - `checkGate`는 남은 예외가 있으면 문(gate)을 다시 묻지 않고 그 예외를 그대로 던진다(:294-305).
  - 문에서 직접 멈춤을 받으면 `compareAndSet(null, e)`로 남긴 뒤 남은 것을 던진다.
  - `ExecutionException` 처리도 같은 방식이다(:178-182).
  - 일반 문구 "다른 Run이 정지 요청을 받아 멈춥니다"는 코드에서 없어졌다.
- 결과: 어느 Run의 Future를 먼저 받든, 올라오는 예외는 문이 처음 돌려준 실제 이유다. 장 시간 보호, 정지 요청 파일, 캘린더 범위 밖 문구와 해제 안내가 그대로 실행 명령 출력까지 간다. 두 스레드가 거의 동시에 서로 다른 이유를 받아도 compare-and-set에서 이긴 하나로 정해지고, 어느 쪽이든 실제 이유다.
- 같은 예외 객체를 여러 스레드가 던지지만, 스택은 만든 때 고정되고 `addSuppressed`를 쓰는 곳이 없어 문제가 없다 `[추론]`.
- 실행기 자체 오류(`ExperimentStoppedException`이 아닌 원인)는 이전과 같이 `IllegalStateException`이다.
- 멈추지 않는 경로는 바뀌지 않았다. `RunGateTest#멈추지_않는_문은_결과를_바꾸지_않는다`와 골든 테스트가 통과한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 격리 복제본 clean build 성공, `RunGateTest` 25번 반복 전부 통과(위) |
| B2 | 통과 | 이번 커밋은 sim 내부 동기화 타입만 바꿨다(`AtomicBoolean` → `AtomicReference`, JDK). 의존·ArchUnit 변경 없음. 앞 재검토의 규칙 2개(`APPS_RUN_EXPERIMENTS_THROUGH_GATE`, `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`)가 유지되고 통과한다 |
| B3 | 통과 | 계약 변경 없음. 실행기의 동작 계약(D-063 결정 2: 멈추면 결과 없이 `ExperimentStoppedException`)과 맞다 |
| B4 | 통과 | 결과 경로가 바뀌지 않았다. 결정론·골든 테스트 통과 |
| B5 | 해당 없음 | 금액 변경 없음 |
| B6 | 통과 | 병렬 정지 테스트가 실제 이유 문구(`hasMessage("정지 요청 파일")`)를 그대로 검사하고(느슨하게 바꾸지 않음), 이제 결정적으로 통과한다. 테스트 삭제·건너뜀 없음 |
| B7 | risk-ops 검토 필요 | 장애 대응 규칙 변경(5.2 0번, 장애표 #10)과 보호 테스트 `ArchitectureTest` 추가 변경이 있는 PR이다. risk-ops F2(= R1)는 8254e03에서 코드로 해소됐다고 본다. 그러나 8254e03 대상 risk-ops 재검토 원문은 아직 없다. 그 판정이 "승인"이어야 머지 조건이 갖춰진다. 머지는 지침 10장·11장에 따라 독립 QA 뒤 사용자가 한다 |
| B8 | 통과 | 새 출력 없음. 정지 메시지는 문이 만든 원래 문구(설정 폴더 경로·파일 시각·시장·현지 구간)뿐이다 |

### 남은 권고(막지 않음)
- 앞 재검토의 덧붙임이 그대로 남아 있다. `RunGateTest#병렬로_돌던_Run도_다음_거래일에_끝나고_결과_없이_멈춘다`는 데이터가 2거래일이라 "이미 돌던 Run이 다음 거래일 경계에서 끝나는" 경로를 항상 지나지는 않는다(Run A가 B 시작 전에 정상 완료하는 순서가 가능). 3거래일 이상 데이터를 쓰거나, Run 시작 확인은 통과시키고 거래일 경계에서만 멈추는 문을 쓰면 의도한 경로를 확실히 시험할 수 있다. 후속 PR로 충분하다.

### 판정
승인(architect 범위. B 전 항목이 통과 또는 해당 없음이고 확인 못 한 항목이 없다. R1은 해소됐고 앞 지적 N1·N2·N4와 risk-ops F1은 반영 확인. B7은 risk-ops의 8254e03 재검토 원문 판정이 "승인"이어야 머지 조건이 갖춰지며, 장애 대응 변경이므로 지침 10장·11장에 따라 독립 QA 뒤 사용자가 머지한다)
