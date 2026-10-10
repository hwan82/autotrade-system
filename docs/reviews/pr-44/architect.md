# architect 검토: 선정 프로필·조합 실행(ADR-007 실험실 쪽, 배치표 3번): autotrade-selection, ExperimentSpec 조합화, RunSeeds v1/v2, 날짜별 선정 공유
- 대상: PR #44 / 브랜치 `claude/project-thread-ytnlgx` / head `1977106` / main(`8680fde`) 대비 `git diff origin/main...HEAD` 전체(37개 파일, +1515 −81)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build`: BUILD SUCCESSFUL(대부분 캐시). 캐시에 기대지 않으려고 `./gradlew build --rerun-tasks`를 다시 돌림: **BUILD SUCCESSFUL in 1m 30s, 109 tasks 모두 실행**. 테스트 결과 XML 집계 613개, 실패 0·오류 0·건너뜀 0(autotrade-selection 10, autotrade-sim 88, autotrade-architecture-test 28 포함).
  - 골든 값 독립 계산(Python `hashlib`, `struct.unpack('>q')`): `combination_hash`(none, a×64) = `2ce6b7ca…f6be`, (b×64, a×64) = `0bc30bba…8945`, run-seed v1(42, a×64) = `3654410437908045060`, v2(42, `0bc30bba…8945`) = `4792420304985496537`, 선정 해시 `ba146f69…777a`·같은 내용 전략 프로필 해시 `6072ea58…1247`. 모두 ADR-007 2.2·2.4와 테스트 기대값(`CombinationTest`, `RunSeedsTest`, `SelectionProfileTest`)에 일치.
  - 실전 경로 diff 확인: `autotrade-risk`, `autotrade-app-engine`, `autotrade-broker-*`, `autotrade-domain`, `autotrade-config`, `autotrade-control`, 보호 테스트(ArchitectureTest, Guard·GuardedOrderGateway, 장애 테스트) 변경 0건.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 실행. `--rerun-tasks` 빌드 성공, 613개 통과 |
| B2 | **실패** | 의존 방향 자체는 맞다: `autotrade-selection`은 `pure-java-conventions`(verifyPureDependencies) + `api(domain, strategy)`(`autotrade-selection/build.gradle.kts`), sim → selection(`autotrade-sim/build.gradle.kts:8`), `ModuleOwnershipTest.OWNERS`에 `com.autotrade.selection`(`ModuleOwnershipTest.java:108`). 그러나 **새 순수 모듈이 ArchUnit 순수 규칙 범위 밖이다**: `ArchitectureTest.java:58-67`의 `PURE`에 `com.autotrade.selection..`이 없어 `PURE_MODULES_HAVE_NO_FRAMEWORK`(java.sql·javax.sql·Spring 등 코드 수준 금지)가 이 모듈을 보지 않는다. ADR-007 2.1은 이 모듈을 config·control과 "같은 등급" 순수 모듈로 정했고 config·control은 `PURE`에 들어 있다. Gradle 검사는 외부 jar만 막고 JDK `java.sql` 사용은 ArchUnit만 막는다 → 지적 F1 |
| B3 | 통과(지적 F2 별도) | 계약 변경(`ExperimentSpec.profiles` → `combinations`, `RunResult`·`RunReport` 필드, `RunSeeds` v2, `TradingStyle.POSITION`, `SelectionProfile`·`Combination`)은 머지된 ADR-007 2.1~2.4(`docs/adr/ADR-007-selection-combination.md:33-123`)와 일치한다: 해시 인코딩·순서, `none` 표식, 조합 단위 중복 + 두 축 "같은 키 다른 내용" 거부(`ExperimentSpec.java:71-87`), 호환 팩토리 `ofProfiles`, 선정 없음 v1 유지. DB 스키마·설정 키·REST API 변경 없음(실험실 REST는 `market()` 위임만, `ExperimentViews.java:50,204`). ADR에 이름이 없는 새 타입(`SelectionSource`, `DailySelection`, `SelectionSchedule`, `SelectionDay`, `RunSelectionDay`, `ProfileYaml.Document`)은 sim·strategy 내부 계약이고 의미는 ADR-007 2.7 "실험실 쪽"을 따른다(권고 R6). 단 ADR이 이 PR 몫으로 정한 "조합 수 상한(SIM-14)은 실행기 시작 검증에서 거부"(ADR-007:112)가 구현도 이연 기록도 없다 → F2 |
| B4 | 통과 | 시스템 시계·무시드 난수 없음(ArchitectureTest `NO_SYSTEM_TIME`·`NO_UNSEEDED_RANDOM` 통과). 선정 표는 Run 시작 전 호출 스레드에서 선정 해시 사전순(TreeMap) × 거래일 오름차순으로 한 번 만들고 불변으로 공유(`SimulationExperimentRunner.java:132`, `SelectionSchedule.compute`). `ExperimentSpec`의 HashMap/HashSet은 검사용이라 순회 순서가 결과에 들어가지 않음. 기존 골든 테스트(`SimulationExperimentRunnerTest`)는 기대값·고정 녹화 파일 변경 없이 통과(테스트 diff는 `new ExperimentSpec` → `ExperimentSpec.ofProfiles` 5줄뿐). 선정 없는 조합 보고서가 조합 도입 전과 바이트 단위로 같음(`SelectionCombinationRunnerTest#선정_없는_조합은_조합_도입_전과_같은_보고서를_낸다`), 스레드 1·4와 두 번 실행 결과 동일(`#두_번_돌려도_스레드_수를_바꿔도_…`) |
| B5 | 통과 | 새 금액 계산 없음. 시드머니·최종 평가금은 기존 `Money`(BigDecimal). 선정 관련 값은 해시·날짜·종목 집합뿐 |
| B6 | 통과 | 새 동작마다 테스트: 조합 해시·선정 해시 골든, 시장·스타일 불일치 거부(`CombinationTest`), 예약 키·갱신 주기-스타일 규칙(`SelectionProfileTest`), YAML 읽기(`SelectionProfileYamlTest`), 조합 중복·두 축 다른 내용 거부·호환 팩토리(`ExperimentSpecTest` 신규 2개), v1/v2 골든·같은 전략 다른 선정이면 다른 시드(`RunSeedsTest`), 선정 밖 진입 차단·청산 유지·공급자 예외 → `PRODUCER_FAILED`·공급자 호출 횟수(선정×거래일 1회)·공급자 없으면 시작 거부(`SelectionCombinationRunnerTest` 6개), MALFORMED/HASH_MISMATCH/NOT_YET/EXPIRED/MISSING 판정과 주간 결과 재사용(`SelectionScheduleTest` 3개). 테스트 삭제·`@Disabled`·assume 없음(`--numstat`: 테스트 파일 삭제 줄은 `ExperimentSpecTest` 2줄, `SampleReports` 1줄, `SimulationExperimentRunnerTest` 5줄로 모두 호출부 교체) |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동·프로세스 격리·보호 테스트 변경 없음(위 직접 실행 3). 선정 필터는 sim 실행기 안에서 신규 진입 신호를 **버리기만** 하고 주문을 만들지 않으며, 통과한 신호는 그대로 `RiskChain`(블랙리스트·수동 보유 우선)을 거친다(`SimulationExperimentRunner.java:265-271`). `TradingStyle.POSITION` 추가는 실전 엔진 코드에 `TradingStyle` 분기가 없어 영향 없음(`grep` 결과 사용처는 `CatalogStrategyFactory.java:45,56,71`, `TradingRules.java:65`뿐). 단 F1을 고치면 `ArchitectureTest`(보호 테스트)를 강화 방향으로 바꾸게 되므로 그 수정 커밋은 risk-ops에게도 알릴 것(ADR-007 2.7 "ArchUnit 규칙 추가는 risk-ops 검토") |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(`git diff … | grep -iE 'appkey|secret|password|token|api.?key'` 0건). 공급자 예외는 클래스 이름만 남김(`SelectionSchedule` `ex.getClass().getSimpleName()`), 그마저 `SelectionDay`에 저장되지 않음 |

## 점검표 밖 발견

### 수정 필요
- **F1 (B2) `autotrade-selection`을 ArchUnit 순수 규칙에 넣을 것.** `ArchitectureTest.java:58-67` `PURE`에 `"com.autotrade.selection.."`을 더한다. 같은 이유로 `DOMAIN_IS_INNERMOST`(domain → selection 금지)와 `REUSABLE_JARS_ARE_DOMAIN_FREE`(notification·monitor → selection 금지)의 금지 목록에도 더하는 것을 권한다(지금은 Gradle 의존이 없어서만 막혀 있다). 규칙 강화라 기존 테스트가 깨지지 않아야 하며 `./gradlew :autotrade-architecture-test:test`로 확인. 보호 테스트 변경이므로 PR 본문에 적고 risk-ops에 알린다.
- **F2 (B3, STR-17·SIM-14) 조합 수 상한 검증이 없다.** ADR-007 2.4(`ADR-007:112`)는 "조합 수 상한(SIM-14)은 실행기 시작 검증에서 거부"를 이 실행기 변경 몫으로 정했고, 기획서 STR-17 인수 조건에 "조합 수가 실험실 자원 상한(SIM-14)을 넘으면 시작 거부"가 있다. 지금 `SimulationExperimentRunner.validateAndSlice`(`:157-`)에 조합 수 검사가 없고, 상한 값을 정한 설정 키도 없다. 둘 중 하나로 고칠 것: (a) 실행기 생성자에 최대 조합 수를 받아 시작 시 거부 + 테스트(설정 키를 새로 만들면 config-keys.md에 기본값·범위·실시간 변경 여부), 또는 (b) 상한 값이 아직 정해지지 않았으면 이 PR 본문과 ADR-007 후속(새 ADR 또는 결정 로그 후속 항목)에 "SIM-14 조합 수 상한은 배치표 N번/실험실 자원 상한 작업으로 이연"을 명시하고 담당을 적는다. 지금은 구현도 이연 기록도 없어 ADR과 코드가 어긋난다.

### 권고(차단 아님)
- **R1 진입 판정을 허용 목록으로.** `SimulationExperimentRunner.java:265`가 `s.intent() == OrderIntent.ENTRY`일 때만 막는다. `OrderIntent` 문서는 "허용 목록 방식이라 새 값을 추가하면 기본은 보호 주문이 아니다"(`OrderIntent.java`, ADR-002)라고 정했다. 같은 원칙으로 `!s.intent().isProtective()`를 쓰면 진입성 의도가 새로 생겨도 선정 필터를 우회하지 않는다. 지금 값이 셋뿐이라 동작 차이는 없다.
- **R2 다음 거래일 체결 신호의 선정 기준일을 문서로 정할 것.** 실행기는 신호가 난 거래일 d의 선정으로 진입을 거른다. 일봉 스윙처럼 d 종가 판단 → d+1 시가 체결이면, 실전·실시간 모의는 d+1 장전에 고정한 선정(`apply_from`=d+1, d까지 데이터)으로 거르게 된다(ADR-007 2.6 장전 고정). 미래 정보 문제는 없지만(리플레이가 하루 더 오래된 선정을 씀) 리플레이와 실시간 모의의 선정 입력이 하루 어긋날 수 있다. sim-engine이 의도를 `SimulationExperimentRunner` 문서 또는 다음 ADR에 적고, 배치표 2번의 일봉 로직이 들어올 때 기준을 확정할 것 `[추론]`.
- **R3 주간 결과 형식 검사는 일부만 한다.** ADR-007 2.6 판정 1번은 주간 결과의 `apply_from`이 그 ISO 주 첫 거래일, `apply_to`가 마지막 거래일이 아니면 `MALFORMED`로 정했다. `SelectionSchedule.judge`(`:110`)는 캘린더가 없어 "매일 갱신이면 하루" 검사만 한다. 클래스 문서가 판정 일부만 한다고 밝혔고 실험실 공급자는 자체 계산 결과라 위험은 낮다. 결과 파일 판정(`SelectionValidity`, 후속 PR)에서 반드시 넣을 것.
- **R4 `verifyProcessIsolation` 표 명시.** ADR-007 2.7(risk-ops G3)은 게이트웨이·감시 `forbiddenProjects`에 `:autotrade-selection`을 넣기로 했다. 지금은 두 프로세스가 `:autotrade-strategy`를 금지해 selection(→ strategy `api`)도 전이적으로 막히므로(`autotrade-app-gateway/build.gradle.kts:12`, `autotrade-app-watchdog/build.gradle.kts:12`, 검사는 `allComponents` 전이 해석) 공백은 없다. 생산 일괄 명령 PR에서 명시하면 된다.
- **R5 `POSITION`이 실험실 프로필 저장 API로 들어올 수 있다.** `ProfileController`/`StrategyProfileStore`는 Jackson 열거형으로 `style`을 받고 저장 시 `CatalogStrategyFactory.validate`를 부르지 않아, 이제 `POSITION` 전략 프로필을 저장할 수 있고 화면은 `format.js:54` `STYLE` 맵에 없어 원문으로 보인다. 실행 시점에는 `requireStyle`로 Run FAILED라 안전 문제는 아니다. ADR-007 2.3이 배치표 2·4번 몫으로 둔 것과 같으니 그 작업에서 처리.
- **R6 새 sim 계약 이름 기록.** `SelectionSource`(실험실 공급자 계약: 미래 정보 금지, 결정론, 예외 → `PRODUCER_FAILED`, 한 스레드·거래일 오름차순 호출)는 배치표 2번·실험실이 구현할 교차 역할 인터페이스다. ADR-007 본문은 고치지 않으므로 다음 ADR(생산 일괄 명령 또는 실험실 조합 REST)에 이름과 계약을 옮겨 적을 것.
- **R7 (기록) 해시 길이 접두는 UTF-16 길이.** `SelectionProfile.append`가 `String.length()`를 쓴다. `StrategyProfile`과 같은 관례이고 BMP 밖 문자(이모지 등)가 없으면 Python·PostgreSQL 문자 수와 같다. DB가 선정 해시를 다시 계산하지 않으므로 지금은 문제 없음.

### 확인한 것(문제 없음)
- `SelectionSchedule`은 받은 결과의 적용 범위가 그날을 덮으면 다시 부르지 않고, `MALFORMED`·`HASH_MISMATCH` 결과도 범위 안에서는 재사용해 같은 사유를 낸다. 결정적이다.
- 선정 밖 진입은 멱등 키를 쓰지 않고 거절 기록도 남기지 않으며 건수만 `blocked-entries`에 남는다. 결과 없는 날에도 청산(`EXIT`)은 나간다(`#결과가_없는_날은_신규_진입만_막고_청산은_그대로_한다`).
- `RunResult` 생성자가 DB CHECK와 같은 규칙(선정 없음 ⇔ v1, `combination_hash` = 유도식)을 Java에서도 강제한다.
- `ProfileYaml.parseDocument` 분리 후 `parse`의 오류 문구·동작이 같고 기존 strategy 테스트가 통과한다.

## 판정
수정 필요(F1 ArchUnit `PURE`에 `com.autotrade.selection..` 추가, F2 조합 수 상한(SIM-14) 시작 검증 구현 또는 이연 명시)
