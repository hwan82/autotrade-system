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

## 재검토 2026-10-20
- 대상: PR #195 / 헤드 커밋 f1a960d / 병합 기준 main e9a7ee2 대비 diff 전체(커밋 3개: adc91bc, 검토 원문 bf420b0, 반영 f1a960d). 반영 커밋은 12파일 +141 −28
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체를 다시 읽고, 반영분은 `git diff bf420b0..f1a960d`로 따로 읽음. 보호 경로(`scripts/ci/protected-paths.txt`)에 드는 파일은 여전히 없다(`autotrade-sim`·`autotrade-app-experiment`·`autotrade-ui`·문서뿐).
  - `./gradlew --init-script <scratchpad>/mirror.gradle clean build`(헤드 f1a960d, 기본 저장소는 1회째와 같은 Maven 429): **BUILD SUCCESSFUL in 3m 19s**(161 actionable tasks: 152 executed, 9 up-to-date). 시험 결과 XML 합계 1,691건, 실패 0, 오류 0, 건너뜀 0. 새 `WatchlistCommandTest` 3건, `WatchlistCandidatesMainTest` 4건 통과.
  - **변이 확인**: `ExperimentJobStatus.COMMANDS`에서 `"watchlist"`만 잠시 빼고 `:autotrade-app-experiment:test --tests '*WatchlistCommandTest'` → "3 tests completed, 2 failed". 새 시험이 R1을 잡는다. 파일은 되돌렸다(`git status` 깨끗함).
  - **실제 진입점 실행**: `AUTOTRADE_CONFIG_DIR=<scratchpad>/cfg2 ./gradlew ... :autotrade-app-experiment:runWatchlistCandidates -Psnapshot=krx-daily-none -Ptotal=100 -Pout=... -Pheap=512m`(09:25 UTC, 장 시간 밖). `[experiment] 진행 상태 파일: .../WATCHLIST-CANDIDATES.status`가 찍히고 본체(`[watchlist] 일봉 스냅샷 읽기: <cfg2>/snapshots/krx-daily/krx-daily-none`)까지 간다. R1은 해결됐다. 설정 폴더가 `ExperimentMain`이 정한 것과 같음도 이 줄로 확인(반영분이 `configDir`를 넘기게 바뀜, `ExperimentMain.java:177`). 다만 아래 R3.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `clean build` 성공, 1,691건 실패 0(미러 init script 사용, 사유는 1회째와 같음) |
| B2 | 통과 | 반영분은 `WatchlistCandidatesMain`의 자체 설정 폴더 해석을 지우고 `ExperimentMain`의 `configDir`를 받는다(`WatchlistCandidatesMain.java:61-65,112-113`). 새 의존 없음. `ArchitectureTest`·`ModuleOwnershipTest` 통과 |
| B3 | 통과 | 상태 파일 명령 값 추가(`ExperimentJobStatus.java:58-60`)가 D-122 결정 2번에 적혔고(`experiment-state/jobs/WATCHLIST-CANDIDATES.status`, 작업 화면 "감시 종목 후보"), 화면 라벨 `jobs.js:14` 추가. 실험실 쪽 읽기는 같은 `ExperimentJobStatus`를 쓰므로 따로 고칠 곳 없음(`autotrade-app-lab/.../ExperimentJobs.java`가 이 클래스를 씀) |
| B4 | 통과 | 1회째와 같음. 반영분에 시계·난수 추가 없음. 새 시험은 `Clock.fixed` |
| B5 | 통과 | 1회째와 같음 |
| B6 | 통과 | `WatchlistCommandTest`: `장_밖에는_끝까지_돌아_목록_파일을_쓰고_작업_상태를_남긴다`(종료 0, 파일 `000030, 000010`, 상태 `command: watchlist`·`COMPLETED`), `장_시간과_정지_요청에는_시작하지_않고_파일을_쓰지_않는다`(장 시간 4, 정지 요청이면 `--allow-during-session`이어도 4, 파일 없음), `작업_ID는_고정이다`. 변이 확인으로 R1 회귀를 잡음을 확인. 단 R3의 실패 경로 시험은 없다(아래) |
| B7 | 해당 없음 | 1회째와 같음. 실전 경로·보호 경로 변경 없음. D-122가 적은 risk-ops 교차 검토(게이트웨이 운영 설정 변경)는 계속 권고 |
| B8 | 통과 | 반영분 로그에도 비밀값 없음 |

### 1회째 항목 처리
- R1 해결: 위 실제 진입점 실행과 `WatchlistCommandTest`.
- R2 해결: 위 B6. 요청한 (a)(b)(c) 모두 있음.
- M1 해결: `docs/strategy/selection-catalog.md:83`에 "(감시 후보, 매매용 아님)" 줄.
- M2 해결: D-122·runbook·selections/README·프로필 주석이 `STOCK`(우선주·KONEX 포함)으로 바뀜. `v1.yaml`은 아직 main에 없는 새 파일이라 같은 PR 안의 수정은 "저장된 버전 파일은 고치지 않는다"에 걸리지 않는다. 바뀐 것은 주석과 `hypothesis` 문구뿐이고 계산 매개변수는 그대로다.

### 새 발견
- **R3(수정 필요)** 스냅샷을 읽지 못하면 종료 코드 계약이 깨진다. 위 실제 실행에서 `SnapshotInputs.load`가 던진 `java.io.UncheckedIOException: 스냅샷을 읽을 수 없습니다: ...`가 `WatchlistCandidatesMain.run`의 `catch (IllegalArgumentException | IllegalStateException | IOException e)`(`WatchlistCandidatesMain.java:129`)에 걸리지 않고 빠져나가 스택 추적이 찍히고, 상태 파일은 `state: FAILED`, `message: 종료 코드 -1`로 남았다. Javadoc(종료 코드 "1 입력 오류")과 runbook 4.1 1번("1이면 마지막 줄을 스레드에 올린다", 마지막 줄이 스택 프레임이나 Gradle 문구가 됨)과 다르고, 실험실 작업 화면에는 입력 오류가 아니라 실패로 보인다. 다른 명령은 `RuntimeException`을 잡아 1로 끝낸다(`CombinationMain.java:87-89`, `RecordingMain.java:84`). 같은 방식으로 `RuntimeException`(또는 최소 `UncheckedIOException`)을 잡아 `[watchlist] 실패: ...`와 1을 돌려주고, `WatchlistCommandTest`에 "없는 스냅샷 ID면 1, 목록 파일 없음, 상태 `INPUT_ERROR`" 시험을 더한다. 1회째 검토에서 이 경로를 놓쳤다(1회째 실행은 R1에서 먼저 멈춰 본체까지 가지 않았다).
- m1(권고) D-122 "위험과 한계"의 "우선주는 거래대금이 작아 100위 안에 드는 일이 드물다 `[추론]`"은 근거가 약하다. 삼성전자우(005935) 같은 대형 우선주는 거래대금 상위권에 자주 든다 `[추론]`. 첫 후보 목록을 뽑은 뒤 우선주 수를 세어 적는 쪽이 낫다. 같은 절에 새 줄 뒤 빈 줄이 하나 더 있다(표시 문제만).

## 판정
수정 필요(R3)

## 재검토 2026-10-20 (2차)
- 대상: PR #195 / 헤드 커밋 ba8b409 / 병합 기준 main e9a7ee2 대비 diff 전체. 1차 재검토(a219088) 뒤 커밋 2개: 895cb29(risk-ops R1·N1~N4 반영), ba8b409(architect R3·m1 반영). 두 커밋 합계 4파일 +46 −3(`WatchlistCommandTest.java`, `WatchlistCandidatesMain.java`, D-122, 시작 점검표)
- 점검표: B
- 직접 실행한 것:
  - `git diff origin/main...HEAD` 전체와 `git diff a219088..ba8b409`를 읽음. 보호 경로(`scripts/ci/protected-paths.txt`)에 드는 파일은 여전히 없다.
  - 다른 세션과 빌드 폴더가 겹치지 않게 별도 worktree(`<scratchpad>/wt195arch`, detached ba8b409)에서 `./gradlew --init-script <scratchpad>/mirror.gradle clean build`: **BUILD SUCCESSFUL in 4m 10s**(161 actionable tasks: 138 executed, 23 up-to-date). 시험 결과 XML 합계 1,693건, 실패 0, 오류 0, 건너뜀 0. `WatchlistCommandTest` 5건 통과.
  - **변이 확인**: worktree에서 `WatchlistCandidatesMain`의 catch만 1차 때 모양(`IllegalArgumentException | IllegalStateException | IOException`)으로 되돌리고 `:autotrade-app-experiment:test --tests '*WatchlistCommandTest'` → "5 tests completed, 1 failed". 새 시험 `없는_스냅샷이면_입력_오류로_끝나고_파일을_쓰지_않는다`가 R3을 잡는다. 되돌림(`git status` 깨끗함).
  - **실제 진입점 실행**(worktree, 09:34 UTC): `runWatchlistCandidates -Psnapshot=krx-daily-none ...` → `[watchlist] 실패: 스냅샷을 읽을 수 없습니다: <cfg3>/snapshots/krx-daily/krx-daily-none` 한 줄, 스택 추적 없음, 종료 코드 1, 상태 파일 `state: INPUT_ERROR`, `message: 종료 코드 1`.
  - runbook 4.1 4번에 새로 들어간 첫날 녹화 확인 명령을 코드와 대조: 파일 위치 `<설정>/recordings/KRX/<yyyy-MM-dd>/events.jsonl.gz`(`GatewayMain.java:45` `RECORDINGS_DIR = "recordings"`, `RecordingLayout.java:9,14`), 줄의 `"sym":"KRX:005930"` 형식(`RecordingCodec.java:26,48`, `Symbol.toString` = `market:code`)과 맞다. 녹화기는 gzip을 `syncFlush`로 열고 주기마다 `flush()`하므로(`DayWriter.java:34-35,113`, `MarketEventRecorder.java:154-155`) 쓰는 중인 파일도 마지막 flush까지는 읽힌다. 그래서 09:30 확인이 "아직 디스크에 안 내려가서 안 보임"으로 거짓 경보를 낼 위험은 작다 `[추론]`. 끝 부분이 잘린 gzip에서 나는 예외는 명령이 잡아 정상으로 안내한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 worktree `clean build` 성공, 1,693건 실패 0(미러 init script 사용, 사유는 1회째와 같음) |
| B2 | 통과 | 반영분은 catch 한 줄과 시험·문서뿐이다. 새 의존 없음. `ArchitectureTest`·`ModuleOwnershipTest` 통과 |
| B3 | 통과 | 계약 변경 추가 없음. 1차 재검토와 같음 |
| B4 | 통과 | 반영분에 시계·난수 없음. 새 시험은 `Clock.fixed`(`at(ExperimentGateTest.WEEKEND)`) |
| B5 | 통과 | 1회째와 같음 |
| B6 | 통과 | `없는_스냅샷이면_입력_오류로_끝나고_파일을_쓰지_않는다`(종료 1, 파일 없음, 상태 `INPUT_ERROR`), `같은_작업이_돌고_있으면_입력_오류로_거절하고_파일을_쓰지_않는다`(같은 JVM에서 잠금을 쥔 채로 실행하면 `AlreadyRunningException` → 1, 파일 없음). 변이 확인으로 R3 회귀를 잡음을 확인 |
| B7 | 해당 없음 | 실전 경로·보호 경로 변경 없음. risk-ops가 같은 PR을 검토했음을 커밋 895cb29 제목과 반영 내용(N1~N4)으로 보았으나, 그 원문의 판정은 내 판정 범위 밖이다 |
| B8 | 통과 | 새 오류 출력은 예외 메시지(스냅샷 경로)뿐이다. `SnapshotInputs`·`SnapshotDailyBarSource` 예외에는 키·계좌가 섞일 경로가 없다(설정 비밀값을 읽지 않는 명령). runbook 확인 명령도 종목 코드만 출력한다 |

### 1차 재검토 항목 처리
- R3 해결: `WatchlistCandidatesMain.java:129-133`이 `IOException | RuntimeException`을 잡아 `[watchlist] 실패: ...`와 1을 돌려준다. `ExperimentStoppedException`은 그 앞 catch(:126-128)가 먼저 잡으므로 종료 코드 3·4는 그대로다. `CombinationMain.java:87-89`와 같은 방식이다.
- m1 해결: D-122 "위험과 한계"가 "대형 우선주는 상위에 들 수 있다, 첫 목록에서 세어 적는다"로 바뀌었고, runbook 4.1 1번에 세는 명령이 있다.

### 점검표 밖 발견
- n1(권고) runbook의 우선주 세기 기준 "코드 끝자리가 0이 아닌 종목"은 셈용 근사다. 끝자리 0인 우선주나 영숫자 새 코드가 있으면 조금 틀릴 수 있다. 목록은 바꾸지 않고 숫자를 기록만 하므로 영향은 없다.
- n2(권고) 첫날 녹화 확인 명령은 `[IO.File]::Open(..., 'Read', 'ReadWrite')`로 연다. Delete 공유를 주지 않으므로, 읽는 몇 초 사이에 게이트웨이가 같은 날 재기동해 tmp → 이름 바꾸기(D-119)를 하면 그 이름 바꾸기가 실패할 수 있다 `[추론]`. 확인은 게이트웨이를 다시 켜는 중이 아닐 때 한 번만 하라는 안내 한 줄이면 충분하다. 막지 않는다.

## 판정
승인
