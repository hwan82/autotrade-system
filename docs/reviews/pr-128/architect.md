# architect 검토: 알림을 감시·실전 엔진에 연결(MON-06 2차, D-100)
- 대상: PR #128 / 브랜치 `claude/project-thread-vtmvk8` / 커밋 918f2e4(7536be0 + 918f2e4) / `git diff origin/main...HEAD`(main 5b46d17 기준, 36개 파일)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build` (4회: 기본, `--offline`, `--refresh-dependencies`, 2분 간격 재시도 루프) → **모두 BUILD FAILED, 코드 단계까지 가지 못함**. `:build-logic` 클래스패스 해석에서 `org.jetbrains.kotlin:kotlin-stdlib:2.0.21` 등을 Maven Central에서 받지 못함(HTTP 429 Too Many Requests, 이어서 404). 같은 URL을 `curl`로 10회 다시 받아 봐도 404·429만 왔다. 이 세션의 네트워크 문제이고 PR 코드와 무관하지만, **컴파일·테스트·ArchUnit은 이 검토자가 실행하지 못했다**.
  - 참고(판정 근거로 쓰지 않음): GitHub check run `Gradle build` 성공(918f2e4, id 116802247975), 7536be0에서는 실패(918f2e4가 `NotifierTest`를 고침). `Secret scan and collector boundary`·`DB schema` 성공.
  - 코드 읽기: diff 전체, `ConfigLoader`·`EngineCommandHandler`·`OperatorAuthenticator`·`Notifier.dispatch`·ADR-001·ADR-005·ADR-006 관련 부분.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 확인 못 함 | 위 "직접 실행한 것". 의존성 해석 실패(Maven Central 429/404)로 컴파일 전에 멈춤. CI 성공은 참고만 |
| B2 | 확인 못 함 | 정적으로는 위반을 찾지 못했다: 새 모듈 의존 없음(엔진 `autotrade-app-engine/build.gradle.kts:26`, 감시 `autotrade-app-watchdog/build.gradle.kts:50`은 main에서 이미 notification-jar 의존), notification-jar는 config를 모르고 엔진·감시가 값을 문자열로 옮김(EngineMain.java:129, WatchdogMain.java:120). 새 운영 코드의 JDK 타입은 D-100 검증 절이 든 D-086 허용 목록 안이라고 적혀 있으나 `RealPathReflectionTest`·`ArchitectureTest`를 직접 돌리지 못했다 |
| B3 | 실패 | 설정 키 계약 변경(카카오 키 4개 추가, `access-token` 선택, 검증 조건 변경, 반영 시점 "즉시" → "실전 엔진·감시 재기동")은 config-keys.md·D-100에 있으나 ① ADR-005 설정 화면 표가 옛 계약 그대로(F2) ② ADR-001 모듈 표에 새 공개 타입이 없음(F2) ③ 기존 설정 파일 호환성 영향이 문서에 없음(F3) |
| B4 | 통과 | 현재 시각은 주입 `Clock`만 씀(NotificationQueue.java `clock.instant()`, Watchdog·HeartbeatWatch의 `now` 인자, KakaoMemoChannel `clock`). `System.nanoTime`은 시험의 대기 루프에만 있음. 시뮬레이션 모듈(sim·strategy) 변경 없음, 골든 테스트 대상 코드 변경 없음. 시험 실행 확인은 B1과 같이 못 함 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험: `NotificationQueueTest`(10개), `KakaoMemoChannelTest` 6개 추가, `FileKakaoTokenStoreTest`, `NotificationSetupTest`, `JdkHttpTransportTest` 2개, `WatchdogTest` 11개 추가, `NotifyingCommandAuditLogTest`, `SettingsControllerTest#카카오_나에게_보내기는_갱신_키와_링크가_있어야_켤_수_있고_새_키는_쓰기_전용이다`, `ConfigLoaderTest` 비밀값 경로. 지우거나 약화한 시험 없음: `NotifierTest`의 바뀐 단언(918f2e4)은 가짜 "log" 채널을 실제 `LogChannel`로 바꾼 것이고 main의 `Notifier.dispatch` 중단 처리(로그 채널은 남김)와 맞춘 강화다. 빈틈은 F1 시험 없음, R1(엔진·감시 설정 옮기기 시험 없음) |
| B7 | 실패(별도 검토 필요) | 보호 경로(`autotrade-app-engine/*`, `autotrade-app-watchdog/*`, `autotrade-config/*`, `autotrade-notification-jar/*`, scripts/ci/protected-paths.txt) 변경이고 실전 엔진의 명령 처리 경로(`EngineCommandHandler`의 감사 로그를 `NotifyingCommandAuditLog`로 감쌈, EngineMain.java:96-97)를 건드린다. **risk-ops 검토 필요**. 이 글을 쓰는 시점에 `docs/reviews/pr-128/risk-ops.md`는 없다. 사용자 머지·독립 QA 대상 |
| B8 | 통과 | `NotificationSetup`·`FileKakaoTokenStore`의 `toString`에 값 없음(시험 있음), 토큰 파일에는 설정 리프레시 토큰 대신 SHA-256만(시험 있음), 오류 로그 문구에 값 없음(NotificationSetup.java ERROR/WARN 문구), 재동의 알림 본문에 토큰 없음(`NotificationQueueTest#카카오_갱신_거절을_보면_재동의_알림을_한_번만_넣는다`), 감사 알림은 계좌 별칭만(EngineCommandHandler.java:339-344). 시험 값은 자리표시자, CI gitleaks 성공 |

## 점검표 밖 발견

### F1(수정 필요): 전체 상한이 긴급 알림을 버리고, 감시는 상태가 바뀔 때만 알리므로 버린 긴급 알림이 다시 나가지 않는다
- `[사실]` `NotificationQueue.accept`는 등급과 상관없이 10분 창에 20건을 넘으면 버린다(NotificationQueue.java:134-138). 버린 건수는 "다음에 받는 알림" 본문에만 붙는다.
- `[사실]` 같은 대상이라도 종류가 바뀌면 억제하지 않는다(:126, `last.kind().equals(kind)`). 그래서 STALE↔ALIVE를 오가는 프로세스는 점검(기본 5초, WatchdogSettings.java:58)마다 `process-stale`·`process-recovered`를 번갈아 넣어 상한을 채울 수 있다.
- `[사실]` 감시는 상태가 바뀔 때만 알린다(Watchdog.java `alertChange`). 상한에 걸려 버린 `engine` `process-stale`은 엔진이 멈춘 채로 있는 한 다시 넣지 않는다. 사람은 다음 아무 알림의 "다른 알림 N건 생략"만 보고 무엇이 빠졌는지 모른다. 폭주가 그치면 그 문구조차 안 나간다(D-100 위험 3번째 줄이 인정).
- `[추론]` 자원 압박(GC·CPU)으로 하트비트가 기준 근처에서 흔들리는 때가 바로 엔진도 멈출 수 있는 때다. 장애표 #8 알림이 그 순간 사라지는 것은 이 PR의 목적(사람이 바로 알게 함)과 절대 규칙 6의 취지에 어긋난다. 실전 엔진 큐도 같다: 서로 다른 계좌·명령·결과 코드 조합은 대상이 달라 억제되지 않으므로, 20건을 넘긴 뒤의 `ACTIVATE … OK` 알림이 버려질 수 있다(인증 잠금은 5회 실패 15분, OperatorAuthenticator.java:26-27, 이지만 `DEACTIVATE`는 인증이 없고 계좌가 여럿이면 대상이 갈린다).
- 고칠 방향(택1, 고른 이유를 D-100에 적을 것): ① 전체 상한은 정보 등급에만 적용하고 긴급은 같은 대상·종류 억제만 받는다(추천: 가장 단순, 긴급 폭주는 대상·종류 억제가 이미 묶음). ② 긴급을 버릴 때 요약(종류·대상 목록)을 큐가 들고 있다가 창이 열리면 보내는 스레드가 스스로 한 건으로 내보낸다(다음 offer에 기대지 않음).
- 시험: 정보·번갈아 오는 상태 알림으로 상한을 채운 뒤 `process-stale`(긴급)이 외부 채널로 나가는지. D-100 결정 1·위험 절 갱신.

### F2(수정 필요, 문서): 계약 문서가 바뀐 설정 계약과 어긋난다
- `docs/adr/ADR-005-ui-dashboard.md:139`는 `notification.*`을 "실시간 변경 '즉시' 키"로, :140은 비밀값을 `webhook-url`·`access-token`만으로 적고 있다. 이 PR에서 반영 시점은 "실전 엔진·감시 재기동"이고(SettingsController `NOTIFY_LIVE`), 비밀값은 `rest-api-key`·`refresh-token`·`client-secret`이 늘었고 `link-url`은 화면에서 편집 불가가 됐다.
- `docs/adr/ADR-001-module-structure.md:56` notification-jar 행에 새 공개 타입(`NotificationQueue`, `NotificationSetup`, `FileKakaoTokenStore`, `KakaoTokenStore.latest`)과 "설정 폴더의 `notification-state/kakao-token.json`을 읽고 쓴다"는 파일 계약이 없다. 이 파일은 엔진·감시 두 프로세스가 공유하는 계약이다.

### F3(수정 필요): 기존 설정 파일이 새 검증에 걸리면 runtime-config.json 전체가 빠진다
- `[사실]` main에서는 `notification.kakao.enabled=true` + `access-token`만 있으면 유효했고(main RuntimeConfig.Kakao), 실험실 설정 화면이 이 값을 저장할 수 있었다(ADR-005:139-140). 이 PR은 ME 모드에서 `rest-api-key`·`refresh-token`·`link-url`을 요구한다(RuntimeConfig.java:97-101).
- `[사실]` 합친 설정 검증이 실패하면 `ConfigLoader`는 일반 설정 파일을 통째로 빼고 기동한다(ConfigLoader.java:92-105). 그러면 `real-trading.accounts`·`market-data.*`·`lab.*`도 함께 사라진다. 실전 엔진은 계좌 0개로 떠서 안전한 쪽이지만, 사용자는 알림 키 하나 때문에 계좌 설정이 없어진 엔진을 보게 되고, 그 설정 경고 알림도 기본값(로그 채널)으로만 나간다.
- 고칠 방향: ① 이 키 조합은 설정 거부 대신 `NotificationSetup`처럼 카카오 채널만 끄고 경고(이미 NotificationSetup.java `kakao()`가 그렇게 한다, 추천: 알림 설정이 실전 설정을 끌어내리지 않음) 또는 ② 거부를 유지한다면 D-100 위험 절과 config-keys.md에 "업그레이드 전 kakao.enabled를 끄거나 새 키를 넣을 것"을 적고 사용자에게 알린다. ①은 설정 검증 계약 변경이라 risk-ops 의견을 함께 받을 것.

### 권고(이번 PR 필수 아님)
- R1: `EngineMain.setup`(EngineMain.java:129-135)과 `WatchdogMain.setup`(WatchdogMain.java:120-125)이 같은 코드이고, `NotificationSetup` 생성자에 문자열 5개를 순서로 넘긴다(restApiKey, clientSecret, accessToken, refreshToken, linkUrl). 순서가 틀려도 컴파일되고 이를 잡는 시험이 없다. 값마다 다른 설정으로 `setup(...)` 결과 채널(카카오 생성 여부, 토큰 파일 출처)을 확인하는 시험을 엔진·감시에 하나씩 두거나 이름 있는 빌더로 바꿀 것.
- R2: 재사용 라이브러리(notification-jar는 "자동매매 도메인을 몰라야 한다")에 이 시스템의 설정 키·프로세스 이름이 들어갔다: 재동의 알림 본문의 "secrets.json의 notification.kakao.refresh-token… 실전 엔진·감시를 다시 시작"(NotificationQueue.java:192-196), `NotificationSetup.DISPLAY_ZONE = Asia/Seoul`. 문구와 시간대를 부르는 쪽에서 넘기게 하면 경계가 깨끗하다.
- R3: `WatchdogMain.start`에서 `watchdog.start()`(WatchdogMain.java:103)가 던지면 알림 큐 스레드가 닫히지 않는다(데몬이라 프로세스 종료는 막지 않음). 생성자 실패와 같이 닫을 것.
- R4: `FileKakaoTokenStore`는 POSIX가 아니면(사용자 PC는 Windows) 권한을 좁히지 않는다. `secrets.json`과 같은 수준이라 받아들일 수 있으나 D-100 위험 절에 적을 것.
- R5: 큐 가득(`CAPACITY` 64) 경로는 상한 20이 먼저 걸려 시험이 닿지 않는다(`NotificationQueueTest#채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`는 실제로 상한만 확인). 이름과 내용을 맞추거나 여러 창에 걸쳐 보내는 스레드가 막힌 경우를 시험할 것.

### 확인한 것(문제 없음)
- 엔진 기동 순서: 설정 → Guard 모두 OFF → 알림 큐 → 명령 채널 → 하트비트(EngineMain.java:89-110). 알림이 Guard 상태를 바꾸는 경로 없음. 기동 실패 시 큐를 닫음(:111-114), 종료 시 서버 → 하트비트 → 큐 순으로 닫고 각 단계 실패가 다음을 막지 않음.
- `NotifyingCommandAuditLog`: 감사 로그 실패를 그대로 올리고(기존 `EngineCommandHandler`의 실패 처리 유지) 알림은 `finally`에서 넣음. `offer`는 짧은 `synchronized` 안에서 I/O 없이 돌아오고 어떤 `Throwable`도 던지지 않음(NotificationQueue.java:110-115). HTTP는 `notification-<프로세스>` 데몬 스레드에서만.
- `Notifier.hasExternalChannel`·`DispatchReport.delivered`가 이름 대신 `LogChannel` 타입/상수로 가림, 이름 `log` 사칭 거부(PR #127 architect R2 해소). 시각 표시 시간대(PR #127 architect R3 해소).
- 카카오 거절 기억과 저장소 토큰 갈아타기 로직(KakaoMemoChannel `adoptLatest`), 토큰 파일 원자적 쓰기(프로세스별 임시 파일 이름)와 4KB 읽기 상한.

## 판정
수정 필요(F1: 전체 상한이 긴급 알림을 버리지 않게 하고 시험 추가, F2: ADR-005·ADR-001 계약 문서 갱신, F3: 기존 설정 호환성 처리 또는 문서화, B1·B2: 검토자 빌드 미실행이라 재검토 때 `./gradlew build` 직접 통과 확인 필요). B7은 risk-ops 검토가 따로 있어야 한다(현재 없음). R1~R5는 권고.
