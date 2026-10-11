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
