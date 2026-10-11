# risk-ops 검토: 실험 실행 명령의 장 시간 보호와 정지 요청 파일 (D-063, Q-049)
- 대상: PR #52 / 브랜치 `claude/project-thread-5nka5j` / 커밋 `426862114a10831a816e0fe0103e35fd9055a238` / `git diff origin/main...HEAD` 전체(29개 파일, +752 −115)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon`: BUILD SUCCESSFUL(종료 코드 0).
  - 테스트 강제 재실행 `./gradlew :autotrade-risk:test --rerun :autotrade-architecture-test:test --rerun :autotrade-app-engine:test --rerun :autotrade-app-experiment:test --rerun :autotrade-sim:test --rerun :autotrade-app-collector:test --rerun :autotrade-marketdata:test --rerun`: BUILD SUCCESSFUL. 결과 XML 집계: risk 55/0 실패, architecture-test 30/0, app-engine 48/0, app-experiment 9/0, sim 113/0, app-collector 60/0, marketdata 80/0(건너뜀 0).
  - 참고: `cleanTest`와 위 테스트를 한 번에 돌린 첫 시도에서 `:autotrade-architecture-test:test`가 1회 FAILED로 끝났다. 보고서가 다음 실행에 덮여 원인은 보지 못했다. 같은 명령(`cleanTest :autotrade-architecture-test:test`)과 단독 실행, `--rerun` 실행 등 이후 4번은 모두 통과해 재현되지 않았다. 판정 근거에서는 빼고 기록만 남긴다.
  - Gradle 끝단 확인(임시 설정 폴더 `AUTOTRADE_CONFIG_DIR=<scratchpad>/cfg`):
    - `experiment-state/stop-request`를 만들고 예전 이름 `./gradlew :autotrade-sim:runStrategyStudy -PallowDuringSession` 실행: 별칭이 `:autotrade-app-experiment:runStrategyStudy`로 넘어감. `[study] 멈췄습니다(결과 파일을 쓰지 않음): 감시 프로세스가 자원 부족으로 정지를 요청했습니다(...)`, **종료 코드 4**. 해제 옵션으로 풀리지 않았고, 파일은 남았으며, 저장소에 쓴 파일도 없었다(`git status` 깨끗).
    - 정지 요청 파일 없이 일요일(2026-10-11, 보호 구간 밖)에 `:autotrade-app-experiment:runStrategyStudy` 실행: 끝까지 돌았다. `experiments/EXP-20261008-STR01/results.md`에서 바뀐 것은 코드 커밋 줄 하나뿐이었다(문을 넣은 뒤에도 결과 바이트가 같다는 부수 확인). 검토자가 만든 변경이라 `git checkout`으로 되돌렸다.
    - 정지 요청 파일이 있는 상태에서 등록 파일 없는 실험 ID로 `runCombinationExperiment` 실행: 종료 코드 1. 입력 검사(`CombinationMain.java:34-71`)가 문 확인(`:73`)보다 먼저 돌기 때문이다. 입력 검사는 파일 존재만 보는 가벼운 작업이라 보호를 약하게 하지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff가 `autotrade-risk`와 엔진 운영 코드를 건드리지 않는다. 엔진 쪽 변경은 `autotrade-app-engine/build.gradle.kts:13`의 `forbiddenProjects`에 `:autotrade-app-experiment`를 더한 것 하나다. RealTradingGuard 테스트를 포함한 `:autotrade-risk:test` 55건과 `:autotrade-app-engine:test` 48건을 재실행해 모두 통과했다 |
| A2 | 통과 | ArchitectureTest에서 바뀐 것은 `PLAIN_PROCESSES`에 `com.autotrade.app.experiment..`를 더한 한 줄뿐이다(`ArchitectureTest.java:72-75`, Spring 금지 강화). 주문 출구 규칙, `PROCESSES_ARE_INDEPENDENT`(`:251-253`, 새 패키지에도 자동 적용), `NO_SYSTEM_TIME`(`:113-124`)은 그대로다. 새 모듈은 `:autotrade-broker-api`, 플러그인 3종, `:autotrade-app-engine`을 런타임 클래스패스에 갖지 못한다(`autotrade-app-experiment/build.gradle.kts:15-19`, `verify` 단계 통과). 그래서 실전 주문 출구에 닿을 수 없다. ArchitectureTest 30건 통과 |
| A3 | 해당 없음 | 증권사 연동, 주문 라우팅 코드 변경 없음(diff 목록) |
| A4 | 해당 없음 | 주문, 타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트와 `RiskChain` 변경 없음. 실행기는 같은 `RiskChain`을 그대로 받는다(`SimulationExperimentRunner.java` 생성자, 체인 인자는 바뀌지 않음). 문은 Run 시작과 거래일 경계에서 멈추게만 하고 주문 경로를 우회하지 않는다 |
| A7 | 해당 없음 | 실전 계좌 큐, 토큰 버킷, 킬스위치 변경 없음 |
| A8 | 통과 | 이 PR의 핵심 항목이다. ① 실험 명령이 5.2 정지 순서 0번에 들어갔다(`broker-and-fault-design.md:144`). 정지 요청 파일 `<설정 폴더>/experiment-state/stop-request`가 있으면 시작하지 않고(`ExperimentGate.java:47`, `CombinationMain.java:73`, `StudyMain.java:75`, `CombinationExperiment.java:116`), 실행 중이면 Run 시작 전과 거래일마다 멈춘다(`SimulationExperimentRunner.java:160,168,331`). 해제 옵션으로 풀리지 않고(`ExperimentGate.java:47-52`), 명령은 파일을 지우지 않는다(`ExperimentGateTest#정지_요청_파일은_해제_옵션과_상관없이_멈추고_지우지_않는다`, Gradle 끝단 확인에서도 파일이 남음). ② 장 시간 보호는 수집기와 같은 구간을 같은 코드로 본다(`SessionProtection.java:42-63`, `RunGuard.java:68`, `ExperimentGate.java:55`). 수집기 `RunGuardTest` 8건은 고치지 않았는데 그대로 통과한다(판정 이전 증명). ③ 멈추면 결과 파일을 쓰지 않는다(`ExperimentGateTest#정지_요청이_있으면_조합_실험을_시작하지_않고_결과를_쓰지_않는다`, `#장_시간에는_조합_실험을_시작하지_않는다`, `#정지_요청이_있으면_합성_시세_비교_실험도_시작하지_않는다`, `RunGateTest#Run_도중_멈추라고_하면_Run_실패로_남기지_않고_실험_전체를_끝낸다`). ④ 정지 확인 없이 돌 길: sim의 `main` 2개가 지워졌다(`grep "main(" autotrade-sim/src/main` 결과 0건). 실험실은 실행기를 만들지 않는다(`autotrade-app-lab/src/main`에서 `SimulationExperimentRunner`, `CombinationExperiment.run`, `StrategyStudy(`를 부르는 곳 0건). ⑤ 실전 엔진, 게이트웨이, 감시가 새 모듈을 갖지 못한다(`autotrade-app-{engine,gateway,watchdog}/build.gradle.kts`의 `forbiddenProjects`) |
| A9 | 통과 | 보호 시세 경로와 서버 측 손절 코드는 바뀌지 않았다. 5.2의 6번(보유 종목 보호, 끝까지 유지)도 그대로다(`broker-and-fault-design.md:145-151`). 이 변경은 보유 종목 보호보다 앞서 멈추는 대상을 하나 더한 것이라, 보호 쪽 자원 여유를 늘리는 방향이다 |
| A10 | 통과 | diff 전체에 키, 계좌번호, 토큰이 없다(diff 직접 확인). 정지 메시지에 나오는 것은 설정 폴더 경로와 파일 시각뿐이다(`ExperimentGate.java:48-50`). 새 모듈은 `secrets.json`을 읽지 않는다(`ExperimentMain.java`는 `ConfigPaths`로 폴더 위치만 얻음) |
| A11 | 실패 | 보호 테스트(ArchitectureTest, ModuleOwnershipTest, RunGuardTest, Guard 테스트)를 지우거나 약하게 한 곳은 없다. ArchitectureTest와 ModuleOwnershipTest는 항목만 더했다. 새 동작의 실패 경우 테스트도 대부분 있다(정지 요청, 국내·미국 장 시간 경계, 캘린더 범위 밖, Run 도중 멈춤). **그러나** 이 PR이 문서와 코드 주석에 안전 규칙으로 적은 "정지 요청 파일이 있는지 판단할 수 없으면 멈춘다"(`ExperimentGate.java:47`의 `!Files.notExists`, D-063 결정 3, `RunGate.java` 주석)를 확인하는 테스트가 없다. 수집기에는 같은 규칙의 테스트(`RunGuardTest#정지_요청이_있는지_판단할_수_없으면_멈춘다`, `RunGuardTest.java:89-96`)가 있는데 실험 명령에는 빠졌다. 이 줄이 `Files.exists`로 바뀌면 판단 실패가 "계속"으로 뒤집히는데, 이를 잡을 테스트가 없다 → F1 |
| A12 | 통과 | 장애표 #10의 감지·대응 칸과 5.2 0번이 코드와 함께 바뀌었다(`broker-and-fault-design.md:144,169`. 종료 코드 4, 실험 실행 명령 경로 추가). 장애 테스트 8종의 전제는 깨지지 않는다. 2번(실험실 CPU 부하) 대상인 실험실 프로세스는 바뀌지 않았고, 실험 명령은 그보다 앞서 멈춘다. 장애표 17종의 수와 해외 O1~O8도 바뀌지 않았다 |
| A13 | 통과 | 장 시간 판정은 캘린더마다 거래소 현지 시간대(`cal.zone()`)로 전날, 당일, 다음날 세션을 본다(`SessionProtection.java:45-46`). 미국 서머타임(EDT) 경계는 `ExperimentGateTest#미국_장_시간도_멈춘다`(12:29Z 통과, 12:30Z 멈춤), 수집기 쪽은 `RunGuardTest#미국_정규장은_서머타임을_따라_막는다`로 확인했다. 조기 폐장은 캘린더 `closeInstant()`를 그대로 쓴다. 금액 계산 변경은 없다 |
| A14 | 통과 | D-063 신설(`docs/decisions/D-063-experiment-command-stop-gate.md`), 결정 로그 표 `docs/decisions/README.md:68`, Q-049를 닫힌 미확정으로 옮김(`:121`), 기획서 v14.15(RSK-07 `autotrade-spec.md:201`, SIM-14 `:148`), ADR-001 모듈 표와 ADR-004 후속 표, 실행 절차 문서 2곳이 코드와 맞다. D-063 상태 줄은 "risk-ops 검토 후 사용자가 PR을 직접 머지하면 확정"이라고 적어 지침 10장과 맞다 |

## 점검표 밖 발견
- **F1 (수정 필요, A11)**: `ExperimentGateTest`에 정지 요청 파일 판단 불가 테스트를 더한다. 예: `config.resolve("experiment-state")`를 일반 파일로 만들어 두면 `exists`와 `notExists`가 둘 다 false가 된다. 이 상태에서 `gate(WEEKEND, true).stopReason()`이 비어 있지 않은지 확인한다. 수집기 `RunGuardTest.java:89-96`과 같은 방식이다.
- **N1 (권고, 이번 PR을 막지 않음)**: `RunGateTest`의 Run 도중 멈춤 테스트는 병렬 1만 다룬다. 병렬 2 이상에서 한 Run이 멈춤을 본 뒤 이미 돌고 있는 다른 Run이 다음 거래일 경계에서 끝나고 결과 없이 `ExperimentStoppedException`으로 올라가는지 보는 테스트를 권한다. 대상은 `SimulationExperimentRunner.java:174-186`(try-with-resources의 `close()`가 실행 중인 Run을 기다린 뒤 예외가 올라가는 경로)이다. 코드를 읽어 보면 맞게 동작한다 `[추론]`. 다만 실행으로 확인하지는 않았다.
- **N2 (후속, D-058 ④ 감시 구현 때)**: 실험 명령은 설정 폴더를 `ConfigPaths.defaultLocation()`으로만 정하고 `--config-dir` 옵션이 없다(`ExperimentMain.java:39`). Gradle `JavaExec`는 환경 변수 `AUTOTRADE_CONFIG_DIR`를 물려주지만 Gradle에 준 `-Dautotrade.config-dir`는 넘기지 않는다. 감시를 구현할 때 감시가 파일을 만드는 폴더와 이 명령이 읽는 폴더가 같은 규칙으로 정해지는지를 시험 항목에 넣어야 한다. 폴더가 어긋나면 정지 요청이 조용히 무시된다.
- **N3 (후속, 7단계 운영 절차)**: `-PallowDuringSession`은 "실전 엔진을 끈 날에만"이라는 사람의 판단에 맡긴다. 실전 엔진이 실제로 꺼졌는지(하트비트, 잠금 파일)는 확인하지 않는다. 수집기(D-058)와 같은 수준이라 이번 PR에서 회귀는 아니다. 다만 7단계 실전 활성화 전 운영 체크리스트에 "실전 계좌가 하나라도 켜진 날에는 해제 옵션 금지"를 넣거나, 엔진 하트비트가 살아 있으면 해제 옵션을 거부하는 방식을 검토하길 권한다.
- **N4 (기록)**: 힙 상한 6g·4g과 OS 우선순위는 D-063 범위 밖으로 적혀 있다(D-063 "위험과 한계"). 장 시간 보호 구간 밖에서만 돌기 때문에 지금 실전 영향은 없다. 해제 옵션을 쓴 날에는 이 크기가 그대로 실전 PC에 걸린다는 점을 N3 절차에 함께 적길 권한다.
- **N5 (기록)**: 감시 쪽(파일 생성과 해제)이 아직 없어서, 실제로 동작하는 보호는 장 시간 보호뿐이다. D-063 "위험과 한계" 첫 줄에 정직하게 적혀 있고, 장애표 #10 감지 칸도 감시 생존을 볼 주체가 없다는 점을 이미 밝힌다. 장애 테스트 2번(부하 주입 시 정지 순서) 합격 전에 감시 구현이 선행돼야 한다.

## 판정
수정 필요(F1: `ExperimentGate`의 정지 요청 판단 불가 시 멈춤 테스트 추가)

## 재검토 2026-10-11
- 대상: PR #52 / 커밋 `5116758e4cd5e8bb536a348c5b38a2be55086591` / `git diff 4268621 5116758`(7개 파일)과 `git diff origin/main...HEAD` 전체 현재 상태(32개 파일, +956 −115)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --no-daemon`: BUILD SUCCESSFUL.
  - 강제 재실행 `:autotrade-{risk,architecture-test,app-engine,app-experiment,sim,app-collector,marketdata}:test --rerun`: BUILD SUCCESSFUL. 실패 0, 건너뜀 0. 건수는 risk 55, architecture-test 31, app-engine 48, app-experiment 10, sim 114, app-collector 60, marketdata 80. 새 테스트 3개(`ExperimentGateTest#정지_요청이_있는지_판단할_수_없으면_해제_옵션과_상관없이_멈춘다`, `RunGateTest#병렬로_돌던_Run도_다음_거래일에_끝나고_결과_없이_멈춘다`, `ArchitectureTest#실험_실행_명령은_정지_확인을_거치고_프로세스_설정을_모른다`)가 결과 XML에 있다.
  - F1 변이 확인: `ExperimentGate.java:47`의 `!Files.notExists(stopRequest)`를 잠시 `Files.exists(stopRequest)`로 바꿔 `:autotrade-app-experiment:test --rerun`을 돌렸다. "10 tests completed, 1 failed"로 새 테스트가 잡았다. 바꾼 코드는 `git checkout`으로 되돌렸다.
  - `RunGateTest`만 반복 실행(`./gradlew :autotrade-sim:test --rerun --tests com.autotrade.sim.run.RunGateTest`): 6번 중 2번(1·4회차) 실패. 그 앞의 `-q` 반복 5번에서도 FAILED 줄이 4번 나왔다. 실패 내용은 아래 F2.
  - 주의: 이 재검토를 쓰는 동안 같은 작업 트리에 검토자가 만들지 않은 커밋 전 변경이 생겼다. 대상은 `SimulationExperimentRunner.java`(`AtomicReference`로 첫 정지 예외를 남김, 주석에 "PR #52 architect R1"), `RunGateTest.java`, `docs/reviews/pr-52/architect.md`다. 실패한 회차의 스택은 커밋 `5116758`의 코드(`checkGate:295`의 일반 문구)를 가리킨다. 그래서 F2 판정은 `5116758` 기준이다. 이 커밋 전 변경은 검토하지 않았다. 반복 실행의 뒤쪽 회차가 이 변경 위에서 돌았는지는 확인 못 했다.
  - 커밋 `5116758`에 들어간 `docs/reviews/pr-52/risk-ops.md`는 검토자가 쓴 원문과 바이트가 같다(`git show 5116758:... | diff -` 차이 없음).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 수정 커밋은 테스트, 고정 예제, D-063 문서만 바꿨다. RealTradingGuard 관련 변경 없음. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 새 ArchUnit 규칙 `APPS_RUN_EXPERIMENTS_THROUGH_GATE`(`ArchitectureTest.java:247`)와 `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`(`:266`)는 규칙을 더하기만 했다. 둘째 규칙이 실험 명령에서 `com.autotrade.broker..`와 `com.autotrade.domain.order..` 의존을 막아 주문 출구와의 거리를 코드로 고정한다. 위반 고정 예제 `RunsWithoutGate`에서 3건과 설정·주문 포트 위반이 잡히는 것도 확인했다(`ArchitectureTest#실험_실행_명령은_정지_확인을_거치고_프로세스_설정을_모른다` 통과). 기존 규칙은 바뀌지 않았다 |
| A3 | 해당 없음 | 변경 없음 |
| A4 | 해당 없음 | 변경 없음 |
| A5 | 해당 없음 | 변경 없음 |
| A6 | 해당 없음 | 변경 없음 |
| A7 | 해당 없음 | 변경 없음 |
| A8 | 실패 | 정지 순서 0번의 동작 자체(시작 거부, 거래일 경계 정지, 결과 파일 없음, 해제 옵션으로 정지 요청이 풀리지 않음)는 첫 검토 때 그대로이고, 게이트 우회 호출을 ArchUnit으로 막아 더 단단해졌다. **그러나** 병렬 실행에서 정지 사유가 사라지는 결함이 있다(F2). 멈추기는 하지만 사용자가 보는 이유가 "다른 Run이 정지 요청을 받아 멈춥니다"로 바뀔 수 있다. 장애표 #10은 감시가 죽은 채 정지 요청 파일이 남은 경우를 지금 **거부 메시지(파일 시각 포함)와 종료 코드로만** 감지한다(`broker-and-fault-design.md:169`). 메시지가 사라지면 그 감지 수단이 비결정적으로 약해진다 |
| A9 | 통과 | 보호 경로 변경 없음 |
| A10 | 통과 | 수정 커밋에 비밀값 없음(diff 직접 확인). 고정 예제는 `ConfigPaths.defaultLocation()`만 부르고 값을 넣지 않는다 |
| A11 | 실패 | F1은 해결됐다(새 테스트가 변이를 잡음). 보호 테스트(ArchitectureTest)는 규칙과 테스트를 더하기만 해서 강화다. **그러나** 새로 더한 `RunGateTest#병렬로_돌던_Run도_다음_거래일에_끝나고_결과_없이_멈춘다`가 비결정적으로 실패한다. 반복 실행 6번 중 2번 실패했다(F2). CI에서 간헐적으로 깨지는 테스트는 결국 끄거나 지우고 싶어지게 만든다(지침 4장 5번 위험). 원인은 테스트가 아니라 운영 코드라 테스트를 느슨하게 고쳐서는 안 된다 |
| A12 | 실패 | A8과 같은 이유다. 장애표 #10 감지 칸이 기대는 거부 메시지가 병렬 실행에서 보장되지 않는다. 대응표 문구는 바꾸지 않아도 되고, 코드가 첫 정지 사유를 지키면 된다 |
| A13 | 통과 | 변경 없음. 시간 판정은 첫 검토 근거 그대로다. D-063 "위험과 한계"의 캘린더 끝 시점 정정(서울 2026-12-31 00:00부터 거부)은 `SessionProtection.java:46`(다음날 세션까지 조회)과 맞다 |
| A14 | 통과 | D-063 결정 6과 대안 F가 새 ArchUnit 규칙과 맞다(`D-063-experiment-command-stop-gate.md`). 결정 로그 표와 기획서는 첫 검토 때 그대로이고 여전히 코드와 맞다 |

### 새 지적
- **F2 (수정 필요, A8·A11·A12)**: 병렬 실행에서 첫 정지 사유를 잃는다.
  - 재현: 위 반복 실행에서 `Expecting message to be: "정지 요청 파일" but was: "다른 Run이 정지 요청을 받아 멈춥니다"`가 나왔다. 스택은 `SimulationExperimentRunner.checkGate(SimulationExperimentRunner.java:295)` ← `runOne(:331)`이다.
  - 원인: `runDetailed`는 `futures`를 순서대로 `get()`하고, 처음 받은 `ExecutionException`의 원인을 그대로 던진다(`SimulationExperimentRunner.java:172-184`). Run 2가 문에서 진짜 사유를 받아 `stopped`를 세우는 사이에 Run 1이 거래일 경계에서 `stopped`만 보고 일반 문구로 끝날 수 있다(`:293-296`). 이때 `futures.get(0)`의 예외, 곧 일반 문구가 위로 올라간다. 그러면 `CombinationMain`·`StudyMain`이 찍는 거부 메시지에 정지 요청 파일 경로와 만든 시각(`ExperimentGate.java:48-50`), 또는 장 시간 보호 구간 문구가 빠진다.
  - 이 결함은 첫 커밋 `4268621`에도 있었다. 첫 검토 N1에서 "실행으로 확인하지 않았다 `[추론]`"으로 남긴 경로이고, 이번에 실행해서 드러났다.
  - 고칠 방향(추천): 첫 정지 예외를 `AtomicReference<ExperimentStoppedException>`(또는 사유 문자열)에 `compareAndSet`으로 남긴다. `checkGate`가 `stopped`를 볼 때와 `runDetailed`가 `ExecutionException`을 받을 때 모두 그 첫 예외를 던진다. 테스트는 사유 메시지가 언제나 문이 준 문구인지 확인하도록 그대로 두고, 같은 테스트를 여러 번 돌리는 반복 테스트(예: `@RepeatedTest(50)`)나 두 Run의 경계 도달 순서를 래치로 고정한 테스트로 비결정성이 사라졌는지 보인다.
  - 대안: 테스트에서 메시지 확인을 빼거나 두 문구를 모두 허용하는 방법은 받아들이지 않는다. 운영 결함을 테스트를 느슨하게 해서 가리는 것이기 때문이다.

### 첫 검토 지적 처리
- F1: 해결. 근거는 `ExperimentGateTest.java:51` 테스트와 변이 확인이다.
- N1: 테스트가 추가됐다. 다만 그 테스트가 F2를 드러냈다.
- N2~N5: 첫 검토 때와 같다(후속 과제, 이번 PR을 막지 않음). 새 ArchUnit 규칙이 설정 로더 의존을 막으므로, N2(설정 폴더 일치)는 감시 구현 때 `ConfigPaths` 순서를 같게 맞추는 시험으로 다룬다.

## 판정 (재검토)
수정 필요(F2: 병렬 실행에서 첫 정지 사유 보존, `RunGateTest` 병렬 테스트의 비결정적 실패 해소)

## 재검토 2 2026-10-11
- 대상: PR #52 / 커밋 `8254e03f686758ac094fea4196ef574e9fd388c1` / `git diff 5116758 8254e03`(운영 코드는 `SimulationExperimentRunner.java` 하나, 테스트는 `RunGateTest.java` 주석)과 `git diff origin/main...HEAD` 전체(32개 파일, +1042 −115)
- 점검표: A
- 작업 트리 간섭을 피하려고 스크래치 폴더에 따로 clone한 곳(`<scratchpad>/r2`, `git checkout 8254e03`)에서만 빌드하고 테스트했다. 커밋 `8254e03`에 들어간 이 파일의 앞부분은 검토자가 쓴 원문과 바이트가 같다(`git show 8254e03:docs/reviews/pr-52/risk-ops.md | diff -` 차이 없음).
- 직접 실행한 것(clone 안에서):
  - `./gradlew --no-daemon build`: BUILD SUCCESSFUL(130 tasks 모두 실행, 캐시 없음).
  - 강제 재실행 `:autotrade-{risk,architecture-test,app-engine,app-experiment,sim,app-collector,marketdata}:test --rerun`: BUILD SUCCESSFUL. 실패 0, 건너뜀 0. 건수는 risk 55, architecture-test 31, app-engine 48, app-experiment 10, sim 114, app-collector 60, marketdata 80.
  - 반복 시험: clone 안에서만 `RunGateTest#병렬로_돌던_Run도_다음_거래일에_끝나고_결과_없이_멈춘다`를 그대로 옮긴 임시 클래스를 `@RepeatedTest(300)`으로 만들어 돌렸다.
    - `8254e03` 코드: **300번 중 실패 0**.
    - 같은 임시 테스트로 실행기만 `5116758` 판으로 되돌린 대조 실행: **300번 중 실패 30**. 반복 시험이 F2 결함을 가려낼 힘이 있다는 확인이다.
    - 대조 실행 뒤 운영 코드를 되돌리고 임시 클래스를 지웠다. clone의 `git status`는 깨끗하다.
  - 원래 `RunGateTest`를 Gradle로 5번 따로 실행: 5번 모두 통과.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 수정 커밋은 sim 실행기와 테스트 주석만 바꿨다. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | ArchUnit 규칙에 이번 변경은 없다(재검토 1 근거 그대로). ArchitectureTest 31건 통과 |
| A3 | 해당 없음 | 변경 없음 |
| A4 | 해당 없음 | 변경 없음 |
| A5 | 해당 없음 | 변경 없음 |
| A6 | 해당 없음 | 변경 없음 |
| A7 | 해당 없음 | 변경 없음 |
| A8 | 통과 | 처음 받은 정지 예외 하나를 `AtomicReference`에 `compareAndSet`으로 남긴다(`SimulationExperimentRunner.java:163`). 문을 묻기 전에 남은 예외가 있으면 그것을 던지고(`:294-297`), 문이 멈추라고 하면 먼저 남긴 쪽을 던진다(`:302`). `runDetailed`가 어느 Future에서 예외를 먼저 받든 같은 첫 예외를 던진다(`:180`). 두 Run이 동시에 문에서 다른 사유를 받아도 `compareAndSet`이 이긴 하나만 남고, 양쪽 모두 그것을 던진다. 그래서 명령까지 올라오는 메시지에 정지 요청 파일 경로와 시각, 또는 장 시간 구간이 언제나 남는다. 반복 시험 300/0(대조 300/30)으로 확인했다. 정지 순서 0번의 나머지 동작은 첫 검토와 재검토 1 근거 그대로다 |
| A9 | 통과 | 보호 경로 변경 없음 |
| A10 | 통과 | 수정 커밋에 비밀값 없음(diff 직접 확인) |
| A11 | 통과 | F1 테스트(`ExperimentGateTest.java:51`, 재검토 1에서 변이로 확인)와 병렬 정지 테스트가 이제 결정적으로 통과한다(반복 300/0). 테스트의 메시지 확인 `hasMessage("정지 요청 파일")`은 느슨해지지 않았다(`RunGateTest.java` diff는 주석만). 보호 테스트를 지우거나 약하게 한 곳은 없다 |
| A12 | 통과 | 장애표 #10 감지 칸이 기대는 거부 메시지(파일 시각 포함)가 병렬 실행에서도 지켜진다(A8). 대응표 17종, O1~O8, 장애 테스트 8종의 전제는 바뀌지 않았다 |
| A13 | 통과 | 변경 없음(재검토 1 근거 그대로) |
| A14 | 통과 | 결정 로그, 기획서, D-063이 바뀐 동작과 맞다. 이번 수정은 D-063 결정 2("멈추면 `ExperimentStoppedException`으로 실험 전체를 끝낸다")를 구현에서 바로잡은 것이라 새 결정이 필요 없다 |

### 지적 처리
- F1: 해결(재검토 1).
- F2: 해결. 근거는 `SimulationExperimentRunner.java:163,180,294-302`와 반복 시험 300/0(대조 300/30)이다.
- N1: 해결. 병렬 정지 테스트가 추가됐고 이제 결정적으로 통과한다.
- N2~N5: 남는다. 후속 과제이고 이 PR을 막지 않는다. N2(감시와 실험 명령의 설정 폴더 일치)와 N5(감시 쪽 파일 생성·해제 미구현)는 D-058 ④ 감시 구현 작업의 시험 항목으로 넘긴다. N3(해제 옵션 사용 날 실전 엔진 꺼짐 확인)과 N4(힙 6g·4g)는 7단계 운영 절차서로 넘긴다.
- 기록: 같은 예외 객체를 여러 Run 스레드에서 다시 던지므로 스택 추적은 첫 스레드 것만 남는다. 운영 판단(메시지, 종료 코드)에는 영향이 없다.

## 판정 (재검토 2)
승인. 장애 대응표(#10, 정지 순서 0번)를 바꾸는 실전 관련 PR이라 지침 10장에 따라 사용자가 직접 확인하고 머지한다.

## 재검토 3 2026-10-11
- 대상: PR #52 / 커밋 `79269ae7bd7dfab1b209eca68b0e3b8e95d403e3`(독립 QA `docs/reviews/pr-52/qa.md` F1·F2·Q1·Q2 반영) / `git diff 8254e03 79269ae`(16개 파일)와 `git diff origin/main...HEAD` 전체 현재 상태
- 점검표: A
- 스크래치 폴더에 새로 clone한 곳(`<scratchpad>/r3`, `git checkout 79269ae`)에서만 빌드하고 테스트했다. 커밋에 들어간 이 파일의 앞부분은 검토자 원문과 바이트가 같다.
- 검토자 자기 지적: 독립 QA F2(`-PallowDuringSession=false`도 보호를 풂)는 첫 검토 때 `build.gradle.kts`의 `allowDuringSession.isPresent`를 읽고도 기록하지 않은 결함이다. risk-ops 검토가 놓친 것으로 남긴다.
- 직접 실행한 것(clone 안에서):
  - `./gradlew --no-daemon build`: BUILD SUCCESSFUL.
  - 강제 재실행 `:autotrade-{risk,architecture-test,app-engine,app-experiment,sim,app-collector,marketdata}:test --rerun`: BUILD SUCCESSFUL. 실패 0, 건너뜀 0. 건수는 risk 55, architecture-test 31, app-engine 48, app-experiment 11, sim 115, app-collector 60, marketdata 80.
    - 첫 시도는 "Gradle build daemon has been stopped: stop command received"로 중단됐다. 외부에서 데몬 정지 명령이 들어온 것으로 보이고, 테스트 실패는 아니다. 결과 폴더를 지우고 다시 돌려 위 결과를 얻었다.
  - 변이 확인 1(QA F1): `SelectionSchedule.java:96`의 `checkpoint.run()`을 주석 처리하자 `SelectionCombinationRunnerTest`가 "8 tests completed, 1 failed"로 잡았다. 되돌림.
  - 변이 확인 2(QA F2): `ExperimentMain.java:42`의 허용 조건을 고치기 전 동작과 같은 `a.startsWith("--allow-during-session")`(어떤 값이든 해제)로 바꿨다. **`:autotrade-app-experiment:test` 11건이 모두 통과했다(변이 생존).** 아래 F3. 되돌림.
  - Gradle 끝단 확인(`AUTOTRADE_CONFIG_DIR=<scratchpad>/cfg`, 없는 실험 ID):
    - `-PallowDuringSession=false`: "--allow-during-session은 값이 없거나 true여야 합니다…" 출력, 종료 코드 1.
    - `-PallowDuringSession`과 `=true`: "[experiment] 정지 요청 파일 확인 위치: …/cfg/experiment-state/stop-request"와 "장 시간 보호 해제됨…" 출력 뒤 등록 파일 없음으로 종료 코드 1.
    - 운영 동작은 맞다. clone의 `git status`는 깨끗하다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | Guard 변경 없음. risk 55건 통과 |
| A2 | 통과 | `APPS_RUN_EXPERIMENTS_THROUGH_GATE`가 `RunGate.NONE` 필드 접근도 막도록 넓어졌다(`ArchitectureTest.java:249-256`, 고정 예제 위반 3 → 4건). 다른 규칙은 그대로다. ArchitectureTest 31건 통과 |
| A3 | 해당 없음 | 변경 없음 |
| A4 | 해당 없음 | 변경 없음 |
| A5 | 해당 없음 | 변경 없음 |
| A6 | 해당 없음 | 변경 없음 |
| A7 | 해당 없음 | 변경 없음 |
| A8 | 통과 | QA F1: 준비 단계 확인 지점이 생겼다. 위치는 스냅샷 적재 전·후, 거래일 도출 뒤, 이벤트 변환 뒤, 결정론 재실행 전(`CombinationExperiment.java:116,137,150,160,205`), 선정 일정 계산 전(`SimulationExperimentRunner.java:157`), 선정 공급자 호출마다(`SelectionSchedule.java:96`)다. 공급자 호출의 `try` 바깥에 있어서 정지가 `PRODUCER_FAILED`로 삼켜지지 않는다(`:96` 뒤 `:98` `try`, 테스트의 `hasMessage("장 시간")`). QA F2: 운영 동작은 맞다(`ExperimentMain.java:42-46`, Gradle 끝단 확인). 해제는 값이 없거나 `true`일 때만이고, `false`와 오타는 종료 코드 1로 멈춘다. 이쪽은 닫힌 쪽 실패다. 확인 경로와 해제 여부도 출력한다(QA Q1, 감시와 설정 폴더가 어긋날 때 사람이 알아챌 수단) |
| A9 | 통과 | 보호 경로 변경 없음 |
| A10 | 통과 | 시작 출력은 설정 폴더 경로뿐이고 비밀값은 없다. 수정 커밋 diff를 직접 확인했다 |
| A11 | 실패 | QA F1 테스트(`SelectionCombinationRunnerTest#정지_확인은_선정_일정_계산_전과_공급자_호출마다_묻는다`)는 변이를 잡는다. **그러나** QA F2 테스트 `ExperimentGateTest#장_시간_보호_해제는_값이_없거나_true일_때만이고_그_밖의_값은_입력_오류다`(`ExperimentGateTest.java:121-139`)는 이 PR이 고친 바로 그 결함을 되살려도 통과한다(변이 확인 2). 나쁜 값(`:128`)과 좋은 값(`:133`) 모두 기대값이 종료 코드 1이기 때문이다. 해제가 거부돼도 1(입력 오류)이고, 해제된 뒤 최소 등록 파일 해석에서 실패해도 1이라 두 경우를 가르지 못한다. 보호 규칙에 실패하는 경우의 테스트가 사실상 없는 상태다 → F3 |
| A12 | 통과 | 5.2 0번 문구가 새 확인 지점과 맞게 바뀌었다(`broker-and-fault-design.md` 5.2 0번 "준비 단계마다·선정 계산 중·Run 시작 전·거래일마다"). 대응표 17종, O1~O8, 장애 테스트 8종의 전제는 바뀌지 않았다 |
| A13 | 통과 | 변경 없음 |
| A14 | 통과 | D-063 결정 2·4, 위험과 한계(준비 단계 지연 `[가정]`, 측정 안 함), 검증 절이 코드와 맞다. 절차 문서에 "`gradle.properties`에 넣어 두지 않는다"가 추가됐다 |

### 새 지적
- **F3 (수정 필요, A11)**: 해제 옵션 값 검사 테스트가 결함을 가려내지 못한다.
  - 고칠 방향: 나쁜 값이 "해제되지 않았다"는 것을 종료 코드 말고 다른 근거로 확인한다. 예:
    - ① 장 시간 시계에서 나쁜 값으로 돌릴 때 `System.err`를 잡아 "값이 없거나 true여야" 문구를 확인하고, "장 시간 보호 해제됨"이 `System.out`에 나오지 않는지도 본다.
    - ② 옵션 해석을 `static Optional<Boolean> parseAllow(String)` 같은 함수로 떼어 직접 단위 테스트한다.
    - ③ 좋은 값의 기대 종료 코드를 1이 아닌 값으로 만든다(정지 요청 파일을 두어 해제 뒤 4가 나오게). 그러면 나쁜 값(1)과 갈린다.
  - 완료 확인: 위 변이 확인 2(`startsWith`로 되돌리기)에서 테스트가 실패해야 한다.
- 기록(수정 불요): `SelectionCombinationRunnerTest.java:197`의 `CountingSource` 설명 주석이 새 테스트 메서드(`:199`) 위로 밀려 메서드 설명처럼 붙었다. 다음에 그 파일을 고칠 때 옮기길 권한다.

### 지적 처리
- 첫 검토 F1, 재검토 1 F2, N1: 해결 상태 유지(이번 빌드에서 관련 테스트 통과).
- 독립 QA F1: 해결(변이 확인 1).
- 독립 QA F2: 운영 코드는 해결. 테스트는 F3.
- QA Q1·Q2: 반영됨(시작 출력, `RunGate.NONE` 규칙).
- N2~N5: 후속 과제로 유지. N2는 Q1의 시작 출력으로 사람이 알아챌 수단이 생겼으나, 감시 구현 때 설정 폴더 일치 시험은 여전히 필요하다.

## 판정 (재검토 3)
수정 필요(F3: `-PallowDuringSession` 값 검사 테스트가 고친 결함의 재발을 잡도록 고침. 변이 `startsWith`로 실패 확인)
