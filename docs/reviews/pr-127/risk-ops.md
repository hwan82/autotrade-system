# risk-ops 검토: 알림 라이브러리 1차(MON-06, D-099)
- 대상: PR #127 / 브랜치 `claude/project-thread-othqxw` / 커밋 895b98a / main(f724dee) 대비 diff `git diff origin/main...HEAD` 전체(29개 파일, +1522 −12)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew --max-workers=2 --offline build` → BUILD SUCCESSFUL(종료 코드 0)
  - `./gradlew --max-workers=2 --offline :autotrade-notification-jar:test :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-app-watchdog:test --rerun-tasks` → BUILD SUCCESSFUL. JUnit XML 집계(테스트/실패/오류/건너뜀): notification-jar 31/0/0/0, risk 55/0/0/0, architecture-test 44/0/0/0, app-engine 52/0/0/0, app-watchdog 40/0/0/0
  - 재현 실험(저장소 밖 스크래치, 소스 변경 없음): `JdkHttpTransport`에 로컬 `HttpServer`가 200 헤더를 보낸 뒤 본문을 1초에 1바이트씩 40바이트 흘려 보내게 했다. 결과 `status 200 len 40`, **경과 40.1초**(설계 상한 10초를 넘음). 아래 R1의 근거
  - 비밀값 스캔: diff에서 Slack URL은 `TPLACEHOLDER/BPLACEHOLDER/SECRETPART` 자리표시자만, 토큰은 `ACCESS-NEW` 같은 가짜 값만

## 이 PR의 실전 경로 영향 범위(판정의 전제)
- [사실] 이 PR이 만든 `com.autotrade.notification` 클래스를 운영 코드에서 부르는 곳이 없다. `grep -rln com.autotrade.notification --include=*.java`의 notification-jar 밖 결과는 `autotrade-architecture-test`의 시험 3개뿐이다.
- [사실] 다만 이 모듈은 이미 실전 엔진·감시 런타임 클래스패스에 있다: `autotrade-app-engine/build.gradle.kts:26`, `autotrade-app-watchdog/build.gradle.kts:21`, `RealPathReflectionTest.REQUIRED_PROJECTS`(RealPathReflectionTest.java:88-92), `REAL_PATH_PACKAGES`(같은 파일 :139). 그래서 실전 경로 규칙(D-086 허용 목록, 리플렉션 금지)을 이번 PR부터 받는다.
- [사실] 보호 경로 `autotrade-notification-jar/*`(scripts/ci/protected-paths.txt:16, .github/CODEOWNERS:13)와 보호 테스트 고정 값 `ModuleOwnershipTest.NO_CLASSES_YET`을 바꾸므로 실전 관련 PR이다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·Guard 팩토리·설정 로딩 파일이 diff에 없다(`git diff origin/main...HEAD --stat`). 알림 모듈은 `com.autotrade.risk`·`broker`에 의존하지 않는다(ArchitectureTest.java:114-120 `REUSABLE_JARS_ARE_DOMAIN_FREE` 통과). 기존 Guard 보호 테스트는 `:autotrade-risk:test` 55건, `:autotrade-architecture-test:test` 44건 재실행 통과 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음. `ArchitectureTest.java` diff 없음. 새 클래스는 주문 타입을 참조하지 않는다(ArchitectureTest `REUSABLE_JARS_ARE_DOMAIN_FREE`가 `com.autotrade.broker..` 의존을 금지하고 통과) |
| A3 | 해당 없음 | 증권사 주문 연동 코드 변경 없음. 알림 모듈의 HTTP 대상은 `hooks.slack.com`(SlackWebhookChannel.java:20,44-46)과 카카오 상수 URL(KakaoMemoChannel.java:36-37)뿐 |
| A4 | 해당 없음 | 주문 타임아웃 처리 코드 변경 없음. (알림 HTTP의 시간 제한 문제는 주문 경로가 아니라 R1로 따로 적는다) |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음. 알림 모듈은 주문을 만들 수 없다(도메인·브로커 의존 금지 규칙 통과) |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 코드 변경 없음. `:autotrade-app-engine:test` 52건 재실행 통과 |
| A8 | 통과 | 이번 PR에서 엔진·감시가 알림을 부르지 않으므로 지금 실전 엔진이 알림 장애(HTTP 멈춤)에 묶일 수 없다(위 "영향 범위"의 grep 결과). 새 의존은 Jackson(`autotrade-notification-jar/build.gradle.kts`)뿐이고 이미 실전 경로 허용 외부 jar이며, `RealPathReflectionTest#실전_경로_런타임_구성은_고정_목록과_같다`가 구성 고정 목록 변경 없이 통과했다. 사용 JDK 패키지 `java.net.http`·`java.util.regex`·`System.Logger`는 이미 허용 목록에 있다(RealPathReflectionTest.java:270,273,300). 단, 연결 PR에서 그대로 쓰면 깨질 전제가 R1에 있다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 시험 픽스처는 자리표시자만(SlackWebhookChannelTest·NotifierTest의 `TPLACEHOLDER/BPLACEHOLDER/SECRETPART`, KakaoMemoChannelTest의 `ACCESS-NEW` 등). 운영 코드는 비밀값을 `toString`에서 가린다(KakaoMemoChannel.java:201-203, KakaoToken.java:44-47, SlackWebhookChannel.java:77-80). 실패 사유는 상태 번호·고정 문자열만(Failures.java:11-13, SlackWebhookChannel.java:69, KakaoMemoChannel.java:123,141-147). 잘못된 URL 거부 문구에 URL이 없다(SlackWebhookChannel.java:42,46). 시험 `SlackWebhookChannelTest#실패는_짧은_코드로만_돌려주고_비밀값을_담지_않는다`, `#hooks_slack_com_https가_아닌_URL은_URL을_문구에_넣지_않고_거부한다`, `KakaoMemoChannelTest#비밀값은_toString에_나오지_않고_잘못된_설정은_거부한다` 통과. 저장 실패 WARN 로그에 값이 없다(KakaoMemoChannel.java:162-163) |
| A11 | 실패 | 보호 테스트 삭제·비활성화·약화 없음: `@Disabled` 없음, 보호 테스트 변경은 `ModuleOwnershipTest` 2곳뿐이다. `NO_CLASSES_YET`을 비운 것(ModuleOwnershipTest.java:131)과 `classlessModules()` 기대값에서 notification-jar를 뺀 것(:406)은 검사를 **좁히지 않고 넓힌다**: 이제 notification-jar도 :175의 "실제로 본 프로젝트" 집합에 들어가야 통과한다. 대상 코드 변경(클래스 15개 추가)도 검토했다. **실패 사유**: 새 동작 중 `JdkHttpTransport`의 "요청 전체 10초 제한"(JdkHttpTransport.java:15,22,37)에 시험이 없고, 실제로 지켜지지 않는다(R1, 재현 40.1초). `JdkHttpTransportTest`는 8KB 상한과 리다이렉트 두 가지만 본다(JdkHttpTransportTest.java:37,55). 실패하는 경우(응답 지연·본문 멈춤)를 포함한 시험이 빠졌다 |
| A12 | 통과 | 지금 장애표 6장 #1~#17과 O1~O8의 감지·조치는 바뀌지 않는다(알림이 아직 어디에도 연결되지 않음). D-099 "위험과 한계"가 장애표 #8이 여전히 감시 ERROR 로그뿐임을 적었다(D-099 문서). 장애 테스트 8종의 7번(토큰 만료 강제 도래)은 증권사 토큰 대상이라 이 PR과 무관하다. 단, 대응표 #2·#16·#17의 "Slack 긴급"을 이 라이브러리로 이을 때 R1이 대체 발송 전제를 깬다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 알림 시각은 `Instant`(UTC ISO-8601) 표시만이고 계산에 쓰지 않는다(Notification.java:32-35). 카카오 만료 판단은 주입 `Clock`(KakaoMemoChannel.java:96,158) |
| A14 | 실패 | D-099 결정 4("시간 초과면 다음 채널로"), 결정 7("연결 3초·요청 10초 제한"), "위험과 한계"("채널당 최대 약 10초, 카카오는 갱신 포함 최대 약 30초")와 ADR-001 표·`Notifier` Javadoc(Notifier.java:15-16)이 코드 동작과 다르다(R1). 결정 6의 "그래도 발송이 HTTP 401이면 한 번만 갱신"도 만료 직전 선제 갱신 뒤 401이 오면 한 발송에서 갱신이 두 번 일어나는 코드(KakaoMemoChannel.java:96-111)와 문구가 어긋난다(R3, 권고 수준). 그 밖의 기획서 MON-06 행, 결정 로그 README 행, config-keys 변경 제안 문구는 코드와 일치한다 |

## 점검표 밖 발견

### R1 (수정 필요) HTTP 시간 제한이 응답 본문 읽기에 걸리지 않아 알림 한 건이 무한정 막힐 수 있다
- [사실] `JdkHttpTransport.post`는 `HttpRequest.timeout(10초)`과 `BodyHandlers.ofInputStream()` 뒤 `in.readNBytes(8KB)`로 본문을 읽는다(JdkHttpTransport.java:36-44). JDK의 요청 시간 제한은 응답 헤더를 받을 때까지만 적용되고, 그 뒤 본문 읽기는 제한이 없다.
- [사실] 재현: 헤더 200을 바로 보내고 본문을 1초에 1바이트씩 40바이트 보내는 로컬 서버에 `post` → 40.1초 뒤 정상 반환(위 "직접 실행한 것"). 본문을 아예 보내지 않고 연결만 유지하면 끝없이 막힌다고 본다 `[추론]`(같은 경로, 상한 없음).
- 영향:
  1. D-099 결정 4의 핵심인 "시간 초과면 다음 채널로"가 응답 헤더 뒤 멈춤에는 동작하지 않는다. 긴급 알림이 Slack에서 멈추면 카카오·로그 대체로 넘어가지 못하고, 결과 보고(`DispatchReport`)도 돌아오지 않아 "전달 실패"를 이상으로 다룰 수 없다.
  2. `KakaoMemoChannel.send`는 `synchronized`(KakaoMemoChannel.java:95)라 카카오 응답 한 건이 멈추면 그 채널을 부르는 모든 스레드(긴급의 2순위, 정보의 1순위)가 같이 멈춘다.
  3. 연결 PR(D-099 후속 1~3)은 문서의 "채널당 최대 약 10초"를 근거로 큐 크기·폭주 억제를 설계하게 된다. 감시 프로세스가 알림 큐를 하트비트·정지 요청과 같은 스레드에서 돌리면 장애표 #8·#10 감지가 같이 멈출 수 있다 `[추론]`.
- 고칠 것(제안, 방식은 구현자 선택):
  - 본문 읽기까지 포함한 전체 상한을 코드로 보장한다. 예: `client.sendAsync(...)`에 `orTimeout`/`get(전체 상한)`을 걸고 초과 시 `cancel(true)` 후 `HttpTimeoutException`을 던지기, 또는 본문 읽기를 남은 시간 안에서만 하고 넘으면 스트림을 닫기.
  - `JdkHttpTransportTest`에 실패 시험을 추가한다: (a) 헤더 뒤 본문을 보내지 않는 서버, (b) 본문을 아주 느리게 흘리는 서버 → 상한(시험에서는 주입한 짧은 값) 안에 `HttpTimeoutException`(→ 사유 `timeout`)으로 끝나는지. 시험 속도를 위해 상한을 생성자나 패키지 전용 생성자로 받게 하는 것을 권한다.
  - D-099 결정 7·위험과 한계, ADR-001 표, Notifier Javadoc의 상한 문구를 고친 동작에 맞춘다.

### R2 (권고) 중단되면 로그 채널까지 건너뛴다
- [사실] `Notifier.dispatch`는 `InterruptedException`이면 남은 채널을 모두 건너뛰고 돌아온다(Notifier.java:91-94). 로그 채널도 건너뛰므로 종료 중에 생긴 긴급 알림(예: 강제 종료)이 프로세스 로그에도 남지 않는다. `LogChannel.send`는 막히지 않으므로(LogChannel.java:26-30), 중단돼도 마지막 로그 채널은 한 번 남기는 편이 "알림이 어디에도 남지 않는 일은 없게 한다"(LogChannel Javadoc)와 맞다. 연결 PR 전에 정하면 된다.
- [사실] 본문 읽기 중 인터럽트는 JDK가 `IOException`으로 바꿔 올리므로 `io-error`로 기록되고 인터럽트 표시가 선 채 다음 채널로 넘어간다(Failures.java:11-13). R1을 고칠 때 같이 정리할 것을 권한다.

### R3 (권고) 한 발송에서 카카오 갱신이 두 번 일어날 수 있다
- [사실] 만료 5분 전 선제 갱신(KakaoMemoChannel.java:96-101)에 성공한 뒤 발송이 401이면 다시 갱신한다(:104-111). 한 알림에 토큰 엔드포인트 호출 2번, 발송 2번, 최대 4번의 HTTP가 된다. 갱신 직후 401은 토큰 문제가 아니라 권한(동의항목) 문제일 가능성이 커 두 번째 갱신은 이득이 적다 `[추론]`. 코드를 "선제 갱신을 했으면 401 갱신은 하지 않음"으로 바꾸거나 D-099 결정 6 문구를 "선제 갱신과 별개로 401 때 한 번 더"로 고친다.

### R4 (권고, 연결 PR 몫) 등급을 부르는 쪽이 바꿀 수 있다
- [사실] `Notification`은 public 레코드라 `new Notification("stop-loss", Severity.INFO, ...)`처럼 매핑표와 다른 등급을 만들 수 있다(Notification.java:15-29). Javadoc은 "등급은 보내는 쪽이 고르지 않고 EventRoutes가 정한다"(:7)고 적었지만 강제하지 않는다. 손절 알림이 정보 등급으로 가면 Slack이 아니라 카카오가 먼저다. 연결 PR에서 `EventRoutes#notification`만 쓰도록 하거나, `Notifier`가 기본 표와 다른 알려진 종류의 등급을 거부·보정하는 시험을 둘 것.

### 확인한 것(문제 없음)
- 리다이렉트 비추종(JdkHttpTransport.java:29, `JdkHttpTransportTest#리다이렉트를_따르지_않는다`), Slack 호스트 고정(`hooks.slack.com.evil...` 같은 호스트는 `URI.getHost` 비교로 거부, SlackWebhookChannel.java:44-46), 사용자 정보·포트가 있는 URL 거부.
- 표에 없는 종류는 긴급(EventRoutes.java:69-72, `EventRoutesTest#표에_없는_종류는_긴급으로_보낸다`): 안전한 쪽 기본값.
- `delivered()`가 로그만 받은 경우를 전달로 치지 않음(DispatchReport.java:21-23, `NotifierTest#외부_채널이_모두_실패하면_로그로_남기고_전달되지_않음으로_보고한다`).
- 유료 알림톡(BIZ) 구현 없음(D-033 유지). 기존 설정 키 `notification.kakao.mode`(config-keys.md:25)는 이번에 바뀌지 않았다.
- 시세 게이트웨이(`autotrade-app-gateway`)는 이 모듈에 의존하지 않는다(build.gradle.kts grep 결과에 없음).

## 판정
수정 필요(A11, A14 — R1: `JdkHttpTransport` 전체 시간 상한을 본문 읽기까지 보장하고 응답 멈춤·느린 본문 실패 시험을 추가, D-099·ADR-001·Notifier Javadoc의 상한 문구를 동작과 일치시킬 것. R2~R4는 권고)

## 재검토 2026-10-16
- 대상: PR #127 / 커밋 8ee12d1 / main(f724dee) 대비 diff 전체. 1차 검토(895b98a) 뒤 바뀐 것: `git diff 895b98a 8ee12d1`(JdkHttpTransport, KakaoMemoChannel, Notifier, 시험 3개, D-099, 검토 원문 2개). 커밋 6513988의 이 파일 1차 내용이 내가 쓴 원문과 같은지 확인함(작업 트리 변경 없음)
- 점검표: A
- 직접 실행한 것:
  - `./gradlew --max-workers=2 --offline build` → BUILD SUCCESSFUL(종료 코드 0)
  - `./gradlew --max-workers=2 --offline :autotrade-notification-jar:test :autotrade-risk:test :autotrade-architecture-test:test :autotrade-app-engine:test :autotrade-app-watchdog:test --rerun-tasks` → BUILD SUCCESSFUL. JUnit XML 집계(테스트/실패/오류/건너뜀): notification-jar 37/0/0/0(1차 31에서 6건 늘어남), risk 55/0/0/0, architecture-test 44/0/0/0, app-engine 52/0/0/0, app-watchdog 40/0/0/0
  - 1차 R1 재현을 고친 코드에 다시 돌림(본문 1초에 1바이트씩 40바이트): `HttpTimeoutException`, **경과 10.03초**(1차 40.1초)
  - 카카오 갱신 횟수 재현(스크래치, 시험 클래스 `FakeHttp` 사용, 소스 변경 없음): 토큰 만료 60초 전, 응답 순서 503(갱신) → 401(발송) → 200(갱신) → 200(발송). 결과 `ok`, 요청 4건: `oauth/token`, `memo/default/send`, `oauth/token`, `memo/default/send`. 아래 R5의 근거

### 1차 지적 처리
| 지적 | 결과 | 근거 |
|---|---|---|
| R1 HTTP 기한이 본문 읽기에 안 걸림 | 해결 | JdkHttpTransport.java:56-72: `sendAsync` + `future.get(전체 기한)`, 넘으면 `cancel(true)` 뒤 `HttpTimeoutException`(→ Failures.java:12 `timeout`). 본문은 `CappedBody`가 8KB까지만 모으고 구독을 끊는다(:77-123). 실패 시험 `JdkHttpTransportTest#헤더_뒤_본문이_멈추면_기한_안에_시간_초과로_끝난다`, `#응답이_오지_않으면_기한_안에_시간_초과로_끝난다`, `#본문이_아주_느리게_와도_기한_안에_끝난다` 통과. 위 재현 10.03초. 예외 문구는 고정 문자열이고(:63,71) 채널은 문구를 쓰지 않는다. 새로 쓴 JDK 타입(`CompletableFuture`, `Flow`, `ByteBuffer`)은 허용 패키지 `java.util.concurrent`·`java.nio` 안이고 `RealPathReflectionTest` 통과 |
| R2 중단 때 로그도 건너뜀 | 해결 | Notifier.java: `InterruptedException`이면 남은 외부 채널은 건너뛰고 아직 시도하지 않은 `LogChannel`은 부른다. 시험 `NotifierTest#중단돼도_로그_채널에는_남긴다`(시도 `slack`, `log`, 카카오 미호출, ERROR 로그 1줄, `delivered()` 거짓) 통과. 본문 읽기 중 인터럽트는 이제 `future.get`의 `InterruptedException`으로 올라와 요청을 취소하고 그대로 던진다(JdkHttpTransport.java:64-66) |
| R3 한 발송에 갱신 두 번 | 부분 해결 | 미리 갱신에 **성공**한 뒤 401이면 다시 갱신하지 않는다(KakaoMemoChannel.java `refreshedNow`, 시험 `#미리_갱신한_토큰이_401이면_다시_갱신하지_않는다`). 그러나 같은 커밋에서 들어온 "미리 갱신이 일시 장애면 만료 전 토큰으로 보냄"(architect R1 반영)과 겹쳐, 미리 갱신이 **실패**한 뒤 401이면 다시 갱신한다. R5 |
| R4 등급을 부르는 쪽이 바꿀 수 있음 | 후속으로 기록 | D-099 후속 표 5번(연결 PR에서 `EventRoutes`로만 만들게). 이번 PR에서는 알림이 어디에도 연결되지 않아 받아들임 |

### 점검표 A 재판정
| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | 1차와 같음. 8ee12d1에도 Guard·설정 로딩 변경 없음(`git diff 895b98a 8ee12d1 --stat`). `:autotrade-risk:test` 55건 재실행 통과 |
| A2 | 해당 없음 | 주문 출구·ArchUnit 규칙 변경 없음. `ArchitectureTest#REUSABLE_JARS_ARE_DOMAIN_FREE`(ArchitectureTest.java:114-120) 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음. HTTP 대상은 그대로 Slack 고정 호스트와 카카오 상수 URL |
| A4 | 해당 없음 | 주문 타임아웃 처리 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음. 알림 모듈은 주문을 만들 수 없다(도메인·브로커 의존 금지 규칙 통과) |
| A7 | 해당 없음 | 주문 큐·토큰 버킷·킬스위치 변경 없음. `:autotrade-app-engine:test` 52건 통과 |
| A8 | 통과 | 엔진·감시는 여전히 알림을 부르지 않는다(`grep -rln com.autotrade.notification --include=*.java`의 모듈 밖 결과는 architecture-test 시험뿐). HTTP 한 번이 이제 전체 기한 안에 끝나 연결 PR의 전제가 성립한다(위 재현 10.03초). `RealPathReflectionTest#실전_경로_런타임_구성은_고정_목록과_같다` 통과, 새 외부 jar 없음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 시험 픽스처도 자리표시자뿐(`REST-KEY-PH`, `ACCESS-OLD`, `TPLACEHOLDER/...`). 시간 초과 예외 문구는 고정 문자열이고 시험이 `hasMessageNotContaining("127.0.0.1")`로 확인. 1차 근거(toString 가림, 사유 코드) 그대로 |
| A11 | 통과 | 보호 테스트 변경은 1차와 같은 `ModuleOwnershipTest` 2곳(검사를 넓히는 변경)뿐, `@Disabled` 없음. 1차 실패 사유였던 시간 제한에 실패 시험 3건이 생겼고 통과한다. 중단 시 로그, 미리 갱신 실패 시 처리, 미리 갱신 뒤 401에도 시험이 있다 |
| A12 | 통과 | 장애표 #1~#17, O1~O8, 장애 테스트 8종의 감지·조치는 바뀌지 않는다(알림 미연결). 대체 발송의 전제(시간 초과면 다음 채널)가 이제 응답 멈춤에도 성립한다 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 카카오 만료 판단은 주입 `Clock`(KakaoMemoChannel.java `send`의 `clock.instant()`) |
| A14 | 실패 | D-099 결정 6 "한 발송에 갱신은 최대 한 번"과 "위험과 한계" "카카오는 갱신 한 번과 발송 두 번까지라 최대 약 30초"가 코드와 다르다. 미리 갱신이 일시 장애로 실패하고(만료 전이라 지금 토큰으로 보냄) 발송이 401이면 `refreshedNow`가 거짓이라 다시 갱신한다. 재현에서 한 발송에 갱신 요청 2번, HTTP 4번(최대 약 40초). R5. 그 밖의 결정 4·5·7, ADR-001 표, Notifier Javadoc은 고친 동작과 일치 |

### 점검표 밖 발견
- **R5 (수정 필요, A14)**: 위 경로. 계좌 안전 영향은 없지만, 연결 PR이 큐 처리 시간과 폭주 억제를 D-099의 상한 숫자로 설계하므로 문서와 코드가 같아야 한다. 둘 중 하나로 맞추면 된다.
  1. (권장) 코드: 이번 발송에서 갱신을 **시도**했으면(성공·실패 무관) 401 때 다시 갱신하지 않는다. 일시 장애 뒤 곧바로 같은 토큰 엔드포인트를 또 부르는 이득도 적다. 시험: 503(갱신) → 401(발송)이면 요청 2건, 결과 `http-401`.
  2. 문서: 결정 6과 위험과 한계를 "갱신 시도 최대 두 번(미리 갱신 실패 뒤 401), HTTP 최대 네 번, 최대 약 40초"로 고치고 위 순서의 시험을 추가한다.
- 확인한 것(문제 없음): `CappedBody`는 cap에 닿으면 결과를 끝내고 구독을 끊으며, 그 뒤 `onComplete`·`onError`는 이미 끝난 결과라 무시된다. `Flow` 규약상 `onNext`는 직렬 호출이라 공유 버퍼 경합이 없다. 연결 시간 초과(`HttpConnectTimeoutException`)는 `HttpTimeoutException` 하위라 사유가 `timeout`이다.

### 판정
수정 필요(A14 — R5: 미리 갱신이 일시 장애로 실패한 뒤 401이면 한 발송에서 갱신을 두 번 시도해 D-099 결정 6 "최대 한 번"·위험과 한계 "최대 약 30초"와 다름. 코드(권장) 또는 문서를 맞추고 그 순서의 시험을 추가할 것. 1차 R1·R2는 해결, R4는 후속 기록으로 받아들임)
