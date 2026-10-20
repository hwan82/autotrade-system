# architect 검토: 감시 종목 100종목 넓히기 준비(D-122, ADR-012 배치표 0번)
- 대상: PR #195 / 브랜치 `claude/project-thread-62fag0` / 헤드 커밋 adc91bc / 병합 기준 main e9a7ee2(현재 `origin/main` 끝과 같음) 대비 diff 전체(커밋 1개, 11파일 +451 −5)
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체 읽음. 바뀐 파일은 `autotrade-app-experiment/build.gradle.kts`, `ExperimentMain.java`, 새 `autotrade-sim/.../selection/WatchlistCandidatesMain.java`와 그 시험, 새 선정 프로필 `selections/kr-watchlist-turnover/v1.yaml`, 문서 6개(ADR-012, D-122, 결정 로그, runbook 2개, selections/README). `scripts/ci/protected-paths.txt`의 보호 경로에 드는 파일은 없다.
  - `./gradlew build`: 1회째 Maven Central 429(Too Many Requests)로 의존성 해석 실패(코드와 무관). 저장소 밖 init script(`mirror.gradle`, Maven Central 미러)로 `./gradlew --init-script <scratchpad>/mirror.gradle clean build` → **BUILD SUCCESSFUL in 3m 47s**(161 actionable tasks: 152 executed, 9 up-to-date). 시험 결과 XML 합계 1,688건, 실패 0, 오류 0, 건너뜀 0. `WatchlistCandidatesMainTest` 4건 통과. `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 통과.
  - **실제 진입점 실행**(시험이 덮지 않는 경로): `AUTOTRADE_CONFIG_DIR=<scratchpad>/cfg ./gradlew --init-script ... :autotrade-app-experiment:runWatchlistCandidates -Psnapshot=krx-daily-none -Ptotal=100 -Pout=<scratchpad>/cand.txt -Pheap=512m`(2026-10-20 09:13 UTC = 18:13 KST, 장 시간 보호 밖). 결과: 본체에 들어가기 전에
    ```
    Exception in thread "main" java.lang.IllegalArgumentException: 모르는 실행 명령입니다: watchlist
        at com.autotrade.app.experiment.ExperimentJob.start(ExperimentJob.java:122)
        at com.autotrade.app.experiment.ExperimentMain.run(ExperimentMain.java:128)
    ```
    로 종료 코드 1. 아래 R1.
  - 주장 대조: runbook이 넣는 꼭 넣을 종목 10개(`stage-6-start-checklist.md` 4.1)가 `experiments/EXP-20261026-LIVE01/experiment.yaml:23-33`의 `boards` 10종목과 집합이 같음을 확인. runbook 2번이 쓰는 JSON 형식(`{"held":[...],"candidates":["KRX:..."]}`)이 `autotrade-app-gateway/.../WatchList.java:21-46`(`FAIL_ON_UNKNOWN_PROPERTIES`, `MARKET:CODE`)과 맞음을 확인. runbook이 가리키는 `구독 거절` 로그(`StreamingMarketDataSource.java:456`), `LiveLabService.INBOX_CAPACITY = 100_000`(`LiveLabService.java:57`), `LiveWeeklyCheck` 존재 확인.
  - 구독 배분 대조: `SourcePlanner.plan`(`SourcePlanner.java:57-86`)은 보유 → 후보 순서로 앞 순위 소스 한도까지 채우고 넘치는 후보를 뒤에서 뺀다. 키움 한도는 `KiwoomStreamProtocol.MAX_SYMBOLS = 200`(:64, 종목 단위 `[가정]`), 한국투자는 `MAX_ENTRIES / 2`(`KisStreamProtocol.java:53`). 꼭 넣을 종목이 후보 목록 앞에 오므로 키움이 DEGRADED여서 한국투자 FAILOVER로 가도 LIVE01 10종목이 먼저 배정된다 `[추론]`(코드 순서 근거). 100 × 2 = 200 계산은 D-122·market-data-limits.md 139행의 미확인 항목과 일치.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `clean build` BUILD SUCCESSFUL, 1,688건 실패 0. 기본 저장소로는 Maven 429(인프라)라 허용된 미러 init script로 실행 |
| B2 | 통과 | 새 클래스는 `autotrade-sim`의 `com.autotrade.sim.selection`에 있고 `domain`·`marketdata`·`selection`·`sim` 안 클래스만 쓴다(`WatchlistCandidatesMain.java:3-12`). Spring·SDK·DB 의존 없음. `ArchitectureTest`·`ModuleOwnershipTest` 통과. 설정 폴더 해석은 기존 `CombinationMain.java:94-98`·`RecordingMain.java:91-95`와 같은 방식이고 `ConfigPaths.defaultLocation`(:28-38)과 순서가 같다 |
| B3 | 통과(조건) | 새 실행 명령·Gradle 작업은 ADR-012 6장 0번, D-122, `docs/runbooks/README.md` 표에 적혀 있다. 단 R1을 고치면 작업 상태 파일 `command` 값 집합(`ExperimentJobStatus.COMMANDS`, 실험실 작업 화면이 읽음)이 늘어나므로 그 변경을 D-122(또는 runbook 표)에 함께 적어야 한다 |
| B4 | 통과 | 새 코드에 시스템 시계·난수 없음. 출력 순서는 입력 순서 `LinkedHashSet` + 계산기 `selected()` 순서(`WatchlistCandidatesMain.java:150-160`). `HashMap`(`Collectors.toMap`)은 조회용으로만 쓰고(:148-149) 출력 순서에 영향 없음. 기존 골든 시험 통과 |
| B5 | 통과 | 금액(거래대금)은 계산기가 준 문자열 그대로 로그에만 쓴다(`:120-121`, `:148-149`). `double` 없음. 프로필 값 `1000000000`·`1000`은 문자열 → 계산기 BigDecimal |
| B6 | 실패 | `WatchlistCandidatesMainTest` 4건은 본체(`run`·`compute`)만 직접 부른다. 운영 진입점 `ExperimentMain.run` → `ExperimentJob.start` 경로(작업 잠금·상태 파일·장 시간 보호·정지 요청)에 `watchlist` 시험이 없다. 다른 명령은 모두 `ExperimentGateTest`(예: `녹화_판정도_정지_요청과_장_시간에는_시작하지_않는다`, :222-237)와 `ExperimentJobTest#jobId`(:441)에 시험이 있다. 이 빈자리 때문에 R1이 빌드를 통과했다 → R2 |
| B7 | 해당 없음 | 실전 엔진·`GuardedOrderGateway`·RealTradingGuard·블랙리스트·장애 대응·증권사 주문 코드와 보호 경로 변경 없음(diff 파일 목록 vs `scripts/ci/protected-paths.txt`). 게이트웨이 코드도 바뀌지 않는다. 다만 runbook 4.1은 실전 엔진과 같은 게이트웨이 구독 한도를 쓰는 운영 설정을 바꾸고, D-122가 스스로 risk-ops 교차 검토를 적었으므로 risk-ops 검토를 권고한다(실전이 꺼진 동안이라 실전 영향은 없다 `[추론]`) |
| B8 | 통과 | 새 코드 로그는 종목 코드·날짜·경로·거래대금만 출력(`:116-122`). 키·계좌·토큰을 읽지 않는다. runbook 명령에도 비밀값 자리 없음 |

## 점검표 밖 발견
- **R1(차단, 수정 필요)** `runWatchlistCandidates`가 어떤 입력으로도 돌지 않는다. `ExperimentMain.run`은 본체 전에 `ExperimentJob.start(job, args[0], ...)`(`ExperimentMain.java:128`)로 상태 파일을 쓰는데, `ExperimentJobStatus` 생성자가 `COMMANDS`(`autotrade-sim/.../run/ExperimentJobStatus.java:58-59`, `combination…live-judge` 7개)에 없는 명령을 `IllegalArgumentException("모르는 실행 명령입니다: watchlist")`로 거절한다(:93-95). 이 예외는 `ExperimentMain.run`이 잡지 않아(잡는 것은 `AlreadyRunningException`·`IOException`뿐, :130-139) 스택 추적과 함께 종료 코드 1로 끝난다. 직접 실행으로 재현함(위). 그래서 runbook 4.1 1번의 "후보 100종목이면 된다 / 종료 코드 4면 장 시간 보호" 안내가 실제로는 언제나 "1"이 된다(장 시간 중에도 정지 확인보다 먼저 터져 4가 아니라 1). 고칠 곳: `ExperimentJobStatus.COMMANDS`에 `"watchlist"` 추가, 실험실 작업 화면 라벨 `autotrade-ui/src/main/resources/static/js/pages/jobs.js:11-14`의 `COMMAND`에 `watchlist: '감시 종목 후보'` 같은 항목 추가(없으면 화면에 명령 이름이 그대로 나올 것 `[추론]`), B3의 문서 한 줄.
- **R2(수정 필요)** `ExperimentGateTest`에 `watchlist` 경우를 더한다: (a) 주말 시계 + 합성 스냅샷으로 `ExperimentMain.run(new String[] {"watchlist", root, snapshot, "-", "N", out, "--snapshots-dir", ...})`이 0이고 목록 파일이 생김(R1 회귀 방지), (b) 장 시간 시계면 4, 정지 요청 파일이 있으면 `--allow-during-session`이어도 4이고 출력 파일 없음, (c) `ExperimentJobTest`에 `jobId("watchlist", ...)`가 `WATCHLIST-CANDIDATES`임. (a)가 지금 코드에서 실패해야 한다.
- M1(권고) `selections/kr-watchlist-turnover/v1.yaml:1`은 카탈로그 `docs/strategy/selection-catalog.md`를 가리키지만 카탈로그 표(:80-82)에 이 프로필 줄이 없다. `selections/README.md`에만 있다. "매매 선정이 아님"을 카탈로그에도 한 줄 적어 두면 조합 실험에 잘못 쓰는 일을 막는다. 이 프로필은 실험실 선정 프로필 목록에도 나타난다 `[추론]`(`SelectionProfileStore`가 `selections/`를 읽음).
- M2(권고) 문서가 "보통주"라고 쓰지만 수집기는 KOSPI·KOSDAQ·KONEX 일별매매 전 종목을 `STOCK`으로 쓴다(`KrxDailySnapshotBuilder.java:203`, `KrxBoard.java:5-7`). 그래서 `filter.kinds: STOCK`은 우선주(예: 005935)·KONEX를 거르지 않는다. 기존 카탈로그(:80)도 같은 표현이라 새로 생긴 문제는 아니고, 감시 후보로는 우선주가 들어가도 해가 없다. 다만 D-122·runbook의 "보통주"를 "주식(우선주 포함, ETF·ETN 제외)"로 바로잡거나, 받아들인다고 적는다.
- 확인함(문제 없음): 꼭 넣을 종목 중복 제거 후 개수 검사(`:95`), 출력은 임시 파일 → `ATOMIC_MOVE`로 바꾸기, 정지 시 파일 안 씀(시험 `정지_확인이_멈추라면_파일을_쓰지_않고_4로_끝난다`), 스냅샷 ID 경로 문자 거절(시험 `입력이_틀리면_파일을_쓰지_않는다`). 기준 데이터일이 스냅샷 마지막 거래일의 앞날이라 하루 늦은 정보임은 Javadoc(:137-139)과 runbook에 적혀 있다.

## 판정
수정 필요(R1, R2)
