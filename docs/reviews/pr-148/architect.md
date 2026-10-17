# architect 검토: D-106 감시 기동 실패를 설정 오류와 프로그램 오류로 나눠 표시(PR #144 QA R1)
- 대상: PR #148 / 커밋 82e2cb5d71188220fb4f742017c1ac067d0b9a29 / `git diff origin/main...HEAD` 전체(파일 8개, +193 −19). PR 브랜치는 main보다 1커밋 뒤(29a2c9d, `docs/qa-out-status.md` 한 줄)이고 겹치는 파일 없음.
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` → 처음 두 번은 Maven Central 429(Too Many Requests, `kotlin-sam-with-receiver-compiler-plugin-embeddable`·`jackson-core` 내려받기)로 실패. 코드 원인 아님. `./gradlew build --offline`으로 캐시를 채운 뒤(이때는 `objenesis` 캐시 없음으로 `:autotrade-app-lab:test` 해석 실패) 다시 `./gradlew build --continue` → **BUILD SUCCESSFUL**, 138 tasks. 시험 결과 XML 합계 1506개, 실패·오류·건너뜀 0. `WatchdogMainTest` 10개 모두 통과(새 시험 4개 포함).
  - `./gradlew :autotrade-notification-jar:javadoc :autotrade-app-watchdog:javadoc` → 통과. 같은 명령을 main의 `NotificationSetup.java`로 바꿔 돌리면 `NotificationSetup.java:89: error: invalid use of @throws`로 실패(PR 설명의 "main에서도 javadoc 실패" 주장 확인). 파일은 `git checkout`으로 되돌림.
  - `:autotrade-app-watchdog:installDist` 후 설치본 실행: 틀린 키(`interval-second=5`, CI 단계와 같은 입력) → 종료 코드 2, 첫머리 "감시 설정이 틀려 기동하지 않습니다". `watchdog.properties` 자리에 폴더 → 종료 코드 2, "감시 설정 파일을 읽지 못했습니다 … (IOException: Is a directory)". (콘솔 한글은 이 컨테이너 로캘 탓에 `?`로 보였고 바이트 확인은 시험이 함)
  - 변이 1개: `WatchdogSettings.java:145`를 `throw new IllegalArgumentException(...)`로 되돌림 → `:autotrade-app-watchdog:test` 57개 중 1개 실패(`WatchdogMainTest#감시_설정이_틀리면_기동을_거부한다`). 되돌린 뒤 작업 트리 깨끗함 확인.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build --continue` 직접 실행, BUILD SUCCESSFUL. 앞선 실패는 Maven Central 429와 오프라인 캐시 부족뿐이고 컴파일·시험 실패 없음 |
| B2 | 통과 | 새 클래스 `WatchdogRefusal`은 `autotrade-app-watchdog`의 기존 패키지 `com.autotrade.app.watchdog` 안, 패키지 전용(`WatchdogRefusal.java:11`). 새 의존 없음. 알림 모듈 변경은 Javadoc 위치만(`NotificationSetup.java:89-96`). ArchUnit·`verifyProcessIsolation`·`verifyPureDependencies` 빌드 안에서 통과 |
| B3 | 통과 | 공개 계약 무변경: `WatchdogMain.run/start` 시그니처 그대로, 새 타입은 패키지 전용. 설정 키·스키마·REST 무변경. 바뀐 외부 동작은 감시 프로세스 종료 코드(설정·JVM 거부 외 기동 예외: 처리되지 않은 예외/IAE→2 에서 1로)와 콘솔 문구이며 결정 기록 D-106(대안 4개·되돌릴 조건·위험)과 결정 로그 표(`docs/decisions/README.md:112`), 수집기 runbook 3.5(`docs/runbooks/collector.md:62`)를 갱신. 종료 코드 2의 뜻(ADR-011:25, D-092 7번, CI `ci.yml:37-40`)은 그대로라 ADR 갱신 불필요로 판단 |
| B4 | 해당 없음 | 시뮬레이션·시계·난수 코드 무변경. `Clock.systemUTC()`는 기존대로 진입점 한 곳(`WatchdogMain.java:45`). 골든 시험 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작 시험: `WatchdogMainTest#설정_파일을_못_읽어도_설정_오류로_거부한다`, `#알림_채널_코드_실수_같은_예외는_설정_오류로_표시하지_않는다`, `#그_밖의_기동_예외도_프로그램_오류로_종료_코드_1이다`, `#기동_실패는_표준_오류에_첫머리_문구와_원인을_한_줄로_남긴다`. 기존 `#감시_설정이_틀리면_기동을_거부한다`·`#PC_CPU_메모리를_줄_수_없는_JVM이면_기동을_거부한다`는 `IllegalArgumentException`→`WatchdogRefusal`로 단언을 **좁혔다**(약화 아님). 위 변이를 실제로 잡음. 지우거나 끈 시험 없음. 빈틈(막지 않음): `main`의 `catch (RuntimeException)`→`System.exit` 배선 자체와 `run`이 비거부 예외를 던지는 경로는 직접 시험이 없다(`StartFailure` 단위 시험으로 판정·출력은 덮음) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-watchdog/*`, `autotrade-notification-jar/*`는 보호 경로(`scripts/ci/protected-paths.txt`). 감시는 장애표 #8·#9·#10의 감지 수단이고 이 PR은 감시 기동 실패 처리(종료 코드)를 바꾼다. 지침 10·11장에 따라 risk-ops 검토, 사용자 직접 머지, 머지 전 독립 QA 대상. 이 판정은 architect 범위에 한한다 |
| B8 | 통과 | 새 문구는 고정 문장 + 예외 종류·메시지(`WatchdogMain.java:173-187`). 프로그램 오류 경로는 이제 예외 흐름을 로그에 남기지만, 바뀌기 전에도 비IAE 기동 예외는 JVM 기본 처리기로 같은 스택을 표준 오류에 찍었다. 알림 채널 꾸리기 예외(웹훅 URL 등 비밀값이 들 수 있는 곳)는 `NotificationSetup.built`(`NotificationSetup.java:293-301`)가 메시지를 버리고 이유 코드로 바꾸며, `SlackWebhookChannel.java:41-43`·`KakaoMemoChannel.java:97-99`는 원인 예외를 잇지 않아 스택에도 URL이 실리지 않음. 키·토큰·계좌번호가 코드·문서·커밋에 없음 |

## 점검표 밖 발견
- N1(권고, 막지 않음): "WatchdogRefusal이 아니면 프로그램 오류"라는 나눔 때문에, 사람이 고칠 수 있는 **환경** 문제 몇 가지가 "설정 파일 문제가 아닙니다. 로그의 예외를 개발 쪽에 알려 주세요"(종료 코드 1)로 표시된다. [사실] ① `AUTOTRADE_CONFIG_DIR`·`autotrade.config-dir` 값이 경로 형식이 아니면 `ConfigPaths.defaultLocation()`(`ConfigPaths.java:31,35`의 `Path.of`)이 `InvalidPathException`을 던지고 `main`의 try 안이라 이 경로로 간다(바뀌기 전에는 IAE라 "감시 설정이 틀려"·2). ② 하트비트·알림 상태 폴더 쓰기 권한 같은 기동 중 I/O 실패도 `WatchdogRefusal`이 아니다. [추론] 세부 문구에 예외 종류와 경로가 붙어 원인은 보이고, 어느 쪽이든 감시는 뜨지 않으므로(0이 아님) 보호는 약해지지 않는다. D-106 "위험과 한계"에 "환경(경로·권한) 문제도 프로그램 오류로 보일 수 있다" 한 줄을 보태거나, 다음 감시 PR에서 `InvalidPathException`을 설정 폴더 거부로 바꾸길 권한다.
- N2(기록): `run`은 이제 `WatchdogRefusal`만 잡고 나머지는 던진다(D-106 4번). 호출자는 시험뿐이고(`WatchdogMainTest:32,39,54,102`) 운영 진입점은 `main`→`start`라 영향 없음. `ProcessStartup.start`(`ConfigLoader`)는 깨진 파일을 경고로 넘기고 던지지 않아(`ConfigLoader.java:68,83,97`) 이 변경으로 동작이 바뀌는 입력은 없다.
- N3(기록): 비IAE 기동 예외가 이제 `System.exit(1)`로 끝나므로, 바뀌기 전처럼 비데몬 스레드가 남아 JVM이 안 끝날 여지가 없어졌다(개선). 종료 코드 1은 JVM의 처리되지 않은 예외 종료와 같은 값이라 "0이 아니면 실패"로 보는 예약 작업 판정은 그대로다.
- 대안 검토(D-106 표, A~D)와 버린 이유가 타당하다. 특히 B(알림 호출만 감싸기)를 버리고 던지는 곳에서 종류를 정한 A가, 기동 단계를 더할 때 같은 실수를 막는 데 맞다.
- `LOG.log(Level, String)`·`LOG.log(Level, String, Throwable)`은 메시지 형식화를 하지 않으므로, 예전 `{0}` 자리표시자를 문자열 이어 붙이기로 바꾼 것이 세부 문구의 `{`·`'` 문자로 깨지지 않는다(확인).

## 판정
승인 (architect 범위. B7에 따라 risk-ops 검토·사용자 머지·독립 QA가 별도로 필요)
