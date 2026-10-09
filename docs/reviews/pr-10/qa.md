# 독립 QA 검토: PR #10 2단계 시세(키움·한투 실시간 수신, 녹화·리플레이, 거래소 캘린더, 일봉 스냅샷)
- 대상: PR #10 / 커밋 `0e9c2ad` (브랜치 `claude/project-thread-u03g6l`) / main 대비 diff (merge-base `9eab2e6`, 107개 파일, +8425 −18)
- 검토 시점 main: `9576a19` (PR 이후 main에 #21 `f6a49be`, #22 `9576a19`가 들어옴). GitHub `mergeable_state: dirty`(충돌)
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장)
- 직접 실행한 것:
  - `./gradlew build` @ `0e9c2ad`: **BUILD SUCCESSFUL**, 테스트 503건, 실패 0, 건너뜀 0 (처음 4회는 Maven Central `429 Too Many Requests`로 의존성 해석 실패, 5회째 성공. 코드 원인 아님)
  - main 병합 시험(로컬 전용, push 안 함): `0e9c2ad`에 `origin/main`(`f6a49be`)을 병합. 충돌 1건 `autotrade-architecture-test/build.gradle.kts`를 main 쪽(설정의 모든 Java 모듈 자동 포함)으로 해소한 `9e89b22`에서 `./gradlew build --continue`: **BUILD FAILED**. `ModuleOwnershipTest` 2건 실패(아래 B1)
  - 탐침 테스트(로컬 전용, 커밋 안 함) `QaProbeTokenIssueFailureTest`: 토큰·접속키 **발급 자체가 실패**하는 경우(`openingMessages()` 예외), 운영과 같은 `BackoffPolicy.standard()`로 600초 진행 → 발급 시도 **24회**, `credentialsRejected()=false`, `authFailures=0` (아래 과금 8.4)
  - 공식 요금 확인 시도: `openapi.kiwoom.com/main/home`, `openapi.kiwoom.com/intro/serviceInfo`, `apiportal.koreainvestment.com/intro`, `apiportal.koreainvestment.com/about-open-api`, `github.com/Kiwoom-Securities/Kiwoom-REST-API`, `github.com/koreainvestment/open-trading-api` README에서 국내 실시간 시세 요금 문구를 찾지 못함. `securities.koreainvestment.com/.../RestAPIService.jsp`는 robots 차단으로 읽지 못함

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 `autotrade-risk`·RealTradingGuard·설정 기본값 변경 없음(`git diff 9eab2e6...0e9c2ad --stat`). `RealTradingGuardTest`·`GuardedOrderGatewayTest` 포함 전체 빌드 통과 |
| A2 | 통과 | `ArchitectureTest.ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 규칙 본문 불변(주석만 "플러그인에 주문 코드를 넣는 첫 PR보다 먼저 규칙 추가"로 바뀜, ArchitectureTest.java:142-143). 세 플러그인 `orderGateway()`는 모두 `Optional.empty()`(KiwoomPlugin.java:92, KisPlugin.java:93, TossPlugin.java:81), `KiwoomPluginTest#시세_능력만_선언하고_주문_출구는_없다`, `KisPluginTest#시세_능력만_선언하고_주문_출구는_어느_계좌에도_없다`, `TossPluginTest` 통과. 플러그인 모듈에 주문 URI·주문 메시지 없음(grep) |
| A3 | 통과 | 주문 경로 변경 없음. 시세 전환(`MarketHub`, `SourcePlanner`)은 구독만 옮기고 `orderGateway`를 부르지 않음(MarketHub.java:38, 213-216) |
| A4 | 해당 없음 | 주문 전송·재전송 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유·RiskChain 변경 없음. 게이트웨이는 주문하지 않음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷 코드 변경 없음 |
| A8 | 통과 | 게이트웨이는 별도 프로세스(`autotrade-app-gateway`), 녹화 큐 10만 건 상한(Gateway.java:28)과 넘침 시 버림·공백 표시. 실전 계좌가 설정된 증권사 키는 게이트웨이가 쓰지 않음(`GatewayMain.reservedForRealTrading`, GatewayMain.java:86, 107-115, `GatewayMainTest#계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다` 통과). 단 기동 시 1회만 판단한다(점검표 밖 R-2) |
| A9 | 통과 | 이전에 없던 수신 경로를 더한 것이고 기존 보호 경로를 약화하지 않음. 보유 종목은 한도 초과 시에도 빼지 않고 다음 소스로 넘김(SourcePlanner.java:69-73, `SourcePlannerTest#FAILOVER라도_넘치는_보유_종목은_다음_소스로_보내고_후보는_보내지_않는다` 통과). 종목별 공백 시 보호 폴링(D-024)은 미구현으로 ADR-003 5장 표에 7단계 후속으로 기록됨 |
| A10 | 통과 | 추가 줄에서 키·계좌번호·토큰 패턴 검색 결과 없음. `BrokerCredentials.toString()`·`TokenCache.Issued.toString()` 마스킹, 발급 실패 예외에 상태 코드만(KiwoomTokenClient.java:140-146, `KiwoomStreamProtocolTest#토큰_발급_실패_메시지에_비밀값이_없다` 통과). 로그에 본문·토큰 없음. 한국투자 접속키가 평문 `ws://`로 나가는 것은 공식 주소 구조이며 ADR-003 4장에 기록됨 |
| A11 | 통과 | 보호 테스트 변경은 `ArchitectureTest`에 규칙·위반 픽스처 추가와 주석 1건뿐, 삭제·약화 없음. RealTradingGuard·GuardedOrderGateway 테스트 미변경. 새 동작에 실패 경우 테스트 있음(끊김, 인증 실패, 큐 넘침, 구독 한도 등). 다만 발급 실패 경로의 재시도 제한 테스트가 없음(과금 8.4) |
| A12 | 통과 | 장애표 7번(토큰 만료·인증 실패 → 해당 증권사 DEGRADED, 시세 소스만 전환): 발급 실패 시에도 소스가 DISCONNECTED → `BrokerStatuses.fromConnection` DEGRADED → 배분에서 빠짐. 단 "알림" 쪽은 발급 실패에서 ERROR가 나오지 않음(과금 8.4, R-1) |
| A13 | 통과 | 캘린더 시간대 KRX `Asia/Seoul`, US `America/New_York`(krx.json:3, us.json:3), 호가 단위는 `KrxMarketRules`·`UsMarketRules`로 분리, 금액 `BigDecimal`(UsMarketRules.java:20-22). 이번 실시간 플러그인은 KRX만 |
| A14 | 실패 | main의 D-033(`docs/decisions/D-033-unpriced-source-default-off.md` "영향과 남은 위험")은 "토스 시세 클라이언트 PR 전에 필요한 것: 저장 파일 `market-data.priority`가 이전 기본값 `["KIWOOM","KIS","TOSS"]`와 정확히 같으면 TOSS를 빼고 알리는 이전 처리와 테스트. 이 처리 없이 토스 시세 클라이언트를 넣는 PR은 과금 검사 8.3 실패"라고 정했다. 이 PR은 토스 시세 클라이언트 `TossPricePoller`(TossPricePoller.java:38)를 넣지만 이전 처리가 없고, 결정 로그·ADR-003에 D-033 반영이 없다(PR이 D-033보다 먼저 쓰였음) |
| B1 | 실패 | `0e9c2ad` 단독은 통과. 그러나 PR은 main과 충돌(dirty)이고, 충돌을 main 방식으로 해소한 병합 결과는 실패: `ModuleOwnershipTest.클래스패스는_settings의_모든_Java_모듈을_담고_표와_같다` ("소유 모듈 표(OWNERS)에 없는 프로젝트: :autotrade-broker-kis, :autotrade-broker-kiwoom, :autotrade-broker-toss"), `ModuleOwnershipTest.운영_클래스는_패키지를_소유한_모듈에만_있다` ("패키지 com.autotrade.broker.kis은 autotrade-broker-api 소유" 등). 병합에는 보호 테스트 파일(`autotrade-architecture-test`)의 충돌 해소와 OWNERS 갱신이 필요하고, 현재 architect·risk-ops 원문(`67e9ebf` 기준)은 그 결과를 다루지 않는다 |
| B6 | 통과 | 새 동작마다 테스트 있음(시세 엔진 15건, 녹화·리플레이, 캘린더, 일봉, 플러그인별, 게이트웨이·배분). 건너뛰거나 지운 테스트 없음(diff에 `@Disabled`·삭제 없음) |

## 과금 검사 (QA 지침 8장)

### 8.1 유료 API 호출 경로 식별 — 실패(확인 못 함)
외부 호출 지점 전체:

| # | 파일:라인 | API | 호출 주기 | 진입점→호출 경로 |
|---|---|---|---|---|
| P1 | KiwoomTokenClient.java:118, 137 | 키움 접근토큰 발급 `POST https://api.kiwoom.com/oauth2/token`(au10001) | 접속마다 `TokenCache` 확인, 캐시 없음·만료 10분 전·로그인 거절 뒤에만 발급. **발급 실패 시 1→2→4→8→16→30초 간격으로 무한 반복**(P1/P3 8.4) | `GatewayMain.main` → `start` → `plugin(KIWOOM)` → `KiwoomPlugin.create` → `Gateway.start` → `MarketHub.start` → `StreamingMarketDataSource.start` → `attempt` → `KiwoomStreamProtocol.openingMessages` → `TokenCache.get` → `KiwoomTokenClient.fetch` |
| P2 | KiwoomStreamProtocol.java:49 | 키움 실시간 WebSocket `wss://api.kiwoom.com:10000/api/dostk/websocket` | 상시 연결, 끊기면 1~30초 백오프 재접속 | 위 경로의 `attempt` → `JdkWsTransport.connect` |
| P3 | KisApprovalClient.java:272, 292 | 한국투자 실시간 접속키 발급 `POST https://openapi.koreainvestment.com:9443/oauth2/Approval` | 24시간 유효, 만료 1시간 전·인증 실패 뒤 발급. 발급 실패 시 P1과 같은 무한 반복 | `GatewayMain` → `KisPlugin.create` → … → `KisStreamProtocol.openingMessages` → `TokenCache.get` → `KisApprovalClient.fetch` |
| P4 | KisStreamProtocol.java:47 | 한국투자 실시간 WebSocket `ws://ops.koreainvestment.com:21000/tryitout` | 상시 연결 | 위 경로의 `attempt` |
| P5 | TossTokenClient.java:24 | 토스 토큰 발급 | 운영 호출 경로 없음 | `TossPlugin.create`를 부르는 운영 코드가 없음(grep: 테스트만). `GatewayMain.plugin`의 `TOSS -> Optional.empty()`(GatewayMain.java:125) |
| P6 | TossPricePoller.java:41, 105 | 토스 현재가 `GET /api/v1/prices` | 운영 호출 경로 없음 | P5와 같음 |

요금 판정:
- P1·P2(키움 국내 실시간): 공식 요금 문구를 직접 확인하지 못했다. 저장소 조사(`docs/research/market-data-limits.md` K3 "REST API는 유료 시세 신청과 무관하게 무료 시세로만 제공")는 미국 시세 항목의 공식 저장소 문구이며, QA가 원문을 다시 확인하지 못했다.
- P3·P4(한국투자 국내 실시간): 공식 요금 근거가 저장소에도 없고 QA도 찾지 못했다(조사 문서의 H5·H6은 미국 시세).
- P5·P6(토스): 요금 미확인(D-033, Q-020).
- 8장 규칙상 "확인 못 함"은 실패다.

### 8.2 필요성과 대체 수단 — 실패(확인 못 함)
- P1~P4는 2단계 목표(실시간 수신·녹화) 자체라 필요하다. 다만 유료 여부가 확인되지 않아 "유료인데 무료 대체가 있는지"를 판정할 수 없다. 국내 실시간의 대체 수단(다른 증권사 무료 실시간)도 같은 이유로 비교 불가.
- P5·P6는 이번 PR에서 호출되지 않으므로 필요성 근거가 없다. 운영 경로 없이 클라이언트만 들어온다.

### 8.3 사용자 옵션 제어 — 실패
- P1~P4: 통과 판단 근거 있음. 키가 없으면 플러그인이 소스를 만들지 않고(GatewayMain.java:118-119, `KiwoomPluginTest#키가_없으면_설정_안_됨이고_소스를_만들지_않는다`), 기본 키는 비어 있다. 감시 종목 파일이 없으면 시장을 열지 않는다(Gateway.java:56, `GatewayTest#감시_종목이_없으면_시장을_열지_않는다`). 사용자가 키를 넣고 감시 종목을 만들어야 호출된다.
- P5·P6: **실패**. main의 D-033이 정한 조건("이전 기본값 저장 파일에서 TOSS 제거 이전 처리와 테스트 없이 토스 시세 클라이언트를 넣는 PR은 8.3 실패")을 충족하지 않는다. 지금은 운영 호출 경로가 없지만(`[사실]` P5 경로), 클라이언트 자체에 켜짐 옵션이 없어 이후 연결 PR이 `GatewayMain.plugin`의 `TOSS` 분기 하나만 바꿔도 이전 기본값을 저장한 사용자에게 명시적 동의 없이 호출된다.

### 8.4 폭주 방지 — 실패
- **발급 실패 경로에 인증 실패 보호가 적용되지 않는다.** `StreamingMarketDataSource.attempt`에서 `protocol.openingMessages()`(토큰·접속키 발급)가 예외를 던지면 `onConnectFailed`로 가서 일반 백오프만 쓴다(StreamingMarketDataSource.java:214-224, 235-247). `authFailures`는 WebSocket `LOGIN_FAILED`에서만 오르고(413-423행), 60초 최소 간격(337-340행)은 `authFailures > 0`일 때만 적용된다.
- 재현: 탐침 테스트에서 발급이 계속 실패하면 600초에 발급 시도 24회(처음 31초 안에 1·2·4·8·16초 간격 5회), `credentialsRejected()` false, `authFailures` 0. 하루 약 2,880회로 끝없이 이어진다. 잘못된 키·폐기된 키·만료된 앱 등록은 대부분 이 경로(발급 HTTP 401/403, `return_code != 0`)로 나타난다 `[추론]`.
- 영향: ADR-003 2.1의 "인증 실패 뒤 다음 접속(재발급)은 최소 60초", "연속 3회 실패하면 ERROR, DEGRADED"가 이 경로에서 지켜지지 않는다. 한국투자 토큰 재발급 1분 1회 제한(KisPlugin.java:49 주석, H4)을 처음 1분 안에 넘는다. 사용자에게는 30초마다 WARNING만 남고 키 확인 경보(ERROR)가 나오지 않는다.
- 기존 테스트 `StreamingMarketDataSourceTest#인증_준비_실패도_재연결_대기로_넘긴다`는 발급 실패 뒤 1초 만의 재시도를 정상으로 고정하고 있다.
- 그 밖: 재연결 백오프 1~30초 상한 있음(BackoffPolicy.java), 토스 폴러는 GCRA와 429 `Retry-After` 차단이 테스트로 검증됨(`TossPricePollerTest` 통과, 단 운영 경로 없음). circuit breaker(일정 횟수 뒤 정지)는 어느 경로에도 없다.

### 8.5 실주문 비용 — 통과
- 플러그인 3종 모두 주문 출구 없음(A2 근거), 게이트웨이는 주문 포트를 모름(ArchitectureTest 기존 규칙 통과). 이 PR로 실제 주문이 나갈 경로가 없다. 수수료·세금 반영 코드 변경 없음.

## 점검표 밖 발견
시도한 잘못된 주문·중복 주문·보호 공백 시나리오:

- **R-1 잘못된 키로 기동(확인됨).** 8.4 그대로. 수정 제안: 발급 실패(`openingMessages` 예외 중 인증 계열, 최소한 HTTP 401/403과 증권사 인증 오류 코드)를 `LOGIN_FAILED`와 같은 인증 실패로 세어 60초 하한과 3회 거부 판정을 적용하고, 테스트를 "발급 실패가 이어져도 발급은 1분에 한 번이고 3회면 거부"로 바꾼다. 네트워크 오류(접속 불가)와 구분할 필요가 있으면 `Fetcher`가 인증 거절을 별도 예외로 던지게 한다.
- **R-2 게이트웨이 기동 뒤 실전 계좌 등록 `[추론]`.** `reservedForRealTrading`은 게이트웨이 기동 때 한 번만 본다(GatewayMain.java:86, `start`에서만 호출). 게이트웨이가 돌고 있는 동안 같은 증권사 실전 계좌번호를 넣고 실전 엔진만 재기동하면, 게이트웨이는 같은 키로 계속 토큰을 재발급한다(만료·인증 실패 때마다). 키움은 토큰당 세션 1개이고 별도 토큰의 상호 무효화 여부가 실측 전(D-024, Q-015)이라, 실전 엔진 세션이 끊길 수 있다. 2단계는 플러그인에 주문 코드가 없어 지금 주문 피해는 없지만, 7단계 주문 연동 전에 (a) 계좌 교체 절차(CFG-05)에 게이트웨이 재기동을 넣거나 (b) 실전 엔진이 같은 키를 쓰는 게이트웨이 연결을 감지하면 기동을 거부하는 장치가 필요하다.
- **R-3 이전 기본값 저장 파일의 토스 `[사실+추론]`.** D-033이 지적한 상태(저장 파일에 `"priority": ["KIWOOM","KIS","TOSS"]`)에서 이 PR의 `TossPricePoller`가 연결되면 사용자 동의 없이 요금 미확인 API가 호출된다. 지금은 `GatewayMain.plugin`의 `TOSS -> Optional.empty()`(GatewayMain.java:125)와 `TossPlugin.create` 호출부 부재로 막혀 있을 뿐, 막는 장치가 코드·테스트로 고정돼 있지 않다.
- **R-4 병합 결과 미검토.** PR은 main과 충돌한다. 해소에는 보호 테스트 모듈(`autotrade-architecture-test/build.gradle.kts`)과 `ModuleOwnershipTest.OWNERS` 수정이 필요하다(B1). 개발 쪽 architect·risk-ops 최종 승인(`67e9ebf`)과 이 보고서(`0e9c2ad`)는 병합 뒤 상태를 다루지 않으므로, 병합 커밋에 대해 역할 검토와 독립 QA 재검토가 다시 필요하다.
- **참고(결함 아님).** `ArchitectureTest` 주석이 "플러그인이 출구 구현을 직접 생성하는 우회" 규칙 추가 시점을 2단계에서 "주문 코드를 넣는 첫 PR 전"으로 미뤘다. 지금 플러그인에 주문 코드가 없어 공백은 없지만, 7단계 첫 주문 PR의 QA에서 이 규칙 존재를 필수로 확인한다.

## 판정
수정 필요(B1, A14, 과금 8.1, 8.2, 8.3, 8.4)

1. main 병합 후 `ModuleOwnershipTest` 통과(OWNERS에 `com.autotrade.broker.kiwoom`·`kis`·`toss` 소유 모듈 추가, 충돌 해소), 병합 커밋으로 architect·risk-ops 재검토.
2. 토큰·접속키 발급 실패에도 인증 실패 보호(60초 하한, 3회 거부·ERROR) 적용과 그 테스트(R-1, 8.4).
3. 토스 시세 클라이언트: D-033의 이전 처리와 테스트를 함께 넣거나, `TossPricePoller`·`TossTokenClient`를 이 PR에서 빼고 연결 PR로 미룬다(A14, 8.3).
4. 키움·한국투자 **국내** 실시간 시세(와 토큰·접속키 발급) 요금의 공식 근거(포털 이용안내·약관 원문 URL과 확인일)를 조사 문서에 남긴다. 확인 못 하면 사용자 결정(지침 8장 1번)으로 올린다(8.1, 8.2).

## 재검토 2026-10-09
- 대상: PR #10 / 커밋 `87adb92` / main(`9576a19`) 대비 diff. main이 PR에 병합됨(`fd6892c`), 충돌 없음
- 점검표: A 전체 + B1, B6 + 과금 검사(QA 지침 8장). 직전 판정 뒤 변경(`fd6892c`, `e35b29f`, `90cf90e`, `e62b861`)과 main 대비 전체 diff를 다시 확인
- 직접 실행한 것:
  - `./gradlew build` @ `87adb92`: **BUILD SUCCESSFUL**, 테스트 516건(삭제된 토스 모듈의 이전 결과 7건 제외), 실패 0, 건너뜀 0. GitHub CI 3종(Gradle build, DB schema, Secret scan) 성공
  - 탐침 테스트(로컬 전용, 커밋 안 함) `QaProbe2Test`: `openingMessages()`가 계속 실패할 때 운영 백오프로 시뮬레이션
    - `CredentialsRejectedException`: 600초 5회, 하루 53회, `credentialsRejected()=true`
    - 일반 `IOException`(네트워크·5xx): 600초 11회, 하루 1,441회(분당 1회 상한), 거부 판정 없음
- 확인한 변경
  - 충돌 해소: `autotrade-architecture-test/build.gradle.kts`는 main과 같다(`git diff origin/main HEAD` 해당 파일 변경 없음). `ModuleOwnershipTest.OWNERS`에 `com.autotrade.broker.kiwoom`·`kis` 두 줄만 추가(ModuleOwnershipTest.java:111-112), 다른 단언·규칙 변경 없음
  - 토스 모듈(`autotrade-broker-toss`) 전체 삭제, settings에서 제외. 운영·테스트 코드에 `TossPricePoller`·`broker-toss` 참조 없음(grep). ADR-003 2.1·D-028이 "D-033 이전 처리와 함께 후속 연결 PR"로 갱신
  - 발급 실패 보호: 실패 원인과 무관하게 다음 발급 60초 하한(`issuanceFailed`), 401·403과 키움 `return_code≠0`은 `CredentialsRejectedException`으로 인증 실패에 산입, 3회 뒤 2·4·8·16·30분(StreamingMarketDataSource.java 223-240, 361-372행 부근). 테스트 `#인증_준비_실패도_재연결_대기로_넘기고_다음_발급은_1분_뒤다`, `#발급이_자격_증명_거절로_이어지면_1분_간격_3회_뒤_거부로_보고_간격을_30분까지_늘린다`, `KisStreamProtocolTest#접속키_발급이_401_403이면_자격_증명_거절로_알리고_그_밖의_실패는_구분한다` 통과
  - 요금 조사: `docs/research/market-data-limits.md` 1.5절. 키움·한국투자 국내 실시간과 토큰·접속키 발급 모두 공식 자료에서 무료·유료 문구를 찾지 못해 "확인 못 함". Q-015에 "키 등록 전 선행: 요금, 사용자 결정"으로 올림

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·설정 기본값 코드가 main과 같다(`git diff origin/main HEAD -- autotrade-risk autotrade-config` 변경 없음). 전체 빌드 통과 |
| A2 | 통과 | `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY` 본문 불변(주석만), 키움·한투 `orderGateway()` 빈 값, 주문 엔드포인트 없음 |
| A3 | 통과 | 주문 전환 경로 없음. 재배분은 구독만 옮김 |
| A4 | 해당 없음 | 주문 재전송 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·RiskChain 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 게이트웨이 별도 프로세스, `reservedForRealTrading` 유지·테스트 통과. 기동 시 1회 판단(R-2)은 그대로이며 7단계 전 과제로 남음 |
| A9 | 통과 | 보유 종목 우선 재배분 유지(`SourcePlannerTest`, `GatewayTest` 통과). 긴 재시도 간격은 거부된 소스에만 걸리고 그 소스는 DEGRADED로 배분에서 빠진다 |
| A10 | 통과 | 새 예외 메시지는 상태 코드·`return_code`만 담는다(KiwoomTokenClient, KisApprovalClient). 비밀값 패턴 검색 결과 없음, CI Secret scan 성공 |
| A11 | 통과 | 보호 테스트 변경은 `ArchitectureTest` 규칙 추가와 OWNERS 두 줄 추가뿐, 삭제·약화 없음. 바뀐 이 PR 테스트 2건은 더 엄격한 단언(정확한 횟수, 하루 52~54회)으로 바뀌었다 |
| A12 | 통과 | 장애표 7번(인증 실패 → DEGRADED, 시세 소스만 전환) 유지. 거부 뒤 ERROR 로그가 발급 거절 경로에서도 나온다(`countAuthFailure`) |
| A13 | 통과 | 시장 규칙·캘린더 변경 없음, 관련 테스트 통과 |
| A14 | 통과 | 토스 클라이언트를 빼서 D-033과 충돌하지 않는다. D-028·ADR-003·Q-015가 코드와 일치 |
| B1 | 통과 | `87adb92` 빌드 직접 실행 성공, main이 병합된 상태 |
| B6 | 통과 | 새 동작(발급 거절 산입, 60초 하한, 거부 뒤 간격 확대, 401·403 구분)에 테스트 있음. 건너뛰거나 지운 테스트 없음(토스 테스트는 토스 코드와 함께 삭제) |

### 과금 검사
| # | 결과 | 근거 |
|---|---|---|
| 8.1 | 실패(확인 못 함) | 남은 유료 후보 호출: 키움 토큰 발급(KiwoomTokenClient), 키움 실시간 WebSocket, 한국투자 접속키 발급(KisApprovalClient), 한국투자 실시간 WebSocket. 호출 경로는 첫 보고서 8.1의 P1~P4와 같다. 토스 P5·P6은 삭제됐다. 요금은 개발 쪽 조사(1.5절)와 QA 확인 모두 공식 근거를 얻지 못했다. 8장 규칙상 "확인 못 함"은 실패다 |
| 8.2 | 실패(확인 못 함) | 유료 여부를 모르므로 무료 대체 수단 비교를 판정할 수 없다 |
| 8.3 | 통과 | 기본 키가 비어 있으면 플러그인이 소스를 만들지 않고, 감시 종목 파일이 없으면 시장을 열지 않는다. 사용자가 키와 감시 종목을 넣어야만 호출된다. 토스 클라이언트는 이 PR에 없다 |
| 8.4 | 통과 | 발급 실패 60초 하한, 자격 증명 거절 3회 뒤 2~30분 지수 간격(하루 53회), WebSocket 재접속 1~30초 백오프. 탐침과 테스트로 확인. 남은 위험: 일반 발급 실패(네트워크·5xx)는 분당 1회로 상한 없이 이어진다(하루 1,441회). 분당 1회라 한국투자 1분 1회 제한 안이며 폭주로 보지 않는다. 7단계 감시 경보 연결 때 "연속 발급 실패" 경보를 권고 |
| 8.5 | 통과 | 주문 코드 없음, 실주문 경로 없음 |

### 점검표 밖 (재검토)
- R-1 해결: 위 8.4.
- R-2 그대로(7단계 전 필수 권고): 게이트웨이의 실전 키 회피는 기동 시 1회 판단. 계좌 교체 절차(CFG-05)에 게이트웨이 재기동을 넣거나 실전 엔진이 키 공유를 감지해 막는 장치가 필요하다.
- R-3 해소: 토스 클라이언트 삭제.
- R-4 해소: 병합 커밋 기준 architect(7차)·risk-ops(6차) 원문이 있다(`e62b861`). 이 재검토도 병합 뒤 커밋 기준이다.
- 키움 `return_code≠0`을 모두 자격 증명 거절로 본다 `[가정]`. 레이트리밋 코드(예: 1700)도 거절로 세어 간격이 늘어나는 쪽이라 과금·폭주 측면에서는 안전하고, 가용성만 늦게 회복된다.

### 판정
수정 필요(과금 8.1, 8.2)

- 코드·테스트·문서의 앞선 수정 필요 항목(B1, A14, 8.3, 8.4)은 모두 해결됐다.
- 남은 것은 키움·한국투자 국내 실시간 시세와 토큰·접속키 발급의 요금 확인뿐이다. 공개 페이지로는 확인할 수 없어(약관·신청 화면이 로그인·팝업 뒤에 있음) **사용자가 직접** 키움 "오픈 API 서비스 이용약관"과 KIS Developers 이용약관·API 신청 화면에서 요금 조항을 확인해야 한다.
- 무료로 확인되면 근거(URL·문구·확인일)를 조사 문서 1.5절에 남기고 재검토를 요청한다. 확인할 수 없거나 유료이면 사용자가 그 조건으로 쓸지 결정해 결정 로그에 남긴 뒤 재검토를 요청한다(지침 8장 1번).

## 재검토 2026-10-09 (2) 과금 8.1·8.2
- 대상: PR #10 / 커밋 `87adb92`(코드 변경 없음, 직전 재검토와 같은 커밋) / main(`9576a19`) 대비 diff
- 새 근거: 사용자(BAE)가 2026-10-09 17:13 KST 이 스레드에서 "키움 '오픈 API 서비스 이용약관'과 KIS Developers 이용약관·API 모두 무료"라고 확인했다. 로그인·팝업 뒤라 QA가 직접 열 수 없던 공식 약관을 사용자가 열어 본 결과다. 약관 원문 문구와 URL은 QA가 보지 못했다.

| # | 결과 | 근거 |
|---|---|---|
| 8.1 | 통과 | 유료 후보 호출 4곳(키움 토큰 발급, 키움 실시간 WebSocket, 한국투자 접속키 발급, 한국투자 실시간 WebSocket)의 요금을 사용자가 공식 약관에서 무료로 확인. 호출 경로 목록은 첫 보고서 8.1 P1~P4 |
| 8.2 | 통과 | 무료이므로 유료 경로를 대체할 필요가 없다. 실시간 수신은 2단계 목표 자체라 필요하다 |

나머지 항목(A1~A14, B1, B6, 8.3~8.5)은 직전 재검토 결과 그대로다(코드 변경 없음).

### 남은 후속(막지 않음)
- 조사 문서 `docs/research/market-data-limits.md` 1.5절과 결정 로그 Q-015는 아직 "확인 못 함"이다. 개발 쪽에서 사용자 확인(약관명, 확인일, 가능하면 조항 문구)을 1.5절에 적고 Q-015의 요금 선행 조건을 닫아야 저장소 기록이 이 판정과 맞는다.
- R-2(게이트웨이 실전 키 회피를 기동 시 1회만 판단)는 7단계 전 필수 과제로 남는다.

### 판정
승인. 점검표 A·B1·B6과 과금 검사에 실패와 확인 못 함이 없다. 이 PR은 실전 관련 PR이라 머지는 사용자가 직접 한다.
