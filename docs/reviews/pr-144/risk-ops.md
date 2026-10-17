# risk-ops 검토: 알림 채널 이름 검사를 꾸리기 try 밖으로(PR #140 QA 시나리오 1·risk-ops K1)
- 대상: PR #144 / 커밋 9ca7786 / main 대비 diff(`git diff origin/main...HEAD`, 5개 파일 +50 −11)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew --init-script <scratchpad>/mirror.init.gradle build --console=plain`(9ca7786): BUILD SUCCESSFUL in 5m 18s, 종료 코드 0(138 tasks)
  - 테스트 결과(build/test-results 집계, 실패·오류·건너뜀 0): `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 46, `:autotrade-app-engine:test` 55, `:autotrade-app-watchdog:test` 53, `:autotrade-notification-jar:test` 86(main 대비 +2), `:autotrade-app-lab:test` 234
  - 새 시험 두 개가 결과 파일에 있고 통과: `NotificationSetupTest#채널_이름이_어긋나는_코드_실수는_설정_오류로_바뀌지_않고_그대로_드러난다`, `#꾸린_채널_이름은_외부_채널_상수와_같다`
  - 변이 시험(직접 실행, 실행 뒤 `git checkout`으로 원복, 작업 트리 깨끗함 확인):
    - V1 `built`를 예전 모양(`ready(name, build.get())`를 try 안에)으로 되돌림 → `:autotrade-notification-jar:test` 86건 중 1건 실패(`#채널_이름이_어긋나는_코드_실수는_...`). 커밋·D-103 기록의 "이 변이를 잡는다"는 주장을 확인했다.
    - V2 `KakaoMemoChannel.name()`이 `"kakao-x"`를 돌려줌 → 6건 실패(`NotificationSetupTest` 5건: `#켜진_채널만_표준_순서로_꾸린다`, `#외부_채널은_Slack_카카오_순서로_...`, `#꾸린_채널_이름은_외부_채널_상수와_같다`, `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다`, `#켰는데_못_꾸린_채널은_...`, 그리고 `NotifierTest#Slack_발송이_실패하면_긴급_알림은_카카오톡으로_넘어간다`). 이름 회귀는 머지 전에 막힌다.

## 검토 범위와 영향 분석
- 보호 경로: `autotrade-notification-jar/*`(`scripts/ci/protected-paths.txt:16`)를 바꾸므로 실전 관련 PR이다(지침 10·11장: 사용자 직접 머지, 머지 전 독립 QA).
- 실전 런타임 영향 경로는 하나다: `EngineMain.start`(`EngineMain.java:89-113`) → `startNotifications`(`:117-126`, 호출 `:94`)와 `WatchdogMain.start` → `startNotifications`(`WatchdogMain.java:86`, `:114-123`) → `NotificationSetup.start`(`NotificationSetup.java:219-226`) → `externalChannels`(`:244-249`) → `slack()`·`kakao()`(`:251-275`) → 새 `built`(`:282-290`). 실험실 `NotificationTester.sendAll`(`NotificationTester.java:85`)도 같은 `externalChannels`를 쓴다.
- [사실] 바뀐 동작은 하나다: 예전에는 `ExternalChannel.ready(...)`(이름·null 검사, `NotificationSetup.java:91-111`)가 try 안이라 그 예외가 `invalid-webhook-url`·`invalid-values`로 바뀌었고, 이제 try 밖이라 그대로 던진다. 채널을 만드는 코드(`SlackWebhookChannel.of`, `FileKakaoTokenStore`·`store.initial`·`KakaoMemoChannel` 생성자)의 예외는 예전처럼 이유 코드로 바뀐다(`:284-288`). 따라서 설정 값이 틀린 경우의 결과·로그(`logSkip`, `:305-321`)는 이전과 같다.
- [사실] try 밖에서 던질 수 있는 것은 `ExternalChannel` 생성자의 이름 불일치와 `ready`의 null 검사뿐이고, 둘 다 설정 값이 아니라 코드로만 정해진다. `SlackWebhookChannel.name()`·`KakaoMemoChannel.name()`이 이제 `NotificationSetup.SLACK`·`KAKAO` 상수를 돌려주므로(`SlackWebhookChannel.java:53`, `KakaoMemoChannel.java:109`) 현재 코드에서는 도달하지 않는다. 즉 어떤 설정 파일 값으로도 엔진·감시 기동이 새로 실패하지 않는다(`EngineMain.startNotifications` Javadoc "채널 설정이 틀려도 기동을 막지 않는다"는 여전히 사실).
- [사실·추론] 코드 회귀가 생겨 이름이 어긋나면 이제 `EngineMain.start`가 `IllegalArgumentException`으로 기동에 실패한다(`main`은 `IOException`만 잡음, `EngineMain.java:57-64`). 실전 계좌 보호 관점에서 따졌다:
  - Guard는 `startNotifications` 전에 모두 OFF로 만들어지고(`:92`), 기동 실패는 주문을 만들지 않는다(fail-closed).
  - 보유 종목이 있는 날 엔진이 뜨지 않으면 PC 쪽 손절 감시가 없다. 다만 이 상태는 코드 변경으로만 생기고, V2처럼 6개 시험이 빌드에서 막는다. 반대 선택(채널만 끄기)은 실전 계좌가 있을 때 긴급 알림이 로그로만 가는 상태를 "형식이 틀림"이라는 틀린 원인으로 숨긴다. 장애표 #2·#8·#16·#17의 긴급 알림 경로를 조용히 잃는 쪽보다 머지 전 시험·기동 시 예외로 드러나는 쪽이 낫다고 본다. D-103 반영 기록(`D-103-settings-notification-test-send.md:71`)이 이 남은 위험과 되돌릴 조건을 적었다.
- 주문 경로·Guard·블랙리스트·직렬 큐·토큰 버킷·보호 시세 경로·서버 측 손절 코드는 diff에 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·`autotrade-risk` 변경 없음(diff 파일 목록 5개). `EngineMain.startGuard`(`EngineMain.java:149-`) 무변경, Guard는 알림 기동 전에 OFF로 생성(`:92`). `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. `autotrade-architecture-test` 무변경, `:autotrade-architecture-test:test` 46건 통과(`ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 포함) |
| A3 | 해당 없음 | `autotrade-broker-*` 변경 없음. 바뀐 코드의 외부 호출 대상은 Slack Webhook·카카오 API뿐이며 호출 방식 무변경 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진·감시 알림 기동 경로에서 설정 값으로 새로 던지는 경로가 없다(`NotificationSetup.java:282-290`, 채널 생성 예외는 이전처럼 이유 코드). 이름은 상수라(`SlackWebhookChannel.java:53`, `KakaoMemoChannel.java:109`) 이름 검사 예외는 도달 불가이고, 회귀는 V2 변이로 6건 시험이 잡음을 확인. 실험실 변경 없음, 정지 순서(5.2) 무관. `EngineMainTest`·`WatchdogMainTest`·`NotificationSetupTest` 통과 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경 없음. 엔진 기동 실패 가능성은 코드 회귀로만 생기고 머지 전 시험이 막는다(V2). 위 "검토 범위" 분석 참고 |
| A10 | 통과 | 새 시험 값은 기존 자리표시자(`SLACK_URL`, `full()` 픽스처)와 문자열 "형식"뿐. 새 예외 문구는 기존 생성자 문구(채널 이름만, `NotificationSetup.java:95,101`). 로그 문구 무변경 |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음(diff는 `NotificationSetupTest`에 시험 2개 추가뿐). 고정 값(지문·허용 목록·소유 표·고정 구성 목록) 변경 없음. 새 동작에 실패 경우 시험이 있다(이름 불일치가 예외로 드러남, 생성 예외는 이유 코드, 정상은 같은 채널). V1 변이로 새 시험이 옛 동작을 잡음을 직접 확인 |
| A12 | 통과 | 설정 값별 채널 구성 결과·순서·대체 발송은 이전과 같다(`Notifier` 무변경, `NotificationSetupTest#켜진_채널만_표준_순서로_꾸린다` 통과). 장애표 17종·O1~O8의 알림 경로와 장애 테스트 8종의 전제를 바꾸지 않는다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 시각은 주입 `Clock`(무변경) |
| A14 | 통과 | `D-103-settings-notification-test-send.md:69-72` 반영 기록이 코드와 일치(`built`의 try 범위, 두 채널 `name()`의 상수 사용, 남은 위험·되돌릴 조건, 시험 이름). 결정 내용이 바뀌지 않아 새 번호를 쓰지 않은 것은 타당하고 "추천안 채택(자율)" 표시가 있다 |

## 점검표 밖 발견
- K1(권고, 막지 않음): `externalChannels` Javadoc(`NotificationSetup.java:230`)의 "던지지 않는다"는 이제 "설정이 틀려도 던지지 않는다(채널 이름이 상수와 어긋나는 코드 실수는 `IllegalArgumentException`)"로 고쳐야 정확하다. `start` Javadoc(`:214`)의 "채널 설정이 틀려도 던지지 않고"는 그대로 사실이다. 다음 알림 PR에서 함께 고치길 권한다.
- K2(기록): 이 PR로 PR #140 risk-ops K1과 QA 위험 시나리오 1이 해소됐다. PR #140 risk-ops K2(변이 미실행)는 이번에 V1·V2를 직접 실행해 확인했다.
- 지침 10·11장: 보호 경로(`autotrade-notification-jar/*`)를 바꾸므로 사용자 직접 머지와 머지 전 독립 QA 대상이다.

## 판정
승인
