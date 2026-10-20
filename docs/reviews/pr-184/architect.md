# architect 검토: 중간가 체결 틱(호가 단위 반 칸)은 세션을 멈추지 않음, 실험실 응답 charset UTF-8 (D-118)
- 대상: PR #184 / 브랜치 `claude/project-thread-m8gp5f` / 커밋 750bb98b / `git diff origin/main...HEAD` 전체(11개 파일, +189 −10)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew clean build` → BUILD SUCCESSFUL in 3m 37s(종료 코드 0). 테스트 결과 XML 합계 실패 0, 건너뜀 0. 새 시험 5개 통과 확인(아래 B6).
  - 변이 확인: `application.yml`을 origin/main 판으로 되돌리고 `VirtualExchange.matchTrade`의 `if (midpoint)`를 끈 상태에서 `:autotrade-app-lab:test --tests '*WebSecurityIntegrationTest*'`, `:autotrade-sim:test --tests '*VirtualExchangeTest*'` → `JSON_응답은_charset_UTF8을_선언한다_D118`, `중간가_체결은_주문을_체결시키지_않는다_D118` 각각 FAILED. 새 시험이 변경을 실제로 잡는다. 확인 뒤 작업 트리 원복(`git status` 깨끗).
  - `gh pr checks`는 이 세션에서 GraphQL 403이라 CI 결과는 보지 못함. 판정은 로컬 빌드로 한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew clean build` 실행, BUILD SUCCESSFUL(ArchUnit·순수 모듈 금지 의존 검사 포함) |
| B2 | 통과 | 새 클래스 `MidpointTrades`는 `autotrade-sim`의 `com.autotrade.sim.exchange`(모듈 소유 패키지) 안, 의존은 `com.autotrade.domain.MarketRules`·`Symbol`·`java.math`뿐(autotrade-sim/src/main/java/com/autotrade/sim/exchange/MidpointTrades.java:1-6). `RecordingExperiment`(sim.recording) → `sim.exchange` 참조는 같은 모듈 안. Spring은 app-lab `application.yml`에만. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 공개 인터페이스·DB 스키마·`runtime-config.json` 설정 키·REST 경로 변경 없음. `server.servlet.encoding.*`는 Spring Boot 표준 속성이며 자체 설정 키 문서(docs/config/config-keys.md) 대상이 아님. REST 응답 헤더에 charset이 붙는 것은 하위 호환(클라이언트 파싱 결과 동일). 결정은 docs/decisions/D-118-midpoint-trade-ticks.md, 결정 로그 표(docs/decisions/README.md:124), 기획서 v14.54 변경 이력(docs/spec/autotrade-spec.md) 갱신 |
| B4 | 통과 | `MidpointTrades.isMidpoint`는 가격과 `MarketRules`만의 상태 없는 함수(MidpointTrades.java:22-32), 시계·난수 없음. 실시간(`LiveSession.accept` → `offStockTick`, LiveSession.java:259)과 리플레이(`LiveReplayCheck`:92, `LiveJudgement`:127, `LiveWeeklyCheck`:565)가 같은 함수·같은 `VirtualExchange` 경로를 지남. 골든·리플레이 시험(RecordingExperimentTest, RecordedMarketDataSetTest, SelectionCombinationRunnerTest 등) 전체 통과 |
| B5 | 통과 | 판정은 `BigDecimal.compareTo`(스케일 무관), 반 칸은 `tickSize.divide(2)`(정확한 나눗셈, 1원 단위도 0.5로 끝남). 계좌 체결가는 기존 경로 그대로: 지정가는 지정가, 호가 없는 종목 시장가는 `roundToTick(CEILING/FLOOR)` 후 체결(VirtualExchange.java:575-585)이라 단위 밖 가격이 계좌에 들어가지 않음 |
| B6 | 통과 | 새 시험: `VirtualExchangeTest#중간가_체결은_주문을_체결시키지_않는다_D118`, `#중간가_판정은_정확한_반_칸만_받는다_D118`(구간 경계 199,950, ETF 5원, 0 포함), `LiveSessionTest#midpointTradesKeepTheSessionRunningButOffTickQuotesAndOtherPricesStillStop`, `#offTickQuotePricesStopEvenWhenHalfTick`, `RecordingExperimentTest#중간가_체결과_단위_밖_가격과_호가를_같은_검사가_가른다_D118`, `WebSecurityIntegrationTest#JSON_응답은_charset_UTF8을_선언한다_D118`. 기존 시험 한 곳 입력 변경(RecordingExperimentTest `180050`→`180005`)은 약화가 아님: 180,050은 이제 정의상 허용 대상이고, "5원 단위 가격 거절" 의도는 180,005로 그대로, 반 칸 허용·호가 반 칸 거절은 새 시험이 덮음. 건너뜀 0 |
| B7 | 해당 없음 | 변경 경로는 `autotrade-sim`, `autotrade-app-lab`(설정·시험), `docs/decisions`, `docs/spec`뿐이며 `scripts/ci/protected-paths.txt`의 어느 패턴에도 걸리지 않음. 실전 엔진(`autotrade-app-engine/build.gradle.kts:19-28`)·게이트웨이는 `autotrade-sim`에 의존하지 않음. `KrxMarketRules`(marketdata)·RealTradingGuard·블랙리스트·리스크 한도 무변경 |
| B8 | 통과 | diff 전체에 키·계좌번호·토큰 없음. 새 로그·예외 메시지 없음(기존 stopReason 문구에 종목 코드와 가격만) |

## 점검표 밖 발견 (판정에 영향 없음, 후속 권고)
1. **시가 단일가 경계 비대칭(문서 보완 권고)**: `VirtualExchange.java:198`에서 중간가 틱은 `matchAuction`을 건너뛰어 참여 주문의 `o.auction` 표시가 지워지지 않는다. 종가 쪽은 D-118 "위험과 한계" 2번대로 표시만 남았다가 다음 틱에서 `closingPrint=false`로 지워져 미체결이 되지만, 시가 쪽은 `print = phase == CONTINUOUS`(VirtualExchange.java:394)라서 **다음 단위 정상 체결 틱이 그 가격으로 시가 단일가 참여 주문을 체결**시킨다(D-069 "첫 체결만 단일가 체결"과 다름). 조건은 그날 그 종목 첫 체결이 개장 60초 안(`AUCTION_PRINT_GRACE`)에 오고 그것이 반 칸 가격일 때뿐이고, 정상 시가 단일가 체결가는 호가 단위에 맞으므로 `[추론]` 실제 발생은 드물다. D-118 위험 항목에 시가 쪽 동작을 적고, 원하면 시험 하나로 고정할 것을 권한다.
2. **미국 시장에도 적용됨(문서 보완 권고)**: 판정이 `MarketRules`로만 돌아가므로 `UsMarketRules`(1달러 이상 0.01, 미만 0.0001; marketdata/rules/UsMarketRules.java:49-57)에서도 $100.005 같은 반 페니 체결이 이제 입력 검사를 통과하고 지정가 체결 근거에서 빠진다. 시장별 차이를 `MarketRules`로만 다룬다는 원칙에 맞고 보수적인 방향이지만, D-118은 KRX만 언급한다. 미국 적용 범위를 한 줄 적을 것을 권한다(미국 실시간 모의를 시작하기 전).
3. `server.servlet.encoding.force-response: true`는 JSON뿐 아니라 정적 자원·SSE 등 모든 응답에 charset을 붙인다. 앱 코드에 UTF-8이 아닌 응답 인코딩을 쓰는 곳이 없음을 확인했고(`grep charset|EUC-KR|MS949|produces` → `LocalOnlyRequestFilter.java:128` UTF-8 하나) 부작용은 없다고 본다.
4. 거래소 원문 대조(중간가 호가 체결 규칙)는 D-118에 `[가정]`으로 남아 있고 되돌릴 조건(한 곳 `MidpointTrades`)이 명시되어 있다. 적절하다.

## 판정
승인
