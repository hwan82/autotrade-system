# QA 검토: PR #19 실험 실행기에 가상·실전 공통 리스크 검증 체인(RiskChain) 적용 (D-032, D-035)
- 대상: PR #19 / 커밋 `98cc10a` / main(`52ad85f`) 대비 전체 diff(코드 14파일 + 문서). 병합 기준 커밋 `2dcea43` 이후 main에 들어온 것은 QA 보고서 1건(#20, 문서)뿐이라 diff에 영향 없음
- 점검표: A 전체 + B1, B6 + 프로젝트 지침 8장 과금 검사
- 검토자: 독립 QA 프로젝트("자동매매 시스템 QA"), 2026-10-09
- 직접 실행한 것:
  - `./gradlew clean build --offline` (커밋 `98cc10a`) → **BUILD SUCCESSFUL**. 테스트 320건, 실패 0, 오류 0, 건너뜀 0. 이 PR과 보호 테스트 결과: `RiskChainTest` 9/9, `BlacklistSnapshotTest` 5/5, `ArchitectureTest` 13/13, `SimulationExperimentRunnerTest` 9/9, `StrategyStudyTest` 4/4, `RealTradingGuardTest` 31/31, `GuardedOrderGatewayTest` 8/8, `RealOrderGatewaysTest` 4/4, `EngineMainTest` 3/3.
    - 실행 환경 메모: Maven Central이 `429 Too Many Requests`를 돌려 처음 몇 번은 의존성 해석에서 실패했다. 저장소 파일을 바꾸지 않고 같은 명령을 재시도해 의존성 캐시를 채운 뒤 `clean build --offline`으로 처음부터 다시 빌드했다.
  - GitHub check-runs(`98cc10a`): `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary` success, `Gradle build`는 검토 시점에 in_progress(판정 근거는 위 직접 실행).
  - ArchUnit 우회 실험 3건(커밋하지 않음, 실험 뒤 삭제하고 `git status` 깨끗함 확인): 아래 F-1.
  - diff 전체에서 HTTP·URL·소켓·클라이언트·증권사 이름 grep: 운영 코드 추가분에 외부 호출 없음.

## 전제
- [사실] 이 PR의 운영 코드 변경은 `autotrade-risk`(새 클래스 4개)와 `autotrade-sim`(생성자 인자 타입, `StudyMain`)뿐이다. `RiskChain`·`BlacklistSnapshot`을 쓰는 실전 엔진 코드는 없다(`grep -rn "RiskChain\|RiskPolicy" --include=*.java` 운영 코드 결과: sim 3파일뿐). Guard·출구·`OrderGateway`·설정 파일은 바뀌지 않았다.
- 그래서 실전 주문 동작을 바꾸는 부분은 없고, A 점검표 중 실전 경로 미구현 항목은 이 PR이 건드리지 않으므로 "해당 없음"으로 적었다(main 전체 상태는 `docs/reviews/gates/main-realpath-2026-10-09.md`).

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard`·설정·Guard 테스트 파일이 diff에 없다(`git diff --name-only origin/main...98cc10a`). `RealTradingGuardTest` 31/31(재기동 OFF 포함), `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과 |
| A2 | 통과 | `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`·`ONLY_COMMAND_CHANNEL_RAISES_GUARD` 등 기존 ArchUnit 규칙은 바뀌지 않았고 새 규칙 1개만 더해졌다(diff는 추가만, `ArchitectureTest.java:159-162, 231-234, 271-273`). 기존 위반 픽스처 검출 수 단언(4건, 6건)도 그대로 |
| A3 | 해당 없음 | 증권사 선택·주문 전송 코드 변경 없음. `RiskChain`은 주문을 보내지 않고 판정만 돌려준다(`RiskChain.java:47-76`) |
| A4 | 해당 없음 | 타임아웃·재전송 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 발급 변경 없음. 실행기는 기존대로 `keys.next(...)` 후 판정(`SimulationExperimentRunner.java:241-244`) |
| A6 | 통과 | 블랙리스트·수동 보유 필터가 의도(ENTRY·EXIT·STOP_LOSS)와 상관없이 진입 규칙보다 먼저 거절한다(`RiskChain.java:53-57`, 보호 주문 통과는 그 뒤 `:58`). 블랙리스트 없는 체인은 생성 불가(`:43` null 거부, 생성자 private, 클래스 final). 실행기·스터디는 `RiskChain` 타입만 받는다(`SimulationExperimentRunner.java:84`, `StrategyStudy.java:64`). 테스트: `RiskChainTest#제외_종목은_진입_청산_손절_모두_막는다`, `#수동_보유_종목은_다른_규칙_ID로_막는다`, `#블랙리스트는_진입_규칙보다_먼저다`, `#블랙리스트_없는_체인과_체인_중첩은_만들_수_없다`, `SimulationExperimentRunnerTest`(HYNIX 거절 `BLACKLIST`, `:102`) 통과. 수동 명령·재기동 복구 경로는 아직 코드가 없다(이 PR 범위 밖) |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음. `RiskChain`·`BlacklistSnapshot`은 불변(`List.copyOf`, `unmodifiableSortedMap`)이라 여러 계좌가 공유해도 상태를 섞지 않는다(`BlacklistSnapshotTest#만든_뒤_입력을_바꿔도_스냅샷은_그대로다`) |
| A8 | 해당 없음 | 프로세스 경계·의존 변경 없음. `PROCESSES_ARE_INDEPENDENT`, 프로세스 격리 검사 통과(build) |
| A9 | 통과 | 실전 보호 경로 코드 변경 없음. 진입 규칙은 청산·손절을 막을 수 없고, 규칙 오류도 손절을 막지 않는다(`RiskChainTest#진입_규칙은_청산과_손절을_막지_못한다`, `#규칙_오류는_진입을_닫는다`). 블랙리스트 종목의 보유분 손절 차단은 사용자 결정 D-035의 의도된 예외이며, 이 체인을 쓰는 실전 코드가 아직 없어 지금 실전 보호가 약해지지는 않는다. 단, 위험 R-1 참고 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(grep). 거절 사유에는 종목과 내부 계좌 별칭만, 규칙 예외는 클래스 이름만 남긴다(`RiskChain.java:51, 66`). CI gitleaks success |
| A11 | 통과 | 보호 테스트 중 `ArchitectureTest`만 바뀌었고 추가뿐이다(규칙 1개, 테스트 1개, 위반 검출 단언 1개). `RealTradingGuardTest`·`GuardedOrderGatewayTest` 변경 없음. `@Disabled`·`assume` 0건, 건너뜀 0건. 삭제된 `TestBlacklist`는 보호 테스트가 아닌 테스트 도우미이고 같은 검사(HYNIX 거절)가 `RiskChain`으로 대체됐다. 이름이 바뀐 `진입_규칙은_모든_모의_진입에_적용된다`는 단언이 이전과 같다. 새 동작마다 실패 경우 테스트가 있다(RiskChainTest 9건). 단, 새 ArchUnit 규칙이 못 잡는 우회가 있다(F-1) |
| A12 | 통과 | 실전 주문 동작이 바뀌지 않아 장애 대응표·장애 테스트 8종의 전제를 깨는 변경이 없다. `broker-and-fault-design.md`에 블랙리스트·손절 관련 전제 문구 없음(grep). D-035가 7단계 전에 "보유 종목이 블랙리스트에 오름" 대응표 항목을 추가하기로 적었다(`D-035` 마지막 표) |
| A13 | 통과 | `Symbol`은 시장을 포함해 비교·정렬한다(`Symbol.java` `ORDER` = market → code). 블랙리스트 지문에도 시장이 들어간다(`BlacklistSnapshot.java` `digest`). `BlacklistSnapshotTest#종목이나_유형이_다르면_지문이_다르다`(KRX:000660 ≠ US:000660) 통과. 시간·금액 계산 없음 |
| A14 | **실패** | D-032가 이 PR이 "지금 보장하는 것"으로 적은 "(1) 규칙 코드가 리스크 모듈 한 곳에만 있음"(`D-032-shared-risk-chain.md:39`)과 보호 테스트 이름 `리스크_규칙은_리스크_모듈에만_있다`가 실제 규칙과 다르다. 규칙은 **패키지**(`com.autotrade.risk..`)만 본다(`ArchitectureTest.java:161`). 다른 모듈(`autotrade-sim`)에 `package com.autotrade.risk;`로 승인만 하는 `RiskPolicy`를 두면 규칙이 통과한다(F-1 실험 E1). 나머지(체인 순서, D-035 예외, 기획서 v13.1 공통 원칙 1·RSK-12·UI-05, ADR-001·004 계약 표)는 코드와 일치함을 대조했다 |

## 점검표 B (B1, B6)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 "직접 실행한 것": `./gradlew clean build --offline` BUILD SUCCESSFUL, 320건 실패 0·건너뜀 0 |
| B6 | 통과 | 새 동작마다 테스트가 있다: 블랙리스트 모든 의도 차단, 유형별 규칙 ID, 순서, 보호 주문 통과, 첫 거절에서 멈춤, 규칙 예외·null은 진입 거절, 계좌 불일치 거절, null 블랙리스트·체인 중첩 거부, 지문 순서·사유 무관, 유형 병합(AUTO_ASSET 우선), 불변성. 테스트 삭제·건너뜀 없음(A11) |

## 점검표 밖 발견

### F-1. ArchUnit `RISK_POLICIES_LIVE_IN_RISK_MODULE`이 패키지만 보고 모듈은 보지 않는다 (A14 실패 근거)
`autotrade-sim/src/main/java`에 임시 파일을 두고 `:autotrade-architecture-test:test --tests '*ArchitectureTest'`를 돌렸다(실험 뒤 삭제).

| 실험 | 넣은 코드 | 결과 |
|---|---|---|
| E1 | `autotrade-sim`에 `package com.autotrade.risk; public final class QaSplitPackageApprove implements RiskPolicy { ... return RiskDecision.approve(); }` | **통과(못 잡음)** |
| E2 | `autotrade-sim`에 `public static final RiskPolicy APPROVE = (o, a) -> RiskDecision.approve();` | **통과(못 잡음)**. D-032 위험과 한계에 이미 적혀 있음 |
| E3 | `autotrade-sim`에 익명 클래스 `new RiskPolicy() { ... approve() }` | 실패(잡음). `리스크_규칙은_리스크_모듈에만_있다() FAILED` |

- [사실] 지금 이 우회로 블랙리스트를 건너뛸 수는 없다. 실행기는 `RiskChain`(final, private 생성자)만 받고, 바깥 정책은 `entryRule`로만 들어가 블랙리스트 뒤·진입에만 적용된다.
- [추론] 위험은 7단계다. 실전 주문 파이프라인이 `RiskPolicy` 인터페이스를 받는 자리를 하나라도 만들면, 다른 모듈의 같은 패키지 클래스나 람다로 승인만 하는 정책이 들어가도 이 보호 테스트는 초록이다. 테스트 이름과 D-032 문구가 "모듈" 보장을 약속하므로 검토자가 이를 믿고 넘어갈 수 있다.
- 고칠 점(둘 중 하나): (가) 규칙을 클래스 출처(모듈) 기준으로 강화한다. 예: `RiskPolicy` 구현 클래스의 `getSource()` 위치가 `autotrade-risk` 산출물인지 검사하고, `com.autotrade.risk..` 패키지 클래스가 다른 모듈에 있으면 실패시키는 규칙과 그 위반 픽스처 테스트. (나) 규칙을 그대로 두면 D-032 "지금 보장하는 것 (1)"과 테스트 이름·주석을 "패키지"로 고치고, 람다·분할 패키지 우회를 7단계 "체인 밖 submit 금지" 작업의 선행 조건으로 적는다. QA 추천은 (가)다(보호 테스트가 약속과 같은 것을 검사해야 한다).

### F-2. D-035 "사용자 결정"의 출처를 QA가 확인할 수 없다
- D-035는 다른 프로젝트 스레드의 결정 카드(2026-10-09 01:42:55Z, 선택지 B)를 출처로 든다. QA 프로젝트에서는 그 카드를 읽을 수 없다. D-035 본문과 risk-ops 재검토(5)도 머지하는 사용자에게 직접 확인을 요청한다.
- 사용자가 실제로 고른 것이 B("지금처럼 차단")가 아니면 D-035, 기획서 v13.1, ADR-001 표가 틀린 기록이다. 머지 전에 사용자가 직접 확인해야 한다.

## 위험 시나리오 (잘못된 주문·중복 주문·보호 공백)

| # | 시나리오 | 결과 |
|---|---|---|
| R-1 | 시스템이 보유 중인 종목을 사용자가 블랙리스트에 올리거나, 사용자가 다른 계좌에서 같은 종목을 직접 사서 AUTO_ASSET으로 자동 등록된 뒤 가격이 급락 | **보호 공백**. 체인이 손절·청산을 모두 거절한다(`RiskChain.java:53-57`). D-035 사용자 결정의 의도된 동작이고, 대신 둘 경고(MANUAL 등록 화면 경고, AUTO_ASSET 긴급 알림)는 **아직 구현이 없다**. 지금은 이 체인을 쓰는 실전 코드가 없어 실제 영향은 없다. 7단계 실전 파이프라인에 이 체인을 연결하는 PR은 경고·긴급 알림 구현과 발생 테스트가 같이 들어오지 않으면 A9 실패로 봐야 한다 |
| R-2 | 배선 오류로 주문 계좌와 다른 계좌 스냅샷이 체인에 들어감 | 모든 의도(손절 포함)가 `RISK_RULE_ERROR`로 값 거절된다(`RiskChain.java:50-52`, `RiskChainTest#다른_계좌의_스냅샷으로는_판정하지_않는다`). 잘못된 주문은 막지만 손절이 조용히 막힌다. 실험에서는 거절 기록만 남는다. D-032 변경 제안(실전에서는 예외·긴급 알림)으로 남아 있고 7단계 전에 닫아야 한다 |
| R-3 | 진입 규칙(예: 저장소 장애)이 계속 예외를 던짐 | 진입만 닫히고 손절·청산은 나간다(`#규칙_오류는_진입을_닫는다`). 잘못된 진입은 없지만 신규 진입이 조용히 모두 막힌다. 오류 누적 알림 연결은 D-032 변경 제안으로 남음 |
| R-4 | 녹화 데이터 실험이나 실전 경로가 `BlacklistSnapshot.EMPTY`로 체인을 만듦 | 블랙리스트가 꺼진 채 돈다. `EMPTY`는 public이고 막는 장치가 없다. 실험 메타에 블랙리스트 지문을 기록하는 코드도 아직 없어(RSK-12), 결과만 보고 EMPTY로 돌았는지 알 수 없다. `StudyMain`은 합성 종목 전용이라 지금은 영향 없음(`StudyMain.java:81-84` 주석). D-032 변경 제안(지문 기록, 실전 EMPTY 금지) |
| R-5 | 전략이 BUY에 EXIT 의도를 붙여 진입 규칙을 건너뛰려 함 | 막힘. `OrderRequest` 생성자가 매수+보호 의도를 거부한다(`OrderRequest.java` `intent.isProtective() && side == Side.BUY`). 보유 초과 EXIT는 가상 거래소가 `INSUFFICIENT_POSITION`으로 거절(`VirtualExchange.java:243`) |
| R-6 | 같은 종목이 MANUAL·AUTO_ASSET 두 유형으로 들어옴(입력 순서 바뀜) | 어느 순서든 AUTO_ASSET 하나로 합쳐지고 지문이 같다(`BlacklistSnapshotTest#같은_종목이_두_유형이면_수동_보유를_남긴다`). 중복·누락 없음 |

## 과금 검사 (프로젝트 지침 8장)
범위: 이 PR의 main 대비 전체 diff와 그 diff가 호출하거나 영향을 주는 코드. main 전체의 기존 과금 항목 판정(토스 요금 미확인, 우선순위 기본값 등)은 `docs/reviews/gates/main-realpath-2026-10-09.md` 과금 검사에 있고, 이 PR이 바꾸거나 새로 만들지 않았다.

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 8.1 | 유료 API 호출 경로 식별 | 통과 | diff의 운영 코드에 외부 호출이 없다. 새 클래스가 쓰는 외부 자원은 JDK `MessageDigest`(SHA-256, 로컬 계산)뿐(`BlacklistSnapshot.java` `digest`). 바뀐 sim 코드는 생성자 인자 타입과 람다→`RiskChain` 교체뿐이고, 호출 대상(`RiskChain.evaluate`)은 순수 계산이다. 유료 호출 지점 0건 |
| 8.2 | 필요성과 대체 수단 | 통과 | 유료 호출이 없으므로 대체 검토 대상 없음(8.1 근거) |
| 8.3 | 사용자 옵션 제어(기본 꺼짐) | 통과 | 설정 키·설정 파일 변경 없음(`git diff --name-only`에 `*.json`, `config` 없음). 새로 켜지는 외부 경로 없음 |
| 8.4 | 폭주 방지 | 통과 | 새 반복·재시도·복구 경로가 없다. 체인은 진입 규칙을 등록 수만큼 한 번씩 평가하고 첫 거절에서 멈춘다(`RiskChain.java:63-75`). 예외 시 재시도하지 않고 거절한다 |
| 8.5 | 실주문 비용 | 통과 | 실전 주문 경로·Guard 변경 없음, 이 체인을 쓰는 실전 코드 없음(전제). 비용 모델(`CostModel`) 변경 없음. 실험 성과는 기존대로 비용 차감 후 계산되고, 체인 교체로 거절된 주문은 체결·비용이 생기지 않는다(`SimulationExperimentRunner.java:244-247`, 거절 시 거래소에 보내지 않음) |

## 판정
**수정 필요(A14)**

- A14: D-032 "지금 보장하는 것 (1) 규칙 코드가 리스크 모듈 한 곳에만 있음"과 보호 테스트 `리스크_규칙은_리스크_모듈에만_있다`가 실제 규칙(패키지만 검사)과 다르다. 다른 모듈의 `com.autotrade.risk` 패키지 클래스와 람다는 잡지 못한다(F-1 E1·E2 실험). F-1의 (가) 또는 (나)로 고친 뒤 재검토한다.
- 머지 전 사용자 확인: F-2(D-035 결정 출처가 실제 선택과 같은지).
- 7단계 실전 연결 전 반드시 닫을 것(이번 판정 조건 아님): R-1 경고·긴급 알림 구현과 테스트, R-2 계좌 불일치 시 크게 실패, R-4 실전 EMPTY 금지와 실험 메타 지문 기록.

## 사용자 확인 2026-10-09
- F-2: 사용자 BAE가 QA 스레드에서 2026-10-09 02:12:14Z(UTC)에 "B(\"지금처럼 차단\")을 골랐습니다."라고 답했다. D-035 결정 출처 기록(선택지 B)과 일치한다. F-2는 닫는다.
- 판정은 바뀌지 않는다(A14 수정 필요). A14 수정 커밋(`5304721`)은 재검토 요청을 받으면 아래에 `## 재검토`로 판정한다.
