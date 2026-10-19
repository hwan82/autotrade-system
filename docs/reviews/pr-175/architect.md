# architect 검토: 6단계 실시간 모의 운영 등록(LIVE00 시험·LIVE01 8주)과 판정 기준(D-113)
- 대상: PR #175 / 커밋 4fdfbaf5 / main 대비 diff(`git diff origin/main...HEAD`, 10파일 +501 −1)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon`(작업 폴더): **실패**. 원인은 코드가 아니라 같은 작업 폴더에서 다른 프로세스(`StageSixRegistrationTest`를 돌리는 별도 java, PID 4806)가 동시에 `autotrade-marketdata/build/classes`를 쓰고 있어 `:autotrade-marketdata:compileJava`가 출력 폴더를 지우지 못함("Unable to delete directory … New files were found").
  - 그래서 커밋 4fdfbaf5를 `git archive`로 스크래치 폴더에 풀어 `./gradlew build --no-daemon` → **실패(Maven 429)**: `:autotrade-app-lab:compileTestJava`가 `spring-boot-starter-test:3.5.16` pom을 repo.maven.apache.org에서 받다 "Received status code 429". 이어 `./gradlew build --offline --no-daemon --continue` → `:autotrade-app-lab:compileTestJava`(spring-boot-starter-test)와 `:autotrade-architecture-test:compileTestJava`(archunit-junit5 1.4.1)가 로컬 캐시에 없어 해석 실패. 나머지 20개 모듈 테스트는 모두 실행·통과(실패·오류·건너뜀 0): autotrade-sim 301(새 `StageSixRegistrationTest` 3개 포함), marketdata 141, watchdog 93, toss 90, notification 86, collector 79, engine 64, risk 55, kiwoom 51, gateway 50, strategy 44, config 42, experiment 40, monitor-agent 36, selection 34, domain 31, kis 30, control 23, monitor-sources 6, broker-api 1.
  - 직접 돌리지 못한 `autotrade-app-lab` 테스트·`autotrade-architecture-test`(ArchUnit)는 CI 결과를 근거로 삼음(이 PR은 두 모듈 파일을 바꾸지 않음).
  - CI(커밋 4fdfbaf5 check-runs, REST API 조회): Gradle build success, Secret scan and collector boundary success, Lab screens (Playwright) success, DB schema (PostgreSQL 16) success.
  - 등록 파일·절차서가 가리키는 코드 대조: `LivePlan`·`LiveSetup`(blacklist → `RiskChain`), `LiveSession.Status.failures`, `LiveLabService.finish`, `WatchList`, `SymbolNameFiles` 헤더, `autotrade-app-experiment`·`autotrade-app-gateway` Gradle 작업 이름·속성, `krx.json` 2026-11-19 행, 프로필 8개 `git log`.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과(로컬 일부 + CI) | 로컬 전체 빌드는 환경 원인(동시 프로세스 파일 잠금, Maven 429)으로 끝까지 못 돌림. 로컬에서 20개 모듈 테스트 전부 통과, app-lab·architecture-test는 CI Gradle build success(4fdfbaf5, `./gradlew build` 전체)로 판정. 변경 운영 코드 없음 |
| B2 | 통과 | 운영 코드 변경 없음. 새 파일은 `autotrade-sim/src/test/.../live/StageSixRegistrationTest.java` 하나(테스트 소스, `com.autotrade.sim.live` 패키지 = sim 소유). 쓰는 `com.autotrade.marketdata.calendar.ExchangeCalendar`는 sim의 기존 `implementation(project(":autotrade-marketdata"))`(autotrade-sim/build.gradle.kts:9) 범위. ArchUnit 규칙 변경 없음(ArchUnit 실행은 로컬 429로 못 함, CI Gradle build success로 확인) |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST API 변경 없음. 등록 파일은 기존 `LivePlan` 형식(LivePlan.java:24-59의 키 그대로, `blacklist` 포함)을 쓴다. ADR 불필요. 결정은 D-113 + README 표·Q-013 갱신 |
| B4 | 통과 | 시드 고정(`seed: "20261020"`, `"20261026"`), 기간 고정. 새 테스트는 시계·난수 미사용(`ExchangeCalendar.bundled`로 거래일 계산). 골든 테스트 무변경 |
| B5 | 해당 없음 | 금액 계산 코드 없음. `seed-capital: "10000000"`은 `Money.of(long, currency)`로 읽힘(LivePlan.java:151) |
| B6 | 통과 | `StageSixRegistrationTest` 3개: 두 등록 파일 파싱·`LiveSetup.of` 조립(프로필 읽기·비용 표 범위)·조합 8개, 두 파일 프로필·boards·블랙리스트·시드머니 동일·대조군 앞 번호·블랙리스트 ⊂ boards, 번들 캘린더로 LIVE00 4거래일·LIVE01 40거래일·기간 비중첩. 건너뛰거나 지운 테스트 없음(diff에 삭제 없음) |
| B7 | 해당 없음 | 실전 경로·보호 경로 미접촉. 변경 파일 10개 모두 `scripts/ci/protected-paths.txt` 밖(autotrade-sim 테스트, docs, experiments). 등록 파일 `blacklist`는 실험 계좌의 `BlacklistSnapshot`→`RiskChain`(LiveSetup.java:61-64)이며 실전 블랙리스트 저장소가 아님. 사용자 PC `watchlist.json` 후보 교체 절차는 게이트웨이 구독 목록만 바꾸고 주문 API와 무관(WatchList.java, 게이트웨이는 주문 API 미호출) |
| B8 | 통과 | 키·계좌번호·토큰 없음. 절차서는 `$env:AUTOTRADE_CONFIG_DIR`·CSRF 토큰을 런타임에 읽기만 하고 값을 문서에 두지 않음. CI Secret scan success |

## 점검표 밖 발견

### 수정 필요
- **N1 (절차 오류, 무사고 I1 판단을 그르침)**: `docs/runbooks/stage-6-start-checklist.md` 2.2 "바로 알릴 것: `failures`가 비어 있지 않음(계좌 실패, 계획 4.1 I1)". 코드상 `failures`는 계좌마다 한 칸이고 정상 계좌는 빈 문자열이다(`LiveSession.java:341-344`, `@param failures 계좌마다 실패 이유(정상이면 빈 문자열)` 368행). 8계좌면 정상일 때도 길이 8인 목록이라 "비어 있지 않음"이 항상 참이다. 화면도 `s.failures.filter(f => f).length`로 센다(autotrade-ui/.../live.js:160). 사용자가 매일 거짓 경보를 올리거나, 경보를 무시하는 습관이 생겨 실제 실패를 놓친다. 고칠 안: `"실패 계좌: {0}" -f @($st.session.failures | Where-Object { $_ }).Count`를 2.2 블록에 넣고 "0이 아니면 바로 알림"으로 바꾼다. 계획 4.1 I1 "어디서 보나"의 `session.failures`도 "빈 문자열이 아닌 칸 0개"로 고친다.
- **N2 (절차 누락, LIVE00 합격 확인·LIVE01 마감이 멈춤)**: 점검표 2.3 2번은 "실험실은 꺼도 된다"고 하지만 4장(LIVE00 끝)과 5장(12-18 LIVE01 끝)은 바로 `POST /api/live/finish`를 부른다. 실험실이 꺼져 있으면 세션이 없어 409("돌고 있는 실시간 모의 세션이 없습니다", `LiveLabService.java:252-253`), 되살리는 중이면 409("되살리는 중입니다", 255-256행)이고, `live-detail.txt`가 생기지 않아 `runLiveReplayCheck`(I3)가 돌지 않는다. 4장 앞에 "실험실이 꺼져 있으면 2.1 창 2·3으로 세션을 다시 열고, 2.2에서 `restoring`이 `False`가 된 뒤 끝내기"를 넣고, 409 메시지별 대응을 한 줄 적는다.

### 권고(머지 막지 않음)
- R1: `docs/strategy/stage-6-plan.md:156` "시작 점검표 4장 명령으로 주간 점검" → 주간 점검은 점검표 **3장**이다(4장은 LIVE00 끝). 같은 줄 "사용자가 결과 파일을 커밋·푸시"는 점검표 3장 "스레드에 올리면 Claude가 커밋"과 어긋난다. 하나로 맞춘다.
- R2: 계획 3장 LIVE00 행 "하루 한 번 실험실 재기동해 되살리기 시간 재기"가 점검표에는 지시로 없다(2.2는 "재기동한 날만 적는다"). LIVE00 목적(되살리기 시간 측정)을 채우려면 점검표 2장에 "LIVE00 동안 장중 하루 한 번 실험실을 껐다 켜고 2.1 창 3을 다시"를 넣는다.
- R3: 점검표 2.3 3번과 3장은 `$id`를 쓰는데 `$id`는 2.1 창 3에서만 정한다. 다른 창에서 붙이면 빈 값이 되어 `-Pexperiment=` 오류가 난다. 0장의 공통 두 줄에 `$id = …` 줄을 옮긴다.
- R4: 3장의 `JAVA_TOOL_OPTIONS="-Xlog:gc:file=$env:TEMP\…"`는 `%TEMP%`에 공백이 있으면(사용자 이름에 공백) JVM 옵션이 쪼개져 Gradle·작업 JVM이 기동 실패할 수 있다 `[추론]`. 경로에 공백이 없는 고정 폴더(예: 설정 폴더 아래 `gc\`)를 쓰거나 공백 여부 확인 줄을 둔다. 대안으로 `runLiveWeeklyCheck`에 GC 기록 속성을 더하는 방법이 있으나 이 PR 범위 밖(sim-engine 후속).
- R5: 점검표 1장 2번은 `watchlist.json`의 기존 `candidates`(다른 시장·종목 포함)를 모두 10종목으로 덮는다. 백업(`.before-stage6`)은 남지만, 해외 녹화 등 기존 후보를 쓰던 경우 6단계 동안 빠진다는 문장을 한 줄 넣는다.
- R6: 결정 번호가 D-111 다음 D-113이다. D-112는 다른 열린 브랜치(`origin/claude/project-thread-blfvr9`)에 있으므로 충돌은 아니나, 그 PR이 닫히면 빈 번호가 남는다(문제 아님, 기록만).
- 확인 범위 밖: 계획 7장 "매주 토요일 10시 예약 작업"이 실제로 만들어졌는지는 저장소에서 확인할 수 없다(문서 주장만 있음). 점검표 B 항목은 아니다.

### 확인한 사실(설계 판단 근거)
- 블랙리스트 확인 종목(I2)이 의미를 가지는가: 등록 `blacklist`는 시세를 빼지 않고 `RiskChain.builder(blacklist)`로 주문만 막는다(LiveSetup.java:61-64). 035720 시세는 boards로 들어가 전략 신호는 나므로, 체결 0건이 "주문 경로 필터가 막았다"는 뜻을 가진다. 리플레이 확인도 같은 `setup.risk()`를 쓴다(LiveReplayCheck.java:100-101).
- 메모리 위험 주장 `[사실]` 부분: `RecordedMarketDataSet.fromDirs`가 모든 날을 `ArrayList`에 올림(RecordedMarketDataSet.java:50-53, 113), `LiveWeeklyCheck`가 `recordedForReplay` 목록에 쌓음(LiveWeeklyCheck.java:189), `LiveReplayCheck.java:88` — 인용 행과 일치. T1을 10-30 전 선행으로 둔 판단에 동의한다.
- 프로필 8개 v1 파일은 모두 커밋 d27f3096(2026-10-09) 하나뿐, REC01(2b6acd25, 2026-10-13)보다 앞섬 — "표본 외" 주장과 일치.
- 캘린더: 2026-10-26~12-18 사이 휴장 없음(krx.json 33-36행), 11-19 10:00~16:30(56행, `verified: false`). 40거래일은 테스트로도 확인.
- 절차서의 작업 이름·속성(`symbolMaster`, `runLiveWeeklyCheck`, `runLiveReplayCheck`, `-Pexperiment`, `-Pheap`, `installDist` 산출 `autotrade-gateway.bat`), 로그 문구(`시세 배분`, `실험실 시세 전달 소켓을 열었습니다`, `열지 못해 녹화만 합니다`), `symbols/KRX.csv` 헤더 `code,name,board`, `watchlist.json` 형식, REST 경로(`/api/session`, `/api/live/sessions/{id}/start`, `/api/live/status`, `/api/live/finish`)와 상태 필드 이름은 코드와 맞다.

## 판정
수정 필요(N1, N2)

## 재검토 2026-10-19
- 대상: PR #175 / 커밋 834d9246 / main 대비 diff 전체(`git diff origin/main...HEAD`). 첫 검토 뒤 더해진 커밋: d26aed3c(기준선 `kr-bh-baseline@2`, 등록 파일 두 개, D-113·계획 문구, `StageSixRegistrationTest` 기대값), af156511(시작 점검표·계획, 이 검토 N1·N2·R1~R5 반영), 834d9246(`RepositoryProfilesTest` 기준선 예외, D-113·계획 문구). 검토 원문 커밋(adba7da5, 906e4470, 9ff54c68)은 판정 대상 아님.
- 점검표: B
- 직접 실행한 것:
  - 다른 프로세스와 겹치지 않게 `git worktree add --detach`로 스크래치 폴더에 따로 풀어 빌드.
  - af156511에서 `./gradlew build --no-daemon` → **실패**: `:autotrade-sim:test`의 `RepositoryProfilesTest`("kr-bh-baseline@2: 새 스윙 프로필은 exit.max-hold-days(2~20) 필수"). d26aed3c가 들여온 결함이고 834d9246이 이것을 고친 커밋이다.
  - 834d9246에서 `./gradlew build --no-daemon` → **BUILD SUCCESSFUL**(이번에는 Maven 429 없음). 22개 모듈 테스트가 모두 실패·오류·건너뜀 0: app-lab 246, architecture-test 46(ArchUnit 포함), sim 301, marketdata 141, watchdog 93, toss 90, notification 86, collector 79, engine 64, risk 55, kiwoom 51, gateway 50, strategy 44, config 42, experiment 40, monitor-agent 36, selection 34, domain 31, kis 30, control 23, monitor-sources 6, broker-api 1.
  - CI(834d9246, 조회 시점): DB schema success, Secret scan success, Gradle build·Playwright는 진행 중. 판정은 위 로컬 전체 빌드를 근거로 한다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 834d9246 로컬 `./gradlew build` BUILD SUCCESSFUL(별도 worktree, 전체 모듈·ArchUnit·금지 의존 검사 포함) |
| B2 | 통과 | 운영 코드 변경 없음. 바뀐 Java는 `autotrade-sim` 테스트 2개(`StageSixRegistrationTest`, `RepositoryProfilesTest`)뿐. architecture-test 46개 통과 |
| B3 | 통과 | 계약 변경 없음. `strategies/kr-bh-baseline/v2.yaml`은 기존 프로필 형식(ADR-005 4장)으로 새 버전을 더한 것이고, v1은 그대로 둠(불변 버전 규칙 지킴) |
| B4 | 통과 | 시드·기간 고정은 그대로. `BuyAndHold`는 `attempted` 집합으로 종목마다 한 번만 시도하고(BuyAndHold.java:42-43), 순서는 호가 도착 순서. 같은 입력이면 같은 결과이고, slots 10 ≥ boards 10이라 바구니가 도착 순서에 달리지 않음 |
| B5 | 해당 없음 | 금액 코드 변경 없음(`perSlot`은 기존 BigDecimal 코드) |
| B6 | 통과 | `RepositoryProfilesTest` 변경을 약화로 볼지 따로 확인함. 기획서 STR-18 인수 조건이 "기준선(`buy-and-hold`)은 예외"라고 정하고(docs/spec/autotrade-spec.md:207), `buy-and-hold`는 `exit.*` 키를 읽지 않아 `requireAllUsed()`가 거절한다(CatalogStrategyFactory.java:44-46·62, ProfileParams.java:118-121). 따라서 그 로직은 이 검사를 만족할 수 없고, 로직 조건으로 빼는 것이 기획서와 맞음. 다른 스윙 로직에 대한 검사와 `SWING_WITHOUT_MAX_HOLD` 목록은 그대로. 보호 테스트가 아님(`scripts/ci/protected-paths.txt` 밖). 지우거나 건너뛴 테스트 없음 |
| B7 | 해당 없음 | 실전 경로·보호 경로 미접촉. 새로 바뀐 파일(strategies/, experiments/, docs/, autotrade-sim 테스트)은 모두 보호 경로 밖 |
| B8 | 통과 | 비밀값 없음. 점검표의 새 명령도 런타임 값만 읽음. CI Secret scan success |

### 첫 검토 항목 처리
- N1 해결: 점검표 2.2가 `@($st.session.failures | Where-Object { $_ }).Count`로 실패 계좌 수를 세고 0이 아니면 알리게 바뀜. 계획 4.1 I1도 "빈 문자열이 아닌 칸 0개"(LiveSession.java:341-344 근거)로 바뀜.
- N2 해결: 점검표 2.3에 "운용 마지막 날(10-23, 12-18)은 실험실을 끄지 않는다"가 들어갔고, 4장 앞에 409 원인과 다시 여는 순서, `restoring`이 false이고 `session`이 있을 때까지 기다리는 블록이 생김. 5장(12-18)도 "세션이 열려 있는지 먼저 확인"을 가리킴. `LiveLabService.finish`의 두 409 조건(252-256행)과 맞음.
- R1 해결(계획 7장: 3장으로 고치고, 스레드에 올리면 Claude가 커밋하는 것으로 통일). R2 해결(LIVE00 매일 장중 재기동). R3 해결(`$id`를 0장으로 옮김). R4 해결(GC 기록을 저장소 `build\weekly-gc`에 씀, 루트 `.gitignore` 10행 `build/`라 결과의 코드 줄에 dirty가 붙지 않음). R5 해결(기존 `candidates`를 덮어쓴다는 문장, 게이트웨이를 다시 띄우라는 문장).

### 새 발견(권고, 머지 막지 않음)
- R7: 점검표 4장의 대기 블록 `do { … } while ($st.restoring -or -not $st.session)`에는 끝이 없음. 세션 시작이 실패하면(`stopReason`에 `시작 실패`) 영원히 돎. 실험실이 꺼져 있으면 `Invoke-RestMethod` 오류를 내고 이전 `$st`로 계속 돎. 매 회 `stopReason`도 찍거나 `$st.stopReason`이 있으면 멈추게 하면 사용자가 바로 알아챈다. 지금도 5초마다 상태 줄을 찍으므로 사람이 보고 멈출 수는 있다.
- R8: 기준선 v2는 boards 10종목 가운데 실제로 8종목만 산다(000660은 99만 원 배정으로 0주, 035720은 블랙리스트). 계획 R5 문장이 이 점과 리포트 기재를 이미 다룸. 확인만 함.

## 판정(재검토)
승인
