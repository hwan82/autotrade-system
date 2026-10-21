# QA 검토: PR #205 QA Q1·Q2 시험 보강(장중 순위 시각 전 신호, 깔때기 미집계)과 QA 처리 기록
- 대상: PR #212 / 커밋 5252070f81c368349834e663c45e2e677954f467 / main(613ef94) 대비 diff (`git diff origin/main...pr-212`, 7파일 +130 −6)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - PR 헤드 별도 작업 트리에서 `./gradlew build --rerun-tasks --no-daemon` → **BUILD SUCCESSFUL in 3m 53s**(Maven Central 429로 4회 실패 뒤 5회째, 미러 init 없이). 시험 결과 XML 합계 1,853건, 실패 0·오류 0·건너뜀 0. 새 시험 2건 실행·통과 확인.
  - 현재 main(5326d94, #213 머지 뒤)을 PR 헤드에 병합한 상태(충돌 없음, `git merge-tree` 깨끗)에서 `./gradlew build` → BUILD SUCCESSFUL, 1,872건 실패 0·건너뜀 0.
  - 변이 11개(운영 코드를 하나씩 바꾸고 `:autotrade-strategy:test :autotrade-sim:test`, 매번 되돌림). 아래 표.
  - GitHub: PR `mergeable_state: clean`, 헤드 5252070 체크 4종(Gradle build, DB schema, Lab screens, Secret scan and collector boundary) 모두 success.

변경 범위 `[사실]`: `src/main` 변경 없음(`git diff --stat origin/main...pr-212 -- '*/src/main/*'` 빈 결과). 시험 2파일(`SelectionEntryQuotaTest` 새 시험 1개와 `runDetailed`를 `runner`·`data`·`spec` 도우미로 나눔, `CatalogStrategyTest` 새 시험 1개), 문서 5파일(D-130 후속 반영 절, `qa-out-status.md` 한 줄, `pending.md` 한 줄, architect·risk-ops 원문). `autotrade-strategy/*`가 `scripts/ci/protected-paths.txt`에 있어 경로상 실전 관련 PR이다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·설정·Guard 시험 변경 없음. 전체 빌드에서 `autotrade-risk`·`autotrade-app-engine` 시험 실패 0 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음. `autotrade-architecture-test` 통과 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성 변경 없음 |
| A6 | 통과 | 블랙리스트 경로 변경 없음. `SelectionEntryQuotaTest#블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다` 입력·단언 그대로, 통과. 새 시험은 `BlacklistSnapshot.EMPTY`로 블랙리스트를 다루지 않음 |
| A7 | 해당 없음 | 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 해당 없음 | 프로세스 구성 변경 없음 |
| A9 | 해당 없음 | 보호 시세·손절 변경 없음. `CatalogStrategyTest#청산은_실행기에_진입_여부를_묻지_않는다` 통과 |
| A10 | 통과 | diff에서 키·토큰·계좌번호 없음(검색 결과는 검토 원문의 단어뿐). 픽스처는 공개 종목 코드 005930·000660과 가상 금액. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·장애 시험) 변경 없음, 고정 값 갱신 없음. 기존 시험 변경은 도우미 분리뿐이고 생성 인자(데이터 이름 FIXTURE-F7, `TestKrxRules`, 비용, `ExecutionSettings.defaults()`, 시드 1, 기준 OUTSIDE, 선정 원천, `ExperimentSpec` 값)가 이전과 같다(SelectionEntryQuotaTest.java:175-194). 새 시험 2건은 PR #205 QA의 생존 변이 P1·P10을 각각 잡는다(아래 변이 표, QA가 직접 재현) |
| A12 | 해당 없음 | 동작 변경 없음. 장애 대응표·O1~O8·장애 시험 8종 전제 영향 없음 |
| A13 | 통과 | 시장 분기 추가 없음. 새 시험은 순위 입력 범위를 `BundledMarketRules.of(Market.KRX)`로 받는다(시험용 `TestKrxRules`는 늘 정규장이라 첫 이벤트를 늦은 시작으로 봄, 주석 79행) |
| A14 | 통과 | D-130 "후속 반영" 절(29-32행)의 주장 "P1에서 Q1 시험 실패, P10에서 Q2 시험 실패"를 QA가 재현해 사실로 확인. `qa-out-status.md` 마지막 줄의 PR #205 머지 커밋 613ef94는 main에 있음. 동작 불변이라 새 결정 필요 없음 |
| B1 | 통과 | PR 헤드에서 직접 `./gradlew build --rerun-tasks` 성공 1,853건 실패 0. main 병합 상태 1,872건 실패 0 |
| B6 | 통과 | 이 PR의 "새 동작"은 시험 자체이며, 각 시험이 겨냥한 변이에서 실패한다(P1, P10b). 지우거나 건너뛴 시험 없음(건너뜀 0) |

## 변이 확인(PR 헤드, `:autotrade-strategy:test :autotrade-sim:test`, 하나씩 넣고 되돌림)
| # | 변이 | 결과 |
|---|---|---|
| P1 | `RunStepper.admits`가 `allowsEntry(symbol, Long.MAX_VALUE)`(순번 무시, PR #205 QA P1) | 잡힘(새 시험 `SelectionEntryQuotaTest#장중_순위_시각_전_…` 1건만) |
| P10b | `ManagedStrategy`의 `funnel.signal(today)`를 `admitsEntry` 확인 앞으로 옮김(PR #205 QA P10) | 잡힘(새 시험 `CatalogStrategyTest#실행기가_받지_않는_…_깔때기에_세지_않는다` 1건만) |
| P10 | `funnel.signal`을 `admitsEntry` 앞에 하나 더(옮기지 않고 덧붙임) | 잡힘(새 시험 + `DailyCloseLogicsTest` 1) |
| N1 | `SelectionDay.allowsEntry`의 `sequence >= activeFrom`을 `>`로 | 잡힘(`IntradayTrackerTest` 1, `SelectionScheduleTest` 1) |
| N2 | `allowsEntry`가 `activeFrom` 무시 | 잡힘(새 시험 포함 2) |
| N3 | 막힌 신호에서 `funnel.entry` 셈 | 잡힘(새 시험) |
| N4 | `admits`가 막아도 막은 수를 세지 않음 | 잡힘(새 시험 포함 3) |
| N5 | `admits`가 순위 전 대기일(`selectionDay.awaiting()`)이면 true | **생존** (실시간 모의 경로, Q1) |
| N6 | `admits`에 `seq - 1`을 넘김(순위 이벤트 자신의 신호를 막음) | **생존** (Q2) |
| N7 | `admits`에 순번 0을 넘김 | 잡힘(새 시험, `LiveIntradaySessionTest`, `LiveIntradayJudgementTest`) |
| N8 | 받은 신호를 깔때기에 두 번 셈 | 잡힘(새 시험 + `DailyCloseLogicsTest`) |

## 시나리오(잘못된 주문·중복 주문·보호 공백 시도)
- **S1 주문 경로 변화** `[사실]` 운영 코드 변경이 없으므로 이 PR이 새 주문·중복 주문·거름 우회를 만들 길이 없다. 병합 상태 전체 빌드 통과로 main의 #213 변경과 겹쳐도 시험이 깨지지 않음을 확인했다.
- **S2 실시간 모의의 순위 전 신호(대기일)** `[사실]` 실시간 모의는 순위 전 `SelectionDay.awaitingIntraday`(사유 NOT_YET)를 쓰고(`RunStepper.java:131-135`), `allowsEntry`는 `reason == null`이 아니면 false라(`SelectionDay.java:76-77`) 지금 코드는 순위 전 신호를 막는다. 그러나 변이 N5(대기일이면 받음)가 모든 시험을 통과한다. 이 변이가 들어가면 순위 전 신호가 전략의 하루 진입 한도를 쓰고 주문 전 안전망이 버려, 실시간 모의에서 F7이 되살아난다. 주문 안전망(`RunStepper.java:146-151`)은 남으므로 후보 밖 주문은 나가지 않는다 `[추론]`. 새 시험은 리플레이 경로만 고정한다(risk-ops N1과 같은 공백을 변이로 확인).
- **S3 순위 이벤트 경계** 변이 N6(전략에 넘기는 순번을 1 줄임)이 생존한다. 순위를 매긴 바로 그 이벤트에서 나온 후보 신호가 막히는 변이로, 결과는 진입 누락(잘못된 주문 아님)이다. `SelectionDay` 쪽 경계(N1)는 기존 시험이 잡지만, `RunStepper`가 전략 문맥에 같은 순번을 넘기는지는 고정되어 있지 않다. 새 시험은 순위 이벤트(09:31:00 호가)와 신호(09:31:01 체결)가 달라 이 경계를 밟지 않는다.
- **S4 시험의 진입 창 의존** 새 장중 시험의 막은 수 1은 09:00 000660 체결이 `entry.window.start` 09:05 앞이라 신호가 되지 않는다는 프로필 기본값에 기댄다(architect 원문 발견과 같음). 창 기본값이 바뀌면 시험이 바로 실패하므로 조용한 공백은 아니다.

## 점검표 밖 발견
- **Q1(권고) 실시간 모의 순위 전 경로의 한도 시험이 없다.** 변이 N5가 생존한다. `LiveIntradaySessionTest`에 순위 시각 전 후보 신호를 넣고 그날 막은 수와 순위 뒤 진입을 단언하는 시험을 권한다. 코드는 S2대로 맞게 동작하므로 차단 사유로 보지 않는다.
- **Q2(권고) 순위 이벤트 자신의 신호 경계 시험이 없다.** 변이 N6이 생존한다. 순위 이벤트가 곧 후보의 진입 신호인 구성을 더하면 고정된다. 진입 누락만 생기는 방향이라 우선순위는 낮다.
- (정보) `SelectionEntryQuotaTest.java:20` 가져오기 순서(architect 원문과 같음). 동작 영향 없음.
- (정보) PR 본문의 "P10" 설명("막혀도 `funnel.signal`")은 옮김(P10b)과 덧붙임(P10) 둘로 읽힐 수 있다. QA는 둘 다 넣어 새 시험이 잡음을 확인했다.

## 판정
승인
