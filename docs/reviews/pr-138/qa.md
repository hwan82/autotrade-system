# QA 검토: 알림 채널 구성 규칙 하나로 합침(D-103 변경 제안)·PR #134 남은 권고 4건
- 대상: PR #138 / 헤드 d42d8e0(브랜치 `claude/project-thread-amlnn0`) / main 대비 diff `git diff origin/main...HEAD` 전체(커밋 5개, 파일 14개, +380 −81. 운영 코드 3개 파일: `NotificationSetup.java`, `NotificationQueue.java`(Javadoc 한 줄), 실험실 `NotificationTester.java`. 시험 4개, 문서 7개). 머지 기준점 a0b0dfb, 검토 시점 main 8eef1c0(PR #137 병합 뒤)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - 헤드 d42d8e0에서 `./gradlew -I <저장소 밖 미러 init 스크립트> build --rerun-tasks --refresh-dependencies` → **BUILD SUCCESSFUL in 4m 20s**, 138 actionable tasks 모두 실행. JUnit XML 집계 1,470건, 실패 0, 오류 0, 건너뜀 0. `NotificationSetupTest` 10/0, `NotificationQueueTest` 17/0, `NotificationTesterTest` 15/0, `NotificationTestControllerTest` 3/0, `NotifyingCommandAuditLogTest` 2/0, `EngineMainTest` 13/0, `WatchdogMainTest` 6/0, `RuntimeConfigNotificationTest` 2/0. 미러 없이 먼저 돌린 `./gradlew build`는 build-logic의 `kotlin-stdlib:2.0.21`을 받지 못해 설정 단계에서 실패(저장소 접근 문제, 코드와 무관). init 스크립트는 저장소 미러만 지정하며 저장소 파일은 바꾸지 않았다.
  - GitHub CI(헤드 d42d8e0): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary, Lab screens (Playwright) 4개 모두 success.
  - main 8eef1c0과 `git merge-tree`: 충돌 없음. main이 a0b0dfb 뒤 바꾼 파일은 수집기(`autotrade-app-collector` KrxDailyCollector·KrxOpenApiClient와 시험)와 PR #137 검토 원문뿐이라 이 PR 파일과 겹치지 않는다. 병합 트리 빌드는 따로 돌리지 않았다([추론] 모듈이 겹치지 않아 컴파일 영향 없음).
  - 변이 15개(아래 표). 각 변이 뒤 `git checkout --`로 되돌렸고, 잡은 시험 이름은 시험 결과 XML로 확인. 실행 범위: `:autotrade-notification-jar:test`, `:autotrade-app-lab:test --tests *Notification*`, `:autotrade-app-engine:test`, `:autotrade-app-watchdog:test`.
  - 동작 동등성: main의 `NotificationSetup.slack/kakao`, main의 실험실 `NotificationTester.slack/kakao`, 이 PR의 `externalChannels`(NotificationSetup.java:231-268)를 줄 단위로 비교. `RuntimeConfig.Kakao`(RuntimeConfig.java:104-119), `Secret.of`(Secret.java:26-30), `KakaoMemoChannel` 생성자(KakaoMemoChannel.java:77-102), `KakaoToken.requireText`(KakaoToken.java:24-30), `FileKakaoTokenStore.initial/latest`를 읽어 확인.

## 변이 시험
| # | 변이 | 결과 | 잡은 시험 |
|---|---|---|---|
| M1 | `start`가 `channels.get(1)`·`get(0)`을 바꿔 `Notifier.standard`에 넘김(긴급 1순위 Slack↔카카오) | 잡힘 | `NotificationSetupTest#켜진_채널만_표준_순서로_꾸린다`, `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다` |
| M2 | 카카오 빈 값 사전 검사 삭제(`if (false)`) | 잡힘 | `NotificationSetupTest#외부_채널은_Slack_카카오_순서로_꾸리고_못_꾸린_채널은_이유_코드로_돌려준다`, `NotificationTesterTest#카카오_필수_값이_모자라거나_링크가_틀리면_보내지_않는다` |
| M3 | 빌더 `kakaoReconsentHelp`가 빈 값이면 다시 일반 문구로 채움 | 잡힘 | `NotificationSetupTest#재동의_문구는_부르는_쪽이_반드시_넘기고_넘긴_것을_그대로_쓴다` |
| M4 | `NotificationTester.setup`에서 REST API 키와 클라이언트 시크릿 자리를 바꿈 | 잡힘 | `NotificationTesterTest#알림_설정_값을_실전_엔진과_같은_이름으로_옮긴다` |
| M5 | `skipMessage`에서 `missing-values` 설명을 지워 기본 문구로 떨어짐 | 잡힘 | `NotificationTesterTest#못_꾸린_이유_코드마다_화면_설명이_있다` |
| M6 | `NotificationTester.label`이 늘 "Slack" | **생존** | 결과 `label`을 단언하는 시험 없음(main에서도 없음) → 권고 Q1 |
| M7 | `start`에서 `logSkip` 호출 삭제(못 꾸린 채널 로그 없음) | **생존** | 로그를 단언하는 시험 없음(main의 ERROR·WARNING 로그도 시험 없음) → 권고 Q2 |
| M8 | `externalChannels`에서 알림톡(BIZ) 검사 삭제 | 잡힘 | `NotificationSetupTest#외부_채널은_…`, `#알림톡_모드는_만들지_않는다`, `NotificationTesterTest#카카오_알림톡_BIZ는_보내지_않는다` |
| M9 | `NotificationTester.setup`의 `kakaoPaidMode`를 `mode == ME`로 뒤집음 | 잡힘 | `NotificationTesterTest#카카오_알림톡_BIZ는_보내지_않는다` 외 3건 |
| M10 | `ExternalChannel` 불변식(채널과 이유 중 하나만) 삭제 | 잡힘 | `NotificationSetupTest#외부_채널_결과는_채널과_이유_중_하나만_가진다` |
| M11 | Slack 꺼짐 검사 삭제(꺼도 URL이 맞으면 꾸림) | 잡힘 | `NotificationSetupTest#외부_채널은_…`, `NotificationTesterTest#꺼진_채널은_HTTP_없이_건너뛴다` |
| M12 | 카카오 꺼짐 검사 삭제 | 잡힘 | 같은 두 시험 |
| M13 | `NotificationTester.setup`에서 액세스 토큰 옮기기 삭제 | 잡힘 | `NotificationTesterTest#알림_설정_값을_실전_엔진과_같은_이름으로_옮긴다` |
| M14 | `logSkip`의 `missing-values` 분기를 지워 "형식이 틀림" 문구로 떨어짐 | **생존** | M7과 같은 이유 → 권고 Q2 |
| M15 | `EngineMain.setup`에서 `.kakaoReconsentHelp(...)` 삭제 | 잡힘 | `EngineMainTest` 13건 중 10건 실패(`#알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다`, `#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다` 등): 빌더가 던져 엔진이 뜨지 않는다. 아래 시나리오 2 |

## 이 PR의 범위(판정의 전제)
- [사실] 보호 경로 `autotrade-notification-jar/*`(scripts/ci/protected-paths.txt:16)와 `autotrade-app-engine` 시험을 바꾸므로 실전 관련 PR이다.
- [사실] 주문 출구, RealTradingGuard, 블랙리스트·수동 보유, 주문 큐·토큰 버킷, 시세 게이트웨이·보호 시세, 서버 측 손절, 장애 대응 코드와 보호 테스트(`autotrade-architecture-test` 전체, RealTradingGuard·GuardedOrderGateway 테스트, 장애 테스트) 파일은 diff에 없다(`git diff origin/main...HEAD --stat`).
- [사실] 실전 엔진·감시에 닿는 운영 변경은 `NotificationSetup`의 채널 조립(`start` → `externalChannels`, NotificationSetup.java:207-268)과 빌더 `kakaoReconsentHelp` 필수화(:74, :184-189)다. 엔진·감시의 `setup`(EngineMain.java:129-144, WatchdogMain.java:126-141)은 바뀌지 않았고 이미 비지 않은 상수를 넘긴다.
- [사실] 동등성: main 엔진 경로에서 빈 REST API 키는 `KakaoMemoChannel` 생성자(KakaoMemoChannel.java:80-82), 빈 리프레시 토큰은 `KakaoToken` 생성자(KakaoToken.java:20, 26-28, `store.initial` 전), 빈 링크는 `checkLink`의 스킴·호스트 검사(:100-102)에서 던져 같은 catch로 채널을 껐다. 새 사전 검사(NotificationSetup.java:256)는 같은 세 값을 같은 결과(채널 꺼짐)로 가르므로 켜지고 꺼지는 채널 집합은 같고 로그 문구만 나뉜다. 실험실 쪽은 main이 `meReady()`(Secret `isPresent`)로 봤지만 `Secret.of`가 공백을 `EMPTY`로 바꾸고 `linkUrl`도 `strip()`되므로 설정에서 온 값으로는 차이가 없다. `mode != ME` → `mode == BIZ`는 `KakaoMode`가 ME·BIZ 둘뿐이라 같다(KakaoMode.java:4-7).
- [사실] 실험실 API 응답의 `detail` 코드 5종과 화면 설명 문구는 main `NotificationTester`의 문자열과 글자 단위로 같다(NotificationTester.java:119-133).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성·기동 코드 diff 없음(EngineMain.java 무변경). 이번 빌드에서 autotrade-risk(RealTradingGuardTest 포함)와 `EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다` 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 규칙·고정 값(허용 목록·소유 표·지문) diff 없음. 새 공개 타입 `ExternalChannel`·`externalChannels`는 알림 채널만 다루고 주문 타입을 참조하지 않는다. autotrade-architecture-test(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest) 빌드 안에서 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음. 바뀐 코드의 외부 호출 대상은 Slack Webhook·카카오 API뿐 |
| A4 | 해당 없음 | 주문 타임아웃·조회 우선 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 통과 | 주문 큐·토큰 버킷·킬스위치 변경 없음. `NotificationQueue` 동작 코드 diff 없음(Javadoc `@param clock` 한 줄). `NotifyingCommandAuditLogTest` 단언 불변, 넘기는 문구만 운영 상수로 바뀜, 통과 |
| A8 | 통과 | 채널 설정이 틀려도 `externalChannels`는 던지지 않고 이유 코드로 돌려준다(NotificationSetup.java:240-268, catch 두 곳). 새 `requireNonNull(clock/http)`(:233-234)는 운영 `start(Path,String,Clock)`가 `JdkHttpTransport`와 주입 시계를 넘기므로 걸리지 않는다. 필수가 된 재동의 문구가 운영 호출부에서 빠지면 엔진이 뜨지 않지만(M15), 그 경로는 코드 변경으로만 생기고 `EngineMainTest` 10건·`WatchdogMainTest`가 잡는다(설정 값으로는 생기지 않음: 문구는 `RuntimeConfig.Notification` 상수). 실험실 `NotificationTester` 변경은 실험실 프로세스 안에서만 돈다 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 새 시험 값은 `SLACK-URL-PH`·`REST-PH`·`TPLACEHOLDER` 류 자리표시자(NotificationTesterTest.java:80-110, NotificationSetupTest.java:19). 이유 코드는 고정 문자열(NotificationSetup.java:39-47), `logSkip` 문구에 값 없음(:272-286). `ExternalChannel` 자동 `toString`은 값을 가리는 채널 `toString`을 부른다(SlackWebhookChannel.java:78, KakaoMemoChannel.java:258). CI gitleaks success |
| A11 | 통과 | 보호 테스트 파일 diff 없음, `@Disabled`·`assume` 추가 없음, 고정 값 변경 없음. 기존 시험에서 바뀐 줄: `NotificationSetupTest`의 "없으면 일반 문구" 단언을 "빠짐·공백·null이면 던짐" 3건으로 바꿈(동작이 엄격해진 데 맞춤, 약화 아님), 필수 인자 추가에 따른 호출 수정, `NotifyingCommandAuditLogTest` 문구 상수. 기존 `NotificationTesterTest`는 추가만 있음(diff 확인). 새 동작마다 실패 경우 시험이 있다(M1~M5, M8~M13, M15 잡힘). 생존 M6·M7·M14는 화면 라벨과 로그 문구로 main에서도 시험이 없던 부분 |
| A12 | 통과 | 장애표의 알림 경로(긴급 Slack 1순위·대체 발송)는 채널 집합·순서가 같다(M1이 `#켜진_채널만_표준_순서로_꾸린다`로 잡힘, `NotifierTest` 통과). 장애 테스트 8종의 전제(주문·대사·계좌 격리)에 닿는 코드 없음 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 표시 시간대는 `RuntimeConfig.Notification.DISPLAY_ZONE`, 시각은 주입 `Clock` |
| A14 | 통과 | D-103 반영 기록(D-103-settings-notification-test-send.md:58-61)과 코드 일치: 공개 `externalChannels`, 이유 코드 5종, 빈 값 사전 검사·로그 문구 분리, 시험 이름 4개 실재. D-100 반영 기록(:89-93): 빌더 문구 필수, 공개 생성자 시험(NotificationQueueTest.java:25-32), `@param clock`, 엔진 시험 상수. 결정 색인 D-103 행(README.md:109)과 ADR-001:56 갱신. 결정 내용 불변이라 새 D 번호 없음에 동의(빌더 필수화는 "추천안 채택(자율)"과 대안 표시) |
| B1 | 통과 | 위 `./gradlew build` 직접 실행 BUILD SUCCESSFUL(1,470건, 실패 0). 미러 init 스크립트는 의존성 내려받기 경로만 바꿈 |
| B6 | 통과 | 새 시험 6개(NotificationSetupTest 3, NotificationQueueTest 1, NotificationTesterTest 2)와 바뀐 시험 1개. 건너뛴 시험 0, 지운 시험 없음. 변이 15개 중 12개 잡힘. PR #134 QA Q1(변이 M7 공개 5인자 생성자) 시험은 PR 본문 주장대로 실재하며 생성자 수·인자 모양을 고정한다(NotificationQueueTest.java:25-32, 코드 읽기 확인) |

## 점검표 밖 발견

### 위험 시나리오
1. **채널 순서 실수로 긴급 알림이 카카오 먼저**: `start`가 위치(`get(0)`=Slack, `get(1)`=카카오)로 고른다(NotificationSetup.java:210). 순서를 바꾸면 긴급 1순위가 바뀌지만 M1로 기존·새 시험 두 개가 잡음을 확인했다. 재현 경로는 코드 변경뿐. (risk-ops R2와 같은 관찰, 이름으로 고르는 편이 더 단단함)
2. **재동의 문구 누락 → 엔진·감시 기동 실패**: 이전에는 빌더가 일반 문구로 조용히 채웠고, 이제는 `build()`가 던져 `EngineMain.start`(EngineMain.java:94, try 블록 밖)가 실패한다(M15). 알림 문구 하나 때문에 실전 엔진이 뜨지 않는 경로가 새로 생겼다. 다만 설정 파일·사용자 입력으로는 생기지 않고(문구는 컴파일 상수), 운영 호출부에서 그 줄을 빼면 `EngineMainTest` 13건 중 10건이 실패해 머지 전에 막힌다. 판정 영향 없음. [추론] Guard 기본 OFF라 기동 실패가 잘못된 주문을 만들지는 않는다.
3. **실험실 시험 성공인데 엔진은 채널을 끔(D-103 K4)**: 이제 실험실과 엔진·감시가 같은 `externalChannels`를 쓴다(NotificationTester.java:85). 남은 차이는 `RuntimeConfig → NotificationSetup` 옮기기가 세 곳에 같은 코드로 있다는 점(EngineMain.java:129, WatchdogMain.java:126, NotificationTester.java:97). 세 곳 모두 같은 손 기대값 시험이 있고 실험실 쪽 값 자리 바꿈(M4·M9·M13)도 잡혔다. 한 곳만 바뀌는 변경이 다른 곳 시험을 깨지는 않는다(architect R1과 같은 관찰).
4. **실험실 시험이 엔진 토큰 파일을 망가뜨림**: `externalChannels`의 카카오 구성은 `FileKakaoTokenStore.initial`로 읽기만 하고(FileKakaoTokenStore.java:59-62, `latest`는 예외 없이 빈 값), 저장은 발송 중 갱신 때만 한다. PR #135와 같은 공유 방식이며 이번 변경으로 쓰기 시점이 늘지 않았다. HTTP도 부르지 않는다(`NotificationSetupTest`의 `http.requests` 빈 목록 단언).
5. **알림톡(BIZ, 유료)으로 보냄(지침 8장 과금)**: BIZ면 채널을 만들지 않는다(NotificationSetup.java:253). 검사를 지우면 세 시험이 잡는다(M8), 실험실 옮기기에서 모드를 뒤집어도 잡힌다(M9). 유료 호출 경로 없음.

### 권고(판정 영향 없음)
- Q1(변이 M6): 실험실 결과의 채널 라벨(`NotificationTester.label`, :114-116)을 단언하는 시험이 없다. "카카오톡"이 "Slack"으로 나와도 빌드가 통과한다. 화면에 잘못된 채널 이름이 보이는 정도라 막지 않는다. `NotificationTesterTest`에 결과 `label` 단언 한 줄이면 된다.
- Q2(변이 M7·M14): 켰는데 못 꾸린 채널의 ERROR·WARNING 로그(`logSkip`, NotificationSetup.java:272-286)를 시험하지 않는다. 엔진이 Slack을 조용히 끄는 일을 운영자가 로그로만 알 수 있는데, 로그가 사라지거나 문구가 바뀌어도 잡히지 않는다. main에서도 같은 공백이었다. 다만 실전 계좌가 있고 외부 채널이 없으면 엔진이 따로 WARNING을 남긴다(EngineMain.java:121-124).
- R1(참고): `logSkip`의 `default` 분기는 알 수 없는 이유 코드를 모두 카카오 "형식이 틀림"으로 적는다(:284). 지금 코드 5종은 다 분기가 있지만, Slack 쪽 이유 코드가 늘면 틀린 채널 문구가 나온다. `case SKIP_INVALID_VALUES`로 명시하고 `default`는 채널 이름을 싣는 편이 낫다.

## 판정
승인
