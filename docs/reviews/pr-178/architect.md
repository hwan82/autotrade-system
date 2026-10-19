# architect 검토: 주간 점검·리플레이 확인이 녹화를 날마다 줄 단위로 읽음(D-115, 6단계 T1)
- 대상: PR #178 / 브랜치 `claude/project-thread-ftm2h6` / 커밋 7334318 / `git diff origin/main...HEAD`(merge-base a66c476, origin/main fa046c3) 전체 18파일 +1569 −291
- 점검표: B
- 직접 실행한 것:
  - 스크래치 clone에서 `./gradlew -I <미러 init 스크립트> build` → **BUILD SUCCESSFUL (4m 30s), 종료 코드 0**. 저장소 밖 init 스크립트는 Maven 저장소 주소만 공개 Google 미러로 바꾼 것이다(빌드 내용·태스크는 같음).
  - 시험 결과 XML 집계: `autotrade-sim` 316건 실패 0·오류 0·건너뜀 0, `autotrade-architecture-test` 46건 실패 0·건너뜀 0. 이 PR과 관련된 클래스: `RecordedDayStreamTest` 5, `RecordedMarketDataSetTest` 9, `SimulationExperimentRunnerTest` 18, `LiveWeeklyCheckTest` 8, `LiveSessionTest` 10, 모두 통과.
  - `git merge-tree --write-tree origin/main HEAD` → 충돌 없음.
  - diff 추가 줄에서 `double`/`float`/`now()`/`currentTimeMillis`/`new Random`/`Math.random`/`parallelStream`, 비밀값 단어 검색 → 0건.
  - 보호 경로(`scripts/ci/protected-paths.txt`)와 바뀐 파일 대조 → 걸리는 파일 없음.

## 읽은 것(코드로 직접 확인)
- `RecordedDayStream` 대 `RecordedDay`(marketdata) 줄 단위 비교: 메타 읽기·형식 버전·정상 종료 SHA-256 선검사·gzip 머리 깨짐·`EndTolerantInputStream`·잘린 마지막 줄·순번 끊김 규칙이 같다. 다른 점은 "줄 하나를 늦게 넘김"과 "검사에 걸리기 전 줄을 이미 넘겼을 수 있음"뿐이고 클래스 설명에 적혀 있다(RecordedDayStream.java:39-46). `unmarkedRestarts` 계산은 경계 첫 줄을 지날 때 표시 여부를 기록하는 방식으로 바뀌었지만 결과는 같다(같은 firstSeq 경계는 같은 줄을 보므로 맵 중복 제거로 달라지지 않음).
- `RecordingDayAdapter`: 예전 `RecordedMarketDataSet.fromDirs` 본문을 옮긴 것. 경계 후보를 "표시 없는 경계"가 아니라 "두 번째 구간부터 firstSeq>1인 모든 경계"로 잡고 줄에서 표시 여부를 보는데, 표시된 경계는 아무것도 넣지 않으므로 결과가 같다. 직전 줄 수신 시각(`lines.get(i-1)`)은 `prevReceivedAt`으로 같다. 끝에서 `summary.unmarkedRestarts()`와 대조해 경계를 놓치면 거부한다(RecordingDayAdapter.java:65-69).
- `SimulationExperimentRunner.runStreamed`(SimulationExperimentRunner.java:512~): 검증(`validate`)은 두 경로 공통, 첫 읽기(`scanStream`, :623)에서 순서·기간 안 건수·거래일·첫 수신 시각·벤치마크, 둘째 읽기에서 Run을 조합 순서로 한 걸음씩. Run 실패 격리(`begin`/`onEvent`/`finish` 각각 `failed`로), 정지 예외 재던짐, 입력 쪽 예외는 Run 밖에서 실험 실패로 올라감, 둘째 읽기 순서·건수 불일치 거부(:548, :577), 예열 거부(:514)를 확인했다. 목록 경로(`MarketDataSet`)는 `slice` 이름만 바뀌고 동작이 같다.
- `LiveWeeklyCheck.compareDay`(LiveWeeklyCheck.java:296~): 예전 목록 대조와 짝 짓기가 같음을 확인했다. jCount는 기록 전체로, rCount는 녹화 전체로 세고, 녹화 순서로 jCount에서 빼고 기록 순서로 rCount에서 빼므로 예전 두 루프와 같다. 기록에서 빼는 줄(실험실 공백 소스의 DataGap) 조건도 두 번 읽기에서 같다. `SpanBuilder`(:421)는 구간 k = 순번 [firstSeq_k, firstSeq_{k+1})로 예전 `sessionSpans`의 from/to 탐색과 같은 범위다. 안정성 확인은 읽기 전 지문 → 마지막 읽기 뒤 비교로 바뀌었다(:334, :369, :389).
- `BoardsRecording.stream`(:510~): 예전 `recordedForReplay` 목록과 같은 필터(onBoards·inPlan)·같은 순번(날짜 → 녹화 순번 순 위치)이고, 다시 읽을 때 지문과 줄 수를 대조한다(:576, :592).
- `LiveSession.restore`: 줄 단위로 계좌에 바로 준다. 읽다 실패하면 세션 객체를 돌려주지 않고 잠금을 풀고(LiveSession.java:163-175) `step`은 메모리 상태만 바꾼다(LiveSession.java:288~). 실패 시 반쯤 되살린 계좌가 밖으로 나가지 않는다.
- 프로세스 경계: `autotrade-sim`은 engine·watchdog·gateway의 `forbiddenProjects`에 있다(autotrade-app-engine/build.gradle.kts:14, autotrade-app-watchdog/build.gradle.kts:14, autotrade-app-gateway/build.gradle.kts:12). sim의 jackson-databind 추가는 실전 클래스패스에 닿지 않는다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 검토자가 직접 `./gradlew build` 실행, BUILD SUCCESSFUL·종료 0. sim 316건·architecture-test 46건 실패 0, 건너뜀 0 |
| B2 | 통과 | 순수 모듈 검사·ArchUnit(`autotrade-architecture-test` 46건) 통과. 새 클래스는 모두 sim 소유 패키지(`com.autotrade.sim.recording`, `com.autotrade.sim.run`, `com.autotrade.sim.live`). 새 의존은 sim → jackson-databind(autotrade-sim/build.gradle.kts:11-13(주석·BOM·databind))로, marketdata가 이미 같은 라이브러리를 쓰고(autotrade-marketdata/build.gradle.kts:9-10) 금지 의존(Spring·증권사 SDK·DB 드라이버)이 아니다. 규칙 변경 없음 |
| B3 | **실패** | 실행기 입력 계약을 바꿨는데 ADR이 없다(아래 R1). 새 공개 인터페이스 `MarketEventStream`(sim/run/MarketEventStream.java), `MarketDataSet extends MarketEventStream`(MarketDataSet.java:20), `SimulationExperimentRunner` 생성자 5개의 입력 타입 변경, 새 실행 방식(두 번 읽기·모든 Run 함께 진행·병렬 미사용·예열 거부). ADR-004는 지금도 "입력: `MarketDataSet`(불변 이벤트 목록…)"(ADR-004-simulation-engine.md:64), "실행기 입력 = 불변 이벤트 목록… 같은 목록을 모든 Run이 독립적으로 순회해 결정론과 병렬화가 쉽다"(같은 파일:102)로 적혀 있다. 같은 계약에 기본 메서드를 더한 때(ADR-009 2.3)는 ADR로 남겼다. D-115는 결정 로그일 뿐 ADR 본문을 고치지 않았다. 설정 키·스키마·REST 변경은 없음 |
| B4 | 통과 | 시스템 시계·시드 없는 난수 없음(diff 검색 0건). 3단계 골든이 목록 경로·목록 아닌 입력·날마다 다시 읽는 녹화 입력 세 경로로 바이트 일치(`SimulationExperimentRunnerTest#목록이_아닌_입력도_같은_골든과_일치한다`, `#녹화를_날마다_다시_읽는_입력도_같은_골든과_일치한다`), 계좌 실패 격리·기간 자르기 보고서가 목록 입력과 같음(`#목록이_아닌_입력에서도_한_계좌의_실패는_그_계좌만_멈춘다`, `#목록이_아닌_입력도_실험_기간_밖_이벤트를_같게_뺀다`). 결과에 영향 주는 순회 순서: Run은 조합 순서(SimulationExperimentRunner.java 둘째 읽기 루프), 공백 끼워 넣기는 `TreeSet<Symbol>`. 기존 `LiveWeeklyCheckTest`·`LiveSessionTest`·`RecordedMarketDataSetTest` 골든은 고치지 않고 통과 |
| B5 | 통과 | 새 금액 계산은 벤치마크 수익률뿐이고 예전과 같은 `BigDecimal.divide(MathContext.DECIMAL128)`(StreamScan.benchmarkReturn). `double`·`float` 추가 0건 |
| B6 | **실패** | 새 읽기·실행 경로 자체는 시험이 있다(`RecordedDayStreamTest` 5건, `RecordedMarketDataSetTest` 새 3건, `SimulationExperimentRunnerTest` 새 7건). 그러나 `LiveWeeklyCheck`에 새로 생긴 거부 동작에 시험이 없다(아래 R2): ① 하루 대조 뒤 계좌 대조 전에 녹화가 바뀌면 거부(LiveWeeklyCheck.java:576, :592), ② 줄 열쇠를 문자열에서 128비트 해시(`Key`, :480)로 바꾼 대조. `LiveWeeklyCheckTest`는 이 PR에서 바뀌지 않았다(8건 그대로). 시험을 지우거나 건너뛴 것은 없음 |
| B7 | 해당 없음 | 실전 주문 경로·Guard·블랙리스트·장애 대응·보호 경로 변경 없음(protected-paths 대조 0건). sim은 실전 엔진·감시·게이트웨이에서 금지 의존이다(위 근거). risk-ops 검토 대상 아님 |
| B8 | 통과 | diff에 비밀값 단어·값 없음. 예외 메시지에는 녹화 폴더 경로·순번·종목 코드만 들어간다 |

## 수정 요청
- **R1(B3)**: ADR-004를 갱신하거나 새 ADR을 쓴다. 최소한 ① 4장 입력 절(:64)에 `MarketEventStream` 계약(부를 때마다 같은 순서·같은 이벤트, 읽다 바뀌면 예외, 실행기는 그 예외를 Run 실패가 아니라 실험 실패로 올림)과 `MarketDataSet`이 그것을 상속함, ② 목록 아닌 입력의 실행 방식(두 번 읽기, Run을 조합 순서로 함께 진행, `parallelism` 미사용, 예열 거부, 정지 확인 지점 추가), ③ 녹화 입력 절(:70)에 `StreamedRecordingDataSet`(같은 어댑터·같은 버전, 다시 읽을 때 파일 해시 확인), ④ 대안 표(:102)에 이번 결정(D-115 대안 ①~③)과 되돌릴 조건을 적고 D-115를 링크한다.
- **R2(B6)**: `LiveWeeklyCheck` 새 거부 동작 시험을 더한다. `compareDay`·`BoardsRecording`이 패키지 범위라 단위로 부를 수 있다. 최소한 ① `compareDay`로 녹화일을 더한 뒤 그 녹화 파일을 바꾸고 `BoardsRecording.stream(...).forEach`가 "대조한 뒤 바뀌었습니다"로 이벤트를 넘기기 전에 멈추는지, ② 같은 내용 줄이 여러 번 나오는 날(같은 열쇠 여러 개)에서 빠짐·남음 수가 예전 목록 대조와 같은지(기존 시험이 이미 이 경우를 고정하고 있다면 그 시험 이름을 PR 본문에 적는 것으로 갈음).

## 점검표 밖 발견(차단 아님)
- N1: 목록 아닌 입력 경로에서 선정 있는 조합(`spec.hasSelections()`, SimulationExperimentRunner.java:520)은 시험이 없다. 지금 이 경로를 쓰는 명령(주간 점검·리플레이 확인)은 선정 공급자 null로 돌므로 닿지 않지만, 다른 명령이 이 입력으로 옮겨 가기 전(D-115 후속 3번째 항목)에 골든 대조를 더하거나 그때까지 선정 조합을 거부하는 것을 권한다.
- N2: D-115 "위험과 한계"의 읽기 횟수(기록 3번 + 녹화 1번 등)는 압축 풀기 횟수만 센 것이다. 지문·SHA-256 확인으로 같은 파일을 날마다 몇 번 더 읽는다(RecordingDayAdapter 앞뒤 해시, 정상 종료 녹화의 `Sha256.ofFile`, `fingerprintOf` 앞뒤). 시간 추정에 영향이 있을 수 있으니 6단계 첫 2주 실측 때 함께 적는다.
- N3: 읽기 규칙이 `RecordedDay`(marketdata)와 `RecordedDayStream`(sim) 두 벌이다. `RecordedDayStreamTest`가 같음을 고정하지만 marketdata 쪽만 고치면 sim 시험이 깨질 때까지 모른다. D-115 후속(data-engineer, 보호 경로)으로 등록된 것을 확인했다. 그 PR 전까지 `RecordedDay` 클래스 설명에 "sim `RecordedDayStream`도 함께 고친다"는 한 줄을 남기는 것이 좋지만 보호 경로라 이 PR 범위 밖이다.
- N4: 문서의 메모리 실측(768MB OOM → 256MB 통과)과 "고치기 전 대비 보고서 19건 바이트 일치"는 개발 쪽 수동 실행이라 검토자가 재현하지 않았다 `[미검증]`. 다만 결과 동일성은 기존 골든 시험들이 고치지 않은 채 통과하는 것으로 B4를 판정했다.
- N5: 128비트 열쇠 충돌 확률 추론(D-115)은 타당하다. 충돌 시 영향은 대조 건수 1건 오차이고 매매·결과에는 닿지 않는다.

## 판정
수정 필요(R1 ADR-004 갱신, R2 LiveWeeklyCheck 새 거부 동작 시험)
