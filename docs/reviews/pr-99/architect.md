# architect 검토: 1단계 후속 OVS-02 엔진 시장 분기 금지 ArchUnit 규칙, CFG-06 ① Run 파라미터 불변 테스트 (D-084)
- 대상: PR #99 / 커밋 a773489 (브랜치 claude/project-thread-7u8o29) / `git diff origin/main...HEAD` 전체(12개 파일, 커밋 1개)
- 점검표: B
- 직접 실행한 것: `./gradlew build --console=plain -q` 종료 코드 0, 출력에 오류 없음(ArchUnit·순수 모듈 의존 검사·`-Xlint:all -Werror` 포함). PR 본문·커밋 메시지는 근거로 쓰지 않고 diff 코드와 위 빌드로만 판단했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | `./gradlew build` 직접 실행, 종료 코드 0 |
| B2 | 통과 | 새 ArchUnit 규칙 `ENGINE_HAS_NO_MARKET_BRANCH`(ArchitectureTest.java 신규 블록)를 추가했고 ADR-001 검사 표에 내용 반영(ADR-001 +1줄), D-084에 범위·대안 기록. 기존 규칙을 약화한 변경 없음. 모듈 의존은 `DailyBarQualityCheck`→`BundledMarketRules`(같은 marketdata 모듈)뿐이고 빌드 통과 |
| B3 | 통과 | 계약 변경은 `MarketRules.dailyPriceLimit`(default 메서드, 기본 `Optional.empty()`) 하나. ADR-001 2.3 `MarketRules` 행과 ADR-003 2.5 표 아래 문단, D-084에 반영됨. default라 기존 구현체(`TestKrxRules`, `SyntheticKrxRules`, 테스트 익명 구현 등)는 소스 변경 없이 컴파일됨(빌드 통과). 설정 키·스키마·REST 변경 없음 |
| B4 | 통과 | 시스템 시계·난수 추가 없음. `DailyBarQualityCheck`의 현지 날짜 계산을 `rulesOf(...).tradingDate(createdAt)`로 바꿨으나 기본 구현이 `instant.atZone(zone()).toLocalDate()`(MarketRules.java:79)로 이전 `ExchangeCalendar.bundled(market).zone()` 계산과 같고, `BundledMarketRules.of`가 같은 번들 캘린더를 씀. UNADJUSTED_JUMP 임계 값은 KRX 35%·미국 50%로 그대로이며 `DailyBarQualityJumpTest`가 경계값(135/136, 65/64, 150/151, 50/49)으로 확인. 기존 골든·품질 검사 테스트 변경 없이 통과. 새 Run 테스트는 고정 픽스처(`/fixtures/krx-golden.events`)와 시드 7 사용 |
| B5 | 통과 | 제한폭·여유(0.30, 0.05, 0.50)는 `BigDecimal`, 비교는 `compareTo`. `double`·`float` 금액 없음(금액 ArchUnit 규칙도 통과) |
| B6 | 통과 | 새 동작마다 테스트 있음: `엔진_코드는_시장을_분기하지_않는다`(운영 클래스 통과 + 위반 고정 예제 `BranchesOnMarket`에서 enum switch 보조 클래스 `BranchesOnMarket$1`, `==` 비교 `isKrx`, `Symbol.krx`는 잡고 `MarketRules` 호출 `tick(`은 허용함을 확인), `MarketRulesImplTest#하루_가격_제한폭은_KRX_주식만_있다`·`번들_규칙은_시장마다_그_시장_구현이다`, `DailyBarQualityJumpTest`, `RunParametersImmutableTest` 3건(프로필 파라미터 변경 시도 예외, 조합 목록 변경 시도 예외, 실행 중 변경 시도는 그 Run만 FAILED이고 해시 불변·다른 Run 결과가 단독 실행과 동일). 삭제·`@Disabled`·약화된 기존 테스트 없음(diff에 테스트 삭제 줄 없음) |
| B7 | 해당 없음 | 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 장애 대응 코드를 바꾸지 않았다. 규칙 대상 패키지에 `com.autotrade.risk..`·`app.engine..`가 들어 있으나 이번 diff는 그 운영 코드를 수정하지 않고 읽기 전용 검사만 더한다. `ArchitectureTest`는 보호 테스트이나 기존 규칙을 지우거나 약화하지 않고 규칙을 추가만 했다(추가라서 지침 11장의 "약화" 기준에 해당하지 않음). risk-ops 검토 필요 없음 |
| B8 | 통과 | diff에 API Key·계좌번호·토큰 없음. 테스트 종목코드는 공개 코드(005930, AAPL)뿐 |

## 점검표 밖 발견
1. (낮음, 비차단) 결정 번호 D-083이 비어 있다. 이 PR이 D-082 다음에 D-084를 추가했고 저장소 어디에도 D-083 참조가 없다. 다른 열린 PR이 쓰는 번호일 수 있으나 이 diff만으로는 확인 못 했다. D-029처럼 "번호 미사용"으로 남기거나 머지 순서에서 확인할 것.
2. (낮음, 한계 명시됨) `ENGINE_HAS_NO_MARKET_BRANCH`는 `Market` enum 상수 읽기와 `Symbol.krx`/`Symbol.us`만 막는다. `symbol.market().name()` 문자열 비교, `Market.valueOf`, `Symbol` 코드 패턴 비교 같은 우회는 잡지 못한다. 이번 범위(QA R2)에는 충분하나 완전한 분기 금지 보증은 아니다. D-084는 범위(전략·선정 제외)와 넣을 조건을 적었으나 이 우회 한계는 적지 않았다.
3. (정보) `KrxMarketRules.dailyPriceLimit`의 ETF·ETN 빈 값과 ±30% 값 중 ETF 배율 부분은 `[추론]`으로 표시돼 있고 업무규정 원문 대조가 남았다고 코드 주석에 적혀 있다. 이번 검토에서 한국거래소 원문은 확인하지 않았다(확인 못 함 아님: 점검표 항목이 아니라 수치 근거이며, 현재 사용처는 품질 검사 임계뿐이라 영향이 작다).
4. (정보) `BundledMarketRules.of`는 모든 KRX 종목을 STOCK으로 본다. 현재 사용처(일봉 품질 검사)에서는 이전 동작(KRX 35%)과 같아 회귀가 없고 주석에 한계가 적혀 있다.

## 판정
승인
