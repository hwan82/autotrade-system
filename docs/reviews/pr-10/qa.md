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
