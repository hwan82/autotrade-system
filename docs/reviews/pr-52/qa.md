# QA 검토: 실험 실행 명령의 장 시간 보호와 정지 요청 파일 (D-063, Q-049)
- 대상: PR #52 / 커밋 6fecd47884ae54cc76cd2b936ffeb0960dbf80b7 / main(ff6d74f) 대비 전체 diff(32개 파일 +1119 −115, 그중 검토 원문 2개). merge-base = main 헤드(ff6d74f)라 충돌 없음
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 6fecd47): **BUILD SUCCESSFUL**(13회째. 1~12회는 Maven Central 429로 의존성 받기 실패, 빌드 판정 아님). 테스트 결과 XML 집계 883개, 실패 0, 오류 0, 건너뜀 0. 이 PR 관련: `RunGateTest` 4, `ExperimentGateTest` 10, `ArchitectureTest` 18, `RunGuardTest` 8, `ModuleOwnershipTest` 13 모두 통과.
  - QA 탐침 테스트(커밋하지 않음, 실행 뒤 지움): `SimulationExperimentRunner`에 처음부터 멈추라고 답하는 `RunGate`와 호출을 기록하는 `SelectionSource`(`SelectionCombinationRunnerTest.CountingSource`)를 넣고 선정 있는 조합 1개로 `runDetailed` → `ExperimentStoppedException`은 나지만 그 전에 선정 공급자가 **모든 거래일**(`[pick-samsung@2026-03-03, pick-samsung@2026-03-04]`)에 불림. 아래 F1의 근거.
  - `./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=EXP-QA-NONE -PallowDuringSession=false -i`: 실제로 띄운 명령줄이 `... ExperimentMain combination --allow-during-session ...`(값 `false`인데 장 시간 보호가 풀림). 같은 명령줄의 클래스패스는 app-experiment·sim·selection·marketdata·config·strategy·risk·domain·jackson뿐이고 broker·app-*·Spring 없음. 없는 실험 ID라 종료 코드 1로 끝나 결과 파일은 생기지 않음(`git status` 깨끗). 아래 F2의 근거.
  - 보호 테스트 변경 확인: `ArchitectureTest`는 규칙 2개(`APPS_RUN_EXPERIMENTS_THROUGH_GATE`, `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`)·테스트 1개 추가와 `PLAIN_PROCESSES`에 `com.autotrade.app.experiment..` 추가만 있고 기존 규칙 삭제·완화 없음. `RunGuardTest` 변경 없음. `autotrade-risk`(RealTradingGuard·GuardedOrderGateway) 파일 변경 없음.
  - 게이트 없는 실행 입구 호출처 검색: `grep -rn "SimulationExperimentRunner|StrategyStudy(|CombinationExperiment.(run|runAndWrite)"` 운영 코드 → sim 안(`CombinationExperiment`, `StrategyStudy`, `CombinationMain`, `StudyMain`)뿐. 실험실(app-lab)은 실험을 돌리지 않음(D-062와 일치).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·설정 코드 변경 없음. 실험 명령은 `ConfigLoader`·`RuntimeConfig*`·`LoadedConfig`·`ProcessStartup`을 쓰지 못함(ArchUnit `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`, 위반 예제 `RunsWithoutGate` 단언, 통과). `ConfigPaths`는 경로 계산만(`ConfigPaths.java` `defaultLocation`) |
| A2 | 통과 | 주문 코드 변경 없음. Guard 팩토리·출구 ArchUnit 규칙 그대로(추가만) |
| A3 | 해당 없음 | 주문·증권사 라우팅 변경 없음. 실험 명령은 `com.autotrade.broker..`·`com.autotrade.domain.order..` 의존 금지(ArchUnit) + `forbiddenProjects`에 broker 4개(`autotrade-app-experiment/build.gradle.kts:14-20`) |
| A4 | 해당 없음 | 주문 재전송 경로 없음 |
| A5 | 해당 없음 | 실전 주문 생성 경로 변경 없음. 모의 실행기의 `SimOrderKeys` 변경 없음 |
| A6 | 해당 없음 | 블랙리스트 코드 변경 없음. 조합 실험은 기존대로 등록 파일 제외 종목으로 `RiskChain`을 만듦(`CombinationExperiment.java` 변경 없는 부분) |
| A7 | 해당 없음 | 실전 계좌 큐·토큰 버킷 변경 없음 |
| A8 | **실패** | 실전 엔진 PC의 큰 힙 부하원(6g·4g)에 멈춤 수단이 생긴 것은 맞음: 시작 확인(`CombinationMain.java:73`, `StudyMain.java:75`, `CombinationExperiment.java:116`), 실행기 시작(`SimulationExperimentRunner.java:160`), Run 시작 전·거래일 경계(`checkGate`, `:169`·`:333`). 프로세스 분리도 맞음(엔진·게이트웨이·감시 `forbiddenProjects`에 `:autotrade-app-experiment`, `PLAIN_PROCESSES`, 실제 클래스패스 확인). 그러나 **F1**: 힙을 가장 많이 쓰는 준비 단계(스냅샷 읽기 뒤 일봉 → 이벤트 변환, 선정 일정 전체 계산)에 확인 지점이 없어 정지 요청·장 시간 진입에 대한 반응 시간이 정해지지 않았고 측정도 없음. **F2**: 장 시간 보호 해제 스위치가 값과 무관하게 켜짐(`-PallowDuringSession=false`도 해제). 아래 "점검표 밖 발견" 참조 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 추가 줄에 키·계좌번호·토큰 없음(`grep -iE "appkey|appsecret|secret|token|password"` 0건, `secrets.json` 언급·검토 원문 제외). 정지 메시지는 경로와 파일 시각만 담음(`ExperimentGate.java:47-50`) |
| A11 | 통과 | 보호 테스트 삭제·약화 없음(위 확인). 새 동작마다 실패 경우 테스트 있음: 시작 때 멈춤(Run 0개), Run 도중 멈춤은 FAILED가 아니라 실험 전체 정지, 병렬 정지 이유 보존, 정지 요청 파일은 해제 옵션과 무관·지우지 않음, 확인 실패 → 멈춤, 국내·미국 경계, 캘린더 범위 밖, 종료 코드 4·결과 파일 없음, 게이트 우회 ArchUnit 위반 예제 3건 |
| A12 | 통과 | 5.2 0번(`broker-and-fault-design.md:144`)과 장애표 #10(`:169`) 감지·실전 대응·실험실 대응 칸이 함께 바뀜(실험 명령 파일 경로, 종료 코드 4). 장애 테스트 8종의 시나리오·합격 기준은 바뀌지 않음. 감시 쪽 파일 생성이 없다는 사실은 D-063 "위험과 한계"에 적힘 |
| A13 | 통과 | 장 시간 판단은 시장별 캘린더의 현지 시간대(`SessionProtection.java` `now.atZone(cal.zone())`, 전날·당일·다음날 세션)로 국내·미국 모두 봄. 미국 경계 테스트 `미국_장_시간도_멈춘다`(뉴욕 08:30 EDT) 통과 |
| A14 | 통과 | D-063 ①~⑥이 코드와 일치(모듈·진입점, `RunGate` 확인 지점, 파일 경로 `ExperimentGate.STOP_REQUEST`, 구간 60/30분, 종료 코드 4, ArchUnit 규칙명). 결정 로그에 D-063 추가·Q-049 닫힘, 기획서 v14.15 RSK-07·SIM-14, ADR-001 표·ADR-004 후속표, 실험 절차 문서 갱신. main과 충돌 없음. 단, F1을 코드로 고치지 않고 받아들이기로 하면 D-063 "위험과 한계"에 준비 단계 공백을 적어야 함 |
| B1 | 통과 | `./gradlew build` 직접 실행 BUILD SUCCESSFUL(위 기록, 테스트 883개 실패 0) |
| B6 | 통과 | 새 동작에 테스트가 있고(A11), 건너뛰거나 지운 테스트 없음(건너뜀 0). 단 F1·F2에 해당하는 경우(준비 단계 중 정지, 해제 옵션 값 `false`)는 테스트가 없음 |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 8.1 유료 API 호출 경로 | 통과 | 이 PR은 외부 호출을 추가하지 않음. 실험 명령은 로컬 스냅샷·합성 시세만 읽고, broker 모듈과 HTTP 클라이언트가 클래스패스에 없음(실제 명령줄 클래스패스 확인). `SessionProtection`·`ExperimentGate`는 번들 캘린더와 파일 존재 확인만 함. 유료 호출 지점 0건 |
| 8.2 필요성과 대체 수단 | 통과 | 유료 호출 없음 |
| 8.3 사용자 옵션 제어 | 통과 | 유료 호출 없음 |
| 8.4 폭주 방지 | 통과 | 외부 호출 없음. 거래일마다 파일 존재 확인 1번(로컬 디스크, Run 수 × 거래일)은 과금과 무관 |
| 8.5 실주문 비용 | 통과 | 실험 명령은 `com.autotrade.domain.order..`·broker 의존 금지(ArchUnit, `forbiddenProjects`). 이 PR로 실주문이 나갈 경로 없음. 모의 비용 모델(`CostModels.krDefault`) 변경 없음 |

## 점검표 밖 발견 (장애·순서 시나리오)

1. **F1 — 준비 단계에 정지 확인이 없음 (A8 실패 사유)**
   - [사실] `CombinationExperiment.run`은 `gate.check()`(`:116`)를 스냅샷 읽기 **전에** 한 번만 하고, 그 뒤 일봉 스냅샷·재무 스냅샷 적재, 거래일 도출, `DailyBarMarketDataSet.of`(전 종목 × 거래일 × 6개 이벤트 생성)까지 확인 없이 진행한다. 이어 `SimulationExperimentRunner.runDetailed`는 `selectionSchedule(spec, events)`(`:157`)로 **모든 선정 프로필 × 모든 거래일**의 선정을 계산한 **뒤에야** `gate.check()`(`:160`)를 부른다. `SelectionSchedule.compute`의 날짜 반복(`schedule(...)`)에도 확인 지점이 없다. `--verify-determinism`이면 선정 계산기를 새로 만들어(`CombinationExperiment.java:202`) 이 과정을 한 번 더 확인 없이 돈다.
   - [사실, QA 탐침] 처음부터 멈추라고 답하는 문을 넣어도 선정 공급자가 실험 기간의 모든 거래일에 불린 뒤에 멈춘다.
   - [추론] 실제 조합 실험은 국내 전 종목 수 년치 일봉과 선정 프로필 여러 개라, 이 구간이 실행 시간과 힙 사용의 큰 몫이다. 감시가 자원 부족으로 정지 요청 파일을 만들어도(5.2 0번) 실험은 이 구간이 끝날 때까지 6g 힙을 계속 쓴다. 장 시간 보호는 정규장 60분 전부터라 여유가 있지만, 정지 요청은 이미 자원이 부족한 순간에 나오는 것이라 여유가 없다. D-063 "위험과 한계"는 "거래일 경계만큼 늦다"만 적고 이 구간은 적지 않았고, 측정도 없다.
   - 요구: (a) 선정 일정 계산의 날짜(또는 선정 프로필) 반복과 스냅샷 적재 뒤·이벤트 변환 뒤에 문을 묻게 하고(예: `SelectionSchedule.compute`에 `RunGate`를 넘기거나 `runDetailed`에서 `gate.check()`를 `selectionSchedule` 앞으로 옮기고 계산 중에도 확인), 준비 단계 중 정지 테스트를 추가하거나, (b) 고치지 않는다면 실제 규모(전 종목·수 년·선정 N개) 준비 시간을 측정해 D-063 "위험과 한계"와 5.2 0번에 반응 지연 상한으로 적는다. (a)를 권한다.

2. **F2 — 장 시간 보호 해제 스위치가 값과 무관하게 켜짐 (A8 실패 사유)**
   - [사실] `autotrade-app-experiment/build.gradle.kts:60,80`은 `allowDuringSession.isPresent`로 판단한다. `-PallowDuringSession=false`도 `--allow-during-session`을 넘기는 것을 실제 실행으로 확인했다(위 명령줄).
   - [추론] Gradle 속성은 `~/.gradle/gradle.properties`나 환경 변수 `ORG_GRADLE_PROJECT_allowDuringSession`으로도 들어온다. 실전 엔진을 끈 날 하루 쓰려고 한 설정이 거기 남으면 이후 모든 날 장 시간 보호가 **조용히** 꺼진다. `ExperimentMain`은 보호가 꺼졌다는 출력을 남기지 않는다. 수집기는 명령 인자 `--allow-during-session`을 매번 직접 줘야 해 이 문제가 없다.
   - 요구: 값이 정확히 `true`(또는 값 없음)일 때만 해제하고 그 밖의 값은 입력 오류로 멈추며, 해제됐을 때 시작 출력에 "장 시간 보호 해제됨(실전 엔진을 끈 날만)"을 남긴다. `-PallowDuringSession=false`가 해제하지 않는 테스트(또는 같은 판단을 하는 Java 쪽 파서와 테스트)를 둔다.

3. **실험 명령과 감시의 설정 폴더가 다른 경우** [추론]: `ConfigPaths.defaultLocation()`은 시스템 속성 `autotrade.config-dir`을 먼저 본다. 그런데 Gradle `JavaExec`는 `./gradlew -Dautotrade.config-dir=...`로 준 시스템 속성을 띄운 JVM에 넘기지 않는다(환경 변수 `AUTOTRADE_CONFIG_DIR`은 넘어감). 감시·엔진을 시스템 속성으로 띄우는 운영이면 실험 명령은 `~/.autotrade/experiment-state/stop-request`를 보고 감시 요청을 영영 못 본다. `ExperimentMain`은 확인하는 경로를 시작 때 출력하지 않는다(수집기는 출력함, PR #46 QA). 감시 구현 PR의 "설정 폴더 일치 시험"(risk-ops N2·N5 후속)에서 확인해야 하고, 그 전에라도 시작 때 확인 경로를 출력하기를 권한다(권고 Q1).

4. **병렬 정지 중 이미 돌던 Run** [사실]: 한 Run이 멈춤을 보면 `stopped`에 예외를 남기고, 다른 Run은 다음 거래일 경계에서 같은 예외로 끝난다(`checkGate`). 메인 스레드는 `futures.forEach(cancel(false))` 뒤 예외를 던지고, `try-with-resources`의 `ExecutorService.close()`가 돌던 Run이 다음 경계에 닿을 때까지 기다린다. 결과 파일은 `runAndWrite`의 쓰기 단계 전에 예외가 올라와 쓰이지 않는다(`ExperimentGateTest` 결과 파일 단언, `RunGateTest` 병렬 정지 테스트 통과). 잘못된 결과·부분 결과가 남는 경로는 찾지 못함.

5. **정지 요청 확인 실패** [사실]: `!Files.notExists`로 판단 못 함을 멈춤 쪽으로 처리(`ExperimentGate.java:47`), 테스트 `정지_요청이_있는지_판단할_수_없으면_해제_옵션과_상관없이_멈춘다` 통과(Linux). Windows에서 같은 전제가 성립하는지는 확인 못 함(PR #46과 같음, 운영 코드 판단은 맞음, 판정 무관).

6. **ArchUnit 규칙의 범위** [사실]: `APPS_RUN_EXPERIMENTS_THROUGH_GATE`는 `RunGate` **파라미터가 없는** 입구만 막는다. 프로세스 진입점이 `RunGate.NONE`을 넘기면 규칙을 통과하면서 같은 우회가 된다. 지금 `ExperimentMain`은 실제 문을 넘겨 결함은 아니다(권고 Q2).

7. **2027년 캘린더** [사실]: 번들 캘린더가 2026-12-31까지라 서울 2026-12-31 00:00부터 실험 명령이 해제 옵션 없이 시작하지 않는다(닫힌 쪽, D-063에 적힘). F2가 남아 있으면 이때 `gradle.properties`에 해제를 넣는 우회가 생기기 쉽다.

## 권고 (판정 무관)
- Q1 `ExperimentMain` 시작 때 확인하는 정지 요청 파일 경로를 출력한다(수집기와 같게). 감시 구현 PR에서 감시가 만든 경로와 대조.
- Q2 `com.autotrade.app..`에서 `RunGate.NONE` 접근을 ArchUnit으로 막는다.
- Q3 2027년 캘린더를 서울 2026-12-31 00:00 전에 넣는다(PR 후속 과제와 같음).

## 판정
수정 필요(A8: F1 준비 단계(스냅샷 적재·이벤트 변환·선정 일정 전체 계산, 결정론 확인 재계산)에 정지 확인이 없어 정지 요청 반응 시간이 정해지지 않음, F2 `-PallowDuringSession`이 값과 무관하게(`=false` 포함) 장 시간 보호를 풂. B6은 통과지만 F1·F2를 고칠 때 실패 경우 테스트를 함께 둘 것. 그 밖의 A·B1·과금 항목은 통과 또는 해당 없음)

## 재검토 2026-10-11
- 대상: PR #52 / 커밋 0ae6736180ce14c3a9a3b5dc68c99abe16fcf7c1 / main(ff6d74f) 대비 전체 diff. main을 포함하고(`merge-base --is-ancestor`) `git merge-tree` 충돌 없음
- 1차(6fecd47) 이후 변경: 커밋 3개. `79269ae`(QA F1·F2·Q1·Q2 반영), `8d256a6`(risk-ops F3: F2 테스트가 결함을 가려내게 함), `0ae6736`(architect·risk-ops 재검토 원문). 코드·테스트 10개 파일 +115 −13, 문서 D-063·5.2 0번·실험 절차 문서
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 0ae6736): **BUILD SUCCESSFUL**(1회째). 테스트 결과 XML 집계 885개, 실패 0, 오류 0, 건너뜀 0. `ExperimentGateTest` 11, `SelectionCombinationRunnerTest` 8, `RunGateTest` 4, `ArchitectureTest` 18, `RunGuardTest` 8 통과.
  - 변이 확인(작업 트리에서 바꿔 돌린 뒤 되돌림, 커밋하지 않음):
    1. `SelectionSchedule.java`의 공급자 호출 전 `checkpoint.run()` 제거 → `SelectionCombinationRunnerTest` 1건 실패(변이 잡힘).
    2. `SimulationExperimentRunner.runDetailed`의 선정 일정 계산 전 `gate.check()` 제거 → `SelectionCombinationRunnerTest`·`RunGateTest` 2건 실패(변이 잡힘).
    3. `ExperimentMain`의 해제 조건을 고치기 전 동작과 같은 `a.startsWith("--allow-during-session")`로 바꿈 → `ExperimentGateTest` 1건 실패(변이 잡힘).
  - `./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=EXP-QA-NONE -PallowDuringSession=false`: `--allow-during-session은 값이 없거나 true여야 합니다 ... --allow-during-session=false` 출력 뒤 종료 코드 1(장 시간 보호 해제 안 됨). 같은 명령에 `-PallowDuringSession`(값 없음): `[experiment] 정지 요청 파일 확인 위치: /root/.autotrade/experiment-state/stop-request`, `[experiment] 장 시간 보호 해제됨(실전 엔진을 끈 날만). 정지 요청 파일은 계속 확인합니다` 출력 뒤 없는 실험 ID라 종료 코드 1. 두 번 다 결과 파일 없음(`git status` 깨끗).

| # | 결과 | 근거 |
|---|---|---|
| A8 | 통과 | **F1 해결**: 조합 실험은 스냅샷 읽기 전(`CombinationExperiment.java:116`), 기간 확인 뒤·상장주식수·재무 적재 전(`:137`), 거래일 도출 전(`:150`), 이벤트 변환 뒤(`:160`), 결정론 확인 재실행 전(`:205`)에 묻는다. 실행기는 선정 일정 계산 **전**(`SimulationExperimentRunner.java:157`)과 계산 안 공급자 호출마다(`SelectionSchedule.compute(..., Runnable checkpoint)`) 묻고, 확인 지점은 공급자 실패를 `PRODUCER_FAILED`로 바꾸는 `try` 밖이라 정지 예외가 판정으로 삼켜지지 않는다. 테스트 `정지_확인은_선정_일정_계산_전과_공급자_호출마다_묻는다`가 1차 QA 탐침과 같은 상황(처음부터 정지)에서 공급자 호출 0건을 단언하고, 변이 1·2를 잡는다. 남은 공백(일봉 스냅샷 한 번 읽기 같은 단일 단계 중에는 확인 없음, 실제 규모 측정 없음)은 D-063 "위험과 한계"에 `[가정]`과 확인 방법(첫 실제 조합 실험 단계별 로그)으로 적혔다. **F2 해결**: Gradle은 값을 그대로 넘기고(`build.gradle.kts` `--allow-during-session=$it`), `ExperimentMain`은 값 없음·`true`만 해제하고 그 밖의 값은 입력 오류 1로 문에 닿기 전에 멈춘다(위 실행 확인). 해제되면 시작 출력에 남는다. 테스트는 정지 요청 파일을 둔 상태에서 받아들인 값은 4, 나쁜 값은 1로 두 경우를 가르고 변이 3을 잡는다 |
| A11 | 통과 | 보호 테스트는 강화만 됨: `APPS_RUN_EXPERIMENTS_THROUGH_GATE`가 `RunGate.NONE` 접근도 막고 위반 예제 4건(1차 3건 + `RunGate.NONE`)을 단언. 기존 규칙·단언 삭제·완화 없음. 새 동작(준비 단계 정지, 해제 값 검사)마다 실패 경우 테스트가 있고 변이로 유효성 확인 |
| A12 | 통과 | 5.2 0번(`broker-and-fault-design.md:144`) 문구가 실제 확인 지점(준비 단계마다·선정 계산 중·Run 시작 전·거래일마다)과 일치하게 바뀜. 장애 테스트 8종 전제 변경 없음 |
| A14 | 통과 | D-063 ②·④·"위험과 한계"·"검증"과 실험 절차 문서가 바뀐 코드와 일치(확인 지점 목록, 해제 값 규칙, 시작 출력, `gradle.properties`에 넣지 말라는 안내) |
| B1 | 통과 | 위 빌드 직접 실행 BUILD SUCCESSFUL(테스트 885개 실패 0) |
| B6 | 통과 | F1·F2 실패 경우 테스트 추가, 건너뜀 0, 지운 테스트 없음 |
| 나머지 A1~A7, A9, A10, A13, 과금 8.1~8.5 | 1차와 같음(통과 또는 해당 없음) | 이번 변경은 실험 명령의 정지 확인 지점·인자 해석·테스트·문서뿐이고 주문·Guard·증권사·외부 호출 코드를 건드리지 않음. 실험 명령 클래스패스와 ArchUnit 경계(설정 로더·broker·주문 포트 금지) 그대로. 추가 줄에 키·계좌번호·토큰 없음 |

### 점검표 밖
- 1차 시나리오 3(감시와 실험 명령의 설정 폴더 불일치, `-Dautotrade.config-dir`가 `JavaExec`로 안 넘어감)은 시작 때 확인 위치 출력(Q1)으로 사람이 알아챌 수단이 생겼다. 감시 구현 PR의 설정 폴더 일치 시험(risk-ops N2·N5)은 여전히 필요하다(후속 과제, 판정 무관).
- `gradle.properties`·`ORG_GRADLE_PROJECT_allowDuringSession=true`로 해제가 오래 남는 경우는 이제 매 실행 시작 출력에 "장 시간 보호 해제됨"이 찍힌다. 코드로 막지는 않는다(실전 엔진을 끈 날 쓰는 정상 경로와 구분할 수 없음). 7단계 운영 절차서에서 실전 엔진 기동 전 이 출력이 없는지 확인하는 항목을 두기를 권한다(권고 Q4, 판정 무관).
- 1차 권고 Q1·Q2는 반영됨. Q3(2027년 캘린더)은 PR 후속 과제로 남음.

### 재검토 판정
승인
