# QA 검토: 재진입 금지 시간 안 받지 않은 신호로 일 손실 멈춤 시험(PR #220 QA Q1)
- 대상: PR #221 / 커밋 24696a6aa0113882bd6c67c89d270187c2c7f912 / main(6a2b80c4) 대비 diff(`git diff origin/main...qa-221`: 5파일 +84, 삭제 0줄)
  - `autotrade-strategy/src/test/java/com/autotrade/strategy/catalog/CatalogStrategyTest.java`(시험 1건 +13, 보호 경로 `autotrade-strategy/*`)
  - `docs/decisions/D-135-blocked-signal-daily-loss-check.md`(후속 반영 절 +6)
  - `docs/qa/pending.md`(+1), `docs/reviews/pr-221/architect.md`, `docs/reviews/pr-221/risk-ops.md`
  - 운영 코드 변경 0줄. 역할 검토가 본 e0b27ea1 뒤 커밋(d2d008f2, 70c7e9de, 24696a6a)은 검토 원문 2개와 `pending.md` 한 줄만 더한 것을 `git log`·diff로 확인.
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 24696a6a) → 1~4회째 의존성 해석 실패(Maven Central 429, `Could not GET ... Received status code 429`), 20초 간격 재시도 5회째 **BUILD SUCCESSFUL in 4m 53s**, 138 tasks executed. 미러 init 스크립트는 쓰지 않음. 결과 XML 합계 1,887건, 실패 0, 오류 0, 건너뜀 0. `CatalogStrategyTest` 26건에 새 시험 `재진입_금지_시간_안에도_받지_않는_확인_신호로_일_손실_한도를_확인한다()` 포함.
  - CI: 헤드 24696a6a run 39553877671에서 **Gradle build 실패**(`:autotrade-app-gateway:test` `GatewayFeedTest.녹화하는_줄을_실험실에도_같은_순서로_넘긴다` GatewayFeedTest.java:87, 마지막 `SOURCE_SWITCH` DataGap 한 줄 누락), DB schema·Lab screens·Secret scan 3종 success(GitHub check runs·잡 로그 직접 조회). 같은 코드인 e0b27ea1 run 39552880327은 4종 success(역할 검토 원문 주장, 이 보고서에서는 다시 조회하지 않음). B1은 직접 실행 결과로 판정하며 CI는 보조 근거. 아래 R1.
  - `GatewayFeedTest` 단독 15회 반복(`:autotrade-app-gateway:test --tests '...GatewayFeedTest' --rerun-tasks`, 헤드 24696a6a) → 15회 모두 통과. 이 PR은 게이트웨이 코드·시험을 건드리지 않는다(diff 확인).
  - 변이 시험(별도 worktree, `ManagedStrategy.java` 받지 않은 분기(250~257행)를 하나씩 바꾸고 `:autotrade-strategy:test --tests 'com.autotrade.strategy.catalog.CatalogStrategyTest' --rerun-tasks`, 실패 시험은 결과 XML에서 읽음, 끝에 원복·`git status` 깨끗함 확인):

| 변이 | 내용 | 결과 |
|---|---|---|
| P0 | 받지 않은 분기의 `dailyLossReached` 호출 삭제 | 잡힘(3건: 새 시험, `보유_수_상한에_막힌_동안에도_…`, `실행기가_받지_않는_확인_신호에서도_…`) |
| P2b | 그 종목 `lastExitAt` 뒤 재진입 금지 시간이 지났을 때만 확인(PR #220 QA P2b) | 잡힘(새 시험만) |
| P2d | 그 종목 청산이 바로 이 순간(`lastExitAt == now`)이면 확인 건너뜀 | 잡힘(새 시험만) |
| P2e | 어느 종목이든 재진입 금지 중이면 확인 건너뜀 | 잡힘(새 시험만) |
| P2f | 그 종목을 그날 한 번이라도 청산했으면 확인 건너뜀 | 잡힘(새 시험만) |
| P2g | 보유 없고 청산 기록도 없을 때만 확인 | 잡힘(2건: 새 시험, `보유_수_상한에_막힌_동안에도_…`) |
| P2h | 재진입 금지 중이되 경과 시간이 0보다 클 때만 건너뜀(`ex.isBefore(now)` 조건) | **생존**(아래 Q1) |

  - P2h 확인용 임시 시험(커밋하지 않음): 재진입 금지 30분, B를 09:05에 청산 관측(손실 없음, 받지 않은 B 신호), 09:10 현금 9,790,000(−2.1%)에서 B 확인 신호(받지 않음, 경과 5분), 회복 뒤 09:11 A 신호. PR 코드 → A 진입 없음(27건 통과). P2h 변이 → 이 임시 시험만 실패(27건 중 1건). 운영 코드는 D-135대로 재진입 금지 중간에도 멈춘다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정·Guard 시험 변경 없음. 운영 코드 0줄 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 무변경. 직접 빌드에서 `autotrade-architecture-test` 포함 전체 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 무변경 |
| A4 | 해당 없음 | 타임아웃·재전송 경로 무변경 |
| A5 | 해당 없음 | 멱등 키 코드 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 거름 무변경. 새 시험의 `refuse(B)`는 기존 `Tape` 픽스처의 `admitsEntry` 거절(Tape.java:102·155)이며 전략 내부 일 손실 중단만 다룸 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 해당 없음 | 프로세스 구성·의존성 무변경 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 무변경 |
| A10 | 통과 | 새 시험 값은 원 금액·시각·수량·기존 키 `limits.reentry-cooldown-minutes`뿐. 새 문서·검토 원문에도 키·계좌번호·토큰 없음(직접 읽음). CI Secret scan success(run 39553877671) |
| A11 | 통과 | diff 삭제 줄 0, `@Disabled`·건너뜀 없음, 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 시험이 P0·P2b·P2d·P2e·P2f·P2g에서 실패함을 직접 확인. 생존 P2h는 아래 Q1 |
| A12 | 통과 | 동작 변경 없음(운영 코드 0줄). 장애 대응표·장애 테스트 8종 전제에 영향 없음 |
| A13 | 해당 없음 | 시장별 경로 무변경. 새 시험은 KRX `Tape` 문맥 |
| A14 | 통과 | D-135 "후속 반영(PR #220 QA Q1)" 절 주장을 직접 확인: P2b 조건을 넣으면 새 시험이 실패한다(위 표). P2a를 시험하지 않은 이유(진입 수가 차면 그날 진입이 모두 막혀 결과가 같다)는 PR #220 QA에서 같은 동작 변이로 확인한 것과 같고, 이후 `haltedToday`를 읽는 곳(ManagedStrategy.java:254·289)이 바뀌지 않았음을 diff로 확인. "운영 코드는 바꾸지 않았다"는 diff로 확인 |
| B1 | 통과 | 검토자가 직접 실행한 `./gradlew build --rerun-tasks`가 5회째 BUILD SUCCESSFUL(1,887건, 실패 0). 앞 4회는 의존성 다운로드 429. CI Gradle build 실패는 R1 |
| B6 | 통과 | 새 시험이 PR #220 QA Q1(P2b) 변이를 잡음. 시험 삭제·건너뜀 없음 |

## 점검표 밖 발견
- 시나리오 1(청산 관측과 같은 순간의 받지 않은 신호, P2b·P2d): 새 시험이 잡는다. 09:10 이벤트에서 `trackPosition`이 `lastExitAt[B]=09:10`(ManagedStrategy.java:167)을 기록한 뒤 같은 이벤트에서 `entry`로 가므로 경과 0분 상황이다.
- 시나리오 2(재진입 금지 중간의 받지 않은 신호, P2h): 운영 코드는 맞게 멈춘다(임시 시험 통과). 그러나 커밋된 시험은 경과 0분 한 점만 고정해, "청산 뒤 시간이 흐른 금지 구간"에서만 확인을 건너뛰는 변경이 모든 시험을 통과한다. 그 경우 금지 시간 중간에 같은 종목 신호로 닿은 손실을 놓치고, 회복 뒤 다른 후보가 그날 진입한다. 경과 0분만 건너뛰지 않는 모양의 변이는 부자연스러워 실제로 들어올 가능성은 낮다 [추론].
- 시나리오 3(다른 종목 재진입 금지, P2e): 어느 종목이든 금지 중이면 건너뛰는 변경도 새 시험이 잡는다.
- 시나리오 4(새 시험이 다른 이유로 통과하는지): 09:11 A 시점 현금을 10,000,000으로 되돌려 손실이 없으므로 A가 막히는 이유는 09:10 B 신호에서 켠 `haltedToday`뿐이다. P0에서 새 시험이 실패하는 것으로 확인.
- R1(CI): 헤드 24696a6a의 CI Gradle build가 이 PR과 무관한 `GatewayFeedTest.녹화하는_줄을_실험실에도_같은_순서로_넘긴다`에서 실패했다. 시험은 300ms 동안 줄이 더 안 오면 게이트웨이를 닫는 시간 기반 대기(GatewayFeedTest.java:76~80)라 느린 러너에서 마지막 줄을 놓칠 수 있다 [추론]. 로컬 15회 반복은 모두 통과. 이 PR이 게이트웨이를 건드리지 않아 판정을 막지 않으나, 머지 전 CI 재실행 녹색 확인과 시험 대기 방식 개선(녹화 줄 수만큼 기다리기 등)은 별도 작업으로 권한다.
- 권고 Q1: P2h 생존. 재진입 금지 구간 중간(청산 뒤 몇 분 지난 시점)의 받지 않은 확인 신호로도 그날 멈추는 시험(위 임시 시험과 같은 모양)을 더하길 권한다. 운영 코드 결함은 아니므로 판정을 막지 않는다.

## 판정
승인
