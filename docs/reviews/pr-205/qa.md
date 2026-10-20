# 독립 QA 검토: 선정 계좌에서 막힌 신규 진입 신호가 하루 진입 한도·대기 금액을 쓰지 않게(F7-a, D-130, ADR-013)
- 대상: PR #205 / 헤드 b40a8ea / main 대비 diff 전체(병합 기준 44c5670, 13개 파일: `RunStepper`, `StrategyContext`, `ManagedStrategy`, 시험 3개(`SelectionEntryQuotaTest` 새 파일, `CatalogStrategyTest`, `Tape`), ADR-013, D-130, 색인 2개, `docs/qa/pending.md`, 검토 원문 2개)
- 점검표: A 전체 + B1, B6
- 요청: 사용자 "PR #205 검토해줘"(2026-10-20)
- 직접 실행한 것:
  - PR 헤드 b40a8ea에서 `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL**(138 작업). Maven Central 429로 의존성 받기 실패 10회 뒤 11회째 통과(미러 init 스크립트 없이). 테스트 결과 XML 합계 1,814건, 실패 0, 오류 0, 건너뜀 0. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 68, `:autotrade-strategy` 47, `:autotrade-sim` 435.
  - 현재 main(fbd1a41, #203·#204·#206 포함)에 PR을 병합(작업 트리 따로, 커밋 안 함): 충돌은 `docs/adr/README.md`·`docs/decisions/README.md` 색인 줄 두 곳뿐(양쪽 줄 모두 남기는 식으로 풀어 시험). 병합 상태 `./gradlew build --offline` → BUILD SUCCESSFUL, 1,826건 실패 0. main #203이 `SelectionDay.allowsEntry(Symbol)`(순번 없는 판정)를 지웠지만 PR은 순번 있는 판정만 써서 그대로 컴파일된다.
  - PR CI(헤드 b40a8ea): Gradle build, Secret scan and collector boundary, DB schema, Lab screens 4개 모두 success.
  - 변이 12개(아래 "변이 확인"), 장중 순위 선정 시나리오 시험 1개(아래 S1, 저장소에 넣지 않음).

## 보호 테스트 변경 여부(먼저 확인)
`[사실]` `git diff 44c5670 b40a8ea --stat`에 `autotrade-architecture-test/*`, `autotrade-risk/*`(RealTradingGuard·GuardedOrderGateway 시험), 장애 시험, `scripts/ci/protected-paths.txt`, `.github/CODEOWNERS` 변경 없음. 보호 경로에 걸리는 변경은 `autotrade-strategy/*` 4개 파일(`StrategyContext.java`, `ManagedStrategy.java`, `CatalogStrategyTest.java`, `Tape.java`)뿐이라 실전 관련 PR이다. 고정 값(지문·허용 목록·소유 표) 갱신 없음.

## 실전 경로 영향 범위
- `[사실]` `StrategyContext`를 구현하는 운영 코드는 `RunStepper.Context`·`WarmupContext`(`autotrade-sim`)뿐이다(`grep -rln "implements StrategyContext\|new StrategyContext()"` 결과: 운영 `RunStepper.java`, 시험 `Tape.java`·`SelectionEntryQuotaTest.java`). `autotrade-app-engine`·`autotrade-risk`·`autotrade-control`에 `StrategyContext`·`admitsEntry` 사용 없음(grep 결과 0줄).
- `[사실]` `RunStepper`는 리플레이 실행기(`SimulationExperimentRunner`)와 실시간 모의(`LiveSession.java:137`) 둘 다 쓴다. 바뀐 동작은 모의 계좌에만 닿는다.
- `[사실]` `admitsEntry`는 기본 메서드라(`StrategyContext.java:29`, 기본 true) 구현하지 않은 문맥은 고치기 전과 같게 동작한다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·설정 로딩·`autotrade-risk`·`autotrade-config` 변경 없음(`git diff --stat`). 직접 실행한 빌드에서 `:autotrade-risk` 55건(Guard 기본 OFF·재기동 OFF 시험 포함), `:autotrade-app-engine` 68건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 변경 없음. `autotrade-architecture-test` 변경 없음, 46건 통과(`ArchitectureTest`, `ModuleOwnershipTest`, `RealPathReflectionTest`). 새 분기는 빈 목록만 돌려준다(`ManagedStrategy.java:250-254`). 주문을 만드는 경로가 늘지 않는다 |
| A3 | 해당 없음 | 증권사 선택·주문 라우팅 코드 변경 없음. 바뀐 코드는 가상 실행기의 문맥과 전략 신호 생성뿐 |
| A4 | 해당 없음 | 타임아웃·조회·재전송 경로 변경 없음. 가상 경로 `RunStepper.submit`도 그대로 |
| A5 | 통과 | 막힌 신호는 `submit` 전에 사라져 멱등 키를 쓰지 않는다(전략 안에서 걸러지거나 `RunStepper.java:146-151` 안전망). 키 생성기(`SimOrderKeys`) 변경 없음. `SelectionEntryQuotaTest#후보_밖_신호가_먼저_와도_…`에서 `rejections()` 비어 있음 |
| A6 | 통과 | `admitsEntry`는 블랙리스트를 보지 않고 거름을 더하기만 한다. 블랙리스트 거절은 그대로 `RiskChain`이 한다: `SelectionEntryQuotaTest#블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다` 통과(체결 0, 거절 모두 그 종목). 청산은 묻지 않는다: `CatalogStrategyTest#청산은_실행기에_진입_여부를_묻지_않는다` 통과. 변이 P12(청산 경로에서도 묻고 거부 시 청산 안 함)가 이 시험으로 잡힘. 묻지 않는 전략용 안전망 제거(P9)는 `SelectionCombinationRunnerTest` 3건이 잡음. 수동 보유·재기동 복구 경로 변경 없음 |
| A7 | 해당 없음 | 실전 주문 큐·토큰 버킷 변경 없음. `admits`는 그 Run의 `state`만 읽고 쓴다(`RunStepper.java:160-166`). 계좌 간 공유 상태 없음 |
| A8 | 통과 | 실전 엔진은 바뀐 코드를 쓰지 않는다(위 영향 범위). 엔진 금지 의존 검사가 포함된 `build` 통과 |
| A9 | 통과 | 보유 중이면 `onEvent`가 `exit`로 가고(`ManagedStrategy.java:144-147`) `admitsEntry`는 `entry` 안 250행 한 곳에서만 불린다. 실행기 안전망도 `isProtective()` 신호를 거르지 않는다(`RunStepper.java:146`). 보호 시세·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff에서 appkey·secret·token·계좌번호 패턴 없음. 시험의 `005930`·`000660`은 공개 종목 코드, 계좌 ID는 모의 ID. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·변경 없음. 기존 시험 변경은 `Tape`에 종목 인자 오버로드·`refuse`·`admitAsks`를 더한 것뿐이고 기존 메서드는 A로 위임해 뜻이 같다. 새 동작에 고치기 전 코드에서 실패하는 시험이 있다: 변이 12개 중 9개가 잡힘(P2·P3·P5·P6·P7·P8·P9·P11·P12). 남은 공백은 권고 Q1·Q2 |
| A12 | 통과 | 장애 대응표(broker-and-fault-design.md 6장)·해외 O1~O8·장애 테스트 8종이 전제하는 주문·시세·Guard·보호 경로를 건드리지 않는다. 문서 변경 없음 |
| A13 | 통과 | 시장 분기 추가 없음. 시간 판정은 기존 `ManagedStrategy`의 `market.zone()`과 `SelectionDay.allowsEntry(symbol, sequence)`(순수 함수)를 그대로 쓴다 |
| A14 | 통과 | D-130·ADR-013·색인 두 곳이 코드와 맞다: 묻는 위치(`armedAt.remove` 뒤·`funnel.signal` 앞, `ManagedStrategy.java:249-255`), 실행기 답과 셈(`RunStepper.java:160-166`), 안전망 유지(`RunStepper.java:146-151`). D-130 번호는 main(fbd1a41)에도 없음(main은 D-129·D-131까지). 선정 계좌 `blocked-entries` 뜻 변화는 ADR-013 5장에 적혀 있다. 단 색인 두 곳이 main과 충돌한다(R1) |
| B1 | 통과 | PR 헤드에서 직접 `./gradlew build --rerun-tasks` 성공, 1,814건 실패 0. main 병합 상태도 1,826건 실패 0 |
| B6 | 통과 | 새 동작에 시험 7개(`CatalogStrategyTest` 3, `SelectionEntryQuotaTest` 4). 건너뛰거나 지운 시험 없음(건너뜀 0). 장중 순위 선정 경로 시험 공백은 권고 Q1 |

## 변이 확인(PR 헤드, `:autotrade-strategy:test :autotrade-sim:test`, 하나씩 넣고 되돌림)
| # | 변이 | 결과 |
|---|---|---|
| P1 | `admits`가 이벤트 순번 대신 `Long.MAX_VALUE`로 판정(장중 순위 시각 무시) | **생존** (실패 0) |
| P2 | `admits`가 false일 때 막은 수를 세지 않음 | 잡힘(`CombinationExperimentTest` 1, `SelectionEntryQuotaTest` 1) |
| P3 | `admits`가 두 번 셈 | 잡힘(`SelectionEntryQuotaTest` 1) |
| P4 | 무효 선정일(`reason != null`)이면 `admits` true | 생존 `[추론]` 거의 같은 변이: 그날 신규 진입이 하나도 없으므로 안전망이 같은 수를 세고 체결도 같다. 전략 내부 한도만 다르게 쓰인다 |
| P5 | 묻기를 한도 확인 뒤로 | 잡힘(`CatalogStrategyTest` 1) |
| P6 | 묻기를 `entriesToday++`·대기 금액 잡은 뒤로 | 잡힘(3) |
| P7 | 문맥 거름 항상 true | 잡힘(2) |
| P8 | 묻기를 `armedAt.remove` 앞으로(막힌 종목 무장 유지) | 잡힘(`SelectionEntryQuotaTest` 1) |
| P9 | `RunStepper` 주문 전 안전망 제거 | 잡힘(`SelectionCombinationRunnerTest` 3) |
| P10 | 막혀도 깔때기 `funnel.signal` 셈 | **생존** |
| P11 | 막혀도 `entriesToday++` | 잡힘(3) |
| P12 | 보유 중 청산 앞에서도 묻고 false면 청산 안 함 | 잡힘(`CatalogStrategyTest#청산은_…` 1) |

## 시나리오(잘못된 주문·중복 주문·보호 공백 시도)
- **S1 장중 순위 선정(INTRADAY_AT) 순위 시각 전 신호** `[사실]` 리플레이는 그날 이벤트를 먼저 읽어 순위를 정하고(`SimulationExperimentRunner.selectionSchedule`, main fbd1a41) 그날 판정을 처음부터 `SelectionDay.intraday(…, activeFrom = 순위 이벤트 순번)`으로 준다. 그래서 순위 전 이벤트는 `allowsEntry(종목, 순번 < activeFrom)`가 false다. QA가 병합 상태 작업 트리에 시험을 따로 만들어 돌렸다(저장소에 넣지 않음): 선정 `kr-intraday-open-all@1`(at 09:30), `random` 하루 1건, boards 005930·000660, 09:00 000660 체결 → 09:10 005930 신호(순위 전) → 09:31 000660 순위·신호. 결과: **PR 코드는 000660 매수 1건, 막은 수 1(통과)**. 같은 시험에서 `RunStepper`·`ManagedStrategy`를 main 판으로 되돌리면 매수 0건(실패, 고치기 전 F7이 장중 선정에도 있었음). 변이 P1을 넣으면 역시 매수 0건(실패). 즉 코드는 맞지만 PR 시험은 이 경로를 고정하지 않는다(Q1).
- **S2 중복 셈·이중 거름** `admits`와 주문 전 안전망은 같은 `state.selectionDay`와 같은 순번으로 같은 순수 판정(`SelectionDay.allowsEntry`)을 쓴다. 한 이벤트 안에서 `state.selectionDay`가 바뀌지 않으므로(`RunStepper.java:126-144`에서 전략 호출 전에 정함) 전략이 받은 신호를 안전망이 다시 막는 일과 두 번 세는 일이 생기지 않는다. 문맥은 이벤트마다 새로 만들고 `ManagedStrategy`는 문맥을 보관하지 않는다. 다른 전략이 문맥을 보관해 나중에 부르면 그 이벤트 순번으로 셈이 늘 수 있다 `[추론]`(주문에는 영향 없음, architect N1과 같은 범위).
- **S3 블랙리스트 후보** 후보 종목이 블랙리스트면 `admits`는 true를 돌려주고 신호는 `RiskChain`에서 거절된다(`SelectionEntryQuotaTest` 블랙리스트 시험). 이때 전략은 하루 진입 수·대기 금액을 쓴다(고치기 전과 같음). 거름을 앞당긴 것은 선정뿐이라 블랙리스트 종목이 주문으로 나가는 길은 생기지 않는다.
- **S4 청산 보호** 보유 종목은 `admitsEntry`를 거치지 않고 청산된다(P12 잡힘). 선정 밖이 된 보유 종목의 손절이 막히는 공백은 없다.
- **S5 선정 없는 계좌** `admits`가 늘 true라 동작이 같다. `SelectionEntryQuotaTest#선정_없는_계좌의_보고서는_…_바이트_단위로_같다` 통과, 기존 골든·스냅샷 시험 모두 통과.

## 점검표 밖 발견
- **Q1(권고) 장중 순위 시각 전 경로에 시험이 없다.** 변이 P1(`admits`가 순번을 무시)이 PR 시험을 모두 통과한다. QA 시나리오 S1처럼 INTRADAY_AT 선정 + `ManagedStrategy`로 "순위 전 신호가 하루 한도를 쓰지 않고, 순위 뒤 후보가 들어간다"를 고정하는 시험을 더하길 권한다. LIVE02 후보 C4 (마) `kr-intraday-open-momentum`과 대조 선정 R3이 이 경로를 쓴다. 코드는 S1에서 맞게 동작함을 확인했으므로 차단 사유로 보지 않는다.
- **Q2(권고) 깔때기 미집계 주장에 시험이 없다.** ADR-013 5장·`ManagedStrategy.java:251-252` 주석은 막힌 신호가 `EntryFunnel`을 건드리지 않는다고 하지만 변이 P10이 생존한다. 해석용 지표라 주문 영향은 없다.
- **R1(머지 전 처리) main과 충돌(`mergeable_state: dirty`).** `docs/adr/README.md`(ADR-014 줄과 ADR-013 줄), `docs/decisions/README.md`(D-129·D-131 줄과 D-130 줄)가 충돌한다. 양쪽 줄을 다 남기면 되고, QA가 그렇게 푼 병합 상태에서 빌드·시험이 통과했다. 푼 뒤 코드 변경이 없어야 이 판정이 유지된다.
- **R2(사용자 확인) 전략 수준 일 손실 한도 확인 시점 변화.** `haltedToday` 래치(`ManagedStrategy.allowedByLimits`)는 신호가 한도 확인에 올 때만 평가된다. 이 PR 뒤 선정 계좌에서는 후보 밖 신호가 이 평가를 일으키지 않아, 장중 하락 뒤 회복하면 예전보다 늦게 멈추거나 멈추지 않을 수 있다(ADR-013 5장, risk-ops 발견 1과 같은 내용을 코드로 확인). 실전 `RiskChain` 한도와 손절 경로는 영향이 없고 모의 계좌의 전략 내부 한도만 해당한다. 리스크 한도 평가 방식의 변화이므로 머지할 때 알고 결정할 일이다.
- (정보) `BuyAndHold`는 묻지 않는다. 선정 × 매수 후 보유 조합에서는 고치기 전처럼 후보 밖 종목이 슬롯을 쓴다(ADR-013 5장). 안전망이 남아 주문 안전에는 영향 없다.

## 판정
승인

- 실패·확인 못 함 없음. 권고 Q1·Q2는 시험 보강, R1은 머지 전 색인 충돌 해결, R2는 사용자가 알고 머지할 사항이다.
- 실전 관련 PR(보호 경로 `autotrade-strategy`)이므로 머지는 사용자가 한다.
