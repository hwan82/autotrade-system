# 독립 QA 검토: PR #122 감시 PC CPU·메모리를 JDK 읽기로만 감시(D-095, Q-053 사용자 결정 ①)
- 대상: PR #122 / 헤드 49ad5b9 / main 대비 diff 전체(`git diff origin/main...pr-122`, 파일 26개 +855 −39: 보호 테스트 `RealPathReflectionTest`와 새 고정 예제 `OsMetricsProbe`, 감시 `OsReadings`·`PlatformOsReadings`·`OsMetricsSource`(새), `Watchdog`·`WatchdogMain`·`WatchdogSettings`·`HeartbeatWatch`·`DiskFreeSource`, 시험 6개 파일, D-095·결정 로그·기획서 v14.38·ADR-002 9.6·ADR-011·config-keys·장애 설계 5.2 0번·#10·pending.md, 개발 쪽 검토 원문 2개). 병합 기준 main 7dfe46d = 현재 origin/main(검토 시점 재확인)
- 점검표: A 전체 + B1, B6 + 과금 검사 (실전 관련 PR: 보호 테스트 `RealPathReflectionTest` 허용 목록 추가, 감시·장애 대응 #10)
- 보호 테스트 변경: **있음**. `RealPathReflectionTest`에 `ALLOWED_OS_METRIC_READERS`(1개)·`OS_METRIC_METHODS`(3개 타입) 추가, 동적 코드 패키지 검사를 ArchUnit 기본 `resideInAnyPackage`에서 허용 클래스·허용 타입만 빼는 사용자 조건으로 교체, 상수 풀·서명 검사에 같은 예외, 고정 목록 시험과 새 시험 1건. 삭제·`@Disabled`·단언 완화 없음
- 개발 쪽 원문(architect·risk-ops 1차·재검토)과 PR 본문의 주장은 확인할 주장으로만 봤다. 판정은 아래 직접 실행 결과와 코드로 냈다
- 직접 실행한 것:
  - `./gradlew build`(헤드 49ad5b9): Maven Central 429로 10회 실패 뒤 11회째 **BUILD SUCCESSFUL**. 결과 XML 합계 **1,300건**, 실패·오류·건너뜀 0(`RealPathReflectionTest` 10건, `ArchitectureTest`, `ModuleOwnershipTest`, `HeartbeatTest`의 3GB 희소 파일 시험 포함)
  - CI(헤드 49ad5b9): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary 모두 success
  - 보호 테스트 변이 M1~M8, 감시 동작 변이 W1~W7(운영 코드 한 곳씩 고치고 해당 모듈 시험, 매번 원복, `git status` 깨끗함 확인), 설치본 탐침 T1(`installDist` 뒤 임시 설정 폴더, 끝나고 지움), JDK 값 탐침 J1: 아래

## 탐침과 변이 결과
| # | 시도(감시 운영 코드에 넣음) | 결과 |
|---|---|---|
| M1 | `PlatformOsReadings`에서 `bean.getProcessCpuLoad()` | 잡힘: `calls method <com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()>`(`실전_경로_모듈은_리플렉션을_쓰지_않는다`) |
| M2 | `PlatformOsReadings`에서 `ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class)` | 잡힘(`getPlatformMXBean`) |
| M3 | `PlatformOsReadings`에서 `UnixOperatingSystemMXBean`으로 형변환 뒤 `getOpenFileDescriptorCount()` | 잡힘(허용 밖 타입) |
| M4 | `PlatformOsReadings`에서 `bean.getObjectName()`(허용 타입의 상속 메서드) | 잡힘: 메서드 표 밖 + `javax.management.ObjectName` 허용 목록 밖 타입 |
| M5 | `PlatformOsReadings`의 중첩 클래스에서 `getCpuLoad()`(허용 메서드, 다른 클래스 이름) | 잡힘(`PlatformOsReadings$Inner`는 허용 클래스가 아님) |
| M6 | 다른 감시 클래스(`OsMetricsSource`)에서 `ManagementFactory...getCpuLoad()` 직접 | 잡힘(동적 코드 패키지 의존·허용 목록 밖 타입) |
| M7 | `PlatformOsReadings`에서 `ManagementFactory.getRuntimeMXBean().getInputArguments()` | 잡힘 |
| M8 | `PlatformOsReadings`에서 람다 안 `bean.getCommittedVirtualMemorySize()` | 잡힘(람다 본문도 허용 클래스 출처로 표 검사) |
| W1 | `Watchdog.pressure`의 결측 `UNKNOWN`을 `CLEAR`로 | 잡힘(2건 실패) |
| W2 | `OsMetricsSource` CPU 상한 검사(`<= 1`) 제거 | 잡힘 |
| W3 | 메모리 `free <= total` 검사 제거 | 잡힘 |
| W4 | 필수 값에서 `memory.used` 제거 | 잡힘(2건) |
| W5 | 설정 검사 `critical <= warn` → `critical < warn`(경고 = 위험 허용) | 잡힘 |
| W6 | 메모리 임계 방향 `ABOVE` → `BELOW` | 잡힘(10건) |
| W7 | 기동 점검 `run`에서 JVM 검사(`os(osReadings)`) 제거 | 잡힘 |
| T1 | 설치본 감시, `cpu.system=0.5:1:1`, 주기 1초 | 둘째 점검(1초 뒤)에 `OK → CRITICAL: cpu.system = 2.19%`, `collector-state/stop-request`·`experiment-state/stop-request` 두 파일 생성. 실제 JVM에서 허용 경로가 동작하고 기동 시 임계 로그가 남음 |
| J1 | 이 컨테이너 JDK 21에서 `getCpuLoad` 3회(1초 간격) | 첫 호출 `0.0`, 이후 0.67·0.82. 첫 호출은 음수가 아니라 0을 준다(아래 R3) |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | RealTradingGuard 코드·시험 변경 없음. 바뀐 운영 코드는 감시 모듈(`autotrade-app-watchdog`)뿐이고 엔진·Guard 경로를 부르지 않음(diff 파일 목록). Guard 시험 포함 빌드 1,300건 통과 |
| A2 | 통과 | `ArchitectureTest`·`ModuleOwnershipTest` 변경 없음. `RealPathReflectionTest`의 예외는 `RealPathReflectionTest.java:195`에서 허용 클래스 출처 + 허용 타입일 때만 메서드 표로 판정하고, 비허용 클래스는 동적 코드 패키지·허용 목록 밖 타입 검사가 그대로 잡음(M5·M6). 표 밖 메서드·다른 MXBean·MBean 서버는 허용 클래스 안에서도 잡힘(M1~M4·M7·M8). 주문 경로 변경 없음 |
| A3 | 해당 없음 | 주문·증권사 연동 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷 변경 없음 |
| A8 | 통과 | 감시가 CPU·메모리 위험에서 만드는 것은 기존 정지 요청 파일 두 개뿐(T1, `StopRequests`). 그 파일을 읽는 곳은 수집기 `RunGuard`, 게이트웨이 `ReplayCheckGate`(리플레이 점검), 실험 `ExperimentGate`·`ExperimentJobFiles`, 실험실 `LiveLabService`이고 실전 엔진은 읽지 않음(grep). 외부 jar·네이티브 적재 추가 없음(JDK `jdk.management`만). 5.2 정지 순서 0·2번이 CPU·메모리 압박에서도 발동하게 되어 엔진 보호가 넓어짐 |
| A9 | 해당 없음 | 시세·보호 시세·손절 경로 변경 없음 |
| A10 | 통과 | 추가 줄 전체를 읽음. 키·계좌번호·토큰 없음. 새 로그는 임계 값·지표 이름·설정 폴더 경로뿐. CI 비밀값 스캔 success |
| A11 | 통과 | 보호 테스트 삭제·약화 0건. 고정 값 갱신(`ALLOWED_OS_METRIC_READERS`·`OS_METRIC_METHODS`)은 D-095(사용자 결정 ①)와 대상 코드 `PlatformOsReadings`를 함께 검토함: 대상 클래스는 세 값 읽기만 하고 빈을 밖으로 내지 않음(`PlatformOsReadings.java`, 인터페이스 `OsReadings`에 관리 타입 없음). 고정 목록 시험이 두 값을 `containsExactly`·`containsOnly`로 고정. 새 시험이 허용 안·밖 두 경우를 실패 포함으로 시험하고, 변이 M1~M8·W1~W7 전부 잡힘 |
| A12 | 통과 | 장애표 #10에 감시 CPU·메모리(D-095)를 더한 것은 코드와 맞음(T1). 5.2 0번 문구 갱신. 값을 못 읽으면 `UNKNOWN`으로 켜진 정지 요청을 풀지 않는 D-092 3번 전제 유지(`StopRequests.java:97`, W1). 감시가 기동을 거부하는 새 경우(HotSpot 아닌 JVM)는 #8·#9 감지도 함께 없어지는 위험으로 D-095에 적힘. 장애 테스트 8종의 전제(실전 엔진·Guard) 변경 없음 |
| A13 | 해당 없음 | 시장별 코드 없음. 시각은 주입 `Clock`·`Instant` |
| A14 | 통과 | D-095(결정 1~5번), 결정 로그 D-095 행·Q-053 닫음, 기획서 v14.38 MON-02, ADR-002 9.6, ADR-011, config-keys(`cpu.system`·`memory.used` 기본 `85:95:3`, 범위 0 < 경고 < 위험 ≤ 100, 연속 1~10)가 코드와 맞음(`WatchdogSettings.DEFAULT_USAGE_PERCENT`, `usage()`, W5). 사용자 결정 ①로 기록되고 "추천안 채택(자율)"이 아님이 명시됨 |
| B1 | 통과 | 직접 빌드 1,300건 통과(429 재시도는 빌드 실패로 보지 않음), CI 3건 success |
| B6 | 통과 | 새 동작마다 시험 있음(`OsMetricsSourceTest` 4, `WatchdogTest` 4, `WatchdogSettingsTest` 1, `WatchdogMainTest` 1, `HeartbeatWatchTest` 1, `HeartbeatTest` 1, `RealPathReflectionTest` 1). 건너뛴 시험 없음. 결측 상태가 이어질 때의 반복 경고는 시험·구현 모두 없음(R1) |

## 과금 검사 (QA 지침 8장)
| # | 결과 | 근거 |
|---|---|---|
| 과금 | 통과 | 새 외부 호출·유료 서비스·라이브러리 없음. JDK 내장 API만 씀 |

## 시나리오 시도 (잘못된 주문·중복 주문·보호 공백)
1. **허용 예외를 넓혀 실전 경로에 관리 API·리플렉션 통로를 여는 변경이 빌드를 통과함**: 같은 클래스 안 표 밖 메서드(M1·M4·M8), 다른 MXBean(M2·M7), 하위 인터페이스 형변환(M3), 중첩 클래스(M5), 다른 클래스(M6) 모두 빌드 실패로 잡힘. 남은 길은 ADR-002 9.6이 적은 대로 `PlatformOsReadings` 이름 그대로 다른 일을 더하는 것(허용 메서드는 숫자만 돌려줌)이며 이는 코드 검토로만 막힘 `[추론]`.
2. **CPU 값을 계속 못 읽어 위험 판정이 조용히 꺼짐**: `OsMetricsSource`가 값을 빼면 `pressure`는 다른 값이 위험이 아닌 한 `UNKNOWN`이라 새 정지 요청을 CPU로는 만들지 못하고 켜진 요청은 풀지 않음(코드, W1). 다른 값이 위험이면 결측과 관계없이 `CRITICAL`(`Watchdog.pressure` 루프는 결측을 건너뛰고 계속 봄). 연속 판정 수는 결측 주기에 초기화되지 않음(`ThresholdEvaluator`, "값이 없는 대상은 수준과 연속 횟수를 그대로 둔다"). 결측 시작은 WARN 한 번으로 알리지만 이어지는 동안 다시 알리지 않음 → R1.
3. **잘못된 위험 판정이 실전 엔진에 영향**: 정지 요청 파일은 단발 일괄 명령·실험실·리플레이 점검만 읽고 실전 엔진은 읽지 않음(A8 grep). 메모리 값이 실제보다 높게 나와도(R2) 결과는 일괄 명령 정지뿐이고 주문 경로에 닿지 않음.
4. **감시를 띄울 수 없는 JVM**: `com.sun.management` 빈이 없으면 `run`은 2, `start`는 던지고 하트비트를 쓰지 않음(W7, `WatchdogMainTest`). 이때 하트비트·드라이브 감시도 없어지지만 감시가 자기 하트비트를 안 쓰므로 "감시 꺼짐"으로는 보임 `[추론]`. 엔진 기동에는 영향 없음.

## 점검표 밖 발견 (판정에 넣지 않음)
- **R1(권고) 결측이 이어지는 동안 반복 경고가 없음.** `Watchdog.reportMissing`은 결측 집합이 바뀔 때만 남긴다(`Watchdog.java:129`). Windows 성능 카운터가 깨진 PC처럼 `getCpuLoad`가 처음부터 계속 음수면 기동 직후 WARN 한 줄 뒤로는 CPU 위험 판정이 꺼진 채 아무 로그가 없고, 한 번 켜진 정지 요청은 감시를 다시 띄워도 풀리지 않는다(`adoptLeftovers`로 이어받음) `[추론, Windows에서 확인 못 함]`. PR #118 QA R1의 설정 폴더 경고처럼 결측이 이어지면 일정 간격(예: 10분)으로 다시 알리거나, 기동 점검(`WatchdogMain.run`)에서 값을 한 번 읽어 결측이면 경고하는 것을 후속으로 권한다.
- **R2(참고) 메모리 사용률 정의가 실험실과 다름.** 실험실 `SystemSource`는 OSHI `getAvailable()`(MemAvailable, `SystemSource.java:94`), 감시는 JDK `getFreeMemorySize`. 같은 지표 이름 `memory.used`로 두 화면·로그의 값이 다를 수 있음. Linux 비컨테이너에서는 페이지 캐시를 사용으로 쳐서 높게 나오고, Windows는 `GlobalMemoryStatusEx`의 사용 가능 값이라 비슷하다 `[추론, JDK 네이티브 소스 기억, 이 검토에서 확인 못 함]`. D-095 위험과 한계에 적혀 있고 사용자 PC 첫 기동 로그를 작업 관리자와 대조하도록 되어 있음. 이 대조를 6단계 운영 점검 항목에 넣기를 권함.
- **R3(참고) `getCpuLoad` 첫 호출이 0을 줌(J1).** D-095는 "첫 호출에 음수(모름)"를 가정하지만 이 JDK에서는 `0.0`(정상으로 판정됨)이었다. 기본 연속 3회라 위험을 늦추는 것은 한 주기뿐이고 해제 쪽은 `CLEAR` 연속이 필요해 안전에는 영향 없음. 문구만 사실과 다름.
- **R4(참고) 결측이 주기마다 오가면 WARN·INFO가 매 주기 남음.** 같은 상태 지속만 막으므로, 값이 한 주기씩 번갈아 빠지면 로그가 주기(기본 5초)마다 쌓인다 `[추론, 코드 읽기]`.

## 판정
승인

점검표 A·B1·B6 실패 0건, 확인 못 함 0건(R1·R2의 Windows 동작 확인 못 함은 판정 항목이 아닌 권고·참고). 실전 관련 PR이므로 머지는 사용자가 직접 한다.
