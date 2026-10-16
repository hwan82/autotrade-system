# QA 검토: 알림 모듈 PR #138 검토 권고 7건(채널 이름 검사·이름으로 고르기·로그 시험·세 곳 설정 옮기기 비교)
- 대상: PR #140 / 헤드 4b2647d(브랜치 `claude/project-thread-ekrq1z`) / main 대비 diff `git diff origin/main...HEAD` 전체(커밋 6개, 파일 12개, +335 −6. 운영 코드 2개 파일: `NotificationSetup.java`, 실험실 `NotificationTester.java`. 시험 7개(새 파일 4개: `NotificationSetupMappingTest`와 다리 클래스 3개), 문서 4개). 머지 기준점 45ee853, 검토 시점 main 792cacf(PR #141 병합 뒤)
- 점검표: A 전체 + B1, B6
- 직접 실행한 것:
  - 헤드 4b2647d에서 `./gradlew -I <저장소 밖 미러 init 스크립트> build --rerun-tasks` → **BUILD SUCCESSFUL in 6m 25s**, 138 actionable tasks 모두 실행. JUnit XML 집계 1,478건, 실패 0, 오류 0, 건너뜀 0. `NotificationSetupTest` 13/0, `NotificationTesterTest` 16/0, `NotificationSetupMappingTest` 2/0, `NotificationQueueTest` 17/0, `EngineMainTest` 13/0, `WatchdogMainTest` 6/0, `ArchitectureTest` 19/0, `ModuleOwnershipTest` 13/0, `RealPathReflectionTest` 10/0. init 스크립트는 의존성 저장소 미러만 지정하며 저장소 파일은 바꾸지 않았다.
  - GitHub CI(헤드 4b2647d): Gradle build, DB schema (PostgreSQL 16), Secret scan and collector boundary, Lab screens (Playwright) 4개 모두 success.
  - main 792cacf와 `git merge-tree --write-tree`: 충돌 없음. main이 45ee853 뒤 바꾼 파일은 수집기(`autotrade-app-collector`)·D-104·ADR-008·런북·PR #141 검토 원문뿐이라 이 PR 파일과 겹치지 않는다. 병합 트리 빌드는 따로 돌리지 않았다([추론] 모듈이 겹치지 않아 컴파일 영향 없음).
  - 변이 15개(아래 표). 각 변이 뒤 `git checkout --`로 되돌렸고(마지막 `git status` 깨끗함), 잡은 시험 이름은 시험 결과 XML로 확인. 실행 범위: `:autotrade-notification-jar:test`, `:autotrade-app-lab:test --tests *Notification*`, `:autotrade-app-engine:test`, `:autotrade-app-watchdog:test`, `:autotrade-architecture-test:test`(변이마다 257건).
  - 검토 원문 되살림 확인: 커밋 025c544가 `docs/reviews/pr-140/architect.md`·`risk-ops.md`를 지웠고(커밋 메시지는 "대기 목록 등록"이지만 실제 변경은 두 파일 삭제뿐), e8b0647이 되살렸다. 두 파일은 cd8309a와 헤드에서 글자 단위로 같다(`git diff --quiet cd8309a HEAD -- <파일>`).

## 변이 시험
| # | 변이 | 결과 | 잡은 시험 |
|---|---|---|---|
| M1 | `NotificationTester.label`에서 카카오를 "Slack"으로(#138 QA M6 해당) | 잡힘 | `NotificationTesterTest#채널_화면_이름은_채널마다_정하고_모르는_채널을_Slack으로_적지_않는다`, `#켜진_채널마다_따로_보낸다_Slack이_받아도_카카오톡도_보낸다` |
| M2 | `start`에서 `logSkip` 호출 삭제(#138 QA M7) | 잡힘 | `NotificationSetupTest#켰는데_못_꾸린_채널은_이유마다_로그를_남기고_꺼진_채널은_조용하다` |
| M3 | `logSkip`의 `missing-values` 분기 삭제(#138 QA M14) | 잡힘 | 같은 시험 |
| M4 | `logSkip` `default`를 예전 카카오 "형식이 틀림" 문구로(QA R1 되돌림) | 잡힘 | `NotificationSetupTest#모르는_이유_코드는_다른_채널_문구로_떨어지지_않고_채널_이름과_코드를_적는다` |
| M5 | `ExternalChannel` 이름 검사(slack·kakao만) 삭제 | 잡힘 | `NotificationSetupTest#외부_채널_이름은_slack_kakao뿐이고_꾸린_채널_이름과_같다` |
| M6 | `ExternalChannel` 채널 이름 일치 검사 삭제 | 잡힘 | 같은 시험 |
| M7 | `start`가 `named(KAKAO)`·`named(SLACK)`을 바꿔 넘김(긴급 1순위 뒤바뀜) | 잡힘 | `NotificationSetupTest#켜진_채널만_표준_순서로_꾸린다`, `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다` |
| M8 | `externalChannels` 순서를 카카오·Slack으로 뒤집음 | 잡힘(14건) | `NotificationSetupTest#외부_채널은_Slack_카카오_순서로_…`, `#큐와_외부_채널_목록은_…`, `NotificationTesterTest` 다수. 이름으로 고르므로 `start`의 긴급 순서는 바뀌지 않는다(`#켜진_채널만_표준_순서로_꾸린다`는 통과) |
| M9 | `WatchdogMain.setup`만 클라이언트 시크릿·액세스 토큰 자리 바꿈 | 잡힘 | `NotificationSetupMappingTest#같은_알림_설정이면_엔진_감시_실험실이_같은_값으로_옮긴다`, `WatchdogMainTest#알림_설정_값을_이름대로_옮기고_…` |
| M10 | `NotificationTester.setup`만 링크 URL에 "/" 덧붙임 | 잡힘 | `NotificationSetupMappingTest#같은_…`, `NotificationTesterTest#알림_설정_값을_실전_엔진과_같은_이름으로_옮긴다` |
| M11 | `EngineMain.setup`만 `kakaoPaidMode(false)` 고정 | 잡힘 | `NotificationSetupMappingTest#같은_…`, `EngineMainTest#알림_설정_값을_이름대로_옮기고_…` |
| M12 | `KakaoMemoChannel.name()`을 "kakao-memo"로(risk-ops K1 회귀 모의) | 잡힘(12건) | `NotificationSetupTest#외부_채널은_…`, `#켜진_채널만_표준_순서로_꾸린다`, `#켰는데_못_꾸린_채널은_…`, `NotificationTesterTest` 다수. risk-ops K1의 `[추론]`(순서 단언에서 깨짐)을 실행으로 확인 |
| M13 | `named`의 개수 검사를 `isEmpty()`로 약화(중복 허용) | **생존** | `externalChannels`가 늘 Slack·카카오 하나씩만 만들어(NotificationSetup.java:247) 중복이 생길 입력이 없다. 도달 불가 방어 코드라 판정 영향 없음 |
| M14 | `WatchdogMain.setup`만 `slackEnabled(true)` 고정 | 잡힘 | `NotificationSetupMappingTest#같은_…`, `WatchdogMainTest#알림_설정_값을_이름대로_옮기고_…` |
| M15 | `label`의 `default`를 "Slack"으로 | 잡힘 | `NotificationTesterTest#채널_화면_이름은_…` |

## 이 PR의 범위(판정의 전제)
- [사실] 보호 경로 `autotrade-notification-jar/*`와 보호 테스트 모듈 `autotrade-architecture-test/*`(scripts/ci/protected-paths.txt)를 바꾸므로 실전 관련 PR이다.
- [사실] `autotrade-architecture-test` 변경은 새 시험 1개(`NotificationSetupMappingTest`)와 시험 소스 다리 클래스 3개 추가뿐이다. `ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest`·`OurModules`·고정 값(지문·허용 목록·소유 표) diff 없음. 다리 클래스는 `src/test`에 있어 운영 jar에 들어가지 않고, `ModuleOwnershipTest`는 `DO_NOT_INCLUDE_TESTS`로 가져오므로(ModuleOwnershipTest.java:217) 다른 모듈 패키지(`com.autotrade.app.engine` 등)에 둔 시험 클래스가 소유 표를 어기지 않는다(빌드 통과로 확인). 리플렉션 없음(코드 읽기).
- [사실] 주문 출구, RealTradingGuard, 블랙리스트·수동 보유, 주문 큐·토큰 버킷, 시세 게이트웨이·보호 시세, 서버 측 손절, 장애 대응 코드 파일은 diff에 없다.
- [사실] 실전 엔진·감시에 닿는 운영 변경은 `ExternalChannel` 생성자 검사 두 개(NotificationSetup.java:93, :99), `start`의 이름으로 고르기(:222, `named` :284-290), `logSkip` 분기(:296-311)다. 엔진·감시 `setup`은 바뀌지 않았다.
- [사실] 지금 채널 이름은 상수와 같다(`SlackWebhookChannel.name()` "slack", `KakaoMemoChannel.name()` "kakao", NotificationSetup.java:34-36). 그래서 정상 설정에서 새 검사가 새로 던지는 경로가 없고, 켜지고 꺼지는 채널 집합과 긴급 순서는 이전과 같다(M7·M8 결과).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성·기동 코드 diff 없음(EngineMain.java 무변경). 이번 빌드에서 autotrade-risk(RealTradingGuardTest 포함)와 `EngineMainTest#실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다` 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 규칙·고정 값 diff 없음. 새 시험·다리 클래스는 알림 설정 타입만 참조. `ArchitectureTest` 19·`ModuleOwnershipTest` 13·`RealPathReflectionTest` 10 통과 |
| A3 | 해당 없음 | 증권사 주문 연동 변경 없음. 바뀐 코드의 외부 호출 대상은 Slack Webhook·카카오 API뿐이고 호출 방식 무변경 |
| A4 | 해당 없음 | 주문 타임아웃·조회 우선 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음 |
| A7 | 통과 | 주문 큐·토큰 버킷·킬스위치 변경 없음. `NotificationQueue`·`Notifier` diff 없음, `NotificationQueueTest` 17건 통과 |
| A8 | 통과 | `start`는 엔진 `startNotifications`(EngineMain.java:117-127, `start`의 try 블록 앞)에서 불리므로 여기서 던지면 엔진이 뜨지 않는다. 새로 던질 수 있는 곳은 `named`의 `IllegalStateException`(:286-288)과 생성자 검사(:93-101)인데, `named`는 `externalChannels`가 늘 slack·kakao 하나씩 돌려주므로(:247) 도달하지 않고(M13 생존이 그 증거), 생성자 검사는 `slack()`·`kakao()`의 try 안(:255, :276)에서 걸려 채널만 끈다(M12). 설정 값으로 엔진 기동을 막는 경로는 생기지 않았다. 실험실 변경은 실험실 프로세스 안에서만 돈다 |
| A9 | 해당 없음 | 보호 시세·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 새 시험 값은 `SLACK-URL-PH`·`ACCESS-PH`·`REST-PH`·`REFRESH-PH`·`CLIENT-PH`·`http://link.example`·`https://example.com/SECRETPART` 류 자리표시자. 새 로그 `default` 문구는 채널 이름과 이유 코드만 싣고(:310) 이름은 생성자 검사로 slack·kakao로 묶인다. 생성자 예외 문구는 채널 이름만 싣는다. `#켰는데_못_꾸린_채널은_…`이 두 채널 실패 로그에 값이 없음을 단언하고 통과. CI gitleaks success |
| A11 | 통과 | 보호 테스트 삭제·비활성화·약화 없음, 고정 값 변경 없음(보호 모듈은 추가만). 기존 시험 변경은 `NotificationTesterTest`에 단언 3줄 추가뿐(diff 확인). `@Disabled`·`assume` 추가 없음. 새 동작마다 실패 경우 시험이 있다: 이름 검사 위반(M5·M6), 이름으로 고르기(M7), 로그 분기(M2~M4), 세 곳 옮기기 불일치(M9~M11, M14), 화면 이름(M1·M15). 생존 M13은 도달 불가 방어 코드 |
| A12 | 통과 | 장애표의 알림 경로(긴급 Slack 1순위·대체 발송)는 채널 집합·순서가 같다. 이제 목록 순서가 바뀌어도 긴급 1순위가 유지된다(M8에서 `#켜진_채널만_표준_순서로_꾸린다` 통과). 장애 테스트 8종의 전제(주문·대사·계좌 격리)에 닿는 코드 없음 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. 표시 시간대·시각 처리 무변경 |
| A14 | 통과 | D-103 반영 기록(D-103-settings-notification-test-send.md:62-68)이 코드와 일치: 이름 검사 `IllegalArgumentException`(:93-101), 이름으로 고르기(:222), `invalid-values` 분기와 `default` 문구(:308-310), `label` 규칙(NotificationTester.java:115-121), 다리 클래스 3개·버린 대안·되돌릴 조건, 시험 이름 실재. 결정 내용 불변이라 새 D 번호 없음에 동의("추천안 채택(자율)" 표시 있음) |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행 BUILD SUCCESSFUL(1,478건, 실패 0). 미러 init 스크립트는 의존성 내려받기 경로만 바꿈 |
| B6 | 통과 | 새 시험 6개(NotificationSetupTest 3, NotificationTesterTest 1, NotificationSetupMappingTest 2)와 기존 시험 단언 추가 1곳. 건너뛴 시험 0, 지운 시험 없음. 변이 15개 중 14개 잡힘. PR 본문의 변이 8건 주장(M6·M7·M14·R1 default·R2 두 검사·감시 자리 바꿈·순서 뒤집기)은 위 M1~M9로 다시 실행해 모두 잡힘을 확인 |

## 점검표 밖 발견

### 위험 시나리오
1. **채널 이름 회귀로 카카오가 조용히 꺼짐(risk-ops K1)**: 채널 클래스의 `name()`이 상수와 어긋나면 생성자 검사가 `kakao()`의 try 안에서 던져 `invalid-values`로 바뀌고, 엔진·감시는 카카오를 끄며 "형식이 틀려" 로그를 남긴다(원인이 틀린 로그). M12로 모의했더니 12개 시험이 잡았다. 코드 변경으로만 생기고 머지 전에 막히므로 판정 영향 없음. risk-ops K1 권고(검사를 try 밖으로, 또는 채널 `name()`이 상수를 쓰게)에 동의한다.
2. **채널 목록 순서 실수로 긴급 1순위가 카카오로**: #138 QA 시나리오 1의 위험이 이 PR로 해소됐다. `start`가 이름으로 고르므로 `externalChannels` 순서를 뒤집어도(M8) 큐의 긴급 순서는 그대로다(`#켜진_채널만_표준_순서로_꾸린다` 통과). 반대로 `start`에서 이름을 바꿔 넘기면(M7) 두 시험이 잡는다.
3. **실험실 시험 성공인데 엔진·감시는 다른 값으로 채널을 꾸림(D-103 K4)**: 세 곳 옮기기 중 한 곳만 바꾸는 변이 4개(M9 감시 값 자리, M10 실험실 링크, M11 엔진 BIZ 모드, M14 감시 Slack 켬)를 모두 `NotificationSetupMappingTest`가 잡았다. 남은 한계: 비교는 `NotificationSetup.equals`(NotificationSetup.java:320-328)가 보는 값에 한정되고, 세 곳이 같은 실수를 함께 하면 이 시험은 통과한다(그 경우는 각 모듈의 손 기대값 시험이 잡는다).
4. **엔진 기동이 알림 구성 예외로 막힘**: 새 `IllegalStateException`(`named`)은 `startNotifications`에서 엔진 기동을 실패시킬 수 있는 위치지만, `externalChannels`가 고정 목록을 돌려주어 도달하지 않는다(M13 생존). [추론] Guard 기본 OFF라 기동 실패가 잘못된 주문을 만들지는 않는다.
5. **알림톡(BIZ, 유료)으로 보냄(지침 8장 과금)**: BIZ 검사 무변경. 엔진 옮기기에서 BIZ를 무시하게 바꾸면(M11) 두 시험이 잡는다. 유료 호출 경로 없음.

### 권고(판정 영향 없음)
- Q1(변이 M13): `named`의 개수 검사는 시험이 없다. 지금은 도달 불가라 시험보다 `externalChannels`의 "Slack·카카오 하나씩" 불변식을 시험이 이미 고정한다(`#외부_채널은_Slack_카카오_순서로_…`). 그대로 두어도 된다.
- R1(참고): 커밋 025c544의 메시지("독립 QA 대기 목록 등록")와 실제 변경(검토 원문 두 파일 삭제)이 다르다. 최종 트리는 원문과 같아 결과에 영향은 없지만, 검토 원문 무결성을 커밋 이력으로 추적할 때 혼동된다. squash 머지면 사라진다.

## 판정
승인
