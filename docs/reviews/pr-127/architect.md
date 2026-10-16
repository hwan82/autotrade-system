# architect 검토: 알림 라이브러리 1차(MON-06, D-099)
- 대상: PR #127 / 브랜치 `claude/project-thread-othqxw` / 커밋 895b98a(main 갈래점 587ff37) / `git diff origin/main...HEAD` 전체(29개 파일, +1522 −12)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew --max-workers=2 --offline build` → BUILD SUCCESSFUL(138 tasks). 알림·아키텍처 테스트가 UP-TO-DATE였으므로 아래 둘을 강제로 다시 돌렸다.
  - `./gradlew --offline :autotrade-architecture-test:test --rerun` → 통과(ArchitectureTest 19, ModuleOwnershipTest 13, RealPathReflectionTest 10, StopRequestPathTest 2, 실패·건너뜀 0)
  - `./gradlew --offline :autotrade-notification-jar:test --rerun` → 통과(EventRoutesTest 5, JdkHttpTransportTest 2, KakaoMemoChannelTest 12, NotifierTest 9, SlackWebhookChannelTest 3, 실패·건너뜀 0)
  - 재현 실험(저장소 밖 스크래치): 로컬 `HttpServer`가 응답 헤더와 본문 1바이트를 보낸 뒤 25초 멈추게 하고 `JdkHttpTransport.post`를 불렀다 → **26.1초 뒤** `IOException`(서버가 닫을 때까지 막힘). 아래 F1.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 성공, 해당 모듈 테스트 강제 재실행 성공 |
| B2 | 통과 | `autotrade-notification-jar/build.gradle.kts:7-10` 의존은 Jackson BOM·databind뿐(이미 `RealPathReflectionTest.EXTERNAL_ALLOWLIST`에 있음, RealPathReflectionTest.java:98-102). `com.autotrade.domain` 등 자동매매 패키지 참조 없음(ArchitectureTest.java:115 규칙 통과). HTTP는 JDK `java.net.http`. ArchUnit 규칙 자체는 바꾸지 않았고, 바뀐 것은 `ModuleOwnershipTest.NO_CLASSES_YET`에서 이 모듈을 뺀 것(ModuleOwnershipTest.java:131, 406)으로 클래스가 생겼으니 필요한 갱신이다 |
| B3 | 통과 | 새 공개 API(Channel, Notifier, HttpTransport, KakaoTokenStore 등)는 이 모듈 내부 계약이고 다른 모듈은 아직 쓰지 않는다. ADR-001 모듈 표 갱신(docs/adr/ADR-001-module-structure.md:64, 74)과 D-099가 있다. 설정 키는 추가하지 않고 변경 제안으로만 남겼다(docs/config/config-keys.md:80, D-099 후속 2). 설정 키를 실제로 넣는 연결 PR에서 config-keys 본문과 `SecretPaths` 지문을 함께 바꿔야 한다 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 카카오 만료 판단은 주입 `Clock`(KakaoMemoChannel.java:50, 96, 158), 알림 시각은 부르는 쪽이 넣는 `Instant`(Notification.java:15). 난수 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 테스트 있음: 기획서 MON-06 인수 조건 두 개(`NotifierTest#Slack_발송이_실패하면_긴급_알림은_카카오톡으로_넘어간다`, `KakaoMemoChannelTest#토큰_만료를_강제하면_보내기_전에_갱신하고_새_토큰으로_보낸다`), 5분 경계, 401 재발송, 갱신 거절·이상 응답, 저장 실패, 200자 자르기, 비밀값 toString, URL 거부, 8KB 상한, 리다이렉트. 지우거나 건너뛴 테스트 없음. 다만 응답 본문이 멈추는 경우의 시험이 없다(F1) |
| B7 | 실패(별도 검토 필요) | 보호 경로 `autotrade-notification-jar/*`, `autotrade-architecture-test/*`(scripts/ci/protected-paths.txt)를 바꾸고, 이 모듈은 실전 엔진·감시 런타임에 들어간다(RealPathReflectionTest.java:92). **risk-ops 검토 필요**. 현재 `docs/reviews/pr-127/risk-ops.md`가 없다. 사용자 머지·독립 QA 대상 |
| B8 | 통과 | 시험 값은 자리표시자(`REST-KEY-PH`, `SECRET-PH`, `ACCESS-OLD`). 사유 코드는 상태 번호·고정 문자열만(Failures.java:11-13, KakaoMemoChannel.java:123, 141-147, SlackWebhookChannel.java:64-69). URL 거부 예외는 원인 예외를 붙이지 않아 문구에 URL이 없다(SlackWebhookChannel.java:41-47, KakaoMemoChannel.java:79-85). `toString` 가림(KakaoToken.java:45, KakaoMemoChannel.java:201, SlackWebhookChannel.java:78). 토큰 저장 실패 WARN에 값 없음(KakaoMemoChannel.java:162) |

## 점검표 밖 발견

### F1(수정 필요): HTTP 요청 시간 제한이 응답 본문 읽기에 걸리지 않는다
- `[사실]` `JdkHttpTransport.post`는 `HttpResponse.BodyHandlers.ofInputStream()`으로 받고 `in.readNBytes(MAX_BODY_BYTES)`로 읽는다(JdkHttpTransport.java:40-43). `HttpRequest.timeout`(10초)은 응답 헤더를 받을 때까지만 적용되고, 그 뒤 본문 읽기는 막히는 읽기에 제한이 없다.
- `[사실]` 재현: 헤더와 본문 1바이트 뒤 25초 멈추는 로컬 서버 → `post`가 26.1초 뒤에야 돌아왔다(서버가 닫아서 끝남). 서버나 중간 경로가 연결을 닫지 않으면 TCP가 끊길 때까지 무기한 막힌다.
- 영향 `[추론]`:
  - D-099 결정 7·위험 표("요청 10초 제한", "채널당 최대 약 10초, 카카오 최대 약 30초")와 `Notifier` Javadoc(Notifier.java:15-16)이 사실과 다르다. 연결 PR은 이 상한을 믿고 큐·감시 주기를 설계하게 된다.
  - 긴급 알림이 Slack에서 멈추면 카카오·로그 대체 발송이 일어나지 않는다(대체 발송의 목적을 깬다).
  - `KakaoMemoChannel.send`는 `synchronized`(KakaoMemoChannel.java:95)라 한 번 멈추면 그 채널을 쓰는 다른 모든 스레드도 같이 멈춘다.
  - 이 모듈은 실전 엔진·감시 런타임에 있으므로(절대 규칙 6), 연결 PR이 별도 스레드로 감싸도 알림 스레드 하나가 영구히 묶일 수 있다.
- 고칠 방향(대안 둘): ① `client.sendAsync(...)`로 본문까지 받는 핸들러(예: 상한 있는 `BodySubscribers` 조합)를 쓰고 `future.get(REQUEST_TIMEOUT 남은 시간)` 뒤 초과면 `cancel(true)`하고 `HttpTimeoutException`으로 바꾸기, ② 본문 읽기를 별도 기한으로 감싸 시간 안에 못 읽으면 `InputStream`을 닫아 끊기. 어느 쪽이든 "헤더 뒤 본문이 멈추면 정해진 시간 안에 `timeout`으로 끝난다"는 `JdkHttpTransportTest` 시험을 추가한다.
- 함께 고칠 문서: 카카오 최악 시간은 사전 갱신 → 발송 401 → 재갱신 → 재발송으로 HTTP 4회(KakaoMemoChannel.java:96-111)라 요청 10초 기준 약 40초다. D-099 위험 표와 Notifier.java:15-16의 "약 30초"를 실제 상한으로 고친다.

### R1(권고, 이번 PR 필수 아님): 미리 갱신이 실패하면 아직 유효한 토큰으로도 보내지 않는다
- `[사실]` 만료 5분 전 구간에서 갱신이 `io-error`·`http-5xx` 등 일시 장애로 실패하면 바로 실패를 돌려준다(KakaoMemoChannel.java:96-100). 그 시점 액세스 토큰은 최대 5분 더 유효하다.
- `[추론]` 정보 알림은 카카오가 첫 채널이라 이 경우 Slack으로 넘어가 큰 손해는 없다. 그래도 `kakao-refresh-rejected`가 아니고 `accessExpiresAt`이 아직 지나지 않았으면 기존 토큰으로 발송을 시도하는 편이 가용성이 높다. 바꾸지 않는다면 D-099에 의도로 적어 둔다.

### R2(권고): 외부 채널 판정이 이름 문자열 `"log"`에 기댄다
- `DispatchReport.delivered()`(DispatchReport.java:22)와 `Notifier.hasExternalChannel`(Notifier.java:77)이 `name().equals("log")`로 로그 채널을 가른다. `Notifier.of`로 다른 구현이 이름 `log`를 쓰면 외부 채널이 "전달 안 됨"으로, 반대로 로그 채널을 다른 이름으로 감싸면 "전달됨"으로 셈한다. 재사용 라이브러리이므로 `LogChannel` 타입 또는 `Channel`의 `external()` 같은 선언으로 가르는 편이 안전하다. 연결 PR 전에 정리하면 된다.

### R3(권고): 사람이 읽는 알림 시각이 UTC다
- `Notification.plainText()`가 `Instant.toString()`(UTC, `Z`)을 쓴다(Notification.java:33). 사용자는 KST·미국장 야간을 보며 판단하므로, 연결 PR에서 표시 시간대(거래소 현지 또는 Asia/Seoul)를 받게 하는 것을 권한다. 계산에는 영향 없음.

### 문서·병합 순서
- 기획서 버전이 v14.39 → v14.42로 건너뛰고(docs/spec/autotrade-spec.md:12, 20), D-097·D-098·v14.40·v14.41은 병행 브랜치가 쓴다고 적었다. 이 PR이 먼저 머지되면 main에 번호 공백이 잠시 생긴다. 병행 PR이 같은 번호로 그대로 머지되는지 머지 때 확인할 것(이 PR의 결함은 아님).

## 판정
수정 필요(F1: `JdkHttpTransport` 응답 본문 읽기에 시간 제한을 걸고 멈춘 본문 시험 추가, D-099·Notifier Javadoc의 최대 시간 문구 정정). B7은 risk-ops 검토가 따로 있어야 한다(현재 없음). R1~R3은 권고.

## 재검토 2026-10-16
- 대상: PR #127 / 커밋 8ee12d1(1차 검토 895b98a 뒤 6513988 검토 원문, 8ee12d1 수정) / `git diff origin/main...HEAD` 전체를 다시 봤고, 수정분은 `git diff 6513988 8ee12d1`(JdkHttpTransport, KakaoMemoChannel, Notifier, 시험 3개, D-099)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew --max-workers=2 --offline build` → BUILD SUCCESSFUL(138 tasks)
  - `:autotrade-notification-jar:test --rerun` → 통과(EventRoutesTest 5, JdkHttpTransportTest 5, KakaoMemoChannelTest 14, NotifierTest 10, SlackWebhookChannelTest 3, 실패·건너뜀 0)
  - `:autotrade-architecture-test:test --rerun` → 통과(ArchitectureTest 19, ModuleOwnershipTest 13, RealPathReflectionTest 10, StopRequestPathTest 2)
  - 1차의 멈춘 본문 재현을 새 jar로 다시 실행 → **10.4초에 `HttpTimeoutException`**(1차는 26.1초 뒤 서버가 닫아서야 끝남). F1 해소 확인
  - 카카오 경로 재현(저장소 밖 스크래치, 시험용 `FakeHttp` 사용): 만료 60초 전 토큰, 응답 순서 503 → 401 → 갱신 200 → 발송 200 → **HTTP 4회(kauth, kapi, kauth, kapi)**, 결과 ok. 아래 F2

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드·강제 재실행 결과 |
| B2 | 통과 | 새 의존 없음. `JdkHttpTransport`는 `java.net.http`·`java.util.concurrent`(`CompletableFuture`, `Flow`)만 추가(JdkHttpTransport.java:1-20). `RealPathReflectionTest` 강제 재실행 통과 |
| B3 | 통과 | 공개 계약 변화 없음(`JdkHttpTransport(Duration)`은 패키지 전용, JdkHttpTransport.java:41). D-099 결정 5·6·7·위험 표 갱신 |
| B4 | 통과 | 카카오 판단은 주입 `Clock`(KakaoMemoChannel.java:97). 시험의 `System.nanoTime`은 경과 시간 상한 확인용으로 결과에 영향 없음 |
| B5 | 해당 없음 | 금액 없음 |
| B6 | 통과 | F1 시험 3개(`JdkHttpTransportTest#헤더_뒤_본문이_멈추면_기한_안에_시간_초과로_끝난다`, `#응답이_오지_않으면_기한_안에_시간_초과로_끝난다`, `#본문이_아주_느리게_와도_기한_안에_끝난다`), R1 `KakaoMemoChannelTest#미리_갱신이_일시_장애로_실패해도_만료_전이면_지금_토큰으로_보낸다`, `#미리_갱신한_토큰이_401이면_다시_갱신하지_않는다`, `NotifierTest#중단돼도_로그_채널에는_남긴다`. 지우거나 약화한 시험 없음. 다만 F2 경로(미리 갱신 실패 → 발송 401) 시험이 없다 |
| B7 | 실패(별도 검토 필요) | 보호 경로 변경은 그대로. **risk-ops 검토 필요**. `docs/reviews/pr-127/risk-ops.md` 1차 판정은 "수정 필요"이고, 이 수정에 대한 risk-ops 재검토는 이 글을 쓰는 시점에 파일에 없다(동시 진행 중). 사용자 머지·독립 QA 대상 |
| B8 | 통과 | 새 예외 문구는 고정 문자열(`"notification request timed out"`, `"notification request failed"`, JdkHttpTransport.java:63, 71). 시험이 문구에 호스트가 없음을 확인(`hasMessageNotContaining("127.0.0.1")`) |

### 1차 지적 처리
- F1 해소. `sendAsync` + `future.get(total)` + 초과 시 `cancel(true)`·`HttpTimeoutException`(JdkHttpTransport.java:56-72), 본문은 `CappedBody`가 8KB까지만 모으고 구독을 끊는다(:77-). `ExecutionException`의 원인이 `IOException`이면 그대로 올려 `Failures`가 `timeout`·`io-error`로 바꾼다. 위 재현으로 10초 상한 확인. `CappedBody.onNext`는 꽉 찬 뒤 오는 버퍼를 0바이트로 처리하고 `onComplete`의 두 번째 `complete`는 무시되어 문제없다.
- R1 반영(KakaoMemoChannel.java:97-106): 거절이 아니고 만료 전이면 지금 토큰으로 보낸다.
- R2·R3는 D-099 후속 5번으로 미뤘다. 권고였으므로 받아들인다.
- Notifier 중단 처리 변경(Notifier.java:91-100, risk-ops R2 반영): 중단 뒤에도 막지 않는 `LogChannel`만 부르고 `interrupted=true`, `delivered()=false`. 타당하다.

### F2(수정 필요): 한 발송에서 갱신을 두 번 시도할 수 있어 문서의 상한과 다르다
- `[사실]` `refreshedNow`는 미리 갱신이 **성공**했을 때만 참이다(KakaoMemoChannel.java:101). 미리 갱신이 일시 장애로 실패하고(R1 경로) 지금 토큰으로 보낸 발송이 401이면, `refreshedNow`가 거짓이라 다시 갱신하고 다시 보낸다(:110-117). 위 재현에서 HTTP 4회를 확인했다.
- `[사실]` 그런데 D-099 결정 6은 "한 발송에 갱신은 최대 한 번", 위험 표와 Notifier.java:15-16 Javadoc은 "갱신 한 번과 발송 두 번까지라 최대 약 30초"라고 적는다. 실제 최악은 HTTP 4회, 약 40초다. 1차 F1에서 고치라고 한 "문서 상한과 실제 동작의 불일치"가 이 경로에 남아 있다.
- 고칠 방향(둘 중 하나): ① 미리 갱신을 **시도했으면**(성공·실패 무관) 401 뒤 재갱신을 하지 않는다(`refreshTried`). 문서 문구("갱신 최대 한 번, 약 30초")가 그대로 맞게 된다. 추천. ② 동작을 두고 D-099·Javadoc을 "갱신 시도 최대 두 번, 약 40초"로 고친다. 어느 쪽이든 "미리 갱신 503 → 발송 401" 시험을 추가해 HTTP 횟수를 고정한다.

## 판정
수정 필요(F2: 미리 갱신 실패 뒤 401 경로의 갱신 횟수를 문서와 맞추고 시험 추가). F1은 해소. B7은 risk-ops 재검토가 따로 있어야 한다.
