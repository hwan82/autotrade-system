# data-engineer 검토: 1단계 후속 - OVS-02 엔진 시장 분기 금지 ArchUnit 규칙, CFG-06 ① Run 파라미터 불변 테스트 (D-084)
- 대상: PR #99 / 커밋 a773489 (브랜치 claude/project-thread-7u8o29, 베이스 main a8633eb) / main 대비 diff (12개 파일, +354 -14)
- 점검표: 시장 분기 제거의 동작 동일성 | 시간·캘린더(현지 시간대) | MarketRules 계약 변경 | 데이터 품질 검사 | 결정론·테스트 | 출처·문서
- 직접 실행한 것: `./gradlew --offline :autotrade-marketdata:test :autotrade-architecture-test:test :autotrade-sim:test --tests '*RunParametersImmutableTest'` BUILD SUCCESSFUL. marketdata와 architecture-test는 전체 테스트가 돌았고, sim은 필터한 RunParametersImmutableTest만 돌았다. 전체 `./gradlew build`와 CI 결과는 확인하지 않았다. diff 전체(ArchitectureTest, BranchesOnMarket, MarketRules, KrxMarketRules, BundledMarketRules, DailyBarQualityCheck, 신규·수정 테스트, ADR-001·003, D-084)를 읽었다.

| # | 결과 | 근거 |
|---|---|---|
| D1 | 통과 | 일봉 급변 임계가 이전과 같은 값이다. 이전: KRX 0.35, 미국 0.50 (`Market.KRX` 분기). 이후: `dailyPriceLimit(0.30) + JUMP_MARGIN(0.05)` = 0.35이고, 제한폭이 없으면 `NO_LIMIT_JUMP` 0.50. `DailyBarQualityJumpTest`가 경계값(135 통과/136 적발, 65/64, 미국 150/151, 50/49)으로 고정했고 통과했다. |
| D2 | 통과 | KRX ETF·ETN은 `BundledMarketRules.of`가 종목 마스터 없이 모든 종목을 STOCK으로 보므로 일봉 품질 검사에서는 이전과 같이 35%다. 동작 변화는 없다. 이 사실은 `BundledMarketRules` javadoc과 ADR-003 2.5절에 적혀 있고, `MarketRulesImplTest#번들_규칙은_시장마다_그_시장_구현이다`가 ETF에도 0.30이 나오는 것을 박았다. |
| D3 | 통과 | 스냅샷 기준일이 `ExchangeCalendar.bundled(market).zone()`으로 계산하던 값에서 `rulesOf(...).tradingDate(createdAt)`로 바뀌었다. `tradingDate`의 기본 구현은 `instant.atZone(zone()).toLocalDate()`(MarketRules.java:79)이고 `zone()`은 같은 번들 캘린더의 zone이라(KrxMarketRules.java:71-72, UsMarketRules.java:39-40) 결과가 같다. 거래소 현지 시간대 기준이 유지되고 하드코딩도 없다. |
| D4 | 통과 | `dailyPriceLimit`은 default 메서드(기본 비어 있음)라 기존 `MarketRules` 구현과 호환된다. KRX 구현은 KRX가 아닌 종목에 `IllegalArgumentException`을 던지고, 테스트가 그 예외를 확인한다. |
| D5 | 통과 | 시장별 규칙 캐시는 `EnumMap`이라 순회 순서가 결정적이다. 검사 결과 순서와 가정(`TreeMap`)은 바뀌지 않았다. 새 `now()`·난수 호출도 없다. |
| D6 | 통과 | ArchUnit 규칙이 `Market` enum 상수 읽기(enum switch 보조 클래스 포함)와 `Symbol.krx`·`Symbol.us` 호출을 막는다. 대상은 risk, app.engine, sim exchange·account·run이다. 규칙 자체를 `BranchesOnMarket`(switch, `==` 비교, `Symbol.krx`)으로 검증하고, 허용 경로(`rules.tickSize`)는 위반으로 안 잡는 것도 확인한다. 운영 코드 PRODUCTION 검사가 통과해 현재 엔진 패키지에는 위반이 없다. 시장 enum에서 규칙 구현을 고르는 곳은 `BundledMarketRules.of`에 모았고 ADR-003에 기록했다. |
| D7 | 통과 | 안전·실전 경로를 약화하는 변경이 없다. RealTradingGuard, 블랙리스트, 주문 경로, 게이트웨이의 주문 API 비호출 규칙 모두 건드리지 않았다. ArchitectureTest에는 규칙이 추가만 됐고 기존 규칙·테스트를 지우거나 약화한 곳이 없다. 따라서 프로젝트 지침 11장의 "보호 테스트 약화" 조항에 해당하지 않는다. |
| D8 | 통과 | `RunParametersImmutableTest`(138줄)는 프로필 파라미터와 조합 목록의 변경 시도가 `UnsupportedOperationException`을 내는지 확인한다. 실행 중 변경 시도는 그 Run만 FAILED가 되고, 프로필 해시가 시작 때와 같고, 다른 계좌의 결과는 단독 실행과 동일함(계좌 격리)을 단언한다. 통과했다. |
| D9 | 통과(근거 품질) | KRX ±30%는 `[사실]`(2015-06-15 시행, 한국거래소 업무규정)로 표시했다. ETF·ETN 배율 부분은 `[추론]`(업무규정 원문 대조 남음)으로 구분하고 비워 두었다. 미국 LULD는 종목·시간 밴드라 시장 단위 값이 없다고 적었다. |

## 점검표 밖 발견
1. (경미, 문서) D-084 직전 번호가 D-082라서 D-083이 비어 있다. main에서도 D-083이 없다(`git grep D-083 origin/main -- docs` 결과 없음). 앞서 D-029 공백이 "번호 미사용"으로 기록된 선례가 있으니, 의도한 결번이면 D-084 행이나 README에 한 줄 적는 것을 권한다. 다른 진행 중 PR이 D-083을 쓸 예정이라면 번호 충돌에 유의해야 한다. 머지를 막을 사안은 아니다.
2. (경미, 규칙의 범위 한계) `ENGINE_HAS_NO_MARKET_BRANCH`는 `Market` enum 상수 읽기와 `Symbol.krx`·`Symbol.us`만 잡는다. `Market.valueOf("KRX")`나 `Market.values()`, `market().name()` 문자열 비교는 못 잡는다. 현재 위반은 없고 D-084가 범위를 QA 판정 그대로라고 밝혔으므로 지금은 허용하되, 한계를 ADR-001 표나 규칙 javadoc에 한 줄 더하면 좋다.
3. (참고, 위험) `dailyPriceLimit`이 일봉 품질 검사 말고는 아직 쓰이지 않는다. javadoc이 "주문 가격 검증에 쓰려면 기준가 계산 규칙을 같은 ADR에서 더한다"고 밝혀 두었다. 이후 가격 검증에 이 값을 끌어 쓸 때는 신규 상장일(공모가 대비 90~400%)과 ETF·ETN 배율 예외를 먼저 처리해야 한다. 지금의 "급변 의심" 용도에서는 문제가 아니다.
4. (한계) 품질 검사의 KRX 종목은 모두 STOCK으로 본다. 레버리지 ETF의 큰 변동을 급변으로 잘못 적발할 가능성은 이전과 같다. 이번 PR에서 악화되지 않았다.
5. 검증하지 못한 것: 전체 `./gradlew build`(순수 모듈 의존 검사 포함), CI 상태, 다른 모듈(app-engine, risk)이 새 규칙에 간접 영향을 받는지는 architecture-test 통과로만 확인했다.

## 판정
승인
