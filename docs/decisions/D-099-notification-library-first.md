# D-099 알림 라이브러리 1차(MON-06): 매핑표, 무료 채널 둘, 대체 발송

- 날짜: 2026-10-16
- 상태: 확정 · **추천안 채택(자율)**(지침 8장). `autotrade-notification-jar`(보호 경로)와 `ModuleOwnershipTest` 자리 모듈 표(보호 테스트 고정 값)를 바꾸므로 실전 관련 PR로 다룬다(architect·risk-ops 검토, 독립 QA, 사용자 머지).
- 담당: risk-ops(+architect). 교차 검토: architect(`docs/reviews/pr-<번호>/architect.md`), risk-ops(`docs/reviews/pr-<번호>/risk-ops.md`)
- 근거: 기획서 MON-06, [D-033](D-033-unpriced-source-default-off.md)(알림 기본 꺼짐, 알림톡 BIZ 안 씀), [D-086](D-086-real-path-reflection-ban.md)(실전 경로 JDK·외부 jar 허용 목록), [D-092](D-092-watchdog-heartbeat-and-stop-requests.md) 범위 밖 표(알림은 MON-06), ADR-005 7장 표(마지막 알림 채널 경고), 공식 문서(아래 "외부 사실")

## 결정

1. **범위는 라이브러리까지**. `com.autotrade.notification`에 이벤트 매핑표, 채널 셋, 대체 발송기를 만든다. 실전 엔진·감시·실험실에 잇는 일(설정 키 추가, 어느 이벤트를 언제 보낼지, 비동기 큐, 폭주 억제)은 이번 범위 밖이다(아래 후속 표). 그래서 이번 PR로 실제로 나가는 알림은 없다.
2. **매핑표**(`EventRoutes`): 긴급 = `error`·`stop-loss`·`risk-limit`·`forced-shutdown`, 정보 = `take-profit`·`market-open-cash`·`market-close-summary`(기획서 MON-06 그대로). **표에 없는 종류는 긴급**으로 보낸다(새 이벤트가 표 갱신 전에 조용히 묻히지 않게).
3. **채널**: Slack Incoming Webhook(`SlackWebhookChannel`), 카카오톡 나에게 보내기(`KakaoMemoChannel`), 마지막 대체인 로그(`LogChannel`, `System.Logger`, 긴급 ERROR·정보 INFO). 유료 알림톡(BIZ)은 만들지 않는다(설정 `notification.kakao.mode=BIZ`는 연결 PR에서 거부하거나 과금 질문으로 멈춘다).
4. **순서와 대체 발송**(`Notifier.standard`): 긴급은 Slack → 카카오톡 → 로그, 정보는 카카오톡 → Slack → 로그. 설정되지 않은 채널은 빠지고 로그는 늘 마지막이다. 한 채널이 실패하면(HTTP 오류, 시간 초과, 런타임 예외, 결과 없음) 다음 채널로 넘기고, 받은 채널에서 멈춘다. 채널 안 재시도는 없다(한 번 실패하면 다음 채널이 더 빠르다).
5. **결과 보고**(`DispatchReport`): 시도한 채널과 짧은 사유 코드(`http-500`, `timeout`, `kakao-refresh-rejected` 등). `delivered()`는 **로그가 아닌 외부 채널이 받았을 때만** 참이다. 로그로만 남았으면 거짓이라 연결하는 쪽이 "긴급 채널 없음·전달 실패"를 이상으로 다룰 수 있다(ADR-005 표). `Notifier.hasExternalChannel`로 기동 때 외부 채널 유무도 본다. `dispatch`는 예외를 던지지 않고, 스레드가 중단되면 인터럽트 표시를 되살리고 남은 외부 채널을 건너뛰되 로그 채널에는 남긴다(PR #127 risk-ops R2).
6. **카카오 토큰 자동 갱신**: 액세스 토큰 만료 5분 전부터는 보내기 전에 갱신한다. 만료 시각을 모르면(설정에서 처음 읽은 토큰) `Instant.EPOCH`로 두어 첫 발송 전에 갱신한다. 미리 갱신이 일시 장애(거절이 아닌 실패)로 끝나도 아직 만료 전이면 지금 토큰으로 보낸다(PR #127 architect R1). 발송이 HTTP 401이면 이번 발송에서 아직 갱신을 시도하지 않았을 때만 한 번 갱신하고 한 번 다시 보낸다(미리 갱신이 실패했어도 시도로 센다. 한 발송에 갱신 시도는 최대 한 번, HTTP는 최대 세 번, PR #127 risk-ops R3·R5, architect F2). 갱신 응답이 400·401이면 리프레시 토큰이 만료·폐기된 것으로 보고 `kakao-refresh-rejected`(사용자가 다시 동의해 토큰을 받아야 함)로 끝낸다. 새 리프레시 토큰이 오면 `KakaoTokenStore`에 남기고, 저장이 실패하면 WARN 로그(값 없이)를 남기고 보내기는 이어 간다. 여러 스레드가 불러도 토큰 상태가 꼬이지 않게 카카오 채널은 직렬로 돈다.
7. **비밀값**: Webhook URL·토큰·REST API 키·클라이언트 시크릿은 결과·로그·예외 문구·`toString`에 나오지 않는다. 사유 코드는 상태 번호와 고정 문자열만 쓰고 예외 문구·응답 본문을 옮기지 않는다(JDK 예외 문구에 URI가 들어갈 수 있다). Slack URL은 `https://hooks.slack.com/services/`로 시작해야 받는다(오타·잘못된 붙여넣기로 알림이 다른 호스트로 새지 않게). HTTP는 리다이렉트를 따르지 않고, 연결 3초·응답 본문까지 포함한 요청 전체 10초 제한(넘기면 요청을 취소하고 `timeout`), 응답 본문은 앞 8KB만 모은다(시간 값은 `[가정]`, PR #127 architect F1).
8. **실전 경로 규칙 안에서**: 이 모듈은 실전 엔진·감시 런타임 클래스패스에 이미 들어 있어(`RealPathReflectionTest.REQUIRED_PROJECTS`) D-086 허용 목록 안 타입만 쓴다. HTTP는 JDK `java.net.http`(이미 허용 패키지), JSON은 이미 허용된 Jackson 타입(`ObjectMapper`·`JsonNode`·`ObjectNode`)만. 새 외부 jar나 허용 목록 변경은 없다. 클래스가 생겨 `ModuleOwnershipTest.NO_CLASSES_YET`에서 이 모듈을 뺀다.

## 외부 사실(2026-10-16 확인)

- `[사실]` 카카오 나에게 보내기: `POST https://kapi.kakao.com/v2/api/talk/memo/default/send`, `Authorization: Bearer`, `Content-Type: application/x-www-form-urlencoded;charset=utf-8`, 본문 `template_object`(JSON), 성공 시 `result_code` 0, 동의항목 `talk_message` 필요, 본인 나와의 채팅방으로만 감. 출처: [카카오톡 메시지 REST API](https://developers.kakao.com/docs/latest/ko/kakaotalk-message/rest-api)
- `[사실]` 텍스트 템플릿: `text` 최대 200자, `link` 필수이며 `web_url`·`mobile_web_url` 등 하나 이상, URL은 앱에 등록한 웹 도메인과 일치. 출처: [기본 템플릿](https://developers.kakao.com/docs/latest/ko/message-template/default)
- `[사실]` 토큰 갱신: `POST https://kauth.kakao.com/oauth/token`, `grant_type=refresh_token`, `client_id`(REST API 키), `refresh_token`, 클라이언트 시크릿을 켰으면 `client_secret`. 응답 `access_token`·`expires_in`은 늘, `refresh_token`·`refresh_token_expires_in`은 리프레시 토큰 남은 기간이 1개월 미만일 때만. 토큰 정보 -401(HTTP 401)은 토큰이 잘못됐거나 만료, 갱신 필요. 출처: [카카오 로그인 REST API](https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api)
- `[가정]` 액세스·리프레시 토큰 유효기간: 문서 예시 응답은 43199초(약 12시간)·5184000초(60일)이나 보장 값으로 적혀 있지 않다. 코드는 응답의 `expires_in`만 쓴다.
- `[사실]` Slack Incoming Webhook 성공 응답은 HTTP 200·본문 `ok`. 출처: [Slack Python SDK Webhook 문서](https://docs.slack.dev/tools/python-slack-sdk/webhook/). 오류 본문 종류와 Webhook 호출 한도는 이 문서에 없어 `[가정]`이며, 코드는 200·`ok`가 아니면 모두 실패로 본다.
- `[사실, 사용자 문서 D-033]` 두 채널 모두 무료 사용 범위다. 카카오 나에게 보내기의 일·월 제공량은 문서 본문에 수치가 없다 `[가정]`(연결 PR에서 폭주 억제로 다룬다).

## 대안

| 대안 | 판단 |
|---|---|
| ① 라이브러리만, 연결은 다음 PR(채택) | 이 PR은 보호 테스트 고정 값 한 줄만 바꾼다. 엔진·감시·설정 키(`SecretPaths` 지문)를 같이 바꾸면 실전 관련 검토 범위가 커지고, 다른 병행 스레드와 파일이 겹친다 |
| ② 감시 프로세스 엔진 멈춤 알림까지 한 번에 | 7단계 조건(PR #116 risk-ops R2)을 바로 닫지만, 설정 키·토큰 저장 위치·비동기 큐·폭주 억제를 함께 정해야 한다. 버림(후속 1번) |
| ③ JSON을 손으로 만들고 파싱(외부 의존 없음, monitor-agent-jar 방식) | 토큰 응답 파싱을 손으로 하면 틀리기 쉽다. Jackson은 이미 실전 경로 허용 목록에 있어 새 위험이 없다. 버림 |
| ④ 채널 안에서 재시도(지수 백오프) | 긴급 알림이 한 채널에 묶여 늦어진다. 다른 채널로 넘기는 편이 빠르다. 버림 |
| ⑤ 알림톡(BIZ) | 유료(과금 멈춤 조건), D-033·MON-06에서 쓰지 않기로 함. 버림 |

되돌릴 조건: 카카오가 나에게 보내기를 유료화하거나 제공량이 운영에 모자라면 그 채널을 끄고 Slack·로그만 쓴다. 실제 운영에서 채널 하나가 짧게 끊기는 일이 잦으면 채널 안 1회 재시도를 다시 본다.

## 위험과 한계

- 이번 PR만으로는 아무 알림도 나가지 않는다. 실전 엔진 멈춤(장애표 #8)은 여전히 감시 로그 ERROR뿐이다(7단계 전 조건 유지).
- `dispatch`는 막는 호출이다(HTTP 한 번 최대 약 10초, 카카오는 갱신 한 번과 발송 두 번까지라 최대 약 30초). 주문·시세 스레드에서 직접 부르면 실전 매매가 알림에 묶인다(절대 규칙 6). 연결 PR에서 별도 스레드 큐로 감싸야 한다.
- 같은 이벤트가 짧은 시간에 쏟아지면 그대로 다 보낸다(폭주 억제 없음). 카카오·Slack 한도에 걸리면 그 사이 알림이 대체 채널로 밀린다.
- 카카오 리프레시 토큰은 남은 기간 1개월 미만일 때만 새로 오고, 새 토큰을 남기지 못한 채 재기동하면 이전 토큰으로 갱신이 실패할 수 있다(옛 리프레시 토큰이 언제 무효가 되는지는 문서에 없음 `[가정]`). 저장 실패 WARN으로만 알린다.
- 카카오 메시지 링크는 앱에 등록한 웹 도메인이어야 한다. `http://localhost`를 등록할 수 있는지는 확인하지 못함 `[가정]`(연결 PR의 사용자 설정 절차에서 확인).
- Webhook·카카오 실제 호출은 시험하지 않았다(키 없음). 가짜 HTTP와 로컬 HTTP 서버로만 확인했다.

## 검증

- `NotifierTest#Slack_발송이_실패하면_긴급_알림은_카카오톡으로_넘어간다`(기획서 MON-06 "Slack 실패 시 대체 채널 테스트"), 예외·결과 없음·모두 실패 시 로그·중단 처리, 등급별 순서.
- `KakaoMemoChannelTest#토큰_만료를_강제하면_보내기_전에_갱신하고_새_토큰으로_보낸다`(기획서 MON-06 "토큰 만료 강제 시 갱신 테스트"), 만료 5분 경계, 401 뒤 한 번 갱신·재발송, 갱신 거절·이상 응답·장애, 새 리프레시 토큰 저장·저장 실패, 200자 자르기.
- `SlackWebhookChannelTest`: 요청 형식, 실패 코드에 비밀값이 없음, 허용 밖 URL 거부(문구에 URL 없음).
- `JdkHttpTransportTest`: 로컬 HTTP 서버로 8KB 상한과 리다이렉트를 따르지 않음.
- `EventRoutesTest`: 매핑표 7종, 모르는 종류 긴급.
- 보호 테스트: `RealPathReflectionTest`·`ArchitectureTest`(도메인 모름)·`ModuleOwnershipTest`가 새 클래스를 검사해 통과.

## 후속(연결 PR, 이번 범위 밖)

| 후속 | 담당 | 언제 |
|---|---|---|
| 1. 감시 프로세스: 엔진·게이트웨이 멈춤(장애표 #8), 정지 요청 생성, 자원 값 결측 지속(D-096 되돌릴 조건)을 알림으로. 별도 스레드 큐와 같은 종류 폭주 억제 | risk-ops | 7단계 실전 활성화 전 |
| 2. 설정 키 변경 제안(architect 결정, `SecretPaths` 지문이 바뀌므로 실전 관련): `notification.kakao.rest-api-key` 🔒, `notification.kakao.refresh-token` 🔒, `notification.kakao.client-secret` 🔒(선택), `notification.kakao.link-url`(일반). 기존 `notification.kakao.enabled=true`면 access-token 필수 조건은 refresh-token·rest-api-key 필수로 바꾼다. 갱신된 토큰 저장 위치(`secrets.json` 원자적 다시 쓰기 또는 설정 폴더 별도 파일)도 그 PR에서 정한다 | architect, risk-ops | 1번과 함께 |
| 3. 실전 엔진의 손절·한도·강제 종료 이벤트와 장 시작 예수금·장 마감 결산 연결 | risk-ops | 7단계 엔진 주문 경로 PR |
| 4. 설정 화면의 "알림 시험 보내기"와 마지막 채널 끄기 경고(ADR-005 표) | ui-dev | 2번 뒤 |
| 5. 검토 권고: 부르는 쪽이 매핑표와 다른 등급의 `Notification`을 직접 만들 수 있음(risk-ops R4, 연결 PR에서 `EventRoutes`로만 만들게), 로그 채널을 이름 `log`로 가림(architect R2), 알림 시각이 UTC(architect R3, 표시 시간대를 받게) | risk-ops, architect | 1번과 함께 |
