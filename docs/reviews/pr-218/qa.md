# QA 검토: 선정 계좌 받지 않은 확인 신호도 일 손실 한도 확인(D-135)·주문 전 거름·순위 전 대기일 시험
- 대상: PR #218 / 커밋 3288ed399e660b5f0322946b1da9a069e22b5c75 / main(c4d6c9c 기준) 대비 diff(`git diff origin/main...pr-218`, 11파일 +308 −12)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - PR 헤드에서 `./gradlew build --rerun-tasks` → **BUILD SUCCESSFUL in 3m 4s**(Maven Central 429로 6회 실패 뒤 7회째, 미러 init 없이). 시험 결과 XML 합계 1,881건, 실패 0·오류 0·건너뜀 0. 새 시험 4건(`CatalogStrategyTest` 2, `SelectionEntryQuotaTest` 2) 실행·통과.
  - 현재 main(3e25539, #216)을 PR 헤드에 병합한 상태(충돌 없음)에서 `./gradlew build --offline` → BUILD SUCCESSFUL, 1,882건 실패 0·건너뜀 0.
  - 변이 10개(운영 코드를 하나씩 바꾸고 `CatalogStrategyTest`·`SelectionEntryQuotaTest` 실행, 매번 `git checkout`으로 되돌리고 `git status` 깨끗함 확인). 아래 표.
  - QA 시나리오 시험 2개(임시 파일 `QaPr218ScenarioTest`, 실행 뒤 지움, 저장소에 넣지 않음). 아래 "점검표 밖 발견".
  - GitHub: 헤드 3288ed3 체크 4종(Gradle build, DB schema, Lab screens, Secret scan and collector boundary) 모두 success. PR 댓글 6307770592(작성자 hwan82, OWNER, 2026-10-21T04:05:39Z) "CI 결과를 B1 근거로 인정, D-135 결정 확인"을 직접 읽음.

변경 범위 `[사실]`: 운영 코드는 `ManagedStrategy.java`(`entry`의 `admitsEntry` false 분기에 `if (!haltedToday) dailyLossReached(account);` 추가, `allowedByLimits`의 일 손실 계산을 `dailyLossReached`로 뽑음)와 `RunSelectionDay.java`(javadoc만)뿐. `autotrade-strategy/*`가 `scripts/ci/protected-paths.txt`에 있어 실전 관련 PR이다. 보호 테스트 경로(`autotrade-architecture-test`, `autotrade-risk`, `autotrade-app-engine`, `scripts`, `.github`) 변경 0(`git diff --stat` 빈 결과).

`dailyLossReached` 추출의 동등성 `[사실]`: 예전 코드는 `equity(account)`를 null 확인 앞에서 계산했고 지금은 뒤에서 한다. `equity`(ManagedStrategy.java:342-350)는 상태를 바꾸지 않고 하위 클래스 재정의가 없다(grep). 계산식(DECIMAL64, `<= -dailyLossLimit`)과 위치(`allowedByLimits` 마지막)는 같다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정 로딩·Guard 시험 변경 없음. 전체 빌드에서 `autotrade-risk` 시험 실패 0 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `autotrade-architecture-test` 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성 변경 없음. 새 분기는 빈 신호 목록을 돌려줘 주문·키를 만들지 않음(ManagedStrategy.java:250-258) |
| A6 | 통과 | 블랙리스트 경로(`RiskChain`) 변경 없음, `SelectionEntryQuotaTest#블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다` 통과. 새 분기는 신호를 늘리지 않는다. 주문 전 거름(RunStepper.java:146-151)을 없애면(변이 P6) 새 시험 `전략이_진입_여부를_묻지_않아도_주문_전_거름이_후보_밖_매수를_막는다`가 실패함을 직접 확인 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | `autotrade-app-engine/src/main`, `autotrade-app-watchdog/src/main`, `autotrade-risk/src/main`에서 `com.autotrade.strategy` 참조 0건(grep). 바뀐 코드는 실험실 실행기에서만 동작 |
| A9 | 통과 | 청산 `exit()`는 `haltedToday`를 읽지 않음(`haltedToday` 사용처 :128, :171, :254, :289, :318). QA 시나리오 S2: 받지 않은 신호로 그날 멈춘 뒤에도 보유 종목 손절(STOP_LOSS)이 나감 |
| A10 | 통과 | diff 추가 줄에서 appkey·secret·token·password·계좌번호 형태 검색 결과는 검토 원문의 단어뿐. 시험 데이터는 가상 금액·종목 코드. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 변경·고정 값 갱신 없음. 두 시험 파일 diff는 추가 줄만(지운 단언 없음), 건너뜀 0. 새 동작 시험은 고치기 전 동작(P1)·전략 내부 차단 생략(P10)·날 바뀜 초기화 누락(P3)에서 실패 |
| A12 | 통과 | 장애 대응표 17종·O1~O8 감지·대응 코드 무변경. 일 손실 한도는 모의 전략 프로필 파라미터로 실전 `RiskChain` 한도와 별개. 장애 테스트 8종 전제 영향 없음 |
| A13 | 해당 없음 | 시장 분기 추가 없음. 손실은 계좌 평가금액 비율, 날짜는 기존 `market.zone()` 기준(ManagedStrategy.java:118-130) |
| A14 | 통과 | D-135 결정 1~3이 코드와 일치. "위험과 한계" 첫 항목(받지 않은 신호는 진입 수·재진입·보유 수 확인 없이 일 손실을 봐서 D-130 전보다 일찍 멈출 수 있음)을 QA 시나리오 S1로 재현해 사실로 확인. 사용자 결정은 PR 댓글 6307770592(hwan82)로 확인. ADR-013 링크·결정 로그 행·`RunSelectionDay` javadoc 일치. 단 `docs/qa/pending.md` 줄 문구가 낡음(R1) |
| B1 | 통과 | PR 헤드에서 직접 `./gradlew build --rerun-tasks` 성공 1,881건 실패 0. main 병합 상태 1,882건 실패 0 |
| B6 | 통과 | 새 동작(받지 않은 신호의 일 손실 확인)에 실패 경우 포함 시험 2건(손실 시 멈춤·다음 날 재진입, 손실 한도 안이면 막지 않음). 지우거나 건너뛴 시험 없음 |

## 변이 확인(PR 헤드, `CatalogStrategyTest`·`SelectionEntryQuotaTest`, 하나씩 넣고 되돌림)
| # | 변이 | 결과 |
|---|---|---|
| P1 | 받지 않은 분기의 `dailyLossReached` 호출 삭제(고치기 전 동작) | 잡힘(`실행기가_받지_않는_확인_신호에서도_일_손실_한도를_확인한다`) |
| P2 | 받지 않은 분기에서 `dailyLossReached` 대신 `allowedByLimits` 전체 호출(D-130 전 범위: 진입 수·재진입·보유 수 뒤에 일 손실) | **생존** |
| P3 | 날 바뀔 때 `haltedToday = false` 삭제 | 잡힘(새 시험 + 기존 `당일_손실_한도에_닿으면_…`) |
| P4 | 받지 않은 분기에서 `entriesToday++` | 잡힘(5건, 새 `SelectionEntryQuotaTest` 순위 시험 포함) |
| P5 | 받지 않은 분기에서 `funnel.signal(today)` | 잡힘(`실행기가_받지_않는_신규_진입은_진입_깔때기에_세지_않는다`) |
| P6 | `RunStepper` 주문 전 거름 제거(`if (false && …)`) | 잡힘(새 `전략이_진입_여부를_묻지_않아도_…`) |
| P7 | 전략에 `seq - 1` 넘김(PR #212 QA N6) | 잡힘(새 순위 시험) |
| P8 | 대기일 이벤트마다 다시 묻기 제거(`else if (false)`) | 잡힘(새 순위 시험) |
| P9 | `dailyLossReached`의 `<= 0`을 `< 0`(한도 경계) | **생존**(main부터: 기존 시험도 −2.1%로 경계를 안 봄) |
| P10 | `admitsEntry` false면 바로 돌아감(분기 전체 우회) | 잡힘(P1과 같은 시험) |

## 점검표 밖 발견
시나리오(임시 시험 `QaPr218ScenarioTest`, 프로필 `sizing.max-positions=1`, 일 손실 2%, 무작위 신호 1000‰):
- S1 보유 수 상한에 막힌 동안 손실 −2.1% → 후보 밖 B 신호 → 회복(현금 10,000,000, 보유 청산) → 후보 A 신호. B를 **받으면** 보유 수 확인에 먼저 막혀 일 손실을 보지 않아 A 진입 1건, B를 **받지 않으면** 일 손실을 봐서 그날 멈춰 A 진입 0건. 통과. D-135 "위험과 한계"의 범위 차이(선정 계좌가 더 엄격)가 실제로 생김을 확인. 잘못된 주문·중복 주문은 아니고 진입이 줄어드는 쪽이다 `[추론]`.
- S2 보유 A 100주(평균 10,000), 손실 −2.1%에서 후보 밖 B 신호로 멈춘 뒤 A 9,800 → STOP_LOSS 매도 신호 1건. 통과. 멈춤이 청산·손절을 막지 않는다.
- S3 선정 없는 계좌: `RunStepper.admits`가 `state.selectionDay == null`이면 늘 true(RunStepper.java:160-166)라 새 분기를 타지 않는다. 기존 `선정_없는_계좌의_보고서는_admitsEntry_도입_전_문맥과_바이트_단위로_같다`와 골든 시험이 전체 빌드에서 통과. LIVE00·LIVE01 결과 영향 없음 `[사실]`.

권고(머지를 막지 않음):
- Q1(P2 생존): D-135가 고른 범위(받지 않은 신호는 진입 수·재진입·보유 수 확인 없이 일 손실을 본다)를 고정하는 시험이 없다. `dailyLossReached`를 `allowedByLimits` 전체로 바꿔도(= D-130 전 범위) 모든 시험이 통과한다. 위 S1과 같은 "보유 수 상한에 막힌 동안 받지 않은 신호로 멈춤" 시험을 추가하길 권한다.
- Q2(P9 생존, main부터): 일 손실 한도 경계(정확히 −2.0%)에서 멈추는지 시험이 없다. `<=`를 `<`로 바꿔도 통과한다.
- R1(문서): `docs/qa/pending.md`의 PR #218 줄이 "D-135 확인 방식은 사용자 결정 대기"라고 남아 있어 D-135 상태(사용자 결정, 안 1)와 다르다(architect 재검토 3, risk-ops R-N1과 같은 지적). 머지 전에 맞추거나 다음 PR에서 고친다.
- 참고: 7단계에서 실전 엔진이 전략을 돌리게 되면 실전 문맥에서도 주문 전 블랙리스트·선정 거름이 `admitsEntry`와 따로 남는지 시험해야 한다(D-135 위험과 한계 3, 이번 시험은 실험실 실행기 근거).

## 판정
승인
