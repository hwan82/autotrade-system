# D-063 실험 실행 명령의 장 시간 보호와 정지 요청 파일 (Q-049)

- 날짜: 2026-10-11
- 상태: Q-049 추천안(sim-engine). broker-and-fault-design 5.2 정지 순서 0번과 장애표 #10을 바꾸므로 실전 관련 변경(지침 8장 2번)이다. risk-ops 검토 후 **사용자가 PR을 직접 머지하면 확정**한다(지침 10장)
- 담당: sim-engine. 교차 검토: risk-ops, architect
- 근거: [Q-049](README.md)(PR #50 architect F1), [D-058](README.md)(단발 일괄 명령 정지 요청), [D-061](D-061-daily-replay-combination-runner.md) "위험과 한계", [broker-and-fault-design.md](../team/broker-and-fault-design.md) 5.1·5.2·6장 #10, [ADR-008](../adr/ADR-008-collector.md) 2.7(수집기 장 시간 보호), 지침 4장 6번(실험실·녹화는 자원 부족 시 먼저 멈춤)
- 범위: 실험 실행 명령(`runCombinationExperiment`, `runStrategyStudy`)만. 실험실 프로세스(Spring)는 실험을 돌리지 않으므로(D-062) 범위 밖이다. 감시 쪽(임계치 감시, 파일 생성·해제)은 D-058 ④와 같은 감시 구현 후속 과제다

## 배경

[사실] 두 명령은 실전 엔진과 같은 PC에서 도는 단발 명령이고 힙 상한이 크다(`runCombinationExperiment` 기본 6g, `runStrategyStudy` 4g: `autotrade-app-experiment/build.gradle.kts`). PR #50까지는 정지 순서 0번에도, 장애표 #10에도 없었고 정지 요청 파일과 장 시간도 보지 않았다(D-061 "위험과 한계"). 순수 모듈(`autotrade-sim`)은 벽시계를 읽지 못해(ArchUnit `NO_SYSTEM_TIME`, 진입점은 `com.autotrade.app.*Main`만) 장 시간 판단을 sim 안에 넣을 수 없었다.

## 결정

1. **실행 명령을 프로세스 모듈 `autotrade-app-experiment`로 옮긴다.** 진입점 `com.autotrade.app.experiment.ExperimentMain`(`combination`·`study`)만 시스템 시계를 만든다. 작업 이름은 `./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=<실험ID>`, `./gradlew :autotrade-app-experiment:runStrategyStudy`. 예전 이름 `:autotrade-sim:run…`은 이 작업으로 넘기는 별칭으로 남긴다. sim의 `CombinationMain`·`StudyMain`에서는 `main`을 지워 정지 확인 없이 돌릴 길을 없앤다. 새 모듈은 D-013 상주 프로세스 4개에 들지 않는 단발 명령이다(수집기와 같은 자리, ADR-001 표).
2. **정지 확인 계약 `RunGate`(autotrade-sim)**: 실험 실행기 `SimulationExperimentRunner`가 실험 시작 때, 각 Run 시작 전, Run 안에서 거래일이 바뀔 때마다 묻는다. 멈추라는 답이면 남은 Run을 시작하지 않고 진행 중 Run도 다음 거래일 경계에서 끝내며, `ExperimentStoppedException`으로 실험 전체를 끝낸다. Run 하나의 실패(계좌 격리, 결과에 FAILED로 남김)와 구분해 **결과 파일을 하나도 쓰지 않는다**(이전 실행 결과는 그대로). 조합 실험은 명령 시작 때, 스냅샷을 읽기 전, 준비 단계마다(스냅샷 적재 뒤·거래일 도출 뒤·이벤트 변환 뒤·결정론 확인 재실행 전)도 묻고, 실행기는 선정 일정 계산(모든 선정 × 거래일) 전과 그 계산 안의 선정 공급자 호출마다 묻는다(PR #52 QA F1: 준비 단계가 힙과 시간의 큰 몫이라 반응이 늦지 않게). 멈추지 않으면 결과는 문이 없을 때와 바이트까지 같다(결정론 유지).
3. **정지 요청 파일 `<설정 폴더>/experiment-state/stop-request`**(설정 폴더 순서는 `ConfigPaths`: 시스템 속성 `autotrade.config-dir` → `AUTOTRADE_CONFIG_DIR` → `~/.autotrade`). D-058과 같은 규칙: 파일이 있으면(있는지 판단할 수 없어도) 멈추고, 명령은 지우지 않으며(감시만 지움), 해제 옵션으로도 풀리지 않는다. 5.2 0번과 장애표 #10에 이 경로를 더한다.
4. **장 시간 보호**: 수집기와 같은 구간(국내·미국 거래일 정규장 60분 전 ~ 폐장 30분 뒤, 캘린더 수록 범위 밖이면 판단 못 해 멈춤)에서 시작하지 않고 실행 중이면 멈춘다. 구간 계산은 수집기와 함께 쓰도록 `com.autotrade.marketdata.calendar.SessionProtection`으로 옮겼다(수집기 `RunGuard`는 같은 판정·같은 문구로 이것을 부른다). 실전 엔진을 끈 날에만 `-PallowDuringSession`으로 푼다(정지 요청 파일은 풀리지 않음). 값이 없거나 `true`일 때만 풀고 그 밖의 값(`false` 포함)은 입력 오류로 멈춘다. Gradle 속성은 `gradle.properties`·환경 변수로도 들어와 하루 쓰려던 해제가 조용히 남을 수 있으므로, 시작 때 확인하는 정지 요청 파일 경로와 해제 여부를 출력한다(PR #52 QA F2·Q1).
5. **종료 코드 4**: 정지 확인으로 멈추면 4(수집기와 같은 "실전 PC 자원 보호로 멈춤"). 0 완료, 1 입력 오류, 2 Run 실패·결정론 불일치는 그대로.
6. 빌드 격리와 경계 규칙: ArchUnit `APPS_RUN_EXPERIMENTS_THROUGH_GATE`(프로세스 진입점은 `RunGate` 없는 실행기·연구·조합 실험 입구를 부르지 못함), `EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG`(실험 명령은 프로세스 설정 로더·증권사·주문 포트를 모름), 둘 다 위반 고정 예제 포함. 그리고 실전 엔진·게이트웨이·감시의 `processIsolation.forbiddenProjects`에 `:autotrade-app-experiment`를 넣고, 새 모듈은 실험실·UI·제어·증권사·다른 app 모듈·Spring을 런타임에 갖지 않는다. ArchUnit `PLAIN_PROCESSES`(Spring 금지)에 `com.autotrade.app.experiment..`를 넣었다. 패키지 소유 표(`ModuleOwnershipTest.OWNERS`)에 새 모듈을 더했다.

## 대안과 되돌릴 조건

| 선택 | 버린 대안 | 이유 | 되돌릴 조건 |
|---|---|---|---|
| 새 단발 명령 모듈 `autotrade-app-experiment` | A. 실험실(`autotrade-app-lab`) 안에 진입점 | 실험실은 Spring Boot 앱이고 정지 순서 1·2번 대상이다. 단발 명령에 Spring 클래스패스를 끌고 오고, 실험실 화면 작업과 같은 모듈을 고치게 된다 | 실험실 화면에서 실험을 직접 만들고 돌리게 되면 실험실이 같은 `RunGate`를 넣어 `CombinationExperiment`를 부른다 |
| 〃 | B. Gradle 작업이 시작 시각을 인자로 넘기고 sim이 그 시각으로 판단 | 시작 순간만 보므로 실행 중 장 시간에 들어서는 것을 못 잡고, 정지 요청 파일 확인과 별개로 시간 인자를 믿어야 한다 | — |
| 정지 요청 파일 + 장 시간 둘 다 | C. Q-049 대안: 운영 절차서에 "실전 PC에서 장중 실행 금지"만 적음 | 사람의 기억에 맡기면 지침 4장 6번(실험실이 먼저 멈춤)을 코드로 지킬 수 없다. 수집기·선정 일괄 명령과 규칙이 갈린다 | — |
| 거래일 경계마다 확인 | D. Run 사이에만 확인(Q-049 추천안 문구) | 일봉 조합 실험 Run 하나가 수 년치를 돌아 길 수 있어, Run 사이만 보면 멈춤이 늦다. 거래일마다 파일 존재 확인 1번은 이벤트 수에 비해 작다 `[추론]` | 확인 비용이 실험 시간에 보이면 N 거래일마다로 줄인다 |
| 게이트 없는 실행 입구는 순수 모듈에만 남기고 `com.autotrade.app..`의 호출을 ArchUnit `APPS_RUN_EXPERIMENTS_THROUGH_GATE`로 막음(PR #52 architect N2) | F. 게이트 없는 오버로드를 지움 | 기존 sim 테스트·골든 테스트 수십 곳이 쓰고, 정지 확인은 프로세스 진입점의 책임이라 그 경계를 규칙으로 막는 편이 변경이 작다 | 순수 모듈 안에서 게이트 없는 실행이 실제로 문제가 되면 오버로드를 지운다 |
| 명령별 파일(`experiment-state/stop-request`) | E. 수집기 파일 하나를 함께 봄 | D-058이 명령마다 파일을 두었고, 감시가 명령별로 해제 시점을 다르게 가져갈 수 있다 | 감시 구현에서 파일 하나로 묶기로 하면 새 결정으로 바꾼다 |

## 위험과 한계

- **감시 쪽은 아직 없다**(D-058 ④). 지금 이 파일을 만드는 주체가 없어, 실제로 동작하는 보호는 장 시간 보호뿐이다. 감시 구현 작업이 `experiment-state/stop-request`도 만들고 거둬야 한다(후속 과제).
- **장 시간 보호로 실험 가능 시간이 줄어든다**: 평일 서울 기준 국내 08:00~16:00와 미국 정규장 앞뒤(서머타임 22:30~05:30, 아니면 23:30~06:30)에는 멈춘다. 실전 엔진이 없는 지금은 `-PallowDuringSession`을 붙여 돌린다. 문구가 이 옵션을 안내한다.
- **번들 캘린더가 2026-12-31까지**라 다음날 세션 조회가 범위 밖이 되는 **서울 2026-12-31 00:00부터** `-PallowDuringSession` 없이 시작하지 않는다(수집기도 같은 판정, PR #52 architect N1에서 확인). 그 전에 2027년 캘린더를 넣어야 한다.
- 멈춤은 확인 지점 사이 한 단계만큼 늦다: Run 안에서는 하루 처리, 준비 단계에서는 스냅샷 적재 한 번(가장 큰 단계, 일봉 스냅샷 전체 읽기)이나 선정 계산 한 번. 실제 규모(국내 전 종목·수 년)에서 이 시간은 측정하지 않았다 `[가정]`. 첫 실제 조합 실험 때 단계별 시간을 로그로 확인한다.
- 멈춤은 거래일 경계에서 일어나므로 Run 하나의 하루 처리 시간만큼 늦다. 일봉은 하루가 짧고, 합성 시세 실험(초 단위 이벤트)은 하루가 길어도 실험 전체가 작다 `[추론]`. 측정하지 않았다.
- 힙 상한(6g·4g) 자체를 줄이거나 낮은 OS 우선순위로 돌리는 것은 이 결정 밖이다(수집기 runbook처럼 운영 절차에 적을 일). 디스크 여유 확인도 넣지 않았다(결과 파일이 수 MB 수준 `[추론]`).

## 검증

- `SelectionCombinationRunnerTest#정지_확인은_선정_일정_계산_전과_공급자_호출마다_묻는다`: 처음부터 멈추면 선정 공급자를 부르지 않고, 계산 중 멈추면 남은 날을 부르지 않음(QA F1). `ExperimentGateTest#장_시간_보호_해제는_값이_없거나_true일_때만이고_그_밖의_값은_입력_오류다`(QA F2). ArchUnit `APPS_RUN_EXPERIMENTS_THROUGH_GATE`는 `RunGate.NONE` 접근도 막음(QA Q2).
- `RunGateTest`(sim): 멈추지 않는 문은 상세 보고서를 바꾸지 않음(호출 5번: 시작 1 + Run 2 × (시작 1 + 거래일 바뀜 1)), 시작 때 멈추면 Run이 돌지 않음, Run 도중 멈추면 FAILED로 남기지 않고 `ExperimentStoppedException`, 병렬 1에서 남은 Run은 시작하지 않음.
- `ExperimentGateTest`(app-experiment): 정지 요청 파일은 해제 옵션과 상관없이 멈추고 지우지 않음, 국내·미국 장 시간 경계, 캘린더 범위 밖, 정지 요청·장 시간에 조합 실험과 합성 시세 실험이 종료 코드 4로 끝나고 결과 파일을 쓰지 않음.
- 수집기 `RunGuardTest`는 고치지 않고 그대로 통과(장 시간 판정을 옮긴 뒤에도 같은 결과).
- `./gradlew build` 전체 통과(ArchitectureTest·ModuleOwnershipTest 포함).
