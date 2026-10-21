# risk-ops 검토: PR #221 재진입 금지 시간 안 받지 않은 신호로 일 손실 멈춤 시험(PR #220 QA Q1)
- 대상: PR #221 / 커밋 e0b27ea113b77b0465d9df454c4835e32c613200 / main 대비 diff(`git diff origin/main...HEAD`, merge-base 6a2b80c, 2파일 +19줄: `autotrade-strategy/src/test/java/com/autotrade/strategy/catalog/CatalogStrategyTest.java` +13, `docs/decisions/D-135-blocked-signal-daily-loss-check.md` +6)
- 점검표: A (보호 경로 `autotrade-strategy/*`를 건드림, `scripts/ci/protected-paths.txt`)
- 직접 실행한 것(별도 git worktree, head e0b27ea, `--offline`):
  - `./gradlew :autotrade-strategy:test --tests '*CatalogStrategyTest'` → 성공, 26건 0 실패·0 건너뜀. 새 시험 `재진입_금지_시간_안에도_받지_않는_확인_신호로_일_손실_한도를_확인한다`가 결과 XML에 있음.
  - 변이 P2b(`ManagedStrategy.entry` 받지 않은 분기를 `!haltedToday && (lastExitAt 없음 || 재진입 금지 시간 지남)`일 때만 `dailyLossReached` 부르게 바꿈) → `CatalogStrategyTest` 26건 중 1건 실패, 실패 시험은 새 시험뿐. 원복.
  - 변이 M0(받지 않은 분기의 `dailyLossReached` 호출을 지움, D-135 전 동작) → 3건 실패(`실행기가_받지_않는_확인_신호에서도_일_손실_한도를_확인한다`, `보유_수_상한에_막힌_동안에도_…`, 새 시험). 원복.
  - 변이 P2a(받지 않은 분기를 `entriesToday < maxEntriesPerDay`일 때만 확인) → `:autotrade-strategy:test` 전체 통과(살아남음). D-135 후속 절의 "결과가 같다" 주장을 코드로 확인: `haltedToday`는 `ManagedStrategy.java:128`(초기화)·`:171`·`:318`(켬)·`:254`·`:289`(읽음)뿐이고, 읽는 곳 `allowedByLimits`(`:289`)는 진입 수가 차면 `haltedToday`와 상관없이 false라 관측 결과가 같은 등가 변이다. 원복 후 `git status` 깨끗함.
  - `./gradlew :autotrade-strategy:test :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test` → 성공. 결과 XML 합: strategy 53, risk 55, architecture-test 46, app-engine 68건, 모두 0 실패·0 오류·0 건너뜀.
  - `./gradlew --offline build --continue` → exit 0(이번에는 캐시로 끝까지 돎).
  - GitHub check runs(PR #221 head e0b27ea, run 39552880327): Gradle build, DB schema (PostgreSQL 16), Lab screens (Playwright), Secret scan and collector boundary 모두 success를 API로 확인.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정·Guard 시험 변경 없음(diff 2파일, 운영 코드 무변경) |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 무변경. `:autotrade-architecture-test:test` 46건 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 코드 무변경 |
| A4 | 해당 없음 | 타임아웃·재전송 경로 무변경 |
| A5 | 해당 없음 | 멱등 키 생성 코드 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 거름(`RiskChain`, 실행기 주문 전 거름) 무변경. 새 시험의 `refuse(B)`는 기존 `Tape` 픽스처의 `admitsEntry` 거절이며 전략 내부 일 손실 중단만 다룸 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 해당 없음 | 프로세스 구성 무변경. 운영 코드가 바뀌지 않아 `autotrade-app-engine`(68건 통과) 동작 영향 없음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 무변경 |
| A10 | 통과 | 새 시험의 값은 금액(원)·시각·수량·프로필 키 `limits.reentry-cooldown-minutes`뿐. 키·계좌번호·토큰 없음. CI "Secret scan and collector boundary" success |
| A11 | 통과 | 보호 시험 삭제·비활성화·약화 없음(시험 추가만 +13줄). 고정 값 갱신 없음. 새 시험이 변이 P2b·M0에서 실패함을 직접 확인(위 실행 기록) — 실패하는 경우를 잡는 시험임 |
| A12 | 통과 | 운영 동작 변경 없음이라 장애 대응표 17종·O1~O8·장애 테스트 8종 전제에 영향 없음 |
| A13 | 해당 없음 | 시장별 경로 무변경. 새 시험은 기존 KRX `Tape` 문맥 |
| A14 | 통과 | D-135 "후속 반영(PR #220 QA Q1)" 절이 추가 시험과 일치: 받지 않은 분기(`ManagedStrategy.java:250-257`, `if (!haltedToday) dailyLossReached(account)`)가 재진입 금지 확인(`:292-294`, `allowedByLimits` 안에만 있음)과 상관없이 일 손실을 본다는 D-135 "위험과 한계" 첫 항목을 시험으로 고정. 09:10 이벤트에서 `trackPosition`이 `lastExitAt(B)=09:10`(`:167`)을 기록한 뒤 같은 이벤트에서 `entry`로 가므로 경과 0분 < 30분, P2b가 확인을 건너뛰는 상황이 맞음(변이 실행으로 확인). P2a를 시험하지 않은 이유(결과가 같음)도 등가 변이로 확인. "운영 코드는 바꾸지 않았다"는 diff로 확인 |

## 점검표 밖 발견
- 없음(차단 사항). PR #220 risk-ops 검토 "점검표 밖 발견"에 적은 참고(재진입 금지 시간에 막힌 경우의 시험 공백)가 이 PR로 메워졌다.
- 참고: P2a는 등가 변이라 어떤 관측 시험으로도 잡을 수 없다. 뒤에 `haltedToday`를 다른 곳(보고서·깔때기·청산 등)에서 읽게 바꾸면 등가가 깨지므로, 그런 변경은 이 분기에 진입 수 상한이 찬 날의 시험을 함께 넣어야 한다(지금 수정 요구 아님).

## 판정
승인
