# architect 검토: 감시 보강 — 파일 읽기 4KB 상한·남은 임시 파일 정리·부분 삭제 실패 시험·설정 폴더 경고(PR #116 후속)
- 대상: PR #118 / 브랜치 `claude/project-thread-0ganrn` / 커밋 c19d28c (de41b05 포함) / `git diff origin/main...HEAD` 전체(16개 파일, +211 −19)
- 점검표: B
- 직접 실행한 것:
  - 작업 폴더에서 `./gradlew build --no-daemon` → BUILD SUCCESSFUL, 종료 코드 0(136 작업, 대부분 UP-TO-DATE).
  - 캐시를 믿지 않으려고 별도 worktree(HEAD c19d28c)에서 `./gradlew build --rerun-tasks --no-daemon` → BUILD SUCCESSFUL in 3m 49s, 136 작업 모두 실행. 시험 결과 XML 집계: `autotrade-app-watchdog` 28건, `autotrade-monitor-agent-jar` 24건, `autotrade-architecture-test` 43건, 실패·오류·건너뜀 0.
  - 같은 worktree에서 변이 시험 12건(아래 표). 실험 뒤 `git checkout -- .`로 되돌림. 작업 폴더는 건드리지 않음.

## 변이 시험

| # | 변이 | 결과 | 잡은 시험 |
|---|---|---|---|
| M1 | 엔진 `dependencies`에 `:autotrade-monitor-sources` 추가 | 잡힘 | `:autotrade-app-engine:verifyProcessIsolation` "들어오면 안 되는 의존이 있습니다: [:autotrade-monitor-sources]" |
| M2 | 감시 `dependencies`에 `:autotrade-monitor-sources` 추가 | 잡힘 | `:autotrade-app-watchdog:verifyProcessIsolation` 같은 메시지 |
| M3 | `SmallFile.read` 크기 검사 제거 | 잡힘 | `HeartbeatTest#작은_파일_읽기는_상한을_넘으면_읽지_않는다` |
| M4 | 경계 `>` → `>=` | 잡힘 | 같은 시험(정확히 4096바이트 읽기) |
| M5 | `deleteLeftoverTemps` 숫자 검사 제거 | 잡힘 | `HeartbeatTest#기록기를_만들면_이전_실행이_남긴_자기_임시_파일만_거둔다` |
| M6 | `HeartbeatWriter` 생성자의 정리 호출 제거 | 잡힘 | 같은 시험 |
| M7 | `StopRequests.adoptLeftovers`의 정리 호출 제거 | 잡힘 | `StopRequestsTest#기동_때_남은_임시_파일을_거두고_…` |
| M8 | `release()`에서 삭제 실패해도 꺼짐(`ok = true`) | 잡힘 | `StopRequestsTest#한_파일만_못_지우면_켜진_상태를_유지하고_…` (PR #116 QA Q1·변이 M7 해소) |
| M9 | 첫 점검 판정 `allMatch` → `anyMatch` | 잡힘 | `HeartbeatWatchTest#첫_점검에서_모두_꺼짐이면_알리고_…` |
| **M10** | `HeartbeatWatch.check`를 상한 없는 `Files.readString`으로 되돌림 | **살아남음** | 없음(아래 R1) |
| **M11** | `StopRequests.requestedAt`을 상한 없는 `Files.readString`으로 되돌림 | **살아남음** | 없음(아래 R1) |
| M12 | `WatchdogSettings.load`를 상한 없는 읽기로 되돌림 | 잡힘 | `WatchdogSettingsTest#상한보다_큰_설정_파일은_읽지_않고_기동을_거부한다` |

M10·M11 원인 확인: 시험 데이터를 "상한 없이 읽어도 결과가 같은" 내용으로 만들었다.
- `HeartbeatWatchTest.java:116` 은 정상 하트비트 뒤에 `"#".repeat(5000)`을 붙인다. `#####…` 줄은 `=`이 없어 `Heartbeat.parse`(Heartbeat.java:62-64)가 어차피 빈 값을 돌려주므로 상한이 없어도 UNREADABLE이다.
- `StopRequestsTest.java:147` 은 `"x".repeat(5000)`이다. `requested-at=` 줄이 없어 상한이 없어도 `now`로 이어받고, 시험의 시계도 `now`와 같은 시각이라 구별이 안 된다.
- 검증: worktree에서 시험 데이터만 `format() + "\n".repeat(5000)`(빈 줄은 parse가 건너뜀, Heartbeat.java:59-61)과 `"requested-at=2026-10-14T00:00:00Z\n" + "\n".repeat(5000)`로 바꾸면 HEAD에서는 둘 다 통과하고, M10·M11을 넣으면 두 시험이 실패했다. 즉 두 줄 수정으로 고정된다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 작업 폴더 `./gradlew build` 종료 0, 별도 worktree `./gradlew build --rerun-tasks` 136 작업 실행·BUILD SUCCESSFUL. 시험 실패 0 |
| B2 | 통과 | 새 운영 클래스 `SmallFile`은 `autotrade-monitor-agent-jar`의 `com.autotrade.monitor`(SmallFile.java:1), 도메인 의존 없음(java.io·java.nio만). `RealPathReflectionTest`·`ModuleOwnershipTest` 포함 architecture-test 43건 통과. 엔진·감시 `processIsolation.forbiddenProjects`에 `:autotrade-monitor-sources` 추가(autotrade-app-engine/build.gradle.kts:14, autotrade-app-watchdog/build.gradle.kts:14)가 실제로 동작함을 M1·M2로 확인. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 공개 타입 추가(`SmallFile`)·감시 설정 파일 4KB 제약·임시 파일 정리 규칙을 ADR-011 1·2·3장(docs/adr/ADR-011-watchdog-heartbeat.md:14,18,21,25,31,33)과 config-keys.md:53에 반영. 정지 요청 파일 형식은 계약 아님(ADR-011:21) 유지. REST·DB 스키마 변경 없음 |
| B4 | 통과 | 새 코드에 `now()`·난수 없음. `HeartbeatWatch.check`는 인자 `now`만 씀(HeartbeatWatch.java:59-95), `StopRequests.adoptLeftovers(Instant now)`. 시뮬레이션 모듈 무변경, 골든 시험은 `--rerun-tasks` 빌드에서 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | **실패** | 새 동작 대부분은 변이로 고정됨(M1~M9, M12). 그러나 PR #116 QA R1의 핵심인 하트비트 읽기 상한과 남은 정지 요청 파일 이어받기 상한(R1이 "기동 때 감시가 뜨지 못한다"고 지목한 `adoptLeftovers` → `requestedAt` 경로)은 이름만 그 동작을 말하는 시험이 있고 실제로는 고정하지 않는다(M10·M11 생존, 아래 R1). 시험 삭제·건너뜀·약화는 없음 |
| B7 | risk-ops 검토 필요 | 보호 경로 변경: `autotrade-app-engine/*`, `autotrade-app-watchdog/*`, `autotrade-monitor-agent-jar/*`(scripts/ci/protected-paths.txt). `HeartbeatWriter` 생성자는 실전 엔진 기동 경로(EngineMain.java:91)에서 새로 파일 시스템 정리를 한다. 장애표 #8 문서(broker-and-fault-design.md:167)도 바뀜. `docs/reviews/pr-118/risk-ops.md`가 있으나 그 판정은 이 검토가 판단하지 않는다 |
| B8 | 통과 | diff에 키·계좌·토큰 없음. 새 로그는 설정 폴더 절대 경로(WatchdogMain.java:64-66, HeartbeatWatch.java:45-46)와 IOException 메시지(경로·크기, WatchdogSettings.java:72)뿐. `watchdog.properties`에는 비밀값을 넣지 않는다(config-keys.md:53) |

## 점검표 밖 발견
- **R1 (수정 필요, 시험) 읽기 상한 두 곳이 시험으로 고정되지 않는다.** `HeartbeatWatchTest#상한보다_큰_하트비트_파일은_읽지_않고_읽을_수_없음이다`(HeartbeatWatchTest.java:110-117)와 `StopRequestsTest#기동_때_남은_임시_파일을_거두고_너무_큰_남은_파일은_지금_시각으로_이어받는다`(StopRequestsTest.java:144-161)는 상한을 빼도 통과한다(M10·M11). 고칠 것: 하트비트 시험은 상한만 넘고 내용은 유효한 파일(`format() + "\n".repeat(5000)`), 정지 요청 시험은 상한만 넘고 과거 `requested-at`을 담은 파일(`"requested-at=2026-10-14T00:00:00Z\n" + "\n".repeat(5000)`)을 쓰고 이어받은 요청 시각이 `2026-10-15T00:00:00Z`(지금)인지 단언한다. 위 변이 절에서 이 수정이 M10·M11을 잡는 것을 확인했다.
- N1 (비차단) `SmallFile.read`는 `Files.size` 뒤 `Files.readString`이라 그 사이 파일이 바뀌면 상한을 넘겨 읽을 수 있다(SmallFile.java:22-27). 쓰는 쪽이 우리 프로세스뿐이라 낮지만, `InputStream.readNBytes(MAX_BYTES + 1)`로 읽고 길이로 거르면 검사와 읽기가 하나가 되고 코드도 짧다.
- N2 (비차단) `HeartbeatWriter` 생성자가 이제 폴더를 읽고 지운다(HeartbeatWriter.java:47). `deleteLeftoverTemps`는 `IOException|RuntimeException`을 삼키지만 try 밖의 `target.toAbsolutePath()`(SmallFile.java:34)가 던질 수 있는 `IOError`는 나간다. 엔진에서는 Guard·명령 채널을 연 뒤 기록기를 만들므로(EngineMain.java:84-92) 이 경우 엔진 기동이 실패한다. 실제 발생 가능성은 매우 낮다[추론]. `Heartbeat.file`은 설정 폴더 기준 경로를 그대로 돌려주므로(Heartbeat.java:41) `toAbsolutePath` 호출까지 try 안에 넣는 편이 "정리 실패는 기동을 막지 않는다"는 주석(SmallFile.java:29-31)과 맞는다.
- N3 (비차단, 참고) 같은 이름 프로세스가 동시에 둘 뜨면 늦게 뜬 쪽이 먼저 뜬 쪽의 쓰는 중 임시 파일을 지워 한 주기 쓰기가 실패한다(로그 한 줄, 다음 주기 복구). 엔진은 명령 채널을 연 뒤에, 실험실은 스프링 기동(포트) 뒤에 기록기를 만들어 중복 기동은 그 앞에서 막힐 것으로 본다[추론, 미검증]. 감시·게이트웨이는 단일 실행 보장을 이 검토에서 확인하지 못했다. 판정에는 영향 없음(한 주기 손실뿐).
- N4 (비차단) `forbiddenProjects`는 프로젝트만 막는다. OSHI·JNA를 엔진·감시에 직접 의존으로 넣는 경우는 `forbiddenGroups`에 `com.github.oshi`, `net.java.dev.jna`를 더하면 같은 검사로 막힌다(D-086 리플렉션 검사가 일부 잡을 수 있으나 빌드 단계에서 먼저 막는 편이 싸다).
- N5 (참고) `첫_점검에서_모두_꺼짐이면_…` 시험은 플래그만 보고 WARNING 로그가 나오는지는 보지 않는다. 로그 전용 기능이라 비차단.
- 확인: PR #116 architect N2(시작 로그의 설정 폴더 WatchdogMain.java:63-66, 첫 점검 경고 HeartbeatWatch.java:92-99), N3(임시 파일 정리, `<이름>.<숫자>.tmp`만 지우고 다른 이름은 남김을 시험이 고정), N4(장애표 #8 표기), PR #116 QA Q1(M8로 확인)은 반영됐다. 같은 폴더의 다른 `.tmp` 사용처(ExperimentJobFiles.java:54 `<job>.status.tmp` 등)와 이름이 겹치지 않는다.

## 판정
수정 필요(R1: `HeartbeatWatchTest`·`StopRequestsTest`의 상한 시험이 상한을 빼도 통과함. 변이 M10·M11 생존. 시험 데이터 두 줄 수정으로 해소)
