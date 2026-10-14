# architect 검토: 체결 규칙 BOOK-V4 (정규장 체결로만 평가, 종가 단일가 체결 창, 장중 시작 녹화, 녹화 실험 호가 단위 검사, D-075)
- 대상: PR #83 / 커밋 63ea9de83b0b8a72f4b39b9a3ac7c1751315570d (a1b5c04 + 63ea9de) / `git diff origin/main...HEAD` 전체(origin/main 7cb71a9, 브랜치가 main을 포함함 확인)
- 점검표: B
- 직접 실행한 것:
  - a1b5c04에서 `./gradlew build` → BUILD SUCCESSFUL, EXIT=0. `./gradlew :autotrade-sim:test --rerun` → autotrade-sim 188개 통과, 실패 0, 건너뜀 0(VirtualExchangeTest 37, RecordingExperimentTest 4)
  - 63ea9de(결정 번호 변경 커밋)로 갱신 후 `./gradlew build` → BUILD SUCCESSFUL, EXIT=0(verifyPureDependencies 14개 모듈 실행). 캐시 영향을 없애려 `./gradlew test --rerun` → 전 모듈 1012개 통과, 실패 0, 오류 0, 건너뜀 0(ArchitectureTest, ModuleOwnershipTest 포함)
  - `git diff a1b5c04 63ea9de`: D-074 → D-075 번호·파일명 변경뿐임을 확인(코드는 주석의 결정 번호만 바뀜). origin/main에는 D-074가 없고 `origin/claude/project-thread-imcg2r`에 `D-074-ui-experiment-registration.md`가 있어 번호 충돌 회피는 사실

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 실행 결과. 63ea9de 기준 `./gradlew build` EXIT=0, `./gradlew test --rerun` 1012/0/0/0 |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-sim`(VirtualExchange, ExecutionSettings, RecordingExperiment)뿐. 새 import는 `com.autotrade.domain.*`(MarketRules, OrderBookSnapshot, PriceLevel, TradeTick)과 `java.time.Duration`으로 기존 의존 방향 안. `:autotrade-sim:verifyPureDependencies` 실행, ArchitectureTest 통과. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음(`MarketRules` 계약 그대로, `sessionPhase`·`opensWithAuction` 기존 메서드만 사용). 결과 비교 계약인 체결 모델 ID가 `BOOK-V4`로 바뀜(ExecutionSettings.java:26) → ADR-004 2.2에 13번 규칙과 버전 설명 추가, D-075·결정 로그 README·기획서 v14.23 변경 이력 갱신. 새 상수 `AUCTION_PRINT_GRACE`(VirtualExchange.java:96)는 package-private 코드 상수이고 설정 키가 아님 |
| B4 | 통과 | 시각은 이벤트의 `exchangeTime`과 캘린더(`rules.sessionPhase`, `rules.tradingDate`)로만 판정(VirtualExchange.java:220~245), `now()`·난수 없음. 새 `HashMap` 두 개(`seen`, `closePrinted`)는 종목 키 조회만 하고 순회하지 않아 순서 영향 없음. 골든 `krx-golden.report`는 머리 줄 `BOOK-V4`만 바뀌고 체결·손익·지표 줄은 그대로이며 골든 테스트 통과. 일봉 경로(`phases == false`)는 `phase()` 첫 분기에서 그대로 CONTINUOUS를 돌려 동작 불변 |
| B5 | 통과 | 가격 비교는 `BigDecimal.compareTo`(RecordingExperiment.java `requireTick`, `roundToTick(..., RoundingMode.DOWN)`), 평가 가격은 기존 `BigDecimal` 맵. double 없음 |
| B6 | 통과 | 새 테스트 7개: VirtualExchangeTest:253(QA R3 P3 재현, 16:10 시간외 체결로 종가 참여 주문 미체결·만료), :265(15:30:59 종가 체결은 체결), :277(08:35·15:25·15:40·16:10 체결은 평가 미반영, 15:30:01 종가 체결은 반영), :296(11:00 장중 시작 → 호가로 60@70,100 + 40@70,200 체결, 이후 70,000 틱으로 체결 없음), :310(09:00:59 첫 이벤트는 아직 시가 단일가 접수), :322(장중 시작은 그날만), RecordingExperimentTest `주식_호가_단위에_맞지_않는_가격이_있으면_시작하지_않는다`(180,050 거절). 기존 테스트는 VirtualExchangeTest 추가 줄만 있고 삭제·`@Disabled` 없음. SimulationExperimentRunnerTest·RecordingExperimentTest의 기존 수정은 기대 체결 모델 ID 문자열(`BOOK-V3` → `BOOK-V4`)뿐 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 연동·보호 테스트 변경 없음. 바뀐 파일은 `autotrade-sim` 가상 거래소·녹화 실험과 문서뿐(diff stat 11개 파일 + 번호 변경) |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음(추가 줄 패턴 검색 결과 없음). 새 예외 메시지는 종목 코드·가격·sequence만 담음 |

## 점검표 밖 발견
변경 의도(R1 장 밖 체결 평가, QA R3 종가 체결 창, R4 장중 시작, R2 ETF 호가 단위)를 코드로 직접 따라가 확인했다. `matchAuction`이 종가 참여 주문을 `closingPrint`일 때만 체결하고(VirtualExchange.java:382), 창 밖 장 밖 체결로는 표시만 지운 뒤 장 마감에 만료되는 흐름이 테스트와 맞는다. 아래는 막는 사항이 아니며 후속 메모로 남긴다.

1. (낮음, 후속) 기본 `MarketRules.sessionPhase`를 쓰는 시장(US)에는 `CLOSING_AUCTION` 구간이 없어 `closingPrint`가 항상 false다(VirtualExchange.java:226). 미국 종가 크로스 체결(16:00 직후 시각)은 평가 가격에 쓰이지 않고 직전 접속 매매 체결가가 평가가 된다. 지금 매매 단계 경로(`exchangeTimed` 녹화)는 KRX뿐이라 영향 없음 `[추론]`. 미국 녹화 실험을 열 때 US 규칙에 종가 단일가 구간을 둘지 ADR-004 13번과 함께 정한다.
2. (낮음) 그 종목 그날 첫 이벤트가 장전 시간외(08:30 전 CLOSED)이고 그 뒤 이벤트가 공백 표시 없이 개장 한참 뒤에야 오면 `firstToday`가 이미 false라(VirtualExchange.java:223, 239) BOOK-V3의 R4 동작(첫 체결을 시가 단일가로 봄)이 남는다. D-075 위험 절은 반대 경우만 적었다. 녹화기가 끊기면 공백 표시를 남기므로 드물다 `[추론]`. 필요하면 "개장 시각 이후 첫 이벤트" 기준으로 바꾸는 방안을 후속 과제로 둘 만하다.
3. (낮음, 테스트 보강 제안) 경계값 테스트가 없다: 정확히 09:01:00(t−60초 = 09:00:00 CONTINUOUS라 장중 시작으로 판정)과, 종가 창 안 두 번째 장 밖 체결(예: 15:30:01 다음 15:30:20)이 평가 가격을 바꾸지 않는 경우. 코드 읽기로는 각각 의도대로 동작한다.
4. (참고) 호가 단위 검사(RecordingExperiment.java:114, 176)는 실녹화 2026-10-12 파일이 컨테이너에 없어 실데이터에서 오탐이 없는지 확인 못 했다. 장전·시간외 체결가와 호가도 같은 호가 단위를 따르므로 오탐 가능성은 낮다 `[추론]`. 사용자 PC에서 `EXP-20261013-REC01` 재실행 때 함께 확인되며, D-075도 그 재실행을 확인 방법으로 적었다. 점검표 항목 판정에는 영향 없음.
5. (절차) 기획서 버전 v14.23은 다른 열린 브랜치(imcg2r, D-074)와 머지 순서에 따라 겹칠 수 있다. 뒤에 머지되는 쪽이 버전과 변경 이력을 올려야 한다.

## 판정
승인
