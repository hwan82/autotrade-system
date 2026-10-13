# architect 검토: 녹화 어댑터와 시세 공백 처리 BOOK-V2: 녹화 형식 골든 일치 (D-068, 3단계)
- 대상: PR #71 / 커밋 4eddd1cc57dd00bfb02c981cd106b0564ab621e5 / `git diff origin/main...HEAD` 전체(20개 파일, +742 −20)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline` → 실패(빌드 로직 플러그인 의존 `kotlin-gradle-plugin-model:2.0.21` 캐시 없음). `./gradlew build`(온라인) 두 번 → 실패(`repo.maven.apache.org` 429 Too Many Requests). **컴파일·테스트 이전 단계에서 멈춰 저장소 코드와 무관한 실패지만, 검토자가 직접 돌린 Gradle 전체 빌드 결과는 없다.**
  - 대체 실행: javac(`--release 21`)로 domain·strategy·selection·marketdata·risk·sim main 컴파일 + sim 테스트 컴파일, JUnit Platform Console 1.11.3로 autotrade-sim 테스트 전체 실행 → 125개 발견, 125개 성공, 0 실패, 0 건너뜀. 소스의 `@Test` 개수 합계 125와 같음(건너뛴 테스트 없음). 새 테스트 클래스 `RecordedMarketDataSetTest`, 바뀐 `VirtualExchangeTest`·`SimulationExperimentRunnerTest` 모두 성공. 이 방식은 ArchUnit(`autotrade-architecture-test`), 순수 모듈 금지 의존 검사, app-lab 등 다른 모듈 컴파일·테스트를 돌리지 않는다.
  - 참고(판정 근거로 쓰지 않음): GitHub check-runs API로 같은 SHA의 CI "Gradle build"(`./gradlew build --no-daemon`, `.github/workflows` 27~28행) 단계 "Build and test" success, "Secret scan and collector boundary" success, "DB schema" success를 확인했다. 잡 로그 본문은 403으로 못 읽었다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 검토자 직접 `./gradlew build`가 Maven Central 429로 빌드 로직 해석 단계에서 실패. 대체로 sim 테스트 125/125 통과(javac + JUnit 콘솔). CI의 같은 SHA Gradle build success는 API로만 확인(로그 미열람). 규칙상 직접 실행 결과가 없어 통과로 적지 않는다 |
| B2 | 통과 | 새 패키지 `com.autotrade.sim.recording`은 `ModuleOwnershipTest` 소유 표 `com.autotrade.sim → autotrade-sim`(ModuleOwnershipTest.java:110) 안. sim → marketdata 의존은 이미 있던 `implementation(project(":autotrade-marketdata"))`(autotrade-sim/build.gradle.kts), 빌드 파일·settings 변경 없음. 새 코드는 Spring·JDBC·broker 패키지를 참조하지 않음(ArchitectureTest.java:91~108 규칙 대상). ArchUnit 자체는 직접 실행하지 못함(B1) |
| B3 | 실패 | 체결 규칙이 BOOK-V1 → BOOK-V2로 바뀌었는데 계약 문서 ADR-004 2.2가 그대로다: 제목 "가상 체결 규칙 `BOOK-V1`"(ADR-004:30), 규칙 목록 1~10에 시세 공백 규칙 없음, 10번 예시 `BOOK-V1;latency=PT0.5S`(ADR-004:43). 2.4·2.5에 실행기 입력 `RecordedMarketDataSet`(여러 날 sequence = 데이터셋 위치, `origin()`)과 정규 보고서 `data-gap` 줄도 없다. 바뀐 것은 4장 후속 표 두 행뿐이다. D-068은 결정 근거로 충분하지만, ADR-004가 "단일 계약 문서"로 남으려면 본문이 현재 규칙과 맞아야 한다 |
| B4 | 통과 | 새 코드에 시스템 시계·난수 없음(`RecordedMarketDataSet` 전체, `VirtualExchange.invalidate` VirtualExchange.java:233~240). 공백 종목 순서는 `TreeSet<Symbol>`(녹화기 `DayWriter` 68·184행과 같음), `invalidate`는 값만 null로 바꿔 순회 순서가 결과에 영향 없음. 기존 골든 `krx-golden.report`는 `execution-model` 줄 한 줄만 바뀜(diff 확인), `SimulationExperimentRunnerTest.녹화_형식으로_다시_돌려도_같은_골든과_일치한다`(86행)가 녹화 형식 입력으로 같은 골든과 바이트 일치. 데이터셋 버전에 파일 SHA-256이 들어가 같은 파일 → 같은 버전(`RecordedMarketDataSetTest.같은_녹화면_같은_버전이고_날짜_범위가_바뀌면_버전도_바뀐다`) |
| B5 | 해당 없음 | 새 금액 계산 없음. 기존 `referenceBuyPrice`·`estimate`(BigDecimal·Money) 경로를 그대로 쓴다 |
| B6 | 통과 | 새 동작마다 테스트: 어댑터 5개(`RecordedMarketDataSetTest` 47·67·79·98·121행, 이전 형식 녹화 경계에 끼운 공백이 현재 녹화기 결과와 같음을 실제 녹화기로 대조), 가상 거래소 3개(`VirtualExchangeTest` 272·282·299행), 실행기 2개(`SimulationExperimentRunnerTest` 86·96행, 손 계산 seq 6 → seq 11, 40주). 지운·끈 테스트 없음, 기존 테스트 변경은 기대 ID `BOOK-V2` 한 줄(`손_계산과_맞는다`). 보강 권고는 아래 R3 |
| B7 | 해당 없음 | 바뀐 운영 코드는 autotrade-sim(가상 거래소·실행기·보고서·어댑터)뿐. RealTradingGuard, GuardedOrderGateway, 실전 OrderGateway, 블랙리스트·리스크 체인(`RiskChain`), 상태머신, 장애 대응, broker 모듈, 보호 테스트(ArchitectureTest, Guard 테스트, 장애 테스트) 변경 없음(diff --stat). 가상·실전 공유 코드(안전 불변식 7) 영향 없음 |
| B8 | 통과 | diff에 키·토큰·계좌번호 패턴 없음(grep). 테스트 데이터는 `FIXTURE-WS`, `KRX_TEST` 같은 자리 이름. CI 비밀값 스캔 success(참고) |

## 점검표 밖 발견

설계 판단(동의): 공백에서 호가 스냅샷과 앞선 잔량만 버리고 주문은 남기는 선택(D-068 대안 E·F 비교)은 실제 거래소 동작과 맞고 보수적이다. `books.remove`가 시장가 체결 경로를 바꾸지 않는 것도 확인했다: 체결 틱 경로의 "호가 있는 종목" 판정은 `books`가 아니라 `booksSeen`(VirtualExchange.java:396)이라, 공백 뒤에도 그 종목 시장가는 다음 스냅샷으로만 체결된다(문서 설명과 일치). 체결 규칙 버전 상향(G 대안 버림)도 D-025 약속과 맞다.

- **R1(권고, 낮음) 공백 표시 없는 경계가 같은 firstSeq로 둘 이상이면 어댑터가 열지 못한다** `[추론]`. 이전 녹화기에서 줄 없이 끝난 구간(빠른 연속 재기동)이 있으면 `RecordedDay.unmarkedRestarts()`(marketdata RecordedDay.java:212~231)가 같은 `firstSeq` 구간을 둘 돌려줄 수 있는데, 어댑터는 줄 하나에 경계 하나만 소비해(`if`, RecordedMarketDataSet.java:144) 두 번째 경계가 남고 167행 `IllegalStateException`으로 끝난다. 이 경우 녹화기 규칙은 마지막 구간 끝을 공백 시작으로 쓰는데 어댑터 `lastAlive`(245행)는 경계 바로 앞 구간을 본다. 지금 있는 이전 녹화(2026-10-12)는 경계 하나라 해당 없음. 같은 줄의 경계를 `while`로 모두 소비하고 마지막 경계 앞 구간 끝을 쓰도록 하거나, 지원하지 않는 형태라고 문서와 메시지에 적을 것.
- **R2(권고) 입력 품질 정보가 보고서에 안 남는다.** `DayInfo.complete=false`(녹화 중이거나 비정상 종료 후 재기동 없음)·`truncated=true`인 날은 그날 꼬리 시세가 공백 표시 없이 빠질 수 있는데, 어댑터는 열고 `ReportWriter`는 `data-gap`만 쓴다. 실녹화 실험 명령에 연결할 때(D-068 후속) 이 두 값과 `insertedGaps`를 실험 결과에 남기거나 `complete=false` 날은 기본 거부할 것. 녹화 중 판정이 "읽기 전후 해시 비교"(122행)뿐이라, 읽는 사이에 쓰기가 없으면 진행 중 녹화도 열린다는 점도 같은 자리에서 다룰 것.
- **R3(권고) 테스트 보강.** `FixtureRecordings`가 `Clock.fixed(첫 이벤트 수신 시각)`(FixtureRecordings.java:32)로 녹화기를 닫아 구간 끝 시각이 항상 마지막 수신보다 앞서므로, `lastAlive`의 "구간 끝이 더 늦은" 분기와 비정상 종료(endedAt 빈 값) 분기를 이전 형식 대조 테스트가 지나지 않는다. 닫는 시각을 마지막 이벤트 뒤로 주는 경우를 하나 더하면 녹화기 규칙 일치 주장이 두 분기 모두에서 확인된다.
- **R4(문서 정밀도)** VirtualExchange 주석(60~64행)과 D-068 결정 2는 "마지막 체결가(평가)는 그대로"라고 하지만, `markPrice`의 최우선 매수 호가 대체(VirtualExchange.java:218~225)는 `books.remove`로 사라져, 체결 틱이 한 번도 없던 보유 종목은 공백 뒤 장 마감 평가가 원가(`VirtualAccount.equity`, 144~146행)로 바뀐다. 동작 자체는 보수적이라 문제 삼지 않지만 문서에 한 줄 적을 것.
- **R5(관찰)** 전략은 `DataGap`을 무시하고 자기 상태(`SymbolState`의 마지막 호가 등)를 유지한다. 공백 뒤 첫 호가 전 신호는 오래된 호가 기준일 수 있다. 체결은 가상 거래소가 보수적으로 막으므로 이 PR 범위 밖이며, 전략 쪽 공백 인지는 strategy-researcher 후속 후보.
- **R6(관찰)** 로드맵 3단계 완료 기준 "녹화 데이터로 골든 테스트 통과"를 이 PR은 "손으로 만든 골든을 실제 녹화기로 녹화 형식에 써서 같은 골든"으로 충족한다고 본다. 실녹화 기반 골든이 아니라는 점(D-068 "검증하지 못한 것")은 독립 QA 단계 판정에서 해석할 몫이다. `docs/qa/pending.md`에 3단계 판정 요청을 이 PR에 넣은 것은 지침 11장과 맞다.
- 코드 중복(사소): `withSequence`가 `RecordedMarketDataSet`(테스트에서도 사용), `FixtureEvents`, marketdata `EventSequences`(패키지 전용)에 세 벌. marketdata 쪽을 공개하려면 data-engineer 소유라 변경 제안 사항이며 이번 수정 범위로 요구하지 않는다.

## 판정
수정 필요(B3: ADR-004 2.2를 BOOK-V2 기준으로 갱신 — 제목·10번 예시 ID, 시세 공백 규칙 항목 추가, 2.4/2.5에 녹화 입력 `RecordedMarketDataSet`의 sequence·버전 규칙과 보고서 `data-gap` 줄 반영. B1: 검토자가 `./gradlew build`를 직접 돌리지 못해 확인 못 함 — 재검토 때 직접 실행해 확인한다)

## 재검토 2026-10-13
- 대상: PR #71 / 커밋 da2509dbb6d2b0694b9e8c26dfb4efb736ef2d9e / `git diff origin/main...HEAD` 전체(커밋 2개: 4eddd1c + 검토 반영 da2509d)
- 점검표: B
- 기록 위치: docs/reviews/README.md "재검토는 같은 파일 아래에 `## 재검토 <날짜>`로 덧붙인다"를 따라 이 파일 끝에 덧붙였다. 위의 1차 원문은 고치지 않았다. 커밋 da2509d에 들어간 1차 원문이 검토자가 쓴 내용과 같은지 `git diff HEAD -- docs/reviews/pr-71/architect.md`(0줄)로 확인했다.
- 직접 실행한 것:
  - `./gradlew build`: 처음에는 Maven Central 429로 의존성 해석이 실패했다. `--continue`로 되풀이해 의존성 캐시를 채운 뒤(11번째 시도 BUILD SUCCESSFUL), 캐시 결과에 기대지 않으려고 **`./gradlew build --rerun-tasks` → BUILD SUCCESSFUL in 1m 44s, 130 actionable tasks: 130 executed**를 돌렸다. 작업 트리는 HEAD da2509d와 같다(`git status` 깨끗함).
  - 테스트 결과 XML 집계(실패/오류/건너뜀): autotrade-sim 126개 0/0/0(소스 `@Test` 126개와 같음), autotrade-architecture-test 31개 0/0/0, autotrade-app-lab 131개 0/0/0, autotrade-marketdata 97개 0/0/0. `verifyPureDependencies`(domain·risk·sim 등)와 프로세스 `verifyProcessIsolation`(collector·engine·experiment·gateway·watchdog)이 실행됐다.
  - 별도로 javac + JUnit 콘솔로 sim 테스트를 다시 돌렸다: 126/126 성공.

### 검토 반영 확인
- **B3(1차 실패) → 해결.** ADR-004 2.2 제목이 `BOOK-V2`로 바뀌었고, BOOK-V1 대비 차이 문단, 10번 예시 `BOOK-V2;latency=PT0.5S`, 11번 시세 공백 규칙이 들어갔다. 2.4에는 `MarketDataSet` 구현 목록, 녹화 입력 규칙(sequence = 데이터셋 위치, `origin()`, 끼워 넣은 공백, 버전 형식, 녹화 중 거부), `SimulationReport.dataGaps()`와 `data-gap` 줄이 있다. 코드 동작과 대조했고 일치한다(VirtualExchange.java:88·135·226·237·284, RecordedMarketDataSet.java).
- **R1 → 해결.** 같은 firstSeq 경계를 `while`로 모두 소비한다. 공백 시작은 마지막 경계 바로 앞 구간 끝으로 잡는다(RecordedMarketDataSet.java 145~156행 부근). 녹화기 `DayWriter`는 재기동마다 `closeOpenSegments`로 빈 끝을 마지막 수신으로 채우고, `lastAlive`는 마지막 구간 끝을 본다(DayWriter.java 106·138~151행). 비정상 종료로 구간 끝이 비어 있어도 어댑터는 마지막 수신 시각으로 떨어지므로 결과가 같다. 테스트 `RecordedMarketDataSetTest.줄_없이_끝난_구간이_낀_이전_녹화는_공백을_한_번만_넣고_그_구간_끝부터_잡는다`가 이를 확인한다: 경계 [14, 14], 공백 2줄, 시작 = 빈 구간 끝(직전 수신 + 10초), 전체 16건.
- **R3 → 부분 해결(충분).** 위 테스트가 `lastAlive`의 "구간 끝이 직전 줄보다 늦은" 분기를 지난다. 비정상 종료(endedAt 빈 값) 분기는 별도 테스트가 없다. 다만 그 분기는 직전 줄 수신 시각을 그대로 쓰는 기본 경로이고, 기존 `공백_표시_없는_이전_녹화는_…`이 같은 결과를 내므로 머지 조건으로 요구하지 않는다.
- **R4 → 해결(동작 변경).** `books.remove`를 없애고 `staleBooks`로 표시만 한다. 공백 뒤에는 시장가 매수 예약 기준(`referenceBuyPrice`, 284행)에서만 스냅샷을 빼고, 평가(`markPrice`, 226행)는 공백 전 최우선 매수 호가를 계속 쓴다. 새 스냅샷이 오면 `staleBooks`에서 지운다(135행). 체결 경로는 들어오는 스냅샷과 `booksSeen`만 보므로 이 변경의 영향을 받지 않는다(matchBook, VirtualExchange.java:396 부근). `VirtualExchangeTest.시세_공백_뒤에는_…`에 평가 70000 확인이 추가됐다. 골든 2종은 그대로 일치한다. ADR-004 11번과 D-068 결정 2가 이 동작으로 고쳐졌다.
- **R2·R5 → 후속으로 기록.** D-068 "위험과 한계"에 입력 품질(`complete`·`truncated`)이 보고서에 없다는 점, 녹화 중 판정의 한계, 전략의 공백 미인지가 후속 과제로 적혔다. 실험 명령에 녹화 입력을 붙이는 작업에서 처리하면 된다.
- R6(3단계 완료 기준 해석)은 독립 QA 판정 몫으로 남는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자 직접 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL(130/130 작업 실행), 테스트 XML 실패·오류·건너뜀 0 |
| B2 | 통과 | `autotrade-architecture-test` 31개 통과(ArchitectureTest, ModuleOwnershipTest 포함), `autotrade-sim:verifyPureDependencies` 통과. 새 패키지 `com.autotrade.sim.recording`은 sim 소유(ModuleOwnershipTest.java:110). 빌드 파일 변경 없음 |
| B3 | 통과 | ADR-004 2.2(제목, 10번, 11번)·2.4(녹화 입력, dataGaps, data-gap 줄) 갱신, D-068·결정 로그 README가 동작과 일치 |
| B4 | 통과 | 새 코드에 시계·난수 없음. `staleBooks`(HashSet)는 포함 여부만 보고 순회하지 않음. 공백 순서는 TreeSet. `krx-golden.report`는 execution-model 줄만 바뀜. 녹화 형식 골든 바이트 일치 테스트(`SimulationExperimentRunnerTest.녹화_형식으로_다시_돌려도_같은_골든과_일치한다`) 통과 |
| B5 | 해당 없음 | 새 금액 계산 없음(기존 BigDecimal·Money 경로 그대로) |
| B6 | 통과 | 새 테스트 11개(어댑터 6, 가상 거래소 3, 실행기 2), 기존 테스트 변경은 기대 ID `BOOK-V2` 한 줄. 건너뛰거나 지운 테스트 없음(sim XML skipped 0) |
| B7 | 해당 없음 | 바뀐 운영 코드는 autotrade-sim뿐. Guard, 실전 OrderGateway, RiskChain, 블랙리스트, 상태머신, 장애 대응, broker, 보호 테스트는 변경 없음(`git diff origin/main...HEAD --stat`) |
| B8 | 통과 | diff에 키·토큰·계좌번호 없음(grep). 테스트 소스 이름은 자리표시자 |

### 점검표 밖(재검토)
- 남은 권고: R3의 비정상 종료 분기 테스트, R2(입력 품질 보고)는 실험 명령에 녹화 입력을 붙일 때 같이. 머지 조건 아님.
- 참고: 검토 원문 파일이 검토 반영 커밋(da2509d)에 함께 들어갔다. 내용은 바뀌지 않았다(위 확인). 다음부터는 검토자가 쓴 원문 커밋을 반영 커밋과 나누면 이력에서 구분하기 쉽다.

### 판정
승인
