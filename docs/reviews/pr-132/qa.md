# 독립 QA 검토: 알림 설정을 이름 있는 빌더로, 시간대·재동의 문구는 부르는 쪽이 (PR #128 후속)
- 대상: PR #132 / 헤드 10f66fa (브랜치 claude/project-thread-7fa1t2) / main 81b5285 대비 전체 diff(`git diff origin/main...10f66fa`, 14개 파일 +529 −40). 헤드가 현재 main을 포함한다(`git merge-base --is-ancestor origin/main HEAD` 참).
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --continue -I <검토자 임시 폴더>/mirror.init.gradle --refresh-dependencies --rerun-tasks` → `BUILD SUCCESSFUL in 4m 8s`, 종료 코드 0, 138 tasks executed. 시험 결과 XML 집계 1,445건 / 실패 0 / 오류 0 / 건너뜀 0. 모듈별: `autotrade-notification-jar` 77, `autotrade-app-engine` 55, `autotrade-app-watchdog` 53, `autotrade-config` 40, `autotrade-architecture-test` 44 전부 통과.
    - 빌드 환경 메모: 미러 없는 빌드는 `Could not HEAD 'https://repo.maven.apache.org/...kotlin-stdlib-2.0.21.pom'. Received status code 429`로 실패했다(이 컨테이너에서 Maven Central 차단, 코드 실패 아님). 사용자 허락(2026-10-16)을 받아 저장소 파일은 건드리지 않고 검토자 임시 폴더의 init 스크립트로 공개 미러(`maven-central.storage-download.googleapis.com/maven2`)를 앞에 두었다. 빌드 스크립트·설정 변경 없음.
  - PR CI(헤드 10f66fa): `Gradle build`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary`, `Lab screens (Playwright)` 모두 success(GitHub check runs 조회).
  - 변이 M1~M16: 운영 코드를 한 곳씩 고치고 해당 모듈 `test`를 돌린 뒤 매번 `git checkout`으로 되돌림. 끝난 뒤 `git status --short` 출력 없음.

    | 변이 | 결과 |
    |---|---|
    | M1 `EngineMain.setup` REST API 키·client secret 자리 바꿈 | 잡힘 |
    | M2 `WatchdogMain.setup` 액세스·리프레시 토큰 자리 바꿈 | 잡힘 |
    | M3 `WatchdogMain.setup`에서 `kakaoReconsentHelp(...)` 삭제 | 잡힘 |
    | M4 `EngineMain.setup` 표시 시간대를 UTC로 | 잡힘 |
    | M5 `EngineMain.setup`에서 `kakaoLinkUrl(...)` 삭제 | 잡힘 |
    | M6 `WatchdogMain.setup` `slackEnabled(false)` 고정 | 잡힘 |
    | M7 `NotificationSetup.start`가 기본 재동의 문구를 넘김 | 잡힘 |
    | M8 `NotificationSetup.start`가 `Asia/Seoul` 고정 | 잡힘 |
    | M9 `NotificationQueue.deliver`가 필드 대신 기본 문구 사용 | 잡힘 |
    | M10 `NotificationQueue` `last.at() != null` 삭제(PR #128 QA Q1의 M17) | 잡힘 |
    | M11 빌더가 빈 재동의 문구를 기본 문구로 바꾸지 않음 | 잡힘 |
    | M12 `equals`에서 리프레시 토큰 비교 삭제 | 잡힘 |
    | M13 `equals`에서 link-url 비교 삭제 | 잡힘 |
    | M14 `equals`에서 유료 모드 비교 삭제 | 잡힘 |
    | M15 `RuntimeConfig.Notification.KAKAO_RECONSENT_HELP`에서 " 실전 엔진·감시를 다시 시작하세요." 삭제 | **살아남음** |
    | M16 `EngineMain.setup` BIZ 판정 뒤집음(`!=`) | 잡힘 |
  - 탐침 시험 P1·P3: 임시 시험 클래스 `QaProbe132Test`를 `autotrade-notification-jar` 시험 폴더에 넣어 `:autotrade-notification-jar:test --tests '*QaProbe132Test'` 실행(통과) 뒤 지움(커밋 안 함). 결과는 아래 "시나리오".
  - 사용자 문구 동일성: main의 `NotificationQueue` 재동의 문자열 조각과 PR의 `RuntimeConfig.Notification.KAKAO_RECONSENT_HELP` 조각을 이어 붙여 비교 → 완전히 같음(`True`). 표시 시간대 값도 main `NotificationSetup.DISPLAY_ZONE`과 같은 `ZoneId.of("Asia/Seoul")`.

## 이번 PR이 실전 경로에 닿는 곳
- 실전 엔진(`EngineMain.setup`, EngineMain.java:129-144)·감시(`WatchdogMain.setup`, WatchdogMain.java:126-141): 같은 설정 값을 위치 인자 생성자 대신 이름 있는 빌더로 옮기고, 표시 시간대·재동의 문구를 `RuntimeConfig.Notification` 상수로 넘긴다. 호출 지점(`startNotifications`, EngineMain.java:118, WatchdogMain.java:115)과 Guard 기동 순서는 바뀌지 않았다.
- 알림 모듈: `NotificationSetup` 공개 생성자·`logOnly()`·`DISPLAY_ZONE` 삭제, 빌더 추가, `equals`/`hashCode` 추가(시험용, 운영 코드 사용처 없음). `NotificationQueue`에 재동의 문구 인자(생성자 2개 추가, 기존 5인자 생성자는 일반 문구로 위임). 억제·큐·발송 로직 diff 없음.
- 설정 모듈: `RuntimeConfig.Notification`에 `public static final` 상수 2개(`ZoneId`, `String`). 설정 키·JSON 형식 변경 없음(레코드 구성 요소 그대로).
- 보호 테스트(`autotrade-architecture-test`, `autotrade-risk`, `RealTradingGuardTest`, `GuardedOrderGatewayTest`)·`scripts/`·`.github/` diff 없음. 바뀐 파일 중 `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-config`·`autotrade-notification-jar`가 보호 경로(scripts/ci/protected-paths.txt:11·16 등)라 실전 관련 PR이다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` diff 없음. `EngineMain.startGuard`·엔진 시작 알림 문구("RealTradingGuard 모두 OFF", EngineMain.java:103-104) 변경 없음. 설정에 Guard 상태를 넣는 변경 없음. 전체 빌드에서 `:autotrade-risk:test` 포함 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리 코드 diff 없음. `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 44건 통과, 규칙 파일 diff 없음. 새 운영 코드 타입은 `ZoneId`·`String`·`boolean`과 빌더 클래스뿐 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진·감시가 알림에 하는 일(`offer`만, 비차단)은 그대로다. 억제·큐 로직 diff 없음(NotificationQueue.java 변경은 문구 필드와 시험용 접근자뿐). 새 실패 지점은 `displayZone` 누락 시 `build()`의 `NullPointerException`(NotificationSetup.java:56)인데, 엔진·감시는 상수 `DISPLAY_ZONE`을 넣으므로 운영에서 일어나지 않는다. 일어나도 기동 실패 쪽(주문 없음)이다 `[추론]` |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 상수 두 개에 비밀값 없음(시간대, 설정 키 이름이 든 안내 문구). `toString`은 그대로 `values=****`(`NotificationSetupTest#…toString…` 통과), `hashCode`는 비밀값을 쓰지 않음(NotificationSetup.java:214-216). `equals`는 비밀값을 비교하지만 운영 코드 사용처 없음(grep). 시험 픽스처는 자리표시자(`REST-PH`, `REFRESH-PH`, `SLACK-URL-PH`, `TPLACEHOLDER`). diff 추가 줄의 32자 이상 토큰 모양 검색은 문서 줄만. CI `Secret scan and collector boundary` success |
| A11 | 통과 | 보호 테스트 diff 없음, `@Disabled`·`assume` 추가 없음, 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 시험에서 바뀐 것은 `NotificationSetupTest`의 생성자·`logOnly()` 호출을 같은 값의 빌더로 옮긴 것과 `EngineMainTest` import 순서뿐이고 단언은 그대로다. 새 동작에 실패 경우 시험 있음(시간대 누락 던짐, 자리 바뀜 `isNotEqualTo`, 값 하나만 달라도 다름, 넘긴 문구로 발송, M17 순서 시험 둘). 변이 16개 중 15개 잡힘, M15는 권고 Q1 |
| A12 | 통과 | 장애 대응표·장애 테스트 8종의 전제(감지·정지 요청·주문·대사)에 닿는 코드 없음. 알림 문구와 시각 표시가 main과 같음을 직접 비교로 확인(위 "사용자 문구 동일성"), 탐침 P1에서 조립된 큐가 `+09:00` 시각과 넘긴 문구로 재동의 알림을 보냄 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. `DISPLAY_ZONE`은 알림 표시용이며 계산에 쓰지 않는다(Notification.java:18 주석, `plainText`) |
| A14 | 통과 | D-100 "반영 기록"(D-100…md:80-85)이 코드와 일치: 빌더로만 생성(공개 생성자 없음, grep `new NotificationSetup(` 운영·시험 0건), 엔진·감시 위치 시험 이름 일치, 상수 위치(`RuntimeConfig.Notification.DISPLAY_ZONE`·`KAKAO_RECONSENT_HELP`), 라이브러리 일반 문구(`NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP`에 `secrets.json`·`notification.kakao`·"실전" 없음, 시험으로 고정), M17 시험 이름 일치(변이 M10으로 실패 확인). 결정 내용(억제 규칙·채널·문구)이 바뀌지 않아 새 D 번호가 없는 것도 맞다. ADR-001 notification-jar 행 갱신 일치. 설정 키 변경이 없어 config-keys.md 갱신 불필요. 과금(지침 8장): 유료 경로는 여전히 `kakaoPaidMode`(BIZ)이면 카카오 채널을 만들지 않음(NotificationSetup.java `kakao(...)` 변경 없음, 변이 M16 잡힘), 기본값 변경 없음 → 과금 검사 통과 |
| B1 | 통과 | 위 직접 빌드 `BUILD SUCCESSFUL`, 1,445/0/0/0 |
| B6 | 통과 | 새 시험: `EngineMainTest`·`WatchdogMainTest#알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다`, `NotificationSetupTest` 3건, `NotificationQueueTest` 3건. 건너뜀 0, 지운 시험 없음. 빈틈은 사용자 문구 본문 자체(Q1) |

## 시나리오(잘못된 알림·누락·보호 공백 시도)
- P1 조립 경로 끝까지: 빌더(슬랙·카카오 켬, 리프레시 토큰만, 표시 시간대 `Asia/Seoul`, 재동의 문구 지정) → `start` → 정보 알림 1건. 카카오 갱신 400 `invalid_grant` → 슬랙으로 대체 발송 `[정보] 익절 (2026-10-16T09:00:00+09:00)` → 재동의 긴급 알림이 슬랙으로 `…(2026-10-16T09:00:00+09:00)\nQA-HELP …` 발송. 넘긴 문구와 시간대가 실제 발송 본문까지 가고 일반 문구는 나가지 않음. 결함 없음.
- P2 엔진·감시 설정 자리 바뀜(이 PR의 목적): 값 9개 중 문자열 값 6개를 서로 바꾸는 변이 M1·M2, 값 누락 M3·M5, 고정 M4·M6, 유료 판정 뒤집기 M16 모두 시험이 잡음. 리프레시 토큰이 액세스 토큰 자리로 가 카카오 갱신이 틀린 값으로 나가는 순서는 이제 머지 전에 막힌다.
- P3 빌더 재사용·null 입력: 빌드한 뒤 같은 빌더의 리프레시 토큰·시간대·문구를 바꿔도 먼저 만든 설정은 그대로(생성자가 값을 복사, NotificationSetup.java:45-58). `kakaoReconsentHelp(null)`·`slackWebhookUrl(null)`은 기본값과 같은 설정. 결함 없음.
- P4 (코드 읽기, `[추론]`) 표시 시간대 누락: 빌더에서 `displayZone`을 빠뜨리면 `build()`가 던져 `startNotifications`(Guard 기동 뒤, try 바깥)에서 엔진·감시 기동이 실패한다. 주문 쪽으로 위험하지 않은 방향이고 운영 호출부는 상수를 넣어 일어나지 않는다. 결함으로 세지 않음.
- P5 (코드 읽기, `[추론]`) 기존 공개 5인자 `NotificationQueue` 생성자는 일반 문구로 위임한다. 운영 호출부는 `NotificationSetup.start`(6인자) 한 곳뿐이라 지금은 영향 없다. 앞으로 누가 5인자로 큐를 만들면 이 시스템의 설정 키 안내 없이 일반 문구가 나간다 → 참고 R1.

## 점검표 밖 발견
- Q1(권고): 사용자에게 가는 재동의 안내 본문은 시험이 `notification.kakao.refresh-token`을 포함하는지만 본다(EngineMainTest·WatchdogMainTest 새 시험). 변이 M15(" 실전 엔진·감시를 다시 시작하세요." 삭제)가 살아남았다. PR 본문의 "사용자가 받는 알림 문구 그대로"는 이번에 QA가 문자열 직접 비교로 확인했지만 시험으로 고정되지는 않았다. 안내에서 "다시 시작" 단계가 빠지면 사람이 새 토큰을 넣고도 재기동하지 않아 카카오 알림이 계속 꺼진 채로 남을 수 있다. 다음 알림 PR에서 문구 전체 또는 "다시 시작"을 단언하는 시험을 권한다. 판정에 영향 없음.
- R1(참고): P5. 공개 5인자 생성자를 남길지, 남기면 문구를 받지 않는 생성자임을 문서에 적을지 다음 알림 PR에서 정하면 된다.
- 개발 쪽 검토 원문 확인: architect(docs/reviews/pr-132/architect.md:49-50) "승인", risk-ops(docs/reviews/pr-132/risk-ops.md:46-47) "승인". 두 원문의 권고 R1은 각각 143bda5·10f66fa에서 시험으로 반영됐고, QA가 같은 항목을 다시 확인했다(M12~M14 잡힘, 큐 가득 시험은 `CountDownLatch entered` 뒤 채움, NotificationQueueTest 새 시험).

## 판정
승인
