# risk-ops 검토: PR #220 D-135 범위·일 손실 한도 경계 시험(PR #218 QA Q1·Q2)
- 대상: PR #220 / 커밋 daaa87a00724ef83ab52e0c58adb356d0c283321 / main 대비 diff(`git diff origin/main...HEAD`, 2파일 +32줄: `autotrade-strategy/src/test/java/com/autotrade/strategy/catalog/CatalogStrategyTest.java`, `docs/decisions/D-135-blocked-signal-daily-loss-check.md`)
- 점검표: A (보호 경로 `autotrade-strategy/*`를 건드림, `scripts/ci/protected-paths.txt`)
- 직접 실행한 것(별도 git worktree, head daaa87a0에서):
  - `./gradlew :autotrade-strategy:test --tests CatalogStrategyTest` → 성공, 25건 0 실패. 새 시험 2건(`보유_수_상한에_막힌_동안에도_받지_않는_확인_신호로_일_손실_한도를_확인한다`, `일_손실이_한도와_정확히_같으면_멈추고_조금_모자라면_진입한다`)이 실행된 것을 결과 XML로 확인.
  - 변이 P2(`ManagedStrategy.entry` 받지 않은 분기의 `dailyLossReached(account)`를 `allowedByLimits(symbol, now, account, ctx)`로 바꿈) → `CatalogStrategyTest` 실패, 실패 시험은 Q1 새 시험 1건뿐. 원복.
  - 변이 P9(`dailyLossReached`의 `<= 0`을 `< 0`으로) → 실패, 실패 시험은 Q2 새 시험 1건뿐. 원복 후 작업 트리 깨끗함 확인.
  - `./gradlew build --continue` → Maven Central 429(Too Many Requests)로 `autotrade-app-lab` 의존성 해석 실패, 전체 빌드는 이 환경에서 완료 못 함.
  - 같은 worktree에서 `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 68건, `:autotrade-strategy:test` 52건(파일 단위 합), `:autotrade-architecture-test:test` 46건(첫 시도 429, 재시도 BUILD SUCCESSFUL) 모두 0 실패·0 오류·0 건너뜀.
  - GitHub CI run 39546028432(head_sha daaa87a0, PR #220) conclusion=success를 API로 확인.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정·Guard 시험 변경 없음(diff 2파일). 운영 코드 무변경 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 무변경. `:autotrade-architecture-test:test` 46건 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 코드 무변경 |
| A4 | 해당 없음 | 타임아웃·재전송 경로 무변경 |
| A5 | 해당 없음 | 멱등 키 생성 코드 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 거름(`RiskChain`, 실행기 주문 전 거름) 무변경. 새 시험은 전략 내부 일 손실 중단만 다룸 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 해당 없음 | 프로세스 구성 무변경. `autotrade-app-engine`은 `autotrade-strategy`에 의존(build.gradle.kts)하나 운영 코드가 바뀌지 않았고, 실전 엔진은 아직 전략을 돌리지 않는다는 D-135 서술은 기존 검토(PR #205·#218)에서 다뤘음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 무변경 |
| A10 | 통과 | 새 시험의 값은 금액(원)·시각·기존 픽스처 계좌 ID `T-A01`(Tape.java:136, 기존)뿐. 키·계좌번호·토큰 없음. CI gitleaks 포함 run 39546028432 success |
| A11 | 통과 | 보호 시험 삭제·비활성화·약화 없음(추가만, +26줄). 고정 값 갱신 없음. 새 시험 2건이 각각 변이 P2·P9에서 실패함을 직접 확인(위 실행 기록) — 실패하는 경우를 잡는 시험임 |
| A12 | 통과 | 동작 변경 없음(운영 코드 무변경)이라 장애 대응표 17종·O1~O8·장애 테스트 8종 전제에 영향 없음 |
| A13 | 해당 없음 | 시장별 경로 무변경. 새 시험은 기존 `Tape.KRX` 문맥 |
| A14 | 통과 | D-135 "후속 반영(PR #218 QA Q1·Q2)" 절이 추가 시험과 일치: Q1은 `ManagedStrategy.java`의 받지 않은 분기(`if (!haltedToday) dailyLossReached(account)`)가 보유 수 확인과 상관없이 일 손실을 본다는 D-135 "위험과 한계" 첫 항목을 시험으로 고정, Q2는 `dailyLossReached`의 `<= 0`(TradingRules.java:25 "이만큼 이상이면 중단")과 일치. "운영 코드는 바꾸지 않았다"는 diff로 확인 |

## 점검표 밖 발견
- 없음(차단 사항). 참고: Q1 시험은 보유 수 상한에 막힌 경우만 고정한다. D-135 "위험과 한계"가 같은 성질로 적은 재진입 금지 시간에 막힌 경우는 시험이 없다. 같은 분기·같은 메서드라 P2 변이는 이 시험으로 이미 잡히므로 수정 요구는 아니다.
- 이 환경에서 `./gradlew build` 전체는 Maven 429로 끝까지 못 돌렸다. 에이전트 규칙의 최소 범위(`:autotrade-risk:test`, `:autotrade-architecture-test:test`, `:autotrade-app-engine:test`)는 직접 통과를 확인했고, 전체 빌드는 같은 head의 CI run 39546028432 success로 보완했다.

## 판정
승인
