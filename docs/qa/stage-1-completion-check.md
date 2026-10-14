# 1단계(뼈대) 완료 기준 대조

- 작성: pm-lead, 2026-10-14 (KST)
- 기준: main `7cb71a9`(PR #80 머지 뒤). 이 커밋의 main CI [run 38427225743](https://github.com/hwan82/autotrade-system/actions/runs/38427225743) 세 잡(Gradle build, DB schema, Secret scan) 모두 success
- 목적: 독립 QA 관문(지침 11장, D-031)에 넘길 입력. 판정은 QA가 하고 보고서는 `docs/reviews/gates/stage-1.md`에 쓴다. 단계 완료 기록(결정 로그)은 QA 승인 뒤에 한다
- 이 문서는 개발 쪽 대조표다. QA는 이 표를 확인할 주장으로 다룬다

## 1. 1단계 완료 기준

| 출처 | 내용 |
|---|---|
| `docs/team/team-design.md` 6장 1단계 | 목표 "멀티 모듈, 인터페이스, DB 스키마, 설정 체계", 주도 아키텍트, 완료 기준 **"빌드·테스트 통과, ADR 작성"** |
| `docs/spec/autotrade-spec.md` 14장 1단계 행 | 주요 요구사항 ARC-01~08, CFG-01~06, OVS-02·03(인터페이스), MD-01(의존 검사). 상태 "코드 머지(PR #7, #9). 단계 완료 판정·기록 전" |

1단계 산출 PR: [PR #7](https://github.com/hwan82/autotrade-system/pull/7)(뼈대, ADR-001, 2026-10-08 머지 by hwan82), [PR #9](https://github.com/hwan82/autotrade-system/pull/9)(RealTradingGuard 골격, ADR-002, 2026-10-08 사용자 머지). 이후 단계 PR들이 같은 계약을 넓혔다(아래 표 근거는 현재 main 기준).

## 2. 로드맵 완료 기준 대조

| 기준 | 판단(개발 쪽) | 근거 |
|---|---|---|
| 빌드·테스트 통과 | 충족 | [사실] main `7cb71a9` CI run 38427225743 success. `Gradle build` 잡이 `./gradlew build --no-daemon`(컴파일·테스트·`verifyPureDependencies`·`verifyProcessIsolation`·ArchUnit)과 4개 프로세스 기동을 수행(`.github/workflows/ci.yml:27-96`). [사실] 이 컨테이너의 로컬 `./gradlew build`는 Maven Central 429(Too Many Requests)로 의존성 해석에서 실패해 직접 실행 결과는 없다. QA가 직접 빌드로 확인해야 한다 |
| ADR 작성 | 충족 | [사실] `docs/adr/ADR-001-module-structure.md`(모듈·프로세스 4개·핵심 계약·설정 체계·실험 스키마, 대안 비교 2.1·2.6·2.7절, 위험 4장, 검증 8장), `docs/adr/ADR-002-real-trading-guard.md`(Guard 골격, 실전 계좌 키) |

## 3. 기획서 요구사항별 대조

판단 칸: **충족** = 기획서의 검증 방법이 지금 main에 자동 검사나 문서로 있음, **부분** = 일부만 있음, **이연** = 1단계 범위에서 빼고 7단계로 옮김(4장, 사용자 결정 A).

| ID | 판단 | 근거 |
|---|---|---|
| ARC-01 Java 21·Spring Boot 3·Gradle 멀티 모듈 | 충족 | `settings.gradle.kts` 모듈 22개, CI `./gradlew build` success |
| ARC-02 모듈 의존 방향 빌드 강제 | 충족 | `ArchitectureTest.java:92-111`(`PURE_MODULES_HAVE_NO_FRAMEWORK`, `DOMAIN_IS_INNERMOST`, `SIM_DOES_NOT_KNOW_BROKERS`, `REUSABLE_JARS_ARE_DOMAIN_FREE`), `:300` `PLUGINS_ARE_INDEPENDENT`, `build-logic/src/main/kotlin/autotrade.pure-java-conventions.gradle.kts:17` `verifyPureDependencies`. 위반 검출 확인 테스트 `규칙은_위반을_실제로_잡아낸다`(`:430`), 모듈 소유 검사 `ModuleOwnershipTest`(D-034 F-1) |
| ARC-03 프로세스 4개 독립 진입점, 엔진 클래스패스에 실험실·UI 없음 | 충족(1단계 기준) | `autotrade-app-engine/build.gradle.kts:13` 금지 모듈(sim·lab·ui·gateway·collector·experiment)과 Spring, `autotrade.process-conventions.gradle.kts:9` `verifyProcessIsolation`, `ArchitectureTest.java:295` `PROCESSES_ARE_INDEPENDENT`, CI "Start each process once (ARC-03)"(`ci.yml:29`). 7단계 기준(부하·강제 종료 중 보호)은 범위 밖 |
| ARC-04 `Clock` 주입만 | 충족 | `ArchitectureTest.java:129` `NO_SYSTEM_TIME`, 위반 픽스처 `archfixture/ForbiddenTimeAndRandom.java`, `ReplayClockTest` |
| ARC-05 핵심 인터페이스 계약 문서화 | 충족, 문구 1곳 낡음 | ADR-001 2.3절 표(9개 계약). 단 `ExperimentRunner` 행 "Run 시드는 실험 시드와 프로필 해시로 파생"은 ADR-007 이후 선정 있는 조합에서 `combinationHash`로 파생(`RunSeeds.java` v2)으로 바뀌었다. 코드 Javadoc(원본)은 맞고 ADR-001 요약 표만 옛 문구다(5장 G3) |
| ARC-06 수집은 별도 일괄 명령 | 충족 | `ArchitectureTest.java:232` `COLLECTOR_READS_ONLY_ITS_SECRETS`, `autotrade-app-collector/build.gradle.kts:17` 금지 모듈, CI `check-python-collectors.sh`(`ci.yml:155`) |
| ARC-07 DB 마이그레이션, 1단계 테이블 5개 | 충족 | `V1__lab_baseline.sql`의 `experiment`·`experiment_run`·`virtual_account`·`virtual_trade_log`·`equity_curve`(+`strategy_profile`), CI "Apply migrations to an empty DB in order (ARC-07)"(`ci.yml:127`)와 `scripts/ci/schema-smoke.sql` |
| ARC-08 결정론 코딩 규칙 | 충족 | `ArchitectureTest.java:144` `NO_UNSEEDED_RANDOM`, `:305` `NO_FLOATING_POINT_MONEY`, `:313` `NO_BIGDECIMAL_FROM_DOUBLE` |
| MD-01 게이트웨이는 주문 포트를 모름(1단계 부분) | 충족 | `ArchitectureTest.java:151` `GATEWAY_HAS_NO_ORDER_PORT`, `autotrade-app-gateway/build.gradle.kts:12` 금지 모듈. 런타임·계측 부분은 2단계(D-067 완료) |
| CFG-01 설정 계층·Write-Through | 충족 | `ConfigLoaderTest` `우선순위는_default_다음_runtime_config_다음_secrets다`, `파일이_없으면_내장_default로_기동한다`, `문법이_깨진_파일이면_default로_기동하고_경고한다`, `RuntimeConfigHolderTest` `변경은_디스크에_먼저_쓰고_재기동하면_그대로_읽힌다`, `저장에_실패하면_메모리도_바뀌지_않는다` |
| CFG-02 재기동 시 실전 OFF | 충족 | `ConfigLoaderTest` `실전_활성화_키가_있는_파일은_받아들이지_않는다`, `설정_스냅샷에는_실전_활성화_상태가_없다`, `RealTradingGuardTest`(RSK-01·CFG-02), `EngineMainTest:69`. 환경변수 계층은 설정 로더가 환경변수를 키로 읽지 않아(ADR-001 2.6) 해당 없음 `[추론]` |
| CFG-03 비밀값 분리·스캔·마스킹 | 충족 | `ConfigLoaderTest` `일반_설정_파일에_비밀값이_있으면_그_파일을_쓰지_않는다`, `SecretTest` `설정_전체를_로그로_찍어도_비밀값이_나오지_않는다`, CI gitleaks 전 이력(`ci.yml:153`) |
| CFG-04 증권사·계좌 키 구조 ADR | 충족 | ADR-001 2.6·2.6.1, ADR-002 2.4(`account-numbers` 맵으로 수정), `docs/config/config-keys.md` |
| CFG-05 운영 중 키·증권사 교체 규칙 | **부분 → 이연(사용자 결정 A, 4장)** | 있는 것: 실시간 변경 거부(`RuntimeConfigHolderTest` `시세_구성과_증권사_키는_실시간으로_바꿀_수_없다`, `실전_계좌는_실시간으로_바꿀_수_없다`, `RuntimeConfigHolder.java:46` 메시지 "CFG-05"). 없는 것: 기획서 검증 방법 "미체결이 있을 때 키 교체 거부 테스트, 보유가 있을 때 증권사 교체 거부 테스트". 미체결·보유는 실전 주문 경로가 있어야 판단할 수 있다(ADR-001 2.6 "별도 경로로 연다") |
| CFG-06 설정 3분류 | **부분 → 이연(사용자 결정 A, 4장)** | 있는 것: 전략 프로필은 새 버전으로만 변경(`StrategyProfileStoreTest` `편집은_새_버전과_새_해시를_만들고_옛_버전은_그대로다`, `이미_있는_버전_파일은_덮어쓰지_않는다`), 프로필 해시 골든(`StrategyProfileTest`). 없는 것: ① "실행 중 Run 파라미터 변경 시도 실패 테스트". 설계로는 `StrategyProfile`이 record이고 파라미터를 생성 때 복사하며(`StrategyProfile.java:25,41`), `ExperimentSpec`도 record로 조합 목록을 `List.copyOf`로 복사한다(`ExperimentSpec.java:34,59`). 그러나 이를 확인하는 실패 테스트는 없다 ② 운영 설정 변경의 감사 로그(`autotrade-config`에 감사 기록 없음) ③ "2차 확인 없는 실전 한도 변경 거부 테스트". 실전 한도 키는 Q-008(미확정)이 정해져야 생긴다(`RuntimeConfig.java:23` "리스크 한도 키는 Q-008 이후 별도 작업") |
| OVS-02 시장 차이는 `MarketRules`로만(인터페이스) | 충족(인터페이스), 검사는 수동 | `MarketRules` 계약(ADR-001 2.3), `MarketRulesTest`, `MarketRulesImplTest`. 기획서 검증 "엔진 코드에 시장 분기 없음(검사)"은 자동 검사가 없다. 수동 확인: `Market.KRX`·`Market.US` 직접 참조는 sim의 실험 명령 입구(`CombinationExperiment.java:112`, `WalkForwardStudy.java:97`, `RecordingPlan.java:97`, KRX 전용 거절)와 비용 표(`CostModels.java:73,99`), 합성 시세(`synthetic`), 그리고 sim 밖 두 곳이다. `DailyBarQualityCheck.java:222`는 일봉 품질 검사의 가격 급변 임계를 `MarketRules`를 거치지 않고 시장별로 고르는 **실제 시장 분기**이고, `SelectionCriteria.java:231`은 재무 지표를 KRX에서만 받는 입구 거절이다. `exchange`·`account`·`run`, `autotrade-risk`, `autotrade-app-engine`에는 없다(`grep -rn "Market\.KRX\|Market\.US"`, PR #81 architect 검토 반영). 5장 G4 |
| OVS-03 거래소 현지 시간대, 서머타임 하드코딩 금지 | 충족 | `ExchangeCalendarTest` `미국_정규장은_서머타임을_시간대_데이터로_따른다`, `미국_장은_한국_날짜로_이틀에_걸쳐도_현지_날짜로_판단한다`, `MarketRulesImplTest` `거래일과_시간대는_시장_현지_기준이다` |

## 4. 이연 제안 (사용자 결정: A)

CFG-05 교체 절차와 CFG-06 실전 한도 2차 확인은 1단계 뼈대로는 만들 수 없는 것이다. 미체결·보유 판단은 실전 주문 경로가, 실전 한도 2차 확인은 Q-008 한도 키가 있어야 한다. CFG-06의 나머지 두 빈칸(Run 불변 실패 테스트, 운영 설정 감사 로그)은 지금 만들 수 있으므로 이연 대상이 아니라 1단계 후속 과제(architect)로 둔다.

| 선택지 | 내용 | 비고 |
|---|---|---|
| **A (추천)** | 1단계 완료 범위를 "실시간 변경 거부와 프로필 버전 규칙까지"로 보고, CFG-05 교체 절차 테스트와 CFG-06 실전 한도 2차 확인 테스트는 7단계 요구사항으로 옮긴다(기획서 14장 7단계 행에 CFG-05·06 추가). Run 불변 실패 테스트와 운영 설정 감사 로그는 1단계 후속 과제로 남긴다 | 1단계 기준(빌드·테스트·ADR)은 그대로. 약화되는 안전 규칙 없음: 교체·한도 변경 경로 자체가 아직 없고, 있는 경로는 거부한다 |
| B | CFG-05·06 테스트가 생길 때까지 1단계를 미완료로 둔다 | 7단계 실전 경로 작업 전까지 1단계가 닫히지 않는다 |

이 해석은 로드맵 완료 기준의 해석이라 3단계 QA(C3, D-068 ④)와 같은 이유로 사용자 확인 대상으로 두었다.

**사용자 결정: A.** 출처: 프로젝트 스레드 사용자 BAE 2026-10-14T00:24:24Z "A안으로 합니다." 결정 번호는 QA 판정 뒤 완료 기록 PR에서 부여한다. 7단계 쪽으로 옮기는 기획서 수정은 QA 판정 뒤 완료 기록 PR에서 하고, 실전 요구사항의 검증 시점을 바꾸므로 그 PR은 risk-ops 검토를 받는다(PR #81 architect B7 메모).

## 5. QA 전에 알려 두는 차이 (판정 뒤 완료 기록 PR에서 정리 제안)

| # | 내용 | 근거 |
|---|---|---|
| G1 | PR #7·#9의 역할 검토 원문이 `docs/reviews/`에 없다. 원문 보존 규칙(D-030, 2026-10-09)이 두 PR 머지(2026-10-08) 뒤에 생겼다. 교차 검토 반영은 ADR-001 7장(risk-ops·sim-engine·data-engineer)과 ADR-002 8장(architect 2회, 차단 1건 반영 후 승인)에만 요약으로 남아 있다. 두 PR 모두 사용자(hwan82)가 머지했다 | `ls docs/reviews/` 가장 이른 폴더 `pr-10`, PR #7·#9 본문 |
| G2 | ADR-001 6장 "추천안 채택(자율)" 10건에 결정 로그 번호가 없다("pm-lead가 다음 정리 때 부여"). 결정 로그 미확정 표에 Q-009(DB 엔진)가 아직 열려 있으나 ADR-001이 PostgreSQL 16으로 닫았다(ADR-001:8, 2.7절) | `docs/decisions/README.md:85`, ADR-001 245-258행 |
| G3 | ADR-001 2.3절 `ExperimentRunner` 시드 문구가 ADR-007 이전 내용 | `RunSeeds.java` Javadoc |
| G4 | OVS-02 "시장 분기 없음" 자동 검사 없음(수동 grep만). `DailyBarQualityCheck.java:222`의 시장별 급변 임계는 `MarketRules`로 옮길 후보 | 3장 OVS-02 행 |
| G5 | 결정 로그에 D-029 번호가 비어 있다(D-028 다음 D-030). 저장소 어디에도 D-029 참조가 없다 | `grep -rn D-029 docs` 결과 없음 |

G2는 1단계 완료 기록 PR에서 ADR-001 자율 결정을 한 번호로 묶어 기록하고 Q-009를 닫는 것을 제안한다. G3·G4는 architect 후속 과제로 넘긴다.

## 6. 다음 단계를 막는 미확정

2단계(D-067)·3단계(D-070)는 이미 완료됐다. 1단계 결과에 기대는 열린 질문 가운데 1단계 완료를 막는 것은 없다 `[추론]`. CFG-06 실전 한도는 Q-008(risk-ops)이 정한다.

## 7. QA 요청

QA 프로젝트에 붙여 넣을 문구: **main 기준으로 1단계 완료 판정해줘**

같은 줄을 `docs/qa/pending.md`에 넣었다. 보고서 위치 `docs/reviews/gates/stage-1.md`.
