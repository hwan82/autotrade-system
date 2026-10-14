# architect 검토: PR #106 체결 규칙 BOOK-V5 (D-087, 장전 시간외 첫 이벤트·종가 크로스·창 경계)
- 대상: PR #106 / 브랜치 `claude/project-thread-itopmh` / 커밋 `5fc73cb`(결정 번호 D-085→D-087 변경 포함, 그 앞 `4341e27`) / `git diff origin/main...HEAD` 전체(10개 파일: `ExecutionSettings.java`, `VirtualExchange.java`, `VirtualExchangeTest.java`, `RecordingExperimentTest.java`, `SimulationExperimentRunnerTest.java`, `krx-golden.report`, ADR-004, D-087, 결정 로그 README, 기획서)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (커밋 `4341e27`, `5fc73cb` 각각): 둘 다 종료 코드 0. `5fc73cb` 기준 테스트 결과 합계 1,107건, 실패 0, 오류 0, 건너뜀 0. `VirtualExchangeTest` 43건 실패 0.
  - 회귀 재현 확인: `VirtualExchange.java`만 `origin/main`(BOOK-V4) 판으로 바꿔 `:autotrade-sim:test --tests '*VirtualExchangeTest'` 실행 → 43건 중 2건 실패(`장전_시간외_첫_이벤트_뒤_장중에야_이벤트가_오면_시가_단일가가_지난_것으로_본다`, `종가_단일가_구간이_없는_시장은_폐장_직후_첫_체결을_평가_가격에만_쓴다`). 나머지 새 테스트 4건은 BOOK-V4에서도 통과(경계 고정용, D-087 결정 3 "코드 그대로"와 맞음). 확인 뒤 파일을 되돌림.
  - 탐침 테스트(커밋하지 않음): 같은 시나리오에서 장전 이벤트를 08:10 대신 **08:35 체결**로 바꿔 실행 → 11:00:01 호가에서 시장가 매수 100주 체결 0건(기대 2건)으로 실패. 아래 F1의 근거. 확인 뒤 테스트 파일을 되돌림(`git status` 깨끗함).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, `5fc73cb`에서 종료 코드 0, 테스트 1,107건 실패 0 |
| B2 | 통과 | 운영 코드 변경은 `autotrade-sim` `exchange` 패키지 두 파일뿐이고 새 의존 없음. 시장 분기 없음(`VirtualExchange.java:229-231`, `238`은 `SessionPhase`만 비교). `ENGINE_HAS_NO_MARKET_BRANCH`(D-084)·모듈 소유 ArchUnit 규칙이 빌드에서 통과. 테스트의 `Market.KRX`는 테스트 규칙 정의(`VirtualExchangeTest.java` `NO_AUCTION`)로 규칙 대상 아님 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음(`MarketRules` 계약 그대로, D-087 대안 C를 버림). 결과에 남는 체결 모델 ID가 `BOOK-V5`로 바뀌는 것은 ADR-004 2.2 머리말·13번과 `ExecutionSettings.java:22-29` 주석에 기록 |
| B4 | 통과 | 시스템 시계·난수 사용 없음(판정은 이벤트의 `exchangeTime()`과 `rules.sessionPhase`만 씀). 골든 `krx-golden.report`는 `execution-model` 머리줄만 바뀌고 거래·지표 줄은 그대로이며 골든 테스트 통과. `KrxMarketRules.sessionPhase`(`KrxMarketRules.java:81-93`)는 폐장 전 10분이 항상 `CLOSING_AUCTION`이라 장 밖 시각 t에서 `sessionPhase(t−60초)==CONTINUOUS`가 될 수 없음 → KRX 종가 판정은 BOOK-V4와 같음(코드로 확인) |
| B5 | 통과 | 금액 계산 변경 없음. 평가 가격은 기존대로 `tick.price()`(BigDecimal)를 저장(`VirtualExchange.java:200-202`) |
| B6 | 통과 | 새 동작마다 테스트 있음(`VirtualExchangeTest.java:363, 378, 390, 401, 414, 429`). 그중 2건은 BOOK-V4에서 실패함을 직접 확인. 기존 테스트 삭제·`@Disabled` 없음. 기존 테스트 2건(`RecordingExperimentTest`, `SimulationExperimentRunnerTest`)의 변경은 모델 ID 문자열 `BOOK-V4`→`BOOK-V5`뿐 |
| B7 | 해당 없음 | 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응·증권사 주문 연동 변경 없음. `VirtualExchange`는 `OrderGateway`의 가상 구현이며 바뀐 것은 시세 이벤트의 매매 단계 판정(`phase`)과 평가 가격 갱신 조건뿐. 리스크 검증·상태머신·블랙리스트 공유 코드는 건드리지 않음 |
| B8 | 통과 | diff 전체에서 키·계좌번호·토큰 패턴 검색 결과 없음(`git diff origin/main...HEAD`에 `appkey|secret|account no|token|password` 일치 없음). 로그·예외 메시지 추가 없음 |

## 점검표 밖 발견

**F1 (수정 필요, 중간) 결정 1이 실제 KRX 장전 시간외(08:30~08:40)를 다루지 못한다. 고치려던 문제(후속 ②)가 실데이터에서는 그대로 남는다.**
- [사실] `KrxMarketRules.sessionPhase`는 개장 30분 전부터 개장까지를 `OPENING_AUCTION`으로 돌려준다(`KrxMarketRules.java:81-93`, 정규장일 08:30~09:00). 저장소의 운영 문서는 KRX 장전 시간외를 정규장일 08:30~08:40으로 적고(`docs/runbooks/collector.md:67`, `[가정]` 표기), 녹화에는 장전·장후 시간외 체결이 장구분 없이 섞여 들어온다고 적었다(같은 줄, `KiwoomStreamProtocol.java:223-229` 인용).
- [사실] 그래서 실제 KRX 장전 시간외 체결은 `CLOSED`가 아니라 `OPENING_AUCTION` 이벤트이고, 바뀐 코드에서도 `seen`에 들어가 `firstToday`를 false로 만든다(`VirtualExchange.java:238`). 탐침 테스트(위 "직접 실행한 것")로 08:35 체결 → 11:00 호가 시나리오에서 BOOK-V4와 똑같이 11:00 호가를 시가 단일가 접수로 보고 시장가 매수를 체결하지 않는 것을 확인했다. 이어 11:00대 첫 체결 틱이 시가 단일가 체결이 되어, D-075가 막으려던 "최우선 매도 호가보다 싼 체결가에 슬리피지 0 체결"이 남는다.
- [추론] 반대로 결정 1이 실제로 바꾸는 경우(08:30 전 `CLOSED` 이벤트)는 지금 데이터에 거의 없다. 녹화 시세는 KRX 단독이라(`docs/research/kis-open-api-spec.md:133`, 키움은 9081이 KRX가 아니면 버림 `docs/research/kiwoom-rest-api-spec.md:122`) NXT 프리마켓(08:00~08:50) 체결이 들어오지 않고, 미국은 `opensWithAuction()`이 false라 이 판정을 쓰지 않는다. 테스트의 08:10 체결은 테스트 규칙에서만 성립하는 시나리오다.
- D-087의 배경 `[사실]` 줄("08:30 전 장전 시간외 체결")과 위험 절("남는 경우는 녹화기는 돌았는데 그 종목 시세가 정말 없었던 경우뿐 `[추론]`")은 이 점 때문에 틀리다. 참고로 이 전제는 PR #83 architect 검토의 후속 ② 메모가 먼저 잘못 적은 것이며(`docs/reviews/pr-83/architect.md` 24줄 "장전 시간외(08:30 전 CLOSED)"), 이번 검토에서 정정한다.
- 고칠 방법(추천 A):
  - A(추천): `phase`에서 "그날 첫 정규장 이벤트" 집계에 `OPENING_AUCTION` 구간의 `TradeTick`을 넣지 않는다. KRX 시가 단일가 접수 구간(08:30~09:00)에는 정규장 체결이 없으므로 그 구간의 체결 틱은 장전 시간외 체결로 볼 수 있다 `[추론]`(시가 단일가 체결은 개장 뒤 `CONTINUOUS` 시각으로 온다, D-069). 시장 분기 없이 `SessionPhase`만으로 표현되고 `MarketRules` 계약도 바뀌지 않는다. 테스트: 기존 `장전_시간외_첫_이벤트…` 테스트와 같은 모양에 08:35 체결을 쓰는 케이스, 그리고 08:35 체결 뒤 08:50 호가(접수 구간 비체결 이벤트)가 오면 지금처럼 첫 체결이 시가 단일가 체결인 케이스. 규칙 버전은 머지 전이므로 `BOOK-V5` 그대로 두고 D-087 결정 1·배경·위험 절과 ADR-004 2.2 13번 문구를 "장 밖 이벤트와 시가 단일가 접수 구간의 체결 이벤트를 세지 않음"으로 고친다.
  - B: 코드는 그대로 두고 D-087 배경·위험 절을 정정해 실제 KRX 장전 시간외(08:30~08:40)는 이 결정으로 해결되지 않음을 적고, 후속 과제로 남긴다. 고치려던 문제가 실데이터에서 그대로라 결정의 목적과 맞지 않아 추천하지 않는다.

**F2 (낮음, 범위 밖 후속, data-engineer)** `KrxMarketRules` 클래스 주석(`KrxMarketRules.java:27-33`)은 "장전·장후 시간외 매매와 시간외 단일가는 CLOSED"라고 적지만, 코드는 08:30~09:00을 `OPENING_AUCTION`으로 돌려주어 장전 시간외 종가(08:30~08:40)가 접수 구간과 겹친다. 이 PR의 diff 밖이므로 막지 않는다. F1을 A로 고치면 주석을 "장전 시간외 체결은 시가 단일가 접수 구간과 겹치며 가상 거래소는 그 구간의 체결 틱을 정규장 체결로 쓰지 않는다"로 고치는 것을 제안한다.

**F3 (확인, 막지 않음)** 종가 크로스 판정 확장(`VirtualExchange.java:229-231`)은 의도대로다. 종가 체결이어도 `matchAuction`은 `o.auction`이 표시된 주문만 체결하는데(`VirtualExchange.java:381-389`) 단일가 구간이 없는 규칙에서는 `joinAuction`이 불리지 않아 표시된 주문이 없다. 그래서 미국 기본 규칙에서 종가 크로스는 평가 가격만 바꾸고 어떤 주문도 체결하지 않는다(테스트 `종가_단일가_구간이_없는_시장은_폐장_직후_첫_체결을_평가_가격에만_쓴다`가 지정가 주문 ACCEPTED 유지까지 확인). ECN 시간외 체결이 종가 크로스보다 먼저 오면 그 값이 평가 가격이 되는 위험은 D-087이 `[가정]`과 되돌릴 조건(대안 C)으로 적었다. 미국 녹화 실험을 처음 열 때 확인 항목으로 둔다.

**F4 (절차, 막지 않음)** 이 브랜치의 결정 로그 표는 D-084 다음에 D-087이 오고 기획서는 v14.29·v14.30 없이 v14.31이다. PR #105 등 D-085·D-086·v14.29·v14.30을 쓰는 PR이 먼저 머지되어야 번호가 이어진다. 뒤에 머지되는 쪽이 충돌을 풀 때 번호와 버전 순서를 확인한다.

## 판정
수정 필요(F1: 장중 시작 판정이 실제 KRX 장전 시간외 체결(08:30~08:40, `OPENING_AUCTION` 구간)을 그날 첫 정규장 이벤트로 세어 후속 ② 문제가 남음. 추천안 A로 코드·테스트를 고치고 D-087 배경·위험 절과 ADR-004 2.2 13번 문구를 정정)
