# architect 검토: 한국투자 실전 주문·계좌 조회 클라이언트(D-145, 7단계 묶음 D-H 1차) [실전 관련]
- 대상: PR #247 / 커밋 e5b3969b / `git diff origin/main...origin/claude/project-thread-truegb` 전체(28파일, +1942 −21)
- 점검표: B
- 직접 실행한 것: `./gradlew build --no-daemon`(저장소 밖 미러 init 스크립트 사용) → BUILD SUCCESSFUL(6분 10초, EXIT=0). 테스트 결과 XML: `KisOrderClientTest` 13/13, `KisAccountQueriesTest` 8/8, `KisOrderReconcilerTest` 11/11, `KisTokenGateTest` 1/1, `ArchitectureTest` 20/20(건너뜀·실패 0). `docs/decisions/` 파일 목록과 README에서 D-143 존재 여부 확인(없음).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 `./gradlew build` 성공. 새 테스트 33건과 `ArchitectureTest#증권사_주문_코드는_플러그인_밖에서_쓰지_않는다`, `ArchitectureTest#규칙은_위반을_실제로_잡아낸다` 통과 |
| B2 | 실패 | 의존 방향은 지킨다: `autotrade-broker-kis/build.gradle.kts` 의존 변경 없음(주석만), 새 코드는 `com.autotrade.broker.kis.order`(소유 표 `ModuleOwnershipTest.java:113`이 이미 `com.autotrade.broker.kis` → `autotrade-broker-kis`로 덮음). 새 규칙 `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN`(`ArchitectureTest.java:178-189`)은 위반 예제 `archfixture/BypassPluginOrderClient`로 고정됨. 그러나 ArchUnit 규칙을 더했는데 ADR이 갱신되지 않았다: ADR-002 4장 R-4(`ADR-002-real-trading-guard.md:124`, 184)는 "`OrderGateway` 구현 생성자는 그 플러그인 패키지 밖에서 호출 금지, 엔진은 플러그인 내부 패키지에 의존 금지"를 예고했는데, 구현은 "`.order` 하위 패키지에 대한 플러그인 밖 의존 금지"로 형태가 다르다. 이 차이와, 앞으로 `KisOrderGateway`를 반드시 `.order` 안에 두어야 규칙이 덮는다는 조건이 ADR에 없다(D-145 결정 8에만 있음). ADR-002 `:63`·`:148`의 규칙 목록("ArchUnit 규칙 2개")도 그대로다 → 수정 항목 2 |
| B3 | 실패 | 인터페이스(`BrokerPlugin`, `OrderGateway`)·스키마·설정 키·REST API 변경은 없다(`KisPlugin.java`는 javadoc만, `orderGateway` 빈 값 유지 확인). 하지만 계약 문서가 사실과 어긋나게 남는다: ADR-003 `ADR-003-market-data.md:31` "두 플러그인 모두 `orderGateway`는 항상 빈 값이고 **주문 코드가 없다**(MD-01)", `:173`·`:204`의 후속 항목(우회 차단 규칙 미구현)이 이 PR 뒤에는 틀린 서술이 된다 → 수정 항목 2. `kis-open-api-spec.md` 9장과 결정 로그는 갱신됨 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 새 코드는 시계를 주입받는다(`KisSession.java:45-63`, `KisTokenGate.java:159`). `ForbiddenTimeAndRandom` 계열 ArchUnit 규칙 포함 `ArchitectureTest` 통과. 골든 테스트 포함 전체 빌드 통과 |
| B5 | 통과 | 가격·금액 `BigDecimal`(`KisOrderClient.won` `:289-295`, 소수 남으면 보내기 전 거절; `KisDailyOrder.amount`, `KisBalance`), 수량 `long`(`quantity`는 `longValueExact`) |
| B6 | 통과 | 새 동작마다 테스트 있음(위 33건). 기존 테스트 삭제·`@Disabled` 없음(diff에 테스트 삭제 줄 없음). 우회 차단 규칙은 위반 예제(생성·호출·세션·계좌 타입 의존 검출)와 비위반 예제(주문 패키지 자신, 무관한 클래스)로 고정 |
| B7 | 실패(별도 검토 필요) | 실전 경로(증권사 주문 연동)와 보호 테스트(`autotrade-architecture-test/*`, `autotrade-broker-*` — `scripts/ci/protected-paths.txt`)를 건드린다. **risk-ops 검토 필요**(A 점검표), 사용자 머지·독립 QA 대상. 주문 응답 분류(결정 3), 토큰 만료 시 NotAccepted(결정 4), 미접수 판정 조건(결정 5)의 안전성 판단은 risk-ops 몫이다 |
| B8 | 통과 | 테스트 키는 자리표시자(`OrderFakes.java:24` `APP-KEY-PLACEHOLDER`). `KisAccountNumber.toString()` 가림(`:476`), 형식 오류 메시지에 값 없음(`:452`). 주문 결과·예외 메시지는 TR·HTTP 상태·예외 클래스 이름·결과 코드만 담음(`KisOrderClient.java:318`, `:329`, `KisAccountQueries.java:464-468`). CI gitleaks 결과는 이 컨테이너에서 확인하지 않았다(로컬 build에는 포함되지 않음) |

## 점검표 밖 발견

**수정 필요**
1. **없는 결정 번호 D-143 참조(5곳).** `docs/decisions/`에 D-143 파일도 README 행도 없다. 이 PR의 결정은 D-145다. 보호 테스트 파일 두 곳을 포함해 추적성이 끊긴다.
   - `autotrade-architecture-test/src/test/java/com/autotrade/archfixture/BypassPluginOrderClient.java:10`
   - `autotrade-architecture-test/src/test/java/com/autotrade/architecture/ArchitectureTest.java:175`(같은 파일 `:164`는 D-145로 적혀 있어 한 파일 안에서도 어긋남)
   - `autotrade-broker-kis/src/main/java/com/autotrade/broker/kis/order/package-info.java:2`
   - `autotrade-broker-kis/src/main/java/com/autotrade/broker/kis/order/KisOrderReconciler.java:15`
   - `autotrade-broker-kis/src/test/java/com/autotrade/broker/kis/order/KisOrderReconcilerTest.java:18`
2. **ADR 갱신 누락(B2·B3).** ADR-002 4장 R-4와 6장(또는 새 절)에 실제 규칙 `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN`의 범위(세 플러그인의 `.order` 하위 패키지, 막는 쪽은 그 플러그인 밖 전체)와 예고 형태와의 차이, "플러그인의 `OrderGateway` 구현·주문 코드는 `.order` 안에만 둔다(밖에 두면 이 규칙이 덮지 못한다)"는 조건을 적는다. 규칙 목록·검증 표(`:63`, `:148`, `:184`)를 맞춘다. ADR-003 `:31`(주문 코드 없음), `:173`·`:204`(우회 규칙 미구현)를 "주문 클라이언트는 있으나 미연결, 규칙 D-145"로 고친다. ADR-002는 보호 경로라 이 PR(이미 실전 관련 PR) 안에서 고치는 것이 맞다.

**권고(머지 막지 않음, 후속 PR에서)**
- R1. `KisTokenClient`(정적 `fetcher` 포함)와 `KisErrorCode`를 `public`으로 넓혔다(`KisTokenClient.java:214`, `:223`, `:235`; `KisErrorCode.java:134-185`). 이제 게이트웨이·실험실·수집기가 실전 앱키로 `KisTokenGate`(60초 하한)를 거치지 않는 토큰 발급기를 만들 수 있다. 같은 앱키 재발급 1분 1회·알림톡 문제(D-054)를 다시 열 수 있으므로, `KisTokenClient`를 플러그인 밖에서 쓰지 못하게 하는 ArchUnit 규칙을 더하거나(`resideOutsideOfPackage("com.autotrade.broker.kis..")` 의존 금지) 엔진 연결 PR(묶음 E)에서 정한다.
- R2. 규칙의 강제력은 "주문 코드를 `.order`에 둔다"는 관례에 기댄다. 다음 PR(`KisOrderGateway`)에서 "`OrderGateway`를 구현한 클래스가 `com.autotrade.broker.*`에 있으면 `..order..`에 있어야 한다" 같은 보조 규칙을 더하면 관례 실수가 빌드에서 잡힌다.
- R3. `KisOrderClient.interpret`(`:327-330`)는 `rt_cd` 없이 `error_code`만 있는 토큰 오류 본문을 Unknown으로 두고 토큰을 버리지 않는다. 안전한 쪽(재주문 없음)이지만 그런 응답이 실제로 오면 토큰 캐시 만료까지 매 주문이 Unknown→대사로 간다(가용성). `KisAccountQueries.call`은 같은 본문을 토큰 만료로 처리해 두 클래스의 해석이 다르다. 판단은 risk-ops, Q-015 실측 항목으로 둘 만하다.
- R4. `KisOrderReconciler.find`(`:122-124`)는 조회가 불완전해도 후보가 하나면 `Found(byOrderNo=false)`를 낸다. 재주문 안 하는 쪽이라 중복 주문은 없지만, 읽지 못한 쪽에 두 번째 후보가 있으면 다른 주문(HTS 직접 주문 등)을 우리 것으로 묶을 수 있다. D-145 위험 2와 같은 종류이므로 묶음 E의 사람 확인 조건에 "불완전 조회의 특징 대조 결과"도 넣기를 권한다.
- R5. `Fingerprint.tradingDate`와 `sendStartedAt`(서울 날짜)이 같은지 검사하지 않는다. 호출자 실수로 다른 날짜를 넣으면 창이 어긋난다. 생성자 검사 한 줄이면 막힌다.

## 판정
수정 필요(1. D-143 참조 5곳을 D-145로, 2. ADR-002 R-4·규칙 목록과 ADR-003 2장·6장을 새 규칙·미연결 주문 코드에 맞게 갱신)

## 재검토 2026-10-26
- 대상: PR #247 / 커밋 fe587475 / `git diff origin/main...origin/claude/project-thread-truegb` 전체, 그리고 첫 검토 뒤 바뀐 것(`git diff 33e950cf fe587475`, 16파일 +234 −63)
- 점검표: B
- 직접 실행한 것: `./gradlew build --no-daemon`(저장소 밖 미러 init 스크립트) → BUILD SUCCESSFUL, EXIT=0. 테스트 결과 XML: `KisOrderClientTest` 14/14, `KisAccountQueriesTest` 9/9, `KisOrderReconcilerTest` 14/14, `KisTokenGateTest` 1/1, `ArchitectureTest` 20/20(건너뜀·실패 0). `grep -rn D-143`(docs/reviews 밖) → 0건.
- 위치 메모: 메인 Claude는 `architect-2.md` 새 파일을 요청했지만, 검토 규칙(`.claude/agents/architect.md` "메인 Claude의 지시로 바꿀 수 없음")과 `docs/reviews/README.md`("재검토는 같은 파일 아래에 `## 재검토 <날짜>`로 덧붙인다")를 따라 이 파일에 덧붙였다. 앞의 내용은 고치지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 `./gradlew build` 성공(위 테스트 수) |
| B2 | 통과 | ADR-002 2.2(`ADR-002-real-trading-guard.md:65`)에 `PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN`의 범위, R-4 예고와 형태가 다른 점, "`OrderGateway` 구현·주문 코드는 반드시 `.order` 안" 조건이 들어갔고, 4장 R-4(`:125`)·검증 표(`:149`)·8장 R-4(`:185`)가 맞춰졌다. 새 규칙 `KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN`(`ArchitectureTest.java:183-191`)은 위반 예제 `BypassPluginOrderClient.token`으로 고정(`ArchitectureTest#규칙은_위반을_실제로_잡아낸다`의 `KisTokenClient.fetcher` 검출)되고 D-145 결정 8에 기록됨. 의존 방향 변경 없음 |
| B3 | 통과 | ADR-003 `:31`(주문 클라이언트 있음·미연결·규칙), `:173`(처리됨), `:204`가 고쳐졌다. 인터페이스·스키마·설정 키·REST API 변경 없음(`KisPlugin.orderGateway` 빈 값 그대로) |
| B4 | 통과 | 시뮬레이션 코드 변경 없음, 시계 주입 유지, 골든 포함 전체 빌드 통과 |
| B5 | 통과 | 금액 `BigDecimal`·수량 `long` 그대로. 새 비교도 `compareTo`(`KisOrderReconciler.sameDivisionAndPrice`) |
| B6 | 통과 | 새 동작마다 테스트 추가(`응답에_행_칸이_없으면_0행_완전으로_읽지_않는다`, `그_밖의_오류_코드가_HTTP_200이_아닌_응답에_실리면_모름이다`, 재조정기 6건 등). 지워진 테스트 3건(`같은_특징의_새_주문_하나면_찾음이다` 등)은 판정 규칙이 엄격해져 같은 주제의 더 엄격한 테스트로 바뀐 것이고(`반영_대기_뒤_완전한_조회에_같은_특징의_새_주문_하나뿐이면_찾음이다`, `…없을_때만_없음이다`, `취소_주문_행은_후보가_아니고_묶이지_않았으면_확정을_막는다`), 건너뛰기·약화가 아니다 |
| B7 | 실패(별도 검토 필요) | 실전 주문 연동 코드와 보호 테스트(`autotrade-architecture-test/*`, `autotrade-broker-*`, `ADR-002`)를 바꾼다. **risk-ops 검토 필요**(A 점검표, 같은 PR risk-ops 재검토), 사용자 머지·독립 QA 대상 |
| B8 | 통과 | 새 결과 문자열은 TR·결과 코드·HTTP 상태만(`KisOrderClient.java:170`). 테스트 키 자리표시자 유지. CI gitleaks는 이 컨테이너에서 확인하지 않았다 |

### 첫 검토 항목 처리
- 수정 1(D-143 표기 5곳): 해결. 다섯 곳 모두 D-145로 바뀌었고 남은 곳 없음.
- 수정 2(ADR 갱신): 해결(위 B2·B3).
- 권고 R1(토큰 발급기 규칙): 반영(`KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN`). R4(불완전 조회의 찾음): 반영(`KisOrderReconciler.java` 완전·반영 대기 검사를 찾음보다 먼저). R5(특징 날짜 검사): 반영(`Fingerprint` 생성자). R2·R3: D-145 "검토 반영"에 묶음 E·Q-015로 넘긴다고 적힘. 받아들인다.

### 재검토에서 본 것(점검표 밖, 권고·머지 막지 않음)
- N1. `KisSession.pace`만 `synchronized`로 좁혀 HTTP 대기 중 잠금을 쥐지 않는다(`KisSession.java:121-133`). 보내기 시작 간격은 그대로 지켜지고, 계좌별 주문 직렬화는 `KisOrderClient.submit/cancel`의 `synchronized`가 맡는다. 구조상 문제 없음.
- N2. `KisOrderReconciler.sameSymbol`(`:256-258`)은 길이가 1 긴 코드가 우리 코드로 끝나면 같은 종목으로 본다. 7자리 ETN 코드가 다른 6자리 코드로 끝나는 경우 오인할 수 있지만, 찾음은 창 안 묶이지 않은 행이 정확히 1건이고 방향·수량·주문구분·가격까지 맞을 때만 나오고 틀려도 재주문하지 않는 쪽이라 위험이 작다. Q-015 실측에서 `pdno` 형식이 확인되면 접두를 영문 한 글자로 좁히기를 권한다.
- N3. `KIS_TOKEN_ISSUER_STAYS_IN_PLUGIN`은 FQN 하나만 막는다. `KisErrorCode`의 `public` 확대는 발급·호출 능력이 없는 해석기라 그대로 두어도 된다. 다음에 `com.autotrade.broker.kis` 루트에 발급·호출 클래스를 `public`으로 더할 때는 이 규칙에 함께 넣는다.
- N4. risk-ops G2(주문 코드가 게이트웨이·수집기 클래스패스에 실려 리플렉션 생성은 `RealPathReflectionTest` 범위 밖)는 D-145에 묶음 E 연결 전 별도 모듈 분리 안 비교로 남았다. architect도 그 비교에 참여한다. 지금은 `KisPlugin`이 주문 코드를 부르지 않아 실행 경로가 없다.

### 판정
승인

## 재검토 2026-10-27
- 대상: PR #247 / 커밋 4283eb6f / `git diff origin/main...HEAD` 전체(37파일 +2632 −27, origin/main f80f4caa가 조상임을 확인), 그리고 독립 QA 원문(`qa.md`, 수정 필요 A4·A12·A14 발견 1) 뒤 바뀐 것: `git diff 7e21527c 9027e0de`(코드·문서 14파일 +404 −70)와 main 합침 4283eb6f(이 PR 파일은 `docs/qa/pending.md`·`docs/decisions/README.md` 줄 합침만, 코드 충돌 해결 없음)
- 점검표: B
- 직접 실행한 것: `./gradlew build --rerun-tasks --no-daemon`(저장소 밖 미러 init 스크립트) → BUILD SUCCESSFUL in 5m 53s, 138 작업 모두 실행, EXIT=0. 테스트 결과 XML 합계 2,051건, 실패·오류·건너뜀 0. `KisOrderClientTest` 16/16, `KisAccountQueriesTest` 10/10, `KisOrderReconcilerTest` 17/17, `KisTokenGateTest` 1/1, `TokenCacheTest` 1/1, `ArchitectureTest` 20/20. `JdkHttpPort.java:43`이 응답 헤더 이름을 소문자로 바꾸는지 확인(`date` 조회가 맞음). `git diff origin/main...HEAD`에서 지워진 `@Test` 줄 0건.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 직접 빌드(`--rerun-tasks`) 성공 |
| B2 | 통과 | 의존 방향 변경 없음: `broker-kis`는 원래 `autotrade-marketdata`(`TokenCache`)·`autotrade-domain`(`OrderRequest`)에 의존한다. 새 코드는 모두 `com.autotrade.broker.kis.order` 안이고 ArchUnit 규칙 변경 없음(`ArchitectureTest` 이번 수정에서 무변경, 20/20 통과). `ZonedDateTime.parse`(`KisAccountQueries.java:102`)는 시계를 읽지 않아 시계 금지 규칙과 무관 |
| B3 | 통과 | `BrokerPlugin`·`OrderGateway`·스키마·설정 키·REST API 변경 없음. 바뀐 형태(`KisOrderResult.Unknown`에 시각 2개, `Fingerprint` record → `of`로만 만드는 클래스, `KisDailyOrders.serverClock`, `KisSession.post`·`invalidateToken(String)`)는 미연결 플러그인 내부 타입이라 ADR 계약이 아니다. ADR·명세에 옛 형태(`requestTimeout`·고정 시계 오차) 서술 남은 곳 없음(grep). `TokenCache.invalidateIfCurrent`(`TokenCache.java:63`)는 marketdata 모듈의 공개 메서드 추가(호환 유지)로 D-145 검토 반영에 기록됨 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 새 시각은 모두 세션 주입 시계(`KisSession.java:106`, `KisOrderClient.java:163`, `KisAccountQueries` `session.clock()`). 골든 포함 전체 빌드 통과 |
| B5 | 통과 | 금액·수량 표현 변경 없음(`BigDecimal`·`long`). 시각 계산은 `Instant`·`Duration` |
| B6 | 통과 | 새 동작마다 테스트: S1 `우리_시계가_61초_빠르면_잰_시계_차이만큼_창을_옮겨_접수된_주문을_놓치지_않는다`(3분 빠름·창 밖 행 포함), `서버_시각을_읽지_못한_조회로는_찾음도_없음도_내지_않는다`, `시계_차이_하한은_응답_받은_시각_기준이라_차이를_작게_재지_않는다`, `첫_쪽_응답의_Date_헤더로_서버_시계를_잰다`(읽지 못하는 Date → null), S3 `모름_결과의_보내기_시작_시각은_토큰_발급_뒤_실제_POST_직전이다`, S2 `옛_토큰의_늦은_만료_응답은_그_사이_받은_새_토큰을_버리지_않아_손절_매도가_막히지_않는다`, `TokenCacheTest`. 바뀐 테스트 1건(`보낸_시각의_한국_날짜와_주문_날짜가_다르면…` → `응답_시각이_보내기_시작보다_앞서면…`)은 주문 날짜를 이제 보내기 시각에서 정해 어긋날 수 없게 된 데 따른 교체이고 약화가 아니다 |
| B7 | 실패(별도 검토 필요) | 증권사 주문 연동 코드와 보호 경로(`autotrade-broker-*`, `autotrade-marketdata/*`)를 바꾼다. **risk-ops 검토 필요**(A 점검표, 이 수정에 대한 risk-ops 재검토), 독립 QA 재검토, 사용자 머지 대상 |
| B8 | 통과 | 새 결과 문자열은 TR·예외 클래스 이름·HTTP 상태·코드만(`KisOrderClient.java` `unknown(...)` 호출부). 테스트 값은 자리표시자·가짜 토큰(`TOKEN-ONE`/`TWO`). CI gitleaks는 이 컨테이너에서 확인하지 않았다 |

### 독립 QA 발견 처리(설계 관점 확인)
- S3(호출자 시각): 해결. 보내기 시작 시각은 `pace()` 뒤 실제 `http.post` 직전에 세션 시계로 잰다(`KisSession.java:106`). `Fingerprint`는 공개 생성자 없이 `Fingerprint.of(OrderRequest, Unknown)`으로만 만들어 호출자가 시각을 넣을 길이 없다(같은 패키지 테스트만 생성자 사용). 반영 대기는 `sendEndedAt + SETTLE`로 센다(`KisOrderReconciler.java` `find`). 간격 대기 중 중단은 POST 전이라 `NOT_SENT`가 맞다(`KisOrderClient.java:155`). `:147`의 IOException·`started == null` 분기는 지금 `pace()`·`headers()`가 IOException을 내지 않아 도달하지 않지만 방어로서 해가 없다.
- S1(시계 오차): 해결(일정한 오차에 대해). `offsetLowerBound = serverDate − receivedAt`(`KisDailyOrders.java:47`)은 Date 절사(실제 ≥ serverDate)와 찍힌 순간의 우리 시각 ≤ receivedAt에서 나온 올바른 하한이고, 응답이 느릴수록 창이 넓어지는(안전한) 쪽이다. `ord_tmd ≥ sendStartedAt + 실제 차이 ≥ sendStartedAt + 하한`이므로 Date 시계와 주문시각 시계가 같다면 창이 우리 주문을 놓치지 않고, 그 차이·절사는 `CLOCK_SKEW` 60초가 덮는다(그 근거는 D-145 연결 조건 2로 실측). Date 없음·파싱 실패는 `Indeterminate`.
- 반영 대기 30초: 코드로 고칠 수 없는 가정이며, D-145 위험 1이 "틀리면 중복 주문 쪽"으로 사실대로 고쳐졌고 연결 조건 1(Q-015 실측 전 `NotFound`로 재발주 금지)이 생겼다. 이 PR은 어디에도 연결되지 않으므로(`KisPlugin.orderGateway` 빈 값 유지) 이 처리 방식을 받아들인다. 연결 조건이 실제로 강제되는지는 묶음 E 검토에서 본다.
- Q1(S2): 해결. 만료 응답을 받은 요청에 쓴 토큰과 지금 캐시 토큰이 같을 때만 버린다(`TokenCache.java:63` `synchronized`, `KisOrderClient.java:177`, `KisAccountQueries.java:203`). `KisTokenGate` 60초 하한과의 상호작용도 테스트로 고정.

### 재검토에서 본 것(점검표 밖, 권고·머지 막지 않음)
- N5. **시계 계단 이동은 남는다.** 시계 차이는 조회 시점에 재고 그것을 보내기 시각(그보다 최소 30초 앞)에 적용한다. 그 사이 PC 시계가 NTP 보정으로 뒤로 크게(60초 넘게) 움직이면, 빠른 시계로 잰 `sendStartedAt`에 보정 뒤 차이(≈0)를 더해 S1과 같은 방식으로 `NotFound`가 틀릴 수 있다. 반영 대기 판정도 같은 비단조 시계(`Instant`)를 쓴다. 드문 경우이고 지금은 연결 조건 1 때문에 `NotFound`로 재발주하지 않지만, 연결 조건 1은 반영 지연만 말한다. 묶음 E 연결 조건에 "보내기 시점의 시계 차이도 잰다(주문·토큰 응답의 `Date`를 세션에 기록) 그리고 보내기 시점과 조회 시점의 차이가 여유를 넘게 다르면 `Indeterminate`"를 더하거나, 보내기~조회 경과를 단조 시계(`System.nanoTime` 계열 주입)로 함께 재기를 권한다. D-145 위험 1 "PC 시계가 몇 분 어긋나도 창이 따라 옮겨진다"는 일정한 오차에 대해서만 맞다.
- N6. 서버 시계는 첫 쪽 응답에서만 잰다(`KisAccountQueries` `serverClock[0] == null`). 첫 쪽에서 토큰 재시도가 일어나도 마지막 응답과 그 뒤 시각으로 재므로 하한 성질은 유지된다. 문제 없음.
- N7. `TokenCache`는 data-engineer 소유 모듈(marketdata)의 공개 API다. 추가만 있고 기존 `invalidate()` 사용처(키움·토스·`KisRestClient`)는 그대로라 이번 변경의 영향은 없다. 시세 쪽 클라이언트도 같은 경쟁이 있을 수 있으므로(`KisRestClient.java:125`) 후속으로 `invalidateIfCurrent`로 옮길지 data-engineer가 판단하기를 권한다.

### 판정
승인
