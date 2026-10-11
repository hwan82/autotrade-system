# risk-ops 검토: 선정 프로필·조합 실행(ADR-007 실험실 쪽, 배치표 3번): autotrade-selection, ExperimentSpec 조합화, RunSeeds v1/v2, 날짜별 선정 공유
- 대상: PR #44 / 브랜치 `claude/project-thread-ytnlgx` / head `67a2a07` / main(`c710520`, merge-base와 같음) 대비 `git diff origin/main...HEAD` 전체(41개 파일, +1618 −86)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --no-daemon`(head `67a2a07`): **BUILD SUCCESSFUL in 2m, 117 tasks 모두 실행**. 테스트 XML 집계 678개, 실패 0·오류 0·건너뜀 0. 모듈별: autotrade-risk 55, autotrade-architecture-test 29, autotrade-app-engine 48, autotrade-selection 10, autotrade-sim 89, autotrade-app-gateway 36, autotrade-app-watchdog 1, autotrade-domain 31, autotrade-config 30, autotrade-control 20, broker-kis 15, broker-kiwoom 51.
  - 같은 빌드 로그에서 `verifyProcessIsolation`(engine·gateway·watchdog·collector)과 `verifyPureDependencies`(selection 포함) 실행 확인.
  - `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-selection:test :autotrade-sim:test --rerun-tasks`를 4번 돌림. 3번은 BUILD SUCCESSFUL. **첫 번째 1번은 `-q`로 돌려 "BUILD FAILED in 1m 7s"만 남고 원인 출력이 없었다**(아래 "점검표 밖 발견" O1). 그 뒤 같은 명령 `-q` 2번, 출력 전체 1번, 전체 빌드 1번은 모두 통과.
  - 실전 경로 변경 범위: `git diff origin/main...HEAD --name-only`에서 `autotrade-risk`, `autotrade-app-engine`, `autotrade-broker-*`, `autotrade-domain`, `autotrade-config`, `autotrade-control` 변경 0건. 보호 테스트 중 바뀐 것은 `ArchitectureTest.java` 하나.
  - 실전 엔진 영향 확인: `grep -rln com.autotrade.strategy autotrade-app-engine/src autotrade-risk/src autotrade-control/src` 0건, 엔진·risk·control·config·domain·broker·gateway·watchdog 운영 코드에 `TradingStyle`·`ProfileYaml` 사용 0건.
  - 비밀값: `git diff origin/main...HEAD`의 추가 줄을 `appkey|app_key|secret|password|token|api.?key|account.?no|계좌번호 모양 숫자`로 검색 → architect 원문 안의 검색식 인용 1줄뿐. 새 운영 코드에 `java.io`·파일 읽기·`getenv`·스레드 생성 0건(`autotrade-selection/src/main`, `autotrade-sim/.../selection`).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 로딩(`autotrade-risk`, `autotrade-config`, `autotrade-app-engine`) 변경 0건. 기존 Guard 테스트(autotrade-risk 55개, app-engine 48개) `--rerun-tasks`로 통과 |
| A2 | 통과 | 주문 출구 변경 없음. `ArchitectureTest`는 넓히기만 했다: `PURE`에 `com.autotrade.selection..` 추가(`ArchitectureTest.java:63`), `DOMAIN_IS_INNERMOST`(`:91-96`)·`REUSABLE_JARS_ARE_DOMAIN_FREE`(`:104-110`) 금지 목록에 추가. 지운 패키지·규칙 0건(diff는 추가와 줄바꿈만). 새 sim 필터는 주문을 만들지 않고 신호를 버리기만 하며, 통과한 신호는 기존 `submit` → `RiskChain` → 가상 거래소 경로 그대로(`SimulationExperimentRunner.java:277-285`, 신호 루프). autotrade-architecture-test 29개 통과 |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 0건. 새 모듈 `autotrade-selection`은 domain·strategy만 의존(`autotrade-selection/build.gradle.kts:8-11`), broker 의존 없음 |
| A4 | 해당 없음 | 타임아웃·UNKNOWN·조회 로직 변경 0건. 실험실 리플레이 쪽에서도 `submit`/상태머신 코드는 diff 밖 |
| A5 | 해당 없음(실전) / sim 확인 | 실전 멱등 키 경로 변경 0건. sim에서 선정 밖 진입은 `SimOrderKeys`를 쓰지 않고 버려지므로(`SimulationExperimentRunner.java:280-283`) 키 중복이 생기지 않는다. Run마다 키 발급기를 새로 만든다(`:258`) |
| A6 | 통과 | 블랙리스트·수동 보유 필터 코드(`RiskChain`) 변경 0건이고 sim에서도 선정 필터 **뒤**에 그대로 탄다(선정 필터는 추가 제한일 뿐 우회 경로가 아님). 청산·손절은 `OrderIntent.isProtective()` 허용 목록(`OrderIntent.java:19-21`)으로 판정해 선정과 무관하게 나가고, 새 진입성 의도가 생기면 기본으로 걸러진다(`SimulationExperimentRunner.java:278`). 테스트: `SelectionCombinationRunnerTest#결과가_없는_날은_신규_진입만_막고_청산은_그대로_한다`, `#같은_전략이라도_선정이_다르면_시드가_다르고_선정_밖_신규_진입은_막힌다` 통과 |
| A7 | 해당 없음 | 실전 주문 큐·토큰 버킷·킬스위치 변경 0건. sim Run 간 공유는 불변 선정 표뿐(`SelectionSchedule`, Run 시작 전 호출 스레드에서 생성, `SimulationExperimentRunner.java:141,211-220`), Run 하나의 예외는 그 Run만 FAILED(`:298-304`) |
| A8 | 통과 | 실전 엔진 `processIsolation`은 그대로 `:autotrade-sim`·`:autotrade-app-lab` 금지(`autotrade-app-engine/build.gradle.kts:12`), 엔진은 selection을 아직 의존하지 않는다. 게이트웨이·감시는 `:autotrade-selection`을 명시 금지로 추가(`autotrade-app-gateway/build.gradle.kts:12`, `autotrade-app-watchdog/build.gradle.kts:12`, ADR-007 2.7 risk-ops G3) → 강화. 실험실 부하 쪽은 조합 수 상한 128로 시작 거부(`SimulationExperimentRunner.java:92,167-170`, `#조합_수가_상한을_넘으면_시작하지_않는다`: 129 거부·128 허용). 정지 순서(broker-and-fault-design.md 5.2) 변경 없음. 모든 `verifyProcessIsolation` 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절·보유 종목 감시 코드 변경 0건. sim에서도 선정 결과가 없거나 공급자가 실패한 날 보호 주문은 그대로 나간다(A6 테스트) |
| A10 | 통과 | 위 비밀값 검색 0건(검토 원문 인용 제외). 공급자 예외는 클래스 이름만 판정 사유로 쓰고 메시지를 남기지 않음(`SelectionSchedule.java`, architect 원문 B8과 같은 결과를 직접 확인) |
| A11 | 통과 | 보호 테스트 삭제·`@Disabled`·assume 0건. `ArchitectureTest` 변경은 금지 범위 확대만(A2). 테스트 파일에서 지운 줄은 호출부 교체(`new ExperimentSpec` → `ExperimentSpec.ofProfiles`)뿐. 새 동작마다 실패 경우 테스트: 선정 밖 진입 차단, 공급자 없음 시작 거부(`#선정_있는_조합에_공급자가_없으면_시작하지_않는다`), 상한 초과 거부, 시장·스타일 불일치 거부(`CombinationTest`), 갱신 주기-스타일 불일치 거부(`SelectionProfileTest`), MALFORMED/HASH_MISMATCH/NOT_YET/EXPIRED/MISSING 판정(`SelectionScheduleTest`) |
| A12 | 통과 | 장애 대응표 #1~#17, O1~O8, 장애 테스트 8종의 대상 코드(게이트웨이·엔진·broker·risk·watchdog) 변경 0건이고 전제(프로세스 4개 격리, 엔진의 sim·lab 미의존, 계좌별 격리)를 바꾸지 않는다. 대응표·테스트 변경 필요 없음. 단발 일괄 명령의 정지 순서 위치(Q-041)는 이 PR이 아니라 `autotrade-app-selection` 생산 일괄 명령 PR 몫(D-049 ⑨) |
| A13 | 통과 | 거래일은 `MarketRules.tradingDate(exchangeTime)`로만 계산(`SimulationExperimentRunner.java:217`, 기존 루프와 같은 방식), 시장 분기 추가 없음. 선정과 전략 프로필의 시장 불일치는 조합 생성에서 거부(`Combination.java:35-38`), 실험 시장은 조합 목록에서 하나로 강제(`ExperimentSpec.market()`). 새 금액 계산 없음 |
| A14 | 통과 | D-041(조합·POSITION, 실전 활성화·Guard·블랙리스트·리스크 한도 불변), D-049 ①~⑥(새 순수 모듈, 해시, `TradingStyle.POSITION` 끝에 추가, 조합 목록, 시드 v1/v2)와 코드가 일치(`docs/decisions/README.md:46,54`, `TradingStyle.java`, `RunSeeds.java`). 실전 쪽 결정(D-049 ⑧ 엔진 장전 고정·없으면 신규 진입 0건, ⑨ 생산 일괄 명령)은 이 PR 범위 밖이고, sim의 "결과 없는 날 신규 진입 0건·청산 유지"는 ⑧과 같은 방향. 조합 수 상한 128은 코드 문서에 `[가정]`과 설정 키 이전 후속으로 표시됨 |

## 점검표 밖 발견

### 관찰(차단 아님)
- **O1 원인 미상 1회 빌드 실패.** 위 5개 모듈 테스트를 `--rerun-tasks -q`로 처음 돌렸을 때 "BUILD FAILED in 1m 7s"가 났고 `-q`라 원인이 남지 않았다(그 실행 뒤 결과 XML도 다음 실행에서 지워짐). 같은 명령 3번과 전체 빌드 `--rerun-tasks` 1번은 모두 통과했고, 실패 0건 집계를 확인했다. 직전에 끝난 백그라운드 빌드와 Gradle 잠금이 겹친 것으로 보이나 `[추론]`이다. 흔들리는 테스트(특히 `SelectionCombinationRunnerTest#두_번_돌려도_스레드_수를_바꿔도_…`, 병렬 실행)일 가능성을 배제하지 못하므로 CI에서 같은 테스트가 다시 실패하면 sim-engine이 로그를 남겨 확인할 것. 실전 경로 코드는 이 PR에서 바뀌지 않아 실전 판정에는 영향이 없다.
- **O2 실전 엔진 읽기 경로(배치표 5번) 때 risk-ops 재검토 필수 항목.** (a) 엔진이 `autotrade-selection`을 의존하게 될 때 `selection.calc`(marketdata 의존, 무거운 계산)가 엔진 클래스패스·실행 경로에 들어오지 않도록 엔진은 결과 파일 읽기·판정만 쓰게 할 것(D-049 ⑧ 크기·범위 상한, 시간 예산, 계좌별 예외 격리). (b) 선정 판정이 진입만 거르고 보호 주문(`isProtective`)은 절대 막지 않는다는 것을 엔진 쪽 테스트로도 둘 것. (c) `SimulationExperimentRunner` 문서의 "실전 엔진과 같은 기준"(신호일 선정) 문장이 일봉 스윙 d 종가 판단 → d+1 주문에서 실전 고정 결과와 하루 어긋날 수 있다는 architect 원문 지적에 동의한다 `[추론]`. 그 PR에서 리플레이·실시간 모의·실전의 기준일을 하나로 정할 것.
- **O3 sim의 `SelectionSource` 호출에 시간 상한이 없다.** 공급자가 멈추면 실험 시작이 멈춘다. 실험실 프로세스 안의 일이고 실전 엔진과 분리돼 있어 실전 영향은 없다. 실전 엔진 쪽에는 D-049 ⑧대로 시간 예산이 반드시 있어야 한다(O2와 함께 확인).

## 판정
승인
