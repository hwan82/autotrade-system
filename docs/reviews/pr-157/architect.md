# architect 검토: 감시 결측 경고: 회복 직후 재결측 바로 알림, 드라이브 읽기 실패 원인, 기동 실패 출력 시험(D-108)
- 대상: PR #157 / 브랜치 `claude/project-thread-133h3h` / 커밋 970e099 / `git diff origin/main...HEAD`(merge-base = origin/main b17c8ff, 커밋 1개, 11파일 +378 −25)
- 점검표: B
- 직접 실행한 것:
  - 별도 clone(스크래치 `review-architect`)에서 `./gradlew -I <스크래치>/mirror.gradle build --no-daemon` → **BUILD SUCCESSFUL**(4m 13s, 138 tasks). `-I mirror.gradle`은 저장소 밖 init 스크립트로 의존 내려받기 저장소만 공개 Maven Central 미러(maven-central.storage-download.googleapis.com/maven2)로 바꾼다. 빌드 로직·테스트는 바꾸지 않는다.
  - `:autotrade-app-watchdog` 시험 결과: DiskFreeSourceTest 2, HeartbeatWatchTest 7, OsMetricsSourceTest 4, StopRequestsTest 7, WatchdogMainTest 15, WatchdogSettingsTest 8, WatchdogTest 25 — skipped 0, failures 0, errors 0.
  - 변이 5건을 직접 넣고 `:autotrade-app-watchdog:test`(각각 원본으로 되돌림, `git status` 깨끗함 확인):
    - M1 `Watchdog.java:224` `afterRecovery = false` → 4건 실패
    - M2 `Watchdog.java:213` 회복 INFO 간격 조건 제거 → 3건 실패
    - M3 `Watchdog.java:237` `causes(missing)` 제거 → 1건 실패
    - M4 `Watchdog.java:240` 알림 조건을 `newName`만으로 → 2건 실패
    - M5 `DiskFreeSource.java:64` 이전 원인을 지우지 않게 → 1건 실패

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 BUILD SUCCESSFUL(ArchUnit·`ModuleOwnershipTest`·`RealPathReflectionTest`·순수 모듈 금지 의존 검사 포함) |
| B2 | 통과 | 바뀐 운영 코드는 `autotrade-app-watchdog`의 `Watchdog.java`·`DiskFreeSource.java` 두 파일뿐이고 새 import는 `java.util.Map`·`TreeMap`(JDK). 새 모듈·새 의존·새 패키지 없음, `build.gradle.kts`·ArchUnit 규칙 변경 없음. `DiskFreeSource`는 여전히 JDK `FileStore` 주입(`FreeSpace`)만 쓴다(D-086/D-090/D-095 허용 범위 안). `RealPathReflectionTest` 통과 |
| B3 | 통과 | 인터페이스·스키마·설정 키·REST 변경 없음(`WatchdogSettings` 무변경, `EventRoutes` 종류 추가 없음 — 기존 `READING_MISSING`·`READING_RECOVERED`만 씀). 바뀐 것은 로그·알림 정책이며 ADR-011 3장 결측 로그 항목, D-108(새 결정, D-096 2번 대체를 명시하고 D-096 본문은 고치지 않음), 기획서 v14.46 MON-02·변경 이력, runbook 6단계, broker-and-fault-design 5.2에 반영. D-108 번호는 main·열린 브랜치 어디와도 겹치지 않음(D-107은 `claude/project-thread-4l8sa4`에 따로 있음) |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 감시 쪽 시각은 주입 `Clock`(`Watchdog.java:103` `clock.instant()`)만 쓰고 새 `now()` 직접 호출 없음. 원인 맵은 `TreeMap`, `causes`는 `TreeSet` 순서로 돌아 출력 순서가 결정적이다 |
| B5 | 해당 없음 | 금액 계산 없음(드라이브 여유 GB 정수 나눗셈은 기존 그대로) |
| B6 | 통과 | 새 동작마다 시험: `WatchdogTest#회복_알림_뒤_다시_빠지면_간격을_기다리지_않고_바로_알린다`, `#간격_안에_두_번째_회복은_미뤘다가_간격이_찬_뒤_값이_다_있으면_남긴다`, `#점검마다_오가도_간격마다_WARN_둘_INFO_하나를_넘지_않고_마지막_로그가_틀린_회복이_아니다`, `#드라이브를_읽지_못한_원인을_결측_경고에_적는다`, `DiskFreeSourceTest` 2건, `WatchdogMainTest#진입점은_기동_실패를_표준_오류에_알리고_프로그램_오류면_로그에_예외_흐름을_남긴다`와 `#진입점은_설정_폴더_경로가_틀리면_설정_오류로_거부한다`의 표준 오류 문구 단언. 기존 시험 `#같은_값이_빠졌다_돌아오기를_되풀이해도_간격마다_한_번만_알린다`는 기대값을 1→2, 2→3, 3→4로 바꿨는데, 이는 정책 변경(D-108 1번)의 직접 결과이고 단언을 줄이지 않고 `missingRecoveries()==1` 단언을 더했다(약화 아님). 삭제·`@Disabled`·건너뛰기 없음. 변이 M1~M5 모두 시험이 잡음 |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-watchdog/*`는 `scripts/ci/protected-paths.txt`의 실전 경로 런타임 모듈이다. **risk-ops 검토 필요**, 사용자 머지·머지 전 독립 QA 대상(지침 10·11장). 정지 요청 판정 자체는 바뀌지 않음을 코드로 확인: `pressure`는 점검마다 실제 스냅숏으로 계산(`Watchdog.java:110`)되고 `reportMissing`의 로그 미루기와 무관 |
| B8 | 통과 | 새 로그 문구는 값 이름(`disk.free-gb:<설정한 경로>`)과 예외 종류·메시지 한 줄(160자, 개행 제거, `DiskFreeSource.java:74-81`)뿐이고 알림 본문에는 원인을 넣지 않는다(`Watchdog.java:242`). 예외 원천은 JDK `FileStore` 읽기이며 비밀값을 다루지 않는다. diff 전체에 키·토큰·계좌번호 없음. 메시지에 사용자 홈 경로(사용자 이름)가 들어갈 수는 있으나 이미 값 이름에 같은 경로가 찍혀 왔으므로 새 노출이 아니다 `[추론]` |

## 점검표 밖 발견
설계·정확성 점검(직접 추적):
- 상태 전이 추적: `warnedSinceRecovery`·`lastMissingWarning`·`lastRecoveryInfo`·`warnedReadings`로 5초 간격 교대 결측을 손으로 따라가면, 회복 INFO 뒤 첫 결측은 항상 `afterRecovery`로 즉시 WARN(`Watchdog.java:224-226`)이고, INFO는 `lastRecoveryInfo`에서 10분마다 최대 1회(`:213`)라 같은 값 기준 간격당 WARN ≤2·INFO ≤1이다. 정상 상태(교대가 계속)에서는 간격당 WARN 1·INFO 1로 수렴한다. "값이 빠진 점검 뒤 마지막 결측 로그는 늘 '읽지 못함'"이 성립한다(회복 INFO는 값이 다 있을 때만 나옴).
- 알림 큐 억제와의 상호작용(테스트는 `Alerts` 대역으로 확인하므로 실제 큐를 별도로 읽음): `NotificationQueue.accept`(autotrade-notification-jar `NotificationQueue.java:153`)는 대상별 **마지막 종류**가 같을 때만 10분 억제한다. 대상 `readings`에서 MISSING → RECOVERED → MISSING 순서라 두 번째 MISSING은 억제되지 않는다. RECOVERED가 INFO 상한으로 버려져도 `drop`이 마지막 종류를 RECOVERED(시각 null)로 남겨 다음 MISSING이 통과한다. 즉 QA R1 문제가 알림 채널에서 다시 생기지 않는다.
- 여러 값이 서로 다른 조합으로 빠지면 `newName` 경로로 간격당 WARN이 값 개수만큼 더 나올 수 있다. 이는 이 PR 이전부터의 동작이고 D-108의 상한 주장은 "같은 값" 기준이라 모순은 아니다(정보).

사소한 것(수정 필요 아님, 다음에 함께 손볼 때):
- N1 `WatchdogTest.java:218` 시험 이름 `..._간격마다_한_번만_알린다`가 새 기대값(회복 뒤 재결측 WARN 포함 2)과 어긋난다. D-108 검증 절이 이를 설명하지만 이름만 보면 오해 여지가 있다.
- N2 `DiskFreeSource.java:81` `substring(0, CAUSE_MAX)`이 서로게이트 쌍 가운데를 자를 수 있다(로그 한 글자 깨짐 정도, 판정 영향 없음). `CAUSE_MAX` 선언(`:84`)이 사용보다 뒤에 있는 것은 모양 문제.
- N3 `#점검마다_오가도_...` 시험의 개수 단언은 상한(≤)만 본다. 다만 같은 시험의 "마지막 결측 로그" 단언이 WARN이 실제로 있어야 통과하고, 하한은 다른 두 시험이 고정하므로 공백은 아니다.
- N4 D-108 "위험과 한계"대로 값이 모두 돌아와도 회복 INFO가 최대 10분 늦을 수 있다(안전한 쪽). 6단계 운용 첫 주 점검에서 소음·오해 여부를 같이 보면 된다.

## 판정
승인 (아키텍처·코드 기준. 보호 경로 변경이므로 B7대로 risk-ops 검토와 사용자 머지·독립 QA가 별도로 필요하다)

## 재검토 2026-10-17
- 대상: PR #157 / 커밋 30bcfd4 / `git diff origin/main...HEAD` 전체(merge-base = origin/main b17c8ff). 앞 검토(970e099) 뒤 커밋은 75fd0f9(이 원문), 9fe0f9d(risk-ops 원문), 30bcfd4(검토 반영). 30bcfd4가 바꾼 것은 `DiskFreeSource.java`, `DiskFreeSourceTest.java`, `WatchdogTest.java`(시험 이름 1줄), D-108 검증 절 2줄이다. 이 원문(`architect.md`)은 970e099 뒤에 다른 커밋이 고치지 않았다(`git diff 75fd0f9 HEAD -- docs/reviews/pr-157/architect.md` 비어 있음).
- 점검표: B
- 직접 실행한 것:
  - 같은 clone에서 `./gradlew -I <스크래치>/mirror.gradle build --no-daemon` → **BUILD SUCCESSFUL**(1m 2s, 138 tasks 중 27 실행·111 up-to-date). 바뀐 감시 모듈과 그 모듈의 의존 모듈(`autotrade-architecture-test` 포함)은 다시 돌았다. 미러 init 스크립트의 의미는 앞 절과 같다.
  - 감시 모듈 시험: DiskFreeSourceTest 4(2 늘어남), HeartbeatWatchTest 7, OsMetricsSourceTest 4, StopRequestsTest 7, WatchdogMainTest 15, WatchdogSettingsTest 8, WatchdogTest 25. skipped 0, failures 0, errors 0.
  - 변이 2건(각각 원본으로 되돌렸고, `git status`가 깨끗함을 확인):
    - R1: `DiskFreeSource.read()` 첫머리의 `failures = Map.of();` 삭제 → 1건 실패(`DiskFreeSourceTest#읽기_중_Error가_나가도_이전_점검의_원인을_남기지_않는다`)
    - R2: 자르는 위치를 늘 `CAUSE_MAX`로 → 1건 실패(`#원인을_자를_때_서로게이트_쌍을_가르지_않는다`)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 BUILD SUCCESSFUL |
| B2 | 통과 | 새 의존·모듈·패키지가 없다. 30bcfd4는 JDK `Character.isHighSurrogate`만 더했다. ArchUnit·`RealPathReflectionTest` 통과 |
| B3 | 통과 | 계약 변경이 없다. D-108 검증 절이 바뀐 시험 이름과 새 시험에 맞게 갱신됐다 |
| B4 | 해당 없음 | 시뮬레이션 변경이 없다. 새 시각·난수 사용도 없다 |
| B5 | 해당 없음 | 금액 계산이 없다 |
| B6 | 통과 | 새 동작 두 가지(읽기 첫머리 비우기, 서로게이트 보존 자르기)에 각각 시험이 있고, 변이 R1·R2를 잡는다. `WatchdogTest`는 시험 이름만 바뀌었고 본문 단언은 그대로다. 삭제나 건너뛰기는 없다. 앞 절 변이 M1~M5의 대상 코드(`Watchdog.java`)는 30bcfd4에서 바뀌지 않았다 |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-app-watchdog/*`는 보호 경로다. risk-ops 원문 `docs/reviews/pr-157/risk-ops.md`(9fe0f9d)가 있고, 30bcfd4는 그 원문의 K1을 반영한 것이다. 반영본에 대해서는 risk-ops 재검토가 필요하다. 이 PR은 사용자 머지와 머지 전 독립 QA 대상이다 |
| B8 | 통과 | 바뀐 것은 원인 문구를 자르는 위치뿐이다. 새로 로그에 남는 항목은 없다 |

## 점검표 밖 발견(재검토)
- K1 반영(`DiskFreeSource.java` `read()` 첫머리 `failures = Map.of()`) 확인: `FreeSpace`가 `Error`를 던지면 그 예외는 `catch (IOException | RuntimeException)` 밖으로 나간다. 그러면 `MonitorAgent.sampleOnce`(autotrade-monitor-agent-jar `MonitorAgent.java:70-77`)가 그 수집원을 버린다. 이때 맵은 이미 비어 있으므로, `Watchdog.causes`가 이전 점검의 원인을 붙이지 않는다. 같은 점검 스레드에서 쓰고 읽으며 필드는 `volatile`이다. 경쟁 상태는 없다.
- N2 반영 확인: 160번째 문자(색인 159)가 high surrogate이면 159에서 자른다. 그래서 낮은 surrogate만 남는 일이 없다. 상수 선언도 사용하는 곳 앞으로 옮겼다. N1 시험 이름도 바뀌었다.
- 앞 절의 N3·N4는 정보 항목이고, 이번에 바꿀 대상이 아니다.
- 새 지적은 없다.

## 판정(재검토)
승인. 아키텍처·코드 기준이다. 보호 경로 변경이므로 B7대로 risk-ops 재검토, 사용자 머지, 독립 QA를 별도로 거쳐야 한다.
