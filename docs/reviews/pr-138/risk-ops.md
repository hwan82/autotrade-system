# risk-ops 검토: 알림 채널 구성 규칙을 NotificationSetup.externalChannels 하나로 합침(D-103 변경 제안), PR #134 남은 권고 4건
- 대상: PR #138 / 커밋 5270cab / main 대비 diff(`git diff origin/main...HEAD`, 10개 파일 +305 −80)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew build --init-script <scratchpad>/mirror.gradle --console=plain`(5270cab): BUILD SUCCESSFUL, 종료 코드 0(138 tasks)
  - 테스트 결과(build/test-results 집계, 실패·오류·건너뜀 0): `:autotrade-risk:test` 55, `:autotrade-architecture-test:test` 44, `:autotrade-app-engine:test` 55, `:autotrade-app-watchdog:test` 53, `:autotrade-notification-jar:test` 81, `:autotrade-app-lab:test` 233, `:autotrade-config:test` 42
  - 새 시험이 결과 파일에 있고 통과함을 확인: `NotificationSetupTest#외부_채널은_Slack_카카오_순서로_꾸리고_못_꾸린_채널은_이유_코드로_돌려준다`, `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다`, `#외부_채널_결과는_채널과_이유_중_하나만_가진다`, `#재동의_문구는_부르는_쪽이_반드시_넘기고_넘긴_것을_그대로_쓴다`, `NotificationQueueTest#공개_생성자는_재동의_문구를_받는_6인자_하나뿐이다`, `NotificationTesterTest#알림_설정_값을_실전_엔진과_같은_이름으로_옮긴다`, `#못_꾸린_이유_코드마다_화면_설명이_있다`

## 검토 범위와 영향 분석
- 보호 경로 변경: `autotrade-notification-jar/*`(운영 `NotificationSetup.java`, `NotificationQueue.java` Javadoc 한 줄, 시험 2개)와 `autotrade-app-engine/*`(시험 `NotifyingCommandAuditLogTest.java`만)가 `scripts/ci/protected-paths.txt`에 걸린다. 따라서 이 PR은 실전 관련 PR이다(지침 10·11장: 사용자 머지, 머지 전 독립 QA).
- 실전 엔진·감시가 이 변경으로 영향받는 경로는 기동 때 알림 큐를 띄우는 곳 하나다: `EngineMain.startNotifications`·`EngineMain.setup`(`EngineMain.java:117-145`), `WatchdogMain.startNotifications`·`WatchdogMain.setup`(`WatchdogMain.java:113-141`). 직접 따진 것:
  - [사실] `kakaoReconsentHelp`가 필수가 됐다(`NotificationSetup.java:74`, 빠지거나 비면 `build()`가 NPE). 엔진·감시는 이미 비지 않은 상수 `RuntimeConfig.Notification.KAKAO_RECONSENT_HELP`를 넘기므로(`EngineMain.java:142`, `WatchdogMain.java:139`) 기동이 새로 실패하는 경로는 없다. `EngineMainTest`·`WatchdogMainTest`의 설정 옮기기 시험이 이를 고정하고 통과했다. 저장소 안 다른 `NotificationSetup.builder()` 호출부는 실험실 `NotificationTester.setup`과 시험뿐이다(grep 확인).
  - [사실] 채널 구성은 여전히 던지지 않는다: Slack은 `SlackWebhookChannel.of` 예외를 잡아 `invalid-webhook-url`, 카카오는 빈 값 사전 검사(`NotificationSetup.java:256`) 뒤 생성 예외를 잡아 `invalid-values`. 새로 더한 `requireNonNull(clock)`·`requireNonNull(http)`는 공개 `start(Path,String,Clock)`가 `JdkHttpTransport`를 만들어 넘기고 엔진·감시가 주입 `Clock`을 넘기므로 걸리지 않는다. 결과 채널 집합과 순서는 이전과 같다(`start`가 `channels.get(0)`=Slack, `get(1)`=카카오를 `Notifier.standard`에 넘김, `NotificationSetup.java:210`; `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다`와 기존 채널 순서 단언 통과).
  - [사실] 엔진·감시에서 바뀐 동작은 카카오 빈 값일 때 로그 문구가 "빈 값"과 "형식이 틀림"으로 나뉜 것뿐이다(`NotificationSetup.java:272-287`). 꺼진 채널은 전처럼 로그가 없고, 로그에 값은 들어가지 않는다.
  - [사실] 새 공개 메서드 `externalChannels`는 HTTP를 부르지 않고(시험의 `http.requests` 비어 있음 단언), 그 안의 `FileKakaoTokenStore.initial`은 읽기만 한다(`FileKakaoTokenStore.java:59-62`). 공개 레코드 `ExternalChannel`의 자동 `toString`은 채널 `toString`을 쓰는데, `SlackWebhookChannel`·`KakaoMemoChannel`은 값을 가린 `toString`을 가진다(`SlackWebhookChannel.java:78`, `KakaoMemoChannel.java:258`).
- 실험실 `NotificationTester`는 이제 엔진·감시와 같은 규칙으로 채널을 꾸린다(`NotificationTester.java:85,97-111`). PR #135 risk-ops K4("시험 성공 ≠ 엔진 발송 가능" 가능성)가 해소됐다. 공유 토큰 파일 사용 방식은 PR #135와 같다.
- 주문 경로·Guard·블랙리스트·직렬 큐·토큰 버킷·보호 시세 경로·서버 측 손절 코드는 diff에 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·`autotrade-risk` 변경 없음(diff 파일 목록). `EngineMain.startGuard`(`EngineMain.java:149-159`) 무변경. `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음(`autotrade-architecture-test` diff 없음). `:autotrade-architecture-test:test` 44건 통과 |
| A3 | 해당 없음 | 증권사 주문 연동(`autotrade-broker-*`) 변경 없음. 바뀐 코드의 외부 호출은 Slack Webhook·카카오 API뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 로직 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 직렬 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진·감시 알림 기동은 채널 설정이 틀려도 던지지 않는다(`NotificationSetup.java:231-268`, `NotificationSetupTest#외부_채널은_Slack_카카오_순서로_꾸리고_못_꾸린_채널은_이유_코드로_돌려준다`). 필수가 된 재동의 문구는 엔진·감시가 상수로 넘긴다(`EngineMain.java:142`, `WatchdogMain.java:139`, `EngineMainTest`·`WatchdogMainTest` 통과). 실험실 변경은 실험실 프로세스 안에서만 돈다. `NotificationQueue` 동작 무변경(Javadoc만). 정지 순서(5.2)와 무관 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 새 시험 값은 `SLACK-URL-PH`·`REST-PH`·`TPLACEHOLDER` 류 자리표시자(`NotificationTesterTest.java:80-110`, `NotificationSetupTest.java:19`). 이유 코드는 고정 문자열(`NotificationSetup.java:39-47`), 로그 문구에 값 없음(`NotificationSetup.java:272-287`), `ExternalChannel.toString`은 값을 가린 채널 `toString`만 쓴다. 기존 `NotificationTesterTest`의 비밀값 단언 통과 |
| A11 | 통과 | 보호 테스트 삭제·비활성화 없음. 보호 경로 시험 변경은 (1) `NotifyingCommandAuditLogTest`가 운영과 같은 문구 상수를 넘김(약화 아님), (2) `NotificationSetupTest`의 "없으면 일반 문구" 단언을 "빠짐·공백·null이면 던짐" 단언 3건으로 바꿈(동작이 엄격해진 데 맞춘 변경), (3) 새 시험 4건 추가. 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작마다 실패 경우 시험이 있다(이유 코드 5종, 레코드 불변식, 공개 생성자 모양) |
| A12 | 통과 | 장애표 #8·#10 등의 알림 경로는 채널 집합·순서·대체 발송이 이전과 같아 17종·O1~O8과 장애 테스트 8종의 전제를 바꾸지 않는다(`#큐와_외부_채널_목록은_같은_규칙으로_꾸린다`, `NotifierTest` 통과). O1(Slack 긴급) 경로 무변경 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 표시 시간대는 `RuntimeConfig.Notification.DISPLAY_ZONE`, 시각은 주입 `Clock` |
| A14 | 통과 | D-100 반영 기록(`D-100-notification-wiring-watchdog-engine.md:89-93`), D-103 반영 기록(`D-103-settings-notification-test-send.md:58-61`), ADR-001 모듈 표가 코드와 일치(필수 재동의 문구, 공개 `externalChannels`, 이유 코드 5종, 빈 값 사전 검사). "기존 `NotificationTesterTest`는 고치지 않음"은 diff로 확인(추가만 있음). 결정 내용이 바뀌지 않아 새 번호를 쓰지 않은 것은 타당. 결정 로그 색인 행은 아래 R1 |

## 점검표 밖 발견
- R1(권고, 막지 않음): `docs/decisions/README.md:109` D-103 색인 행이 "알림 모듈 무변경(채널 구성 공개 메서드는 변경 제안)"으로 남아 이 PR 뒤 사실과 다르다. 상세 파일에는 반영 기록이 있으므로 색인 행에 "변경 제안은 PR #138에서 반영"을 덧붙이길 권한다.
- R2(권고, 막지 않음): `NotificationSetup.start`가 `channels.get(0)`·`get(1)` 위치로 Slack·카카오를 고른다(`NotificationSetup.java:210`). 지금은 `externalChannels`의 순서를 문서와 시험이 지키지만, 이름(`SLACK`·`KAKAO`)으로 고르면 순서 실수가 생겨도 긴급 순서(Slack 1순위)가 뒤바뀌지 않는다.
- R3(기록): PR #135 risk-ops K4는 이 PR로 해소됐다. K3(카카오 리프레시 토큰 회전 `[가정]`)은 여전히 7단계 운영 점검 몫이다.
- 지침 10·11장: 보호 경로(`autotrade-notification-jar/*`, `autotrade-app-engine/*`)를 바꾸므로 사용자 직접 머지와 머지 전 독립 QA 대상이다.

## 판정
승인
