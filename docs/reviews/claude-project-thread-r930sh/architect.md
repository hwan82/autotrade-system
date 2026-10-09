# architect 검토: 실전 운영 화면(ADR-006) 1차
- 대상: 커밋 4ab3553
- 비고: 프로젝트 지침 11장 도입(2026-10-09 #15) 전에 받은 검토라 점검표 형식이 아니다. 아래는 architect 서브에이전트의 최종 보고를 줄 들여쓰기만 빼고 그대로 옮긴 원문이다. 재검토는 11장 형식으로 이 파일 아래에 덧붙인다.

## 1차 검토 원문 (2026-10-09)

## architect 리뷰: 4ab3553 실전 제어 명령 채널 (ADR-006)

결론: 차단 사유는 없습니다. 다만 중요 6건은 7단계(실전 연동) 전에 반드시 고쳐야 하고, 가능하면 이번 PR에서 고치는 것을 권합니다. 실전 경로 PR이므로 risk-ops 검토 뒤 사용자가 직접 머지합니다(지침 10장).

검증 결과
- `./gradlew build --offline`: 통과.
- `--rerun`으로 다시 돌린 테스트 모두 통과: ControlChannelTest 11건(3회 반복, 매번 통과), EngineMainTest 6, EngineCommandHandlerTest 15, OperatorAuthenticatorTest 6, OperatorPasswordHashTest 5, ArchitectureTest 12, RealOpsControllerTest 9, ConfigLoaderTest 20, RuntimeConfigHolderTest 6, SecretTest 3.
- Linux에서만 실행했습니다. Windows와 macOS는 검증하지 못했습니다.

### 1. 확인 요청 항목별 요약
1. **모듈 경계: 대체로 적합.**
   - autotrade-control은 pure-java-conventions를 쓰고 의존은 config(api)와 Jackson뿐입니다. 엔진 런타임에 Spring·실험실·UI가 들어오지 않습니다.
   - architecture-test 의존에 추가돼 있고, `PROCESSES_ARE_INDEPENDENT`는 유지됩니다(control은 `com.autotrade.app.*` 슬라이스 밖).
   - 빈틈은 아래 중요-2, 중요-3입니다.
2. **프로토콜·소켓**: 기본 동작은 맞습니다. 다만 STATUS가 명령 큐 뒤에서 기다리는 구조와 "응답 없음"을 "꺼짐"으로 보는 문제(중요-1)가 있고, 경계 조건 몇 개는 참고로 적었습니다.
3. **설정 계약**:
   - SecretPaths가 레코드에서 비밀값 경로를 자동으로 뽑으므로 ConfigLoader 쪽 영향은 없습니다.
   - RuntimeConfigHolder는 real-trading 레코드가 같은지 비교하므로 해시의 실시간 변경을 올바르게 거부합니다.
   - SettingsController의 디스크 대조에서 운영상 마찰이 생깁니다(참고-1).
4. **EngineMain**: run/start 분리 덕분에 테스트하기 쉽습니다. Clock은 `main`에서만 만들므로 ARC-04를 지킵니다. 종료 훅 경로는 CI로만 검증됩니다(참고).
5. **REST**: 오류 형식 `{code,message}`와 `lockedUntil` UTC ISO는 맞습니다. ADR-005의 코드 카탈로그는 갱신되지 않았습니다(참고).
6. **테스트**: 시간·스레드 테스트 가운데 아무것도 확인하지 않고 통과할 수 있는 것과 멈출(hang) 수 있는 것이 있습니다(중요-6).

### 2. 차단
없음.

### 3. 중요

**중요-1. 상태 조회가 명령 뒤에 줄을 서고, "엔진이 바쁨"이 "엔진 꺼짐 = 가상"으로 표시됨**
- 근거:
  - `autotrade-control/.../ControlServer.java:118`: STATUS도 단일 명령 스레드 큐에 들어갑니다.
  - `ControlClient.java:36-59`: 소켓이 없을 때와 시간 초과·연결 끊김이 같은 예외로 합쳐집니다.
  - `RealOpsController.java:44-51`: 둘 다 `engineReachable=false`가 됩니다.
  - `autotrade-ui/.../js/app.js` modeBand: false이면 "실험실 · 가상 계좌만 / 실전 엔진이 꺼져 있습니다"로 표시합니다.
- 어떻게 생기나:
  - 긴 명령(7단계 활성화, 최대 15초)이 도는 동안 탭마다 5초 폴링이 쌓입니다.
  - 연결 4개 상한(`ControlServer.java:90-94`)이 차면 새 연결은 바로 닫힙니다.
  - 그러면 OPEN 계좌가 있어도 띠가 가상으로 바뀝니다. UI-05 "실전·모의 상태를 착각할 수 없게"에 어긋납니다. 지금은 활성화가 항상 실패해 실제 영향은 없습니다.
- 수정 제안:
  - (a) STATUS는 연결 스레드에서 Guard 스냅샷으로 바로 답합니다(ADR-002 2.1의 잠금 없는 스냅샷). 이때 `OperatorAuthenticator`의 `lockedUntil`은 volatile로 바꾸거나 스냅샷으로 넘겨야 합니다.
  - (b) `StatusView.engineReachable` boolean을 `engine: UP|DOWN|UNKNOWN` 3상태로 바꿉니다. 소켓 파일 없음 또는 연결 거부는 DOWN, 시간 초과·끊김은 UNKNOWN입니다.
  - (c) UNKNOWN이면 띠를 "실전 상태 확인 불가" 경고 띠로 바꾸고, 가상 표시는 금지합니다.
  - (d) STATUS 전용 짧은 타임아웃(예: 2초)을 둡니다.
  - 계약 변경이므로 ADR-006 2.3과 2.7에 반영합니다.

**중요-2. ArchUnit이 새 모듈을 덮지 못함**
- 근거: `ArchitectureTest.java:48-56` PURE 배열에 `com.autotrade.control..`이 없습니다. Gradle 의존 검사만 1차로 막는 상태입니다.
- 근거: control을 누가 쓸 수 있는지 정하는 규칙이 없습니다. 시세 게이트웨이나 감시가 나중에 의존을 추가해 `ControlClient`로 ACTIVATE를 보내거나, 실험실이 `ControlServer`로 엔진 소켓을 먼저 선점(엔진 기동 거부)해도 빌드가 막지 않습니다.
- 수정 제안:
  - PURE에 control을 추가합니다.
  - `ControlServer`는 `com.autotrade.app.engine..`만 쓸 수 있게 합니다.
  - `ControlClient`는 `com.autotrade.app.lab..`만 쓸 수 있게 합니다(MON-02 때 watchdog 추가는 새 ADR로).
  - 각 규칙에 위반 픽스처를 하나씩 둡니다.

**중요-3. ADR-001(모듈 구조 단일 원본) 미갱신**
- 근거: `docs/adr/ADR-001-module-structure.md:44-54` 모듈 표, 25-28행 프로세스별 금지 의존, 81행 ArchUnit 규칙 목록·개수에 autotrade-control과 새 규칙이 없습니다.
- 수정 제안: 모듈 표에 한 줄을 추가합니다("autotrade-control | ControlRequest/Response, Codec, Server, Client | O(Jackson만) | config"). gateway와 watchdog의 금지 목록에 control을 넣습니다(중요-2와 일치). ArchUnit 목록을 갱신합니다.

**중요-4. 검증 통과 표시(`verified`)가 계좌·명령에 묶이지 않고, 쓰이지 않으면 다음 명령까지 남음**
- 근거: `OperatorAuthenticator.java:56-71`에서 verify OK이면 `verified=true`가 됩니다. 그런데 PANIC_ACCOUNT·PANIC_ALL은 `confirmationFor`를 부르지 않으므로(`EngineCommandHandler.java:152-163`) 명령이 끝난 뒤에도 true로 남습니다.
- 근거: `confirmationFor(AccountId)`(92-98)는 어느 문구·계좌로 검증했는지 보지 않습니다.
- 지금은 activate가 항상 verify를 먼저 하므로 악용 경로가 없습니다. 하지만 7단계에서 이 패키지에 Q-016 보호 모드 진입 같은 호출자가 생기면 ADR-002 3장 "직전 검증에서 한 번만"이 깨질 수 있습니다.
- 수정 제안:
  - verify가 일회용 `Verified(account 또는 phrase, command)` 값을 돌려주고, `confirmationFor`가 그 값의 계좌가 일치하는지 확인하게 합니다.
  - 또는 최소한 `EngineCommandHandler.apply` 끝에서 항상 지우는 메서드를 부릅니다.
  - 계좌 불일치와 PANIC 뒤 재사용 불가를 테스트로 고정합니다. risk-ops 검토가 필요합니다.

**중요-5. 연속 실패 잠금이 PANIC SELL까지 15분 막음**
- 근거: `OperatorAuthenticator.java:62-78`은 하나의 카운터로 모든 2차 확인을 잠급니다. 같은 OS 사용자의 아무 프로세스나 UI 오조작으로 틀린 시도 5번만 하면 PANIC이 15분 동안 막힙니다(내리기는 됨).
- 실전 영향 항목이라 architect가 정할 일이 아닙니다. risk-ops와 사용자 결정 항목으로 ADR-006 7장 4번에 명시해야 합니다.
- 대안:
  - 활성화와 PANIC의 실패 카운터를 분리합니다.
  - PANIC은 잠금 대신 시도마다 지연을 늘립니다.
  - 잠금 시간을 줄입니다.

**중요-6. ControlChannelTest의 아무것도 확인하지 않는 통과, 멈춤 위험, 빈틈**
- `ControlChannelTest.java:122-153`(직렬 처리):
  - b가 200ms 안에 연결하지 못하거나 `sendQuietly`가 예외를 던져도(가상 스레드라 예외가 사라짐) `max==1`로 통과합니다.
  - 수정 제안: 처리기 호출 수가 2이고 두 send 모두 OK인지 확인합니다. b의 요청이 서버에서 읽혔다는 신호(두 번째 latch)를 기다린 뒤 release합니다.
- 90-106(읽기 타임아웃): `@Timeout`이 없어 서버가 닫지 않으면 무한 대기합니다. 하한도 없어서 상한 초과로 즉시 닫혀도 통과합니다. `@Timeout(15)`와 `>= READ_TIMEOUT - 0.5s`를 추가합니다.
- 30행 `received`는 ArrayList를 서버 스레드에서 쓰고 테스트 스레드에서 읽습니다. RealOpsControllerTest처럼 CopyOnWriteArrayList를 씁니다.
- 183-194(남은 소켓): 일반 파일로 흉내만 냅니다. 실제 죽은 소켓(bind 후 close, 파일은 남음)으로 테스트합니다.
- 빠진 테스트:
  - 연결 상한 초과 거절과, 시간 초과·형식 오류 뒤 슬롯이 반환되는지(누수 없음).
  - 크기 경계(정확히 MAX_BYTES).
  - 엔진이 바쁠 때 실험실 status/명령 응답(중요-1과 함께).

### 4. 참고
1. **설정 대조 마찰**: 정상 절차(hash-password → secrets.json 편집 → 엔진 재기동)를 거친 뒤, 실험실 설정 저장이 `SettingsController.requireDiskMatches`(150-163행)에서 `CONFIG_CHANGED_ON_DISK` 409로 막힙니다.
   - 저장이 메모리 스냅샷 전체를 다시 쓰기 때문에 이 409는 해시가 덮어써지는 것을 막는 올바른 보호입니다. 대조에서 해시를 빼면 안 됩니다.
   - 대신 config-keys.md의 "재기동(실전 엔진)"을 "실전 엔진 재기동(실험실 설정 저장은 실험실 재기동 후)"으로 고칩니다.
   - ADR-006 B1 "해시는 엔진만 읽음"도 정확하지 않습니다. 실험실·게이트웨이·감시도 ConfigLoader로 해시를 메모리에 올립니다. 문구를 고치거나 로딩 범위를 줄입니다.
2. **ControlCodec**(`ControlCodec.java:53-69`):
   - 줄바꿈 뒤 바이트는 조용히 버립니다. 연결 하나에 요청 하나라 해는 없지만 문서에 적거나 BAD_REQUEST로 거절합니다.
   - 크기 검사가 청크(1024)를 처리한 뒤에 있어 최대 MAX+1023바이트 줄까지 받습니다. 바이트를 추가하는 루프 안에서 검사합니다.
3. **Jackson 엄격도**:
   - `FAIL_ON_NUMBERS_FOR_ENUMS`가 꺼져 있어 `{"command":1}`이 ACTIVATE로 해석될 것으로 봅니다 `[추론: Jackson 기본값]`.
   - `FAIL_ON_TRAILING_TOKENS`를 켜고 스칼라 강제 변환을 끕니다.
   - 반대로 클라이언트 쪽 응답 읽기의 `FAIL_ON_UNKNOWN_PROPERTIES`는 엔진과 실험실을 따로 재기동할 때 버전이 어긋나면 "연결 불가"로 보입니다. 프로토콜 `version` 필드를 두거나 응답은 관대하게 읽습니다.
4. **ControlServer 세부**:
   - (a) `serve` 106-118행: 읽기 직후 데드라인이 이미 실행됐어도 명령을 처리합니다. `if (!deadline.cancel(false)) return;`.
   - (b) 86-89행: accept의 IOException에 백오프가 없어 EMFILE 같은 지속 오류에서 바쁜 루프와 로그 폭주가 납니다.
   - (c) 95행: close와 경쟁할 때 `RejectedExecutionException`으로 accept 스레드가 죽고 슬롯·채널이 샙니다.
   - (d) 64행: `restrictSocketFile`이 실패하면 서버 채널과 소켓 파일이 남습니다. try/catch로 닫고 지웁니다.
   - (e) 158-175행: 연결 실패 사유를 구분하지 않고 지웁니다(일반 파일, EACCES 포함). ECONNREFUSED이고 대상이 소켓일 때만 지우고 그 밖에는 기동을 거부합니다. 프로브 connect에 타임아웃이 없어 backlog가 가득 차면 기동이 멈출 수 있습니다 `[추론]`.
   - (f) close가 진행 중인 명령을 interrupt합니다. 7단계에서는 accept 중단 → 정해진 시간 동안 명령 마무리 대기 → shutdownNow 순서로 바꿉니다.
5. **ControlClient**:
   - `holder` 배열(39-52행)을 동기화 없이 공유합니다. AtomicReference로 바꿉니다.
   - Windows에서 AF_UNIX 소켓 파일(reparse point)에 대한 `Files.exists` 동작은 확인하지 못했습니다 `[가정]`. 존재 확인보다 연결을 시도하고 `ConnectException`·`NoSuchFileException`으로 DOWN을 판정하는 편이 이식성이 좋습니다.
6. **이식성**:
   - Windows는 권한을 설정하지 않습니다(137-155행). 실사용 PC가 Windows면 `AclFileAttributeView`로 소유자 전용 ACL을 설정합니다.
   - macOS sun_path는 104바이트입니다. `@TempDir` 경로(/var/folders/…/junitNNN/run/engine-control.sock, 약 97자)가 한계에 가까워 macOS 개발 PC에서 ControlChannelTest·EngineMainTest가 실패할 수 있습니다. RealOpsControllerTest처럼 짧은 폴더를 씁니다.
7. **EngineMain**:
   - `main` 58-67행의 종료 훅·latch 경로는 CI 셸 검증뿐입니다. `Engine.awaitShutdown()`처럼 분리하면 단위 테스트가 가능합니다.
   - JDK 22 이상에서는 `System.console()`이 리다이렉트 상태에서도 null이 아닐 수 있습니다. 업그레이드할 때 `isTerminal()`을 확인합니다(지금 21은 문제없음).
8. **REST**(ADR-005 82행):
   - 새 코드(UNKNOWN_ACCOUNT, PASSWORD_NOT_CONFIGURED, CONFIRMATION_FAILED, LOCKED 423, INVALID_STATE, ENGINE_UNREACHABLE 503, INTERNAL 502)를 카탈로그에 추가합니다.
   - 엔진 `BAD_REQUEST`는 실험실 `INVALID`(400)와 겹치므로 INVALID로 매핑합니다.
   - 활성화 단계 실패가 200과 message로만 나갑니다. `outcome`(OPENED|FAILED|ABORTED) 필드를 두면 화면이 문자열을 해석하지 않아도 됩니다.
   - 스냅샷에 `asOf`(엔진 Clock, UTC ISO)를 넣는 것을 권합니다.
9. **확인 문구 비교**(`OperatorAuthenticator.java:65`): "전체 청산"은 입력기에 따라 NFD로 올 수 있으니 양쪽을 NFC로 정규화합니다.
10. **CI**: `stat -c`는 GNU 전용인데 ubuntu 러너라 문제없습니다. 소켓 600 확인은 단위 테스트에만 있으므로 CI 단계에도 `stat -c %a`로 넣을 수 있습니다.

### 5. 판정
**조건부 승인.** 차단 사유는 없지만 중요-1~6을 고치고(최소 중요-1, 2, 3, 6은 이번 PR에서), 중요-4·5는 risk-ops 검토를 거친 뒤 사용자가 직접 머지해야 합니다.

관련 파일:
- /home/claude/autotrade-system/autotrade-control/src/main/java/com/autotrade/control/ControlServer.java
- /home/claude/autotrade-system/autotrade-control/src/main/java/com/autotrade/control/ControlClient.java
- /home/claude/autotrade-system/autotrade-control/src/main/java/com/autotrade/control/ControlCodec.java
- /home/claude/autotrade-system/autotrade-control/src/test/java/com/autotrade/control/ControlChannelTest.java
- /home/claude/autotrade-system/autotrade-app-engine/src/main/java/com/autotrade/app/engine/EngineMain.java
- /home/claude/autotrade-system/autotrade-app-engine/src/main/java/com/autotrade/app/engine/command/OperatorAuthenticator.java
- /home/claude/autotrade-system/autotrade-app-engine/src/main/java/com/autotrade/app/engine/command/EngineCommandHandler.java
- /home/claude/autotrade-system/autotrade-app-lab/src/main/java/com/autotrade/app/lab/real/RealOpsController.java
- /home/claude/autotrade-system/autotrade-app-lab/src/main/java/com/autotrade/app/lab/settings/SettingsController.java
- /home/claude/autotrade-system/autotrade-ui/src/main/resources/static/js/app.js
- /home/claude/autotrade-system/autotrade-architecture-test/src/test/java/com/autotrade/architecture/ArchitectureTest.java
- /home/claude/autotrade-system/docs/adr/ADR-001-module-structure.md
- /home/claude/autotrade-system/docs/adr/ADR-006-real-control-channel.md
- /home/claude/autotrade-system/docs/config/config-keys.md

## 재검토 2026-10-09

# architect 검토: 실전 운영 화면(ADR-006) 1차 검토 반영본
- 대상: 브랜치 claude/project-thread-r930sh, 커밋 a3f31de, `git diff origin/main...HEAD` 전체(57개 파일, origin/main c50ab6f 기준)
- 점검표: B
- 직접 실행한 것:
  - 다른 검토자의 빌드와 겹치지 않도록 `git archive HEAD`를 scratchpad로 풀어 그곳에서 `./gradlew build --offline --no-daemon --rerun-tasks` 실행: **BUILD SUCCESSFUL**(91 tasks 실행, EXIT=0). 테스트 359건, 실패 0, 오류 0, 건너뜀 0. 이 중 ArchitectureTest 13, ControlChannelTest 18, EngineCommandHandlerTest 21, OperatorAuthenticatorTest 9, EngineMainTest 8, OperatorPasswordHashTest 5, RealOpsControllerTest 11, ConfigLoaderTest 20, RuntimeConfigHolderTest 6, SecretTest 3.
  - `verifyPureDependencies`·`verifyProcessIsolation` 작업이 빌드 로그에 실행됨(13회).
  - 이 PC에서 PBKDF2-HMAC-SHA256 600,000회 1회 계산 시간 측정: 약 190~270ms(4코어 Linux).
  - Linux에서만 실행했다. Windows·macOS와 GitHub Actions CI 단계(엔진 기동·소켓 권한 셸 검사)는 실행하지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 결과(로컬, --offline). CI 러너 결과는 보지 않았다 |
| B2 | 통과 | `ArchitectureTest.java:58` PURE에 `com.autotrade.control..` 추가, 161-179행 새 규칙 3개(`ONLY_AUTHENTICATOR_CREATES_CONFIRMATION`, `ONLY_ENGINE_OPENS_CONTROL_SERVER`, `ONLY_LAB_SENDS_CONTROL_COMMANDS`)와 위반 픽스처(`ForgedConfirmation`, `MisplacedControlChannel`)로 규칙이 위반을 잡는지 확인(ArchitectureTest 13건 통과). `autotrade-control/build.gradle.kts`는 pure-java-conventions, 의존은 config·Jackson뿐. gateway·watchdog `processIsolation`에 `:autotrade-control` 금지 추가. architecture-test 의존에 control 추가. 규칙 변경은 ADR-006 2.1, ADR-001:64에 기록. 단 ADR-001:83 "ArchUnit (10개 규칙)" 문구는 현재 15개와 맞지 않음(참고-5) |
| B3 | 통과 | 설정 키 `real-trading.operator-password-hash`: `RuntimeConfig.java` RealTrading 레코드, `autotrade-default.json`, `docs/config/config-keys.md:30`(기본값·형식·범위·재기동 범위), ConfigLoaderTest 비밀값 경로 목록 갱신. REST: ADR-005:82 오류 코드 추가, `RealOpsController` 매핑과 일치(BAD_REQUEST→INVALID 포함). 명령 채널 프로토콜·응답 코드: ADR-006 2.2·2.3 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음, 골든 테스트 포함 전체 테스트 통과. 새 코드의 현재 시각은 주입 Clock(`OperatorAuthenticator`, `RealOpsController`), 시스템 시계 생성은 `EngineMain.main:49`뿐. `ControlServer.java:219-222`의 `System.nanoTime()`은 대기 시간 측정용(ADR-001:224 허용 범위). 난수는 해시 salt용 `SecureRandom`(EngineMain:133)뿐이며 결과 재현성과 무관 |
| B5 | 해당 없음 | 금액을 다루는 코드 변경 없음 |
| B6 | 통과 | 새 동작마다 테스트 있음(위 건수). 1차 지적 테스트 결함 수정 확인: `ControlChannelTest.java:29` 클래스 `@Timeout(30)`, 35행 CopyOnWriteArrayList, 166-195행 직렬 처리 테스트가 `calls==2`·두 응답 모두 ok 확인, 110-121행 읽기 타임아웃 하한·상한, 연결 상한 초과·슬롯 반환(124행), 정확히 MAX_BYTES(92행), 실제 죽은 소켓(358·391행). `git diff origin/main...HEAD`에 삭제된 파일·`@Disabled`·삭제된 `@Test` 없음 |
| B7 | 확인 못 함 | 실전 경로(Guard 전이, PANIC SELL, 2차 확인, 명령 채널)를 건드림 → **risk-ops 검토 필요**. `docs/reviews/claude-project-thread-r930sh/risk-ops.md`에는 4ab3553 대상 1차 원문만 있고 a3f31de 대상 A 점검표 재검토가 아직 없다 |
| B8 | 통과 | 테스트의 비밀값은 자리표시자(`PLACEHOLDER-...`, 테스트용 비밀번호). `ControlRequest.toString`(ControlRequest.java:47-52)이 비밀번호·계좌 칸을 가림, `ControlCodec` 파싱 예외에 원문 없음, `ControlServer.java:238` 오류 로그는 명령 이름과 예외 클래스만, `RealOpsController.java:88,92` 로그는 명령·코드만, `EngineCommandHandler.auditTarget`은 설정에 있는 별칭만 기록. gitleaks는 이 환경에 없어 실행하지 않았다(CI가 실행) |

### 1차 지적 사항 반영 확인
- 중요-1(상태 조회가 명령 뒤에 줄 섬, 응답 없음이 "가상"으로 보임): **해결.** `ControlServer.java:60,211-214` STATUS·DEACTIVATE 직접 처리, `ControlClient` NOT_RUNNING/UNKNOWN 구분, `RealOpsController` `EngineState` 3상태와 `asOf`, 상태 조회 2초 제한, `app.js` modeBand가 UNKNOWN일 때 마지막 UP 상태를 경고 띠로 유지. 남은 점은 참고-2.
- 중요-2(ArchUnit이 control을 덮지 못함): **해결.** B2 근거.
- 중요-3(ADR-001 미갱신): **대체로 해결.** 모듈 표·프로세스 금지 의존·설계 근거 갱신. 규칙 개수 문구만 남음(참고-5).
- 중요-4(검증 통과 표시가 계좌·명령에 묶이지 않음): **해결.** `OperatorAuthenticator.java:36,75,97-104`(확인 문구에 묶이고 한 번 쓰면 지움), `EngineCommandHandler.java:71-73`(명령 끝에 항상 버림). 남은 점은 참고-3.
- 중요-5(잠금이 PANIC을 막음): **해결 방향 확인.** PANIC 1단계(보호 모드 고정)를 2차 확인 전에 수행(`EngineCommandHandler.java:176-183,189-200`), 잠금 중 UI 안내(`real.js:71-72`). 잠금 정책 자체는 ADR-006 7장 사용자 결정 항목. 실전 정책 판단은 risk-ops 몫.
- 중요-6(테스트 결함): **해결.** B6 근거.
- 1차 참고 항목: 코덱 크기 검사·엄격 파싱(`ControlCodec.java:29-42,108`), 늦게 도착한 요청 무시(`ControlServer.java:198-201`), accept 백오프(147-152), RejectedExecution 처리(167-171), 소켓 권한 설정 실패 시 정리(110-116), engine.lock(83-98), 클라이언트 AtomicReference, NFC 정규화, ADR-005 오류 코드, CI 소켓 권한 검사가 반영됐다. 남은 것: 프로브 connect 타임아웃 없음(`ControlServer.java:280-287`), close 시 진행 중 명령 interrupt(323행, ADR-006에 7단계 조건으로 기록), Windows ACL, macOS sun_path(테스트가 여전히 `@TempDir` 사용, macOS에서 실행해 보지 않음).

## 점검표 밖 발견

**중요-A. PANIC SELL이 `BUSY`로 통째로 거절될 수 있다(1단계 보호 모드 고정 포함)**
- 근거: `ControlServer.java:212-218`. DIRECT가 아닌 명령(ACTIVATE, PANIC_ACCOUNT, PANIC_ALL)은 `queued` 세마포어(MAX_QUEUED=2, 실행 중 포함)를 못 얻으면 바로 `BUSY`를 돌려준다. PANIC도 예외가 아니다. 이때 처리기가 불리지 않으므로 `EngineCommandHandler`의 "2차 확인 전에 하는 보호 모드 고정"도 일어나지 않는다.
- 생기는 경우: 활성화 1건 실행 중 + 1건 대기(탭 두 개, 이중 클릭 등), 또는 전체 PANIC 실행 중 + 계좌 PANIC 대기 중에 다시 PANIC을 누를 때. 화면은 BUSY에서 대화상자를 닫는다(`real.js:162`).
- 영향: ADR-006 2.2의 "PANIC은 기다렸어도 실행한다(내리는 방향)"와 D-023 공통 원칙 2(내리는 전이는 언제나 허용)에 어긋난다. 지금은 활성화 단계가 즉시 실패해 대기열이 거의 차지 않으므로 실제 위험은 낮다 `[추론]`. DEACTIVATE는 대기열을 거치지 않아 우회 수단은 있다.
- 수정 제안(둘 중 하나, risk-ops 판단):
  - (a) PANIC_ACCOUNT·PANIC_ALL은 BUSY 대상에서 빼고 항상 대기열에 넣는다(대기 상한은 ACTIVATE에만 적용).
  - (b) PANIC 1단계(보호 모드 고정)를 연결 스레드에서 바로 수행한 뒤 2차 확인·증권사 단계만 대기열로 보낸다. 이 경우 1단계는 활성화 진행 중에도 즉시 일어난다. ADR-006 148행의 "긴 활성화 중 PANIC도 기다린다" 위험도 1단계만큼은 해소된다.
  - 어느 쪽이든 "대기열이 찬 상태에서 PANIC을 보내면 보호 모드로 내려간다" 테스트를 추가하고 ADR-006 2.2 문구를 맞춘다.

### 참고
1. **늦게 끝나는 활성화**: `STALE`은 대기열 대기 시간만 본다(`ControlServer.java:222-223`). 9초 기다린 ACTIVATE가 실행되면 PBKDF2 검증과 활성화 단계 시간이 더해져 실험실 15초 제한을 넘길 수 있다. 그러면 화면은 "응답 없음"인데 엔진은 OPEN이 될 수 있다. 지금은 단계가 즉시 실패해 문제없다. 7단계에서 활성화 단계가 생기면 Guard를 올리기 직전(`beginActivation` 전)과 마지막 단계 전에 요청 시작 후 경과 시간을 다시 확인하는 기한(deadline)을 처리기에 넘기는 것을 권한다. ADR-006 7단계 완료 조건 표에 추가하는 것을 권한다.
2. **상태 조회가 해시 검증 동안 막힘**: `OperatorAuthenticator.verify`(60행)는 `synchronized` 안에서 PBKDF2(이 PC에서 약 0.2초, 느린 PC나 부하 시 더 김 `[추론]`)를 계산한다. STATUS가 부르는 `lockedUntil()`(48행)과 DEACTIVATE 뒤의 `discardVerification()`(107행)이 같은 모니터를 기다린다. Guard 전이는 그 전에 끝나므로 안전 영향은 없지만, "STATUS는 바로 답한다"는 설명과 다르고 STATUS 2초 제한에 걸리면 UNKNOWN 띠가 뜬다. 해시 비교를 모니터 밖에서 하고 실패 횟수·잠금·검증 문구 갱신만 동기화하는 것을 권한다.
3. **동시 DEACTIVATE가 진행 중인 활성화의 검증을 지움**: DEACTIVATE는 연결 스레드에서 `apply`를 거치므로 끝에서 `discardVerification()`(EngineCommandHandler.java:72)을 부른다. 명령 스레드의 ACTIVATE가 `confirm` 통과(111행) 후 `confirmationFor`(116행) 전이면 검증이 지워져 `INVALID_STATE "이 계좌로 2차 확인 검증을 통과하지 않았습니다"`로 끝난다. 안전한 방향(열리지 않음)이고 창이 매우 짧다. 다만 메시지가 원인을 오해하게 하므로 `discardVerification`은 대기열 명령(ACTIVATE·PANIC)에서만 부르거나, `verify`가 일회용 검증 값을 돌려주게 바꾸는 것을 권한다(1차 중요-4 제안 (a)).
4. **NOT_RUNNING 판정이 소켓 파일 존재에만 기댐**: `ControlClient.java:50`. 엔진이 살아 있는데 누가 소켓 파일을 지우면 화면이 "실험실 · 가상 계좌만"이 된다. engine.lock 덕분에 두 번째 엔진은 뜨지 않지만 표시는 틀린다. 실험실에서 `run/engine.lock`에 공유 잠금 시도(잡히면 엔진 없음, 못 잡으면 UNKNOWN)를 보조 판정으로 쓰는 방안을 검토할 만하다. Windows 이식성(참고: 1차 5번)과 함께 7단계 전 정리.
5. **ADR-001 규칙 개수**: `docs/adr/ADR-001-module-structure.md:83` "ArchUnit (10개 규칙)"과 설명 목록이 현재 `ArchitectureTest`의 규칙 15개(Guard 팩토리, 명령 채널, 2차 확인, control 서버·클라이언트 규칙 포함)와 다르다. main에서 이미 어긋나 있던 것(main 12개)이 더 벌어졌다.
6. **코덱 줄 뒤 바이트 검사 범위**: `ControlCodec.java:102-105`는 같은 read 청크 안의 남은 바이트만 거절한다. 다음 read에 오는 바이트는 보지 않는다. 연결 하나에 요청 하나라 해는 없으나, ADR-006·테스트 설명의 "두 줄 거절"은 같은 청크일 때만 성립한다는 점을 Javadoc에 적어 두면 좋다.

## 판정
수정 필요(B7 확인 못 함: a3f31de 대상 risk-ops A 점검표 재검토 원문이 없음, 중요-A: PANIC이 BUSY로 거절되어 보호 모드 고정도 안 되는 경로)

## 재검토 2 2026-10-09

# architect 검토: 실전 운영 화면(ADR-006) 재검토 반영본
- 대상: 브랜치 claude/project-thread-r930sh, HEAD c7e158b, `git diff origin/main...HEAD` 전체(origin/main 2dcea43 = merge-base, 57개 파일). 직전 검토(a3f31de) 뒤 바뀐 것은 c7e158b(코드 7개·ADR 2개)와 main 반영 4f36e2a(main 쪽 파일만, 이 브랜치 diff에는 나타나지 않음)
- 점검표: B
- 직접 실행한 것:
  - `git archive c7e158b`를 scratchpad의 이 검토 전용 폴더에 풀고 `./gradlew build --offline --no-daemon --rerun-tasks`: **BUILD SUCCESSFUL**(91 tasks 모두 실행, EXIT=0). 테스트 377건, 실패 0, 오류 0, 건너뜀 0. 이 중 ArchitectureTest 13, ControlChannelTest 19, EngineCommandHandlerTest 22, OperatorAuthenticatorTest 9, EngineMainTest 8, OperatorPasswordHashTest 5, RealOpsControllerTest 11. `verifyPureDependencies`·`verifyProcessIsolation` 13회 실행.
    - 참고: 처음 두 번은 같은 scratchpad의 공용 이름 폴더(`rv2`)에서 돌렸다가 빌드 도중 `autotrade-config/build/classes`의 클래스 파일이 사라져 `:autotrade-app-lab:compileJava`가 실패했다(NoSuchFileException). 같은 시각 다른 검토자의 작업이 그 폴더를 건드린 것으로 본다 `[추론]`. 코드 문제가 아니므로 판정에 쓰지 않았고, 전용 폴더에서 다시 돌린 위 결과를 쓴다.
  - 시간에 민감한 테스트 반복: `:autotrade-control:test`와 `EngineCommandHandlerTest`를 `--rerun-tasks`로 3회 더 실행, 3회 모두 통과.
  - gitleaks 8.21.2(저장소 `.gitleaksignore` 적용): `origin/main..HEAD` 5개 커밋, 전 이력 54개 커밋 모두 "no leaks found".
  - Linux에서만 실행했다. Windows·macOS와 GitHub Actions CI 단계(엔진 기동·소켓 권한 셸 검사)는 실행하지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 결과(로컬, --offline, 전용 폴더). CI 러너 결과는 보지 않았다 |
| B2 | 통과 | c7e158b는 모듈 의존과 ArchUnit 규칙을 바꾸지 않았다. 새 코드는 `ControlClient`(control 모듈)의 JDK `ProcessHandle`·`Files`뿐이고 control은 계속 PURE 패키지(`ArchitectureTest.java:58`), 서버·클라이언트 위치 규칙과 위반 픽스처 통과(ArchitectureTest 13건). `ADR-001-module-structure.md:83`이 규칙 개수 대신 "ArchitectureTest의 규칙 전체"와 목록으로 바뀌어 직전 참고-5 해소 |
| B3 | 통과 | 새 프로세스 간 계약(잠금 파일 `run/engine.lock`에 엔진 PID를 US-ASCII 10진수로 기록, 실험실이 이를 읽어 NOT_RUNNING/UNKNOWN 판정)이 ADR-006 2.2(48행)·2.3(78행)에 기록됨. 응답 코드 표 `BUSY(ACTIVATE만)`(72행), 2.4 검증 폐기 범위(85행), 5장 테스트 표 건수(22·19건)가 코드와 일치. REST·설정 키·스키마 변경 없음 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음, 골든 테스트 포함 전체 통과. 새 코드에 시스템 시계·난수 없음(`ControlServer.java:237-241`의 `System.nanoTime()` 상대값은 기존 그대로, ArchUnit `NO_SYSTEM_TIME` 통과) |
| B5 | 해당 없음 | 금액을 다루는 코드 변경 없음 |
| B6 | 통과 | 바뀐 동작마다 테스트: PANIC은 대기열이 차도 BUSY 아님(`ControlChannelTest.java:222` `대기열이_차면_활성화는_BUSY로_거절하고_PANIC은_받는다`, ACTIVATE가 정확히 MAX_QUEUED건 처리되고 PANIC 1건 ok 확인), 소켓만 없어지면 UNKNOWN(320행), 잠금 파일 PID가 죽었으면 NOT_RUNNING(309행 근처), DEACTIVATE가 검증을 지우지 않음(`EngineCommandHandlerTest.java:350`). 기존 BUSY 테스트는 지운 것이 아니라 바뀐 계약에 맞게 고쳐 단언을 늘렸다. `git diff origin/main...HEAD`에 삭제된 파일·`@Disabled`·assume·삭제된 `@Test` 없음 |
| B7 | 확인 못 함 | 실전 경로(명령 채널 대기열 정책, PANIC 수락, 2차 확인 검증 폐기, 엔진 생존 판정)를 건드림 → **risk-ops 검토 필요**. `docs/reviews/claude-project-thread-r930sh/risk-ops.md`에는 a3f31de 대상 재검토(판정 "수정 필요 F1, F2")까지만 있고, F1·F2를 고친 c7e158b 대상 A 점검표 재검토 원문이 아직 없다(이 검토를 쓰는 시점 기준) |
| B8 | 통과 | gitleaks 이력 전체 무결과(위). 새 코드의 로그·예외 메시지에 비밀값 없음: `ControlClient.java:94-95,99` 메시지는 고정 문구, 잠금 파일에는 PID만 기록(`ControlServer.java:104-107`). 테스트 값은 자리표시자·실행 중 생성 해시 |

### 직전 지적(재검토 1) 처리 확인
- B7(a3f31de 대상 risk-ops 재검토 없음): 해당 원문은 생겼다(risk-ops.md 185행 이후). 다만 그 결과로 다시 고친 c7e158b에 대한 재검토가 필요해져 이번 B7도 확인 못 함이다.
- 중요-A(PANIC이 BUSY로 거절되고 보호 모드 고정도 안 됨): **해결.** `ControlServer.java:228-232` PANIC_ACCOUNT·PANIC_ALL은 세마포어 없이 명령 스레드에 넣고 기다린다. 전체 수는 연결 상한 8(`ControlServer.java:56`)이 묶는다. ACTIVATE만 `queued`를 쓰고 STALE 검사도 ACTIVATE에만 남았다. PANIC이 같은 단일 명령 스레드에서 돌므로 ACTIVATE·PANIC 사이의 검증 경합도 없다. ADR-006 2.2 문구 일치. 1차 제안 (b)(1단계를 연결 스레드에서 즉시)는 택하지 않았고, 긴 활성화 뒤에서 PANIC 1단계가 기다리는 문제는 ADR-006 4장 149행에 7단계 완료 조건으로 남아 있다.
- 참고-3(동시 DEACTIVATE가 활성화 검증을 지움): **해결.** `EngineCommandHandler.java:71-76`.
- 참고-4(NOT_RUNNING이 소켓 파일 존재에만 기댐): **부분 해결.** 잠금 파일 PID로 보조 판정(`ControlClient.java:88-101`). 제안했던 "잠금을 잡아 보기" 대신 PID를 택해 실험실이 엔진 기동을 막을 위험을 피했다. 남은 점은 아래 참고-1·2.
- 참고-5(ADR-001 규칙 개수): **해결.**
- 참고-1(늦게 끝나는 활성화: 처리기에 기한 전달)과 참고-2(PBKDF2를 `synchronized` 안에서 계산해 STATUS가 막힘, `OperatorAuthenticator.java:48,60`): **반영 안 됨, ADR에도 기록 없음.** 둘 다 참고 등급이고 지금은 영향이 없으므로 이번 판정 사유로 삼지 않는다. 7단계 전에 ADR-006 6장 후속 표에 올려 두기를 권한다.
- 1차 참고 중 남은 것(프로브 connect 타임아웃, close 시 진행 중 명령 interrupt, Windows ACL, macOS sun_path)은 그대로이며 ADR-006 6장에 후속으로 기록돼 있다(프로브 타임아웃은 기록 없음).

## 점검표 밖 발견

### 참고
1. **Windows에서는 PID 보조 판정이 동작하지 않을 수 있다** `[추론, 미검증]`. 엔진은 `lockChannel.tryLock()`(`ControlServer.java:90`)으로 파일 전체 구간을 잠근다. Windows의 파일 잠금(LockFileEx)은 강제 잠금이라 다른 프로세스가 잠긴 구간을 읽으면 실패한다. 그러면 `ControlClient.java:91`의 `Files.readString`이 IOException을 던지고 99행으로 빠져 `NOT_RUNNING`이 된다. 즉 재검토 1 참고-4와 risk-ops F2가 막으려던 "엔진은 도는데 소켓만 없어 가상으로 보임"이 Windows에서는 그대로 남는다. 리눅스에서는 fcntl 권고 잠금이라 문제없다(테스트 통과). 수정 제안: PID를 잠그지 않는 별도 파일(`run/engine.pid`)에 쓰거나, 잠금 구간을 PID 기록 구간과 겹치지 않게(예: 파일 끝 너머 1바이트) 잡는다. 실사용 PC가 Windows로 정해지면 ADR-006 6장의 "Windows ACL·macOS 경로 길이" 항목에 이 확인을 함께 넣는다. 근본 해결인 "엔진이 지워진 소켓을 다시 만들기"는 이미 7단계 완료 조건(ADR-006 6장)이다.
2. **정상 종료 뒤에도 PID가 남아 PID 재사용 시 UNKNOWN이 뜬다.** `ControlServer.close()`(334-350행)는 잠금만 풀고 PID를 지우지 않는다. PC 재부팅 뒤처럼 PID가 다시 쓰이면 엔진이 꺼져 있어도 실험실은 "엔진 프로세스는 살아 있지만 명령 채널에 닿지 못했습니다"(UNKNOWN)를 보인다. 안전한 쪽(가상으로 보이지 않음)이고 엔진을 다시 띄우면 PID가 덮어써져 풀리므로 등급은 참고. ADR-006 48행이 재사용을 "안전한 쪽"으로 적어 둔 것과도 맞다. 완화 제안: close에서 잠금을 풀기 전에 `lockChannel.truncate(0)`, 판정에서 `ProcessHandle.info().startInstant()`가 잠금 파일 수정 시각보다 늦으면 다른 프로세스로 본다.
3. **테스트 이식성**: `ControlChannelTest.java:309` `new ProcessBuilder("true")`는 Windows에 없는 명령이다. CI(ubuntu)와 macOS에서는 동작한다. Windows에서 개발·검증하게 되면 `ProcessHandle`이 끝난 JVM 하위 프로세스(`java -version`) 등으로 바꾼다.
4. **PANIC 대기 상한**: PANIC은 이제 상한 없이 명령 스레드 뒤에 쌓인다(연결 8개가 상한). 실험실 쪽 대기 제한(15초)이 지나면 화면은 "응답 없음"인데 엔진은 나중에 PANIC을 실행한다. 내리는 방향이라 ADR 의도("기다렸어도 실행")와 맞고 위험하지 않다. 화면 문구가 "반영 여부는 엔진이 다시 응답해야 알 수 있습니다"(real.js 67행)로 이미 이 경우를 안내한다. 기록만 해 둔다.

## 판정
수정 필요(B7 확인 못 함: c7e158b 대상 risk-ops A 점검표 재검토 원문이 없음). architect 관점의 코드·계약 지적은 남지 않았다(점검표 B 실패 0, 중요 지적 0, 참고 4건). c7e158b 대상 risk-ops 재검토 원문이 생기면 B7만 다시 확인한다. 실전 경로 PR이므로 그 뒤에도 머지는 사용자가 직접 한다(지침 10장).

## 재검토 3 2026-10-09

# architect 검토: 실전 운영 화면(ADR-006) 재검토 2 이후 반영본
- 대상: 브랜치 claude/project-thread-r930sh, HEAD d402140, `git diff origin/main...HEAD` 전체(origin/main 2dcea43 = merge-base, 12커밋, 57개 파일). 직전 검토(c7e158b) 뒤 바뀐 것은 c4e1a18(ControlClient 판정 규칙, ControlChannelTest 1건 추가·1건 이식성 수정, real.js 문구, ADR-006), b554eba(ControlClient Javadoc, real.js 문구 1줄, ADR-006 2.2·4장·6장, config-keys.md 안내 1줄), 검토 원문 보존 커밋(51387cb, 8017d53, d402140; d402140은 ADR-006 1줄과 결정 로그 Q-018 추가 포함). diff 전체를 다시 훑고, 바뀐 실행 코드는 `ControlClient.notRunningOrUnknown`과 `ControlServer` 잠금 처리(80-135행)를 함께 읽었다
- 점검표: B
- 직접 실행한 것:
  - `git archive d402140`을 이 검토 전용 새 폴더(scratchpad `arch-rv3-1791509142`)에 풀고 `./gradlew build --offline --no-daemon --rerun-tasks`: **BUILD SUCCESSFUL**(91 actionable tasks, 91 executed, EXIT=0). 테스트 XML 집계 378건, 실패 0, 오류 0, 건너뜀 0. 이 중 ArchitectureTest 13, ControlChannelTest 20, EngineCommandHandlerTest 22, OperatorAuthenticatorTest 9, EngineMainTest 8, OperatorPasswordHashTest 5, RealOpsControllerTest 11. `verifyPureDependencies`·`verifyProcessIsolation` 13회 실행
  - gitleaks 8.21.2(`--redact`): `origin/main..HEAD`, 저장소 전 이력 모두 "no leaks found"
  - `scripts/ci/check-python-collectors.sh`: 종료 코드 0
  - `git diff --diff-filter=D origin/main...HEAD`: 삭제 파일 없음. 추가 줄의 `@Disabled`·`@Ignore`·`assume`은 검토 원문 인용 줄에만 있음
  - Linux에서만 실행했다. Windows·macOS와 GitHub Actions CI 단계(엔진 기동·소켓 권한 셸 검사)는 실행하지 않았다
- 코드는 고치지 않았다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 결과(로컬, --offline, 전용 폴더). CI 러너 결과는 보지 않았다 |
| B2 | 통과 | c7e158b 이후 모듈 의존·ArchUnit 규칙·build.gradle.kts 변경 없음. 바뀐 `ControlClient`는 JDK(`Files`, `NoSuchFileException`, `ProcessHandle`)만 써 control 모듈 PURE 규칙(`ArchitectureTest.java:58`) 유지. ArchitectureTest 13건·verifyPureDependencies·verifyProcessIsolation 통과 |
| B3 | 통과 | 프로세스 간 계약(실험실의 엔진 생존 판정 규칙)이 바뀌었고 ADR-006에 먼저 기록됨: 2.2(48행) "잠금 파일이 아예 없을 때만 NOT_RUNNING, 읽지 못하면 UNKNOWN", 2.3(78행) `engineState` 정의 "판단할 수 없으면 언제나 UNKNOWN". 코드(`ControlClient.java:91-107`: NoSuchFile→NOT_RUNNING 96행, 읽기 오류·비PID→UNKNOWN 99행, PID 생존→UNKNOWN 103행, PID 죽음→NOT_RUNNING)와 클래스 Javadoc(26-31행)이 일치. "엔진은 설정 폴더당 하나" 한계가 2.2(44행)·4장 위험 표에 정정됨. 5장 테스트 표 건수(ControlChannelTest 20건)가 실행 결과와 일치. REST 응답 형태·설정 키·스키마 변경 없음(config-keys.md는 운영 안내 문장만) |
| B4 | 통과 | 시뮬레이션 코드 변경 없음, 골든 테스트 포함 전체 통과. 새 코드에 시스템 시계·난수 없음(ArchUnit `NO_SYSTEM_TIME` 통과) |
| B5 | 해당 없음 | 금액을 다루는 코드 변경 없음 |
| B6 | 통과 | 바뀐 판정마다 테스트: `ControlChannelTest#잠금_파일을_읽지_못하거나_내용이_PID가_아니면_알_수_없음이다`(빈 파일, 폴더로 흉내 낸 읽기 오류 모두 UNKNOWN), `#엔진이_없으면_실행_중이_아님을_알린다`(잠금 파일 없음·죽은 PID → NOT_RUNNING, `true` 대신 현재 JVM `-version`으로 바꿔 Windows 이식성 개선, 단언은 그대로), `#엔진이_살아_있는데_소켓만_없어지면_실행_중이_아님이_아니라_알_수_없음이다` 유지. 테스트 삭제·비활성화 없음 |
| B7 | 통과 | 실전 경로(명령 채널, 엔진 생존 판정, Guard 제어 화면)를 건드림 → **risk-ops 검토 필요**이며, 그 원문이 있다: `docs/reviews/claude-project-thread-r930sh/risk-ops.md` "재검토 4"(353행 이후)가 b554eba(마지막 실행 코드 변경 커밋) 대상 A 점검표로 실패 0·확인 못 함 0, 판정 "승인". 그 뒤 d402140은 검토 원문, ADR-006 1줄(Q-018 참조), 결정 로그 Q-018 추가뿐이라 실행 코드 변경이 없다(`git show --stat d402140`). 실전 경로 PR이므로 머지는 사용자가 직접 한다(지침 10장) |
| B8 | 통과 | gitleaks 범위·전 이력 무결과. 새 예외 메시지(`ControlClient.java:97-101,104-105`)는 고정 문구이고 경로·PID·파일 내용을 담지 않는다. 테스트 값은 빈 문자열·실행 중 PID뿐 |

### 직전 지적(재검토 2) 처리 확인
- B7(c7e158b 대상 risk-ops 재검토 원문 없음): **해소.** 위 B7. risk-ops는 재검토 2·3에서 F1·F2·G1·G3·H1·H2를 지적했고 c4e1a18·b554eba로 고친 뒤 재검토 4에서 승인했다.
- 참고-1(Windows에서 잠긴 engine.lock 읽기 실패 시 NOT_RUNNING으로 빠짐): **해결(안전한 쪽).** 읽기 오류는 이제 UNKNOWN(`ControlClient.java:99-101`). Windows에서는 엔진이 살아 있고 소켓만 없을 때 PID 확인 대신 읽기 실패로 UNKNOWN이 되어 결과가 같다 `[추론, Windows 미검증]`. ADR-006 5장(174행)에 미검증으로 기록.
- 참고-2(정상 종료 뒤 PID가 남아 재사용 시 UNKNOWN): 변경 없음. ADR-006 48행이 "안전한 쪽"으로 기록. 참고 유지.
- 참고-3(`new ProcessBuilder("true")` 이식성): **해결.** 현재 JVM 실행 파일로 바꿈.
- 참고-4(PANIC 대기 상한): ADR-006 6장 표에 "기다리는 PANIC의 시간 상한"이 7단계 완료 조건으로 추가됨. 해결(기록).
- 재검토 1의 참고-1(활성화 기한 전달)·참고-2(PBKDF2를 `synchronized` 안에서 계산): 여전히 코드·ADR 모두 반영 없음. 지금 영향 없음(활성화 단계가 즉시 실패, STATUS 지연만). 아래 참고-1로 다시 남긴다.

## 점검표 밖 발견

### 참고
1. **재검토 1 참고-1·2를 ADR-006 6장 후속 표에 올릴 것.** 처리기에 요청 기한을 넘겨 늦게 끝나는 활성화를 버리기, `OperatorAuthenticator.verify`의 PBKDF2 계산을 모니터 밖으로 빼기. 둘 다 7단계에서 활성화 단계가 실제로 생기기 전에 할 일이다. 이번 판정 사유로 삼지 않는다.
2. **빈 잠금 파일이 남으면 UNKNOWN이 계속된다.** `ControlServer.java:104-107`에서 `truncate(0)` 뒤 PID 쓰기·`force`가 실패하면 잠금만 풀고 빈 파일이 남는다(또는 그 사이 프로세스가 죽은 경우). 그러면 엔진이 없어도 실험실은 다음 엔진 기동 전까지 UNKNOWN을 보인다. 안전한 쪽이고 엔진을 다시 띄우면 풀리며, 창이 매우 좁다. 화면 문구("엔진 상태를 확인하지 못했습니다… 실전 엔진 터미널을 확인하세요", real.js:57-58)가 이 경우도 맞게 안내한다. 기록만 한다.
3. **ADR-006 5장 테스트 표 문구**: "엔진 없음·죽은 소켓(잠금 파일 PID 죽음)은 NOT_RUNNING"은 맞지만, 새 규칙의 핵심인 "잠금 파일이 없을 때만"을 시험하는 경우(소켓 파일 없음 + 잠금 파일 없음)는 `#엔진이_없으면_실행_중이_아님을_알린다` 첫 단언이 맡는다. 표에 이를 명시하면 다음 검토가 쉬워진다. 선택 사항.

## 판정
판정은 **승인**. 점검표 B는 실패 0, 확인 못 함 0, 해당 없음 1(B5). 직전 판정 사유였던 B7은 risk-ops 재검토 4 승인 원문으로 해소됐고, 마지막 실행 코드 변경(c4e1a18·b554eba)은 ADR-006 계약과 일치하며 테스트로 덮였다. 전체 빌드 378건 통과, gitleaks·Python 경계 검사 통과. 참고 3건은 승인 조건이 아니다. 실전 경로(RealTradingGuard 제어, 장애 대응) PR이므로 이 승인 후에도 독립 QA와 사용자의 직접 확인·머지가 필요하다(지침 10장·11장).
