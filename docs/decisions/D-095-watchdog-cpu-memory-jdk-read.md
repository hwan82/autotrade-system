# D-095 감시 프로세스의 PC CPU·메모리 읽기: JDK 읽기만 허용(Q-053 결정)

- 날짜: 2026-10-15
- 상태: 확정 · **사용자 결정 ①**(Q-053, 2026-10-15 스레드 결정 카드에서 "JDK 읽기만 허용" 선택). 추천안 채택(자율) 아님. 보호 테스트(`RealPathReflectionTest`)를 바꾸므로 실전 관련 PR이다(risk-ops·architect 검토, 독립 QA, 사용자 머지).
- 담당: risk-ops. 교차 검토: architect(`docs/reviews/pr-<번호>/architect.md`), risk-ops(`docs/reviews/pr-<번호>/risk-ops.md`)
- 근거: [D-092](D-092-watchdog-heartbeat-and-stop-requests.md) 6번(감시 런타임에 `java.lang.management`를 들이지 않음, CPU·메모리는 Q-053), [D-086](D-086-real-path-reflection-ban.md)(실전 경로 JDK 허용 목록, `java.lang.management..`·`javax.management..`는 동적 코드 패키지로 금지), [D-090](D-090-monitor-agent-and-screen.md) 5번(실험실 표준 지표 `cpu.system`·`memory.used`, 85/95% 3회 `[가정]`), 장애 설계 5.2·장애표 #10, 기획서 MON-02
- D-092 6번 가운데 "`java.lang.management`를 들이지 않음"만 아래 1번의 좁은 예외로 보충한다. OSHI·JNA 금지(`forbiddenGroups`)와 `autotrade-monitor-sources` 금지는 그대로다.

## 결정

1. **읽기 클래스 하나**: 감시 모듈의 `com.autotrade.app.watchdog.PlatformOsReadings`만 JDK 관리 API를 쓸 수 있다. 쓸 수 있는 것은 `java.lang.management.ManagementFactory#getOperatingSystemMXBean`, `java.lang.management.OperatingSystemMXBean` 타입(메서드 호출 없음, `instanceof` 검사용), `com.sun.management.OperatingSystemMXBean`의 `getCpuLoad`·`getTotalMemorySize`·`getFreeMemorySize` 셋뿐이다. MBean 서버(`getPlatformMBeanServer`, `javax.management..`), 다른 MXBean(`RuntimeMXBean` 등), 같은 빈의 다른 메서드(`getProcessCpuTime`, `getArch` 등)는 이 클래스에서도 금지다. 실전 엔진 런타임을 포함한 실전 경로의 다른 모든 클래스에는 예전 그대로 금지다.
2. **보호 테스트 변경**: `RealPathReflectionTest`에 허용 읽기 클래스 목록 `ALLOWED_OS_METRIC_READERS`와 허용 메서드 표 `OS_METRIC_METHODS`를 더하고, 고정 목록 시험이 두 값을 정확히 고정한다. 동적 코드 패키지 의존 검사는 ArchUnit 기본 조건 대신 허용 읽기 클래스의 허용 타입만 빼는 조건으로 바꾸고, 상수 풀 검사(D-086 6)도 같은 예외만 둔다. 고정 예제 `OsMetricsProbe`가 허용 경로와 금지 경로(MBean 서버, RuntimeMXBean, 다른 메서드)를 모두 갖고, 허용 목록에 넣었을 때와 뺐을 때를 함께 시험한다.
3. **감시 판단에 넣기**: 수집원 `OsMetricsSource`(id `os`)가 지표 `cpu.system`(PC 전체 CPU 사용률 %)과 `memory.used`(PC 전체 물리 메모리 사용률 %)를 낸다. 이름은 실험실 표준 수집원(D-090)과 같다. 다른 클래스는 인터페이스 `OsReadings`만 보고 관리 타입을 모른다. 임계는 `ABOVE`, 감시 설정 `cpu.system`·`memory.used`(`경고%:위험%:연속`, 기본 `85:95:3` `[가정]`, D-090 5번과 같음). 드라이브 여유와 똑같이 하나라도 위험이면 단발 일괄 명령 정지 요청 파일을 만들고, 모든 값이 정상으로 `release-hold-seconds`(기본 5분) 이어질 때만 지운다. 값을 못 읽은 주기(CPU 음수·NaN·1 초과, 메모리 전체 0 이하·여유가 범위 밖)는 그 값이 빠지고 "판단 못 함"이라 정지 요청을 풀지 않는다(D-092 3번과 같은 원칙).
4. **못 읽는 JVM이면 기동 거부**: `com.sun.management.OperatingSystemMXBean`을 주지 않는 JVM이면 감시는 종료 코드 2로 뜨지 않는다(감시 설정 오류와 같은 처리, PR #115 risk-ops K2 원칙). 감시를 띄울 JDK(Temurin 21 등 HotSpot 계열)는 이 빈을 준다 `[사실]`(근거: `OsMetricsSourceTest#이_JDK에서는_PC_값을_읽을_수_있다`, 이 저장소 빌드 JDK 21).
5. 감시는 이 값으로도 어떤 프로세스를 멈추거나 죽이지 않는다(D-058). 실험실·실시간 모의 세션의 자원 보호(D-076)와 실전 엔진은 바꾸지 않는다.

## 대안 (Q-053)

| 대안 | 판단 |
|---|---|
| ① 감시의 읽기 클래스 하나에 JDK 운영체제 MXBean 읽기 세 메서드만 허용(채택, 사용자 결정) | 외부 jar·네이티브 적재가 없다. 허용 범위가 클래스 이름 하나와 메서드 이름 셋으로 시험에 고정되어 넓어지면 빌드가 실패한다 |
| ② OSHI·JNA를 감시 런타임에 허용 | 외부 jar와 네이티브 접근이 실전 경로 런타임(감시는 실전 경로 모듈)에 들어와 D-086의 "허용 목록 외부 jar만 신뢰"를 넓힌다. 버림 |
| ③ OSHI가 있는 실험실이 PC 지표를 파일로 내보내고 감시가 읽음 | 실험실이 죽거나 멈추면 안 보이고, 실험실이 가장 먼저 멈추는 프로세스(5.2)인데 감시 판단이 거기에 기댄다. 버림 |
| ④ 넣지 않음 | CPU·메모리 압박은 명령의 힙 상한·장 시간 보호로만 막혀, 장애표 #10 "PC 자원 고갈"의 감시가 드라이브만 본다. 버림 |

되돌릴 조건: 7단계 감시 긴급 경로가 생겨 실전 엔진 상태를 명령 채널로 묻게 되어도 이 결정은 그대로 둔다. JDK가 같은 값을 다른 API로 주게 되면(예: `getCpuLoad` 폐기) 허용 메서드 표를 바꾸는 실전 관련 PR로 다시 정한다. 프로세스별 CPU·메모리가 필요해지면 `ProcessHandle`(이미 허용 목록 안 `java.lang`)부터 검토하고, 관리 API 범위를 넓히는 것은 새 결정으로 한다.

## 위험과 한계

- `getFreeMemorySize`는 OS가 말하는 여유 메모리다. Linux에서는 페이지 캐시를 여유로 치지 않아 사용률이 높게 나온다. 대상 PC는 Windows이고 Windows의 값은 작업 관리자의 "사용 가능"에 가깝다 `[추론]`(Windows에서 확인하지 못함, 사용자 PC 첫 기동 로그의 값을 작업 관리자와 대조해 확인).
- `getCpuLoad`는 첫 호출이나 짧은 간격에서 음수(모름)를 줄 수 있다. 그 주기는 판단 못 함이라 정지 요청을 만들지도 풀지도 않는다. 계속 음수면 감시 판단이 늘 "판단 못 함"이 되어 이미 켜진 정지 요청이 풀리지 않는다(안전한 쪽). 수집원 읽기 실패 로그는 감시 에이전트 코어 정책(PR #119)을 따른다.
- 컨테이너·가상 머신 안에서 JDK는 컨테이너 한도를 PC 전체 값으로 보고할 수 있다. 감시는 사용자 PC에서 직접 돈다는 전제다 `[가정]`.
- 기본 85/95%와 3회 연속은 실험실 기본을 따른 `[가정]`이다. 6단계 운영 중 사용자 PC 값으로 다시 본다.
- `com.sun.management` 타입은 JDK 공개 지원 API(`jdk.management` 모듈)이지만 Java SE 표준은 아니다. HotSpot이 아닌 JVM에서는 4번으로 감시가 뜨지 않는다.

## 검증

- `RealPathReflectionTest`: 고정 목록 시험이 `ALLOWED_OS_METRIC_READERS`·`OS_METRIC_METHODS`를 정확히 고정. `PC_지표_읽기는_허용_클래스의_허용_메서드만_통과한다`: 고정 예제를 허용 읽기 클래스로 넣으면 허용 메서드는 통과하고 MBean 서버·RuntimeMXBean·`getProcessCpuTime`·`getArch`는 위반, 빼면 허용 메서드도 위반(동적 코드 패키지 의존·허용 목록 밖 타입 포함). 운영 코드(감시 포함) 위반 0건.
- `OsMetricsSourceTest`(4): 사용률 계산, 경계값 0·100%, 모르거나 말이 안 되는 값 6가지는 빠짐, 이 JDK에서 실제 값을 읽음.
- `WatchdogSettingsTest`: 기본 `85:95:3`, 파일 값 읽기, 형식·범위 오류 7가지 거부.
- `WatchdogTest` 3건: CPU 위험 연속 2회에 정지 요청 생성·경고 구간 유지·정상 연속 뒤 해제 유지 시간 지나야 해제, 메모리 위험에 정지 요청, CPU를 모르면 메모리가 정상이어도 풀지 않음. 변이 확인: 필수 값에서 CPU를 빼면 실패한다.
