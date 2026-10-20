# architect 검토: 녹화 판정 묶음 실패 즉시 멈춤·주간 점검 5장 접속 매매 분모·합계 행·BOOK-V6 이전 날짜, 실시간 체결 최근 것부터 시험(PR #185·#188 후속)
- 대상: PR #190(번호는 메인 전달 기준) / 브랜치 `claude/project-thread-yxpj4r` 커밋 4a54490 / `git diff origin/main...HEAD` 전체(7개 파일, +282 −29)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → BUILD SUCCESSFUL(대부분 up-to-date, 2s)
  - `./gradlew build --rerun-tasks` → BUILD SUCCESSFUL in 2m 47s, 138 actionable tasks: 138 executed, FAILED 0
  - 테스트 결과 XML 확인: `LiveJudgementTest` 17건, `LiveWeeklyCheckTest` 13건, `LiveSessionControllerTest` 7건 모두 failures 0 / errors 0 / skipped 0. 새 시험 `abortable_stream_stops_at_the_next_event_once_failed_and_otherwise_passes_everything_through`, `abortable_wrappers_override_every_method_of_their_interface`, `midpointTicksOutsideContinuousTradingAreCountedApart`, `accountActivityIsNewestFirst` 실행 확인. 전체 모듈에서 skipped>0인 스위트 없음
  - 바뀐 경로를 `scripts/ci/protected-paths.txt`와 대조: 걸리는 경로 없음(autotrade-app-lab, autotrade-sim, docs/adr/ADR-010만)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 138개 작업 모두 실행, BUILD SUCCESSFUL. ArchUnit·순수 모듈 금지 의존 검사 포함 |
| B2 | 통과 | `autotrade-sim`에 새 import는 `com.autotrade.domain.SessionPhase`, `domain.market.Market`, `domain.market.MarketEvent`뿐(LiveJudgement.java:3-4, LiveWeeklyCheck.java:3). Spring·DB·SDK 의존 없음. 컨트롤러 변경은 app-lab 안(LiveSessionController.java:65-88). 프로세스 경계 변경 없음. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | REST API(`/runs/{runId}/fills`, `/rejections`)의 경로·파라미터·응답 모양은 그대로이고 본문을 package-private static 함수로 뺀 리팩터링뿐(LiveSessionController.java:63-88). 공개 record `LiveWeeklyCheck.MidpointCount`에 두 필드가 더해졌으나 저장소 안 다른 사용처 없음(grep, sim/live 밖 0건). 주간 점검 출력(5장 열 두 개·합계 행·BOOK-V6 이전 날짜 문구)은 ADR-010 미해결 표 행에 반영(ADR-010:110). 설정 키·스키마 변경 없음 |
| B4 | 통과 | 새 코드에 `now()`·난수 없음. `abortable(MarketEventStream, AtomicBoolean)`(LiveJudgement.java:424-456)은 실패가 없으면 같은 이벤트·같은 순서·같은 version/market/exchangeTimed/eventCount를 그대로 위임(시험 `abortable_stream_...`이 목록 동일성 확인). 실행기 분기 `data instanceof MarketDataSet`(SimulationExperimentRunner.java:178)·예열 검사(:565)에 대해 원래 `StreamedRecordingDataSet`도 MarketDataSet이 아니므로 감싸도 같은 경로를 탄다. `RecordingInput`은 판정 렌더(`render`, LiveJudgement.java:578)에 원래 `journal`을 넘기므로 영향 없음. 주간 점검의 `openingPrinted`는 HashSet 멤버십만 쓰고 순회하지 않으며, 표 순회는 TreeMap(LiveWeeklyCheck.java:338). 골든 테스트 포함 전체 테스트 통과 |
| B5 | 해당 없음 | 금액 계산 변경 없음. 틱·거래량은 long, 비중은 기존 `share`(HALF_EVEN) 재사용(LiveWeeklyCheck.java:806-812). 시험의 가격은 BigDecimal |
| B6 | 통과 | 새 동작마다 시험: 이벤트마다 실패 표시 확인(첫 날 10번째 이벤트에서 실패 표시 → 정확히 10개만 넘김), 감싼 쪽이 인터페이스 메서드를 모두 위임(리플렉션), 시가 단일가 체결·장후 시간외 체결이 분모에서 빠지고 따로 세짐(`MidpointCount(1, 7, 1, 7, 2, 1)`), 합계 행과 BOOK-V6 이전 날짜 문구, 체결·거절 최근 것부터(PR #188 남은 권고 해소). 기존 시험 `midpointTradeTicksAreCountedPerSymbolFromTheJournal`은 지우지 않고 기대값을 새 분모로 바꿨고, 기대값을 시험 안에서 이벤트로부터 다시 계산한다. 지우거나 끈 시험 없음 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동·보호 테스트 미변경. 보호 경로 목록에 걸리는 파일 없음. 바뀐 것은 실험실 판정·주간 점검(참고 값)과 실험실 REST 리팩터링 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 예외·보고 문구는 "다른 판정 묶음이 실패해 멈춤", 날짜·종목 코드뿐 |

## 점검표 밖 발견
- [사실] PR #185 architect N1 해소: 실패 표시를 기록 읽기 이벤트마다 보므로(LiveJudgement.java:447-452) Run이 모두 끝난 묶음이나 한 거래일 중간에 있는 묶음도 다음 이벤트에서 멈춘다. 먼저 실패한 묶음의 예외가 `runAll`에서 먼저 올라간 뒤에야 `failed`가 서므로(LiveJudgement.java:333, 368-371) 여기서 던진 정지 예외가 원인으로 올라가는 경로는 없다. 실행기는 `forEach` 안의 `ExperimentStoppedException`을 Run 실패로 바꾸지 않고 그대로 올린다(SimulationExperimentRunner.java:630-632).
- [사실] PR #185 architect N2 해소. 다만 N1(막지 않음): 주간 점검의 "그날 첫 접속 매매 체결 = 시가 단일가 체결" 판정(LiveWeeklyCheck.java:362-363)은 가상 거래소의 판정(VirtualExchange.java:243-263)보다 단순하다. 가상 거래소는 그날 첫 정규장 이벤트가 개장 뒤 `AUCTION_PRINT_GRACE`(60초) 넘어 오면(녹화가 장중 시작·재기동) 시가 단일가가 지난 것으로 보고, 단일가 구간 시세 공백 뒤에도 접속 매매로 본다. 주간 점검은 그 경우에도 종목마다 첫 접속 매매 틱 하나를 "시간외·단일가" 칸으로 보낸다. [추론] 영향은 그런 날 종목당 틱 1개이고 참고 값이라 판정에 영향이 없다. 두 판정이 갈리지 않게 하려면 다음에 같은 규칙(그날 첫 정규장 이벤트 시각 + 유예)을 쓰거나 문서에 이 단순화를 적기를 권한다.
- N2(막지 않음): `BOOK_V6_FIRST_DAY = 2026-10-20`(LiveWeeklyCheck.java:151)은 D-118 결정일(docs/decisions/README.md:124)과 맞지만 "사용자 PC에 들어간 날"이라는 배포 사실을 코드 상수로 고정한 것이다. 다른 PC나 늦게 받은 환경에서는 표시가 덜 나온다. [가정] 현재 운영 PC가 하나라 맞다. 표시 문구가 "증거가 아님" 경고뿐이라 틀려도 판정은 바뀌지 않는다.
- N3(참고): 장중 VI(변동성 완화장치) 단일가 체결은 `sessionPhase`가 구분하지 않으므로 접속 매매 분모에 들어간다. 가상 거래소도 같은 전제이므로 일관된다. 이 PR의 새 위험은 아니다.
- 시험 코드가 완전한 패키지 이름(`com.autotrade.sim.run.MarketEventStream` 등)을 곳곳에 쓴다. 가독성 문제일 뿐 차단 사유 아님.

## 판정
승인
