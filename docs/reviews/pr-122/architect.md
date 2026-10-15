# architect 검토: 감시 PC CPU·메모리를 JDK 읽기로만 감시(D-095, Q-053) + PR #118 QA Q1·R1 후속
- 대상: PR #122 / 브랜치 `claude/project-thread-0ganrn` 헤드 `5e1871a` / base main `7dfe46d` (merge-base = `7dfe46d`), `git diff origin/main...origin/claude/project-thread-0ganrn` 전체 22파일 +576 −33
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 `5e1871a` 분리 체크아웃) → `BUILD SUCCESSFUL in 4m 13s`, 종료 코드 0. 시험 결과 XML 집계: `autotrade-app-watchdog` 37건·`autotrade-architecture-test` 44건·`autotrade-monitor-agent-jar` 27건, 실패·오류·건너뜀 0.
  - 변이 1: `PlatformOsReadings.cpuLoad()`에 `bean.getArch()` 호출을 넣음 → `:autotrade-architecture-test:test` 44건 중 1건 실패(`RealPathReflectionTest`, 실패 보고에 `getArch()`). 되돌림.
  - 변이 2: `Watchdog` 생성자에서 `requiredReadings.add(OsMetricsSource.CPU)` 삭제 → `:autotrade-app-watchdog:test` 2건 실패(`CPU를_모르면_판단_못_함이라_정지_요청을_풀지_않는다`, `CPU가_위험으로_연속되면_..._푼다`). 되돌림. 작업 트리 깨끗함 확인.
  - Windows 실행, gitleaks, PostgreSQL 스키마 검사는 실행하지 않음(스키마 변경 없음).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 종료 코드 0, 시험 실패 0. 새 시험 `RealPathReflectionTest#PC_지표_읽기는_허용_클래스의_허용_메서드만_통과한다`, `OsMetricsSourceTest`(4), `WatchdogTest` 3건, `WatchdogSettingsTest#CPU_메모리_임계_형식과_범위를_검사한다`, `HeartbeatWatchTest#하트비트를_하나도_못_본_동안은_...`, `HeartbeatTest#아주_큰_파일도_...` 모두 결과 XML에 통과로 있음 |
| B2 | 통과 | 새 운영 클래스 3개(`OsReadings`, `OsMetricsSource`, `PlatformOsReadings`)는 감시 모듈 소유 패키지 `com.autotrade.app.watchdog` 안. 관리 타입은 `PlatformOsReadings.java:3-4,15`에만 있고 `OsReadings` 인터페이스(`OsReadings.java:7-17`)는 원시 타입만 노출. ArchUnit 규칙 변경(`RealPathReflectionTest.java` +90줄)은 ADR-002 9.6과 D-095로 문서화됨. 규칙 논리 확인: ① 호출 검사 `reflective(access, readers)`는 표의 타입이면 "허용 클래스이면서 표 밖 메서드"만 위반으로 보고, 허용 클래스가 아닌 쪽은 ② 동적 코드 패키지 의존 조건(기존 `dependOnClassesThat().resideInAnyPackage`를 `getDirectDependenciesFromSelf` + `getBaseComponentType` + `PackageMatchers`로 바꿈, 예외는 `osMetricType`만)과 ③ `outsideAllowlist`(상수 풀 포함, `com.sun.management`는 허용 패키지 밖)가 잡는다. 고정 예제 `OsMetricsProbe`가 허용 안·밖 두 경우를 시험하고, 변이 1로 허용 클래스 안 표 밖 메서드가 실제로 걸리는 것을 확인. 고정 목록 시험이 `ALLOWED_OS_METRIC_READERS`·`OS_METRIC_METHODS`를 `containsExactly`·`containsOnly`로 고정(`RealPathReflectionTest.java` 고정 목록 시험 추가분) |
| B3 | 통과(문서 오류 1건은 아래 N1) | 계약 변경: 감시 설정 키 `cpu.system`·`memory.used`(`WatchdogSettings.java` `KEYS`, 기본 `85:95:3`) → `docs/config/config-keys.md:63-64,72-73`에 기본값·범위·근거 있음, `release-hold-seconds` 설명 갱신. 패키지 내부 생성자 `Watchdog(..., OsReadings)`·`WatchdogSettings` 레코드 확장은 모듈 밖 계약 아님. 결정 기록 D-095, ADR-002 9.6, ADR-011 3장 보충, 기획서 v14.38 MON-02, 장애표 #10 갱신 확인 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 감시는 주입 `Clock`(`Watchdog.tick` `clock.instant()`), `HeartbeatWatch.check(now)`는 인자 시각만 씀. 시스템 시계 직접 호출 추가 없음(`WatchdogTest#주기_점검을_스레드에서_돌린다`의 `Clock.systemUTC()`는 기존 시험). 골든 시험은 전체 빌드에서 통과 |
| B5 | 해당 없음 | 금액 계산 없음. CPU·메모리 사용률(%)은 `double`이며 금액 아님. 설정 파싱은 `BigDecimal`로 읽어 `NaN`·`Infinity` 문자열을 숫자 오류로 거부(`WatchdogSettings.usage`) |
| B6 | 통과 | 새 동작마다 시험 있음(위 B1 목록). 기존 시험 삭제·`@Disabled` 없음(diff에서 테스트 삭제 줄은 `Watchdog` 생성자 인자 추가에 따른 호출 수정 2곳뿐). 변이 1·2가 새 시험에 잡힘. 빈 곳: JVM이 빈을 못 줄 때 종료 코드 2 경로는 시험 없음(아래 N4) |
| B7 | risk-ops 검토 필요 | 실전 경로 런타임 모듈 `autotrade-app-watchdog`·`autotrade-monitor-agent-jar`와 보호 테스트 `autotrade-architecture-test`(`RealPathReflectionTest` 고정 값 추가, 고정 예제 추가)를 바꾼다(`scripts/ci/protected-paths.txt`의 `autotrade-architecture-test/*`, `autotrade-app-watchdog/*`, `autotrade-monitor-agent-jar/*`, `docs/adr/ADR-002-real-trading-guard.md`). 프로젝트 지침 11장 "보호 테스트" 기준 실전 관련 PR: risk-ops 검토, 독립 QA(`docs/qa/pending.md`에 행 추가 확인), 사용자 머지 대상 |
| B8 | 통과 | diff에 키·계좌번호·토큰 없음. 새 로그(`WatchdogMain` 임계 로그, `HeartbeatWatch` 경고)는 임계 숫자와 설정 폴더 경로만 남김(경로 출력은 기존 PR #116 N2와 같은 수준). 커밋 메시지 2건에 비밀값 없음. gitleaks는 직접 실행하지 않음(CI 몫) |

## 점검표 밖 발견
- **N1 (문서, 수정 필요) D-092 항목 번호를 잘못 인용**: `docs/decisions/D-095-watchdog-cpu-memory-jdk-read.md` 근거 줄("D-092 6번(감시 런타임에 `java.lang.management`를 들이지 않음, CPU·메모리는 Q-053)")과 그 아래 "D-092 6번 가운데 ... 보충", `docs/decisions/README.md` D-095 행 "D-092 6번 보충"이 틀렸다. 그 경계는 `docs/decisions/D-092-watchdog-heartbeat-and-stop-requests.md:17`의 **4번**("PC 자원은 드라이브 여유만 본다")이고 6번은 감시 생존 확인이다. 이전 Q-053 행도 "D-092 4번"이라 적었다. 결정 로그가 단일 원본이므로 4번으로 고친다.
- **N2 (문서·주석, 수정 필요) 낡은 주석**: `autotrade-app-watchdog/src/main/java/com/autotrade/app/watchdog/DiskFreeSource.java:12` "감시 런타임은 OSHI·`java.lang.management`를 쓰지 않으므로(D-086, D-090)"가 이 PR 뒤 사실이 아니다. "OSHI를 쓰지 않고, `java.lang.management`는 `PlatformOsReadings`의 D-095 예외만"처럼 고친다.
- **N3 (시험 이식성, 수정 필요) 3GB "희소 파일" 시험이 Windows에서 희소가 아닐 수 있음**: `HeartbeatTest#아주_큰_파일도_상한까지만_읽어_메모리를_쓰지_않는다`는 `RandomAccessFile.setLength(3L << 30)`로 만들고 주석에 "희소 파일, 디스크는 안 씀"이라 적었다. Linux ext4/tmpfs에서는 맞지만 NTFS는 희소 표시 없이 길이를 늘리면 클러스터를 할당한다 `[추론]`(Windows에서 확인하지 못함). 사용자 개발 PC가 Windows(CLAUDE.md의 QA 폴더 `C:\...`)라 `./gradlew build`마다 임시 폴더에 3GB를 잡거나, 여유가 적으면 `IOException`으로 시험이 실패할 수 있다. 권고: `Files.newByteChannel(path, CREATE_NEW, WRITE, StandardOpenOption.SPARSE)`로 만든 뒤 `position(3L << 30).write(1바이트)`(NTFS에서 SPARSE가 희소 표시를 건다), 그리고 주석을 실제 성질에 맞춘다. 2초 시간 단언은 크기 검사 경로라 여유가 크다고 본다 `[추론]`.
- **N4 (권고) JVM 거부 경로**: `WatchdogMain.start`의 `PlatformOsReadings.create().orElseThrow(...)`(`WatchdogMain.java` +64~66줄)는 종료 코드 2 경로인데 시험이 없고, 기동 점검 `WatchdogMain.run`은 이 검사를 하지 않아 같은 JVM에서 `run`은 0, `start`는 2가 된다. 이 JVM 조건은 이 저장소 빌드 JDK에선 재현이 어렵고 실제 영향은 기동 거부 쪽(안전한 쪽)이라 차단 사유로 보지 않는다. `start`에 `Supplier<Optional<OsReadings>>`를 받는 패키지 내부 오버로드를 두고 시험 1건, `run`에도 같은 검사를 넣는 것을 권한다.
- **N5 (참고) 범위**: PR #118 QA Q1·R1 후속(`HeartbeatWatch` 10분 반복 경고, `HeartbeatTest` 3GB 시험)이 D-095와 한 커밋에 섞여 있다. 커밋 메시지에 밝혀 있고 같은 보호 경로라 묶음 자체는 문제 삼지 않는다. `HeartbeatWatch` 논리 확인: `seenAny`는 한 번이라도 모두 꺼짐이 아니면 영구 true, 경고 간격은 `ALL_ABSENT_REPEAT`(10분, 설정 키 아님)로 `now` 인자 기준 — 시험이 경계(10분−5초 / 10분)와 "한 번 본 뒤 다시 모두 꺼짐이면 침묵"을 고정한다.
- **N6 (참고, risk-ops·사용자 확인 사항)**: ① 설계상 CPU 값이 계속 음수인 플랫폼이면 이미 켜진 정지 요청이 영원히 풀리지 않는다(D-095 위험과 한계에 적힘, 안전한 쪽). ② Linux의 `getFreeMemorySize`는 페이지 캐시를 사용으로 쳐 사용률이 높게 나온다(D-095에 적힘). 대상 PC가 Windows라는 전제와 기본 85/95%·3회(15초) `[가정]`이 실시간 모의 세션 정지(D-076·D-092 5번 실험 정지 요청)를 너무 쉽게 일으키지 않는지는 6단계 운영 값으로 다시 봐야 한다. ③ "Q-053 사용자 결정 ①"은 저장소 안에서 확인할 근거가 없어 검증하지 못했다. 이 PR은 사용자 머지 대상이므로 머지 시 사용자가 확인하는 것으로 갈음한다.

## 판정
수정 필요(N1 D-095·결정 로그의 "D-092 6번"을 4번으로 정정, N2 `DiskFreeSource.java:12` 낡은 주석 정정, N3 3GB 시험을 SPARSE 옵션으로 만들거나 Windows 동작에 맞게 주석·방식 정정). N4는 권고. 코드·규칙 변경 자체는 빌드·변이 확인으로 문제를 찾지 못했다. 실전 관련 PR이므로 수정 뒤에도 risk-ops 검토·독립 QA·사용자 머지가 필요하다.

## 재검토 2026-10-15
- 대상: PR #122 / 브랜치 `claude/project-thread-0ganrn` 헤드 `9c828bd` / base main `7dfe46d`, `git diff origin/main...HEAD` 전체 26파일 +762 −39(지난 검토 헤드 `5e1871a` 대비 커밋 1건 `9c828bd`, 14파일 +206 −26)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` (헤드 `9c828bd` 분리 체크아웃) → `BUILD SUCCESSFUL in 51s`, 종료 코드 0. 바뀐 모듈은 다시 시험함: `autotrade-app-watchdog` 39건(지난번 37 + 새 2), `autotrade-architecture-test` 44건, `autotrade-monitor-agent-jar` 27건, 실패·오류·건너뜀 0. 입력이 안 바뀐 모듈은 Gradle 최신 상태 재사용.
  - 변이 3: `WatchdogMain.run`에서 `os(osReadings)` 삭제 → `WatchdogMainTest#PC_CPU_메모리를_줄_수_없는_JVM이면_기동을_거부한다` 실패. 되돌림.
  - 변이 4: `Watchdog.tick`에서 `reportMissing(snapshot)` 삭제 → `WatchdogTest#값이_빠지기_시작하면_한_번만_알리고_돌아오면_다시_알릴_수_있다` 실패. 되돌림. 작업 트리 깨끗함 확인.
  - 커밋된 1차 원문(이 파일 1~30줄)은 내가 넘긴 내용 그대로임을 확인(30줄, B6 "아래 N4" 정정 포함). 이 절만 덧붙임.
  - Windows 실행(SPARSE 동작)은 하지 못함.

### 지난 지적 처리
| 항목 | 결과 | 근거 |
|---|---|---|
| N1 D-092 항목 번호 | 해소 | `docs/decisions/D-095-watchdog-cpu-memory-jdk-read.md:5,7` "D-092 4번", `docs/decisions/README.md:101` "D-092 4번 보충". "6번" 인용 남은 곳 없음(grep) |
| N2 낡은 주석 | 해소 | `DiskFreeSource.java:12-13` "OSHI를 쓰지 않고 `java.lang.management`는 `PlatformOsReadings`의 CPU·메모리 읽기만 허용". `WatchdogMain` 클래스 설명도 D-095·기동 거부 조건 반영 |
| N3 3GB 시험 이식성 | 해소(Windows 미실행) | `HeartbeatTest#아주_큰_파일도_상한까지만_읽어_메모리를_쓰지_않는다`가 `Files.newByteChannel(CREATE_NEW, WRITE, SPARSE)` + 끝 1바이트 쓰기로 바뀜, 주석이 Linux·NTFS 차이를 정확히 적음. 시간 단언 2초→10초는 전체 읽기 변이가 `OutOfMemoryError`로 시간 전에 실패하므로 판별력 손실 없음 `[추론]`. NTFS에서 실제 희소로 잡히는지는 Windows에서 확인하지 못함 `[추론]` |
| N4 JVM 거부 경로(권고) | 반영 | `WatchdogMain.run/start`에 `Supplier<Optional<OsReadings>>` 오버로드, 공통 `os(...)`. 시험이 `run` 2, `start` 던짐, 하트비트 미작성을 고정. 변이 3이 잡힘 |

### B 점검표(헤드 9c828bd)
| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 종료 코드 0, 시험 실패 0 |
| B2 | 통과 | 새 커밋은 감시 패키지 안 변경뿐, 새 의존 없음. `RealPathReflectionTest`·고정 예제는 지난 헤드 대비 변경 없음(diff 0). 1차 B2 판단 유지 |
| B3 | 통과 | 설정 키 범위 변경(`cpu.system`·`memory.used`의 경고 = 위험 거부, `WatchdogSettings.java` `usage`)이 `docs/config/config-keys.md` `cpu.system` 행, D-095 3번에 반영. `memory.used` 행은 "범위는 `cpu.system`과 같다"로 따라감. 결정 로그·장애 설계 5.2 0번·장애표 #10 문구 갱신 확인 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 새 코드도 시각을 쓰지 않음(`reportMissing`은 상태 비교만) |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작(결측 경고, JVM 거부, 경고 = 위험 거부)마다 시험 있음: `WatchdogTest#값이_빠지기_시작하면_...`, `WatchdogMainTest#PC_CPU_메모리를_줄_수_없는_JVM이면_기동을_거부한다`, `WatchdogSettingsTest#CPU_메모리_임계_형식과_범위를_검사한다`의 `90:90:3`. 기존 시험 삭제 없음(바뀐 단언은 메시지 문구 `≤`→`<` 1곳과 시간 한도 1곳). 변이 3·4가 잡힘 |
| B7 | risk-ops 검토 필요 | 보호 경로(`autotrade-app-watchdog/*`, `autotrade-monitor-agent-jar/*`, `autotrade-architecture-test/*`)를 바꾸는 실전 관련 PR. 브랜치의 `docs/reviews/pr-122/risk-ops.md` 판정은 아직 1차 "수정 필요"뿐이라 risk-ops 재검토, 독립 QA, 사용자 머지가 남음 |
| B8 | 통과 | 새 로그는 지표 이름 집합(`cpu.system`, `memory.used`, `disk.free-gb:<경로>`)과 결정 번호만 남김. 비밀값 없음. 커밋 메시지에 비밀값 없음 |

### 점검표 밖 발견
- **M1 (참고) 결측 집합이 줄어도 WARN**: `Watchdog.reportMissing`은 빠진 값 집합이 바뀌면 비어 있지 않은 한 늘든 줄든 WARN을 남긴다(예: {CPU, 드라이브} → {CPU}). 메시지가 "자원 값을 읽지 못함: <지금 빠진 값>"이라 내용은 맞고, 로그가 상태 변화 때만 나오므로 차단 사유 아님. 줄어든 경우를 INFO로 나누면 더 정확하다.
- **M2 (참고)** `reportMissing`은 `sampleOnce()` 뒤·`pressure()` 앞의 같은 `try` 안에 있어, 만약 던지더라도 그 점검은 판단 못 함(UNKNOWN)으로 끝난다. 정지 요청을 푸는 쪽으로 새는 길은 없다.
- 1차 N5·N6(범위 묶음, CPU 계속 음수 시 해제 불가·Linux 메모리 값·기본 임계 `[가정]`·"사용자 결정 ①" 저장소 밖 근거)은 그대로 참고 사항이다. N6의 무경보 결측은 R1 반영(WARN 로그)으로 운영자가 알 수 있게 됐다.

### 판정
승인(architect 범위). 지난 수정 필요 N1·N2·N3은 해소, N4 권고도 반영. 확인 못 한 것은 NTFS에서 SPARSE가 실제 희소 파일을 만드는지 하나이고, 이것은 시험의 판정 대상이 아니라 개발 PC의 디스크 사용량 문제이며 Linux CI·이 검토 빌드에서는 통과한다. 실전 관련 PR이므로 risk-ops 재검토 판정, 독립 QA, 사용자 머지가 끝나야 머지할 수 있다.
