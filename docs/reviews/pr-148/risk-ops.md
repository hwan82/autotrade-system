# risk-ops 검토: D-106 감시 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R1)
- 대상: PR #148 / 헤드 커밋 82e2cb5d71188220fb4f742017c1ac067d0b9a29(브랜치 `claude/project-thread-87sta8`) / main 대비 diff(`git diff origin/main...HEAD`, 머지 기준 c4be949, 파일 8개 +193 −19)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --console=plain`(헤드 82e2cb5): BUILD SUCCESSFUL in 5m 13s. 첫 두 번은 Maven Central `429 Too Many Requests`로 의존성 내려받기 단계에서 실패(코드와 무관), 세 번째 시도 성공. `*/build/test-results/test/*.xml` 합계 1,506건, 실패 0·오류 0·건너뜀 0. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest·NotificationSetupMappingTest·StopRequestPathTest), `:autotrade-app-engine` 55, `:autotrade-app-watchdog` 57, `:autotrade-notification-jar` 86, 모두 실패 0.
  - `./gradlew :autotrade-notification-jar:javadoc`: 종료 코드 0(경고만).
  - `:autotrade-app-watchdog:installDist` 뒤 CI와 같은 단계 재현: `interval-second=5`만 있는 `watchdog.properties` 폴더를 `AUTOTRADE_CONFIG_DIR`로 주고 `bin/autotrade-watchdog` 실행 → 종료 코드 **2**, 로그 `WatchdogMain$StartFailure report` SEVERE 한 줄 + 표준 오류 한 줄(콘솔 로캘 탓에 한글이 `?`로 보였지만 경로와 `interval-second`는 보임).
  - 변이 7개(이 worktree, 한 번에 하나, 각 변이 뒤 `git checkout -- autotrade-app-watchdog`로 원복, 마지막에 작업 트리 깨끗함 확인). 실행 범위 `:autotrade-app-watchdog:test`:
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | M1 | `StartFailure.of`가 거부가 아닌 예외도 종료 코드 2·설정 문구로 | 잡힘(3건) |
    | M2 | `WatchdogSettings.load`의 읽기 실패를 일반 `IllegalArgumentException`으로 | 잡힘(1건, `#설정_파일을_못_읽어도_설정_오류로_거부한다`) |
    | M3 | `os()`의 JVM 거부를 일반 `IllegalArgumentException`으로 | 잡힘(1건) |
    | M5 | `WatchdogSettings.parse`의 설정 오류를 일반 `IllegalArgumentException`으로 | 잡힘(1건) |
    | M4 | `main`의 catch를 `RuntimeException` → `IllegalArgumentException`으로 되돌림 | **생존**(아래 R2) |
    | M6 | `run`의 catch를 `WatchdogRefusal` → `RuntimeException`으로 넓힘 | **생존**(아래 R2) |
    | M7 | 프로그램 오류 로그에서 예외(스택) 빼기 | **생존**(아래 R2) |
  - `git merge-tree --write-tree origin/main HEAD` 충돌 없음. 머지 기준 뒤 main에 들어온 변경은 `docs/qa-out-status.md` 하나뿐(`git diff --name-only c4be949 origin/main`)이라 병합본 빌드는 하지 않았다.

## 검토 범위와 영향
- [사실] 보호 경로 `autotrade-app-watchdog/*`, `autotrade-notification-jar/*`(`scripts/ci/protected-paths.txt`) 변경이라 실전 관련 PR이다. 보호 테스트(`autotrade-architecture-test`, RealTradingGuard·GuardedOrderGateway 시험, 장애 시험)와 고정 값은 diff에 없다.
- [사실] 운영 코드 변경:
  - `WatchdogMain.main`(`WatchdogMain.java:45-50`): catch를 `IllegalArgumentException` → `RuntimeException`으로 넓히고, `StartFailure.of`(`:173-178`)가 `WatchdogRefusal`이면 종료 코드 2·거부 문구, 아니면 종료 코드 1(`EXIT_START_FAILED`, `:36`)·"프로그램 오류" 문구. `report`(`:181-188`)가 로그 ERROR(프로그램 오류면 예외 포함)와 표준 오류 한 줄.
  - `WatchdogMain.run`(`:74`): `WatchdogRefusal`만 2로 바꾸고 나머지는 던짐. 운영 호출자는 없다(`grep WatchdogMain.run` 결과 시험뿐).
  - 새 `WatchdogRefusal`(패키지 전용, `IllegalArgumentException` 상속, 생성자 private). 던지는 곳은 `WatchdogSettings.java:81`(파일 읽기 실패·4KB 초과), `:145`(값 오류 모음), `WatchdogMain.java:151`(JVM 빈 없음) 세 곳뿐이다.
  - `NotificationSetup.ExternalChannel`: javadoc `@throws` 위치만 옮김. 실행 코드 변화 없음(diff에 문장 변경 없음).
- [사실] 기동 거부 조건 자체(무엇이 틀리면 뜨지 않는가)는 바뀌지 않았다. 바뀐 것은 거부의 **표시 문구와 종료 코드(2 → 1, 거부가 아닌 예외만)**와, 예전에는 잡히지 않던 비-IAE 기동 예외를 `System.exit(1)`로 끝내는 점이다. 후자는 오히려 기동 실패 뒤 비데몬 스레드가 남아 JVM이 안 끝나는 경우를 없앤다([추론], `start`가 실패 시 알림 큐·감시를 닫기는 함, `:102-115`).
- [사실] 실전 엔진(`EngineMain`)·주문 경로·Guard 코드는 무변경이다. 감시는 프로세스를 죽이거나 띄우지 않는다(D-058, ArchUnit `WATCHDOG_KILLS_NO_PROCESS` 통과).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·`autotrade-risk`·`EngineMain` 무변경(diff 파일 8개). 빌드 안 `:autotrade-risk:test` 55건, `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 무변경. `autotrade-architecture-test` 무변경, 46건 통과(ArchitectureTest·RealPathReflectionTest 포함) |
| A3 | 해당 없음 | 증권사 연동 무변경. 감시는 증권사를 부르지 않는다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 무변경 |
| A5 | 해당 없음 | 멱등 키 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 무변경 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 통과 | 바뀐 것은 감시 프로세스의 기동 실패 처리뿐이고 실전 엔진과 JVM이 분리돼 있다. 감시가 뜨지 않는 조건은 그대로(설정 오류·JVM 미지원은 2, 그 밖의 기동 예외도 여전히 기동 실패, 종료 코드 1). 감시가 뜨지 않으면 5.2 0번 정지 요청 파일을 못 만드는 공백은 PR 전과 같다. CI 기동 단계(`.github/workflows/ci.yml:37-40`, 틀린 감시 설정 → 2)를 직접 재현해 2 확인 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 무변경. 감시 기동이 막히는 경우의 집합이 늘거나 줄지 않았다(변경은 표시만). 변이 M2·M3·M5가 잡혀 거부 경로가 일반 IAE로 바뀌면 시험이 막는다 |
| A10 | 통과 | 새 출력은 예외 종류·메시지와 설정 파일 경로뿐. 프로그램 오류의 메시지·스택은 PR 전에도 JVM 기본 처리기가 표준 오류에 찍던 것이라 노출이 늘지 않는다. 알림 채널 생성 예외는 `NotificationSetup.built`가 이유 코드만 남기고 메시지를 버린다(PR #144 그대로). 새 시험 값은 가짜 문구뿐(채널 이름 `kakao`/`kakao-x`, `감시 설정 오류(x)`) |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음, 고정 값 변경 없음. 기존 시험은 `isInstanceOf(IllegalArgumentException)` → `WatchdogRefusal`로 **더 좁게** 바뀜(`WatchdogMainTest.java:36`, `:100`). 새 동작 실패 경우 시험: `#알림_채널_코드_실수_같은_예외는_설정_오류로_표시하지_않는다`(`:60`), `#그_밖의_기동_예외도_프로그램_오류로_종료_코드_1이다`(`:72`), `#설정_파일을_못_읽어도_설정_오류로_거부한다`(`:51`), `#기동_실패는_표준_오류에_...`(`:80`). M1·M2·M3·M5 잡힘. `main`의 catch 자체는 시험이 없음(M4 생존, R2) |
| A12 | 통과 | 장애표 #8·#9·#10의 감지 수단(하트비트·정지 요청 파일 시각·종료 코드)과 장애 시험 8종의 전제를 바꾸지 않는다. 감시 종료 코드 2의 뜻(설정 오류·JVM 미지원, D-092 7번·D-095 4번·`config-keys.md:57`·ADR-011)은 그대로이고 새 1은 "0이 아니면 실패"를 보던 예약 작업에 같은 결과다. 수집기 runbook 3.5에 새 문구 대응을 더함(`docs/runbooks/collector.md:62`) |
| A13 | 해당 없음 | 시장별 경로 무변경. 시각은 주입 `Clock` 그대로 |
| A14 | 통과 | D-106(`docs/decisions/D-106-watchdog-start-failure-kinds.md`)과 README 표 한 줄이 코드와 일치: 거부 종류 3곳, 종료 코드 2/1, 문구, `run`이 거부만 2로, javadoc 고침. 시험 이름 6개 모두 실제로 존재하고 통과. 보호 경로라 사용자 머지로 확정한다고 적음. "1은 JVM이 처리되지 않은 예외로 끝낼 때와 같은 값"은 맞다. D-092·D-095의 "종료 코드 2" 기술과 충돌 없음 |

## 점검표 밖 발견
- 위험 시나리오(잘못된 주문·중복 주문·보호 공백을 만들 수 있는가):
  1. **틀린 `watchdog.properties`·4KB 초과·폴더가 파일 자리에 있음**: 여전히 `WatchdogRefusal` → 종료 코드 2, 같은 문구. 보호 공백 변화 없음.
  2. **알림 채널 이름 코드 실수(PR #144 QA 탐침 M2와 같은 상황)**: 이제 종료 코드 1·"프로그램 오류" 문구. 감시가 뜨지 않는 결과는 같고, 운영자를 `watchdog.properties`로 잘못 보내지 않는다. 이 PR의 목적대로 동작.
  3. **감시가 엉뚱하게 "설정 오류 아님"을 말해 운영자가 고칠 수 있는 문제를 개발 쪽으로 넘기고 기다림** → 아래 R1. 감시 부재 시간이 길어질 수 있지만 계좌에는 영향 없음(엔진은 별도 프로세스, 감시는 주문하지 않음).
- R1(권고, 막지 않음): `main`의 try 안에서 `ConfigPaths.defaultLocation()`이 평가된다(`WatchdogMain.java:45`). 이 메서드는 `Path.of(prop|env)`(`ConfigPaths.java:31`, `:35`)를 부르므로 `AUTOTRADE_CONFIG_DIR`·`-Dautotrade.config-dir` 값이 경로 형식에 맞지 않으면 `InvalidPathException`(IAE 하위)이 난다. [추론] Windows에서 환경 변수에 따옴표를 넣은 경우(`"C:\autotrade"`)처럼 운영자 설정 실수로 실제 생길 수 있다. 이 경우 PR 전에는 "감시 설정이 틀려"(역시 정확하진 않음), 이제는 "프로그램 오류(설정 파일 문제가 아닙니다. 개발 쪽에 알려 주세요)"와 종료 코드 1로 나오고, runbook 3.5는 "고치지 말고 개발 쪽에 넘긴다"고 안내한다. 상세 줄에 `InvalidPathException: Illegal char <"> ...`가 붙어 원인은 보이므로 막지 않는다. 다음 감시 PR에서 설정 폴더 해석을 `WatchdogRefusal.settings`(또는 "설정 폴더 경로가 틀림" 문구)로 감싸거나, runbook에 "상세가 `InvalidPathException`이면 `AUTOTRADE_CONFIG_DIR` 값을 확인"을 더하길 권한다. Linux CI에서는 NUL 말고는 재현할 수 없어 직접 실행으로 확인하지 못했고, 코드 읽기로만 판단했다.
- R2(권고, 막지 않음): 변이 M4(`main` catch를 IAE로 되돌림)·M6(`run` catch를 넓힘)·M7(프로그램 오류 로그에서 스택 빼기)이 살아남는다. `main`은 `System.exit`를 불러 시험하지 않았다. M4가 생겨도 IAE 코드 실수는 여전히 `StartFailure.of`로 "프로그램 오류"가 되고 비-IAE만 PR 전처럼 JVM 기본 처리로 가므로 보호는 약해지지 않는다. `main`의 판정·출력을 `static int startOrExitCode(...)` 같은 시험 가능한 메서드로 빼면 M4를 잡을 수 있다.
- 참고: `docs/runbooks/README.md:21`의 감시 행("설정만 읽고 바로 종료, 종료 코드 0")은 감시가 상주 프로세스가 된 뒤로 낡았다. 이 PR이 만든 문제는 아니다.
- 참고: PR 브랜치는 main보다 문서 1커밋 뒤(`docs/qa-out-status.md`), 충돌 없음.

## 판정
승인

## 재검토 2026-10-17
- 대상: PR #148 / 헤드 커밋 c6cd841f9c5f99ef56ddff718e1b3d2968a022c8(브랜치 `claude/project-thread-87sta8`) / main 대비 diff(`git diff origin/main...HEAD`, 머지 기준 c4be949, 파일 11개 +352 −23, 검토 원문 2개 포함). 앞 검토(82e2cb5) 뒤 커밋은 e692f2d(이 원문, 앞 검토 커밋 ec025aa와 내용 같음), fcea7b3(architect 원문), c6cd841(코드·문서)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --console=plain`(헤드 c6cd841): BUILD SUCCESSFUL in 42s(바뀌지 않은 모듈은 Gradle 최신 판정으로 다시 돌지 않음). 그래서 `./gradlew :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-app-watchdog:test :autotrade-notification-jar:test --rerun`을 따로 돌림: 종료 코드 0. 결과 XML 합계 1,510건, 실패·오류·건너뜀 0. 모듈별 risk 55, architecture-test 46, app-engine 55, app-watchdog 61(앞 검토보다 4건 늘어남), notification-jar 86.
  - `:autotrade-app-watchdog:installDist` 뒤 CI 단계 재현(틀린 `watchdog.properties`, `AUTOTRADE_CONFIG_DIR`): 종료 코드 **2**.
  - 변이 4개(한 번에 하나, 각 변이 뒤 `git checkout -- autotrade-app-watchdog`로 원복, 마지막에 작업 트리 깨끗함 확인), 실행 범위 `:autotrade-app-watchdog:test`:
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | M4' | `launch`의 catch를 `RuntimeException` → `IllegalArgumentException`(앞 검토 M4와 같은 뜻) | 잡힘(1건, `#진입점은_설정_오류가_아닌_기동_예외를_종료_코드_1로_끝낸다`) |
    | M6 | `run`의 catch를 `WatchdogRefusal` → `RuntimeException` | 잡힘(1건, `#기동_점검은_...던진다`) |
    | M8 | `configPaths`가 `InvalidPathException`을 거부로 바꾸지 않고 그대로 던짐 | 잡힘(1건, `#진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다`) |
    | M9 | `launch`가 판정과 상관없이 늘 종료 코드 1을 넘김 | 잡힘(2건) |
  - `git merge-tree --write-tree origin/main HEAD` 충돌 없음. 머지 기준 뒤 main 변경(`autotrade-sim` 수집 신선도, 문서, `docs/reviews/pr-147`)은 이 PR 파일과 겹치지 않아 병합본 빌드는 하지 않았다.

### 바뀐 것과 영향
- [사실] `main`(`WatchdogMain.java:44`)이 `launch(ConfigPaths::defaultLocation, Clock.systemUTC(), PlatformOsReadings::create, System::exit)`를 부르고 null이면 끝난다. `launch`(`:63-71`)는 앞 검토의 `main` catch와 같은 판정(`StartFailure.of` → `report` → `exit`)이고, 종료 함수를 바꿔 끼울 수 있어 시험이 진입점의 종료 코드를 직접 본다. 운영에서는 `System::exit`라 `exit` 뒤 null 반환에 닿지 않는다.
- [사실] `configPaths`(`:78-84`)가 설정 폴더 공급자의 `IllegalArgumentException`(경로 형식 오류 `InvalidPathException` 포함)을 `WatchdogRefusal.settings("설정 폴더 경로가 틀렸습니다(...)")`로 바꾼다. 앞 검토 R1을 해결한다. `ConfigPaths.in`이 던질 수 있는 다른 IAE("일반 설정과 비밀값은 다른 파일", `ConfigPaths.java`)는 `in(dir)`로는 닿지 않는다. NPE(`user.home` 없음)는 프로그램 오류 1로 남는데, 사람이 고칠 값이 아니라 맞는 분류다.
- [사실] 기동 거부 조건과 감시 기동 순서(`start`)는 바뀌지 않았다. 바뀐 것은 설정 폴더 경로 오류를 1이 아닌 2로 표시하는 것 하나다. 감시를 띄우는 경우·막는 경우의 집합은 그대로다.
- [사실] `Clock.systemUTC()`는 여전히 진입점 한 곳에서만 만든다(ArchitectureTest 통과).
- [사실] 문서: D-106에 4번(설정 폴더 경로 오류도 거부)과 남은 위험(폴더 쓰기 권한 같은 I/O 실패는 1로 표시), 시험 이름 4개, M7을 시험하지 않는 이유를 더했다. `docs/runbooks/README.md:21` 감시 행을 상주 프로세스로 고쳐 앞 검토의 참고 사항을 반영했다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·`autotrade-risk`·`EngineMain` 무변경. `:autotrade-risk:test` 55건 다시 돌려 통과, `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 무변경. `autotrade-architecture-test` 무변경, 46건 다시 돌려 통과 |
| A3 | 해당 없음 | 증권사 연동 무변경. 감시는 증권사를 부르지 않는다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 무변경 |
| A5 | 해당 없음 | 멱등 키 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 무변경 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 통과 | 감시 프로세스의 기동 실패 표시만 바뀌고 실전 엔진은 별도 JVM이다. 감시가 뜨는 조건과 막히는 조건은 그대로다. CI 단계(`.github/workflows/ci.yml:37-40`)를 재현해 2 확인 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 무변경. 거부 경로 변이(앞 검토 M2·M3·M5, 이번 M8)를 시험이 잡는다 |
| A10 | 통과 | 새 문구는 설정 폴더 경로와 예외 메시지뿐이다(경로는 비밀값 아님). 새 시험 값은 `"C:\autotrade"`, 채널 이름 `kakao`/`kakao-x`, "코드 실수"뿐 |
| A11 | 통과 | 보호 테스트 삭제·약화·고정 값 변경 없음. 새 시험 4개(`WatchdogMainTest.java:72`, `:81`, `:92`, `:107`)가 진입점 종료 코드 2·1, 설정 폴더 경로 거부, 기동 점검의 예외 전파를 실패하는 경우와 함께 확인한다. 앞 검토에서 살아남은 M4·M6을 이제 잡는다. M7(프로그램 오류 로그의 스택)만 시험 밖이고 이유가 D-106에 적혀 있다 |
| A12 | 통과 | 장애표 #8·#9·#10의 감지 수단과 장애 시험 8종의 전제가 그대로다. 종료 코드 2의 뜻은 "사람이 고칠 설정·JVM 문제"로 같고, 그 안에 설정 폴더 경로가 더해졌다. runbook README·수집기 runbook 3.5가 2/1 구분과 맞는다 |
| A13 | 해당 없음 | 시장별 경로 무변경. 시각은 주입 `Clock` 그대로 |
| A14 | 통과 | D-106 4·5번, 위험과 한계, 검증 목록이 코드·시험 이름(실제로 있고 통과)과 일치한다. 결정 내용(종료 코드 나눔)은 같고 범위만 넓어져 같은 번호에 반영한 것은 타당하다 |

### 점검표 밖 발견
- 앞 검토 R1(설정 폴더 경로 오류가 "프로그램 오류"로 보임): 해결. `configPaths`가 거부로 바꾸고, `#진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다`와 변이 M8로 확인했다. Windows 실제 환경 변수로는 재현하지 않았고, 공급자가 `InvalidPathException`을 던지는 시험으로 확인했다.
- 앞 검토 R2(M4·M6·M7 생존): M4·M6 해결. M7은 남지만 표준 오류 한 줄에 예외 종류와 메시지가 남아 보호와 무관하다. 그대로 둬도 된다.
- 참고(막지 않음): 설정 폴더 경로 오류의 첫머리 문구는 `WatchdogRefusal.SETTINGS_HEADLINE` "감시 설정이 틀려 기동하지 않습니다"이다. 수집기 runbook 3.5는 이 문구를 보면 "`watchdog.properties`를 고친다"고 안내한다. 상세 줄의 "설정 폴더 경로가 틀렸습니다(autotrade.config-dir 또는 AUTOTRADE_CONFIG_DIR)"로 무엇을 고칠지는 알 수 있다. 다음에 runbook을 고칠 때 "상세에 설정 폴더 경로가 나오면 환경 변수를 고친다"를 한 줄 더하면 더 정확하다.
- 참고: 이 재검토는 docs/reviews/README.md("재검토는 같은 파일 아래에 `## 재검토 <날짜>`로 덧붙인다")를 따라 이 파일에 덧붙였다. 별도 파일(`risk-ops-2.md`)을 만들라는 요청은 검토 규칙(원문 보존 형식)과 달라 따르지 않았다.

### 판정
승인
