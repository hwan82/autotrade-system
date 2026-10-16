# D-103 설정 화면 "알림 시험 보내기"(D-100 후속 2번)

- 날짜: 2026-10-16
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). 실험실 설정 화면(UI-04)에 버튼 하나와 API 하나를 더한다. 알림 모듈(`autotrade-notification-jar`), 실전 엔진·감시, 설정 키, 보호 경로, 실전 주문 경로·RealTradingGuard·블랙리스트·리스크 한도·장애 대응 규칙과 보호 테스트는 바꾸지 않는다.
- 담당: ui-dev. 교차 검토: architect(코드), risk-ops(실전 엔진·감시와 같은 카카오 토큰 파일을 쓰므로)
- 근거: [D-100](D-100-notification-wiring-watchdog-engine.md)(5번 카카오 키, 6번 토큰 파일 공유), [D-099](D-099-notification-library-first.md) 후속 "설정 화면 시험 발송", [D-033](D-033-unpriced-source-default-off.md)(유료·요금 미확인 기능은 기본 꺼짐, 알림톡 BIZ를 보내지 않는 근거), UI-01(127.0.0.1 전용·CSRF, `LocalOnlyRequestFilter`)

## 결정

1. **버튼을 누를 때만 보낸다.** 설정 화면 "알림" 묶음 아래에 "알림 시험 보내기" 버튼을 둔다. 누르면 `POST /api/settings/notification-test`(본문 없음)가 실험실 메모리의 **저장된** 알림 설정으로 켜진 외부 채널(Slack, 카카오톡)마다 시험 알림 한 건을 보낸다. 입력만 하고 저장하지 않은 값은 쓰지 않는다(비밀값이 이 요청으로 오가지 않음). 실험실은 기동 때나 정해진 시각에 저절로 보내지 않는다.
2. **채널마다 따로 보낸다.** 실전 엔진·감시의 `Notifier`는 첫 성공에서 멈추므로(대체 발송) Slack이 받으면 카카오톡을 시험할 수 없다. 그래서 채널 객체(`SlackWebhookChannel`, `KakaoMemoChannel`)를 하나씩 직접 부른다. 로그 채널은 시험하지 않는다. 알림 종류는 `notification-test`(정보 등급, `EventRoutes.defaults().with`로 이 화면에서만 더함), 제목 "알림 시험", 본문 "실험실 설정 화면에서 보낸 시험 알림입니다. 조치할 것은 없습니다.", 시각은 Asia/Seoul로 보인다.
3. **채널 구성 규칙은 `NotificationSetup`과 같다.** Slack은 켜져 있고 Webhook이 `https://hooks.slack.com/services/`로 시작할 때만, 카카오톡은 켜져 있고 ME이며 `rest-api-key`·`refresh-token`·`link-url`이 있을 때만 보낸다. BIZ는 보내지 않는다(D-033). 액세스 토큰은 만료 시각을 모르므로 보내기 전에 갱신한다. 못 보내는 경우는 결과에 "보내지 않음"과 이유(꺼짐, URL 형식, 빈 값, BIZ, 링크 형식)를 보이고 HTTP를 부르지 않는다. 이유 문구는 "실전 엔진·감시도 이 채널을 끕니다"를 함께 말한다.
4. **카카오 토큰은 실전 엔진·감시와 같은 파일을 쓴다.** `<설정 폴더>/notification-state/kakao-token.json`(D-100 6번)을 같은 `FileKakaoTokenStore`로 읽고 쓴다. 다른 프로세스가 갱신한 더 늦게 만료되는 토큰이 있으면 그것으로 보내고, 시험 중 갱신한 토큰(카카오가 리프레시 토큰을 새로 주면 그것까지)은 파일에 남겨 엔진·감시가 이어 쓴다.
5. **결과와 로그에는 사유 코드와 설명만.** 채널마다 `{channel, label, status(SENT·FAILED·SKIPPED), detail, message}`. `detail`은 `SendResult`의 짧은 사유 코드(`http-404`, `timeout`, `kakao-refresh-rejected` 등, 비밀값 없음), `message`는 서버가 만든 한국어 설명이다(리프레시 토큰 거절이면 `RuntimeConfig.Notification.KAKAO_RECONSENT_HELP`). 로그는 `알림 시험(UI): slack=SENT(ok), kakao=FAILED(timeout)` 한 줄뿐이다.
6. **겹침·연타 막기.** 한 번에 하나만 보낸다(보내는 중이면 409 `TEST_RUNNING`). 시험이 끝난 뒤 10초 안에 다시 누르면 429 `TEST_TOO_SOON`. 화면은 보내는 동안 버튼을 끈다. 한 번 시험은 HTTP 최대 Slack 1번(10초 기한) + 카카오 3번(갱신 1번·발송 2번, 각 10초 기한)이라 요청이 최대 약 40초 걸린다.
7. **127.0.0.1 전용.** 다른 변경 요청과 같이 `LocalOnlyRequestFilter`가 Host 루프백·Origin·`X-CSRF-Token`을 검사한다(CSRF 토큰 없으면 403, 시험으로 확인).

## 대안

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 채널마다 직접 부름(채택) | 채널별 성공·실패를 모두 보임. 알림 모듈 무변경 | 채널 구성 규칙을 실험실이 한 번 더 씀(`NotificationSetup`과 어긋날 수 있음) | **채택**. 어긋남은 아래 변경 제안으로 줄인다 |
| B. `NotificationSetup.start()`로 큐를 띄워 넣음 | 실전 엔진·감시와 완전히 같은 경로 | 첫 성공에서 멈춰 채널별 결과를 못 보임. 큐는 비동기라 결과를 화면에 돌려줄 수 없음. 10분 억제가 연속 시험을 삼킴 | 버림 |
| C. 알림 모듈에 "채널별 시험" 공개 메서드를 더함 | 구성 규칙이 한 곳 | `autotrade-notification-jar`는 보호 경로라 실전 관련 PR이 되고, 같은 때 "알림 큐 QA 후속" 작업이 이 모듈을 고침 | 이번엔 버림. 변경 제안으로 남김 |
| T1. 카카오 토큰 파일 공유(채택) | 시험 중 리프레시 토큰이 바뀌어도 엔진·감시가 새 토큰을 받음 | 실험실이 실전 엔진이 읽는 파일을 씀 | **채택**. 쓰기는 엔진·감시와 같은 원자적 이동·출처 해시 규칙이고, 출처가 다르면 엔진은 파일을 쓰지 않는다 |
| T2. 실험실은 메모리에만 갱신 토큰을 둠 | 실험실이 엔진 파일을 쓰지 않음 | 카카오가 갱신 때 리프레시 토큰을 새로 주면(만료 1개월 미만일 때) 그 토큰을 잃고, 옛 토큰이 계속 유효한지는 확인 못 함 `[가정]` → 엔진·감시 카카오 알림이 끊길 수 있음 | 버림 |
| S1. 저장된 값으로만 시험(채택) | 비밀값이 이 요청으로 오가지 않음, 실전 엔진이 다시 떴을 때 쓸 값과 같음 | 입력하고 저장 전에는 시험 못 함 | **채택** |
| S2. 입력 중인 값으로 시험 | 저장 전 확인 | 비밀값이 새 경로로 오감, 저장 값과 시험 값이 달라 혼동 | 버림 |

되돌릴 조건: 변경 제안이 반영돼 알림 모듈이 채널별 시험을 제공하면 `NotificationTester`의 채널 구성 부분을 그것으로 바꾼다.

## 변경 제안 (알림 모듈, 다음 알림 PR에서 검토)

- `NotificationSetup`에 채널 목록을 돌려주는 공개 메서드(예: `List<Channel> externalChannels(Path configDir, Clock clock)` 또는 채널별 시험 `Map<String, SendResult> testEach(...)`)를 두어 실험실이 구성 규칙(Webhook 접두사, ME·BIZ, 빈 값, 액세스 토큰 자리 값)을 다시 쓰지 않게 한다. 실험실 `NotificationTester`가 지금 같은 규칙을 따로 갖고 있다(`autotrade-app-lab/.../settings/NotificationTester.java`).
- 채널을 못 만든 이유를 짧은 코드로 돌려주면(지금은 ERROR 로그만) 실험실 설명과 엔진 로그가 같은 이유를 말한다.

## 위험과 한계

- 실험실은 저장된 값을 쓰고, 실전 엔진·감시는 기동 때 읽은 값을 쓴다. 화면에서 바꾸고 엔진을 다시 띄우지 않았으면 시험 성공이 엔진 알림 성공을 뜻하지 않는다. 화면 안내 문구에 "실전 엔진·감시는 다시 띄워야 바뀐 값을 씁니다"를 적는다.
- 리프레시 토큰을 화면에서 바꾸고 엔진을 다시 띄우지 않으면 실험실과 엔진의 토큰 파일 출처가 달라 서로의 갱신 토큰을 쓰지 않는다. 엔진은 자기 메모리 토큰으로 계속 보내므로 엔진 알림은 그대로다 `[추론]`(`FileKakaoTokenStore.latest`가 출처가 다른 파일을 무시).
- 시험이 리프레시 토큰 거절(400·401)을 만나면 실험실 결과에만 재동의 안내가 나오고, 엔진·감시의 "재동의 필요" 긴급 알림은 엔진·감시가 직접 거절을 만날 때 나간다(거절 기억은 프로세스 메모리).
- 카카오 "나에게 보내기"의 일일 호출 한도는 확인하지 않았다 `[가정]`. 10초 간격 제한과 버튼 누름에만 보내는 것으로 충분하다고 본다.
- 이 요청은 Tomcat 요청 스레드에서 최대 약 40초 막힌다. 실험실은 실전 엔진과 다른 프로세스라 실전 매매에는 영향이 없다(절대 규칙 6).

## 검증

- `NotificationTesterTest`: 꺼진 채널 HTTP 없음, 두 채널 따로 보냄(Slack이 받아도 카카오 보냄), Slack 404·카카오 갱신 시간 초과 설명, Slack URL 형식·BIZ·빈 값·링크 형식 건너뜀, 액세스 토큰 없을 때 갱신 후 토큰 파일 기록, 다른 프로세스 토큰 파일 사용, 리프레시 거절 재동의 안내, 중단 시 남은 채널 안 보냄, 결과에 비밀값 없음.
- `NotificationTestControllerTest`: 10초 안 재요청 429, 보내는 중 409, 예외 뒤에도 다음 시험 가능.
- `SettingsControllerTest`: CSRF 토큰 없으면 403, 꺼진 채널 SKIPPED, 응답·로그에 비밀값 없음, 바로 다시 누르면 429.
- 화면 시험 `autotrade-ui/e2e/tests/03-settings-notification-test.spec.mjs`: 버튼 → 채널별 "보내지 않음" 표, 바로 다시 누르면 거절 안내(외부로 보내지 않음, 시험용 설정 폴더는 알림이 꺼져 있음).
- 실제 Slack·카카오로 보내는 확인은 사용자 PC에서 키를 넣은 뒤 한다(이 컨테이너에는 키가 없다).

## 반영 기록

- PR #135 architect A1: 새 API와 오류 코드(`TEST_RUNNING` 409, `TEST_TOO_SOON` 429)를 ADR-005 5장(공통 오류·API 표)과 6장(설정 화면 표)에 올림.
- PR #135 risk-ops K1·K2: 갱신 거절·시간 초과 때 토큰 파일을 만들지 않음, 카카오가 새 리프레시 토큰을 주면 파일에 남겨 엔진·감시가 같은 출처로 읽음을 실험실 시험으로 단언(`NotificationTesterTest`).
- architect A2·risk-ops K4(채널 구성 규칙 중복)는 위 "변경 제안"을 다음 알림 모듈 PR에서 다룬다. risk-ops K3(리프레시 토큰 회전 뒤 옛 토큰 무효 여부 `[가정]`)는 7단계 실전 활성화 전 운영 점검에 "카카오 재동의 알림 수신 확인"으로 넘긴다.
- 2026-10-16, 변경 제안 반영(architect A2·risk-ops K4, 결정 내용은 바뀌지 않아 새 번호를 쓰지 않음, 추천안 채택(자율)): 채널 구성 규칙을 알림 모듈 공개 메서드 `NotificationSetup.externalChannels(configDir, clock, http)` 하나로 합쳤다. Slack·카카오톡 순서로 `ExternalChannel(name, channel, skipReason)`을 돌려주고, 못 꾸린 채널은 이유 코드(`disabled`, `invalid-webhook-url`, `paid-mode`, `missing-values`, `invalid-values`, `NotificationSetup.SKIP_*`)를 담는다. 큐(`NotificationSetup.start`)도 이것으로 꾸리고 로그는 `start`만 남긴다. 실험실 `NotificationTester`는 엔진·감시와 같은 이름 옮기기(`NotificationTester.setup`)로 `NotificationSetup`을 만들어 이 메서드를 부르고, 이유 코드를 화면 설명으로 바꾸기만 한다(채널 구성 코드와 `NO_ACCESS_TOKEN` 사본 삭제). API 응답의 `detail` 코드와 화면 설명은 그대로다.
  - 대안 "채널별 시험 `testEach`를 알림 모듈에 둠"은 버렸다: 결과 화면 문구(한국어 설명, "실전 엔진·감시도 끕니다")와 중단 처리는 실험실 화면 몫이라 라이브러리에 둘 이유가 없고, 채널을 꾸리는 규칙만 같으면 "실험실 시험 성공 = 엔진 발송 가능"이 선다. 되돌릴 조건: 다른 화면·프로세스도 채널별 발송 결과가 필요해지면 그때 알림 모듈로 올린다.
  - 바뀐 점 하나: 카카오 빈 값(`rest-api-key`·`refresh-token`·`link-url`)을 채널 생성자 예외가 아니라 미리 검사해 `missing-values`로 가린다. 엔진·감시에서는 그 채널을 끄는 결과가 같고, 로그 문구만 "빈 값"과 "형식이 틀림"으로 나뉜다.
  - 시험: `NotificationSetupTest#외부_채널은_Slack_카카오_순서로_꾸리고_못_꾸린_채널은_이유_코드로_돌려준다`, `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다`, `NotificationTesterTest#알림_설정_값을_실전_엔진과_같은_이름으로_옮긴다`, `#못_꾸린_이유_코드마다_화면_설명이_있다`. 기존 `NotificationTesterTest`는 고치지 않고 통과한다.
- 2026-10-16, PR #138 검토 권고 반영(결정 내용은 바뀌지 않아 새 번호를 쓰지 않음, 추천안 채택(자율)). 사용자 문구·채널 구성 결과·API 응답은 그대로다.
  - architect R2: `ExternalChannel`은 이름이 `slack`·`kakao` 둘 중 하나이고 꾸린 채널의 `Channel.name()`과 같아야 만든다(아니면 `IllegalArgumentException`).
  - risk-ops R2: `NotificationSetup.start`가 Slack·카카오를 자리(`get(0)`·`get(1)`)가 아니라 이름으로 고른다. 목록 순서가 바뀌어도 긴급 1순위(Slack)가 뒤바뀌지 않는다.
  - QA R1: 못 꾸린 채널 로그(`logSkip`)에 `invalid-values` 분기를 따로 두고, 모르는 이유 코드는 카카오 문구로 떨어지지 않고 채널 이름과 코드를 적는다.
  - architect R3: 실험실 결과의 채널 화면 이름(`NotificationTester.label`)을 `slack`→"Slack", `kakao`→"카카오톡"으로 정하고, 모르는 채널은 이름을 그대로 보인다(예전에는 카카오가 아니면 모두 "Slack").
  - architect R1: 같은 설정이면 실전 엔진·감시·실험실의 설정 옮기기(`EngineMain.setup`, `WatchdogMain.setup`, `NotificationTester.setup`)가 같은 `NotificationSetup`을 만드는지 `autotrade-architecture-test`의 `NotificationSetupMappingTest`가 설정 5가지로 직접 비교한다. 세 `setup`은 패키지 전용 그대로 두고, 시험 소스의 같은 패키지 다리 클래스(`EngineNotificationMapping`, `WatchdogNotificationMapping`, `LabNotificationMapping`)로 부른다. 대안 "세 `setup`을 public으로"는 운영 코드의 공개 범위를 시험 때문에 넓혀 버렸고, "리플렉션"은 실전 경로 리플렉션 금지(D-086) 취지와 맞지 않아 버렸다. 되돌릴 조건: 세 옮기기를 한 모듈로 모을 곳이 생기면 이 시험은 그 모듈 시험으로 바꾼다.
  - QA Q1·Q2: `NotificationTesterTest`가 결과 `label`·보낸 뒤 설명을 단언(변이 M6), `NotificationSetupTest`가 `start`의 못 꾸린 채널 로그를 이유마다 등급·문구까지 단언하고 꺼진 채널·정상 채널은 로그가 없음을 본다(변이 M7·M14).
