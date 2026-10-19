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
