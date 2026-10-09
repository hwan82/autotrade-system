# QA 검토: PR #21 QA 관문(2026-10-09) 후속: 패키지 위장 차단, OrderState 통일, 토스 기본 꺼짐, 기획서 v14
- 대상: PR #21 / 커밋 `218942a` / main(`52ad85f`, PR의 merge-base와 같음) 대비 전체 diff(30파일, +811 −47)
- 점검표: A 전체 + B1, B6 + 프로젝트 지침 8장 과금 검사
- 검토자: 독립 QA 프로젝트("자동매매 시스템 QA"), 2026-10-09
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (커밋 `218942a`) → **BUILD SUCCESSFUL**. 테스트 318건, 실패 0, 오류 0, 건너뜀 0. 보호 테스트와 이 PR 관련 결과: `ArchitectureTest` 12/12, `ModuleOwnershipTest` 8/8, `RealTradingGuardTest` 31/31, `GuardedOrderGatewayTest` 8/8, `RealOrderGatewaysTest` 4/4, `EngineMainTest` 3/3, `OrderStateTest` 4/4, `VirtualExchangeTest` 18/18, `ConfigLoaderTest` 21/21.
    - 실행 환경 메모: `repo.maven.apache.org`가 `429 Too Many Requests`를 돌려 의존성 해석이 실패했다. 저장소 파일은 바꾸지 않고, 이 검토 환경의 `~/.gradle/init.d`에서 Maven Central 주소만 Google 미러(`maven-central.storage-download.googleapis.com`)로 돌려 같은 명령을 다시 실행했다.
  - GitHub check-runs(`218942a`): `Gradle build`, `Secret scan and collector boundary`, `DB schema (PostgreSQL 16)` 모두 success.
  - 변조 실험 7건(별도 git worktree, 커밋하지 않음, 실험 뒤 worktree 삭제): 아래 "F-1·R-1 재현 확인".
  - diff와 운영 코드 전체에서 HTTP·URL·소켓·클라이언트 grep(과금 검사).

## F-1·R-1 재현 확인 (조정 세션 요청)
PR이 닫는다고 한 지적을 같은 방법으로 다시 시도했다. 각 실험은 해당 파일만 넣고 `./gradlew :autotrade-architecture-test:test` 또는 `./gradlew build`를 돌렸다.

| 실험 | 넣은 코드 | 결과 | 판단 |
|---|---|---|---|
| E1 (main QA F-1 원 재현) | `autotrade-app-lab`에 `com.autotrade.risk.guard.QaProbe`(`new SecondConfirmation(...)` → `beginActivation`) | `ModuleOwnershipTest#운영_클래스는_패키지를_소유한_모듈에만_있다` 실패: "QaProbe (autotrade-app-lab): 패키지 com.autotrade.risk.guard은 autotrade-risk 소유" | **닫힘** |
| E7 (같은 FQN) | `autotrade-app-lab`에 `com.autotrade.app.engine.order.RealOrderGateways` | 같은 테스트 실패(소유 모듈 위반) | **닫힘** |
| E6 (PR #19 QA F-1 E1 형태) | `autotrade-sim`에 `com.autotrade.risk.QaSplitPackageApprove` | 같은 테스트 실패(소유 모듈 위반). #19가 머지되면 `RISK_POLICIES_LIVE_IN_RISK_MODULE`의 분할 패키지 우회도 이 테스트가 잡는다 `[추론]`(#19 코드가 이 브랜치에 없어 `RiskPolicy` 구현으로는 시험하지 못함). #19 E2(람다)는 이 테스트 범위 밖이다 | 분할 패키지는 닫힘 |
| E4 (risk-ops R-1) | settings에만 include한 새 모듈 `autotrade-rogue`(`autotrade-risk` 의존, `com.autotrade.risk.guard.RogueRaiser`) | `#운영_클래스는...` 실패(소유 위반) + `#클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다` 실패("소유 모듈 표(OWNERS)에 없는 모듈: autotrade-rogue") | **닫힘** |
| E2 | `autotrade-app-lab`에 `package qa.probe; OutsideRaiser`: `new SecondConfirmation(a, "nobody", now)` → `beginActivation` → `completeStep` 3단계. 같은 모듈 테스트 `OutsideRaiserTest`가 실제 Guard로 실행해 `GuardState.OPEN` 단언 | **`./gradlew build` BUILD SUCCESSFUL**. `ArchitectureTest` 12/12, `ModuleOwnershipTest` 8/8 통과, `OutsideRaiserTest` 통과(사람 확인 없이 OPEN 도달). 대조군: 같은 코드를 `com.autotrade.app.lab.qaprobe`에 두면 `Guard를_올리는_조작은_명령_채널에서만_한다` 실패(3건 검출) | **우회 성공** (Q-1) |
| E3 | `autotrade-app-engine`에 `package qa.probe; OutsideOpener`: `plugin.orderGateway(account)` 직접 호출 | **`./gradlew build` BUILD SUCCESSFUL**. `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`가 잡지 못함 | **우회 성공** (Q-1) |
| E5 | settings에 `"tools:autotrade-risk"`(폴더 `tools/autotrade-risk`, `group = "qa.tools"`, `autotrade-risk` 의존) 추가, 그 안에 `com.autotrade.risk.guard.NestedRaiser`(`beginActivation` 호출) | **`./gradlew build` BUILD SUCCESSFUL**. 의존성 트리에 `project :tools:autotrade-risk`가 아키텍처 테스트 클래스패스에 들어온 것 확인. 그러나 모듈 이름을 폴더 이름으로 알아내므로 `autotrade-risk`로 인정되어 소유 위반이 아니다 | **우회 성공** (Q-2) |

결론: main QA F-1이 실측한 형태(다른 모듈이 `com.autotrade.risk.guard`·`app.engine.command`·같은 FQN을 빌림)와 risk-ops R-1(아무도 의존하지 않는 새 모듈)은 실제로 닫혔다. 그러나 같은 보호(사람 확인 없는 Guard 상향 금지, Guard 팩토리 밖 출구 금지)를 빌드가 보장한다는 주장은 아직 성립하지 않는다(Q-1, Q-2).

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard`·설정의 Guard 관련 부분이 diff에 없다. 기본 상태 `Snapshot.INITIAL = OFF`(`RealTradingGuard.java:332, 337`). `RealTradingGuardTest` 31/31(`#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다` 포함), `EngineMainTest` 3/3 통과. 기본값·재기동 동작을 바꾸는 변경 없음. 단, 런타임에 사람 확인 없이 OPEN으로 올리는 코드를 빌드가 막는다는 주장은 Q-1로 깨진다(기본값 자체와는 별개라 A2에서 판정) |
| A2 | **실패** | ArchUnit 규칙 자체는 바뀌지 않았다(`ArchitectureTest.java` diff 없음). 그러나 두 실전 경로 규칙의 검사 대상이 `importPackages("com.autotrade")`(`ArchitectureTest.java:59-61`)이고, 새 `ModuleOwnershipTest`도 `Locations.ofPackage("com.autotrade")`(`ModuleOwnershipTest.java:258`)만 본다. 그래서 우리 모듈 안이라도 `com.autotrade` 밖 패키지에 둔 클래스는 두 테스트 모두 보지 않는다. E3: 실전 엔진 모듈 운영 코드가 Guard 팩토리 없이 `BrokerPlugin.orderGateway`를 호출해도 빌드 통과. E2: 같은 방식으로 Guard를 OPEN까지 올리는 코드도 통과. "우회 경로 없음"을 빌드가 보장하지 못한다(Q-1) |
| A3 | 통과 | 증권사 선택·주문 전송 코드 변경 없음. `RealOrderGateways.java:31-33` 플러그인 증권사 ≠ 계좌 증권사면 거부 그대로. `RealOrderGatewaysTest` 4/4 통과(바뀐 것은 가짜 플러그인의 응답 enum 이름뿐) |
| A4 | 해당 없음 | 타임아웃·재전송 경로 코드 변경 없음(main에도 구현 없음, main QA 보고서 A4). `OrderState.UNKNOWN`은 계속 비종결이다(`OrderState.java` `isTerminal`, `OrderStateTest#미확정과_취소_요청_중은_종결이_아니다`). 차단된 취소는 여전히 `UNKNOWN`을 돌려 호출자가 조회부터 하게 한다(`GuardedOrderGateway.java:85`). 7단계 실패 테스트 요구는 TST-07로 기획서에 들어갔다 |
| A5 | 해당 없음 | 멱등 키 발급·형식 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음(main QA F-2는 TST-07 (1)로 7단계 관문에 묶임, D-034) |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 해당 없음 | 프로세스 경계·런타임 의존 변경 없음. 아키텍처 테스트 모듈의 `testImplementation`만 바뀌었다(테스트 클래스패스라 배포물과 무관). `PROCESSES_ARE_INDEPENDENT`, `processIsolation` 검사 통과(build) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음. 토스 HELD_ONLY 보호 폴링 설정(`toss-polling-scope`)은 그대로이며 D-033이 예외로 명시 |
| A10 | 통과 | diff grep(앱 키·시크릿·토큰·계좌번호 패턴) 0건. 픽스처의 `KIS-1`, `nobody`는 가상 값. CI gitleaks success |
| A11 | 통과 | 보호 테스트 변경: `GuardedOrderGatewayTest`·`RealOrderGatewaysTest`는 enum 이름 교체만(`SUBMITTED→ACCEPTED`, 가짜 증권사의 취소 응답 `CANCELLED→CANCEL_REQUESTED`), 단언 수·조건은 그대로. `ArchitectureTest`는 변경 없음. 새 `ModuleOwnershipTest`는 위반 픽스처 4건 검출·대조군·범위 누락 시나리오 테스트를 갖췄다. `@Disabled`·`assume` 0건, 건너뜀 0건. 단, 새 테스트가 Q-1·Q-2 우회를 잡는 픽스처는 없다 |
| A12 | 통과 | 실전 주문 동작 변경 없음. 대응표 기준을 `broker-and-fault-design.md` 6장 하나로 정했고, 그 표는 실제로 17행이다(6장 `| n |` 행 수 17). 기획서 RSK-08, `role-guides.md`, `qa/checklists.md` A12가 17종으로 맞춰졌다. `EXPIRED` 추가는 장애 대응표의 전제(조회 우선, UNKNOWN 비종결)를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 분기·시각·금액 계산 변경 없음 |
| A14 | **실패** | 맞춘 것: `OrderState`가 ORD-07 10개 상태·순서와 같다(`autotrade-spec.md:202`, `OrderStateTest#상태_집합은_기획서_ORD_07과_같다`). RSK-01 문구가 ADR-002 2.1과 맞다(`autotrade-spec.md:174`). 장애표 17종 정정(A12). D-033·Q-020·Q-021 기록. 어긋난 것: ADR-002 9장(“이 규칙이 패키지 이름을 모듈과 같게 만들어 2.2 규칙들의 전제가 된다”, 그리고 "이 보강 전에는 성립하지 않았다"는 문장으로 보강 뒤에는 "지금은 운영 코드 어디서도 실전을 열 수 없다"가 성립함을 함의), D-034 표의 "F-1 … 이번 PR"(닫힘으로 처리), PR 본문의 "위장 패키지와 같은 FQN 클래스를 잡는다"는 E2·E3·E5에서 사실과 다르다. 결정 로그와 ADR이 실제로 보장하지 않는 보호를 약속한다 |

## 점검표 B (B1, B6)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 "직접 실행한 것": `./gradlew build` BUILD SUCCESSFUL, 318건 실패 0·건너뜀 0 |
| B6 | 통과 | 새 동작마다 테스트가 있다: `ModuleOwnershipTest` 8건(위반 픽스처 4종, 실험실 모듈 위장, 같은 FQN, 대조군, settings·클래스패스·표 교차 확인, 경로 해석), `OrderStateTest` 4건, `VirtualExchangeTest` 장 마감 `EXPIRED`·종결 단언, `ConfigLoaderTest` 기본 목록에 TOSS 없음·사용자가 넣으면 순서대로. 테스트 삭제·건너뜀 없음 |

## 점검표 밖 발견

### Q-1 [높음, 실측] `com.autotrade` 밖 패키지의 클래스는 실전 경로 규칙과 소유 규칙 모두를 피한다
- 원인: `ArchitectureTest.PRODUCTION`이 `importPackages("com.autotrade")`(`ArchitectureTest.java:59-61`)로 가져온 클래스에만 `noClasses()` 규칙을 적용하고, `ModuleOwnershipTest`도 `Locations.ofPackage("com.autotrade")`(`ModuleOwnershipTest.java:258`)만 검사한다. 우리 모듈의 클래스 폴더에 `com.autotrade` 밖 패키지가 생겨도 어느 테스트도 실패하지 않는다.
- 실측: E2(실험실 모듈, Guard OFF → OPEN, 실제 실행 테스트로 확인), E3(실전 엔진 모듈, Guard 팩토리 밖에서 증권사 주문 출구 생성). 둘 다 `./gradlew build` 통과.
- 영향: [사실] 지금은 증권사 플러그인 구현이 없어 E3이 열 실제 출구가 없고, 실험실은 별도 프로세스라 실전 엔진의 Guard 인스턴스를 얻을 수 없다. [추론] 7단계에 플러그인과 명령 채널이 생기면, 실전 엔진 모듈에 실수로 다른 패키지(예: 코드 생성기 출력, 오타 `com.autotarde…`, 외부 예제 복사)로 들어온 코드가 Guard를 거치지 않는 출구를 열어도 CI가 초록이다. ADR-002 2.2가 "유일한 구조적 방어"로 삼는 규칙의 전제가 깨진다.
- 고칠 점(제안): 소유 규칙을 "모듈 → 클래스" 방향으로도 닫는다. 아키텍처 테스트 클래스패스의 **각 우리 모듈 위치(클래스 폴더·jar 전체)**를 패키지 필터 없이 가져와, 모든 운영 클래스가 그 모듈이 소유한 `com.autotrade` 패키지 안에 있어야 한다는 검사(밖이면 실패)를 더하고, `com.autotrade` 밖 패키지 위반 픽스처(E2·E3 형태)를 넣는다. 그러면 `ArchitectureTest`의 `importPackages("com.autotrade")` 범위가 우리 모듈 클래스 전체와 같아진다. 또는 `ArchitectureTest.PRODUCTION`을 우리 모듈 위치 전체에서 가져오게 바꾼다(보호 테스트 변경이므로 실전 관련 PR).

### Q-2 [중간, 실측] 모듈 이름을 폴더 이름으로 알아내 같은 이름의 중첩 프로젝트를 구분하지 못한다
- 원인: `moduleOf`·`classpathModuleOf`(`ModuleOwnershipTest.java:273, 342`)는 경로의 `<폴더>/build/classes` 바로 위 폴더 이름을 모듈로 쓰고, settings 목록도 `it.name`(`autotrade-architecture-test/build.gradle.kts:32`)으로 비교한다. Gradle은 부모가 다르면 같은 이름의 프로젝트를 허용한다.
- 실측: E5에서 `:tools:autotrade-risk`가 `com.autotrade.risk.guard`에 Guard 상향 코드를 두었는데 `./gradlew build`가 통과했다(이름 목록은 집합이라 중복이 사라지고, 위치는 `autotrade-risk`로 인정됨). `projectDir`을 다른 이름 폴더로 바꾸는 형태는 클래스패스·settings 교차 확인이 잡을 것으로 보이나 실측하지 않았다 `[추론]`.
- 영향: 의도적인 settings 변경이 필요해 우발 가능성은 Q-1보다 낮다 `[추론]`. 그러나 이 테스트의 목적이 "이름 위장" 차단이므로 같은 종류의 구멍이다.
- 고칠 점(제안): Gradle이 프로젝트 **경로**(`:tools:autotrade-risk`)와 `projectDir`의 대응을 시스템 속성으로 넘기고, 위치를 그 표로 모듈에 매핑한다. settings에 같은 이름의 프로젝트가 둘 이상이면 실패시킨다.

### Q-3 [낮음, 과금 관련 관찰] 이전 기본값(TOSS 포함)이 이미 저장된 설정 파일에는 D-033이 적용되지 않는다
- [사실] UI 설정 변경은 메모리의 전체 설정을 `runtime-config.json`에 그대로 쓴다(`RuntimeConfigHolder.java:48` → `RuntimeConfigStore.save`, `market-data` 포함). 이 PR 전 기본값으로 실행하며 어떤 설정이든 UI에서 한 번 바꾼 사용자는 파일에 `"priority": ["KIWOOM","KIS","TOSS"]`가 남아 있고, 이 PR 뒤에도 토스가 켜진 채로 읽힌다(`ConfigLoaderTest#사용자가_토스를_넣으면_적은_순서대로_쓴다`와 같은 경로). 사용자가 명시적으로 켠 것이 아니다.
- [사실] 지금은 토스 시세 클라이언트가 없어 호출이 일어나지 않는다(과금 검사 8.1).
- 고칠 점(제안): 토스 시세 클라이언트가 들어오는 PR 전에, 저장 파일의 우선순위가 이전 기본값과 정확히 같으면 TOSS를 빼고 사용자에게 알리는 이전 처리(또는 D-033 "명시적으로 켬"을 별도 표시로 기록), 그리고 그 테스트. D-033 "영향과 남은 위험"에 이 경우를 적는다.

## 위험 시나리오 (잘못된 주문·중복 주문·보호 공백)

| # | 시나리오 | 결과 |
|---|---|---|
| R-1 | 7단계에 실전 엔진 모듈로 `com.autotrade` 밖 패키지 클래스가 들어와 `BrokerPlugin.orderGateway`를 직접 부름 | **보호 공백(빌드 통과)**. Guard OFF여도 증권사 출구가 열린다. E3 실측. Q-1 |
| R-2 | 실험실·UI 모듈이 `com.autotrade` 밖 패키지에서 2차 확인 객체를 만들어 Guard를 올림 | **빌드 통과**, 실제 Guard 객체로 OPEN 도달(E2 `OutsideRaiserTest`). 지금은 프로세스가 달라 실전 Guard에 닿지 못함 `[추론]`. 7단계 명령 채널이 실험실 쪽 코드와 연결되면 사람 확인 없는 활성화 경로가 된다 |
| R-3 | 증권사 취소 응답을 새 enum으로 매핑할 때 "취소 접수"를 `CANCELED`(종결)로 잘못 매핑 | 취소 확인 전 체결을 놓쳐 보유 수량이 틀리고, 그 수량으로 다음 주문을 내면 잘못된 주문이 된다. 이 PR은 `CANCEL_REQUESTED`를 비종결로 정의하고 테스트했다(`OrderStateTest`). 매핑 코드는 7단계라 지금 결함은 없음. 전이표(ORD-07) 구현 PR에서 확인할 항목 |
| R-4 | 재기동 시 `EXPIRED`·`CANCEL_REQUESTED` 주문 복구 | ORD-07은 "종결되지 않은 주문은 모두 UNKNOWN으로 보고 조회로 확정"이다. `CANCEL_REQUESTED`가 비종결이므로 조회 대상에 들어간다(올바름). 복구 코드는 미구현 |
| R-5 | 가상 거래소 장 마감 소멸이 `CANCELED`→`EXPIRED`로 바뀌어 실험 결과가 달라짐 | 예약 현금 해제·`open` 제거는 같은 `close` 경로라 그대로다(`VirtualExchange.java:474-479`). 실험 실행기는 `REJECTED`만 구분한다(`SimulationExperimentRunner.java:249`). 골든·결정론 테스트 포함 빌드 통과. 실험 산출물(`experiments/`)에 주문 상태 이름이 기록된 곳 없음(grep) |

## 과금 검사 (프로젝트 지침 8장)
범위: 이 PR의 main 대비 전체 diff와 그 diff가 호출하거나 영향을 주는 코드. 이 장은 확인 못 함도 실패로 본다.

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 8.1 | 유료 API 호출 경로 식별 | 통과 | 운영 코드 전체에 외부로 나가는 호출이 없다: `HttpClient`·`WebClient`·`RestTemplate`·`URLConnection`·`WebSocket`·`requests`·`urllib`·외부 URL grep 결과, 걸린 것은 실험실 UI의 같은 출처 `fetch`(`autotrade-ui/.../js/api.js:13`)와 SVG 네임스페이스 문자열뿐. diff의 운영 코드 변경(`OrderState`, `VirtualExchange`, 기본 설정 JSON)은 외부 호출을 만들지 않는다. 바뀐 Gradle 설정은 테스트 클래스패스 구성뿐이며 새 저장소·원격 호출 없음. 유료 호출 지점 0건 |
| 8.2 | 필요성과 대체 수단 | 통과 | 유료 호출이 없다(8.1). 요금 미확인 소스(토스 미국 실시간)는 기본에서 빠졌고 대체 수단(키움·한국투자)과 그 영향(용량 감소)이 D-033에 적혀 있다 |
| 8.3 | 사용자 옵션 제어(기본 꺼짐) | 통과 | 기본값 `"priority": ["KIWOOM", "KIS"]`(`autotrade-default.json:3`), `ConfigLoaderTest`가 기본 목록에 TOSS가 없음을 단언. 사용자가 `runtime-config.json`에 넣어야만 켜진다. 알림 `enabled=false` 기본, 실전 계좌 `[]` 기본이라 HELD_ONLY 토스 폴링도 기본 설정으로는 대상이 없다. 단, 이전 기본값이 이미 저장된 파일은 켜진 채로 남는다(Q-3). 지금 호출 코드가 없어 실제 호출은 0건이므로 통과로 보되, 토스 클라이언트 PR에서는 Q-3 처리 없이는 실패로 본다 |
| 8.4 | 폭주 방지 | 통과 | 이 PR은 반복·재시도·복구 경로를 만들지 않는다. 호출 경로가 없어 폭주할 대상이 없다(8.1). 호출 상한·토큰 버킷은 TST-07(7단계 관문)에 실패 테스트 요구로 들어갔다 |
| 8.5 | 실주문 비용 | 통과 | 실주문을 낼 증권사 구현이 없고 Guard 기본 OFF(A1). 토스 실주문 시험(TST-05)에 횟수·금액·손실 상한 승인 전 시험 금지 조건이 추가됐고 상한은 Q-021로 사용자 결정 대기. 가상 체결 비용 모델은 바뀌지 않았다 |

## 판정
**수정 필요(A2, A14)**
- A2: `com.autotrade` 밖 패키지의 운영 클래스는 `ArchitectureTest`의 실전 경로 규칙과 `ModuleOwnershipTest`를 모두 피한다(Q-1, E2·E3 실측, `./gradlew build` 통과). 같은 이름 중첩 프로젝트도 소유 규칙을 피한다(Q-2, E5).
- A14: ADR-002 9장, D-034, PR 본문이 F-1을 닫힌 것으로 기록하지만 위 우회가 남아 있다. 고치거나, 고치지 않으면 남은 범위를 기록에 정확히 적어야 한다.
- 닫힌 것으로 확인한 것: main QA F-1의 실측 형태(E1, E7), PR #19 QA F-1 E1 형태의 분할 패키지(E6), risk-ops R-1(E4), `OrderState`·RSK-01·장애표 종 수 불일치(main QA A14 (1)~(3)), 토스 기본 켜짐(main QA P3).

## 재검토 2026-10-09
- 대상: PR #21 / 커밋 `d736306` / main(`52ad85f`, merge-base 같음) 대비 전체 diff. 앞 검토(`218942a`) 뒤 바뀐 것: `74a0f41`(검사 범위를 우리 모듈 운영 클래스 전체로, 모듈 식별을 프로젝트 경로로), `f03b6bf`(클래스패스 항목을 선언 출력과 정확히 맞춤), 역할 검토 원문 4건. 코드 변경은 `autotrade-architecture-test`(빌드 스크립트, `ArchitectureTest.PRODUCTION` 정의, `ModuleOwnershipTest`, 새 `OurModules`, 픽스처 `qa.probe.*`)와 문서(ADR-002 9.1~9.4, D-033, D-034, CLAUDE.md)뿐이다.
- 점검표: A 전체 + B1, B6 + 프로젝트 지침 8장 과금 검사
- 직접 실행한 것:
  - `./gradlew clean build --no-daemon` (커밋 `d736306`) → **BUILD SUCCESSFUL**. 테스트 323건, 실패 0, 오류 0, 건너뜀 0. `ArchitectureTest` 12/12, `ModuleOwnershipTest` 13/13, `RealTradingGuardTest` 31/31, `GuardedOrderGatewayTest` 8/8, `RealOrderGatewaysTest` 4/4. (앞 검토와 같은 환경 메모: Maven Central 429 때문에 이 검토 환경의 `~/.gradle/init.d`에서 미러만 바꿨다. 저장소 파일 변경 없음)
  - 변조 실험 8건(별도 git worktree, 커밋하지 않음, 실험 뒤 worktree 삭제하고 `git status` 깨끗함 확인): 아래 표.

### 지적 항목 재현

| 실험 | 넣은 코드 | 앞 검토(`218942a`) | 이번(`d736306`) |
|---|---|---|---|
| E1 | 실험실에 `com.autotrade.risk.guard.QaProbe`(`beginActivation`) | 실패(잡음) | 실패(잡음): `#운영_클래스는_패키지를_소유한_모듈에만_있다` |
| E2 (Q-1) | 실험실 운영 코드에 `qa.probe.OutsideRaiser`(2차 확인 생성 → `beginActivation` → `completeStep` 3단계) | **통과(우회)** | **실패(잡음)**: `ArchitectureTest#Guard를_올리는_조작은_명령_채널에서만_한다` + `ModuleOwnershipTest#운영_클래스는_패키지를_소유한_모듈에만_있다` |
| E3 (Q-1) | 실전 엔진 운영 코드에 `qa.probe.OutsideOpener`(`plugin.orderGateway` 직접 호출) | **통과(우회)** | **실패(잡음)**: `ArchitectureTest#실전_주문_출구는_Guard_팩토리만_연다`("Method <qa.probe.OutsideOpener.open(...)> calls ...") + 소유 위반 |
| E4 (R-1) | settings에만 넣은 `autotrade-rogue`의 `com.autotrade.risk.guard.RogueRaiser` | 실패(잡음) | 실패(잡음): 소유 위반 + `#클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다` |
| E5 (Q-2) | settings에 `tools:autotrade-risk`(group `qa.tools`), 그 안에 `com.autotrade.risk.guard.NestedRaiser` | **통과(우회)** | **실패(잡음)**: "NestedRaiser (:tools:autotrade-risk): 패키지 com.autotrade.risk.guard은 autotrade-risk 소유", "같은 이름의 프로젝트가 여럿: autotrade-risk (:autotrade-risk, :tools:autotrade-risk)", "소유 모듈 표(OWNERS)에 없는 프로젝트: :tools:autotrade-risk" |
| E6 | sim에 `com.autotrade.risk.QaSplitPackageApprove` | 실패(잡음) | 실패(잡음) |
| E7 | 실험실에 같은 FQN `com.autotrade.app.engine.order.RealOrderGateways` | 실패(잡음) | 실패(잡음): "(:autotrade-app-lab): 패키지 com.autotrade.app.engine.order은 autotrade-app-engine 소유" |
| E8 (새로 시도) | 실전 엔진 운영 코드 `com.autotrade.app.engine.qaprobe.ReflectiveRaiser`: `Class.forName`·`getMethod("begin"+"Activation")`·`invoke`로 2차 확인 생성과 3단계 통과. 같은 모듈 테스트가 실제 Guard로 실행해 `GuardState.OPEN` 단언 | (시도 안 함) | **`./gradlew build` 통과(우회), 테스트로 OPEN 도달 확인**. ADR-002 9.4·D-034 (다)에 "리플렉션·`MethodHandle` 접근은 보지 않는다"로 이미 적힌 한계와 같다(Q-4) |

결론: Q-1(E2·E3), Q-2(E5)는 실제로 닫혔다. 앞서 닫혔던 E1·E4·E6·E7은 회귀 없이 그대로 잡힌다. 남은 우회 E8은 문서에 적힌 ArchUnit 한계다.

### 점검표 A (재검토)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·설정 코드 변경 없음(이번 변경은 `autotrade-architecture-test`와 문서뿐). `RealTradingGuardTest` 31/31(생성 직후 OFF, 재기동 OFF 포함), `EngineMainTest` 통과 |
| A2 | 통과 | 두 실전 경로 규칙 자체(`ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`, `ONLY_COMMAND_CHANNEL_RAISES_GUARD`)는 그대로이고 검사 범위만 넓어졌다: `ArchitectureTest.java:63-65`가 `OurModules.architectureScope()`(`OurModules.java:323`, 예전 `com.autotrade` 위치 전체 ∪ 우리 프로젝트 운영 항목 전체)를 `architectureImportOption()`(`:336`, 우리 폴더 안은 선언 종류로, 외부 jar는 예전 `DoNotIncludeTests`)로 가져온다. 예전 범위 포함은 `ModuleOwnershipTest#ArchitectureTest_범위는_우리_모듈_운영_클래스_전체를_덮는다`가 단언한다. E2·E3·E5 실측으로 앞 검토의 우회가 닫혔다. 남은 우회 E8(리플렉션)은 ArchUnit의 정적 분석 한계로 ADR-002 9.4에 보장 범위 밖으로 적혀 있다. 지금은 증권사 플러그인 구현이 없어 열 출구가 없다. Q-4 참고 |
| A3 | 통과 | 앞 검토와 같음(증권사 선택·주문 전송 코드 변경 없음, `RealOrderGatewaysTest` 4/4) |
| A4 | 해당 없음 | 타임아웃·재전송 경로 코드 없음, 이번 변경 무관(앞 검토 근거 유지) |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 해당 없음 | 런타임 의존 변경 없음. 바뀐 것은 아키텍처 테스트 모듈의 테스트 클래스패스와 시스템 속성뿐 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음 |
| A10 | 통과 | 이번 diff grep(키·시크릿·토큰·계좌번호 패턴) 0건. 시스템 속성 `autotrade.projectLayout`에는 경로만 들어간다(`build.gradle.kts` `projectLayout`) |
| A11 | 통과 | 보호 테스트 `ArchitectureTest`는 `PRODUCTION` 정의만 바뀌었고 규칙·위반 픽스처·검출 수 단언은 그대로 12/12다. 범위는 넓어졌고 좁아지지 않음을 별도 테스트가 단언한다(강화). `ModuleOwnershipTest` 8→13, 새 테스트마다 실패 경우를 담는다(`#com_autotrade_밖_패키지의_운영_클래스도_잡아낸다`, `#같은_이름의_중첩_프로젝트는_소유_모듈로_인정하지_않는다`, `#출력을_다른_프로젝트_폴더로_돌리면_잡아낸다`, `#선언되지_않은_클래스패스_항목과_테스트처럼_보이는_운영_출력을_잡아낸다`, `#클래스가_없다고_선언한_모듈에_클래스가_있으면_잡아낸다`). 삭제·`@Disabled`·건너뜀 0건 |
| A12 | 통과 | 실전 주문 동작 변경 없음(앞 검토 근거 유지) |
| A13 | 해당 없음 | 시장·시각·금액 계산 변경 없음 |
| A14 | 통과 | 앞 검토 실패 근거였던 "F-1이 닫혔다"는 기록이 이번에는 실측과 맞다: ADR-002 9.2가 Q-1·Q-2 원인과 고친 방식을, 9.4가 보장 범위와 남은 한계(사용자 정의 태스크·`jar from`, 외부 의존성의 `com.autotrade` 밖 코드, `includeBuild`, 리플렉션·`MethodHandle`)를 적었다. D-034 표와 "F-1 보장 범위·남은 한계"가 같은 내용이다. 내가 재현한 결과(E2·E3·E5 잡힘, E8 통과)가 이 문서의 보장·한계 구분과 일치한다. Q-3도 D-033 "영향과 남은 위험"에 토스 클라이언트 PR 전 조건으로 기록됐다. 사소한 부정확 1건: `ModuleOwnershipTest.java:93` 주석이 "람다 메서드 참조"를 바이트코드 호출로 드러나지 않는 접근에 넣었지만, ArchUnit은 메서드 참조를 접근으로 잡고 `ArchitectureTest`의 출구 규칙 주석도 "메서드 참조든 막는다"고 적는다. 동작에는 영향이 없는 테스트 주석이라 실패로 보지 않는다 |

### 점검표 B (재검토)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | `./gradlew clean build` BUILD SUCCESSFUL, 323건 실패 0·건너뜀 0(커밋 `d736306`) |
| B6 | 통과 | 새 동작(범위 확대, 프로젝트 경로 매핑, 선언 출력 정확 일치, 배치 검사, 예외 목록 고정)마다 실패 경우 테스트가 있고, 앞 검토의 E2·E3 형태가 `qa.probe.OutsideRaiser`·`OutsideOpener` 픽스처로 들어갔다. 테스트 삭제·건너뜀 없음 |

### 점검표 밖 발견 (재검토)

**Q-1 해소, Q-2 해소** (위 재현표). **Q-3 기록됨**(D-033, 코드 변경은 토스 시세 클라이언트 PR 전 조건).

**Q-4 [중간, 실측, 문서화된 한계] 리플렉션으로 Guard를 사람 확인 없이 OPEN까지 올리는 실전 엔진 코드가 빌드를 통과한다 (E8).**
- [사실] 실전 엔진 모듈 운영 코드가 문자열로 만든 메서드 이름과 `Method.invoke`로 `SecondConfirmation` 생성, `beginActivation`, `completeStep` 3단계를 부르면 `ArchitectureTest`·`ModuleOwnershipTest` 모두 통과하고, 실제 Guard가 OPEN이 된다. 같은 방법으로 `BrokerPlugin.orderGateway`도 부를 수 있다 `[추론]`(같은 메커니즘이라 따로 실측하지 않음).
- [사실] ADR-002 9.4, D-034 (다), `ModuleOwnershipTest` 주석이 이 한계를 보장 범위 밖으로 적었다. 그래서 이번 PR이 약속한 것과 다르지 않다.
- [추론] 위험은 7단계다. 실전 엔진·리스크·증권사 모듈은 Spring을 쓰지 않는 순수 Java라(`ArchitectureTest#실험실_외_프로세스는_Spring을_쓰지_않는다`) 리플렉션을 쓸 정상적인 이유가 거의 없다. 그런데도 지금은 막는 규칙이 없어, 직렬화·플러그인 로딩 같은 이유로 리플렉션 유틸이 들어오면 Guard 상향·출구 생성 우회를 빌드가 보지 못한다.
- 고칠 점(제안, 이 PR을 막지 않음): `autotrade-app-engine`, `autotrade-risk`, `autotrade-broker-*`, `autotrade-app-watchdog` 운영 코드에서 `java.lang.reflect.Method#invoke`, `Constructor#newInstance`, `Class#forName`, `java.lang.invoke.MethodHandles$Lookup#find*` 호출을 금지하는 ArchUnit 규칙과 위반 픽스처(E8 형태). 필요한 예외(예: `ServiceLoader` 기반 플러그인 로딩)는 허용 목록으로 둔다. 기획서 TST-07(7단계 진입 관문)에 넣는 것을 권한다.

### 위험 시나리오 (재검토)

| # | 시나리오 | 결과 |
|---|---|---|
| R-1 | 실전 엔진에 `com.autotrade` 밖 패키지 클래스가 들어와 `orderGateway`를 직접 부름 | **막힘**(E3 실측, 두 테스트 모두 실패) |
| R-2 | 실험실·UI 모듈이 밖 패키지에서 2차 확인을 만들어 Guard를 올림 | **막힘**(E2 실측) |
| R-3 | 같은 이름 중첩 프로젝트로 `risk.guard` 소유를 위장 | **막힘**(E5 실측, 같은 이름 프로젝트 자체도 실패) |
| R-4 | 실전 엔진 코드가 리플렉션으로 Guard를 올리거나 출구를 엶 | **보호 공백(빌드 통과)**. E8 실측. 문서화된 한계. Q-4 |
| R-5 | 새 외부 라이브러리(Gradle 캐시 안 jar)가 `com.autotrade` 밖 패키지에서 Guard API를 부름 | 빌드가 보지 않는다(ADR-002 9.4 (나)). 의존성 추가는 빌드 파일 변경으로 보이고, 실전 엔진의 새 외부 의존성은 risk-ops 검토 대상으로 적혀 있다. 실측하지 않음(risk-ops 재검토 3의 M11이 실측했다고 적었으나 QA는 그 주장을 확인하지 않았다) |

### 과금 검사 (재검토)

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 8.1 | 유료 API 호출 경로 식별 | 통과 | 이번 변경은 아키텍처 테스트(테스트 소스, 빌드 스크립트)와 문서뿐이다. 운영 코드 변경 없음(`git diff 6ec4a29 d736306 --stat`: `autotrade-architecture-test`, `docs`, `CLAUDE.md`). 빌드 스크립트는 로컬 경로(`rootDir`, `gradleUserHomeDir`, 출력 경로)만 읽고 새 저장소·원격 호출을 더하지 않는다. 운영 코드 전체의 외부 호출 0건은 앞 검토 근거 그대로다 |
| 8.2 | 필요성과 대체 수단 | 통과 | 유료 호출 없음(8.1) |
| 8.3 | 사용자 옵션 제어(기본 꺼짐) | 통과 | 기본 `priority`는 `["KIWOOM", "KIS"]` 그대로(`autotrade-default.json:3`). Q-3(이전 기본값이 저장된 파일)은 D-033에 토스 클라이언트 PR 전 조건으로 기록됐고, 지금 호출 코드가 없어 실제 호출 0건 |
| 8.4 | 폭주 방지 | 통과 | 새 반복·재시도·외부 호출 경로 없음 |
| 8.5 | 실주문 비용 | 통과 | 실주문 경로 변경 없음, Guard 기본 OFF, 증권사 구현 없음. TST-05 상한(Q-021) 조건 유지 |

### 판정 (재검토)
**승인**
- 앞 검토의 수정 필요 항목 A2(Q-1·Q-2)와 A14가 닫혔음을 직접 재현으로 확인했다(E2·E3·E5 모두 빌드 실패로 바뀜, 문서가 실측과 일치).
- 실패·확인 못 함 항목 없음. 과금 검사 5항목 모두 통과.
- 남은 위험: Q-4(리플렉션 우회, 문서화된 한계, 7단계 진입 관문에 규칙 추가 권장), R-5(외부 의존성, 문서화된 한계), Q-3(토스 클라이언트 PR 전 처리 필요).
- 이 PR은 실전 관련 PR(보호 테스트 `ArchitectureTest` 범위 변경, `OrderState` 계약 변경)이므로 머지는 사용자가 직접 한다.
