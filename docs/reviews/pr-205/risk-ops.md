# risk-ops 검토: 선정 계좌에서 막힌 신규 진입 신호가 하루 진입 한도·대기 금액을 쓰지 않게(F7-a, D-130, ADR-013)
- 대상: PR #205 / 커밋 50d6227 / main(44c5670) 대비 diff 전체(10개 파일: `RunStepper`, `StrategyContext`, `ManagedStrategy`, 시험 3개, ADR-013, D-130, 색인 2개)
- 점검표: A
- 직접 실행한 것(별도 작업 트리 `/tmp/risk-wt`, 커밋 50d6227):
  - `./gradlew build --no-daemon` → 종료 코드 0. 테스트 결과 XML 합계 1814건, 실패 0, 오류 0, 건너뜀 0. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 68, `:autotrade-strategy` 47, `:autotrade-sim` 435(모두 실패 0).
  - 새 시험: `SelectionEntryQuotaTest` 4건, `CatalogStrategyTest` 20건 통과.
  - 변이 확인: `ManagedStrategy.entry`의 `if (!ctx.admitsEntry(symbol))`를 `if (false && …)`로 바꿔 다시 돌림 → `CatalogStrategyTest`의 `실행기가_받지_않는_신규_진입은_신호를_내지_않고_하루_진입_수를_쓰지_않는다`, `실행기가_받지_않는_신규_진입은_대기_진입_금액도_잡지_않는다`, `SelectionEntryQuotaTest`의 `후보_밖_신호가_먼저_와도_…`, `블랙리스트에_오른_후보_종목은_…` 4건 실패. 새 시험이 고친 동작을 실제로 잡는다. 확인 뒤 원복(`git checkout`).

## 실전 경로 영향 범위 확인
- `[사실]` `StrategyContext`·`admitsEntry`를 쓰는 운영 코드는 `autotrade-strategy`(`Strategy`, `StrategyContext`, `ManagedStrategy`, `BuyAndHold`)와 `autotrade-sim`(`RunStepper`)뿐이다(`grep -rln "StrategyContext\|admitsEntry" --include=*.java`).
- `[사실]` `autotrade-app-engine/build.gradle.kts:21`이 `:autotrade-strategy`에 의존하지만 엔진·`autotrade-risk`·`autotrade-control`·`autotrade-broker-*` 소스에 `com.autotrade.strategy` import가 없다. 게이트웨이·감시는 `autotrade-strategy`를 금지 의존으로 둔다(`autotrade-app-gateway/build.gradle.kts:12`, `autotrade-app-watchdog/build.gradle.kts:14`). 이 PR은 지금 실전 프로세스 동작을 바꾸지 않는다. 다만 `autotrade-strategy`는 보호 경로(`scripts/ci/protected-paths.txt`)라 실전 관련 PR로 다룬다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | diff에 RealTradingGuard·설정 로딩·`autotrade-risk` 변경 없음(`git diff --stat origin/main...HEAD`). `:autotrade-risk:test` 55건, `:autotrade-app-engine:test` 68건 통과(Guard 기본 OFF·재기동 OFF 시험 포함) |
| A2 | 통과 | 주문 출구 코드 변경 없음. `autotrade-architecture-test` 변경 없음, 46건 통과(`ArchitectureTest`, `ModuleOwnershipTest`, `RealPathReflectionTest`). 새 기본 메서드는 신호를 줄이기만 하고 주문을 만드는 경로를 더하지 않는다(`ManagedStrategy.java:250-254`는 빈 목록 반환뿐) |
| A3 | 해당 없음 | 증권사 선택·주문 라우팅 코드 미변경. 바뀐 코드는 가상 실행기와 전략 신호 생성뿐 |
| A4 | 해당 없음 | 타임아웃·조회·재발주 경로 미변경. 가상 경로의 `RunStepper.submit`(멱등 키·`risk.evaluate`, `RunStepper.java:257`)도 그대로 |
| A5 | 통과 | 막힌 신호는 `submit` 전에 사라져 멱등 키를 쓰지 않는다(이전 안전망과 같음, `RunStepper.java:146-150`). 키 형식·생성기(`SimOrderKeys`) 미변경. `SelectionEntryQuotaTest#후보_밖_신호가_먼저_와도_…`에서 `rejections()` 비어 있음 |
| A6 | 통과 | 블랙리스트 거절은 그대로 `RiskChain`이 한다. `admitsEntry`는 블랙리스트를 보지 않고 더 막기만 한다(선정 밖이면 false). 블랙리스트에 오른 후보는 `admits`가 true여도 위험 검사에서 거절됨: `SelectionEntryQuotaTest#블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다`(체결 0, 거절 모두 해당 종목). 청산은 묻지 않음: `CatalogStrategyTest#청산은_실행기에_진입_여부를_묻지_않는다`(STOP_LOSS 신호, `admitAsks()` 0). 묻지 않는 전략의 주문 전 거름은 남아 있음: `RunStepper.java:146`, 기존 `SelectionCombinationRunnerTest#같은_전략이라도_선정이_다르면_…`(FixtureStrategies, `blockedEntries` 1) 통과 |
| A7 | 해당 없음 | 실전 주문 큐·토큰 버킷 미변경. 모의 계좌별 `RunStepper` 상태(`state.blockedToday`)만 쓰고 계좌 간 공유 상태 없음(`admits`는 그 Run의 `state`만 읽고 씀, `RunStepper.java:160-166`) |
| A8 | 통과 | 실전 엔진은 바뀐 코드를 쓰지 않는다(위 영향 범위). 엔진 금지 의존 `:autotrade-sim`(`autotrade-app-engine/build.gradle.kts:14`) 유지, `build`의 금지 의존 검사 통과 |
| A9 | 통과 | 보유 종목 청산 경로(`ManagedStrategy.exit`, 손절 우선)는 `admitsEntry`를 거치지 않는다(`ManagedStrategy.java:179-224`, `admitsEntry` 호출은 `entry`(226행~) 안 250행 한 곳뿐). `isProtective()` 신호(EXIT·STOP_LOSS)는 실행기 거름에서도 제외(`RunStepper.java:146`). 보호 시세·서버 측 손절 코드 미변경 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음. 시험의 `005930`·`000660`은 공개 종목 코드, 계좌 ID는 `EXP-F7-A01` 형식 모의 ID |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·GuardedOrderGateway 시험, 장애 시험) 삭제·변경 없음. 고정 값 갱신 없음. 기존 시험 변경은 `Tape`에 종목 인자·`refuse`·`admitAsks`를 더한 것뿐(기존 메서드는 A로 위임, 기존 시험 의미 그대로). 새 동작에 실패 경우 포함 시험 있음(위 변이 확인 4건 실패) |
| A12 | 통과 | 장애 대응표(broker-and-fault-design.md 6장, 현재 17행)·해외 O1~O8·장애 테스트 8종이 전제하는 주문·시세·Guard·보호 경로를 건드리지 않는다. 대응표·문서 변경 없음 |
| A13 | 통과 | 시장 분기 추가 없음. `admitsEntry`는 종목·이벤트 순번만 받고, 시간 판정은 기존 `ManagedStrategy`의 `rules`/`market`·`SelectionDay.allowsEntry(symbol, sequence)`(`SelectionDay.java:78-80`, 순수 함수)를 그대로 쓴다 |
| A14 | 통과 | D-130(`docs/decisions/D-130-strategy-entry-admission.md`)·ADR-013·색인 두 곳이 코드와 일치: 묻는 위치(`armedAt.remove` 뒤·한도 확인 앞, `ManagedStrategy.java:249-254`), 실행기 답과 셈(`RunStepper.java:160-166`), 안전망 유지(`RunStepper.java:146`), 선정 없는 계좌 바이트 동일(`SelectionEntryQuotaTest#선정_없는_계좌의_보고서는_admitsEntry_도입_전_문맥과_바이트_단위로_같다` 통과). D-130 번호는 main에 없음(D-129·D-130 파일 모두 main에 없음, 44c5670 기준). 기획서에는 이 수준의 세부가 없어 바꿀 곳 없음 |

## 점검표 밖 발견
1. **(비차단, 기록) 전략 수준 일 손실 한도의 확인 시점이 신호 도착에 묶여 있다.** `ManagedStrategy.allowedByLimits`의 `haltedToday` 래치(`ManagedStrategy.java:284-309`, 래치 설정 305행)는 진입 신호가 한도 확인에 올 때만 평가된다. 이 PR 뒤로 선정 계좌에서는 후보 밖 신호가 이 평가를 더 이상 일으키지 않아, 장중 하락 뒤 회복하면 예전보다 늦게 멈추거나 멈추지 않을 수 있다(ADR-013 5장이 인정). `[추론]` 진입 순간의 평가금액으로 판단한다는 뜻에서는 오히려 맞는 동작이고, 실전 `RiskChain` 한도와 손절 경로는 영향받지 않으므로 차단 사유로 보지 않는다. 다만 이것은 "리스크 한도 평가 방식"의 변화이므로 사용자 머지 때 알고 결정해야 한다. 후속 제안: 일 손실 래치를 신호와 무관하게 이벤트마다 평가하도록(sim-engine·strategy-researcher, 별도 PR, LIVE02 결과 해석 전).
2. **(비차단, 후속 조건) `admitsEntry` 기본값 true는 열린 쪽 기본값이다.** 지금은 "앞당길 뿐 대신하지 않는다"는 계약과 주문 전 거름(`RunStepper.java:146`)·`RiskChain`이 함께 있어 안전하다. 실전 엔진이 전략을 직접 돌리게 되면 실전 문맥이 이 메서드를 유일한 거름으로 쓰지 않도록, 그때의 PR에 "주문 전 블랙리스트·선정 거름이 `admitsEntry`와 별도로 남아 있음"을 확인하는 시험을 요구한다(ADR-013 3장 되돌릴 조건과 같은 방향).
3. **(비차단, 정보) `blocked-entries` 값 뜻 변경.** 이제 한도 확인 앞에서 세므로 값이 커질 수 있다(ADR-013 5장). 판정 지표가 아니고 실전 영향 없음. LIVE02 등록 문서가 이 값을 쓰면 새 뜻을 적어야 한다.
4. (정보) `BuyAndHold`는 묻지 않아 선정 조합에서 이전과 같다(ADR-013 5장). 안전망이 남아 있어 주문 안전에는 영향 없음.

## 판정
승인

- 이 PR은 보호 경로(`autotrade-strategy`)를 바꾸는 실전 관련 PR이므로 지침 10·11장에 따라 독립 QA 후 **사용자가 직접 확인하고 머지**한다. 점검표 밖 발견 1(일 손실 한도 평가 시점 변화)을 사용자에게 함께 알린다.
