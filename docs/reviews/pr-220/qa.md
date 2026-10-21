# QA 검토: D-135 범위·일 손실 한도 경계 시험(PR #218 QA Q1·Q2)
- 대상: PR #220 / 커밋 d908ceeda8c64790ee4edba12b8dd9e48c9f5f93 / main(b4b39b6c) 대비 diff(`git diff origin/main...pr-220`: 5파일 +131, 삭제 0줄)
  - `autotrade-strategy/src/test/java/com/autotrade/strategy/catalog/CatalogStrategyTest.java`(시험 2건 +26, 보호 경로 `autotrade-strategy/*`)
  - `docs/decisions/D-135-blocked-signal-daily-loss-check.md`(후속 반영 절 +6)
  - `docs/qa/pending.md`(+1), `docs/reviews/pr-220/architect.md`, `docs/reviews/pr-220/risk-ops.md`
  - 운영 코드 변경 0줄. risk-ops가 본 daaa87a0 뒤 커밋(23decb04, d908ceed)은 `git diff --stat daaa87a0 d908ceed`로 문서 3개만 바뀐 것을 확인.
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 d908ceed) → 1~12회째 의존성 해석 실패(`Could not resolve all files/artifacts`, Maven Central 429), 20초 간격 재시도 13회째 **BUILD SUCCESSFUL in 3m 4s**, 138 tasks executed. 미러 init 스크립트는 쓰지 않음. 결과 XML 합계 1,886건, 실패 0, 오류 0, 건너뜀 0. `CatalogStrategyTest` 25건에 새 시험 2건 포함.
  - CI: 헤드 d908ceed run 39547915773 Gradle build·DB schema·Lab screens·Secret scan 4종 success(GitHub check runs 직접 조회). B1은 직접 실행 결과로 판정하며 CI는 보조 근거.
  - 변이 시험(별도 worktree, `ManagedStrategy.java` 하나씩 바꾸고 `:autotrade-strategy:test --tests '*CatalogStrategyTest' --rerun-tasks`, 실패 시험은 결과 XML에서 읽음, 끝에 원복·`git status` 깨끗함 확인):

| 변이 | 내용 | 결과 |
|---|---|---|
| P0 | 받지 않은 분기의 `dailyLossReached` 호출 삭제 | 잡힘(새 Q1 시험 + 기존 `실행기가_받지_않는_확인_신호에서도_일_손실_한도를_확인한다`) |
| P2 | 받지 않은 분기에서 `allowedByLimits` 전체 호출(PR #218 QA P2) | 잡힘(새 Q1 시험만) |
| P2c | 받지 않은 분기에 보유 수 `< maxPositions`일 때만 확인 | 잡힘(새 Q1 시험만) |
| P2a | 받지 않은 분기에 `entriesToday < maxEntriesPerDay`일 때만 확인 | 생존. 같은 동작 변이: `entriesToday`는 그날 줄지 않고(ManagedStrategy.java:126·274), 진입 수가 차면 `allowedByLimits`(289행)가 그날 모든 진입을 막으므로 `haltedToday`를 켜든 안 켜든 결과가 같다 |
| P2b | 받지 않은 분기에 그 종목 재진입 금지 시간이 지났을 때만 확인 | **생존**(아래 Q1) |
| P9 | `dailyLossReached`의 `<= 0` → `< 0`(PR #218 QA P9) | 잡힘(새 Q2 시험만) |
| P9b | 손실률 나눗셈을 `DECIMAL64` 대신 소수 4자리 HALF_UP | 잡힘(새 Q2 시험만, −1.9999%가 −0.0200으로 반올림돼 멈춤) |
| P9c | 손실률 나눗셈을 소수 3자리 DOWN | 생존. 한도 2%(소수 3자리 안에 정확히 표현)에서는 같은 동작: (−0.021, −0.020] 구간은 −0.020으로 잘려 그대로 멈추고 (−0.020, −0.019) 구간은 −0.019로 잘려 그대로 진입 |

  - P2b 확인용 임시 시험(커밋하지 않음): 재진입 금지 30분, B 보유를 09:10에 0으로(그 순간 B 청산 기록) 바꾸고 현금 9,790,000(−2.1%)에서 B 확인 신호(받지 않음), 회복 뒤 09:11 A 신호. PR 코드 → A 진입 없음(통과). P2b 변이 → 이 시험만 실패(26건 중 1건). 운영 코드는 D-135대로 동작하고, 커밋된 시험이 이 범위를 고정하지 않을 뿐이다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정·Guard 시험 변경 없음. 운영 코드 0줄 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 무변경. 직접 빌드에서 `autotrade-architecture-test` 포함 전체 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 무변경 |
| A4 | 해당 없음 | 타임아웃·재전송 경로 무변경 |
| A5 | 해당 없음 | 멱등 키 코드 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 거름 무변경. 새 시험은 전략 내부 일 손실 중단만 다룸 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 해당 없음 | 프로세스 구성·의존성 무변경 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 무변경 |
| A10 | 통과 | 새 시험 값은 원 금액·시각·기존 픽스처 계좌 ID `T-A01`(Tape.java:136)뿐. 새 문서에도 키·계좌번호·토큰 없음. CI Secret scan success(run 39547915773) |
| A11 | 통과 | diff 삭제 줄 0, `@Disabled`·건너뜀 없음, 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 시험 2건이 각각 P2·P2c·P0(Q1), P9·P9b(Q2) 변이에서 실패함을 직접 확인. 생존 P2b는 아래 Q1 |
| A12 | 통과 | 동작 변경 없음(운영 코드 0줄). 장애 대응표·장애 테스트 8종 전제에 영향 없음 |
| A13 | 해당 없음 | 시장별 경로 무변경. 새 시험은 KRX 문맥 |
| A14 | 통과 | D-135 "후속 반영" 절의 두 주장을 직접 확인: Q1 시험은 P2(`allowedByLimits` 전체)에서 실패, Q2 시험은 `<=`→`<`에서 실패. "운영 코드는 바꾸지 않았다"는 diff로 확인. 운영 분기 ManagedStrategy.java:249-257, 경계 317행이 문서·`TradingRules` 주석(25행 "이만큼 이상이면 중단")과 맞음 |
| B1 | 통과 | 검토자가 직접 실행한 `./gradlew build --rerun-tasks`가 13회째 BUILD SUCCESSFUL(1,886건, 실패 0). 앞 12회는 의존성 다운로드 429 |
| B6 | 통과 | 새 시험 2건이 PR #218 QA Q1(P2)·Q2(P9) 변이를 각각 잡음. 시험 삭제·건너뜀 없음 |

## 점검표 밖 발견
- 시나리오 1(재진입 금지 중 받지 않은 신호, 변이 P2b): 운영 코드는 맞게 멈춘다(임시 시험 통과). 그러나 커밋된 시험은 D-135 범위 중 "보유 수 확인 없이"만 고정하고 "재진입 확인 없이"는 고정하지 않는다. 받지 않은 분기에 재진입 금지 조건을 끼워 넣는 변경이 모든 시험을 통과한다. 그 경우 금지 시간 안에 같은 종목 신호로 닿은 손실을 놓치고, 회복 뒤 다른 후보가 그날 진입한다.
- 시나리오 2(진입 수 한도가 찬 뒤 받지 않은 신호, 변이 P2a): 진입 수가 차면 그날 진입이 모두 막혀 `haltedToday`와 결과가 같다. 보호 공백 없음.
- 시나리오 3(손실률 반올림이 한도 경계를 흐림, P9b·P9c): 소수 4자리 반올림은 새 Q2 시험이 잡는다. 소수 3자리 버림은 정수 % 한도에서 같은 동작이다. 한도에 소수 넷째 자리가 있으면(예 1.2345%) 달라지나, 운영 코드는 `DECIMAL64`를 쓰므로 현재 위험 없음 [추론].
- 시나리오 4(경계 −2.0%의 정확도): (9,800,000 − 10,000,000) / 10,000,000 = −0.02가 `DECIMAL64`에서 정확히 나오고, `compareTo`는 scale을 무시하므로 `limits.daily-loss-limit-pct`="2"가 0.02 어떤 scale로 읽혀도 같은 판정이다. 경계 시험은 결정론적.
- 권고 Q1: P2b 생존. 재진입 금지 시간 안의 받지 않은 확인 신호로도 그날 멈추는 시험(위 임시 시험과 같은 모양)을 더하길 권한다. 운영 코드 결함은 아니므로 판정을 막지 않는다.

## 판정
승인
