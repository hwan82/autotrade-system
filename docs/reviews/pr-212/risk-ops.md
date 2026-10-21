# risk-ops 검토: PR #205 QA Q1·Q2 시험 보강(장중 순위 시각 전 신호, 깔때기 미집계)과 QA 처리 기록
- 대상: PR #212 / 커밋 5a6cae4 / main(613ef94) 대비 diff (`git diff origin/main...HEAD`, 브랜치가 main 최신을 포함함을 `merge-base --is-ancestor`로 확인)
- 점검표: A
- 직접 실행한 것:
  - 별도 작업 트리(`/tmp/risk212`, 검토 후 삭제)에서 `./gradlew build` → **BUILD SUCCESSFUL in 4m 12s**. 결과 XML 집계: `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 46건, `:autotrade-app-engine:test` 68건, `:autotrade-sim:test` 457건, `:autotrade-strategy:test` 48건, 모두 실패 0·건너뜀 0. 새 시험 2건(`SelectionEntryQuotaTest#장중_순위_시각_전_신호는_하루_진입_한도를_쓰지_않고_순위_뒤_후보가_들어간다`, `CatalogStrategyTest#실행기가_받지_않는_신규_진입은_진입_깔때기에_세지_않는다`) 실행·통과 확인.
  - 변이 시험(운영 코드를 임시로 바꾸고 해당 시험만 실행, 끝난 뒤 원복하고 `git status` 깨끗함 확인):
    - V1 `RunStepper.admits`를 `allowsEntry(symbol, Long.MAX_VALUE)`로(이벤트 순번 무시, QA P1과 같은 변이) → `SelectionEntryQuotaTest` 5건 중 **새 시험 1건만 실패**(매수 `[]`, 기대 `[KRX:000660]`). 기존 시험은 이 변이를 못 잡고 새 시험이 잡는다.
    - V2 `strategy.onEvent`에 넘기는 거름을 `symbol -> true`로(D-130 이전 동작) → 3건 실패(새 시험 포함). 
    - V3 `ManagedStrategy`의 `funnel.signal(today)`를 `admitsEntry` 확인 앞으로(QA P10과 같은 변이) → `:autotrade-strategy:test` 48건 중 **새 시험 1건만 실패**(신호·진입 `[2, 1]`, 기대 `[1, 1]`).
    - 참고: `admits`가 `awaiting()`인 날을 통과시키는 변이는 이 시험으로 잡히지 않는다. 리플레이는 Run 전에 이벤트를 먼저 읽어 순위를 정하므로(`IntradayTracker` 클래스 주석) 그날 판정이 처음부터 `activeFrom` 있는 결과라 리플레이에서 `awaiting` 분기가 쓰이지 않기 때문이다. 실시간 모의 경로의 시험 범위 문제이며 이 PR 범위 밖(점검표 밖 발견 N1).

변경 범위 `[사실]`: 시험 2개 파일(`autotrade-sim/.../SelectionEntryQuotaTest.java` +45/-6, 기존 내용은 `runner`·`data`·`spec` 도우미로 나눈 것뿐이고 기존 시험의 입력·단언은 그대로; `autotrade-strategy/.../CatalogStrategyTest.java` +12), 문서 2개(`docs/decisions/D-130-strategy-entry-admission.md` 후속 반영 절, `docs/qa-out-status.md` 한 줄). 운영 코드(`src/main`) 변경 없음. `autotrade-strategy/*`가 `scripts/ci/protected-paths.txt`에 있어 경로상 실전 관련 PR이다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 RealTradingGuard·설정·Guard 시험 변경 없음(변경 파일 4개가 위 범위뿐). 전체 빌드에서 `autotrade-risk`·`autotrade-app-engine` 시험 실패 0 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 46건 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 코드 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성 변경 없음. 막힌 신규 진입이 주문(멱등 키)을 만들지 않는 운영 코드(`RunStepper.java:146-150`)는 이 PR에서 그대로 |
| A6 | 통과 | 블랙리스트 경로 변경 없음. `SelectionEntryQuotaTest.java:106` `블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다` 단언 그대로이고 통과. 새 시험은 `RiskChain.builder(BlacklistSnapshot.EMPTY)`라 블랙리스트를 약화하지 않음. 거름(`admitsEntry`)은 진입을 더 막기만 하고 RiskChain 앞을 우회하지 않음(`RunStepper.java:144-152`) |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성·정지 순서 변경 없음 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 변경 없음. 청산은 거름을 묻지 않는 기존 시험(`CatalogStrategyTest#청산은_실행기에_진입_여부를_묻지_않는다`) 통과 |
| A10 | 통과 | diff를 key·token·secret·계좌번호로 검색해 해당 없음. 시험 픽스처는 공개 종목코드(005930, 000660)와 가상 금액뿐 |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·장애 시험) 변경 없음, 고정 값 갱신 없음. 기존 시험 삭제·약화 없음(건너뜀 0). 새 시험 2건은 QA가 생존으로 보고한 변이 P1·P10(`docs/reviews/pr-205/qa.md:41,50`)을 직접 넣어 각각 실패함을 확인(V1, V3) |
| A12 | 해당 없음 | 동작 변경 없음(시험·문서만). 장애 대응표 17종·O1~O8·장애 시험 8종의 전제에 영향 없음 |
| A13 | 해당 없음 | 미국 경로 변경 없음. 새 시험의 장중 순위 입력은 `BundledMarketRules.of(Market.KRX)`로 시장 규칙을 통해 받음 |
| A14 | 통과 | `D-130-strategy-entry-admission.md` 29행 "후속 반영" 절이 시험 2건과 변이 P1·P10 실패 확인을 적었고, 위 V1·V3 결과와 일치. 새 결정 필요 없음(동작 불변). `docs/qa-out-status.md` 마지막 줄이 `docs/reviews/pr-205/qa.md` 첫 판정 처리 기록이고 머지 커밋 613ef94는 main에 있음 |

## 점검표 밖 발견
- N1(참고, 이 PR 차단 아님) `[추론]` 리플레이는 순위를 미리 정해 `sequence >= activeFrom`만으로 막고, 실시간 모의는 순위 전 `SelectionDay.awaitingIntraday`(사유 NOT_YET)로 막는다(`RunStepper.java:131-135`, `SelectionDay.java:64-65,76-77`). 이번 시험은 리플레이 쪽만 고정한다. `allowsEntry`는 사유가 있으면 false라 지금 코드는 실시간 모의에서도 막지만, 실시간 모의 경로에서 "순위 전 신호가 한도를 쓰지 않음"을 고정하는 시험은 확인하지 못했다. 실험실 경로이고 실전 주문과 무관하므로 후속 시험 보강 후보로만 남긴다.
- N2(사소) 새 시험 주석의 "삼성전자(OUTSIDE 005930)·하이닉스(PICKED 000660)" 이름이 상수명과 다르게 읽힐 수 있으나 이벤트 구성(09:00 000660 체결, 09:10 005930 신호, 09:31 000660 신호)과 단언은 주석 설명과 맞다.

## 판정
승인
