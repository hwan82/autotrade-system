# 독립 QA 검토: 알림을 감시·실전 엔진에 연결(MON-06 2차, D-100)
- 대상: PR #128 / 헤드 95163d5 (브랜치 claude/project-thread-vtmvk8) / main 5b46d17 대비 전체 diff(`git diff origin/main...95163d5`, 42개 파일 +2289 −124). 병합 기준점 = 현재 main 5b46d17.
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build -I <검토자 임시 폴더>/mirror.init.gradle --refresh-dependencies` → `BUILD SUCCESSFUL in 4m 36s`, 종료 코드 0. 시험 결과 XML 집계 1,428건 / 실패 0 / 오류 0 / 건너뜀 0. 모듈별: `autotrade-notification-jar` 74, `autotrade-app-watchdog` 51, `autotrade-app-engine` 54, `autotrade-config` 40, `autotrade-app-lab` 214, `autotrade-risk` 55, `autotrade-architecture-test` 44(`ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10, `StopRequestPathTest` 2) 전부 통과.
    - 빌드 환경 메모: 미러 없는 빌드는 `Could not find org.jetbrains.kotlin:kotlin-stdlib:2.0.21`로 실패했다(이 컨테이너에서 Maven Central 차단, 코드 실패 아님). 사용자 허락을 받아 저장소 파일은 건드리지 않고 검토자 임시 폴더의 init 스크립트로 Maven Central 공개 미러(`maven-central.storage-download.googleapis.com/maven2`)를 앞에 두어 받았다. 빌드 스크립트·설정 변경 없음.
  - PR CI(헤드 95163d5): `Gradle build`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary` 모두 success(GitHub check runs 조회).
  - 탐침 시험 P1~P5: 임시 시험 클래스 `QaProbe128Test`를 `autotrade-notification-jar` 시험 폴더에 넣어 `:autotrade-notification-jar:test --tests '*QaProbe128Test'` 실행 뒤 지움(커밋 안 함). 결과는 아래 "시나리오".
  - 변이 M1~M15: 운영 코드를 한 곳씩 고치고 해당 모듈 `test` 실행, 매번 `git checkout`으로 되돌림. 끝난 뒤 `git status --short` 출력 없음(탐침 파일 삭제 확인).

    | 변이 | 결과 |
    |---|---|
    | M1 `NotificationQueue` 전체 상한을 긴급에도 적용(`&& severityOf(kind) == INFO` 삭제) | 잡힘 |
    | M2 `NotifyingCommandAuditLog` 억제 대상에서 결과 코드 삭제 | 잡힘 |
    | M3 `Watchdog` 결측 알림을 10분 반복마다(`if (newName)` → `if (true)`) | 잡힘 |
    | M4 `KakaoMemoChannel.adoptLatest`의 "더 늦게 만료되는 저장소 토큰" 판정 끔 | 잡힘 |
    | M5 `HeartbeatWatch` 시계 역행 조건 삭제 | 잡힘 |
    | M6 `Watchdog` 회복 알림 삭제 | 잡힘 |
    | M7 `Watchdog` 재기동 알림 삭제 | 잡힘 |
    | M8 `FileKakaoTokenStore.latest` 출처 해시 검사 삭제 | 잡힘 |
    | M9 `NotificationQueue.deliver`가 `Exception`만 잡음 | 잡힘 |
    | M10 `EngineMain.start`가 `NotifyingCommandAuditLog` 대신 `CommandAuditLog.LOGGING` 연결 | **살아남음** |
    | M11 `KakaoMemoChannel.refresh` 거절 기억(`rejectedRefreshToken = ...`) 삭제 | 잡힘 |
    | M12 `Watchdog` 정지 요청 켜짐 알림 끔 | 잡힘 |
    | M13 `NotificationQueue` 재동의 알림 끔 | 잡힘 |
    | M14 `WatchdogMain.start`가 `notifications::offer` 대신 `Alerts.NONE` 연결 | **살아남음** |
    | M15 `NotificationSetup` BIZ(유료) 차단 조건 끔 | 잡힘 |

## 이번 PR이 실전 경로에 닿는 곳
- 실전 엔진(`EngineMain.start`, EngineMain.java:92-112): Guard 기동(`startGuard`, :92, 변경 없음) 뒤 알림 큐를 띄우고(:94), 실전 제어 명령 감사 로그를 `NotifyingCommandAuditLog`로 감싼다(:97). 엔진이 하는 일은 `NotificationQueue.offer`(막지 않음·던지지 않음, NotificationQueue.java:111-117)뿐이고 HTTP는 데몬 스레드 `notification-engine`에서만 돈다. 종료 때 큐를 최대 3초+1초 닫는다(:216).
- 감시(`WatchdogMain.start`·`Watchdog.runOnce`): 상태 변화 때 `alert`(Watchdog.java:161-167, `Throwable`까지 삼킴)로 큐에 넣는다. 하트비트 판정·정지 요청 파일 로직 자체는 그대로이고, 시계 역행 시 반복 경고가 밀리지 않게 조건만 넓혔다(HeartbeatWatch.java:115-116, Watchdog.java:215-216).
- 설정(`RuntimeConfig.Kakao`): 비밀값 3개·`link-url` 추가, 켜짐+키 부족을 더는 거부하지 않음(예전 파일 호환). 실험실 설정 화면은 카카오 값을 고칠 때만 거부.
- 보호 테스트(`autotrade-architecture-test`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`) diff 없음. 고정 값 변경은 `ConfigLoaderTest`의 비밀값 경로 목록(+3줄)뿐. `RealPathReflectionTest`는 엔진·감시·알림 모듈을 검사하고 통과(새 운영 코드가 D-086 허용 목록 안 타입만 씀).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` diff 없음. `EngineMain.startGuard`(EngineMain.java:92) 변경 없이 그 뒤에 알림 큐를 띄우고, 엔진 시작 알림 문구도 "RealTradingGuard 모두 OFF"(:103-104). 설정 키에 Guard 상태 없음(config-keys.md). `:autotrade-risk:test` 55건 포함 전체 빌드 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 diff 없음. 알림 모듈은 주문·broker를 모름(`ArchitectureTest` 19건 통과, 규칙 변경 없음). `NotifyingCommandAuditLog`는 명령 처리 뒤 감사 기록 자리(EngineCommandHandler.java:91)에서만 불리고 Guard 전이에 관여하지 않음 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음. 명령 알림의 억제 대상이 `명령+계좌 별칭+결과 코드`라 한 계좌 알림이 다른 계좌 알림을 억제하지 않음(NotifyingCommandAuditLog.java:28-29) |
| A8 | 통과 | 엔진 스레드는 `offer`만 부른다. `accept`는 `synchronized`지만 안에서 막는 호출이 없고 큐는 `ArrayBlockingQueue.offer`(비차단, NotificationQueue.java:149). 탐침 P4: 채널이 영원히 막히고 첫 알림에서 `OutOfMemoryError`를 던져도 1,000건 `offer`가 10ms에 끝남(64건 보관·936건 버림, 넣는 쪽 예외 0). 감시 `alert`는 `Throwable`을 삼킴(Watchdog.java:161-167, `WatchdogTest#알림이_던져도_점검은_계속한다` 통과). 엔진 기동은 알림 설정이 틀려도 막히지 않음(NotificationSetup.java:72-116, 채널만 끄고 로그). 기동 중 예외 시 큐를 닫음(EngineMain.java:110-111). 엔진·감시가 같은 토큰 파일을 공유하지만 읽기·쓰기는 알림 스레드에서만 일어나고 실패는 빈 값으로 처리(FileKakaoTokenStore.java:96-118) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 비밀값 3개는 `Secret` 타입이고 `SecretPaths`가 경로를 만듦(`ConfigLoaderTest` 비밀값 경로 목록 통과). 설정 화면 응답은 쓰기 전용(SettingsController.java:73 `SECRETS`). 엔진·감시 로그는 채널 이름만 남김(EngineMain.java:119-120). 토큰 파일은 `secrets.json`의 리프레시 토큰 원문 대신 SHA-256만 출처로 적고(FileKakaoTokenStore.java:55), POSIX에서 `rw-------`(:79). 탐침 P5: 실제 파일 권한 `[OWNER_WRITE, OWNER_READ]`, 파일에 설정 토큰 원문 없음. 명령 알림에 비밀번호·확인 문구 없음(감사 로그 인자가 명령·별칭·결과 코드뿐). 시험 픽스처는 자리표시자(`REST-KEY-PH`, `https://hooks.slack.com/services/TPLACEHOLDER/...`), diff 추가 줄 32자 이상 토큰 모양 검색은 문서 파일 이름만. CI `Secret scan and collector boundary` success. Windows 권한은 아래 R3 |
| A11 | 통과 | 보호 테스트 삭제·비활성화 없음, `@Disabled`·`assume` 없음. 고정 값 갱신은 `ConfigLoaderTest` 비밀값 경로 3줄이며 대상 코드(`RuntimeConfig.Kakao`의 `Secret` 필드 3개)를 확인했다. 바뀐 기존 시험 `NotifierTest` 중단 시험은 실제 `LogChannel`을 쓰면서 기존 동작(중단 때도 로그 채널 기록, PR #127 risk-ops R2)에 기대를 맞춘 것이다. 새 동작 시험은 실패 경우 포함(막힌 채널·큐 가득·`Error`·형식 오류·억제·시계 역행·거절 기억·4KB 초과·출처 다름·기한 초과·8KB 중단). 변이 15개 중 13개 잡힘, 살아남은 M10·M14는 배선 시험 공백(권고 Q1) |
| A12 | 통과 | 장애표 #8·#9·#10의 감지(하트비트 판정·ERROR 로그·정지 요청 파일)는 바뀌지 않고 알림이 더해졌다. 시계 역행 변경은 경고를 앞당기는 쪽(`WatchdogTest#시계가_뒤로_가도_반복_경고가_밀리지_않는다`, `#하트비트를_하나도_못_본_경고도_시계가_뒤로_가면_바로_되풀이한다` 통과, 변이 M5 잡힘). 장애 테스트 8종의 전제(주문·대사·계좌 격리)에 닿는 코드 없음. 단 #8 문구에 새로 넣은 "멈춤·재기동은 긴급 알림"이 특정 순서에서 지켜지지 않는다(F1, A14에서 실패로 셈) |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 알림 시각 표시 시간대(Asia/Seoul)는 표시용이고 계산에 쓰지 않음(Notification.java `plainText`) |
| A14 | **실패** | D-100 결정 1번은 "종류가 바뀌면(멈춤 → 회복 → 멈춤) 늘 보낸다"이고, architect F1 반영 근거는 "감시는 상태가 바뀔 때만 알리므로 버린 긴급 알림은 다시 오지 않는다"이다. 코드는 정보 알림이 전체 상한(NotificationQueue.java:136-139)이나 큐 가득(:149-152)으로 버려지면 `lastBySubject`를 갱신하지 않고 돌아간다(갱신은 받았을 때만, :156). 그래서 엔진 멈춤(긴급, 보냄) → 회복(정보, 버려짐) → 10분 안 다시 멈춤(긴급)에서 두 번째 멈춤이 "같은 대상·같은 종류"로 억제된다(:127-130). 탐침 P1(상한)·P2(큐 가득) 모두 `stale#2=false suppressed=1`. 문서와 코드가 다르고, 장애표 #8 문구(엔진 멈춤은 긴급 알림)도 이 경로에서 거짓이 된다 → F1. 그 밖의 대조는 일치: 매핑표 14종(EventRoutes.java, `EventRoutesTest`), 큐 64·10분·20건·닫기 3초(NotificationQueue.java 상수), 카카오 키 4개(config-keys.md·ADR-005·`SettingsControllerTest`), 토큰 파일 위치·출처 해시(FileKakaoTokenStore.java), 거절 기억·재동의 한 번(KakaoMemoChannel.java, `NotificationQueueTest#카카오_갱신_거절을_보면_재동의_알림을_한_번만_넣는다`), 기획서 v14.44 MON-02·MON-06 행, 결정 로그 표 D-100 행. 과금(지침 8장): 알림 기본값 모두 꺼짐(autotrade-default.json), 채널은 Slack Webhook·카카오 나에게 보내기 두 무료 채널과 로그뿐, `mode=BIZ`(유료)는 채널을 만들지 않음(NotificationSetup.java:98-101, 변이 M15 잡힘) → 과금 검사 통과 |
| B1 | 통과 | 위 직접 빌드 `BUILD SUCCESSFUL`, 1,428/0/0/0 |
| B6 | 통과 | 새 시험 파일 6개(`NotificationQueueTest`, `NotificationSetupTest`, `FileKakaoTokenStoreTest`, `JdkHttpTransportTest` 보강, `KakaoMemoChannelTest` 보강, `NotifyingCommandAuditLogTest`)와 `WatchdogTest`·`SettingsControllerTest`·`SettingsDiskGuardTest`·`ConfigLoaderTest`·`EventRoutesTest` 보강, 건너뜀 0. 시험 삭제 없음(`NotifierTest`는 기대값 조정, A11 참고). F1 경로와 배선(M10·M14)에는 시험 없음 |

## 시나리오(잘못된 알림·누락·보호 공백 시도)
- P1 엔진 멈춤(긴급, 받음) → 다른 대상 긴급 20건으로 10분 창의 상한을 채움 → 엔진 회복(정보) **버려짐** → 4분 뒤 엔진 다시 멈춤(긴급) → **억제됨**(`offer=false`, `suppressedTotal=1`). 사람이 엔진을 손으로 다시 띄운 직후 또 죽어도 알림이 없다. 생략 건수는 다음 같은 종류 알림이 올 때만 붙는다. → F1.
- P2 같은 순서를 큐 가득으로: 채널이 막힌 동안(Slack·카카오 응답 없음, 발송 한 건 최대 약 10~30초) 다른 긴급 64건이 큐를 채우고 회복이 버려짐 → 두 번째 엔진 멈춤 억제. → F1.
- P3 점검 사이 재기동(엔진이 15초 안에 죽고 다시 뜸)이 2분 간격으로 두 번 → 두 번째 `process-restarted` 억제. D-100 1번의 "같은 대상·같은 종류 10분에 한 번"대로이며 생략 건수는 다음 재기동 알림에 붙는다. 설계대로라 결함으로 세지 않음. → 참고 R2.
- P4 채널이 영원히 막히고 첫 발송에서 `OutOfMemoryError` → 넣는 쪽 1,000건 10ms, 예외 0, 보관 64건. 엔진 명령 스레드가 알림 때문에 막히거나 죽는 경로 없음.
- P5 엔진·감시가 같은 설정 폴더 토큰 파일 공유: 엔진이 만료 토큰을 갱신해 회전된 리프레시 토큰(R2)을 받아 저장 → 감시는 갱신 요청 없이 파일 토큰(A2/R2)으로 발송 성공(감시 HTTP 요청 1건, 발송만). 파일 권한 `rw-------`, 파일에 설정 리프레시 토큰 원문 없음.
- (코드 읽기, `[추론]`) 엔진과 감시가 리프레시 토큰 회전 시점에 거의 동시에 갱신하면 한쪽이 400을 받아 "카카오톡 알림 재동의 필요"를 보낼 수 있으나, 다음 발송에서 파일의 새 리프레시 토큰으로 갈아타 풀린다(KakaoMemoChannel.java `adoptLatest`). 사람에게는 틀린 재동의 안내가 한 번 갈 수 있다. D-100 "위험과 한계" 첫 줄에 적혀 있음.

## 점검표 밖 발견
- F1(수정 필요): 버려진 알림이 억제 상태를 바꾸지 않아, 종류가 바뀐 뒤 같은 종류로 돌아온 긴급 알림이 억제된다(NotificationQueue.java:127-156, 탐침 P1·P2). 고칠 방향 예: 상한·큐 가득으로 버릴 때도 그 대상의 마지막 종류를 기록하거나(억제 기준만 갱신, 생략 건수는 `dropped`로 계속 셈), 대상의 마지막 종류와 다른 종류가 들어왔다는 사실을 남겨 다음 같은 종류를 받게 한다. P1·P2를 시험으로 고정할 것.
- Q1(권고, 시험 공백): 변이 M10(엔진이 `NotifyingCommandAuditLog` 대신 기본 감사 로그를 연결)·M14(감시가 `Alerts.NONE`을 연결)가 살아남는다. 부품 시험은 있지만 운영 배선이 빠지면 알림이 하나도 안 나가도 빌드가 통과한다(risk-ops R2와 같은 지적). `EngineMain.start`·`WatchdogMain.start`가 큐에 실제로 넣는지 보는 시험을 권한다.
- R1(권고): 개발 쪽 architect 검토 원문(`docs/reviews/pr-128/architect.md`)의 마지막 판정이 "수정 필요(B1·B2 검토자 빌드 확인)"로 남아 있다. 지침 11장상 미해결 "수정 필요"가 있으면 머지하지 않으므로, 개발 쪽에서 architect 재검토 판정을 받아야 한다. 이 보고서의 직접 빌드 결과는 독립 QA 판정 근거이며 architect 판정을 대신하지 않는다.
- R2(참고): 같은 대상·종류 10분 억제로 엔진 재기동 반복(P3)·명령 반복(같은 결과의 PANIC 재시도)은 두 번째부터 알림이 없고 생략 건수는 다음 같은 알림이 올 때만 보인다(D-100 "위험과 한계"에 적힘). 6단계 주간 점검(후속 4번)에서 긴급 종류를 억제에서 뺄지 볼 것.
- R3(참고): Windows(사용자 PC)에서 토큰 파일 권한은 설정 폴더 ACL을 그대로 물려받는다(D-100 "위험과 한계", architect R4). `secrets.json`과 같은 보호 수준이다.

## 판정
수정 필요(A14: F1 버려진 정보 알림 뒤 같은 대상의 긴급 알림 억제, D-100 결정 1번·장애표 #8 문구와 코드 불일치. 고친 뒤 P1·P2 시험 추가)
