# risk-ops 검토: 2단계 시세: 키움·한투 실시간 수신, 녹화·리플레이, 거래소 캘린더, 일봉 스냅샷
- 대상: PR #10 / 커밋 4160936 (416093614e33b565500e4b96b7f40f2572e95a6c) / main 대비 diff (`git diff origin/main...HEAD`, 100개 파일, +7490 −14)
- 점검표: A
- 검토일: 2026-10-09
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL, 104개 작업 전부 실행(캐시 없음). 모듈별 테스트(실패·오류·건너뜀 0): risk 41, architecture-test 13, app-engine 7, app-gateway 15, marketdata 61, broker-kis 7, broker-kiwoom 13, broker-toss 7, broker-api 1, config 29, domain 27, sim 59, strategy 25, app-lab 86, app-watchdog 1
  - `git diff --name-status origin/main...HEAD`: 삭제 파일 0, 수정된 테스트 파일은 `ArchitectureTest.java` 1개(규칙 추가만, 삭제 줄 0)
  - `autotrade-risk`, `autotrade-config`, `autotrade-app-engine` 아래 변경 파일 0
  - 새 main 코드의 엔드포인트 전수 grep: 키움 토큰·WebSocket, 한투 접속키·WebSocket, 토스 토큰·현재가뿐. 주문 엔드포인트 0
  - GitHub check-run(4160936): Secret scan and collector boundary 성공, DB schema 성공, Gradle build 진행 중(로컬에서 직접 통과 확인)
- 검토 범위: diff 전체와, 그 변경이 닿는 실전 경로(BrokerPlugin 계약, ArchUnit 주문 출구 규칙, RealTradingGuard·GuardedOrderGateway, RuntimeConfig 증권사 키·실전 계좌 구조, 프로세스 격리 설정). PR 본문·커밋 메시지·ADR 서술은 근거로 쓰지 않고 코드와 실행 결과로 판정했다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard·설정 로딩 코드 변경 0(`autotrade-risk`, `autotrade-config` diff 없음). 재실행 통과: `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `#OFF_계좌는_주문_세션_허가를_받을_수_없다`, `GuardedOrderGatewayTest#OFF_계좌로는_출구를_만들_수_없다` |
| A2 | 통과 | 기존 규칙 `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`(ArchitectureTest.java:130)·`GATEWAY_HAS_NO_ORDER_PORT`(:121) 변경 없음, 새 플러그인 3개가 architecture-test 클래스패스에 들어가 규칙 대상이 됨(autotrade-architecture-test/build.gradle.kts:11). `실전_주문_출구는_Guard_팩토리만_연다`, `시세_게이트웨이는_주문_포트를_모른다`, `규칙은_위반을_실제로_잡아낸다` 통과. 세 플러그인의 `orderGateway`는 항상 `Optional.empty()`(KiwoomPlugin.java:89, KisPlugin.java:90, TossPlugin.java:81), `OrderGateway` 구현 클래스 0개(grep). 게이트웨이 테스트 가짜 플러그인은 `orderGateway` 호출 시 `AssertionError`(FakePlugin.java:66, MD-01 계측) |
| A3 | 통과 | 주문 경로 없음. 시세 전환(MarketHub.replan, SourcePlanner.plan)은 `MarketDataSource` 구독만 옮기고 `orderGateway`를 부르지 않음(MarketHub.java:98-210). 주문 전환 코드 0 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음(`autotrade-domain/.../order`, `autotrade-risk` diff 0) |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음. 새 `KrxMarketRules`/`UsMarketRules`는 `MarketRules.tradingDate` 기본 구현(현지 시간대 날짜, 예외 없음)을 그대로 씀(MarketRules.java:56) |
| A6 | 해당 없음 | 주문·청산·복구 경로 변경 없음. 게이트웨이 `watchlist.json`의 held는 구독 우선순위에만 쓰임(WatchList.java, SourcePlanner.java:69-73) |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음. 참고: 토스 폴러는 자체 GCRA(150ms, 버스트 4)로 MARKET_DATA 그룹 한도(초당 15회)의 절반 이하만 씀(TossPricePoller.java:43-48), `TossPricePollerTest#평균_간격과_순간_허용량을_넘으면_기다리지_않고_거절한다`, `#레이트리밋_429면_Retry_After까지_묻지_않는다` 통과 |
| A8 | 통과 | 엔진의 금지 의존에 `:autotrade-app-gateway` 유지(autotrade-app-engine/build.gradle.kts:13), 엔진은 새 플러그인 모듈에 의존하지 않음. 게이트웨이→엔진 IPC 없음(ADR-003 5장, Gateway.java 녹화만). `프로세스_진입점끼리_참조하지_않는다` 통과. 게이트웨이 내부 큐는 크기 제한(디스패처 5만, 녹화 10만, 넘치면 버리고 DataGap). 단, 7단계에 생길 키 공유 문제는 점검표 밖 R1 |
| A9 | 통과 | 기존 보호 수단을 줄이는 변경 없음. 새로 생긴 보호 시세 경로 `PollingQuoteSource`/`TossPricePoller`는 아직 엔진에 연결되지 않음(호출처 grep 0). 보유 종목은 구독 한도에서 빼지 않고 다음 소스로 넘기며, 못 받으면 `unservedHeld` ERROR(SourcePlanner.java:69-73, MarketHub.java:122). `SourcePlannerTest#FAILOVER라도_넘치는_보유_종목은_다음_소스로_보내고_후보는_보내지_않는다`, `#모든_소스가_장애면_보유_종목_전체가_경보_대상이다`, `GatewayTest#키움이_끊기면_보유_종목을_한투로_옮기고_전환_공백을_녹화한다` 통과. 7단계 전에 메울 공백은 점검표 밖 R2·R4 |
| A10 | 통과 | `BrokerCredentials.toString`·`TokenCache.Issued.toString` 마스킹(BrokerCredentials.java:25, TokenCache.java:30). 로그·예외 메시지 전수 grep: 예외는 클래스 이름·HTTP 상태·증권사 결과 코드만, 토큰·키·본문 없음(StreamingMarketDataSource.java:205,211 주석 포함). 테스트 픽스처는 `TEST-TOKEN-VALUE`, `TOSS-TOKEN` 같은 자리표시자. CI gitleaks 성공 |
| A11 | 실패 | 보호 테스트 삭제·비활성화·약화 없음(삭제 줄 0, @Disabled 추가 0). 새 동작 대부분은 실패 경우 테스트가 있음(접속 실패·인증 실패·무응답·큐 넘침·한도 초과·429·401). 그러나 새 보호 규칙 `PLUGINS_ARE_INDEPENDENT`(ArchitectureTest.java:157)는 위반을 실제로 잡는지 보이는 픽스처가 없다. 같은 파일의 Guard 규칙은 `규칙은_위반을_실제로_잡아낸다`로 고정했는데(ArchitectureTest.java:253-258, ADR-002 8장 B-1에서 규칙이 실제로 0건을 잡던 전례), 새 규칙은 빈 규칙이어도 통과한다 → R3 |
| A12 | 통과 | 대응표·장애 테스트 전제를 깨는 변경 없음. 장애표 1번 감지 기준(수신 간격 3초)에 대해 조용한 끊김은 90초 무응답 판단 시간으로만 잡힌다는 한계를 ADR-003 2.2·4장에 적었고 Q-015에 "무응답 판단 시간" 확인을 올렸다(docs/decisions/README.md Q-015). 장애 테스트 1번의 합격 여부는 7단계에서 판정해야 하며 이 PR은 그 합격을 주장하지 않는다 |
| A13 | 통과 | 시장 차이는 `KrxMarketRules`/`UsMarketRules`와 캘린더 데이터(krx.json, us.json)로만. 시간대는 캘린더 `zone`(Asia/Seoul, America/New_York), 녹화 날짜도 거래소 현지 날짜(Gateway.java:63). 금액은 `BigDecimal`(LastPrice, RawQuote, 호가 단위 표), `금액에_실수형을_쓰지_않는다` 통과. `ExchangeCalendarTest#미국_정규장은_서머타임을_시간대_데이터로_따른다`, `#미국_조기_폐장일은_13시에_닫는다`, `#미국_장은_한국_날짜로_이틀에_걸쳐도_현지_날짜로_판단한다` 통과(O3·O7 데이터 전제) |
| A14 | 실패 | D-028·Q-015 기록은 있음. 그러나 게이트웨이가 `brokers.kiwoom`/`brokers.kis` 키로 토큰 발급과 WebSocket 접속을 하는데(GatewayMain.java:93-103(`plugin`)), 설정 구조상 이 키는 같은 증권사 실전 계좌가 쓸 키와 같다(RuntimeConfig.java:93-123, 계좌별 키 없음). D-024는 "실전 계좌의 앱키·토큰은 실전 엔진 전용이며, 게이트웨이·실험실은 그 키로 토큰 발급, WebSocket 연결, REST 호출을 하지 않는다", "키움 별도 토큰은 다른 토큰을 무효화하지 않음을 실측한 뒤에만 허용"이라고 정했다(broker-and-fault-design.md 4.1·4.2). 코드는 이 제약을 지키는 장치가 없고, ADR-003은 토스 토큰 문제만 적고(4장, 6장 risk-ops 7단계 요구) 키움·한투 키 공유는 적지 않았다 → R1 |

## 점검표 밖 발견

**수정 필요(이 PR에서)**

- **R1. 게이트웨이와 실전 계좌의 키 공유(D-024)를 기록하고 막을 지점을 정한다.** 지금은 엔진이 증권사에 붙지 않아 계좌 피해 경로가 없다. 다만 7단계에서 키움 실전 계좌를 같은 키로 켜면, 게이트웨이의 `LOGIN_FAILED` 재발급 루프(StreamingMarketDataSource.java:374, 최대 30초 간격)가 엔진 토큰이나 세션을 밀어낼 수 있다(market-data-limits.md 55행 `[추론]`). 최소 조치: ADR-003 4장(위험)과 6장 "risk-ops 7단계 요구 사항"에 "실전 계좌가 설정된 증권사 키로는 게이트웨이가 토큰 발급·WebSocket 접속을 하지 않는다. 시세 전용 키 분리 또는 키움 다중 토큰 무효화 여부 실측(Q-015) 전에는 기동 검사로 막는다. 엔진을 증권사에 연결하는 PR의 선행 조건"을 추가한다. 추천안: 이 PR에서 바로 `GatewayMain`에 기동 검사를 넣는다. `real-trading.accounts`에 같은 증권사 계좌가 있고 계좌번호까지 설정되어 있으면(`RealTrading.isConfigured`) 그 증권사 실시간을 띄우지 않고 WARNING을 남긴다. 실패 경우 테스트도 함께 둔다. 이 검사는 실전 영향 규칙을 *강화*하는 쪽이라 D-024와 충돌하지 않는다.
- **R2. 구독 거절이 장애 전환과 경보에 걸리지 않는 문제를 후속 과제로 기록한다.** `SUBSCRIBE_REJECTED`는 건수만 세고 연결을 유지한다(StreamingMarketDataSource.java:379-382). 그래서 (a) 보유 종목 구독이 거절돼도 소스는 CONNECTED·ACTIVE로 남는다. 이때 재배분, `unservedHeld` 경보, DataGap이 모두 일어나지 않는다. (b) 한투는 접속키 오류도 구독 응답의 `rt_cd`로 오므로(KisStreamProtocol.java:156-159) `invalidateCredentials`가 불리지 않는다. PINGPONG이 수신 시각을 갱신하면 무응답 재연결도 일어나지 않아, 무효 접속키가 24시간 캐시 만료까지 남을 수 있다 `[추론]`(실제 응답 형식은 Q-015 확인 대상). ADR-003 5장에 담당 data-engineer로 "거절된 종목의 재배분·경보, 한투 인증 계열 거절 시 접속키 폐기"를 추가한다. 보유 종목은 7단계 종목별 수신 공백 감시(장애표 15번)가 마지막 방어선이라는 점도 함께 적는다.
- **R3. `증권사_플러그인끼리_참조하지_않는다` 규칙에 위반 픽스처를 더한다.** `com.autotrade.archfixture` 아래에 서로 참조하는 두 플러그인 패키지 픽스처를 만들고, `규칙은_위반을_실제로_잡아낸다`처럼 위반 건수를 고정하는 테스트를 추가한다(A11).

**권장(막지 않음, 7단계 전 필수 목록으로 넘김)**

- R4. 보호 시세 경로가 토스 하나뿐이다. 장애표 2번은 "가능한 증권사 아무거나"로 REST 폴링이고, 능력표 3.2는 키움·한투도 POLLING_QUOTE를 O로 둔다. 하지만 이 PR의 키움·한투 플러그인은 `pollingQuotes()`를 구현하지 않는다. 토스 키가 없는 사용자는 실시간이 끊기면 보호 시세가 없다. 엔진 보호 경로 PR 전에 키움·한투 폴링을 더하거나, 실전 활성화 화면 경고 대상에 넣는다.
- R5. `PollingQuoteSource` 계약에 두 가지를 적는다. 하나, 호출자는 `priceTime`으로 가격의 신선도를 판정해야 한다(거래정지·VI 중 옛 가격이 그대로 온다). 둘, 한 번의 `poll`이 여러 호출로 나뉘다가 중간에 `RateLimitedException`이 나면 앞서 받은 결과까지 버려진다(TossPricePoller.java:82-91). 보호 감시 루프는 부분 결과가 없다고 가정해야 한다.
- R6. `ExchangeCalendar`는 수록 범위 밖에서 예외를 던진다(KRX는 2026년만). 엔진이 7단계에 `isRegularSession`을 쓰게 되면 이 예외는 신규 진입만 막고 보호 청산(손절)은 막지 않도록 처리해야 한다. 감시 프로세스에는 수록 범위 종료 30일 전 경보를 둔다.
- R7. 한투 WebSocket이 평문 `ws://`라 접속키가 평문으로 나간다(ADR-003 4장에 기록됨). 같은 앱키로 실전 계좌를 운용할 경우 접속키로 체결 통보 구독이 가능한지 7단계 전에 확인한다(기존 7단계 요구 "계좌 정보 실시간은 ws:// 금지"와 연결).
- R8. ADR-002 6장·ArchitectureTest.java:127 주석이 예고한 "플러그인이 주문 출구 구현을 직접 생성하는 우회 차단 규칙"은 이 PR에서 미뤘다(ADR-003 5장에 기록됨). 지금은 주문 코드가 없어 우회 경로가 없다. 플러그인에 주문 코드를 넣는 첫 PR보다 먼저 이 규칙이 들어가야 한다.

## 판정
수정 필요(A11 → R3, A14 → R1, 점검표 밖 R2). R1은 문서 기록이 최소 요건이고 기동 검사 코드는 추천안이다. R2는 ADR-003 5장에 기록하면 된다. R3은 테스트 추가다. 셋 다 반영되면 재검토한다. 이 PR은 증권사 플러그인·장애 전환·보호 시세 경로를 건드리는 실전 관련 PR이라, risk-ops 승인 뒤에도 사용자 직접 확인과 독립 QA(D-031)를 거쳐 머지한다.
