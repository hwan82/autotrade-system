# risk-ops 검토: D-103 설정 화면 알림 시험 보내기(D-100 후속 2번)
- 대상: PR #135 / 커밋 1ba352b / main 대비 diff(`git diff origin/main...HEAD`, 12개 파일 +864 −6)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --init-script <mirror.gradle>`(1ba352b): BUILD SUCCESSFUL, 종료 코드 0
  - 테스트 결과(build/test-results 집계, 실패·오류·건너뜀 0): `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 44, `:autotrade-app-engine:test` 55, `:autotrade-app-watchdog:test` 53, `:autotrade-notification-jar:test` 77, `:autotrade-app-lab:test` 229(`NotificationTesterTest`, `NotificationTestControllerTest`, `SettingsControllerTest` 포함)
  - 화면 시험 `autotrade-ui/e2e/tests/03-settings-notification-test.spec.mjs`(Playwright)는 돌리지 않았다. 아래 판정은 이 시험에 기대지 않는다.

## 검토 범위와 영향 분석
- 바뀐 운영 코드는 실험실(`autotrade-app-lab`)뿐이다: `LabWebConfiguration.java`(빈 1개), `settings/NotificationTestController.java`, `settings/NotificationTester.java`, UI `settings.js`·`app.css`. `scripts/ci/protected-paths.txt`의 보호 경로(`autotrade-risk`, `autotrade-app-engine`, `autotrade-app-watchdog`, `autotrade-broker-*`, `autotrade-config`, `autotrade-notification-jar`, `autotrade-architecture-test` 등)는 diff에 없다.
- 실전 쪽과 닿는 유일한 지점은 **공유 카카오 토큰 파일** `<설정 폴더>/notification-state/kakao-token.json`이다(`NotificationTester.java:123-127`). 실전 엔진(`EngineMain.java:118`)과 감시(`WatchdogMain.java:149-150`)도 `paths.runtimeFile().getParent()`를 설정 폴더로 쓰고, 실험실 빈도 `store.paths().runtimeFile().getParent()`(`LabWebConfiguration.java:74-76`)라 같은 파일이다. 이 파일은 엔진 긴급 알림(장애표 #2·#8·#10·#16 등의 Slack·카카오 알림) 경로의 입력이므로 따로 따졌다.
  - [사실] 실험실은 갱신이 **성공**했을 때만 파일을 쓴다(`KakaoMemoChannel.java:193-216`: 거절·시간 초과·이상 응답은 `store.save` 전에 반환). 쓰기는 같은 `FileKakaoTokenStore`의 pid별 임시 파일 + 원자적 이동(`FileKakaoTokenStore.java:74-86`)이라 엔진이 반쯤 쓴 파일을 읽지 않는다.
  - [사실] 엔진의 `KakaoMemoChannel.adoptLatest`는 같은 출처(리프레시 토큰 해시)이고 더 늦게 만료되는 토큰만 받는다(`KakaoMemoChannel.java:143-161`, `FileKakaoTokenStore.java:110`). 읽기 실패·깨진 파일은 빈 값이라 엔진은 자기 메모리 토큰으로 보낸다(`FileKakaoTokenStore.java:96-117`, `KakaoMemoChannelTest#저장소를_읽다_런타임_예외가_나도_지금_토큰으로_보낸다`, `FileKakaoTokenStoreTest#없거나_깨졌거나_너무_큰_파일은_빈_값이다`). 실험실은 엔진의 거절 기억(프로세스 메모리)을 바꿀 수 없다.
  - [추론] 실험실 시험이 엔진 카카오 알림을 끊는 경로는 "갱신 응답에 새 리프레시 토큰이 왔는데 실험실 저장이 실패하거나 다른 프로세스 쓰기와 겹쳐 사라지고, 카카오가 옛 리프레시 토큰을 무효로 하는 경우"뿐이다. 카카오가 회전 뒤 옛 리프레시 토큰을 무효로 하는지는 확인하지 못했다 `[가정]`(D-103 대안 T2도 같은 가정). 이 위험은 D-100의 엔진·감시 공유에 이미 있던 것이고 실험실이 세 번째 쓰는 쪽으로 더해 확률을 조금 늘린다. 이 경우에도 엔진은 갱신 거절을 만나 재동의 긴급 알림을 내고(`KakaoMemoChannel.java:193-196`), 긴급 1순위 Slack은 영향이 없다. 계좌 훼손으로 이어지는 경로는 없다.
- 실전 엔진 격리(절대 규칙 6): 시험 요청은 실험실 Tomcat 요청 스레드에서 최대 약 40초 막히고(`JdkHttpTransport.java:31-32` 연결 3초·요청 10초, Slack 1회 + 카카오 최대 3회), 한 번에 하나(`NotificationTestController.java:50-60`)·끝난 뒤 10초(`COOLDOWN`)로 제한된다. 엔진·게이트웨이·감시 프로세스에 호출·소켓·제어 명령을 보내지 않는다.
- 외부 메시지(지침 8장 3번): 사용자가 버튼을 누를 때만 나간다(요청 본문 없음, 기동·예약 발송 없음). `LocalOnlyRequestFilter`가 POST에 루프백·Origin·CSRF를 검사한다(`LocalOnlyRequestFilter.java:58-69`, `SettingsControllerTest#알림_시험은_CSRF_토큰이_있어야_하고_꺼진_채널은_보내지_않는다`: 토큰 없으면 403). 보내는 곳은 `SlackWebhookChannel.of`의 `https://hooks.slack.com/services/` 접두사 검사를 거친 URL과 카카오 고정 URL뿐이다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·`autotrade-risk`·`autotrade-app-engine` 변경 없음(diff 파일 목록). Guard 관련 문자열은 D-103 문서의 "바꾸지 않는다" 문장뿐. `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. `:autotrade-architecture-test:test` 44건 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 새 코드의 외부 호출은 Slack Webhook·카카오 API뿐(`NotificationTester.java:94-131`) |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 새 코드는 실험실 프로세스 안에서만 돈다(`LabWebConfiguration.java:74-76`). 실전 엔진과 닿는 것은 공유 토큰 파일뿐이며, 엔진은 같은 출처·더 늦은 만료의 유효 토큰만 받고 읽기 실패 시 자기 토큰으로 보낸다(`KakaoMemoChannel.java:143-161`, `KakaoMemoChannelTest#저장소의_토큰이_더_늦게_만료되면_갱신하지_않고_그_토큰을_쓴다`, `FileKakaoTokenStoreTest#사용자가_새_리프레시_토큰을_넣으면_옛_파일을_쓰지_않는다`). 동시 실행·연타 제한 `NotificationTestControllerTest#보내는_중에는_겹쳐_보내지_않는다`, `#끝난_뒤_10초_안에는_거절하고_지나면_다시_보낸다`. 정지 순서(broker-and-fault-design.md 5.2)는 바뀌지 않는다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 시험 픽스처는 `PLACEHOLDER-…`·`example.invalid` 자리표시자(`NotificationTesterTest.java:35-41`). 응답·로그에는 채널 이름·상태·사유 코드만(`NotificationTestController.java:64-67`, 예외는 클래스 이름만 `NotificationTester.java:144`). `NotificationTesterTest#assertNoSecrets`, `#Slack이_실패해도_카카오톡을_시험하고_실패_설명에_비밀값이_없다`, `SettingsControllerTest#알림_시험은_CSRF_토큰이_있어야_하고_꺼진_채널은_보내지_않는다`(응답 `assertNoPlaintext`, 로그에 옛 토큰 없음) 통과. 토큰 파일은 기존 규칙대로 소유자 전용 권한(`FileKakaoTokenStore.java:78-80`) |
| A11 | 통과 | 보호 테스트 삭제·비활성화·고정 값 변경 없음(보호 경로 diff 없음). 새 동작에 실패 경우 테스트 있음: 꺼짐·URL 형식·BIZ·빈 값·링크 형식 건너뜀, Slack 404, 갱신 거절, 중단, 409·429, 예외 뒤 복구(`NotificationTesterTest` 11건, `NotificationTestControllerTest` 3건, 모두 통과) |
| A12 | 통과 | 장애 대응표 17종·O1~O8과 장애 테스트 8종의 전제를 바꾸지 않는다. 엔진 알림 큐·대체 발송 순서·`NotificationSetup`은 무변경이고, 공유 토큰 파일의 남은 위험은 위 영향 분석과 같이 D-100에 이미 있던 성격(Slack 긴급 경로 무관) |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 시각 표시는 `RuntimeConfig.Notification.DISPLAY_ZONE`, 현재 시각은 주입된 `Clock`(`NotificationTester.java:83-85`, `NotificationTestController.java:55,62,74`) |
| A14 | 통과 | D-103(`docs/decisions/D-103-settings-notification-test-send.md`)과 결정 로그 행, 기획서 v14.45 UI-04·MON-06이 코드와 일치: 저장 값만 사용(요청 본문 없음), 채널별 직접 발송, 409/429, CSRF, 공유 토큰 파일, 로그 한 줄 형식(`SettingsControllerTest`의 로그 단언과 일치) |

## 점검표 밖 발견
- K1(권고, 막지 않음): 실험실이 갱신에 실패하거나 거절될 때 토큰 파일을 건드리지 않는다는 것을 실험실 쪽 테스트로 고정하지 않았다. 지금은 `KakaoMemoChannel` 구현(`KakaoMemoChannel.java:193-205`가 `save` 전에 반환)에 기댄다. `NotificationTesterTest`에 "갱신 401·시간 초과 뒤 기존 토큰 파일 내용이 그대로" 단언을 더하길 권한다.
- K2(권고, 막지 않음): 카카오가 갱신 응답에 새 리프레시 토큰을 줄 때 실험실이 그 값을 파일에 남기는 경우(D-103 4번)를 실험실 테스트로 확인하지 않았다(지금 테스트는 액세스 토큰만 확인). 실험실 구성(출처 해시가 설정의 리프레시 토큰)과 묶은 단언이 있으면 엔진과의 이어 쓰기가 더 분명해진다.
- K3(기록): 카카오 리프레시 토큰 회전 뒤 옛 토큰 유효 여부는 `[가정]`이다. 실전 활성화 전 운영 체크리스트에 "카카오 재동의 긴급 알림 수신 확인"을 남기는 것이 좋다(이 PR 범위 밖).
- K4(기록): `NotificationTester`가 `NotificationSetup`의 채널 구성 규칙을 따로 갖는다(D-103 변경 제안에 기록됨). 규칙이 어긋나면 "시험 성공 ≠ 엔진 발송 가능"이 될 수 있으나 실전 주문·보호에는 영향이 없다.
- 지침 10장 머지 권한: 이 PR은 보호 경로·실전 주문 경로를 바꾸지 않지만 실전 엔진·감시가 읽는 파일을 실험실이 쓰게 하므로, 머지 전 사용자 확인 여부는 10장 기준으로 판단할 것.

## 판정
승인
