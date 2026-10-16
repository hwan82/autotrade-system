# risk-ops 검토: 알림 모듈 PR #138 검토 권고 7건(채널 이름 검사·이름으로 고르기·로그 시험·세 곳 설정 옮기기 비교)
- 대상: PR #140 / 커밋 2bb196a / main 대비 diff(`git diff origin/main...HEAD`, 9개 파일 +257 −6)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build -I <scratchpad>/mirror.gradle --console=plain`(2bb196a): BUILD SUCCESSFUL, 종료 코드 0(138 tasks, 4분 20초)
  - 테스트 결과(build/test-results 집계, 실패·오류·건너뜀 0): `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 46(main 대비 +2), `:autotrade-app-engine:test` 55, `:autotrade-app-watchdog:test` 53, `:autotrade-notification-jar:test` 84(+3), `:autotrade-app-lab:test` 234(+1)
  - 새 시험이 결과 파일에 있고 통과함을 확인: `NotificationSetupMappingTest#같은_알림_설정이면_엔진_감시_실험실이_같은_값으로_옮긴다`, `#설정이_다르면_옮긴_결과도_다르다`, `NotificationSetupTest#외부_채널_이름은_slack_kakao뿐이고_꾸린_채널_이름과_같다`, `#켰는데_못_꾸린_채널은_이유마다_로그를_남기고_꺼진_채널은_조용하다`, `#모르는_이유_코드는_다른_채널_문구로_떨어지지_않고_채널_이름과_코드를_적는다`, `NotificationTesterTest#채널_화면_이름은_채널마다_정하고_모르는_채널을_Slack으로_적지_않는다`
  - 변이 시험(시도, 실행 못 함): `start`의 Slack·카카오 자리 바꿈과 `WatchdogMain.setup`의 값 자리 바꿈을 넣어 시험이 잡는지 돌리려 했으나 이 환경의 권한 판정이 작업 트리 수정을 막아 실행하지 못했다. 아래 해당 판단은 코드 읽기에 근거한 `[추론]`이다.

## 검토 범위와 영향 분석
- 보호 경로 변경: `autotrade-architecture-test/*`(새 시험 1개와 시험 전용 다리 클래스 3개)와 `autotrade-notification-jar/*`(운영 `NotificationSetup.java`, 시험 1개)가 `scripts/ci/protected-paths.txt:5,16`에 걸린다. 따라서 이 PR은 실전 관련 PR이다(지침 10·11장: 사용자 직접 머지, 머지 전 독립 QA).
- 실전 엔진·감시가 이 변경으로 영향받는 경로는 기동 때 알림 큐를 띄우는 곳 하나다: `EngineMain.startNotifications`(`EngineMain.java:117-126`, 호출 `:94`)와 `WatchdogMain.startNotifications` → `NotificationSetup.start`(`NotificationSetup.java:218-225`). 직접 따진 것:
  - [사실] `start`가 `channels.get(0)`·`get(1)` 대신 `named(channels, SLACK)`·`named(channels, KAKAO)`로 고른다(`NotificationSetup.java:222`, `:284-290`). `externalChannels`는 언제나 `List.of(slack(..), kakao(..))` 두 개를 이름 `slack`·`kakao`로 돌려주므로(`:243-248`, `:250-281`) `named`의 `IllegalStateException`은 지금 코드로는 나올 수 없다. 나오더라도 `startNotifications`가 엔진 기동을 실패시키는 쪽(fail-closed)이고, Guard는 어차피 모두 OFF로 시작한다. 결과 채널 집합·순서는 이전과 같다: `Notifier.standard`(`Notifier.java:43-60`)가 긴급 Slack→카카오→로그, 정보 카카오→Slack→로그로 꾸리며 `NotificationSetupTest:52-53`의 순서 단언이 통과했다.
  - [추론] 자리 바꿈 변이(`named(KAKAO)`, `named(SLACK)` 순서 뒤바꿈)는 `NotificationSetupTest:52`의 `containsExactly("slack", "kakao", "log")`에서 깨진다. 직접 실행하지는 못했다(위 "직접 실행한 것").
  - [사실] `ExternalChannel` 생성자에 이름 검사 두 개가 더해졌다(`NotificationSetup.java:91-102`). 실제 채널 이름은 `SlackWebhookChannel.name()`="slack"(`SlackWebhookChannel.java:52-54`), `KakaoMemoChannel.name()`="kakao"(`KakaoMemoChannel.java:108-110`)로 상수와 같아, 엔진·감시에서 지금까지 꾸려지던 채널이 새로 꺼지는 경로는 없다. 정상 설정에서 두 채널이 꾸려지는 기존 시험과 엔진·감시 시험이 통과했다.
  - [사실] `logSkip`은 `SKIP_INVALID_VALUES` 분기를 명시하고 `default`는 채널 이름과 이유 코드만 적는다(`NotificationSetup.java:296-311`). 이유 코드는 고정 문자열이고 설정 값은 들어가지 않는다. 새 시험이 등급·문구까지 단언하고, 두 채널 모두 못 꾸린 경우 로그에 자리표시자 값(`SECRETPART`, `REST-KEY-PH` 등)이 없음을 확인한다.
- 시험 전용 다리 클래스 `EngineNotificationMapping`·`WatchdogNotificationMapping`·`LabNotificationMapping`은 `autotrade-architecture-test`의 테스트 소스다. 실전 엔진·감시 런타임 클래스패스(`realPathRuntime`, `autotrade-architecture-test/build.gradle.kts`)에 들어가지 않고, 운영 코드의 공개 범위(세 `setup`은 패키지 전용 그대로: `EngineMain.java:129`, `WatchdogMain.java:126`, `NotificationTester.java:97`)를 넓히지 않으며 리플렉션을 쓰지 않는다. 같은 모듈의 `com.autotrade.app.engine.*` 테스트 소스는 이미 선례가 있고(`archfixture`, `SplitPackageCommand` 등), `ModuleOwnershipTest#운영_클래스는_패키지를_소유한_모듈에만_있다`는 운영 클래스만 보므로 소유 표(`OWNERS`)를 바꿀 필요가 없었고 바뀌지 않았다. 전체 빌드에서 `ModuleOwnershipTest`·`ArchitectureTest`·`RealPathReflectionTest` 통과.
- `NotificationSetupMappingTest`의 비교는 `NotificationSetup.equals`(`NotificationSetup.java:321-328`, 11개 필드 전부)로 한다. 설정 5가지는 값마다 다른 자리표시자를 쓰고 ME/BIZ, 켜짐/꺼짐, 액세스 토큰·클라이언트 시크릿 유무를 나눠 한 곳의 자리 바꿈이 다른 두 곳과 달라지게 한다. 대조 시험(`#설정이_다르면_옮긴_결과도_다르다`)이 비교가 실제 값을 본다는 것을 고정한다. 이로써 PR #138 architect R1·QA 관찰 3("실험실 시험 성공 = 엔진·감시 발송 가능"의 전제가 직접 고정되지 않음)이 해소됐다.
- 실험실 `NotificationTester.label`(`NotificationTester.java:115-121`) 변경은 실험실 프로세스 화면 문구뿐이다.
- 주문 경로·Guard·블랙리스트·직렬 큐·토큰 버킷·보호 시세 경로·서버 측 손절 코드는 diff에 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·`autotrade-risk` 변경 없음(diff 파일 목록). `EngineMain.startGuard`(`EngineMain.java:149-`) 무변경. `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. `autotrade-architecture-test`의 변경은 새 시험·다리 클래스 추가뿐이고 `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest`·`OurModules` 무변경(diff 파일 목록). `:autotrade-architecture-test:test` 46건 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 바뀐 코드의 외부 호출 대상은 Slack Webhook·카카오 API뿐이며 호출 방식 무변경 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진·감시 알림 기동 경로에서 바뀐 것은 채널 고르기 방식(`NotificationSetup.java:222`)과 생성자 이름 검사(`:91-102`)이며, 지금 채널 이름과 상수가 같아 새로 던지는 경로가 없다(`SlackWebhookChannel.java:52-54`, `KakaoMemoChannel.java:108-110`). `EngineMainTest`·`WatchdogMainTest`·`NotificationSetupTest` 통과. 시험 다리 클래스는 시험 소스라 실전 런타임에 없다. 실험실 변경은 실험실 프로세스 안에서만 돈다. 정지 순서(5.2)와 무관 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 새 시험 값은 `SLACK-URL-PH`·`ACCESS-PH`·`REST-PH`·`REFRESH-PH`·`CLIENT-PH`·`http://link.example` 자리표시자(`NotificationSetupMappingTest.java` `configs()`). 새 로그 `default` 문구는 채널 이름·이유 코드만 싣는다(`NotificationSetup.java:310`). `NotificationSetupTest#켰는데_못_꾸린_채널은_이유마다_로그를_남기고_꺼진_채널은_조용하다`가 로그에 값이 없음을 단언하고 통과. 생성자 예외 문구는 채널 이름만 싣는다(`:94-101`) |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음(diff는 시험 추가와 기존 시험 단언 추가뿐: `NotificationTesterTest.java:153-156`에 단언 3줄 추가). 고정 값(지문·허용 목록·소유 표·고정 구성 목록) 변경 없음. 새 동작마다 실패 경우 시험이 있다: 이름 검사 위반 4종(`#외부_채널_이름은_slack_kakao뿐이고_꾸린_채널_이름과_같다`), 모르는 이유 코드(`#모르는_이유_코드는_...`), 설정 옮기기 불일치(`NotificationSetupMappingTest` 두 시험), 모르는 채널 화면 이름(`NotificationTesterTest#채널_화면_이름은_...`) |
| A12 | 통과 | 장애표 #2·#8·#10·#16·#17과 O1의 Slack 긴급 경로는 채널 집합·순서·대체 발송이 이전과 같다(`Notifier.java:43-60` 무변경, `NotificationSetupTest:52-53` 순서 단언 통과). 17종·O1~O8 표와 장애 테스트 8종(7장)의 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 표시 시간대는 `RuntimeConfig.Notification.DISPLAY_ZONE`, 시각은 주입 `Clock`(무변경) |
| A14 | 통과 | `D-103-settings-notification-test-send.md:62-68`에 반영 기록 6줄이 코드와 일치(이름 검사 `IllegalArgumentException`, 이름으로 고르기, `invalid-values` 분기와 `default` 문구, `label` 규칙, 다리 클래스 3개와 버린 대안·되돌릴 조건, 시험 이름). 결정 내용이 바뀌지 않아 새 번호를 쓰지 않은 것은 타당. 색인 행(`docs/decisions/README.md:109`)은 PR #138 risk-ops R1대로 이미 main에 반영되어 있다. ADR-001 모듈 표(`ADR-001-module-structure.md:56`)는 이 PR로 사실과 달라진 곳이 없다 |

## 점검표 밖 발견
- K1(권고, 막지 않음): 새 이름 검사(`ExternalChannel` 생성자, `NotificationSetup.java:91-102`)는 `ExternalChannel.ready(...)`가 `slack()`·`kakao()`의 `try` 안에서 불려(`:255`, `:276`) 걸리면 `RuntimeException`으로 잡혀 `invalid-webhook-url`·`invalid-values`로 바뀐다. 채널 클래스의 `name()`이 상수와 어긋나는 회귀가 생기면 엔진·감시는 그 채널을 끄고 운영자에게 "설정 형식이 틀림"이라는 틀린 원인을 로그로 보인다. 지금은 이름이 같고, 그런 회귀는 정상 설정에서 채널이 null이 되어 `NotificationSetupTest:52`의 순서 단언에서 깨질 것으로 본다(`[추론]`, 변이 실행 못 함). 다음 알림 PR에서 `ready(...)`를 `try` 밖으로 빼거나, 채널 클래스의 `name()`이 `NotificationSetup.SLACK`·`KAKAO` 상수를 쓰게 하길 권한다.
- K2(기록): 이번 검토에서 변이 시험 두 개를 돌리려 했으나 환경 권한 판정으로 실행하지 못했다. 점검표 판정은 직접 실행한 전체 빌드와 코드 읽기로 했고, 변이로만 확인할 수 있는 주장(K1, 위 `[추론]` 두 곳)은 독립 QA의 변이 검사로 확인하길 권한다.
- K3(기록): PR #138 risk-ops R2(이름으로 고르기)는 이 PR로 해소됐다. PR #135 risk-ops K3(카카오 리프레시 토큰 회전 `[가정]`)은 여전히 7단계 운영 점검 몫이다.
- 지침 10·11장: 보호 경로(`autotrade-architecture-test/*`, `autotrade-notification-jar/*`)를 바꾸므로 사용자 직접 머지와 머지 전 독립 QA 대상이다.

## 판정
승인
