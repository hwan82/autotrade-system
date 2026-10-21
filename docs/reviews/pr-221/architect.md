# architect 검토: 재진입 금지 시간 안 받지 않은 신호로 일 손실 멈춤 시험(PR #220 QA Q1)
- 대상: PR #221 / 커밋 e0b27ea113b77b0465d9df454c4835e32c613200 / main(6a2b80c) 대비 diff (`git diff origin/main...HEAD`: 2개 파일, +19, 삭제 0)
  - `autotrade-strategy/src/test/java/com/autotrade/strategy/catalog/CatalogStrategyTest.java` (시험 1건 추가, 200~211행)
  - `docs/decisions/D-135-blocked-signal-daily-loss-check.md` (후속 반영(PR #220 QA Q1) 절 추가)
- 점검표: B
- 직접 실행한 것 (검토자 전용 worktree, 같은 헤드):
  - `./gradlew build --offline` → **BUILD SUCCESSFUL in 6m 30s**, 138 tasks. 전체 테스트 결과 XML 합계 1,887건, 실패 0, 오류 0, 건너뜀 0. `CatalogStrategyTest` 26건 실패 0, 새 시험 `재진입_금지_시간_안에도_받지_않는_확인_신호로_일_손실_한도를_확인한다()`가 결과 XML에 있음. `verifyPureDependencies`·`autotrade-architecture-test` 포함.
  - 변이 시험 (`ManagedStrategy.java`를 손으로 고친 뒤 `:autotrade-strategy:test --tests 'com.autotrade.strategy.catalog.CatalogStrategyTest'`, 끝나면 원복하고 다시 돌려 26건 통과·`git status` 깨끗함 확인):
    - P2b: 받지 않은 분기(254행)를 `lastExitAt`의 그 종목 재진입 금지 시간이 지났을 때만 `dailyLossReached` 부르도록 바꿈 → **죽음**. 실패 시험은 새 시험 하나뿐(26건 중 1건). QA가 PR #220에서 생존으로 본 변이를 이 시험이 잡는다.
    - P0: 받지 않은 분기의 `dailyLossReached` 호출 삭제 → **죽음**. 실패 3건: 새 시험, `보유_수_상한에_막힌_동안에도_…`, `실행기가_받지_않는_확인_신호에서도_…`.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --offline` 직접 실행 성공, 실패·건너뜀 0 |
| B2 | 통과 | 운영 코드·빌드 스크립트·의존성 변경 없음(diff는 시험 1건과 결정 문서). `verifyPureDependencies`·ArchUnit 통과 |
| B3 | 해당 없음 | 인터페이스·스키마·설정 키·REST API 변경 없음. 시험이 쓰는 키 `limits.reentry-cooldown-minutes`는 기존 키 |
| B4 | 통과 | 새 시험은 `Tape`의 고정 시각·고정 프로필(`random`의 `signal.per-mille`=1000, 결정론)만 쓴다. 시스템 시계·시드 없는 난수 없음. 골든·스냅샷 시험이 포함된 전체 빌드 통과 |
| B5 | 해당 없음 | 운영 금액 계산 변경 없음. 시험 값은 정수 원 단위(`cash(9_790_000)` 등) |
| B6 | 통과 | 새 시험이 D-135의 "재진입 금지 확인 없이 일 손실만 본다" 범위를 고정함을 변이 P2b로 직접 확인. 시나리오 검산: 09:00 B 보유로 당일 시작 평가금액 10,000,000(Tape 기본 현금 대신 9,000,000 + 100×10,000), 09:10 B 보유 0 관측 → `trackPosition`이 `lastExitAt[B]=09:10`(ManagedStrategy.java:167) 기록 후 같은 이벤트에서 확인 신호 → `admitsEntry` false 분기(250~257행)에서 −2.1%로 `haltedToday`. 09:11 A는 `allowedByLimits` 289행에서 막힘. 시험 삭제·`@Disabled`·건너뜀 없음 |
| B7 | 해당 없음(단, 보호 경로) | 실전 주문 경로·RealTradingGuard·GuardedOrderGateway·블랙리스트·`RiskChain`·게이트웨이 변경 없음. 다만 `autotrade-strategy/*`가 `scripts/ci/protected-paths.txt`:15 보호 경로라 지침 10·11장상 실전 관련 PR로 취급되며 risk-ops 검토·독립 QA·사용자 머지 대상이다(D-135 상태 줄과 같음). **risk-ops 검토 필요** |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(직접 확인, 시험 수치와 한국어 설명뿐). gitleaks는 로컬에서 돌리지 않았고 CI 결과에 맡김 |

## 점검표 밖 발견
- 문서 주장 확인: D-135 후속 반영 절의 "P2b 조건을 넣으면 이 시험이 실패해야 한다"는 변이 P2b로 직접 확인(위). "운영 코드는 바꾸지 않았다"는 diff로 확인.
- P2a(진입 수 한도가 차지 않았을 때만 확인)를 시험하지 않은 이유("진입 수가 차면 그날 진입이 모두 막혀 결과가 같다")는 코드와 맞다: `haltedToday`를 읽는 곳은 `allowedByLimits` 289행 하나이고 같은 줄에서 `entriesToday >= maxEntriesPerDay`가 먼저 막으며, `entriesToday`는 그날 줄지 않는다(126행 초기화, 274행 증가만). 관측 가능한 차이가 없는 같은 동작 변이로 본다 `[추론]`(코드 읽기 근거, 변이를 돌려 확인하지는 않음. 판정에는 영향 없음).
- 사소: 새 시험 설명 "후보 밖 B를 09:10에 막 판 직후"는 실제로는 09:10 이벤트에서 청산이 관측되고 같은 이벤트에서 확인 신호가 오는 모양(금지 시간 경과 0분)이다. 시험 안의 `as(...)` 문구가 이를 바르게 적고 있어 고칠 필요는 없다.

## 판정
승인
