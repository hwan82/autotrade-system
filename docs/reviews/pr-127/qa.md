# 독립 QA 검토: 알림 라이브러리 1차(MON-06, D-099)
- 대상: PR #127 / 헤드 3a87bec (브랜치 claude/project-thread-othqxw) / main 1b10bd7 대비 전체 diff(`git diff origin/main...3a87bec`, 32개 파일 +2016 −12). 병합 기준점 = 현재 main 1b10bd7.
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - `./gradlew build -I <검토자 임시 폴더>/mirror.gradle --refresh-dependencies` → `BUILD SUCCESSFUL in 3m 38s`, 종료 코드 0. 시험 결과 XML 집계 1,381건 / 실패 0 / 오류 0 / 건너뜀 0. `:autotrade-notification-jar:test` 38건, `:autotrade-architecture-test:test` 44건(`ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10, `StopRequestPathTest` 2) 전부 통과.
    - 빌드 환경 메모: 이 컨테이너에서 Maven Central(`repo.maven.apache.org`)이 404와 `retry-after: 55503`을 돌려줘 미러 없는 빌드는 `Could not find org.jetbrains.kotlin:kotlin-stdlib:2.0.21`로 실패했다(코드 실패 아님, 재시도 2회 같은 결과). 사용자 허락을 받아 저장소 파일은 건드리지 않고 검토자 임시 폴더의 init 스크립트로 Maven Central 공개 미러(`maven-central.storage-download.googleapis.com/maven2`)를 앞에 두어 받았다. 빌드 스크립트·설정 변경 없음.
  - PR CI(헤드 3a87bec): `Gradle build`, `DB schema (PostgreSQL 16)`, `Secret scan and collector boundary` 모두 success(GitHub check runs 조회).
  - 탐침 시험 P1~P7: 임시 시험 클래스 `QaProbe127Test`를 `autotrade-notification-jar` 시험 폴더에 넣어 `:autotrade-notification-jar:test --tests '*QaProbe127Test'` 실행(7/0/0) 뒤 지움(커밋 안 함). 결과는 아래 "시나리오".
  - 변이 M1~M12: 운영 코드를 한 곳씩 고치고 `:autotrade-notification-jar:test` 실행, 매번 `git checkout`으로 되돌림. 끝난 뒤 `git status --short` 출력 없음.

    | 변이 | 결과 |
    |---|---|
    | M1 `KakaoMemoChannel.send`의 `\|\| refreshTried` 삭제(한 발송 갱신 두 번) | 잡힘 |
    | M2 `JdkHttpTransport.post` 기한 초과 때 `future.cancel(true)` 삭제 | **살아남음** |
    | M3 `followRedirects(HttpClient.Redirect.NEVER)` 삭제 | **살아남음**(JDK 기본값도 NEVER라 동작 같음) |
    | M4 Slack URL 포트 검사 `uri.getPort() != -1` 삭제 | 잡힘 |
    | M5 `Notifier.dispatch` 중단 경로의 로그 채널 기록 삭제 | 잡힘 |
    | M6 `EventRoutes` 모르는 종류 기본 등급을 INFO로 | 잡힘 |
    | M7 `DispatchReport.delivered`에서 로그 제외 조건 삭제 | 잡힘 |
    | M8 `CappedBody.onNext`의 `subscription.cancel()` 삭제(8KB 뒤에도 계속 받음) | **살아남음** |
    | M9 미리 갱신 거절(`kakao-refresh-rejected`) 때 멈춤 조건 삭제 | 잡힘 |
    | M10 `Failures.of`가 늘 `io-error` | 잡힘 |
    | M11 카카오 링크 쿼리 거부 조건 삭제 | 잡힘 |
    | M12 `Notifier`의 null 결과 → `no-result` 처리 삭제 | 잡힘 |

## 이번 PR이 실전 경로에 닿는 곳
- 운영 코드 변경은 `autotrade-notification-jar` 안 신규 클래스 15개(`Channel`, `DispatchReport`, `EventRoutes`, `Failures`, `HttpTransport`, `JdkHttpTransport`, `KakaoMemoChannel`, `KakaoToken`, `KakaoTokenStore`, `LogChannel`, `Notification`, `Notifier`, `SendResult`, `Severity`, `SlackWebhookChannel`)와 `build.gradle.kts`(Jackson BOM·databind 추가)뿐이다. 이 모듈은 `scripts/ci/protected-paths.txt`의 보호 경로이고 `autotrade-app-engine`·`autotrade-app-watchdog`·`autotrade-app-lab`의 런타임 의존이다(각 build.gradle.kts:26·21·18, 이번 diff 밖 기존 선언).
- `com.autotrade.notification`을 import하거나 부르는 운영 코드는 이 모듈 밖에 없다(`grep -rn "com.autotrade.notification" --include=*.java` 결과 모듈 자체와 `autotrade-architecture-test`뿐). 따라서 이번 PR로 엔진·감시·실험실의 동작은 바뀌지 않고, 알림도 실제로 나가지 않는다.
- 보호 테스트 변경: `ModuleOwnershipTest.NO_CLASSES_YET`에서 `autotrade-notification-jar`를 빼고(:131), 고정 단언 `classlessModules()`를 `autotrade-ui`만으로 고침(:406). `NO_CLASSES_YET`은 "클래스가 없어도 되는 예외"(:175)라 빼면 검사가 더 엄격해진다(이제 이 모듈 출력에 클래스가 있어야 하고, 소유 표대로 `com.autotrade.notification` 안에만 있어야 함). 약화 아님. `RealPathReflectionTest` 허용 목록·`ArchitectureTest` 규칙 변경 없음.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | `autotrade-risk` diff 없음. RealTradingGuard 기본값·재기동 OFF에 닿는 설정·코드·시험 변경 없음. `:autotrade-risk:test` 포함 전체 빌드 통과 |
| A2 | 통과 | 알림 모듈은 주문 API·Guard·broker 모듈을 모름(`ArchitectureTest`의 도메인 모름 규칙 ArchitectureTest.java:115, 통과). 새 코드는 JDK `java.net.http`(RealPathReflectionTest.java:270 허용 패키지)와 이미 허용된 Jackson 타입(`ObjectMapper`·`JsonNode`·`ObjectNode`·`JsonProcessingException`, :329·:337·:340·:355)만 쓴다. `RealPathReflectionTest`는 이 모듈을 검사 대상(REQUIRED_PROJECTS :92, 패키지 :139)으로 두고 통과. ArchUnit 규칙 변경 없음 |
| A3 | 해당 없음 | 증권사 주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음(알림 HTTP의 "재시도 없음, 다음 채널" 정책은 주문과 무관) |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 엔진·감시 코드 diff 없음, 알림 라이브러리는 아직 어디서도 불리지 않음. `dispatch`가 막는 호출(HTTP 한 번 최대 약 10초, 카카오 최대 약 30초)이라 주문·시세 스레드에서 직접 부르면 안 된다는 점은 Notifier.java:15-16 Javadoc과 D-099 "위험과 한계"에 적혀 있고 연결 PR 후속 1번(별도 스레드 큐)으로 넘겨져 있다. 탐침 P4: Slack이 응답 없는 서버에 걸려도 기한(시험값 1초) 뒤 `timeout`으로 카카오톡에 넘어가 전체 1.005초. 탐침 P3: 이미 중단된 스레드에서 실제 `JdkHttpTransport`로 보내면 Slack `interrupted`, 로그 채널에 ERROR 한 줄, 인터럽트 표시 유지 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 시험 픽스처는 자리표시자만(`REST-KEY-PH`, `SECRET-PH`, `ACCESS-OLD`, `https://hooks.slack.com/services/TPLACEHOLDER/BPLACEHOLDER/SECRETPART`). diff 추가 줄에서 `xox[bp]-`·실제 Webhook 모양·32자리 hex 검색 0건. 사유 코드는 상태 번호와 고정 문자열만(Failures.java:12, SlackWebhookChannel.java:69, KakaoMemoChannel.java:130·148-160), 예외 문구·응답 본문을 옮기지 않음. `toString`은 `****`(SlackWebhookChannel.java:79, KakaoMemoChannel.java:209, KakaoToken.java:46), 거부 문구에 URL 없음(SlackWebhookChannel.java:42·46, `SlackWebhookChannelTest#hooks_slack_com_https가_아닌_URL은_URL을_문구에_넣지_않고_거부한다` 통과), 토큰 저장 실패 WARN에 값 없음(KakaoMemoChannel.java:169-170). CI `Secret scan and collector boundary` success |
| A11 | 통과 | 보호 테스트 삭제·비활성화 없음. 고정 값 갱신은 `ModuleOwnershipTest.NO_CLASSES_YET`·`classlessModules` 단언 두 곳이며 이유(자리 모듈에 클래스가 생김)와 대상 코드(알림 모듈 전체)를 위에서 검토했다. 검사가 더 엄격해지는 방향이다. 새 동작 시험 38건이 실패 경우를 포함한다(Slack 실패→카카오, 예외·null 결과·모두 실패→로그, 중단, 401 한 번 갱신, 갱신 거절·이상 응답·장애, 저장 실패, 200자 자르기, 허용 밖 URL, 8KB 상한, 리다이렉트, 응답 없음·본문 멈춤·느린 본문 기한). 변이 12개 중 9개 잡힘. 살아남은 M2·M8은 요청 자체의 `timeout(total)`이 따로 있어 기한 뒤 연결이 정리되는 경로이고, M3은 JDK 기본값과 같아 동작 차이가 없으므로 실패로 두지 않고 권고 Q1로 적는다 |
| A12 | 통과 | 장애 대응표(broker-and-fault-design.md 6장) 중 Slack 긴급을 전제로 한 #2·#16·#17과 #8 엔진 멈춤 알림은 아직 연결되지 않은 상태 그대로이고, D-099 "위험과 한계"와 후속 1·3번이 이를 명시한다. 장애 테스트 8종의 전제를 바꾸는 코드 변경 없음 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 알림 시각은 UTC `Instant` 표시(Notification.java:33)로 계산에 쓰이지 않으며, 표시 시간대는 후속 5번(architect R3) |
| A14 | 통과 | D-099 결정 6("한 발송에 갱신 시도는 최대 한 번, HTTP는 최대 세 번")은 KakaoMemoChannel.java:96-118과 일치하고 `미리_갱신이_일시_장애로_실패한_뒤_발송이_401이면_다시_갱신하지_않는다`·`미리_갱신한_토큰이_401이면_다시_갱신하지_않는다`·`갱신_뒤에도_401이면_더_갱신하지_않고_실패로_돌려준다`가 고정한다. 결정 7의 "연결 3초·본문까지 전체 10초·리다이렉트 안 따름·8KB"는 JdkHttpTransport.java:31-32·45·53·60·30와 일치. 결정 4 순서는 Notifier.java:39-55, 결정 2 매핑표·모르는 종류 긴급은 EventRoutes.java:37-71과 일치. 기획서 v14.42 MON-06 행, 결정 로그 표 D-099 행, ADR-001 모듈 표, config-keys.md 변경 제안이 같은 내용. 과금(지침 8장): 채널은 Slack Webhook·카카오 나에게 보내기 두 무료 채널과 로그뿐이고 유료 알림톡(BIZ) 코드 없음, 라이브러리는 아무 데서도 켜지지 않음(기본 동작 없음) |
| B1 | 통과 | 위 직접 빌드 `BUILD SUCCESSFUL`, 1,381/0/0/0 |
| B6 | 통과 | 새 동작 시험 38건, 건너뜀 0. 시험 삭제·`@Disabled` 없음(diff에 시험 삭제 줄 없음) |

## 시나리오(잘못된 알림·누락·보호 공백 시도)
- P1 카카오 갱신 응답 `expires_in`이 `Long.MAX_VALUE` → `Instant.plusSeconds`에서 `ArithmeticException`이 채널 밖으로 나옴. `Notifier`가 잡아 `error-ArithmeticException`으로 기록하고 로그 채널로 넘김(알림 누락 없음). 토큰은 새 객체를 만들기 전에 던져서 바뀌지 않음. → 참고 R2.
- P2 만료 토큰으로 네 스레드가 동시에 카카오 발송 → 갱신 1번, 발송 4번 모두 성공(`synchronized send`, KakaoMemoChannel.java:96). 토큰 경쟁 없음.
- P3 이미 중단 표시가 있는 스레드에서 긴급 알림 → Slack `interrupted`, 로그 ERROR 1줄, `delivered()=false`, 인터럽트 표시 유지. 종료 중 긴급 알림이 아무 데도 안 남는 공백 없음.
- P4 Slack 응답 멈춤 → 기한 뒤 `timeout`, 카카오톡으로 대체 발송 성공(1.005초, 기한 1초 설정). 기한이 응답 본문까지 지켜짐.
- P5 채널이 `StackOverflowError`(`Error`)를 던짐 → `dispatch` 밖으로 나가고 로그 채널에도 남지 않음. `RuntimeException`만 잡는 설계(Notifier.java:101). Javadoc "예외를 던지지 않는다"(Notifier.java:15)는 `Error`를 빼고 읽어야 맞음. → 참고 R3.
- P6 리프레시 토큰이 거절된 뒤에도 발송마다 갱신 요청을 다시 보냄(3번 보내면 갱신 요청 3번, 모두 `kakao-refresh-rejected`). 차단 상태가 없음. 연결 PR의 폭주 억제와 함께 다룰 것. → 권고 R1.
- P7 8KB에서 잘려 깨진 JSON의 카카오 성공 응답 → `kakao-result-code` 실패로 다음 채널(실패 쪽으로 닫힘). 실제 카카오 성공 응답은 짧아 운영 영향 없음 `[추론]`.

## 점검표 밖 발견
- Q1(권고, 시험 공백): 변이 M2(기한 초과 때 `future.cancel(true)` 삭제)·M8(8KB 뒤 `subscription.cancel()` 삭제)이 살아남는다. 지금은 요청 자체의 `HttpRequest.timeout(total)`(JdkHttpTransport.java:53)이 따로 있어 기한 뒤 연결은 정리되지만, 두 취소가 실제로 연결을 끊는지 보는 시험이 없다(예: 서버 쪽에서 연결 닫힘을 관찰). M3(`followRedirects(NEVER)` 삭제)은 JDK 기본값과 같아 시험으로 잡을 수 없으나 명시 선언은 유지 권장.
- R1(권고, 연결 PR): 갱신 거절(`kakao-refresh-rejected`) 뒤 같은 리프레시 토큰으로 발송마다 갱신을 반복한다(P6). 사용자가 재동의하기 전에는 성공할 수 없으므로 연결 PR에서 거절 상태를 기억해 갱신 요청을 멈추고 "카카오 재동의 필요"를 한 번만 알리는 편이 낫다(카카오 쪽 요청 수와 로그 소음 감소).
- R2(참고): `expires_in`이 비정상적으로 크면 런타임 예외가 채널 밖으로 나간다(P1). `Notifier`가 잡으므로 알림은 대체 채널로 가지만, 상한(예: 1일)을 두고 `kakao-refresh-bad-response`로 처리하면 사유 코드가 일관된다.
- R3(참고): `dispatch`는 `Error`를 잡지 않는다(P5). 의도라면 Javadoc을 "런타임 예외를 던지지 않는다"로 좁힐 것. 연결 PR에서 알림 스레드가 `Error`로 죽어도 엔진 스레드에 번지지 않게 큐 쪽에서 막아야 한다(D-099 후속 1번 범위).
- R4(참고): 미리 갱신이 거절되면 아직 유효한 액세스 토큰(만료 5분 안)으로도 보내지 않고 실패한다(KakaoMemoChannel.java:103, 개발 시험 `미리_갱신이_일시_장애로_실패해도_만료_전이면_지금_토큰으로_보낸다` 후반부가 고정). 다음 채널로 넘어가므로 누락은 없고 설계로 받아들인다.
- 한계: 실제 Slack·카카오 호출은 검토자도 하지 않았다(키 없음). 공식 문서 대조는 D-099 "외부 사실" 링크의 주장을 확인할 주장으로 보고 코드 상수(URL·헤더·form 키·`result_code`·200/`ok`)와만 맞춰 봤다. 카카오 링크 도메인 등록(`http://localhost`) 가능 여부는 D-099가 `[가정]`으로 둔 대로 확인 못 함이며, 이번 PR의 동작에는 영향 없음(연결 PR의 사용자 설정 절차 항목).

## 판정
승인

(머지는 사용자. 권고 Q1·R1과 참고 R2·R3은 연결 PR 또는 후속에서 처리 권장.)
