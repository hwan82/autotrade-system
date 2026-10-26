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
