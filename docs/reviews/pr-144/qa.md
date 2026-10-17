# QA 검토: 알림 채널 이름 검사를 꾸리기 try 밖으로(PR #140 QA 시나리오 1·risk-ops K1)
- 대상: PR #144 / 헤드 커밋 f2c139bc020e7b0bcdf09599611bd13bc0484662(브랜치 `claude/project-thread-12sz8h`) / main 대비 diff(`git diff origin/main...pr-144`, 머지 기준 1352889, 파일 8개 +131 −13)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks --console=plain`(헤드 f2c139b, 새 컨테이너, 미러 init 스크립트 없이): BUILD SUCCESSFUL in 4m 40s, 종료 코드 0. `build/test-results/test/*.xml` 합계 1,492건, 실패 0·오류 0·건너뜀 0. `NotificationSetupTest` 15건 통과(새 시험 `#채널_이름이_어긋나는_코드_실수는_설정_오류로_바뀌지_않고_그대로_드러난다`, `#꾸린_채널_이름은_외부_채널_상수와_같다` 포함).
  - GitHub CI(헤드 f2c139b) 4개 모두 success: Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary, Lab screens (Playwright).
  - 변이 7개(별도 worktree, 각 변이 뒤 `git checkout -- .`로 원복, 작업 트리 깨끗함 확인). 실행 범위 `:autotrade-notification-jar:test :autotrade-app-engine:test :autotrade-app-watchdog:test :autotrade-app-lab:test :autotrade-architecture-test:test`:
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | M1 | `built`를 옛 모양(`ready(name, build.get())`를 try 안에)으로 | 잡힘: `NotificationSetupTest#채널_이름이_어긋나는_...` 1건 |
    | M2 | `KakaoMemoChannel.name()` → `"kakao-x"` | 잡힘: NotificationSetupTest·NotifierTest·NotificationTesterTest 14건 |
    | M3 | `SlackWebhookChannel.name()` → `"slack-x"` | 잡힘: 같은 세 클래스 14건 |
    | M4 | 카카오 실패 이유 `invalid-values` → `invalid-webhook-url` | 잡힘: 3건(NotificationSetupTest·NotificationTesterTest) |
    | M5 | Slack 실패 이유 `invalid-webhook-url` → `invalid-values` | 잡힘: 3건 |
    | M6 | `built` catch에서 이름을 늘 `SLACK`으로 | 잡힘: 2건 |
    | M7 | `built` catch를 `RuntimeException` → `IllegalArgumentException`으로 좁힘 | **생존**(아래 Q1) |
  - 탐침(worktree에 임시 시험 클래스, 실행 뒤 삭제): 설정 폴더에 Slack·카카오(ME) 값을 모두 넣고 `EngineMain.run(paths, clock)`, `WatchdogMain.start(paths, clock).close()` 호출.
    - 헤드 그대로: 둘 다 기동, 로그 `알림 채널: 긴급 [slack, kakao, log], 정보 [kakao, slack, log]`.
    - M2 적용: 둘 다 `java.lang.IllegalArgumentException: 외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x`를 던짐(채널을 조용히 끄지 않음).
  - `git diff bc0390e pr-144 -- docs/reviews/pr-144/risk-ops.md` 출력 없음: 되살린 risk-ops 원문이 원래 커밋 내용과 같다.

## 검토 범위와 영향
- [사실] 보호 경로 `autotrade-notification-jar/*`(`scripts/ci/protected-paths.txt:16`) 변경이라 실전 관련 PR이다. 보호 테스트(`autotrade-architecture-test` 모듈, RealTradingGuard·GuardedOrderGateway 시험, 장애 시험)는 diff에 없다.
- [사실] 운영 코드 변경은 세 곳이다. `NotificationSetup.built`(`NotificationSetup.java:288-296`)가 채널 생성만 try로 감싸고(`:291-292`), 이름·null 검사(`ExternalChannel.ready`)는 try 밖(`:295`)에서 한다. `slack()`(`:261`)·`kakao()`(`:274`)가 이를 쓴다. `SlackWebhookChannel.name()`(`SlackWebhookChannel.java:53`)·`KakaoMemoChannel.name()`(`KakaoMemoChannel.java:109`)이 상수를 돌려준다(값 `"slack"`·`"kakao"`는 같음, `NotificationSetup.java:35,37`).
- [사실] 실전 런타임 경로: `EngineMain.start` → `startGuard`(`EngineMain.java:92`, Guard 모두 OFF) → `startNotifications`(`:117-`) → `NotificationSetup.start` → `externalChannels` → `built`. 감시는 `WatchdogMain.start` → `startNotifications`(`WatchdogMain.java:86`, `:114-`). 실험실 `NotificationTester.sendAll`(`NotificationTester.java:85`)도 같은 `externalChannels`.
- [사실] 설정 값으로 도달하는 채널 생성 예외는 모두 try 안이다: `SlackWebhookChannel.of`의 URL 검사(`SlackWebhookChannel.java:39-47`), `KakaoMemoChannel` 생성자의 키·링크 검사(`KakaoMemoChannel.java:79-102`), `FileKakaoTokenStore.initial`은 읽기 실패를 자체로 삼킨다(`FileKakaoTokenStore.java:100-115`). 따라서 설정 값별 결과·이유 코드·로그는 main과 같다(M4·M5·M6이 잡힘).
- [사실] main 1352889 뒤 main에 들어온 2커밋(ba12cca, 55b1adf)은 수집기·`autotrade-sim`·문서만 바꾸고 이 PR 파일과 겹치지 않는다(`git diff --name-only 1352889 origin/main`). `git merge-tree --write-tree origin/main pr-144` 충돌 없음. 병합본 빌드는 하지 않았다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·`autotrade-risk` 무변경(diff 파일 8개). 알림 기동 전에 Guard를 OFF로 만든다(`EngineMain.java:92`). 빌드 안 `:autotrade-risk:test`·`EngineMainTest`(`#설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 등) 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 무변경. `autotrade-architecture-test` 무변경, 빌드 안 통과(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest·NotificationSetupMappingTest) |
| A3 | 해당 없음 | 증권사 연동 무변경. 바뀐 코드의 외부 호출 대상은 Slack Webhook·카카오 API뿐이고 호출 방식 무변경 |
| A4 | 해당 없음 | 주문 타임아웃·조회 무변경 |
| A5 | 해당 없음 | 멱등 키 무변경 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 무변경 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 무변경 |
| A8 | 통과 | 설정 값으로 새로 던지는 경로 없음(위 영향 분석, M4·M5·M6 잡힘). 새로 던지는 경우는 채널 이름 코드 실수뿐이며 현재 코드에서 도달 불가(이름이 상수, `SlackWebhookChannel.java:53`, `KakaoMemoChannel.java:109`). 회귀하면 M2·M3처럼 14건 시험이 빌드에서 막는다. 실험실·UI 변경 없음 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 무변경. 코드 실수가 머지되면 엔진·감시가 뜨지 않는 남은 위험(탐침 M2)은 D-103 반영 기록(`D-103-settings-notification-test-send.md:71`)에 적혀 있고, 그 상태는 시험이 머지 전에 막는다 |
| A10 | 통과 | 새 시험 값은 기존 자리표시자(`SLACK_URL` `TPLACEHOLDER/BPLACEHOLDER/SECRETPART`, `full()` 픽스처 `*-PH`)와 문자열 "형식"뿐. 새 예외 문구 없음(기존 `NotificationSetup.java:101` 문구, 채널 이름만). `built`의 catch는 예외 메시지를 버리고 이유 코드만 남긴다(`:292-293`). gitleaks CI 통과 |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음, 고정 값(지문·허용 목록·소유 표·고정 구성 목록) 변경 없음. 새 동작에 실패 경우 시험이 있다: `NotificationSetupTest#채널_이름이_어긋나는_...`(`NotificationSetupTest.java:205`, 이름 불일치는 IAE, 생성 예외는 이유 코드, 정상은 같은 객체)가 M1을 잡고, `#꾸린_채널_이름은_외부_채널_상수와_같다`(`:223`)가 M2·M3을 잡음을 직접 확인 |
| A12 | 통과 | 설정 값별 채널 구성·순서·대체 발송 무변경(`Notifier` 무변경, `NotifierTest#Slack_발송이_실패하면_긴급_알림은_카카오톡으로_넘어간다` 통과, 탐침 헤드 그대로에서 긴급 `[slack, kakao, log]`). 장애표 17종·O1~O8과 장애 시험 8종의 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 경로 무변경. 시각은 주입 `Clock` 그대로 |
| A14 | 통과 | `D-103-settings-notification-test-send.md:69-72` 반영 기록이 코드와 일치한다(try 범위, 두 `name()`의 상수, 남은 위험·되돌릴 조건, 시험 이름). 결정 내용이 바뀌지 않아 새 번호 없이 "추천안 채택(자율)"으로 둔 것은 타당하다. Javadoc(`NotificationSetup.java:214-218`, `:247-248`)이 코드 실수 예외를 적었다 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` BUILD SUCCESSFUL, 1,492건 실패·건너뜀 0(검토자 직접 실행) |
| B6 | 통과 | 새 동작 시험 2개, 변이 M1~M6 잡힘. 지우거나 끈 시험 없음(시험 diff는 `NotificationSetupTest`에 2개 추가뿐) |

## 점검표 밖 발견
- 위험 시나리오(잘못된 주문·중복 주문·보호 공백을 만들 수 있는가):
  1. **설정 값이 틀린 채로 엔진·감시 기동**(Slack URL 접두사 틀림, 카카오 링크에 쿼리): 생성 예외가 try 안이라 그 채널만 꺼지고 이유 코드·로그는 main과 같다(M4·M5·M6 잡힘, `NotificationSetupTest#틀린_채널_설정은_던지지_않고_그_채널만_끈다` 통과). 기동 실패나 Guard 영향 없음. 결함 아님.
  2. **채널 이름 코드 실수가 머지된 경우**(M2 탐침): 엔진은 `EngineMain.start`에서 IAE를 던지고 Guard는 이미 OFF라 주문은 생기지 않는다. 알림 큐는 만들어지기 전이라 남는 스레드도 없다. 감시도 같은 IAE로 뜨지 않는다. 보호 공백(엔진·감시 둘 다 안 뜸)은 생기지만 코드 변경으로만 생기고 시험 14건이 머지 전에 막는다. 감시 쪽 로그 문구는 아래 R1.
  3. **null을 돌려주는 채널 공급자**: `ready`의 `requireNonNull` NPE도 이제 try 밖이다. 현재 두 공급자는 `of`/`new`라 null을 돌려줄 수 없어 도달 불가(architect K1과 같은 결론을 직접 코드로 확인).
  4. **실험실 "알림 시험 보내기"에서 같은 코드 실수**: `NotificationTester.sendAll`(`NotificationTester.java:85`)이 예외로 끝난다. 실험실 경로라 실전 영향 없음.
- R1(권고, 막지 않음): `WatchdogMain.main`은 기동 중 `IllegalArgumentException`을 모두 "감시 설정이 틀려 기동하지 않습니다"로 바꿔 종료 코드 2(`EXIT_BAD_SETTINGS`)로 끝낸다(`WatchdogMain.java:42-45`, `:153-156`). 이 PR로 알림 채널 이름 코드 실수도 IAE로 이 catch에 들어오므로(탐침 M2), 감시에서는 원인이 "설정 오류"로 표시된다. 메시지 뒤에 실제 원인("외부 채널 이름과 꾸린 채널 이름이 다르다: kakao / kakao-x")이 붙어 PR #140 시나리오 1처럼 조용히 숨지는 않지만, 운영자가 설정 파일부터 의심하게 된다. 지금은 도달 불가라 막지 않는다. 다음 감시 PR에서 알림 기동 예외를 설정 오류와 나누거나 문구를 바꾸길 권한다. 개발 쪽 architect·risk-ops 원문은 엔진 쪽(`main`은 `IOException`만 잡음)만 다루고 감시 `main`의 이 catch는 다루지 않았다.
- Q1(참고): 변이 M7(`built` catch를 `IllegalArgumentException`으로 좁힘) 생존. [사실] 현재 설정 값으로 도달하는 생성 예외는 모두 IAE(`SlackWebhookChannel.java:41-47`, `KakaoMemoChannel.java:79-102`)이고 `FileKakaoTokenStore.initial`은 예외를 삼키므로 지금 입력으로는 같은 동작이다. main(1352889)의 try도 같은 조건이라 이 PR이 만든 공백은 아니다. 다른 RuntimeException(예: 토큰 저장소가 `UncheckedIOException`을 던지게 바뀜)이 생기면 그때 시험을 더하면 된다.
- R2(참고): 엔진은 이 IAE를 잡지 않아 `main` 스레드의 처리되지 않은 예외(JVM 기본 스택 출력)로 끝난다(`EngineMain.java:57-64`는 `IOException`만). 원인 문구는 그대로 보이고 코드 실수 전용이라 막지 않는다.
- 참고: PR 브랜치는 main보다 2커밋 뒤(ba12cca, 55b1adf). 겹치는 파일·충돌 없음.

## 판정
승인
