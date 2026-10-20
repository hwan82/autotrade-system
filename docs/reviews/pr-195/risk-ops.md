# risk-ops 검토: 감시 종목 100종목 넓히기 준비(D-122, ADR-012 배치표 0번)
- 대상: PR #195 / 브랜치 `claude/project-thread-62fag0` / 코드 커밋 `adc91bc`(그 위 `bf420b0`은 architect 원문만 더함) / `git diff origin/main...HEAD` 전체(12파일)
- 점검표: A
- 직접 실행한 것:
  - 깨끗한 작업 트리(`git worktree add --detach … bf420b0`, 커밋 안 된 변경 없음)에서 `./gradlew --init-script <scratchpad>/mirror.gradle build` → **BUILD SUCCESSFUL**(4분 8초). init script는 저장소 주소만 Maven Central 미러로 바꾼다(Maven 429 회피, 내용 확인함).
  - 테스트 결과 XML 집계(실패·오류·건너뜀 모두 0): `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 46, `:autotrade-app-engine:test` 68, `:autotrade-sim:test` 363(새 `WatchlistCandidatesMainTest` 4건 포함), `:autotrade-app-experiment:test` 42, `:autotrade-app-gateway:test` 55.
  - 참고: 처음에는 공유 작업 폴더(`/home/claude/autotrade-system`)에서 돌렸는데, 다른 세션이 같은 폴더에서 `clean build`를 동시에 돌려 `autotrade-architecture-test`가 클래스 폴더를 잃고 실패했고, 또 그 폴더에는 커밋되지 않은 수정(`ExperimentMain.java`, `WatchlistCandidatesMain.java`, `ExperimentJobStatus.java`, `jobs.js`, 새 `WatchlistCommandTest.java`)이 있었다. 그래서 그 결과는 판정에 쓰지 않고, 위 깨끗한 작업 트리 결과만 쓴다. **이 판정은 커밋 `bf420b0` 기준이며 커밋 안 된 수정은 검토하지 않았다.**
  - 실전 경로 확인: `autotrade-app-engine/src/main/java`(EngineMain, command, order)는 게이트웨이 시세를 읽지 않음(`grep gateway|Feed` 0건), `watchlist.json`을 읽는 운영 코드는 `WatchList`·`GatewayMain:96`·`KiwoomDailyMain:117`뿐. 보호 경로 목록(`scripts/ci/protected-paths.txt`)에 걸리는 파일 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`autotrade-control`·`autotrade-config` 변경 없음(diff 12파일 중 해당 없음). RealTradingGuard 테스트 포함 `:autotrade-risk:test` 55건 통과. 새 코드는 Guard·설정 로더를 읽지 않음(`WatchlistCandidatesMain.java` import 목록: selection·sim·marketdata calendar뿐) |
| A2 | 통과 | 새 코드에 주문·OrderGateway 참조 없음. `ArchitectureTest`·`RealPathReflectionTest` 등 `:autotrade-architecture-test:test` 46건 통과, 해당 모듈 파일 변경 없음 |
| A3 | 해당 없음 | 주문 경로 변경 없음. 바뀌는 것은 게이트웨이 시세 구독 목록(사용자 PC `watchlist.json`)뿐이고 주문 라우팅과 무관. 실전 계좌 증권사는 `GatewayMain.reservedForRealTrading`(:134-142)으로 게이트웨이 시세 소스에서 빠지는 규칙이 그대로다 |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 주문 생성 경로 변경 없음 |
| A6 | 통과 | 감시 종목은 구독(데이터)일 뿐 주문 후보가 아니다. 실험실 실시간 모의는 등록 파일 `boards`만 거래하고 블랙리스트를 그대로 적용(`experiments/EXP-20261026-LIVE01/experiment.yaml:23-36`, 035720 블랙리스트 유지). `watchlist.json`을 주문 후보로 쓰는 코드 없음(`WatchList.java` Javadoc: 후보 풀 연동은 3·7단계). 런북 4.1의 PowerShell은 `held` 칸을 원래 값 그대로 다시 쓴다(`stage-6-start-checklist.md` 4.1 2번) |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 변경 없음. 실전 엔진은 게이트웨이 구독을 쓰지 않음(위 확인) |
| A8 | 통과 | 실전 엔진은 게이트웨이 시세를 소비하지 않고 별도 JVM이다. 게이트웨이 → 실험실 전달은 기다리지 않는다(장애표 #9, 받는 쪽 큐가 차면 버림). 새 명령은 `ExperimentMain`의 작업 잠금·장 시간 보호·정지 요청 파일 아래에서만 돈다(`ExperimentMain.java` `run`→`ExperimentJob.start`→`runControlled`, `jobId("watchlist")`가 고정 ID를 돌려 잠금 없이 도는 길이 없음). 5.2 정지 순서 0번(실험 실행 명령 정지 요청)에 그대로 들어간다. 정지 시 파일을 쓰지 않고 4로 끝남: `WatchlistCandidatesMainTest#정지_확인이_멈추라면_파일을_쓰지_않고_4로_끝난다` 통과 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. 실전 보유 종목은 `held`로 들어가 `SourcePlanner.plan`(:55-81)에서 후보보다 먼저 배정되고 한도를 넘어도 빠지지 않는 규칙이 그대로다. 지금은 실전 계좌가 꺼져 있다(D-122 위험 4번째 줄) |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음. 런북은 "로그 줄을 올린다(키는 나오지 않는다)"만 적고, 새 코드 로그는 스냅샷 ID·종목 코드·거래대금·출력 경로뿐(`WatchlistCandidatesMain.java` `run`의 `log` 호출) |
| A11 | 수정 필요(R1) | 보호 테스트 삭제·약화·고정 값 갱신 없음(`autotrade-architecture-test` 변경 없음). 새 계산·입력 오류·정지 4는 `WatchlistCandidatesMainTest` 4건이 실패 경우까지 덮는다. 그러나 이 PR이 새로 더한 `ExperimentMain`의 `watchlist` 분기(장 시간 보호 구간에서 4로 멈추고 파일을 안 씀, `jobId("watchlist")` 고정 ID로 작업 잠금이 걸림, 중복 실행 거절)를 확인하는 시험이 커밋 `bf420b0`에 없다(`autotrade-app-experiment/src/test` 변경 0건). 다른 명령의 같은 경로 시험(`ExperimentGateTest`의 `live-judge`, `ExperimentJobTest#jobId`)은 있지만 새 분기를 고정하지 않는다 |
| A12 | 통과 | 장애표 17종·O1~O8·장애 테스트 8종 전제 변경 없음. #9(게이트웨이 → 실험실 버림), #10(실험 실행 명령 정지 요청, 종료 코드 4)에 새 명령이 같은 규칙으로 들어간다. 구독 거절은 기존 `StreamingMarketDataSource.java:456`("구독 거절" 경고)로 감지되고 런북 4.1 4번이 그 경우 09:00 전 10종목 복귀를 정한다. 키움 한 REG는 100종목씩 나뉘므로(`KiwoomStreamProtocol.java:65,140`) 100종목은 REG 1건이다 |
| A13 | 해당 없음 | KRX만(`Market.KRX`, `ExchangeCalendar.bundled(Market.KRX)` 고정). 미국 경로 변경 없음 |
| A14 | 통과 | D-122와 결정 로그 표 행, ADR-012 배치표 0번 갱신, 런북 4.1과 README 명령 표가 코드 동작(목록 파일만 씀, `watchlist.json` 미변경, 종료 코드 0·1·3·4)과 맞는다. 기획서 MD-04(한도 안, 보유 제외 안 함)와 충돌 없음. 7단계 전 risk-ops "실전 보유 몫을 남긴 watchlist 상한" 숙제는 ADR-012·D-122 위험 절에 남아 있다 |

## 점검표 밖 발견
- **R1(수정 필요, A11)**: `ExperimentMain`의 `watchlist` 분기 시험을 더한다. 최소: (a) 장 시간 보호 구간 시각의 `Clock`으로 `ExperimentMain.run(["watchlist", …])`이 4를 돌려주고 출력 파일이 생기지 않음, (b) 정지 요청 파일이 있으면 4·파일 없음, (c) `jobId("watchlist", …)`가 `WATCHLIST-CANDIDATES`이고 같은 ID 작업이 도는 중이면 1로 거절. 이 명령은 6GB 힙으로 전 기간 스냅샷을 읽으므로 장중 보호가 실제로 걸리는지를 이 분기 단위로 고정해야 한다. (공유 작업 폴더에 커밋 안 된 `WatchlistCommandTest.java`가 보이지만 커밋되지 않아 검토하지 않았다.)
- N1(권고): 런북 4.1 4번은 첫날 "구독 거절" 로그만 본다. 거절 없이 일부 종목이 안 오는 경우(키움이 항목별로 세어 일부만 받는 등, `KiwoomStreamProtocol` 종목 단위 계산은 `[가정]`)를 잡으려면 첫날 09:10쯤 LIVE01 `boards` 10종목이 모두 수신 중인지(2.2 상태의 `dataGaps` 또는 녹화 종목 수) 한 줄 확인을 더하기를 권한다. 계좌 안전 문제는 아니고 LIVE01 결과 보호용이다.
- N2(권고, 7단계 숙제 확인): 키움 실전 계좌가 설정되면 게이트웨이는 키움을 시세 소스에서 통째로 뺀다(`reservedForRealTrading`). 그때 100종목 watchlist는 KIS(appkey당 41건)로 넘어가 후보 대부분이 `excluded`가 된다. 7단계 상한을 정할 때 "키움 예약 시 watchlist 크기"를 함께 정해야 한다. 지금은 실전이 꺼져 있어 영향 없음.
- N3(참고): `WatchlistCandidatesMain.configDir()`는 `ExperimentMain`이 넘기는 설정 폴더를 쓰지 않고 속성·환경 변수를 다시 읽는다. 두 값이 같은 규칙이라 지금은 같은 폴더지만, 정지 요청 폴더와 스냅샷 폴더가 갈릴 여지를 없애려면 `configDir`을 인자로 넘기는 편이 낫다. 안전 영향 없음.
- N4(참고): `KiwoomDailyMain`은 인자가 없으면 `watchlist.json` 전 종목을 조회하므로 100종목이면 REST 100회가 된다. 게이트웨이를 끄고 실전 키움 계좌가 없을 때만 도는 수동 도구라(`KiwoomDailyMain.java:78-81`) 실전 영향은 없다.

## 판정
수정 필요(R1)

- 범위 메모: 이 원문을 push하려 할 때 브랜치에 `f1a960d`(architect R1·R2·M1·M2 반영, `WatchlistCommandTest.java` 추가 등 12파일)가 새로 올라와 있었다. 그 커밋은 이 검토에 들어가지 않았다. R1이 그 커밋으로 풀렸는지는 재검토로 판정한다.

## 재검토 2026-10-20
- 대상: PR #195 / 커밋 `ba8b409`(앞 검토 뒤 `f1a960d`·`895cb29`·`ba8b409`, 그 사이 architect 원문 `a219088`) / `git diff origin/main...ba8b409` 전체. 앞 검토 대비 바뀐 것은 `git diff bf420b0 ba8b409`로 따로 봤다(검토 원문 빼고 12파일).
- 점검표: A
- 직접 실행한 것:
  - 별도 worktree(`git worktree add --detach … origin/claude/project-thread-62fag0` = `ba8b409`, 커밋 안 된 변경 없음)에서 `./gradlew --init-script <scratchpad>/mirror.gradle build` → **BUILD SUCCESSFUL**(3분 59초). 새 worktree라 모든 테스트가 실제로 돌았다.
  - 테스트 결과 XML 집계(실패·오류·건너뜀 0): `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 46, `:autotrade-app-engine:test` 68, `:autotrade-sim:test` 363, `:autotrade-app-experiment:test` 47(새 `WatchlistCommandTest` 5건 포함), `:autotrade-app-gateway:test` 55.
  - 런북 N1 확인 명령의 전제를 코드로 확인함: 녹화 파일 이름 `events.jsonl.gz`(`RecordingLayout.java:14`), 줄 모양 `"sym":"KRX:005930"`(`RecordingCodec.java:26,48`), gzip `syncFlush`와 약 1초마다 `flush`(`DayWriter.java:34-35,113`, `MarketEventRecorder.java:155`). 그래서 쓰는 중인 파일을 장중에 읽어도 1초 전까지의 줄이 보인다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 앞 검토와 같음. 추가 커밋도 `autotrade-risk`·`autotrade-control`·`autotrade-config`를 건드리지 않음. `:autotrade-risk:test` 55건 통과 |
| A2 | 통과 | 추가 커밋에 주문 경로 참조 없음. `:autotrade-architecture-test:test` 46건 통과, 해당 모듈 변경 없음 |
| A3 | 해당 없음 | 주문 경로 변경 없음(앞 검토와 같음) |
| A4 | 해당 없음 | 주문·타임아웃 경로 변경 없음 |
| A5 | 해당 없음 | 주문 생성 경로 변경 없음 |
| A6 | 통과 | 앞 검토와 같음. 런북 4.1 4번 추가 확인 명령은 녹화 파일을 읽기만 하고 `watchlist.json`·블랙리스트를 바꾸지 않음. 되돌리기는 기존 `before-wide` 복사본(`held` 그대로)으로만 함 |
| A7 | 해당 없음 | 주문 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | `ExperimentMain.dispatch`가 이제 실험 명령이 정한 `configDir`을 넘김(`ExperimentMain.java` `case "watchlist"`, `WatchlistCandidatesMain.run(String[], Path, RunGate)`). 정지 요청 폴더와 스냅샷 기본 폴더가 같은 설정 폴더에서 나온다(앞 N3 해소). 작업 잠금·장 시간 보호·정지 요청을 진입점에서 거치는 것이 시험으로 고정됨(아래 A11) |
| A9 | 통과 | 보호 시세 경로·서버 측 손절·`SourcePlanner` 보유 우선 규칙 변경 없음 |
| A10 | 통과 | 추가 diff에 키·계좌번호·토큰 없음. 실패 로그는 `e.getMessage()`인데 이 명령은 증권사·비밀값을 읽지 않는다(스냅샷 파일·선정 프로필만) |
| A11 | 통과 | 앞 R1 해소. `WatchlistCommandTest`(운영 진입점 `ExperimentMain.run`): `장_시간과_정지_요청에는_시작하지_않고_파일을_쓰지_않는다`(장 시간 4, 정지 요청 파일 있으면 `--allow-during-session`이어도 4, 출력 파일 없음), `같은_작업이_돌고_있으면_입력_오류로_거절하고_파일을_쓰지_않는다`(같은 ID 작업이 있으면 1·파일 없음), `작업_ID는_고정이다`(`WATCHLIST-CANDIDATES`), `장_밖에는_끝까지_돌아_목록_파일을_쓰고_작업_상태를_남긴다`(COMPLETED), `없는_스냅샷이면_입력_오류로_끝나고_파일을_쓰지_않는다`(INPUT_ERROR) 5건 모두 통과. 보호 테스트 삭제·약화·고정 값 갱신 없음. `catch (IOException \| RuntimeException)`으로 넓혔지만 `ExperimentStoppedException`(RuntimeException 하위, `ExperimentStoppedException.java:7`)을 먼저 잡으므로 정지 4가 1로 바뀌지 않는다(`WatchlistCandidatesMainTest#정지_확인이_멈추라면_파일을_쓰지_않고_4로_끝난다` 통과) |
| A12 | 통과 | 장애표 17종·O1~O8·장애 테스트 8종 전제 변경 없음. 런북 4.1 4번에 "거절 없이 LIVE01 종목이 빠지는 경우" 확인과 되돌리기가 더해졌다(앞 N1 해소). 장중 되돌리기의 게이트웨이 재기동은 기존 #9 처리(녹화 `RECORDER_RESTART`, 실험실 `DISCONNECTED` 공백)를 따르고 실전 엔진은 게이트웨이 시세를 쓰지 않아 영향 없음 |
| A13 | 해당 없음 | KRX만 |
| A14 | 통과 | D-122에 `STOCK` 정의(우선주·KONEX 포함), 키움 예약 시 KIS 41건 문제(앞 N2)를 7단계 상한에 넣는다는 문장, `KiwoomDailyMain` 100회(앞 N4), 되돌릴 조건에 "LIVE01 종목이 녹화에 없음"이 더해짐. 작업 상태 명령 목록(`ExperimentJobStatus.COMMANDS`)과 화면 이름(`jobs.js`)이 새 명령과 맞음. 기획서 MD-04와 충돌 없음 |

### 점검표 밖 발견
- 앞 R1·N1·N3 해소, N2·N4는 D-122 위험 절에 기록됨(7단계 상한 숙제로 남음, 지금은 실전이 꺼져 있어 영향 없음).
- N5(참고): 런북 4.1 4번 확인 명령은 `\w{6}`로 종목을 세므로 우선주(예: `005935`)도 함께 센다. `녹화된 종목 수`가 100 가까이인지만 보므로 판단에는 지장이 없다.
- N6(참고): 장중(09:30) 되돌리기는 그날 LIVE01에 재기동 공백을 남긴다. 계좌 안전 문제는 아니고, 런북이 "LIVE01 결과를 지키는 쪽이 먼저"라고 정한 대로다.

### 판정
승인
