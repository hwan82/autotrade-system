# risk-ops 검토: PR #118 감시 보강(파일 읽기 4KB 상한, 남은 임시 파일 정리, 부분 삭제 실패 시험, 설정 폴더 경고, PR #116 후속)
- 대상: PR #118 / 브랜치 `claude/project-thread-0ganrn` / 커밋 `c19d28c`(HEAD), `de41b05` / `git diff origin/main...HEAD` 전체(16개 파일, +211 −19). 바뀐 줄은 모두 읽었다. 그 변경이 닿는 실전 경로 코드도 함께 봤다: `EngineMain.start`(엔진 기동 때 `HeartbeatWriter` 생성자가 새로 폴더 정리를 함), `AtomicFile`, `Heartbeat.parse`, `StopRequests` 전체, `HeartbeatWatch.check`, `build-logic/.../autotrade.process-conventions.gradle.kts`(격리 검사 구현).
- 점검표: A
- 직접 실행한 것:
  - HEAD `c19d28c`를 별도 worktree에 두고 `./gradlew build --no-daemon`을 돌렸다. **BUILD SUCCESSFUL(종료 코드 0)**. 전체 시험 1261건, 실패 0, 오류 0, 건너뜀 0. 모듈별 결과(시험/실패/오류/건너뜀)는 다음과 같다.
    - `:autotrade-risk:test` 55/0/0/0
    - `:autotrade-architecture-test:test` 43/0/0/0
    - `:autotrade-app-engine:test` 52/0/0/0
    - `:autotrade-app-watchdog:test` 28/0/0/0
    - `:autotrade-monitor-agent-jar:test` 24/0/0/0
    - `:autotrade-app-gateway:test` 50/0/0/0
    - `:autotrade-app-lab:test` 181/0/0/0
  - 변이 시험. 두 번째 worktree에서 운영 코드나 빌드 파일을 하나씩 바꾸고 관련 시험을 돌린 뒤 원복했다. 끝난 뒤 그 worktree의 `git status`는 비어 있다.

    | 변이 | 결과 |
    |---|---|
    | V1 `SmallFile.read`의 크기 검사를 없앰 | 잡힘: `HeartbeatTest#작은_파일_읽기는_상한을_넘으면_읽지_않는다`, `WatchdogSettingsTest#상한보다_큰_설정_파일은_읽지_않고_기동을_거부한다` 실패. **`HeartbeatWatchTest#상한보다_큰_하트비트_파일은_읽지_않고_읽을_수_없음이다`와 `StopRequestsTest#기동_때_남은_임시_파일을_거두고_너무_큰_남은_파일은_지금_시각으로_이어받는다`는 통과(못 잡음)** |
    | V2 `deleteLeftoverTemps`의 숫자(PID) 검사를 없앰 | 잡힘: `HeartbeatTest#기록기를_만들면_이전_실행이_남긴_자기_임시_파일만_거둔다`, `StopRequestsTest#기동_때_남은_임시_파일을_…` 실패 |
    | V3 `HeartbeatWriter` 생성자의 정리 호출을 지움 | 잡힘: `HeartbeatTest` 1건 실패 |
    | V4 `StopRequests.adoptLeftovers`의 정리 호출을 지움 | 잡힘: `StopRequestsTest` 1건 실패 |
    | V5 `release()`가 하나를 못 지워도 꺼지게 함(QA 변이 M7) | 잡힘: `StopRequestsTest#한_파일만_못_지우면_켜진_상태를_유지하고_다음_점검에_다시_쓰고_다시_지운다` 실패 |
    | V6 첫 점검 "모두 꺼짐" 판정을 늘 false로 함 | 잡힘: `HeartbeatWatchTest#첫_점검에서_모두_꺼짐이면_알리고_하나라도_있으면_알리지_않는다` 실패 |
    | V7 엔진에 `implementation(project(":autotrade-monitor-sources"))`를 더함(금지 목록 유지) | 잡힘: `:autotrade-app-engine:verifyProcessIsolation`이 "들어오면 안 되는 의존이 있습니다: [:autotrade-monitor-sources]"로 실패 |
    | V8 감시에 같은 의존을 더함(금지 목록 유지) | 잡힘: `:autotrade-app-watchdog:verifyProcessIsolation` 실패 |
    | V9 대조: 엔진 금지 목록에서 빼고 의존을 더함 | `verifyProcessIsolation`은 통과한다. 기존 `RealPathReflectionTest`(실전 경로 런타임 구성 고정 목록, 리플렉션 금지) 2건이 실패해 잡는다. 새 금지 항목은 이미 있던 보호에 한 겹을 더한 것이고, 기존 보호를 대신하지 않는다 |
    | **V10 `HeartbeatWatch.check`와 `StopRequests.requestedAt`가 `SmallFile.read` 대신 예전처럼 `Files.readString`을 쓰게 되돌림** | **못 잡음. `:autotrade-app-watchdog:test` 28건 모두 통과** |

## 이번 PR이 실전 경로에 닿는 곳
- **엔진 기동 경로**: `HeartbeatWriter` 생성자가 이제 `SmallFile.deleteLeftoverTemps`를 부른다(HeartbeatWriter.java:49-50). 실전 엔진에서는 `EngineMain.start`가 Guard(모두 OFF)를 만들고 명령 채널을 연 다음 하트비트를 만든다(EngineMain.java:83-93). 그래서 이 정리 단계는 Guard가 OFF로 확정된 뒤에 돈다.
  - 이 메서드는 `IOException`과 `RuntimeException`을 모두 삼킨다(SmallFile.java:33-58). `DirectoryIteratorException`도 `RuntimeException`이라 함께 삼킨다.
  - 하트비트 폴더가 없거나 폴더가 아니면 0을 돌려준다. 기존 `EngineMainTest#하트비트를_쓰지_못해도_엔진은_뜨고_Guard는_OFF이며_상태에_응답한다`는 하트비트 폴더 자리에 파일을 두고 이 경로를 지난다. 이번 빌드에서도 통과했다.
  - 엔진은 명령 채널 잠금 때문에 두 번째 인스턴스가 하트비트 단계까지 오지 못한다(ADR-006 "엔진 잠금: 소켓을 지워도 두 번째 서버 거부", `ControlServer.start`가 하트비트보다 먼저). 그래서 살아 있는 엔진의 임시 파일을 다른 엔진이 지우는 경우는 없다.
- **격리 검사**: 엔진과 감시의 `processIsolation.forbiddenProjects`에 `:autotrade-monitor-sources`를 더했다. 검사 구현은 런타임 클래스패스의 모든 구성 요소(간접 의존 포함)를 본다(autotrade.process-conventions.gradle.kts:18-21). V7·V8로 실제로 잡는 것을 확인했다. 보호가 강해지는 쪽이다.
- **실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 보호 시세 경로, 서버 측 손절은 바뀌지 않았다.** `autotrade-risk`와 `autotrade-architecture-test`의 diff는 비어 있다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard`·설정 로더·`EngineMain.startGuard`는 바뀌지 않았다. 새로 생긴 엔진 쪽 동작(임시 파일 정리)은 Guard를 만든 뒤 돌고 예외를 밖으로 내지 않는다(EngineMain.java:83-93, SmallFile.java:33-58). `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `EngineMainTest#하트비트를_쓰지_못해도_엔진은_뜨고_Guard는_OFF이며_상태에_응답한다`가 통과했다 |
| A2 | 통과 | 주문 출구, Guard 팩토리, `ArchitectureTest`, `RealPathReflectionTest`는 바뀌지 않았다(`autotrade-architecture-test` diff 없음). 엔진 런타임에 새로 들어온 `SmallFile`(`DirectoryStream`, `Files.newDirectoryStream` 사용)도 실전 경로 허용 목록 검사를 통과했다. `:autotrade-architecture-test:test` 43건 통과 |
| A3 | 해당 없음 | 증권사 연동과 주문 라우팅은 바뀌지 않았다 |
| A4 | 해당 없음 | 주문 타임아웃 처리는 바뀌지 않았다 |
| A5 | 해당 없음 | 멱등 키는 바뀌지 않았다 |
| A6 | 해당 없음 | 블랙리스트와 수동 보유 필터는 바뀌지 않았다 |
| A7 | 해당 없음 | 계좌별 큐, 토큰 버킷, 킬스위치는 바뀌지 않았다. 하트비트와 정지 요청 파일은 프로세스 단위다 |
| A8 | 통과 | 엔진에 새로 들어온 일은 기동 때 한 번 하는 로컬 폴더 목록 읽기와 삭제뿐이다. 예외를 삼키고, 폴더가 없으면 건너뛴다. 감시 쪽 4KB 상한은 커진 파일 하나가 감시 힙을 채우는 것을 막는다. 그래서 감시가 5.2 0번(정지 요청 파일)을 계속 수행할 수 있다. 부분 삭제가 실패해도 두 정지 요청 파일이 늘 함께 켜져 있는 것은 V5가 잡는 시험으로 고정됐다. 엔진·감시에 OSHI·JNA가 들어오지 않는 경계는 V7·V8로 확인했다. 정지 순서 1~6번과 보유 종목 보호 유지 원칙은 바뀌지 않았다 |
| A9 | 통과 | 보호 시세 경로, 서버 측 손절 코드, 6.1 문서는 바뀌지 않았다 |
| A10 | 통과 | 추가된 diff를 grep했다(appkey, secret, token, password, account, 계좌번호, 8자리 이상 숫자). 해당하는 것이 없다. 새 로그와 예외 메시지에는 설정 폴더 경로, 파일 경로, 파일 크기만 들어간다(HeartbeatWatch.java:97-98, WatchdogMain.java:64-66, WatchdogSettings.java:72, SmallFile.java:24). `watchdog.properties`에는 비밀값을 넣지 않는다(config-keys.md). 시험은 `@TempDir`만 쓴다. gitleaks는 로컬에 없어 CI 결과로 확인한다 |
| A11 | **실패** | 보호 시험의 삭제·약화는 없다. 금지 목록 강화(V7·V8)와 정리·부분 삭제·첫 점검 경고(V2~V6)는 실패하는 경우까지 시험한다. 그러나 이 PR의 핵심인 "감시 파일 읽기 4KB 상한"은 감시가 실제로 읽는 두 경로에서 시험이 회귀를 잡지 못한다(V10). 아래 K1 |
| A12 | 통과 | 장애표 6장 17종과 O1~O8의 행 수, 대응 내용은 그대로다. #8 감지 문구만 기준값을 설정 키(`heartbeat-stale-seconds`, 기본 15초)로 적었고 코드(HeartbeatWatch.java:71)와 맞는다. 장애 시험 8종(7장)의 시나리오와 합격 기준은 바뀌지 않았다 |
| A13 | 해당 없음 | 시장별 경로는 바뀌지 않았다. 시각은 프로세스 생존 판단용 UTC `Instant`다 |
| A14 | **실패** | ADR-011, config-keys.md, broker-and-fault-design.md #8은 코드와 맞는다. 그러나 결정 로그 D-092는 이번 변경을 따라오지 않았다. 아래 K2 |

## 수정 요청
- **K1 (A11) 상한을 시험한다는 시험 두 건이 상한 없이도 통과한다.**
  - `HeartbeatWatchTest#상한보다_큰_하트비트_파일은_읽지_않고_읽을_수_없음이다`(HeartbeatWatchTest.java:110-117)는 정상 하트비트 뒤에 `"#".repeat(5000)`을 붙인다. `Heartbeat.parse`는 `=`가 없는 줄을 만나면 바로 빈 값을 돌려준다(Heartbeat.java:62-65). 그래서 상한이 없어도 결과가 UNREADABLE이 된다.
  - `StopRequestsTest#기동_때_남은_임시_파일을_거두고_너무_큰_남은_파일은_지금_시각으로_이어받는다`(StopRequestsTest.java:144-161)는 `"x".repeat(5000)`을 쓴다. 이 내용에는 `requested-at`이 없으므로 상한이 없어도 지금 시각을 이어받는다.
  - 그 결과 V10에서 `HeartbeatWatch`와 `StopRequests`를 예전처럼 상한 없는 `Files.readString`으로 되돌려도 감시 시험 28건이 모두 통과한다. 상한은 `SmallFile` 단위 시험과 `WatchdogSettings` 경로에서만 지켜진다. 감시가 실제로 점검마다 읽는 하트비트 파일과, 기동 때 이어받는 정지 요청 파일에서는 지켜지지 않는다.
  - 고칠 방법: 상한이 없으면 결과가 달라지는 입력을 쓴다.
    - 하트비트 시험은 정상 하트비트 다섯 줄에 빈 줄(`"\n".repeat(5000)`)을 더한다. 빈 줄은 `parse`가 건너뛰므로, 상한이 없으면 ALIVE가 나와 시험이 실패한다.
    - 정지 요청 시험은 `requested-at=<지금보다 이른 시각>\n`에 빈 줄을 더해 4KB를 넘긴다. 상한이 없으면 이른 시각을 이어받으므로 시험이 실패한다.
  - 고친 뒤 V10이 잡히는지 확인한다.
- **K2 (A14) D-092를 이번 변경에 맞춘다.**
  - D-092 "범위 밖" 표의 마지막 줄(D-092…md:37)은 아직 "엔진·감시 `forbiddenProjects`에 `autotrade-monitor-sources` 직접 금지 → 별도 보호 테스트 강화 PR"이라고 적혀 있다. 이 PR이 그 일을 했으므로 완료(PR #118)로 고쳐야 한다.
  - 7번(감시 설정 기동 거부 사유)에 "4KB를 넘는 파일"이 빠져 있다. config-keys.md와 ADR-011에는 이 사유가 들어갔다.
  - 검증 목록(69줄)의 시험 건수와 내용이 실제와 다르다. 예: `WatchdogSettingsTest`, `HeartbeatWatchTest`, `StopRequestsTest`의 새 시험이 빠져 있다.

## 점검표 밖 발견(비차단)
- R1(권고): `SmallFile.read`는 `Files.size`로 크기를 본 다음 `Files.readString`으로 따로 읽는다(SmallFile.java:21-26). 두 호출 사이에 파일이 커지면(TOCTOU) 상한이 지켜지지 않는다. 지금 위협 모델(이미 커진 파일)에서는 충분하다. 다만 `InputStream.readNBytes(MAX_BYTES + 1)`로 한 번에 읽고 길이를 보면 상한이 확실해진다.
- R2(참고): 감시가 두 개 떠 있는 오설정에서는 두 번째 감시가 기동 때 첫 감시의 `stop-request.<pid>.tmp`를 지울 수 있다. 그러면 첫 감시의 그 주기 쓰기가 실패하고 ERROR 로그 한 줄이 남는다. 다음 점검에서 다시 쓰므로 실전 영향은 없다. 감시 단일 실행 잠금은 자동 재기동(7단계)을 설계할 때 함께 정하면 된다.
- 확인: 첫 점검 "모두 꺼짐" 경고와 기동 로그의 설정 폴더 표시는 로그만 남기고 아무 동작도 하지 않는다. `WatchdogSettings`의 오류 메시지에 `e.getMessage()`가 붙었다. 여기에 들어가는 값은 파일 경로와 크기, 또는 JDK 예외 문구이고 비밀값이 들어갈 자리는 없다.
- 남은 7단계 조건(PR #116에서 이어짐): 엔진 멈춤(#8)은 아직 감시 로그 ERROR로만 알린다(MON-06). 자동 재기동도 아직 없다.

## 판정
수정 필요(A11 K1: 4KB 상한 연결 시험이 상한 없이도 통과함, A14 K2: D-092 범위 밖 표·7번·검증 목록 갱신)

## 재검토 2026-10-15
- 대상: PR #118 / 브랜치 `claude/project-thread-0ganrn` / 커밋 `6c7fdba`(HEAD), `97f2b22`, `c19d28c`, `de41b05` / `git diff origin/main...HEAD` 전체.
  - 1차 뒤 바뀐 부분(`c19d28c..6c7fdba`)은 한 줄씩 읽었다. 운영 코드는 `SmallFile`, 빌드 파일은 엔진·감시 `build.gradle.kts`, 시험은 2건, 문서는 ADR-011과 D-092가 바뀌었다.
  - 판정은 PR 전체를 기준으로 다시 냈다.
  - 1차 원문은 `97f2b22`에 커밋된 그대로이고, `97f2b22..6c7fdba`에서 이 파일은 바뀌지 않았다. 이 절은 그 아래에 덧붙였다.
- 점검표: A
- 직접 실행한 것:
  - `6c7fdba`를 별도 worktree에 두고 `./gradlew build --no-daemon`을 돌렸다. **BUILD SUCCESSFUL(종료 코드 0)**. 전체 시험 1261건, 실패·오류·건너뜀 0건이다. 모듈별 결과(시험/실패/오류/건너뜀)는 다음과 같다.
    - `:autotrade-risk:test` 55/0/0/0
    - `:autotrade-architecture-test:test` 43/0/0/0
    - `:autotrade-app-engine:test` 52/0/0/0
    - `:autotrade-app-watchdog:test` 28/0/0/0
    - `:autotrade-monitor-agent-jar:test` 24/0/0/0
    - `:autotrade-app-gateway:test` 50/0/0/0
    - `:autotrade-app-lab:test` 181/0/0/0
  - 변이 시험은 두 번째 worktree에서 했다. 하나씩 바꾸고 시험을 돌린 뒤 원복했다. 끝난 뒤 `git status`는 비어 있었고, 두 worktree는 지웠다.

    | 변이 | 결과 |
    |---|---|
    | V10 `HeartbeatWatch`와 `StopRequests`를 상한 없는 `Files.readString`으로 되돌림(1차에서 못 잡던 변이) | **잡힘**: `HeartbeatWatchTest#상한보다_큰_하트비트_파일은_읽지_않고_읽을_수_없음이다`, `StopRequestsTest#기동_때_남은_임시_파일을_거두고_너무_큰_남은_파일은_지금_시각으로_이어받는다` 실패 |
    | V10a 하트비트 경로만 되돌림 | 잡힘: `HeartbeatWatchTest` 해당 시험 실패 |
    | V10b 정지 요청 경로만 되돌림 | 잡힘: `StopRequestsTest` 해당 시험 실패 |
    | V1 `bytes.length > MAX_BYTES` 판정을 없앰 | 잡힘: 감시 3건(하트비트, 정지 요청, 설정)과 `HeartbeatTest#작은_파일_읽기는_상한을_넘으면_읽지_않는다`, 모두 4건 실패 |
    | V11 `readNBytes(MAX_BYTES)`로 바꿔 넘는 파일을 잘라 읽고 통과시킴 | 잡힘: 위와 같은 4건 실패 |
    | V12 경계를 `>=`로 바꿈(정확히 4096바이트도 거부) | 잡힘: `HeartbeatTest#작은_파일_읽기는_상한을_넘으면_읽지_않는다` 실패 |
    | V13 엔진에 `libs.oshi.core`를 직접 의존(프로젝트 금지 목록과 무관) | 잡힘: `:autotrade-app-engine:verifyProcessIsolation` 실패. 메시지에 `com.github.oshi:oshi-core`, `com.github.oshi:oshi-common`, `net.java.dev.jna:jna`, `net.java.dev.jna:jna-platform`이 나온다 |
    | V14 감시에 같은 의존 | 잡힘: `:autotrade-app-watchdog:verifyProcessIsolation` 실패. 같은 네 구성 요소가 나온다 |
    | V15 엔진에 `:autotrade-monitor-sources` 의존(1차 V7 재확인) | 잡힘: 프로젝트와 그룹 두 금지에 모두 걸린다 |
    | V16 `deleteLeftoverTemps` 안에서 `IllegalStateException`을 던짐(삼키는지 확인) | 예외를 삼킨다. `HeartbeatTest#기록기를_만들면_…`만 실패하고(정리를 안 했으므로), `:autotrade-app-engine:test` 52건은 모두 통과한다. 정리가 실패해도 엔진 기동은 막히지 않는다 |

### 1차 지적 처리
| 지적 | 처리 | 근거 |
|---|---|---|
| K1 (A11) 상한 연결 시험이 상한 없이도 통과 | 해소 | 두 시험 모두 "다 읽으면 결과가 달라지는" 입력으로 바뀌었다. 하트비트 시험은 정상 다섯 줄 뒤에 빈 줄 5000개를 붙인다(HeartbeatWatchTest.java:116). 정지 요청 시험은 `requested-at=2026-10-14…` 뒤에 빈 줄 5000개를 붙이고, `doesNotContain("2026-10-14")`를 단정한다(StopRequestsTest.java:148, 162). V10, V10a, V10b, V1, V11이 모두 잡힌다 |
| K2 (A14) D-092 갱신 | 해소 | 범위 밖 표에서 `forbiddenProjects` 줄을 지웠다. 7번 거부 사유에 "4KB를 넘는 파일 크기(PR #118)"를 넣었다(D-092…md:25). 검증 목록에는 PR #118 후속 시험 7건과 그룹 금지를 한 줄로 더했다(:71). 처음 1차 줄(:68)의 건수는 PR #116 시점 기록으로 남고 새 줄이 보완한다. 코드와 어긋나는 서술은 남아 있지 않다 |
| R1 (권고) 크기 확인과 읽기 사이 TOCTOU | 해소 | 한 스트림에서 `readNBytes(MAX_BYTES + 1)`로 읽고 길이로 판정한다(SmallFile.java:24-31). V11과 V12로 경계를 고정했다 |
| R2 (참고) 감시 두 개 | 유지(비차단) | 바뀐 것이 없다. 실전 영향 없음 |

### 1차 뒤 바뀐 코드의 실전 영향
- **`SmallFile.deleteLeftoverTemps`가 바뀌었다**(SmallFile.java:38-51). 엔진 기동 경로에서 호출되는 함수다.
  - 이제 `toAbsolutePath()` 대신 `target.getParent()`를 쓴다. `toAbsolutePath()`가 낼 수 있는 `IOError`를 피하기 위해서다.
  - 함수 전체를 `catch (RuntimeException)`으로 감쌌다.
  - 엔진에서 대상은 `Heartbeat.file(<설정 폴더>, "engine")`이라 부모 폴더가 늘 있다.
  - 정리가 실패해도 엔진은 뜬다. V16에서 엔진 시험 52건이 통과한 것으로 확인했다.
  - 이 단계가 Guard(모두 OFF) 생성 뒤에 도는 순서는 그대로다(EngineMain.java:83-93).
- **엔진·감시 `forbiddenGroups`에 `com.github.oshi`, `net.java.dev.jna`를 더했다**(두 `build.gradle.kts`의 15줄).
  - 격리 검사는 그룹이 같거나 그 그룹으로 시작하는 하위 그룹이면 걸러 낸다(autotrade.process-conventions.gradle.kts:22-24).
  - OSHI 좌표는 `com.github.oshi:oshi-core`다(libs.versions.toml:19). V13·V14에서 JNA까지 간접 의존을 모두 잡는 것을 봤다.
  - 보호가 강해지는 변경이고, 약해지는 곳은 없다.
- 실전 주문 경로, RealTradingGuard, 블랙리스트, 리스크 한도, 보호 시세 경로, 서버 측 손절은 바뀌지 않았다. `autotrade-risk`와 `autotrade-architecture-test`의 diff는 비어 있다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `RealTradingGuard`, 설정 로더, `EngineMain.startGuard`는 바뀌지 않았다. 엔진 쪽 새 동작(임시 파일 정리)은 Guard 생성 뒤에 돌고 예외를 밖으로 내지 않는다(EngineMain.java:83-93, SmallFile.java:38-51, V16). `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`, `#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `EngineMainTest#하트비트를_쓰지_못해도_엔진은_뜨고_Guard는_OFF이며_상태에_응답한다`가 통과했다 |
| A2 | 통과 | 주문 출구, Guard 팩토리, `ArchitectureTest`, `RealPathReflectionTest`는 바뀌지 않았다. 엔진 런타임에 들어온 `SmallFile`(`InputStream.readNBytes`, `DirectoryStream`)도 실전 경로 허용 목록 검사를 통과했다. `:autotrade-architecture-test:test` 43건 통과 |
| A3 | 해당 없음 | 증권사 연동과 주문 라우팅은 바뀌지 않았다 |
| A4 | 해당 없음 | 주문 타임아웃 처리는 바뀌지 않았다 |
| A5 | 해당 없음 | 멱등 키는 바뀌지 않았다 |
| A6 | 해당 없음 | 블랙리스트와 수동 보유 필터는 바뀌지 않았다 |
| A7 | 해당 없음 | 계좌별 큐, 토큰 버킷, 킬스위치는 바뀌지 않았다. 하트비트와 정지 요청 파일은 프로세스 단위다 |
| A8 | 통과 | 엔진에 새로 생긴 일은 기동 때 한 번 하는 로컬 폴더 정리뿐이다. 실패해도 삼킨다(V16). 감시 파일 읽기는 4KB+1바이트를 넘지 않는다(V1, V11, V12). 그래서 커진 파일 하나가 감시 힙을 채워 5.2 0번 정지 요청을 막는 일이 없다. 정지 요청 두 파일은 늘 함께 켜지고 함께 꺼진다(1차 V5, `StopRequestsTest#한_파일만_못_지우면_…`). OSHI·JNA·`monitor-sources`가 엔진·감시에 들어오지 않는 것은 프로젝트와 그룹 두 겹으로 잡는다(V13~V15). 정지 순서 1~6번과 보유 종목 보호 유지 원칙은 바뀌지 않았다 |
| A9 | 통과 | 보호 시세 경로, 서버 측 손절 코드, 6.1 문서는 바뀌지 않았다 |
| A10 | 통과 | 1차 뒤 diff에도 키, 계좌번호, 토큰이 없다. 상한 초과 예외 메시지에는 이제 크기 없이 상한과 경로만 들어간다(SmallFile.java:29). 시험은 `@TempDir`만 쓴다. gitleaks는 CI 결과로 확인한다 |
| A11 | 통과 | 보호 시험은 삭제·약화되지 않았다. 바뀐 두 시험은 단정을 강화했다(`doesNotContain` 추가). 금지 목록 강화는 V13~V15로 실제 동작을 확인했다. 1차에서 못 잡던 연결 변이 V10을 이제 잡는다. 새 동작(상한, 정리, 부분 삭제, 첫 점검 경고, 격리)은 모두 실패하는 경우까지 시험한다 |
| A12 | 통과 | 장애표 6장 17종과 O1~O8의 행 수, 대응 내용은 그대로다. #8 감지 문구는 설정 키 기준으로 코드(HeartbeatWatch.java:71)와 맞는다. 장애 시험 8종(7장)의 시나리오와 합격 기준은 바뀌지 않았다 |
| A13 | 해당 없음 | 시장별 경로는 바뀌지 않았다. 시각은 프로세스 생존 판단용 UTC `Instant`다 |
| A14 | 통과 | D-092(7번, 범위 밖 표, 검증 목록), ADR-011 3장(`forbiddenGroups` 포함), config-keys.md(4KB 거부), broker-and-fault-design.md #8이 코드와 맞는다(K2 해소) |

### 점검표 밖 발견(비차단)
- R2(유지): 감시가 두 개 떠 있으면 두 번째 감시가 첫 감시의 `stop-request.<pid>.tmp`를 지울 수 있다. 그 주기 쓰기만 실패하고 다음 점검에 다시 쓰므로 실전 영향은 없다. 감시 단일 실행 잠금은 7단계 자동 재기동과 함께 정한다.
- 참고: `SmallFile.read`가 일반 파일이 아닌 FIFO를 열면 블록될 수 있다. 이는 이 PR 전의 `Files.readString`도 같았으므로 회귀가 아니다. 설정 폴더는 사용자만 쓰는 곳이다.
- 남은 7단계 조건(PR #116에서 이어짐): 엔진 멈춤(#8)은 아직 감시 로그 ERROR로만 알린다(MON-06). 자동 재기동도 아직 없다.

### 판정
승인
