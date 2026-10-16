# architect 검토: 알림 채널 구성 규칙을 NotificationSetup.externalChannels 하나로 합침(D-103 변경 제안), PR #134 남은 권고 4건
- 대상: PR #138 / 커밋 5270cab / main 대비 diff (`git diff origin/main...HEAD`, 10개 파일 +305 −80)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --offline`: build-logic의 Kotlin 플러그인이 캐시에 없어 해석 단계에서 실패(코드와 무관).
  - `./gradlew build --init-script <scratchpad>/mirror.gradle`(사용자가 허용한 저장소 밖 미러 init 스크립트): **BUILD SUCCESSFUL in 3m 51s**, 종료 코드 0.
  - 테스트 결과 XML 확인: `NotificationSetupTest` 10/10, `NotificationQueueTest` 17/17, `NotificationTesterTest` 15/15, `NotifyingCommandAuditLogTest` 2/2, 건너뜀 0. `autotrade-architecture-test`(ArchitectureTest, ModuleOwnershipTest, RealPathReflectionTest, StopRequestPathTest) 전부 통과, 건너뜀 0.
  - 동작 동등성 확인(코드 읽기): main의 실험실 `NotificationTester`(slack/kakao 메서드)와 엔진 `NotificationSetup.slack/kakao`, 이 PR의 `externalChannels`를 줄 단위로 비교.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 미러 init 스크립트로 `./gradlew build` 직접 실행, BUILD SUCCESSFUL. 오프라인 실패는 플러그인 캐시 문제로 PR 코드와 무관 |
| B2 | 통과 | 새 의존 없음. 실험실(`autotrade-app-lab`)이 이미 의존하던 `autotrade-notification-jar`의 공개 API만 더 쓴다. 알림 모듈은 여전히 설정 모듈을 모른다(값은 문자열로 받음, NotificationSetup.java:231). 새 타입은 `java.util.List`와 레코드뿐이라 D-086 JDK 허용 목록 안이고 RealPathReflectionTest·ArchitectureTest 통과. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 알림 모듈 공개 API가 늘었다(`externalChannels`, `ExternalChannel`, `SLACK`/`KAKAO`, `SKIP_*`, NotificationSetup.java:34-47·85·231). ADR-001 모듈 표의 notification-jar 행을 갱신했고, D-103 "변경 제안 반영" 기록에 대안·되돌릴 조건이 있다. `Builder.kakaoReconsentHelp` 필수화는 D-100 기록에 있다. REST API 응답의 `detail` 코드 문자열(`disabled`, `invalid-webhook-url`, `paid-mode`, `missing-values`, `invalid-values`)과 화면 설명 문구가 main의 NotificationTester와 글자 단위로 같음을 비교했다(NotificationTester.java:119-133). 설정 키·스키마 변경 없음 |
| B4 | 통과 | 시뮬레이션 코드 변경 없음. 새 코드의 시각은 주입한 `Clock`만 쓴다(`externalChannels(configDir, clock, http)`), `now()` 직접 호출 없음. 반환 순서는 `List.of(slack, kakao)`로 고정 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험: `NotificationSetupTest#외부_채널은_Slack_카카오_순서로_꾸리고_못_꾸린_채널은_이유_코드로_돌려준다`(이유 코드 5종, HTTP 미호출), `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다`, `#외부_채널_결과는_채널과_이유_중_하나만_가진다`, `#재동의_문구는_부르는_쪽이_반드시_넘기고_넘긴_것을_그대로_쓴다`(null·공백·누락 모두 NPE), `NotificationQueueTest#공개_생성자는_재동의_문구를_받는_6인자_하나뿐이다`, `NotificationTesterTest#알림_설정_값을_실전_엔진과_같은_이름으로_옮긴다`, `#못_꾸린_이유_코드마다_화면_설명이_있다`. 기존 `NotificationTesterTest` 시험은 diff상 추가만 있고 고친 줄이 없다. 지우거나 건너뛴 시험 없음(바뀐 기존 시험 2개는 필수 인자 추가에 따른 호출 수정이며 단언은 강해졌다) |
| B7 | risk-ops 검토 필요 | `autotrade-notification-jar/*`, `autotrade-app-engine/*`는 `scripts/ci/protected-paths.txt`의 보호 대상(실전 경로 런타임 모듈)이다. 실전 엔진·감시가 쓰는 `NotificationSetup.start`의 채널 조립 경로가 바뀌었다. 주문·Guard·블랙리스트 코드는 diff에 없다. `docs/reviews/pr-138/risk-ops.md`는 이 커밋 시점에 없다. 프로젝트 지침 10·11장에 따라 risk-ops 검토, 사용자 머지, 머지 전 독립 QA가 필요하다 |
| B8 | 통과 | 새 문자열은 이유 코드, 로그·화면 안내 문구, 시험 자리표시자(`SLACK-URL-PH`, `REST-PH` 등)뿐이다. 로그(`logSkip`, NotificationSetup.java:272-286)에 값을 넣지 않는다. `ExternalChannel` 레코드의 자동 `toString`은 채널의 `toString`을 부르는데 `SlackWebhookChannel`(:78)·`KakaoMemoChannel`(:258)은 값을 `****`로 가린다. 커밋 메시지에 비밀값 없음 |

## 점검표 밖 발견
- [사실] 실전 엔진·감시 동작 동등성: 카카오 빈 값을 이제 미리 검사해 `missing-values`로 가른다(NotificationSetup.java:256-257). main에서는 빈 REST API 키는 `KakaoMemoChannel` 생성자(KakaoMemoChannel.java:80), 빈 리프레시 토큰은 `KakaoToken` 생성자(`requireText`), 빈 링크는 `checkLink`의 스킴 검사에서 던져져 같은 catch로 채널을 껐다. 빈 리프레시 토큰일 때 토큰 파일의 다른 토큰으로 살아날 길도 main에 없었다(`KakaoToken(access, "", EPOCH)`가 `store.initial` 전에 던짐). 따라서 엔진·감시에서 채널이 켜지고 꺼지는 결과는 같고 로그 문구만 나뉜다. D-103 기록 "바뀐 점 하나"와 맞다.
- [사실] 실험실 쪽 미세 차이: main은 `RuntimeConfig.Kakao.meReady()`(Secret `isPresent`)로 빈 값을 봤고, 이제는 빌더의 `strip()` 뒤 빈 문자열로 본다. `Secret.of`가 공백 값을 이미 `EMPTY`로 바꾸므로(Secret.java:26-29) 설정에서 오는 값에서는 차이가 생기지 않는다.
- [사실] `start(..., http)`가 이제 `clock`·`http`가 null이면 `externalChannels`에서 NPE를 던진다(main은 Slack 생성 실패로 조용히 껐음). 운영 호출부는 공개 `start(configDir, process, clock)`(:203)로 실제 HTTP를 넘기므로 영향 없음. 프로그래밍 오류를 빨리 드러내는 쪽이라 문제 삼지 않는다.
- 권고 R1(머지 막지 않음): `RuntimeConfig.Notification → NotificationSetup` 옮기기가 세 곳(EngineMain.java:129, WatchdogMain.java:126, NotificationTester.java:97)에 같은 코드로 있다. 각 시험이 같은 손 기대값과 비교하므로 지금은 같음이 지켜지지만, "실험실 시험 성공 = 엔진 발송 가능"을 직접 고정하지는 않는다. 프로세스 경계상 한 모듈에 모을 곳이 없으므로(lab은 engine을 참조할 수 없음), 세 모듈이 함께 올라오는 `autotrade-architecture-test`나 별도 시험에서 같은 설정 입력에 세 결과가 `equals`인지 한 번에 단언하는 시험을 다음 알림 PR에서 더하길 권한다(세 `setup`이 package-private이라 공개 범위 조정이나 리플렉션 없는 다른 방법 검토 필요).
- 권고 R2(머지 막지 않음): `ExternalChannel`의 공개 생성자는 `name`이 `SLACK`/`KAKAO`인지, `channel.name()`과 같은지 검사하지 않는다. 지금 만드는 곳은 알림 모듈 내부 `ready`/`skipped`뿐이라 위험은 낮다. 외부에서 만들 이유가 없으면 다음 PR에서 이름 검사를 더하거나 문서에 "부르는 쪽이 만들지 않는다"를 적는다.
- 권고 R3(머지 막지 않음): `NotificationTester.label`은 `KAKAO`가 아니면 모두 "Slack"으로 적는다. 채널이 늘면 틀린 이름이 나온다. 지금 채널은 둘뿐이라 문제는 없다.

## 판정
승인 (B7: 보호 경로 변경이므로 risk-ops 검토, 사용자 머지, 독립 QA가 별도로 필요)
