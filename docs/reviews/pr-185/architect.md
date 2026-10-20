# architect 검토: 녹화 판정 묶음 실패 즉시 올리기, 주간 점검 중간가 체결 표, L5 겹침 시험, ADR-004 BOOK-V6 계보
- 대상: PR #185 / 커밋 79e8a8e (코드 커밋. 그 뒤 3ed54db는 `docs/reviews/pr-185/strategy-researcher.md` 추가뿐) / `git diff origin/main...HEAD` 전체(8개 파일, +316 −20)
- 점검표: B
- 직접 실행한 것: 커밋 79e8a8e를 별도 worktree로 꺼내 `./gradlew build --no-daemon` → **EXIT=0**(BUILD 성공). `autotrade-sim` 시험 354건 실패·건너뜀 0. 새 시험 `LiveJudgementTest` 15/15, `LiveWeeklyCheckTest` 12/12, `LiveJudgeCriteriaTest` 14/14 통과(아래 시험 이름 확인). `scripts/ci/protected-paths.txt` 패턴과 바뀐 파일 대조 → 걸리는 것 없음.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 79e8a8e worktree에서 `./gradlew build` EXIT=0. ArchUnit(`ArchitectureTest`, `ModuleOwnershipTest`) 포함 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-sim`의 `sim.live` 두 파일뿐. 새 import는 JDK `java.util.concurrent`, 같은 모듈 `sim.exchange.MidpointTrades`·`sim.run.ExperimentProgress`, `domain.market.TradeTick`. Spring·증권사·DB 의존 없음. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 공개 계약 변경은 `LiveWeeklyCheck.DayRow`(public record)에 `midpoints` 필드 추가와 새 public record `MidpointCount`. 저장소 안 다른 생성처 없음(grep `new DayRow` → LiveWeeklyCheck.java 안 두 곳뿐). 주간 보고서 형식 변경(5장 추가)은 ADR-010 미해결 표(docs/adr/ADR-010-live-feed-and-session.md:110)에 반영. `runAll`·`abortable`은 패키지 전용. 설정 키·스키마·REST 변경 없음. ADR-004 2.2 BOOK-V6 계보 문장은 D-118(docs/decisions/D-118-midpoint-trade-ticks.md:26-28)과 일치 |
| B4 | 통과 | 판정 결과: `runAll`은 끝난 순서와 상관없이 작업 순서로 결과를 돌려준다(LiveJudgement.java:83-123, 시험 `run_all_returns_results_in_task_order_whatever_finishes_first`). 결과 리스트는 서로 다른 인덱스에만 `set`하고 `take().get()` 뒤에 읽으므로 FutureTask happens-before로 보인다. `abortable`은 멈출 때만 다르고 계속할 때는 원래 문과 같다(`abortable_gate_stops_once_failed_and_otherwise_follows_the_original_gate`). 주간 점검 집계는 `TreeMap<Symbol,…>`(Symbol은 Comparable)로 순서가 정해지고 벽시계·난수 없음. 체결 엔진·`VirtualExchange` 변경 없음 → 골든 시험 그대로 통과(빌드) |
| B5 | 통과 | 비중은 `BigDecimal` 나눗셈, 소수 둘째 자리 HALF_EVEN(LiveWeeklyCheck.java:289-295, 시험 `shareIsAPercentWithTwoDecimals`). 틱 수·거래량은 `long`. 금액 계산 추가 없음 |
| B6 | 통과 | 새 동작마다 시험: 묶음 실패 즉시 올림(`run_all_raises_a_later_group_failure_without_waiting_for_earlier_groups`, 앞 묶음이 문에서 멈추고 끝까지 돌지 않음을 확인), 문 위임(`abortable_…`), 중간가 집계·보고서 행(`midpointTradeTicksAreCountedPerSymbolFromTheJournal`, 손 계산 기대값과 비교), L5 판정 불가 + L2 탈락 겹침(`l5_short_sample_does_not_hide_another_fail`). 지우거나 끈 시험 없음(diff에 삭제된 `@Test` 없음) |
| B7 | 해당 없음 | 실전 엔진·Guard·블랙리스트·주문 경로·보호 경로 변경 없음(`protected-paths.txt` 대조). 실험실 순수 모듈의 판정·점검 도구만 바뀜 |
| B8 | 통과 | 비밀값·계좌번호 없음. 새 예외·보고서 문구는 실험 ID와 종목 코드만 담음 |

## 점검표 밖 발견
- N1(막지 않음): 묶음 실패 뒤 다른 묶음은 다음 확인 지점에서 멈춘다. 스트림 실행(`SimulationExperimentRunner.streamLanes`)의 확인 지점은 Run 시작과 **Run 안의 거래일 바뀜**(`dayClosed` 콜백, SimulationExperimentRunner.java:627-629)이라, 한 묶음의 Run이 모두 이미 실패로 끝나 있으면 그 묶음은 남은 기록을 확인 없이 끝까지 읽는다(Run이 없으니 짧다). 또 최악의 대기는 "한 거래일 분량 처리"다. 주석(LiveJudgement.java:52-53)과 맞고 결과에는 영향이 없다. PR #180 N2는 해결로 본다.
- N2(막지 않음): 주간 점검 중간가 표는 `onBoards && inPlan` 체결 틱 전부를 센다(LiveWeeklyCheck.java:345). 장전·장후 시간외와 단일가 체결 틱도 분모에 들어간다. 가상 거래소가 반 칸을 빼는 대상은 접속 매매 지정가와 단일가 체결이므로, 해석할 때 "정규장 접속 매매 비중"과 다를 수 있다. 참고 값이고 문구가 "boards 체결 틱"이라 틀리지는 않다. 필요하면 다음에 매매 단계별로 나누거나 문구에 적기를 권한다(strategy-researcher 판단).
- N3(막지 않음): `MidpointTrades.isMidpoint`는 `LiveSetup`의 `KrxMarketRules(…, s -> InstrumentKind.STOCK)`로 판정한다. 가상 거래소와 같은 규칙이라 일관되지만, D-118 26행대로 ETF 5원 단위 체결가가 일부 구간에서 반 칸으로 잡힐 수 있다. 판정 대상 boards가 주식만이라는 기존 전제(호가 가격 검사)에 기대는 것이라 이 PR의 새 위험은 아니다.
- 확인: `build.gradle.kts` 주석 "21m−1"은 코드 `nullSeeds = 21 * m - 1`(LiveJudgement.java:421-423)과 일치.
- 확인: `runAll`의 `InterruptedException` 경로가 이제 `onFailure`를 불러 남은 묶음을 멈추게 한다(전에는 풀 닫기에서 끝까지 기다림). `ExecutorService.close()`는 다시 건 중단 플래그에서 `shutdownNow` 뒤 계속 기다리므로 묶음이 문에서 멈춘 뒤 돌아온다.
- 확인: 처음 받은 실패는 언제나 진짜 실패(또는 사람의 정지·취소)다. `failed`는 첫 실패를 받은 뒤에만 서므로 "다른 판정 묶음이 실패해 멈춤"이 명령까지 올라가 원인을 가리는 경우는 없다.

## 판정
승인
