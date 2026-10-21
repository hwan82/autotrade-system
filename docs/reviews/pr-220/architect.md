# architect 검토: D-135 범위·일 손실 한도 경계 시험(PR #218 QA Q1·Q2)
- 대상: PR #220 / 커밋 daaa87a00724ef83ab52e0c58adb356d0c283321 / main(b4b39b6c) 대비 diff (`git diff origin/main...HEAD`: 2개 파일, +32)
  - `autotrade-strategy/src/test/java/com/autotrade/strategy/catalog/CatalogStrategyTest.java` (시험 2건 추가)
  - `docs/decisions/D-135-blocked-signal-daily-loss-check.md` (후속 반영 절 추가)
- 점검표: B
- 직접 실행한 것 (검토자 전용 worktree, 같은 헤드):
  - `./gradlew build --offline` → 실패. `:autotrade-app-collector:testRuntimeClasspath`의 junit-platform-launcher 1.12.2 등이 오프라인 캐시에 없음.
  - `./gradlew build` (온라인) → 실패. `:autotrade-monitor-sources:compileJava`가 oshi-core 6.12.0 의존성 해석에서 Maven Central `429 Too Many Requests`. 테스트 단계까지 가지 못함.
  - `./gradlew :autotrade-strategy:test --offline` → BUILD SUCCESSFUL. CatalogStrategyTest 25건, DailyCloseLogicsTest 14건, StrategyProfileTest 5건, TradeSignalTest 2건, ProfileYamlTest 6건. 실패 0, 건너뜀 0. 새 시험 2건도 결과 XML에 있음.
  - 변이 시험 (`ManagedStrategy.java`를 손으로 고친 뒤 `:autotrade-strategy:test --tests '*CatalogStrategyTest'`, 끝나면 원복):
    - P2: 255행 `dailyLossReached(account);` → `allowedByLimits(symbol, now, account, ctx);` → **죽음**. 실패한 시험은 `보유_수_상한에_막힌_동안에도_받지_않는_확인_신호로_일_손실_한도를_확인한다()` 하나뿐.
    - P9: 317행 `compareTo(...) <= 0` → `< 0` → **죽음**. 실패한 시험은 `일_손실이_한도와_정확히_같으면_멈추고_조금_모자라면_진입한다()` 하나뿐.
  - GitHub check runs (`pull_request_read get_check_runs`) 직접 조회: 같은 헤드 run 39546028432의 Gradle build, DB schema, Lab screens, Secret scan 4종 모두 success.
  - PR #220 댓글 조회: 0건. "CI 결과를 B1 근거로 인정" 같은 사용자 댓글이 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 검토자가 직접 돌린 `./gradlew build`는 의존성 해석(Maven Central 429, 오프라인 캐시 없음) 단계에서 실패해 전체 빌드 결과를 얻지 못했다. 바뀐 모듈 `:autotrade-strategy:test`는 직접 돌려 통과했다. 같은 헤드 CI `Gradle build`는 success로 직접 확인했으나, 이 PR에는 CI를 B1 근거로 인정한다는 사용자 댓글이 없다(PR #218 선례). |
| B2 | 통과 | 운영 코드·빌드 스크립트·의존성 변경이 없다. 시험 파일에 새 import가 없다(diff는 메서드 2개만 추가). ArchUnit은 로컬에서 돌리지 못했고 CI success만 확인했다. 다만 diff상 모듈 의존이 바뀔 여지가 없다. |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 시험에 쓴 키 `sizing.max-positions`(TradingRules.java:92), `limits.daily-loss-limit-pct`(TradingRules.java:96, 시험 기본값 CatalogStrategyTest.java:465 "2")는 기존 키다. |
| B4 | 통과 | 새 시험은 `create()`에서 고정 시드 42L(CatalogStrategyTest.java:427-428)과 Tape의 고정 시각·금액만 쓴다. 시스템 시계·시드 없는 난수가 없다. 금액 비교는 `MathContext.DECIMAL64` 나눗셈이고 −200,000/10,000,000 = −0.02가 정확히 나와 경계가 결정론적이다. |
| B5 | 통과 | 시험 금액은 long 원 단위(`Tape.cash(long)` → `Money.of`)이고, 운영 비교는 BigDecimal(ManagedStrategy.java:312-321)이다. |
| B6 | 통과 | 새 시험 2건이 각각 QA Q1(P2)와 Q2(P9) 변이를 죽이는 것을 직접 확인했다(위 변이 시험). 기존 시험은 지우거나 건너뛰거나 약화하지 않았다(diff에 삭제 줄 0). Q1 시험은 보유 수 1 = 상한(`sizing.max-positions`=1, 평가금액 8,790,000 + 100×10,000 = 9,790,000, −2.1%)으로 P2 변이가 보유 수 확인에서 먼저 false를 내게 한다. 그 뒤 A 청산·회복 상태에서 진입이 없음을 본다. Q2 시험은 −2.0% 정확히(멈춤)와 −1.9999%(진입 1건) 양쪽을 본다. |
| B7 | risk-ops 검토 필요 | 운영 코드는 바뀌지 않았지만 `autotrade-strategy/*`가 `scripts/ci/protected-paths.txt`:15에 있는 실전 경로 런타임 모듈이다. 그 안의 시험을 바꾸므로 실전 관련 PR이다(일 손실 한도 = 리스크 한도 동작 고정). risk-ops 검토, 머지 전 독립 QA, 사용자 머지가 필요하다. |
| B8 | 통과 | diff에 API Key·계좌번호·토큰이 없다. 시험 계좌 ID는 기존 Tape의 자리표시자 `T-A01`이다. CI Secret scan은 success. |

## 점검표 밖 발견
- 없음(결함). 참고:
  - D-135 문서의 "`<=`를 `<`로 바꾸면 실패해야 한다"는 운영 코드 ManagedStrategy.java:317 `compareTo(rules.dailyLossLimit().negate()) <= 0`과 맞는다. 검토자가 직접 그렇게 바꿔 실패를 확인했다.
  - Q1 시험은 09:11에 A의 보유를 0으로 바꾼 뒤 진입이 없는지만 본다. 다음 거래일 재진입은 기존 시험 `실행기가_받지_않는_확인_신호에서도_일_손실_한도를_확인한다()`가 덮는다. 따라서 빠진 범위는 아니다.
  - Q1 시험의 주석 "D-135는 받지 않은 신호에서 진입 수·재진입·보유 수 확인 없이 일 손실만 본다"는 ManagedStrategy.java:249-257 분기(`if (!haltedToday) dailyLossReached(account)`)와 맞는다.

## 판정
수정 필요(B1 확인 못 함: 이 컨테이너에서 `./gradlew build`가 Maven Central 429와 오프라인 캐시 부족으로 의존성 해석 단계에서 실패해 전체 빌드를 직접 확인하지 못했다. 바뀐 모듈 `:autotrade-strategy:test`는 직접 통과시켰고 변이 P2·P9가 새 시험으로 죽는 것도 확인했다. 같은 헤드 CI `Gradle build` success(run 39546028432)도 직접 확인했으므로, 사용자가 이 PR에 "CI 결과를 B1 근거로 인정"을 남기면 B1은 CI로 판단할 수 있다. 코드·시험 결함으로 인한 수정 요구는 없다. B7: 보호 경로(`autotrade-strategy/*`) 변경이므로 risk-ops 검토·독립 QA·사용자 머지 대상이며 이 판정만으로 Claude가 머지하지 않는다)
