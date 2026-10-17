# QA 검토: D-106 감시 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R1)
- 대상: PR #148 / 헤드 커밋 d632f77be6b4d5bbfee2e2d316cce8051de227fa(브랜치 `claude/project-thread-87sta8`) / main 대비 diff(`git diff origin/main...HEAD`, 머지 기준 c4be949, 파일 13개 +433 −24)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks`(헤드 d632f77, 미러 init 스크립트 없이): 첫 시도는 Maven Central 429(`spring-boot-dependencies:3.5.16` pom)로 의존성 받기 실패 → 20초 뒤 재시도 1회째에 BUILD SUCCESSFUL in 4m 18s, 138 tasks executed. `build/test-results/test/*.xml` 합계 1,510건, 실패 0·오류 0·건너뜀 0. `WatchdogMainTest` 14건 통과. 429 실패는 빌드 실패로 보지 않았다(의존성 서버 응답).
  - `./gradlew :autotrade-notification-jar:javadoc`: 종료 코드 0(경고 100건, 오류 없음). PR이 고쳤다는 `@throws` 오류가 없음을 확인.
  - GitHub CI(헤드 d632f77) 4개 모두 success: Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary, Lab screens (Playwright).
  - `git merge-tree --write-tree origin/main HEAD` 충돌 없음. 머지 기준 뒤 main(1bb2f3e)에 들어온 파일 7개(`autotrade-sim` 수집 신선도, 문서, `docs/reviews/pr-147`)는 이 PR 파일과 겹치지 않는다(`comm` 교집합 없음). 병합본 빌드는 하지 않았다.
  - 변이 10개(QA가 새로 고름. 각 변이 뒤 `git checkout --`로 원복, 작업 트리 깨끗함 확인). 실행 `./gradlew :autotrade-app-watchdog:test --rerun --offline`:
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | Q-M1 | `WatchdogSettings.load`의 읽기 실패를 `WatchdogRefusal` 대신 일반 `IllegalArgumentException`으로 | 잡힘 |
    | Q-M2 | `WatchdogSettings.parse`의 설정 오류를 일반 `IllegalArgumentException`으로 | 잡힘 |
    | Q-M3 | `os()`의 JVM 미지원을 일반 `IllegalArgumentException`으로 | 잡힘 |
    | Q-M4 | `StartFailure.of`가 프로그램 오류에도 종료 코드 2 | 잡힘 |
    | Q-M5 | `configPaths`의 catch를 `IllegalArgumentException` → `RuntimeException`으로 넓힘 | 생존(아래 참고, 현재 공급자로는 같은 동작) |
    | Q-M6 | `configPaths` 거부 문구에서 "설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR): " 삭제 | **생존**(Q2) |
    | Q-M7 | `run`의 `WatchdogRefusal` catch가 0을 돌려줌 | 잡힘(3건: `#감시_설정이_틀리면_기동을_거부한다`, `#설정_파일을_못_읽어도_설정_오류로_거부한다`, `#PC_CPU_메모리를_줄_수_없는_JVM이면_기동을_거부한다`) |
    | Q-M8 | `report`의 표준 오류 줄 첫머리를 늘 프로그램 오류 문구로 | 잡힘 |
    | Q-M9 | 프로그램 오류 로그에서 예외 흐름(스택) 빼기(risk-ops M7과 같음) | 생존(D-106 검증 절에 시험하지 않는 이유가 적혀 있음) |
    | Q-M10 | `launch`에서 `failure.report(e)` 호출 삭제(종료 코드만 넘기고 아무것도 출력하지 않음) | **생존**(Q1) |
  - 설치본 종단 확인(`:autotrade-app-watchdog:installDist`, `AUTOTRADE_CONFIG_DIR`만 바꿔 실행):
    - S1 모르는 키(`interval-second=5`, CI 단계와 같음): 종료 코드 2, 표준 오류 `감시 설정이 틀려 기동하지 않습니다(감시 없이 실전·실험을 돌리지 마세요): 감시 설정 오류(...): 1번째 줄: 모르는 키입니다(오타?): interval-second`.
    - S2 `watchdog.properties` 5,000바이트(상한 4,096 초과): 종료 코드 2, 같은 첫머리와 "감시 설정 파일을 읽지 못했습니다 ... IOException: 파일이 너무 큽니다".
    - S3 설정 폴더 자리에 일반 파일: 종료 코드 2, 같은 첫머리와 "설정 폴더를 만들지 못했습니다".
    - S4 정상 빈 설정 폴더: 8초 뒤 시간 초과(124)까지 살아 있고 `heartbeat/watchdog.hb` 생김.
    - S5 `heartbeat` 자리에 일반 파일: 기동 실패가 아니라 감시가 뜬 채로 세 프로세스 하트비트 `UNREADABLE` 긴급 알림을 냄(이 PR 범위 밖 동작, 기동 실패 분류와 무관).
    - 프로그램 오류(종료 코드 1)는 설치본에 주입할 입력이 없어 종단으로는 재현하지 않았다. `#진입점은_설정_오류가_아닌_기동_예외를_종료_코드_1로_끝낸다`가 `launch`로 확인한다.

## 검토 범위와 영향
- [사실] 보호 경로 `autotrade-app-watchdog/*`(`scripts/ci/protected-paths.txt:9`)와 `autotrade-notification-jar/*`(`:16`) 변경이라 실전 관련 PR이다. 보호 테스트(`autotrade-architecture-test` 모듈, RealTradingGuard·GuardedOrderGateway 시험, 장애 시험)는 diff에 없다.
- [사실] 운영 코드 변경: `WatchdogMain.main`(`WatchdogMain.java:44`)이 `launch`(`:63-71`)를 부르고, `launch`는 `RuntimeException`을 모두 받아 `StartFailure.of`(`:196`) → `report`(`:204`) → `exit`. `configPaths`(`:78-84`)가 설정 폴더 공급자의 `IllegalArgumentException`(`InvalidPathException` 포함)을 `WatchdogRefusal.settings`로 바꾼다. `run`(`:97`)은 `WatchdogRefusal`만 종료 코드로 바꾼다. `WatchdogSettings.load`(`WatchdogSettings.java:81`)·`parse`(`:145`)와 `os()`(`WatchdogMain.java:174`)가 `WatchdogRefusal`을 던진다. `NotificationSetup.ExternalChannel`의 javadoc만 옮김(동작 무변경).
- [사실] 감시 기동 순서(`start`: 설정 로딩 → 감시 설정 → JVM 검사 → 알림 큐 → 감시 객체 → 시작)는 바뀌지 않았다. 감시가 뜨는 경우·막히는 경우의 집합은 그대로이고 바뀐 것은 표시 문구와 종료 코드(설정 폴더 경로 오류 1→2, IAE 코드 실수 2→1, 비IAE 기동 예외는 JVM 기본 스택 1 → `report` 후 1)다.
- [사실] 설정 값에서 오는 예외가 "프로그램 오류"로 새는 경로를 찾았다: `WatchdogSettings.parse`는 모든 값 문제를 `problems`에 모아 끝에서만 던지고(`:145`), 경로 형식 오류(`InvalidPathException`)와 폴더 만들기 실패도 `problems`로 받는다(`disks`). 알림 설정 값 오류는 `NotificationSetup.built`의 try(`NotificationSetup.java:291-295`)가 이유 코드로 바꿔 기동 예외가 되지 않는다. `ConfigLoader`는 `runtime-config.json`·`secrets.json` 오류를 경고로 바꾼다. 그래서 운영자가 고칠 설정 파일 값으로 종료 코드 1이 나는 경로는 찾지 못했다. 남는 것은 D-106 "위험과 한계"에 적힌 기동 중 I/O 실패(폴더 권한 등)뿐이다.
- [사실] `Watchdog`(`Watchdog.java:261`)·`NotificationQueue`(`NotificationQueue.java:117`)의 "이미 시작했습니다" `IllegalStateException`은 코드 실수라 프로그램 오류 분류가 맞다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard·`autotrade-risk`·`EngineMain` 무변경(diff 파일 목록). 전체 빌드에서 `autotrade-risk` 시험 통과, `EngineMainTest` 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·`autotrade-architecture-test` 무변경. 전체 빌드에서 ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest 통과. 새 클래스 `WatchdogRefusal`은 기존 패키지 `com.autotrade.app.watchdog` 안 |
| A3 | 해당 없음 | 증권사 연동 무변경. 감시는 증권사를 부르지 않는다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 무변경 |
| A5 | 해당 없음 | 멱등 키 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 경로 무변경 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 통과 | 바뀐 것은 별도 JVM인 감시 프로세스의 기동 실패 표시뿐이다. 실패 시 `exit`로 바로 끝나 비데몬 스레드가 남지 않는다(`launch`, `:66-70`). 설치본 S1~S4에서 종료 코드 2/정상 기동 확인 |
| A9 | 통과 | 감시가 기동을 거부하는 조건은 그대로다(Q-M1·Q-M2·Q-M3·Q-M7 잡힘, S1~S3 종료 코드 2). 감시가 뜨지 않는 경우가 늘거나 줄지 않았다 |
| A10 | 통과 | 새 문구는 설정 폴더 경로·감시 설정 파일 경로·예외 종류와 메시지뿐이다. 프로그램 오류 로그에 예외 흐름이 새로 남지만, 알림 채널 비밀값이 닿는 생성 예외는 `NotificationSetup.built`가 삼키고(`NotificationSetup.java:291-295`) 이유 코드만 남긴다. 새 시험 값은 `"C:\autotrade"`, `kakao`/`kakao-x`, "코드 실수"뿐. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·약화·고정 값 변경 없음. 기존 `WatchdogMainTest` 두 시험은 단언을 `IllegalArgumentException` → `WatchdogRefusal`로 좁혔다(강화). 새 시험 8개(`WatchdogMainTest.java:51`, `:60`, `:72`, `:81`, `:92`, `:107`, `:115`, `:123`)가 실패 경우를 포함한다. 생존 변이 Q-M6·Q-M10은 아래 Q1·Q2(표시 문구, 보호 동작 아님) |
| A12 | 통과 | 장애표 #8·#9·#10의 감지 수단(감시 하트비트·정지 요청)과 장애 시험 8종의 전제가 그대로다. CI 단계 "틀린 감시 설정 → 종료 코드 2"(`.github/workflows/ci.yml:37-40`)를 S1로 재현해 2 확인. runbook README 감시 행·수집기 runbook 3.5가 2/1 구분과 맞는다 |
| A13 | 해당 없음 | 시장별 경로 무변경. 시각은 주입 `Clock` 그대로(진입점 `Clock.systemUTC()` 한 곳, ArchitectureTest 통과) |
| A14 | 통과 | D-106 1~5번이 코드와 일치한다(`WatchdogRefusal` 두 첫머리 문구, 종료 코드 2/1, `configPaths`, `run`의 전파). D-106 검증 절의 시험 이름 10개가 모두 있고 통과. 결정 로그 표에 D-106 행 있음 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 1,510건 실패 0 |
| B6 | 통과 | 새 동작(거부 종류, 진입점 종료 코드, 설정 폴더 경로 거부, `run` 전파, 표준 오류 출력)마다 시험이 있고 변이 6개를 잡는다. 건너뛰거나 지운 시험 없음(`@Disabled` 추가 없음) |

## 점검표 밖 발견
- PR #144 QA R1(감시 main이 모든 IAE를 "감시 설정이 틀려"·종료 코드 2로 표시): **해결**. 알림 채널 이름 코드 실수 IAE는 이제 종료 코드 1·"프로그램 오류"(`#진입점은_설정_오류가_아닌_기동_예외를_종료_코드_1로_끝낸다`, Q-M4 잡힘).
- PR #144 QA Q1(`NotificationSetup.built` catch를 IAE로 좁히는 변이 M7 생존): 이 PR 범위 밖이라 그대로다(`built` 무변경). 현재 입력으로 같은 동작이라 막지 않는다.
- PR #144 QA R2(엔진은 IAE 미포착): D-106 "위험과 한계"에 범위 밖으로 적혀 있다. 그대로다.
- Q1(권고): Q-M10 생존. `launch`에서 `report` 호출을 지워도 시험이 모두 통과한다. 그러면 감시가 기동에 실패하면서 콘솔·로그에 아무 줄도 남기지 않고 종료 코드만 남는다. `#기동_실패는_표준_오류에...`는 `StartFailure.report`를 직접 부르고, `launch` 시험들은 종료 코드만 본다. `launch` 시험 하나에서 표준 오류 첫머리 문구를 확인하면 잡힌다.
- Q2(권고): Q-M6 생존. 수집기 runbook 3.5가 운영자에게 "그 줄 뒤에 '설정 폴더 경로가 틀렸습니다'가 나오면 환경 변수를 고친다"고 안내하는데, 이 문구를 확인하는 시험이 없다(`#진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다`는 종료 코드만 본다). 문구가 바뀌면 운영자는 `watchdog.properties`부터 고치게 된다.
- 참고: Q-M5(catch를 `RuntimeException`으로 넓힘)는 생존하지만 `ConfigPaths.defaultLocation`이 IAE 말고 던질 수 있는 것은 `user.home` 없음의 NPE 정도라 결과 차이가 사실상 없다.
- 위험 시나리오 시도(입력·순서·장애):
  1. 설정 값 오류가 "프로그램 오류(설정 파일 문제가 아닙니다, 고치지 말고 개발 쪽에 넘김)"로 잘못 보이는가: 위 "검토 범위와 영향"처럼 설정 파일 값 경로는 모두 거부(2)나 이유 코드로 가 찾지 못했다. S1~S3로 확인. 기동 중 I/O 권한 실패만 1로 보이며 D-106에 적혀 있고, 어느 쪽이든 감시는 뜨지 않는다.
  2. 실패 표시 경로 자체가 예외를 내 감시가 반쯤 뜬 상태로 남는가: `report`는 `LOG.log(Level, String[, Throwable])`만 써서 메시지의 `{`·`}`가 형식 오류를 내지 않는다(이전 `{0}` 형식과 달리 경로를 그대로 붙임). 알림 큐·감시 객체를 만든 뒤의 실패는 `start`가 닫고 다시 던진다(`WatchdogMain.java` `start`의 두 try). `launch`가 `exit`를 부르므로 남는 스레드가 없다. `Error`(예: `IOError`)는 `RuntimeException`이 아니라 잡히지 않고 PR 전과 같이 JVM 기본 처리(종료 코드 1)다.
  3. 기동 점검(`run`)과 실제 기동(`launch`)의 판정이 갈리는가: 둘 다 `StartFailure.of`를 쓰고, 거부 종류만 2로 바꾼다. `run`은 시험 전용 진입이라(운영 호출처 없음, `grep`) 운영 영향 없음. Q-M7 잡힘.
  - 세 시나리오 모두 잘못된 주문·중복 주문·보호 공백으로 이어지는 경로는 찾지 못했다. 감시는 주문을 내지 않고, 이 PR은 감시가 뜨는 조건을 바꾸지 않는다.

## 판정
승인

권고 Q1(`launch` 출력 시험), Q2(설정 폴더 경로 문구 시험)는 막지 않는다. 머지는 사용자가 한다.
