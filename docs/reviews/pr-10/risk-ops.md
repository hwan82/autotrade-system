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

## 재검토 2026-10-09
- 대상: PR #10 / 커밋 1ea693e (`git diff origin/main...1ea693e` 전체. 직전 검토 878f49c 이후 변경은 4f3c5b2 architect 검토 원문, 1ea693e 검토 반영)
- 점검표: A
- 직접 실행한 것:
  - 1ea693e를 별도 작업 트리로 꺼내 `./gradlew build --rerun-tasks` 실행 → BUILD SUCCESSFUL, 104개 작업 전부 실행. 공유 체크아웃에 있는 추적되지 않은 다른 검토자의 스크래치 테스트는 빠진 상태로 돌렸다. 모듈별 테스트(실패·오류·건너뜀 0): risk 41, architecture-test 13, app-engine 7, app-gateway 19, marketdata 62, broker-kis 7, broker-kiwoom 13, broker-toss 7, config 29, domain 27, sim 59, strategy 25, app-lab 86, broker-api 1, app-watchdog 1
  - 실험 1(일회용 테스트, 실행 후 삭제): `requiresLoginAck()=false`인 프로토콜(한투와 같은 방식)로 매 접속마다 인증 실패 응답을 주고 60초를 돌렸다. 결과는 접속 61회, 자격 증명 발급 61회, 폐기 60회
  - 실험 2(작업 트리에서만, 되돌림): 1ea693e가 `GatewayTest#키움이_끊기면_보유_종목을_한투로_옮기고_전환_공백을_녹화한다`에서 지운 단언 `gap.reason() == SOURCE_SWITCH`를 되살려 실행 → 통과

### 직전 지적 반영 확인
| 지적 | 결과 | 근거 |
|---|---|---|
| R1 (A14) 실전 계좌 키 공유 | 반영 | `GatewayMain.reservedForRealTrading`(GatewayMain.java:107)은 계좌번호까지 설정된 실전 계좌의 증권사를 모은다. 그 증권사는 게이트웨이가 실시간을 띄우지 않고 WARNING만 남긴다(:86-92). `GatewayMainTest#계좌번호까지_설정된_실전_계좌의_증권사는_게이트웨이가_쓰지_않는다` 통과. 계좌번호 없는 계좌는 `RealTrading.isConfigured`가 false라 막지 않는데, 활성화도 할 수 없는 상태라 타당하다. ADR-003 4장·6장에 기록됨. `real-trading.*`은 실시간 변경이 거부된다(RuntimeConfigHolder.java:45) |
| R2 구독 거절, 한투 접속키 | 부분 반영(기록은 충족) | 거절된 종목의 재배분·경보는 ADR-003 5장에 후속 과제로 기록됨. 한투 접속키 오류(`OPSP0011`, "invalid approval")를 `LOGIN_FAILED`로 바꿨다(KisStreamProtocol.java:160-162, 174). `KisStreamProtocolTest` 통과. **다만 이 변경으로 새 결함 N1이 생겼다(아래)** |
| R3 (A11) 플러그인 독립 규칙 위반 픽스처 | 반영 | `com.autotrade.broker.fixturea.PluginA`→`fixtureb.PluginB` 픽스처로 위반을 잡고 broker-api 참조는 위반이 아님을 확인(ArchitectureTest.java:267-272). `규칙은_위반을_실제로_잡아낸다` 통과 |
| R4~R8 (권장) | 7단계 전 필수 목록에 기록 | ADR-003 6장 "risk-ops 7단계 요구 사항" 마지막 항목. R8은 ArchitectureTest.java:130 주석도 고침 |

### 점검표 A (1ea693e 기준)
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-config` 변경 0(main 대비). `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `GuardedOrderGatewayTest#OFF_계좌로는_출구를_만들_수_없다` 재실행 통과 |
| A2 | 통과 | `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`·`GATEWAY_HAS_NO_ORDER_PORT` 규칙 본문 변경 없음(주석만 바뀜). `실전_주문_출구는_Guard_팩토리만_연다`, `시세_게이트웨이는_주문_포트를_모른다` 통과. 플러그인 `orderGateway`는 모두 빈 값, `OrderGateway` 구현 0개 |
| A3 | 통과 | 주문 전환 경로 없음. 시세 재배분(MarketHub)은 구독만 옮긴다 |
| A4 | 해당 없음 | 주문 타임아웃 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 주문·청산·복구 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진의 금지 의존에 게이트웨이 유지, IPC 없음. R1 기동 검사로 실전 계좌 키를 게이트웨이가 쓰지 않는다. N1 루프도 게이트웨이 안에 갇히고, 실전 계좌 키에는 닿지 않는다(R1 검사 때문) |
| A9 | 통과 | 보호 수단 약화 없음. 받을 소스를 잃은 보유 종목의 공백이 이제 녹화에 남는다(MarketHub `lost`). `GatewayTest#모든_소스가_끊겼다가_돌아오면_받지_못한_구간을_남긴다`, `#받을_소스_없이_끝나면_종료_시각까지_공백을_남긴다` 통과 |
| A10 | 통과 | 새 로그는 증권사 ID만 찍는다(GatewayMain.java:90). 한투 인증 실패 detail은 `msg_cd`만 쓴다(KisStreamProtocol.java:162). 픽스처 계좌번호는 `<ACCOUNT_NUMBER>` 자리표시자(GatewayMainTest) |
| A11 | 실패 | 1ea693e가 기존 테스트 `GatewayTest#키움이_끊기면_보유_종목을_한투로_옮기고_전환_공백을_녹화한다`의 단언 `assertThat(gap.reason()).isEqualTo(DataGap.GapReason.SOURCE_SWITCH)`를 지웠다(GatewayTest.java:111 부근). 실험 2에서 되살려도 통과하므로 지울 필요가 없었던 약화다 → N2. 또 N1의 새 동작(인증 실패 반복)에는 실패 경우 테스트가 없다 |
| A12 | 실패 | 장애표 7번(토큰 만료·인증 실패)의 대응은 "선제 갱신, 실패 시 해당 증권사 DEGRADED"이고, 장애 테스트 7번의 합격 기준은 "선제 갱신 성공 또는 DEGRADED 전환 후 알림"이다. 1ea693e 뒤 한투 소스는 인증 실패가 반복되면 약 1초마다 접속키를 새로 받는다. 실험 1에서 60초 동안 61회였고 상한이 없으며 DEGRADED로 수렴하지도 않는다 → N1 |
| A13 | 통과 | 스냅샷 기준일을 거래소 현지 날짜로 고침(DailyBarQualityCheck `localDate`), `SnapshotDailyBarSourceTest#스냅샷_기준일은_생성_시각의_거래소_현지_날짜다` 통과. 기존 US 캘린더 테스트 통과 |
| A14 | 통과 | D-024 키 공유를 ADR-003 4장·6장에 기록하고 코드로 강제함. 한투 접속키 오류 처리와 Q-015 확인 항목이 ADR 5장과 일치 |

### 점검표 밖 발견 (재검토)
**수정 필요**
- **N1. 한투 인증 실패 루프(1초 간격, 상한 없음).** 원인은 세 가지다.
  - 한투 프로토콜은 `requiresLoginAck()=false`다. 그래서 소켓이 열리자마자 `becomeReady()`가 불리고 재연결 대기가 초기값으로 돌아간다(StreamingMarketDataSource.java:250, 259-261).
  - 그 뒤 구독 응답으로 `LOGIN_FAILED`가 오면 자격 증명을 버리고(:381) 1초 뒤 다시 접속한다. 다시 접속할 때 `openingMessages()`가 접속키를 새로 받는다.
  - 결과적으로 인증 오류가 계속되면(잘못된 키, 모의 도메인 키, 서버 쪽 제한 등) `/oauth2/Approval`을 1초마다 영구히 부른다. 실험 1에서 60초 동안 61회였다. 키움은 LOGIN 응답을 기다리므로 대기가 30초까지 늘어나 이 문제가 없다.
  - 한투 토큰 재발급 1분 1회 제한(H4)이 접속키에도 걸리는지는 Q-015 미확인이다 `[가정]`. 걸리면 앱키가 제한·차단될 위험이 있고, 장애표 7번의 "DEGRADED 전환" 전제와도 어긋난다.
  - 요구:
    - (a) 인증 실패로 끊긴 경우에는 재연결 대기를 초기화하지 않는다. 예를 들어 첫 시세 수신이나 첫 구독 성공 응답 전까지 `backoff.reset()`을 미루거나, 연속 인증 실패 횟수를 따로 센다.
    - (b) 한투 접속키 재발급 간격에 하한을 둔다. 추천은 60초로, H4 제한을 보수적으로 적용한 것이다 `[가정]`.
    - (c) 연속 인증 실패가 N회(추천 3회)를 넘으면 소스를 DEGRADED로 보고 경보 로그를 남긴다.
    - (d) 같은 프로토콜 모양(로그인 응답 없음 + 인증 실패 반복)으로 접속·발급 횟수가 상한 안에 머무는지 확인하는 실패 경우 테스트를 둔다.
- **N2. 지운 단언을 되살린다.** `GatewayTest#키움이_끊기면_보유_종목을_한투로_옮기고_전환_공백을_녹화한다`에서 하이닉스·삼성 공백의 `reason()`이 `SOURCE_SWITCH`인지 보는 단언을 원래대로 둔다. 카카오처럼 원인이 다른 종목은 이미 따로 걸러 검사하므로 지울 이유가 없다(실험 2에서 통과 확인).

**권장(막지 않음)**
- N3. R1 기동 검사는 게이트웨이를 기동할 때만 돈다. 7단계에서 사용자가 `secrets.json`에 계좌번호를 넣고 실전 엔진만 재기동하면, 이미 떠 있는 게이트웨이는 같은 키를 계속 쓴다. CFG-05 계좌·키 교체 절차에 "게이트웨이 재기동"을 넣는다. 또는 엔진 기동 때 감시 프로세스가 게이트웨이의 증권사별 연결을 대조하게 한다(장애표 17번). 7단계 전 필수 목록에 더한다.
- N4. `KisStreamProtocol.isAuthFailure`의 코드 목록은 `[가정]`(OPSP0011)이다. Q-015 실측에서 실제 인증 오류 코드와 "MAX SUBSCRIBE OVER" 같은 한도 오류가 섞이지 않는지 확인한다.

### 판정
수정 필요(A11 → N2, A12 → N1). R1·R3은 반영됐고 R2는 기록 요건을 충족했다. N1은 이번 수정이 새로 만든 결함이고, N2는 기존 테스트 약화다. 둘을 고치면 다시 재검토한다. 이 PR은 실전 관련 PR이다. risk-ops 승인 뒤에도 사용자가 직접 확인하고, 독립 QA(D-031)를 거쳐 머지한다.

## 재검토 2026-10-09 (2차)
- 대상: PR #10 / 커밋 eecb2d2 (`git diff origin/main...eecb2d2` 전체. 직전 재검토 01231f5 이후 코드 변경은 eecb2d2뿐이다. 그 뒤 ae90625는 `.gitignore` 한 줄이라 판정 대상에서 뺐다)
- 점검표: A
- 직접 실행한 것:
  - eecb2d2를 별도 작업 트리로 꺼내 `./gradlew build --rerun-tasks` 실행 → BUILD SUCCESSFUL, 104개 작업 전부 실행. 모듈별 테스트(실패·오류·건너뜀 0): risk 41, architecture-test 13, app-engine 7, app-gateway 19, marketdata 63, broker-kis 7, broker-kiwoom 13, broker-toss 7, config 29, domain 27, sim 59, strategy 25, app-lab 86, broker-api 1, app-watchdog 1
  - main 대비 테스트 파일에서 지운 줄: ArchitectureTest 주석 1줄뿐. `@Disabled`·`assume*` 추가 0
  - 실험 3(일회용 테스트, 실행 후 삭제): 로그인 응답 없는 프로토콜에서 서버가 구독 메시지에만 인증 실패로 답하게 했다. `credentialsRejected()`가 참이 되면 허브처럼 구독을 닫고 30분 돌렸다. 거부 판정은 접속 3회째에 났다. 그 뒤에도 접속은 이어졌는데, 가짜 서버가 PING을 보내지 않아 90초 무응답 재연결이 난 것이다. 구독이 없으니 인증 실패 응답은 더 오지 않고 자격 증명 폐기도 일어나지 않는다. `TokenCache`는 폐기 전까지 캐시를 쓰므로 접속키 재발급은 멈춘다 `[추론]`(TokenCache.get 캐시 조건). 실제 한투 서버는 PINGPONG을 보내므로 연결이 유지된다 `[가정]`(Q-015)

### 직전 지적 반영 확인
| 지적 | 결과 | 근거 |
|---|---|---|
| N1 (A12) 한투 인증 실패 루프 | 반영 | (a) 로그인 응답이 없는 프로토콜은 열리자마자 `becomeReady`로 가도 재연결 대기를 초기화하지 않는다. 첫 시세가 와야 확인한다(StreamingMarketDataSource.java:271-273, 432, `confirm` :292). (b) 인증 실패 뒤 다음 접속은 최소 60초 뒤다(:46, :338-339). (c) 연속 3회 실패하면 ERROR 로그를 남기고 `credentialsRejected()`가 참이 된다(:48, :303, :417). 키움·한투 플러그인은 이때 DEGRADED를 돌려준다(KiwoomPlugin.java:64, KisPlugin.java:65). 허브는 DEGRADED 소스를 배분에서 뺀다(MarketHub `replan`). (d) `StreamingMarketDataSourceTest#로그인_응답이_없는_프로토콜에서_인증_실패가_이어져도_재발급은_1분에_한_번이고_3회면_거부로_본다` 통과. 10분 동안 접속 10~11회로 직전 측정 61회/분에서 줄었다. `#인증이_실패하면_토큰을_버리고_다시_연결한다`에 60초 대기 단언이 추가됐다 |
| N2 (A11) 지운 단언 | 반영 | `GatewayTest.java:112` `assertThat(gap.reason()).isEqualTo(DataGap.GapReason.SOURCE_SWITCH)` 복구, 통과 |

### 점검표 A (eecb2d2 기준)
| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-config` main 대비 변경 0. `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `GuardedOrderGatewayTest#OFF_계좌로는_출구를_만들_수_없다` 재실행 통과 |
| A2 | 통과 | `ONLY_GUARD_FACTORY_OPENS_REAL_ORDER_GATEWAY`·`GATEWAY_HAS_NO_ORDER_PORT` 본문 변경 없음, `실전_주문_출구는_Guard_팩토리만_연다`·`시세_게이트웨이는_주문_포트를_모른다`·`규칙은_위반을_실제로_잡아낸다` 통과. 플러그인 `orderGateway`는 전부 빈 값, `OrderGateway` 구현 0 |
| A3 | 통과 | 주문 전환 경로 없음. 재배분은 시세 구독만 옮긴다 |
| A4 | 해당 없음 | 주문 타임아웃 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 주문·청산·복구 경로 변경 없음 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진의 금지 의존에 게이트웨이 유지, IPC 없음. 실전 계좌가 있는 증권사 키는 게이트웨이가 쓰지 않는다(`GatewayMain.reservedForRealTrading`, 테스트 통과). 인증 실패 재시도는 1분 하한이고 3회면 멈춘다 |
| A9 | 통과 | 보호 수단 약화 없음. 소스를 잃은 보유 종목 공백 녹화 테스트 통과(`GatewayTest#모든_소스가_끊겼다가_돌아오면_받지_못한_구간을_남긴다`, `#받을_소스_없이_끝나면_종료_시각까지_공백을_남긴다`) |
| A10 | 통과 | 새 로그는 소스 ID와 실패 횟수만 찍는다(StreamingMarketDataSource.java:417-418). 비밀값 없음. 픽스처는 자리표시자 |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음(N2 복구 확인). 새 동작(인증 실패 하한·거부 판정·해제)에 실패 경우 테스트가 있다 |
| A12 | 통과 | 장애표 7번 "실패 시 해당 증권사 DEGRADED"와 장애 테스트 7번 전제에 맞게 연속 인증 실패가 DEGRADED로 수렴하고 ERROR 로그를 남긴다. 알림 연결은 7단계(ADR-003 5장 `unservedHeld`·경보 연결 항목) |
| A13 | 통과 | 이번 변경에 시장별 분기 없음. 기존 캘린더·규칙 테스트 통과 |
| A14 | 통과 | ADR-003 2.1 "토큰" 항목이 60초 하한, 3회 거부, 확인 조건, 한투는 재기동해야 풀린다는 점까지 코드와 일치한다 |

### 점검표 밖 발견 (2차, 모두 권장, 막지 않음)
- P1. 한투는 거부(DEGRADED) 판정 뒤 배분에서 빠지면 시세가 오지 않는다. 그래서 키를 고치거나 일시 장애가 끝나도 게이트웨이를 재기동하기 전에는 예비 소스로 돌아오지 않는다(ADR-003 2.1에 기록됨). 일시적인 서버 오류 3회만으로도 예비 소스를 잃는다. 7단계 전에 몇 가지를 정한다. 거부 상태에서 일정 간격(예: 10분)으로 구독 1건을 시험 등록해 확인하거나, 구독 성공 응답(`rt_cd` 0)도 인증 확인으로 쓰는 안이다. 거부 상태는 감시 프로세스 경보로 연결한다.
- P2. 한투에서 인증 실패가 한 번 난 뒤 장이 닫혀 시세가 없으면 `authFailures`가 0으로 돌아가지 않는다. 이후 일반 끊김에도 재연결이 최소 60초 늦어진다. 공백은 DataGap으로 남고 다른 소스로 재배분되므로 계좌 위험은 없다. P1의 "구독 성공 응답으로 확인" 안이 이것도 함께 푼다.
- P3. `LiveMarketDataSource.java`에서 `lastReceivedAt`의 Javadoc이 새 `credentialsRejected` Javadoc 위에 떨어져 고아 주석이 됐다. 문서 품질 문제라 다음 수정 때 고친다.
- 이전 권장 R4~R8과 N3·N4는 여전히 7단계 전 필수 목록이다(ADR-003 6장). N3(게이트웨이 기동 때만 도는 키 공유 검사)은 ADR에 아직 없으므로 CFG-05 작업 때 넣는다.

### 판정
승인. 점검표 A에 실패와 확인 못 함이 없다. P1~P3은 권장이고 7단계 전 목록으로 넘긴다. 이 PR은 증권사 플러그인, 장애 전환, 보호 시세 경로를 건드리는 실전 관련 PR이다. 지침 10·11장과 D-031에 따라 Claude가 머지하지 않는다. 사용자가 직접 확인하고 독립 QA 검토를 거쳐 머지한다.
