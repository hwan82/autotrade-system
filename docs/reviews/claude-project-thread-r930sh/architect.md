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
