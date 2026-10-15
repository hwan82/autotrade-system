# risk-ops 검토: PR #122 감시 PC CPU·메모리를 JDK 읽기로만 감시(D-095, Q-053 사용자 결정 ①) + PR #118 QA Q1·R1 후속
- 대상: PR #122 / 브랜치 `claude/project-thread-0ganrn` / 헤드 `5e1871a`(커밋 `d7ee4b8`, `5e1871a`) / base main `7dfe46d` / `git diff origin/main...origin/claude/project-thread-0ganrn` 전체(22개 파일, +576 −33). 바뀐 줄은 모두 읽었다. 그 변경이 닿는 실전 경로 코드도 함께 봤다: `Watchdog` 전체, `StopRequests` 전체, `WatchdogMain`, `DiskFreeSource`, `MonitorAgent.sampleOnce`(수집원 실패 로그 정책), `RealPathReflectionTest`의 `reflective`·`outsideAllowlist`·`noReflectionIn`·`DYNAMIC_CODE_PACKAGES`, 정지 요청 파일을 읽는 쪽(`CollectorMain`·`RunGuard`·`ExperimentGate`·`ExperimentJobFiles`·`LiveLabService`·`ReplayCheckGate`). 실전 엔진(`autotrade-app-engine`)·`autotrade-risk`·증권사 플러그인 코드는 이 PR에서 바뀌지 않았다.
- 점검표: A
- 직접 실행한 것:
  - PR 헤드 `5e1871a`를 이 worktree에 detached로 두고 `./gradlew build --no-daemon`을 돌렸다. **BUILD SUCCESSFUL(종료 코드 0, 4분 13초, 136 tasks)**. JUnit XML 기준 시험 1004건, 실패 0, 오류 0, 건너뜀 0(`autotrade-sim`·`autotrade-strategy`는 test 태스크가 돌았지만 XML 보고서가 없어 이 수에 빠졌다. 빌드는 성공). 모듈별(시험/실패/오류/건너뜀):
    - `:autotrade-risk:test` 55/0/0/0 (`RealTradingGuardTest`, `GuardedOrderGatewayTest` 포함)
    - `:autotrade-architecture-test:test` 44/0/0/0 (`RealPathReflectionTest#PC_지표_읽기는_허용_클래스의_허용_메서드만_통과한다` 포함)
    - `:autotrade-app-engine:test` 52/0/0/0
    - `:autotrade-app-watchdog:test` 37/0/0/0
    - `:autotrade-monitor-agent-jar:test` 27/0/0/0
    - `:autotrade-app-gateway:test` 50/0/0/0, `:autotrade-app-lab:test` 193/0/0/0
  - 변이 시험. 운영 코드를 하나씩 바꾸고 관련 시험만 돌린 뒤 `git checkout`으로 원복했다. 끝난 뒤 `git status --short`는 비어 있다.

    | 변이 | 결과 |
    |---|---|
    | M1 허용 클래스 `PlatformOsReadings.cpuLoad()`에서 `bean.getProcessCpuTime()`을 더 부름(표 밖 메서드) | 잡힘: `RealPathReflectionTest#실전_경로_모듈은_리플렉션을_쓰지_않는다` 실패("calls method com.sun.management.OperatingSystemMXBean.getProcessCpuTime()") |
    | M2 허용 클래스가 `ManagementFactory.getPlatformMXBean(Class)`로 빈을 얻음 | 잡힘: 같은 시험 실패(`getPlatformMXBean` 호출, 허용 목록 밖 타입 `PlatformManagedObject`) |
    | M3 허용 클래스가 아닌 `OsMetricsSource.read()`가 `ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()`를 부름 | 잡힘: 같은 시험 실패("동적 코드 패키지 의존: OsMetricsSource.read() calls ManagementFactory.getOperatingSystemMXBean()" 외 4건) |
    | M4 `Watchdog` 필수 값에서 CPU를 뺌(Watchdog.java:53 삭제) | 잡힘: `WatchdogTest#CPU를_모르면_판단_못_함이라_정지_요청을_풀지_않는다`, `WatchdogTest#CPU가_위험으로_연속되면_정지_요청을_만들고_경고_아래로_내려가_이어지면_푼다` 실패 |
    | M5 `HeartbeatWatch`의 설정 폴더 경고를 첫 번만 내게 되돌림(반복 조건 제거) | 잡힘: `HeartbeatWatchTest#하트비트를_하나도_못_본_동안은_설정_폴더_경고를_주기로_되풀이하고_한_번이라도_보면_그친다` 실패(expected 2 but was 1) |

## 이번 PR이 실전 경로에 닿는 곳
- **보호 테스트 `RealPathReflectionTest` 변경**(실전 관련 PR 사유). D-086의 동적 코드 패키지 금지(`java.lang.management..`, `javax.management..`)에 좁은 예외를 낸다.
  - 허용 클래스 `ALLOWED_OS_METRIC_READERS`(RealPathReflectionTest.java:121)는 `com.autotrade.app.watchdog.PlatformOsReadings` 하나다. 허용 표 `OS_METRIC_METHODS`는 `ManagementFactory#getOperatingSystemMXBean`, `java.lang.management.OperatingSystemMXBean`(타입만, 메서드 없음), `com.sun.management.OperatingSystemMXBean#getCpuLoad·getTotalMemorySize·getFreeMemorySize`다. 고정 목록 시험이 두 값을 정확히 고정한다(:937 이하).
  - `reflective()`의 조기 반환(:195)은 세 타입에 대해 허용 클래스가 아닌 곳에서는 `false`를 돌려준다. 그래서 허용 클래스 밖의 사용은 이 술어가 아니라 동적 코드 패키지 조건(:535)과 허용 목록 밖 타입 조건이 잡는다. 이전에도 `reflective()`에는 `java.lang.management` 절이 없었으므로 이 조기 반환이 기존 검사를 빼지는 않는다. M3에서 허용 클래스 밖 사용이 "동적 코드 패키지 의존"으로 잡히는 것을 확인했다.
  - ArchUnit 기본 `dependOnClassesThat().resideInAnyPackage(DYNAMIC_CODE_PACKAGES)`를 직접 조건으로 바꿨다. 같은 `getDirectDependenciesFromSelf`를 보고 배열은 기본 구성 타입으로 펴서 보므로 기존보다 약하지 않다 `[추론]`(배열 의존 변이는 돌리지 않았다).
  - `com.sun.management` 패키지의 다른 타입(`HotSpotDiagnosticMXBean#setVMOption`, `UnixOperatingSystemMXBean` 등)은 허용 표에 없어 허용 클래스에서도 허용 목록 밖 타입으로 걸린다(:131의 `osMetricType`이 정확한 FQN만 받음). 허용 메서드 세 개는 원시 값만 돌려줘 다른 객체로 가는 길이 없다.
- **감시 프로세스 판단 변경**: CPU·메모리가 위험이면 단발 일괄 명령 정지 요청 파일 두 개(`collector-state/stop-request`, `experiment-state/stop-request`)를 만든다. 이 파일을 읽는 쪽은 수집기, 실험 실행 명령, 실험실 실시간 모의 세션, 리플레이 점검뿐이고 실전 엔진은 읽지 않는다. 감시는 여전히 어떤 프로세스도 죽이지 않는다(StopRequests 클래스 설명, ArchUnit `WATCHDOG_KILLS_NO_PROCESS`). 5.2 정지 순서에서 0번의 발동 조건이 늘어난 것이고, 실전 쪽(5·6번)에는 닿지 않는다.
- **감시 기동 거부 조건 추가**: `com.sun.management.OperatingSystemMXBean`을 주지 않는 JVM이면 감시가 종료 코드 2로 뜨지 않는다(WatchdogMain.java:65, main의 `IllegalArgumentException` 처리 :35). 감시가 안 뜨면 장애표 #8·#9(하트비트)와 #10(자원) 감지도 함께 없다. 큰 소리로 실패하고 대상 JDK(Temurin 21)에서는 빈이 있다는 것이 `OsMetricsSourceTest#이_JDK에서는_PC_값을_읽을_수_있다`로 확인되므로 받아들인다. 다만 이 거부 경로 자체를 확인하는 시험은 없다(R3).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk`·`RealTradingGuard` 관련 파일 변경 없음(diff 파일 목록 22개에 없음). `:autotrade-risk:test` 55/0/0/0, `RealTradingGuardTest` 통과 |
| A2 | 통과 | 실전 주문 출구·Guard 팩토리 코드 변경 없음. `ArchitectureTest`·`RealPathReflectionTest#실전_경로_모듈은_리플렉션을_쓰지_않는다` 통과. 이 PR의 보호 테스트 변경은 감시 클래스 하나에 JDK 읽기 세 메서드만 여는 것이고, M1·M2·M3으로 표 밖·클래스 밖 사용이 걸리는 것을 확인 |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 코드 변경 없음 |
| A4 | 해당 없음 | 주문 상태머신·타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 실전 엔진 코드·의존 변경 없음, `:autotrade-app-engine:test` 52/0/0/0, `verifyProcessIsolation` 포함 build 성공. 새 동작은 감시가 자원 위험에서 단발 일괄 명령·실험실 정지 요청 파일만 만드는 것(StopRequests.java:89-104)이라 5.2 정지 순서의 앞쪽만 강화한다. 감시 런타임에 외부 jar·네이티브 적재는 들어오지 않음(OSHI·JNA 금지 유지, `실전_경로_런타임_구성은_고정_목록과_같다` 통과) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | diff에 키·계좌번호·토큰 없음(새 파일 전체를 읽음). 로그 문구는 설정 폴더 경로와 임계 숫자만 낸다(WatchdogMain.java:65-72, HeartbeatWatch 경고). gitleaks는 로컬에서 돌리지 않았다(CI 몫) |
| A11 | 실패 | 보호 테스트 삭제·비활성화는 없고, 허용 목록·표 갱신의 대상 코드(`PlatformOsReadings`)를 읽고 M1~M3으로 경계를 확인했다(통과 부분). 그러나 새 동작 두 가지에 실패하는 경우의 시험이 없다: ① `com.sun.management` 빈이 없을 때 감시 기동 거부(WatchdogMain.java:65, D-095 4번) 시험 없음(R3) ② CPU·메모리 값이 계속 빠질 때 알림이 없고 시험도 없다(R1) |
| A12 | 실패 | 장애표 #10 감지 열은 CPU·메모리를 넣어 갱신했다(broker-and-fault-design.md:169). 그러나 ① 5.2 0번(broker-and-fault-design.md:144)은 여전히 "모든 드라이브가 경고 경계 위로 5분 이어지면 지우고 … CPU·메모리 기준은 Q-053 결정 뒤"로 남아 바뀐 해제 조건(드라이브·CPU·메모리 모두 정상)과 다르다(R2) ② CPU·메모리 값이 OS에서 계속 음수·범위 밖이면 감시는 그 값을 조용히 빼고 "판단 못 함"으로만 둔다. 그 동안 CPU·메모리 위험을 감지하지 못하는데 로그도 상태도 남지 않아 #10의 "감지 방법"에 구멍이 생긴다(R1). 장애 테스트 8종의 전제는 깨지 않음 |
| A13 | 해당 없음 | 시장별 코드 변경 없음. 감시 시간 계산은 주입 `Clock`(UTC 순간) 기준 |
| A14 | 실패 | D-095·결정 로그·기획서 v14.38·ADR-002 9.6·ADR-011·config-keys는 코드와 맞는다. 다만 ① D-095 위험과 한계(D-095-watchdog-cpu-memory-jdk-read.md:31)의 "수집원 읽기 실패 로그는 감시 에이전트 코어 정책(PR #119)을 따른다"는 사실과 다르다: `OsMetricsSource.read()`는 음수·범위 밖 값을 예외 없이 빼므로(OsMetricsSource.java:33, :38) `MonitorAgent.sampleOnce`의 실패 추적(예외일 때만)이 돌지 않는다(R1) ② 5.2 0번 문구가 낡았다(R2) ③ `WatchdogMain` 클래스 설명(WatchdogMain.java:11-14)이 "CPU·메모리 감시 … 후속 작업"이라 하고, `DiskFreeSource` 설명(DiskFreeSource.java:12)이 "감시 런타임은 … `java.lang.management`를 쓰지 않으므로"라 해 D-095와 어긋난다(R2) |

## 점검표 밖 발견
- **R1(수정 필요) CPU·메모리 값이 계속 빠져도 아무도 모른다.** `OsMetricsSource`는 `getCpuLoad()`가 음수·NaN·1 초과이거나 메모리 값이 말이 안 되면 그 값만 뺀다(OsMetricsSource.java:33-41). `Watchdog.pressure`는 그 주기를 `UNKNOWN`으로 돌려주고(Watchdog.java:104, :114) `StopRequests.update`는 해제 유지 시간만 다시 센다(StopRequests.java:97). 어디에도 로그가 없다. D-095는 "계속 음수면 이미 켜진 정지 요청이 풀리지 않는다(안전한 쪽)"라 쓰지만, 반대쪽이 빠져 있다: 그 동안 CPU·메모리가 95%를 넘어도 위험으로 판정되지 않으므로 실험실·일괄 명령이 계속 돌고, 5.2 정지 순서의 0·2번이 CPU·메모리 압박에서 발동하지 않는다. 이것은 실전 엔진 지연 보호(A8)의 감지 수단이 조용히 꺼지는 경우다. 드라이브 경로를 못 읽는 경우(DiskFreeSource.java:52-54)도 같은 침묵이 이미 있었고 이번에 같은 틀에 값 두 개가 더해졌다.
  - 고칠 것: 감시가 필수 값(드라이브 경로별, `cpu.system`, `memory.used`)이 빠지기 시작할 때 어느 값인지 WARNING 한 번, 돌아올 때 INFO 한 번을 상태 변화 때만 남긴다(PR #119와 같은 원칙). 첫 점검의 `getCpuLoad` 음수는 흔하므로 연속 N회(예: 3회) 뒤부터 알리는 방식도 괜찮다. 시험: CPU를 계속 음수로 두면 경고가 한 번 나오고, 값이 돌아오면 복구 로그가 나오고, 드라이브 하나를 못 읽어도 같다는 것을 확인. D-095 31행 문구를 실제 동작에 맞게 고친다.
- **R2(수정 필요) 낡은 문서 문구.** broker-and-fault-design.md:144의 5.2 0번 해제 조건과 "CPU·메모리 기준은 Q-053 결정 뒤", WatchdogMain.java:11-14 클래스 설명, DiskFreeSource.java:12 클래스 설명을 D-095에 맞춘다. 5.2는 장애 대응의 기준 문서라 운영자가 이 문구로 정지 요청 해제를 판단한다.
- **R3(수정 필요) 기동 거부 경로 시험이 없다.** `WatchdogMain.start`가 `PlatformOsReadings.create()`를 직접 불러 빈이 없는 JVM을 흉내 낼 수 없다. 읽기 객체 공급자를 받는 패키지 전용 오버로드(운영 진입점은 지금처럼 `PlatformOsReadings::create`)를 두고, 빈 값이면 `IllegalArgumentException`(→ 종료 코드 2)이고 하트비트·정지 요청 파일을 만들지 않는다는 시험을 더한다.
- N1(참고) `cpu.system`·`memory.used` 검사는 `경고 = 위험`을 허용한다(WatchdogSettings.java:164). ADR-007 2.7의 해제 기준은 "임계치 − 10%p"가 기본이라, 사용자가 `95:95:1`로 적으면 위험과 해제 경계가 같아 정지 요청이 켜졌다 꺼졌다 할 수 있다(해제 유지 5분이 완충하긴 한다). 값은 `[가정]`이라 막을 필요까지는 없고, config-keys에 "경고는 위험보다 충분히 낮게(기본 10%p)"를 적는 정도를 권한다.
- N2(참고) `HeartbeatTest#아주_큰_파일도_상한까지만_읽어_메모리를_쓰지_않는다`는 `setLength(3GiB)`로 희소 파일을 만든다고 적었지만 Windows NTFS에서는 희소 파일이 아니어서 개발 PC 빌드 때 3GB 디스크를 잠시 차지하고, 여유가 없으면 시험이 실패한다 `[추론]`(Windows에서 돌리지 않았다). 2초 시간 단언도 느린 PC에서 흔들릴 수 있다. 안전 문제는 아니다.
- N3(참고) `WatchdogMain.run`(설정 검사 모드)은 빈 유무를 보지 않아, 검사 모드는 0인데 실제 기동은 2로 거부되는 경우가 있다. R3 수정 때 함께 맞추기를 권한다.
- N4(남은 위험) 감시가 HotSpot 계열이 아닌 JVM에서 뜨지 않으면 #8·#9 하트비트 감지까지 사라진다. 7단계 실행 절차서에 감시 JDK를 고정하는 항목을 넣기를 권한다. Windows에서 `getFreeMemorySize`·`getCpuLoad` 값이 작업 관리자와 맞는지는 D-095 위험과 한계대로 확인하지 못했다.
- PR #118 QA 후속(Q1 3GB 읽기 시험, R1 설정 폴더 경고 반복)은 코드와 시험이 맞고 M5로 반복 경고 시험이 동작을 잡는 것을 확인했다.

## 판정
수정 필요(A11·A12·A14 / R1 CPU·메모리 값 결측의 무경보 해소와 시험, R2 5.2 0번·WatchdogMain·DiskFreeSource 낡은 문구 정정, R3 감시 기동 거부 경로 시험)
