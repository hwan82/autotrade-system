# architect 검토: 선정 계좌에서 막힌 신규 진입 신호가 하루 진입 한도·대기 금액을 쓰지 않게(F7-a, D-130, ADR-013)
- 대상: PR #205 / 커밋 50d6227 / `git diff origin/main...HEAD` 전체(10개 파일, +403 −6)
- 점검표: B
- 직접 실행한 것:
  - 별도 작업 트리(`/tmp/arch-wt`, 커밋 50d6227)에서 `./gradlew build --no-daemon` → 종료 코드 0. 테스트 결과 XML 합계 1814건, 건너뜀 0, 실패 0, 오류 0. `SelectionEntryQuotaTest` 4건, `CatalogStrategyTest` 20건 통과. `autotrade-architecture-test`(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest 등) 통과.
  - 회귀 확인: `ManagedStrategy.java`·`RunStepper.java`만 origin/main으로 되돌리고(`StrategyContext`는 PR 것 유지) 새 시험만 실행 → 4건 실패(`CatalogStrategyTest#실행기가_받지_않는_신규_진입은_신호를_내지_않고_하루_진입_수를_쓰지_않는다`, `#실행기가_받지_않는_신규_진입은_대기_진입_금액도_잡지_않는다`, `SelectionEntryQuotaTest#후보_밖_신호가_먼저_와도_후보_종목이_그날_진입_한도_안에서_들어간다`, `#블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다`). 새 시험이 결함을 실제로 잡는다. 확인 뒤 작업 트리를 HEAD로 되돌림(`git status` 깨끗).
  - `grep StrategyContext`(빌드 산출물 제외): 구현은 `RunStepper`(Context·WarmupContext), 시험(`Tape`, `FixtureStrategies`, `SelectionEntryQuotaTest`)뿐. `autotrade-app-engine`은 `autotrade-strategy`에 의존하지만(`autotrade-app-engine/build.gradle.kts:21`) `StrategyContext`를 구현·호출하지 않는다.
  - 매수 신호 생성처 `grep marketBuy|limitBuy`: `ManagedStrategy.java:273`, `BuyAndHold.java:50` 두 곳뿐.
  - `scripts/ci/protected-paths.txt`: `autotrade-strategy/*`가 보호 경로 → 이 PR은 실전 관련 PR.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 별도 작업 트리에서 `./gradlew build` 직접 실행, 종료 코드 0, 테스트 1814건 실패·건너뜀 0 |
| B2 | 통과 | 새 의존 없음. `StrategyContext`(autotrade-strategy, 순수 Java)에 `java.*`·도메인 타입만 쓰는 기본 메서드 추가, `RunStepper`(autotrade-sim)는 `java.util.function.Predicate`만 추가 import(`RunStepper.java:47`). ArchitectureTest·ModuleOwnershipTest 통과. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 계약 변경(`StrategyContext.admitsEntry`, `StrategyContext.java:18-31`)에 ADR-013 신규, ADR 목록(`docs/adr/README.md:19`), 결정 D-130과 결정 로그 표 갱신. 대안 F7-b·c·d 비교·되돌릴 조건(ADR-013 3장), 바뀌지 않는 것(4장), 위험(5장) 있음. 스키마·설정 키·REST 변경 없음. 번호 충돌 없음(origin/main에 D-129·D-130·ADR-013 없음) |
| B4 | 통과 | 새 코드에 시스템 시계·난수 없음. 거름은 이벤트 순번을 캡처한 람다(`RunStepper.java:143-144`)로 같은 입력이면 같은 답. 주문 전 거름(`RunStepper.java:146`)과 같은 기준(`SelectionDay.allowsEntry(symbol, sequence)`, `RunStepper.java:160-166`)이라 묻는 전략의 신호는 안전망에서 다시 걸리지 않는다. 골든·스냅샷 시험 포함 전체 빌드 통과. 선정 없는 계좌는 `SelectionEntryQuotaTest#선정_없는_계좌의_보고서는_admitsEntry_도입_전_문맥과_바이트_단위로_같다`로 보고서 바이트 일치 확인 |
| B5 | 통과 | 금액 계산 변경 없음. 기존 `BigDecimal` 경로(`pendingEntryNotional`, `quantity`)를 건드리지 않고 그 앞에서 반환만 추가(`ManagedStrategy.java:250-254`) |
| B6 | 통과 | 새 동작 시험: `CatalogStrategyTest` 3건(한도·대기 금액·청산은 묻지 않음), `SelectionEntryQuotaTest` 4건(실행기 + 실제 `random` 전략, 블랙리스트 거절 유지, 선정 없는 계좌 동작·바이트 동일). 고치기 전 코드에서 4건 실패를 검토자가 직접 확인. 지운·건너뛴 시험 없음(diff에 삭제된 `@Test`·`@Disabled` 없음) |
| B7 | 해당 — **risk-ops 검토 필요** | 보호 경로 `autotrade-strategy/*`(`scripts/ci/protected-paths.txt`) 변경. 실전 엔진이 의존하는 모듈의 공개 인터페이스(`StrategyContext`)와 `ManagedStrategy` 진입 경로를 바꾼다. 실전 주문 경로·RealTradingGuard·블랙리스트 코드 자체는 diff에 없음(architect 확인 범위). 지침 10·11장에 따라 risk-ops A 점검표 검토, 독립 QA, 사용자 머지 대상 |
| B8 | 통과 | diff에 키·계좌번호·토큰 패턴 없음(`git diff origin/main...HEAD`에서 appkey/secret/token/account 검색 결과 없음). 시험 종목 코드는 공개 종목 코드 |

## 점검표 밖 발견
- N1(참고, 막지 않음): `admitsEntry`는 질의이면서 실행기 쪽에서 "막은 신규 진입" 수를 올리는 부수 효과가 있다(`RunStepper.java:164`). "판단마다 한 번만 묻는다"는 계약은 javadoc(`StrategyContext.java:22-25`)과 ADR-013 2장 2번에만 있고 강제되지 않는다. 지금 묻는 곳은 `ManagedStrategy.entry` 한 곳(`ManagedStrategy.java:250`)이고 `CatalogStrategyTest`가 `admitAsks` 횟수를 고정해 회귀는 잡힌다. 새 전략이 두 번 묻게 되면 `blocked-entries`가 부풀 뿐 주문·체결에는 영향이 없다. 되돌릴 조건으로 충분하다.
- N2(참고): `blocked-entries` 값 뜻이 선정 계좌에서 바뀐다(한도 확인 뒤 → 한도 확인 앞). ADR-013 5장에 적혀 있고 ADR-005·D-060은 값 정의를 세밀히 두지 않아 문서 충돌은 없다. 선정 계좌 결과를 다른 시점 코드끼리 비교하지 않도록 LIVE02 계획 8장 F7 행에 머지 후 "D-130으로 해결, 이전 선정 계좌 결과의 blocked-entries와 비교 불가"를 적는 후속을 권한다(strategy-researcher 산출물이라 제안).
- N3(참고): 바이트 동일 시험의 "도입 전 문맥"은 PR 뒤 `ManagedStrategy`에 기본값 문맥을 넣은 것이라 PR 전 `ManagedStrategy`와의 비교는 아니다. 다만 기본값 true일 때 바뀐 것은 반환 분기 하나뿐이고(`ManagedStrategy.java:250-254`), 기존 골든·스냅샷 시험이 모두 통과하므로 선정 없는 계좌 결과 불변은 확인된 것으로 본다.
- N4(참고): 진입 확인 후 `armedAt.remove`를 먼저 하고 묻는다(`ManagedStrategy.java:249-250`). 막힌 종목의 신호는 소비되어 같은 장에 순위 시각이 지나도 다시 확인 신호를 거쳐야 한다. 고치기 전에도 신호를 내고 버렸으므로 `trigger`·`observe` 상태는 같다(ADR-013 2장 3번과 일치).
- 실전 엔진 영향: `[사실]` 실전 엔진은 `StrategyContext`를 구현하지 않고 기본 메서드라 바이너리 호환이다. 다만 `autotrade-strategy`는 실전 엔진 클래스패스에 들어가므로 판단은 risk-ops에 남긴다(B7).

## 판정
승인 (architect 범위. B7에 따라 risk-ops 검토·독립 QA·사용자 머지가 별도로 필요하다)
