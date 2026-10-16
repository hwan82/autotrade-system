# risk-ops 검토: 알림을 감시·실전 엔진에 연결(MON-06 2차, D-100)
- 대상: PR #128 / 브랜치 `claude/project-thread-vtmvk8` / 커밋 918f2e4(PR head, `gh api repos/hwan82/autotrade-system/pulls/128`로 확인) / main(5b46d17) 대비 diff `git diff origin/main...HEAD` 전체(커밋 2개 7536be0·918f2e4, 35개 파일)
- 점검표: A
- 직접 실행한 것:
  - 이 컨테이너에서 Maven Central(`repo.maven.apache.org`)이 404·429를 돌려줘 의존성을 받지 못했다. 저장소 밖 스크래치의 Gradle init 스크립트로 같은 Maven Central 내용의 Google 미러(`maven-central.storage-download.googleapis.com/maven2/`)를 앞에 두고 실행했다(저장소 파일 변경 없음).
  - 7536be0(첫 커밋)에서 `./gradlew build --continue` → **BUILD FAILED**. `NotifierTest > 중단되면_인터럽트_표시를_되살리고_남은_채널을_건너뛴다()` 실패(`Expected size: 1 but was: 2`). 시험의 `log`를 실제 `LogChannel`로 바꾸면서 중단 때도 로그 채널을 부르는 기존 동작(Notifier.java:97-104, PR #127 risk-ops R2)과 기대값이 어긋난 것이다. D-100 "위험과 한계"의 "로컬 빌드를 하지 못했다"가 사실이었고, 첫 커밋 CI도 `Gradle build` failure(`gh api .../commits/7536be0/check-runs`).
  - 918f2e4(PR head, 위 시험만 고침)에서 `./gradlew clean build --continue` → **BUILD SUCCESSFUL**(161 tasks). 이어서 `:autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-app-watchdog:test :autotrade-notification-jar:test :autotrade-config:test --rerun-tasks` → BUILD SUCCESSFUL(68 tasks 모두 실행). JUnit XML 집계(테스트/실패/오류/건너뜀): risk 55/0/0/0, architecture-test 44/0/0/0, app-engine 54/0/0/0, app-watchdog 51/0/0/0, notification-jar 68/0/0/0, config 39/0/0/0, app-lab 213/0/0/0, app-gateway 50/0/0/0. 918f2e4의 CI도 `Gradle build`·`Secret scan and collector boundary`·`DB schema` 모두 success.
  - 918f2e4의 시험 수정은 기대를 좁히지 않고 넓혔다: 중단 뒤 외부 채널(kakao)은 건너뛰고(`kakao.got` 비어 있음) 로그는 한 줄 남는지까지 본다(NotifierTest.java:147-162). 약화 아님.
  - 탐침 시험(저장소에 남기지 않음, 실행 뒤 지움): `NotificationQueue`에 `lab`의 `process-stale`·`process-recovered`를 20초 간격으로 10번씩 번갈아 넣은 뒤 `engine`의 `process-stale`을 넣었다. 결과 `offer`가 false, `droppedTotal()==1`, 외부 채널에 "engine 멈춤"이 가지 않음(시험 통과 = 아래 R1 재현).

## 이 PR의 실전 경로 영향 범위(판정의 전제)
- [사실] 실전 엔진이 새로 하는 일: 기동 때 알림 큐와 보내는 데몬 스레드 하나를 띄움(EngineMain.java:94, NotificationQueue.java:91), 시작·설정 경고 알림을 넣음(EngineMain.java:103-108), 실전 제어 명령 감사 로그를 `NotifyingCommandAuditLog`로 감쌈(EngineMain.java:96-97), 닫을 때 최대 3초+1초 남은 알림을 보냄(EngineMain.java:206-216, NotificationQueue.java:233-261). 실전 엔진 프로세스가 처음으로 Slack·카카오로 HTTP를 낸다(보내는 스레드에서만).
- [사실] 주문 출구·Guard·블랙리스트·주문 큐·시세 게이트웨이 코드는 diff에 없다(`git diff origin/main...HEAD --stat`).
- [사실] 보호 경로 `autotrade-app-engine/*`·`autotrade-app-watchdog/*`·`autotrade-config/*`·`autotrade-notification-jar/*`(scripts/ci/protected-paths.txt)를 바꾸므로 실전 관련 PR이다. 보호 테스트(`autotrade-architecture-test` 전체, RealTradingGuard·GuardedOrderGateway 테스트) 파일은 diff에 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `startGuard`는 그대로 `new RealTradingGuard(accountRefs, clock, ...)`이고 알림 큐는 Guard 다음에 만든다(EngineMain.java:92-94, 139-147). 알림 설정 값은 Guard에 닿지 않는다(`setup`은 `notification.*`만 옮김, EngineMain.java:129-134). 엔진 시작 알림 문구 "RealTradingGuard 모두 OFF"는 기동 직후 사실과 같다. `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`·`#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다`·`#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 재실행 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 규칙 diff 없음. `NotifyingCommandAuditLog`는 감사 기록 뒤 큐에 넣기만 하고 Guard·주문 타입을 참조하지 않는다(NotifyingCommandAuditLog.java:1-33). `:autotrade-architecture-test:test` 44건(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest 포함) `--rerun-tasks` 통과. 새 운영 코드의 JDK 타입(`Thread$Builder$OfPlatform`, `java.util.concurrent`, `java.nio.file.attribute`, `MessageDigest`)은 기존 허용 목록 안이다(RealPathReflectionTest.java:271-303, 고정 값 변경 없음) |
| A3 | 통과 | 증권사 주문 연동 코드 변경 없음. 새 HTTP 대상은 Slack Webhook과 카카오 상수 URL뿐이고 보내는 스레드에서만 부른다(NotificationSetup.java:72-80, NotificationQueue.java:187-205) |
| A4 | 해당 없음 | 주문 타임아웃 처리 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음. 알림 경로는 주문을 만들 수 없다(ArchitectureTest `REUSABLE_JARS_ARE_DOMAIN_FREE` 통과) |
| A7 | 통과 | 주문 큐·토큰 버킷·킬스위치 코드 변경 없음. 엔진 명령 스레드가 알림 때문에 막히지 않는다: `offer`는 `synchronized accept` 안에서 `ArrayBlockingQueue.offer`(막지 않음)만 하고 HTTP는 그 잠금 밖 보내는 스레드에서 돈다(NotificationQueue.java:109-155, 187-205). `offer`는 `Throwable`을 삼킨다(:109-114). 시험 `NotificationQueueTest#채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`·`#채널이_Error를_던져도_넣는_쪽으로_번지지_않고_다음_알림은_보낸다`·`NotifyingCommandAuditLogTest#감사_로그가_실패해도_알림은_넣고_실패는_그대로_올린다` 통과. 기존 감사 로그 실패 처리(EngineCommandHandler.java:90-96)는 그대로다 |
| A8 | 통과 | 실전 엔진은 알림 채널의 지연·`Error`에 묶이지 않는다(A7 근거). 알림 설정이 틀려도 그 채널만 끄고 기동한다(NotificationSetup.java:82-111, `NotificationSetupTest` 통과). 실험실·게이트웨이 코드와의 새 의존 없음(엔진 `forbiddenProjects` 그대로, autotrade-app-engine/build.gradle.kts:14). 감시는 알림을 넣기만 하고 실패를 삼켜 점검이 계속된다(Watchdog.java:161-167, `WatchdogTest#알림이_던져도_점검은_계속한다`). 5.2 정지 순서 코드(정지 요청 파일 만들기·풀기)는 바뀌지 않았고 알림만 덧붙었다(Watchdog.java:121-135, `WatchdogTest#정지_요청이_켜지면_긴급_꺼지면_정보_알림을_넣는다`). 단, 실험실·게이트웨이의 장애가 **감시의 엔진 멈춤 알림**을 밀어낼 수 있는 문제는 R1로 따로 적는다(엔진의 매매 동작 자체에는 영향 없음) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 시험 값은 가짜(`NEW_WEBHOOK` 자리표시자, `KIS-KR-1` 별칭 등). 새 비밀값 키 3개는 `Secret` 타입이고 비밀값 경로 고정 시험에 추가됐다(RuntimeConfig.java:91-103, ConfigLoaderTest.java:438-442). 설정 화면은 쓰기 전용으로 받고 응답·`runtime-config.json`에 평문이 없다(`SettingsControllerTest#카카오_나에게_보내기는_갱신_키와_링크가_있어야_켤_수_있고_새_키는_쓰기_전용이다`). 토큰 파일은 출처를 SHA-256으로만 적고, 값은 로그·`toString`에 없다(FileKakaoTokenStore.java:55, 68, 129-131, `FileKakaoTokenStoreTest`). 설정 오류 로그에 값이 없다(NotificationSetup.java:89, 109). 명령 알림 대상은 계좌 별칭 또는 고정 문구(EngineCommandHandler.java:339-344)이고 비밀번호·확인 문구를 받지 않는다. 설정 경고 알림 문구는 `ConfigJson.describe`로 값 없이 만든 경고다(ConfigLoader.java:180-182, 195-201). gitleaks CI success. 참고: 토큰 파일 권한 제한은 POSIX에서만이다(FileKakaoTokenStore.java:78-80, R4) |
| A11 | 통과 | 보호 테스트 파일 diff 없음(`autotrade-architecture-test`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`), `@Disabled` 추가 없음. 고정 값 변경은 비밀값 경로 목록(ConfigLoaderTest.java:438-442)뿐이고 대상 코드(`RuntimeConfig.Kakao`에 `Secret` 필드 3개)를 검토했다. `RealPathReflectionTest.RECORD_READER_FINGERPRINTS`는 바뀌지 않았고 시험 통과(D-100 5번 주장과 일치). 바뀐 시험 `NotifierTest` 중단 시험은 기대를 넓혔다(위 "직접 실행한 것"). 새 동작마다 실패 경우를 포함한 시험이 있다(D-100 "검증" 목록의 시험 이름 모두 존재·통과 확인). 다만 R1의 실패 경우(전체 상한이 긴급 알림을 버림)와 R2의 운영 배선에는 시험이 없다 |
| A12 | 통과 | 장애표 #8(엔진 크래시)·#9·#10의 감지(하트비트 판정·ERROR 로그·정지 요청 파일)는 바뀌지 않고 알림이 더해졌다(HeartbeatWatch.java:92-126, Watchdog.java:105-167). 시계 역행 처리 변경(HeartbeatWatch.java:115-116, Watchdog.java:215-216)은 경고를 늦추지 않는 쪽이다(`WatchdogTest#시계가_뒤로_가도_반복_경고가_밀리지_않는다`·`#하트비트를_하나도_못_본_경고도_시계가_뒤로_가면_바로_되풀이한다`). 장애 테스트 8종의 전제(주문·대사·계좌 격리)에 닿는 코드 없음. O1(야간 긴급 Slack)은 이 큐가 첫 구현이며 R1이 그 전제를 약하게 만든다. 장애표 #10 문구 "감시 상태를 화면·알림으로 내는 일은 후속"이 이제 일부 낡았다(R5, 권고) |
| A13 | 통과 | 시장 분기 없음. 알림 시각은 표시용 Asia/Seoul이고 계산은 주입 `Clock`의 `Instant`(NotificationSetup.java:28, Notification.java:46-50(표시만), NotificationQueue.java:121). 억제 창도 `Instant` 비교다 |
| A14 | 통과 | D-100 결정 1~9가 코드와 일치한다(큐 64·창 20·10분·닫기 3초: NotificationQueue.java:38-44, 233-261; 매핑 14종: EventRoutes.java; 카카오 키 검증: RuntimeConfig.java:97-103; 거절 기억: KakaoMemoChannel.java:114-117, 143-162, 193-195; `expires_in` 상한: KakaoMemoChannel.java:55, 203-206). 결정 로그 README 행, 기획서 MON-02·MON-06 행과 v14.44 이력, config-keys.md 표가 갱신됐다. D-100 "위험과 한계"의 "로컬 빌드 못 함"은 사실이었고 그 결과 첫 커밋 시험 실패가 있었으나 918f2e4에서 고쳐졌다 |

## 점검표 밖 발견

### R1(수정 필요): 전체 상한(10분 20건)이 감시의 엔진 멈춤 긴급 알림을 버린다
- [사실] `NotificationQueue.accept`는 등급과 상관없이 창 안 20건이 차면 새 알림을 버리고 세기만 한다(NotificationQueue.java:130-138). 버린 건수는 "다음에 받는 알림" 본문에 붙을 뿐이다(:143-145).
- [사실] 같은 대상의 억제는 "마지막 종류" 하나만 기억하므로(:54-55, 65, 124-128) 멈춤 → 회복 → 멈춤이 오가면 매번 받는다. 감시는 실험실·게이트웨이 하트비트가 멈춤·회복을 오갈 때마다 알림을 넣는다(Watchdog.java:139-158).
- [사실] 감시는 상태가 **바뀔 때만** 알린다. 엔진이 멈춘 채로 있으면 `process-stale`은 다시 나오지 않는다(HeartbeatWatch `Change`, Watchdog.java:110-113).
- [사실·재현] 위 탐침: 실험실 멈춤·회복 10번(20건) 뒤 엔진 `process-stale`이 `offer=false`로 버려지고 외부 채널로 가지 않았다.
- 영향: 자원 부족(5.2) 때 가장 먼저 흔들리는 프로세스가 실험실·게이트웨이인데, 그 소음이 10분 창을 채우면 장애표 #8(실전 엔진 크래시)의 알림이 Slack·카카오로 나가지 않는다. 버린 건수도 다음 알림이 올 때까지(최대 10분 이상, 안 올 수도 있음) 안 보인다. 남는 것은 감시 로그뿐이다. 이는 절대 규칙 6("실전은 다른 기능에 영향받지 않는다")의 취지와 O1(야간 무인 긴급 알림)의 전제를 약하게 만든다. D-100 후속 1번대로 7단계에 손절·한도 알림이 같은 엔진 큐로 들어오면, 로컬 명령 채널의 반복 실패(명령·대상·결과 조합마다 1건) 같은 소음이 손절 알림을 같은 방식으로 밀어낼 수 있다.
- 고칠 것(추천): 긴급 등급은 전체 상한에서 빼거나(같은 대상·종류 10분 억제만 적용), 최소한 긴급 몫을 따로 둔다. 그리고 "실험실 멈춤·회복이 창을 채운 뒤에도 엔진 `process-stale`(긴급)은 받는다"는 실패해야 하는 시험을 `NotificationQueueTest`(또는 `WatchdogTest`에 실제 큐를 붙여)로 추가한다. D-100 결정 1의 상한 문구와 "위험과 한계"를 함께 고친다. 대안: 정보 등급만 상한을 두는 안(추천, 단순), 긴급 몫 예약(복잡, 긴급 폭주 자체는 대상·종류 억제가 막음)

### R2(권고): 운영 배선 시험
- `EngineMain.start`가 `NotifyingCommandAuditLog`로 감싸는지(EngineMain.java:96-97), `WatchdogMain.start`가 `notifications::offer`를 넘기는지(WatchdogMain.java:95)를 보는 시험이 없다. 두 줄을 `CommandAuditLog.LOGGING`·`Alerts.NONE`으로 되돌려도 지금 시험은 모두 통과한다고 본다([추론], 해당 시험 검색 결과 없음). `EngineMainTest`에 명령 하나를 보내 알림 채널(시험용 주입)로 `real-trading-command`가 나오는지 보는 시험을 권한다.

### R3(권고): 기존 설정의 이행 위험
- [사실] `notification.kakao.enabled=true`·`mode=ME`이고 새 키가 없는 기존 설정은 이제 결합 검증에 실패하고, 로더는 `runtime-config.json` 전체를 빼고 기동한다(ConfigLoader.java:101-106, RuntimeConfig.java:97-103). 그 파일의 `real-trading.accounts`·`market-data.*`·`notification.slack.*`도 함께 빠진다. 실전 쪽으로는 계좌 0개로 뜨는 안전한 쪽 실패이고 경고가 상태 조회에 실리지만, Slack 설정까지 빠져 설정 경고 알림이 로그로만 간다. config-keys.md나 D-100에 "업그레이드 전 카카오를 끄거나 새 키를 넣는다" 한 줄을 권한다.

### R4(권고): Windows에서 토큰 파일 권한
- 사용자 PC는 Windows다. `kakao-token.json`의 소유자 전용 권한은 POSIX에서만 걸린다(FileKakaoTokenStore.java:78-80). Windows에서는 설정 폴더 ACL을 따르므로 `secrets.json`과 같은 수준이다. D-100 "위험과 한계"에 명시하고 7단계 시험 절차서의 사용자 PC 점검(설정 폴더 ACL)에 넣기를 권한다.

### R5(권고): 장애 대응표 문구
- broker-and-fault-design.md 6장 #8 감지 칸은 "ERROR 로그"까지만, #10 칸은 "감시 상태를 화면·알림으로 내는 일은 후속"이다. 이 PR로 #8·#9·#10에 긴급 알림이 붙었으므로 표에 D-100 링크를 더하길 권한다(대응 동작 변경은 없음).

### 참고(조치 불요)
- 엔진 재기동 알림은 같은 대상·종류라 10분 안 두 번째는 억제된다(D-100이 받아들인 억제 규칙). 생략 건수는 다음 같은 알림에 붙는다.
- 보내는 스레드 생성 실패(`OutOfMemoryError: unable to create native thread`)는 엔진 기동 실패로 이어진다(NotificationQueue.java:91, EngineMain.java:94). Guard는 OFF이고 주문 경로가 아직 없어 계좌 위험은 없다. 7단계에서 기동 순서를 정할 때 알림 실패가 보유 종목 보호 기동을 막지 않게 하는지 다시 본다.

## 판정
수정 필요(R1: 전체 상한이 긴급 알림, 특히 감시의 엔진 멈춤 알림을 버림. 긴급을 상한에서 빼거나 몫을 두고 실패해야 하는 시험 추가)

## 재검토 2026-10-16
- 대상: PR #128 / 브랜치 `claude/project-thread-vtmvk8` / 커밋 91e404a / main(5b46d17) 대비 diff `git diff origin/main...HEAD` 전체. 앞 검토(918f2e4) 뒤로 바뀐 것은 f085eac(architect F1·F2·F3, R3·R4 반영)와 91e404a(R5)다. 이 둘은 `git diff 918f2e4 HEAD`로 따로 다시 읽었다(코드 7개 파일, 문서 5개 파일).
- 점검표: A
- 직접 실행한 것:
  - `./gradlew clean build --continue`(Maven Central 대신 Google 미러를 쓰는 init 스크립트, 저장소 밖) → **BUILD SUCCESSFUL**(161 tasks). JUnit XML 집계(테스트/실패/오류/건너뜀): risk 55/0/0/0, architecture-test 44/0/0/0, app-engine 54/0/0/0, app-watchdog 51/0/0/0, notification-jar 69/0/0/0(`--rerun-tasks`로 다시 돌림), config 40/0/0/0, app-lab 214/0/0/0, app-gateway 50/0/0/0.
  - CI: 91e404a의 `Secret scan and collector boundary`·`DB schema`는 success, `Gradle build`는 **cancelled**(이어진 푸시로 취소된 것으로 보임 `[추론]`). 판정은 위 로컬 빌드로 한다.
  - R1 탐침을 다시 돌렸다(저장소에 남기지 않음, 실행 뒤 지움). 실험실 `process-stale`·`process-recovered`를 10초 간격으로 30번씩 번갈아 넣은 뒤 엔진 `process-stale`을 넣었다. 결과: `offer=true`, 외부 채널로 "engine 멈춤"이 나감. 외부로 나간 알림은 모두 `MAX_PER_WINDOW + 2`건 이하였다. 같은 시험이 918f2e4에서는 버려졌었다.

### 앞 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| R1 전체 상한이 긴급을 버림 | 해결 | NotificationQueue.java:136 `windowCount >= MAX_PER_WINDOW && severityOf(kind) == INFO`일 때만 버린다. 긴급도 창 건수에는 더한다. 정보 알림이 상한으로 버려지면 `lastBySubject`가 바뀌지 않는다. 그래서 실험실이 멈춤↔회복을 오가도 회복이 버려진 뒤의 멈춤은 같은 대상·종류로 억제되고, 큐가 긴급으로 넘치지 않는다(탐침에서 외부 건수 상한 확인). 실패해야 하는 시험이 추가됐다: `NotificationQueueTest#긴급_알림은_전체_상한을_넘어도_받고_상한으로_버린_정보_건수를_붙인다`, `#정보_알림만_전체_상한으로_버리고_버린_건수는_다음에_받는_알림에_붙는다`. 큐가 차면 긴급도 버린다는 기존 시험은 기대가 `CAPACITY`까지로 바뀌었다(`#채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`). 넣는 쪽이 1초 안에 돌아온다는 단언은 그대로다. D-100 결정 1과 클래스 Javadoc 문구가 코드와 같다 |
| R2 운영 배선 시험 | 미반영(권고 유지) | `EngineMain`의 `NotifyingCommandAuditLog` 연결과 `WatchdogMain`의 `notifications::offer` 연결을 보는 시험은 여전히 없다. 차단 사유는 아니다(아래 판정) |
| R3 기존 설정 이행 | 해결(더 나은 방식) | `RuntimeConfig.Kakao`가 더는 거부하지 않는다. 대신 `meReady()`로 판정하므로(RuntimeConfig.java:104-107) 예전 파일도 실전 계좌 등 다른 설정과 함께 그대로 읽힌다(`ConfigLoaderTest#갱신_키_없이_카카오를_켠_예전_파일도_받아들인다`). 키가 모자라면 엔진·감시가 카카오 채널만 끈다. 근거: `KakaoMemoChannel` 생성자가 빈 REST 키·틀린 링크를 거부하고, `NotificationSetup.kakao`가 이를 잡아 ERROR 로그를 남긴다(NotificationSetup.java:94-111, `NotificationSetupTest#틀린_채널_설정은_던지지_않고_그_채널만_끈다`의 빈 refresh-token 경우). 설정 화면은 카카오 값을 고칠 때만 거부한다(SettingsController.java:203-210, `SettingsDiskGuardTest#갱신_키_없이_카카오를_켠_예전_파일이어도_다른_항목은_저장하고_카카오를_고칠_때만_거부한다`) |
| R4 Windows 토큰 파일 권한 | 해결(문서) | D-100 "위험과 한계"에 한 줄 추가. 7단계 절차서 반영은 후속 |
| R5 장애표 문구 | 해결 | broker-and-fault-design.md 6장 #8·#10에 D-100 알림이 추가됐다. 대응 동작은 바뀌지 않았다 |
| 기타 f085eac | 확인 | `WatchdogMain.start`가 `watchdog.start()` 실패 때 감시를 닫아 알림 큐까지 닫는다(WatchdogMain.java:103-109). ADR-001·ADR-005·config-keys.md 문구가 코드와 일치한다 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 바뀐 부분이 Guard·`startGuard`(EngineMain.java:139-147)에 닿지 않는다. `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`·`#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과. F3 때문에 카카오 키가 모자란 파일이 받아들여져도 Guard 상태 키는 여전히 없다(CFG-02 그대로) |
| A2 | 통과 | 주문 출구·ArchUnit 규칙 diff 없음. architecture-test 44건 통과, 고정 값 변경 없음 |
| A3 | 통과 | 증권사 주문 연동 변경 없음. 알림 HTTP는 보내는 스레드에서만(앞 검토와 같음) |
| A4 | 해당 없음 | 주문 타임아웃 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 통과 | `offer`는 막지 않고 던지지 않는다(NotificationQueue.java:109-114). 상한 조건 변경으로 잠금 안 작업이 늘지 않았다(`routes.severityOf` 표 조회 하나). 엔진 명령 처리와 감사 로그 실패 처리는 그대로다(EngineCommandHandler.java:90-96). `NotifyingCommandAuditLogTest` 통과 |
| A8 | 통과 | 앞 검토 근거 그대로이고, 감시의 엔진 멈춤 알림이 실험실·게이트웨이 소음에 밀리던 문제(R1)가 풀렸다(탐침, 위 시험) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 시험 값은 자리표시자다(`PLACEHOLDER-T`, `FAKE_SECRET`). `ConfigLoaderTest`의 새 시험은 경고가 비었는지 본다. 설정 화면 거부 문구에는 키 이름만 있다(SettingsController.java:209). gitleaks CI success |
| A11 | 통과 | 보호 테스트 파일 diff 없음. `@Disabled` 없음. `NotificationQueueTest`에서 바뀐 기대 두 곳(상한 대상이 정보로 좁혀짐)은 동작 변경을 따른 것이고, 긴급이 상한을 넘어도 받는다는 반대쪽 시험이 추가됐다. `ConfigLoaderTest`의 비밀값 경로 고정 목록은 앞 검토 그대로다. R2 배선 시험이 없는 것은 남은 권고다 |
| A12 | 통과 | 장애표 #8·#10 감지 칸에 알림이 추가됐을 뿐이고 대응·장애 테스트 8종의 전제는 바뀌지 않았다. O1(야간 긴급 알림) 전제를 약하게 만들던 R1이 풀렸다 |
| A13 | 통과 | 시장 분기 없음. 시각 계산은 주입 `Clock`의 `Instant` |
| A14 | 통과 | D-100 결정 1·5, "위험과 한계", "검증", ADR-001·ADR-005·config-keys.md, broker-and-fault-design.md 6장이 코드와 일치한다 |

### 점검표 밖 발견(재검토)
- R2(권고, 유지): 운영 배선 시험. 차단하지 않는 이유는 둘이다. 배선이 빠지면 알림이 덜 나갈 뿐 주문·Guard에는 닿지 않는다. 또 각 부품(`NotifyingCommandAuditLog`, `Watchdog`에 주입한 `Alerts`)은 시험으로 고정돼 있다. 7단계 주문 경로 알림을 이을 때 `EngineMainTest`에 넣기를 권한다.
- R6(참고): 긴급은 이제 큐 크기(64)까지 받는다. 채널이 멈춰 한 건에 최대 약 40초(Slack 10초 + 카카오 갱신 포함 약 30초)가 걸리면, 뒤에 들어온 엔진 멈춤 알림이 앞 긴급들 뒤에서 늦게 나갈 수 있다. 감시의 긴급 원천은 대상·종류 억제로 묶여 있어 실제 대기열은 짧다고 본다(`[추론]`). 6단계 주간 점검에서 `pending()`·생략 건수를 보길 권한다(D-100 후속 4번과 같이).

## 판정(재검토 2026-10-16)
승인

## 재검토 2026-10-16 (2차)
- 대상: PR #128 / 브랜치 `claude/project-thread-vtmvk8` / 커밋 90497aa / main 대비 diff `git diff origin/main...HEAD` 전체. 앞 재검토(91e404a, 원문 커밋 aab2ca6) 뒤로 바뀐 것은 세 커밋이다: 95163d5(QA 대기 목록), 49a0d2e(독립 QA 보고서 `docs/reviews/pr-128/qa.md`, 수정 필요 F1·권고 Q1), 90497aa(F1·Q1 반영: NotificationQueue.java, Watchdog.java, NotificationQueueTest·EngineMainTest·WatchdogMainTest, D-100). `git diff aab2ca6 HEAD`로 따로 다시 읽었다.
- 점검표: A
- 직접 실행한 것:
  - `./gradlew clean build --continue`(Maven Central 대신 Google 미러를 쓰는 init 스크립트, 저장소 밖) → **BUILD SUCCESSFUL**(161 tasks). 이어서 `:autotrade-notification-jar:test :autotrade-app-engine:test :autotrade-app-watchdog:test --rerun-tasks` → BUILD SUCCESSFUL.
  - JUnit XML 집계(테스트/실패/오류/건너뜀): risk 55/0/0/0, architecture-test 44/0/0/0, app-engine 54/0/0/0, app-watchdog 52/0/0/0, notification-jar 71/0/0/0, config 40/0/0/0, app-lab 214/0/0/0, app-gateway 50/0/0/0.
  - 변이 시험(실행 뒤 원복, 커밋하지 않음): `NotificationQueue.drop`에서 `lastBySubject.put(key, new Last(kind, null, 0))` 한 줄을 지웠다. 그러자 `NotificationQueueTest#상한으로_회복_알림을_버려도_다시_멈춤은_억제하지_않는다`와 `#큐가_차서_회복_알림을_버려도_다시_멈춤은_억제하지_않는다`가 실패했다(71건 중 2건). 새 시험이 F1을 실제로 잡는다.
  - CI: 90497aa의 `Secret scan and collector boundary`·`DB schema`는 success, `Gradle build`는 **cancelled**(앞 재검토 때와 같다). 판정은 로컬 빌드로 하되, 머지 전에 CI `Gradle build` 성공을 다시 확인해야 한다.

### 앞 재검토 정정(검토자 오류)
- 앞 재검토의 R1 근거에서 이렇게 적었다: "정보 알림이 상한으로 버려지면 `lastBySubject`가 바뀌지 않으므로 … 멈춤은 같은 대상·종류로 억제되고, 큐가 긴급으로 넘치지 않는다." 이것은 **장점이 아니라 결함**이었다. 같은 동작 때문에 엔진 멈춤(보냄) → 회복(버림) → 10분 안 다시 멈춤에서 두 번째 엔진 멈춤 긴급 알림도 억제된다. 독립 QA가 F1(탐침 P1·P2)로 잡았다. 앞 재검토의 승인은 이 점에서 틀렸다.

### 앞 지적과 QA 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| QA F1(버린 알림 뒤 같은 대상의 긴급 알림 억제) | 해결 | 버릴 때(전체 상한·큐 가득 모두) `drop`이 그 대상의 마지막 종류를 시각 없이(`at=null`) 기록한다(NotificationQueue.java:165-170). 억제는 `at`이 있을 때만 건다(:133). 그래서 멈춤 → 회복(버림) → 멈춤에서 두 번째 멈춤은 억제되지 않는다. P1·P2 시험이 추가됐고 변이로 효과를 확인했다(위). D-100 결정 1번 문구가 코드와 일치한다 |
| QA Q1 / risk-ops R2(운영 배선 시험) | 해결 | `EngineMainTest#실제_배선으로_활성화하면_증권사_연동_전이라_첫_단계에서_OFF로_돌아간다`가 실제 기동한 엔진 큐의 `engine-started` 1건과 `real-trading-command` 1건 이상을 본다(EngineMainTest.java:231-232). `WatchdogMainTest#운영_배선으로_감시가_본_엔진_멈춤을_알림_큐에_넣는다`가 실제 기동한 감시가 멈춘 엔진 하트비트로 `process-stale`을 넣는지 본다(WatchdogMainTest.java:62-77). 셈은 `NotificationQueue.acceptedCount`(:233-235)로 한다. 운영 코드에 더해진 것은 읽기 전용 셈 하나와 감시의 패키지 내부 접근자(Watchdog.java:287-290)뿐이고, 동작은 바뀌지 않는다 |
| R6(긴급이 큐 크기까지 받음) | 참고 유지, 재평가 | F1 수정 뒤에는 실험실이 멈춤↔회복을 오가면 회복이 상한으로 버려져도 다음 멈춤(긴급)이 매번 받아진다. 다만 그 빈도는 하트비트 기준(멈춤 판정 15초, 점검 5초)으로 약 20초에 한 번이 상한이다. 채널이 정상이면 보내는 속도가 이보다 빠르다. 큐(64)가 이 소음으로 차려면 외부 채널이 오래 멈춰 있어야 하는데, 그때는 엔진 멈춤 알림도 어차피 밖으로 못 나간다 `[추론]`. 큐가 차서 버린 긴급도 이제 억제 상태를 남기지 않으므로, 다음 상태 변화는 다시 받는다. 6단계 주간 점검에서 `pending()`·`droppedTotal()`을 보라는 권고는 그대로 둔다 |

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | 바뀐 부분이 Guard에 닿지 않는다. `RealTradingGuardTest#생성_직후_모든_계좌는_OFF다`·`#재기동하면_열려_있던_계좌도_OFF로_시작한다`, `EngineMainTest#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 통과. 새 배선 시험도 활성화 시도 뒤 Guard가 OFF로 돌아오는지 함께 본다(EngineMainTest.java:229) |
| A2 | 통과 | 주문 출구·ArchUnit 규칙·보호 테스트 diff 없음. architecture-test 44건 통과. 새 공개 메서드 `acceptedCount`는 허용 목록 안 타입(`HashMap`)만 쓴다 |
| A3 | 통과 | 증권사 주문 연동 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 코드 변경 없음 |
| A7 | 통과 | `offer`는 여전히 막지 않고 던지지 않는다(NotificationQueue.java:109-114). `drop`·`acceptedByKind.merge`는 같은 잠금 안의 맵 갱신 하나씩이다. `NotificationQueueTest#채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`(1초 안에 돌아옴) 통과 |
| A8 | 통과 | 감시의 엔진 멈춤 알림이 실험실 소음(전체 상한)과 버려진 회복(F1) 어느 쪽으로도 묻히지 않는다(P1·P2 시험, 앞 재검토 탐침). 엔진 매매 동작에는 영향 없음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 시험은 가짜 값과 하트비트 파일만 쓴다. 비밀값 관련 코드 변경 없음. gitleaks CI success |
| A11 | 통과 | 보호 테스트 diff 없음, `@Disabled` 없음, 기존 시험 기대값 변경 없음(추가만). 새 동작(F1 수정)에 실패해야 하는 시험 2건이 있고 변이로 확인했다. 배선 시험 2건이 추가됐다 |
| A12 | 통과 | 장애표 #8 문구 "멈춤·재기동은 긴급 알림"이 F1 경로에서도 지켜진다(P1·P2). 장애 테스트 8종의 전제에 닿는 코드 없음. O1 전제가 강해졌다 |
| A13 | 통과 | 시장 분기 없음. 억제 판정은 주입 `Clock`의 `Instant` |
| A14 | 통과 | D-100 결정 1번(버린 알림도 마지막 종류로 남김)과 "검증"(P1·P2, 운영 배선)이 코드·시험과 일치한다. QA 보고서 F1이 지적한 문서·코드 불일치가 풀렸다 |

### 점검표 밖 발견(2차 재검토)
- 없음(차단). 참고 하나: `drop`은 그 대상의 이전 생략 건수(`suppressed`)를 0으로 되돌린다. 그래서 같은 대상·종류로 억제된 건수가 버림 한 번 뒤에는 다음 알림 본문에 붙지 않는다. 버린 건수는 `dropped`로 따로 붙으므로 사람이 "생략이 있었다"는 사실은 본다. 조치 불요.

## 판정(재검토 2026-10-16, 2차)
승인
