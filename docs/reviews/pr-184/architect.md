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

## 재검토 2026-10-20
- 대상: PR #184 / 브랜치 `claude/project-thread-m8gp5f` / HEAD 6c048c92(코드 변경 커밋 dfe56a0b 포함) / `git diff origin/main...HEAD` 전체(17개 파일, +332 −17)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew clean build` → BUILD SUCCESSFUL in 2m 57s(종료 코드 0). 테스트 결과 XML 합계 1,658건, 실패 0, 오류 0, 건너뜀 0. D-118 시험 5건(sim) 포함.
  - 변이 확인: `VirtualExchange.matchAuction`의 `boolean print = !midpoint && (...)`에서 `!midpoint`를 `true`로 바꾸고 `:autotrade-sim:test --tests '*VirtualExchangeTest*'` → `시가_단일가_뒤_첫_체결이_중간가면_..._D118` FAILED(48건 중 1건). 새 시험이 권고 1 반영을 실제로 잡는다. 확인 뒤 원복(`git status` 깨끗).
  - 보지 않은 것: CI 결과(이 세션 `gh pr checks` GraphQL 403). 판정은 로컬 빌드로 한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew clean build` 실행, BUILD SUCCESSFUL |
| B2 | 통과 | dfe56a0b의 코드 변경은 `autotrade-sim` `exchange` 패키지 안(`VirtualExchange`, `ExecutionSettings`)뿐. 새 의존 없음, ArchUnit 통과 |
| B3 | 통과 | 체결 규칙 계약 변경에 ADR 갱신이 있다: docs/adr/ADR-004-simulation-engine.md 2.2 제목 `BOOK-V6`, 14번 "중간가 체결"(지정가·단일가 체결 제외, 참여 표시만 지움, 평가 가격·호가 없는 종목 시장가 기준에는 사용, 반 칸 없는 데이터는 BOOK-V5와 같음, 미국 규칙에도 적용). 코드(`VirtualExchange.java:198`, `:386-397`)와 일치함을 대조했다. `ExecutionSettings.RULES_VERSION = "BOOK-V6"`(ExecutionSettings.java:32)와 Javadoc 이력, D-118 5번, 기획서 v14.54 행이 같은 내용. 남은 `BOOK-V5` 문자열은 이력 설명과 과거 결정·검토 문서뿐(grep 확인) |
| B4 | 통과 | 판정·참여 표시 정리 모두 이벤트와 규칙만의 함수, 시계·난수 없음. 골든 리포트 변경은 `execution-model` 머리 한 줄(`BOOK-V5`→`BOOK-V6`)뿐이고 거래 내역·지표 줄은 바이트 그대로(golden/krx-golden.report diff 1줄): 반 칸 체결이 없는 데이터는 결과 무변경이라는 주장과 맞다. 실시간 세션 재시작 쪽: `autotrade-sim/.../live`는 체결 모델 ID를 대조하지 않고(`grep RULES_VERSION|executionModel` 결과 없음), LIVE00 입력 기록에는 반 칸 틱이 없으므로(거절된 이벤트는 기록 전, D-118 재시작 절) V5→V6 재생 결과가 같다 `[추론]` |
| B5 | 통과 | 변경 없음(1차 검토와 같음). 새 경계 시험 1999.5·500500·500400은 `BigDecimal.compareTo` |
| B6 | 통과 | 새 시험: `VirtualExchangeTest#시가_단일가_뒤_첫_체결이_중간가면_단일가_체결을_놓친_것으로_보고_다음_체결이_참여_주문을_단일가로_체결하지_않는다_D118`(70,050 중간가 첫 체결 → 체결 없음, 다음 70,100 체결은 접속 매매 통과 체결로 지정가 70,200 체결), `#중간가_판정은_정확한_반_칸만_받는다_D118` 경계 3건 추가, `RecordingExperimentTest#만원대_5원_체결가는_반_칸이라_통과하고_같은_가격의_호가가_잡는다_D118`. 버전 문자열 기대값 2곳(`RecordingExperimentTest`:69, `SimulationExperimentRunnerTest`:159)과 골든 머리 변경은 의도된 버전 갱신이며 다른 단언은 그대로. 건너뜀 0 |
| B7 | 해당 없음 | 변경 경로 `autotrade-sim`, `autotrade-app-lab`(설정·시험), `docs/adr/ADR-004`, `docs/decisions`, `docs/spec`, `docs/reviews` 모두 `scripts/ci/protected-paths.txt` 밖. 실전 엔진·게이트웨이는 `autotrade-sim` 비의존 |
| B8 | 통과 | 추가 diff에 비밀값·새 로그 없음 |

### 1차 권고 반영 확인
- 권고 1(시가 단일가 비대칭): 반영됨. `matchAuction`이 중간가 틱에서도 호출되어 참여 표시를 지우고 체결하지 않는다(VirtualExchange.java:198, 396-397). 다음 정상 체결이 시가 단일가로 체결하는 경로가 없어졌고 시험으로 고정됨.
- 권고 2(미국 적용 범위): 반영됨. D-118 6번, ADR-004 2.2 14번 끝 문장.

### 점검표 밖 발견 (판정에 영향 없음)
1. D-118 "위험과 한계" 2번의 괄호 "(참여 주문은 접속 매매로 이어짐)"은 시가 쪽에만 맞다. 종가 단일가 참여 주문은 놓친 뒤 접속 매매가 없고 ADR-004 2.2 12번대로 장 마감에 만료된다(CLOSED 단계에서는 `matchTrade`를 부르지 않음, VirtualExchange.java:199-201). 다음 문서 수정 때 "시가는 접속 매매로 이어지고 종가는 만료"로 고칠 것을 권한다.
2. ADR-004 2.2 첫 문단의 버전 계보(V1→V5) 서술에 V6 한 문장이 없다. 14번 항목에 버전과 무변경 조건이 적혀 있어 정보는 빠지지 않았다.

## 판정 (재검토)
승인
