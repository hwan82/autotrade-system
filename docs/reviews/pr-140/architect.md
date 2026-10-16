# architect 검토: 알림 모듈 PR #138 검토 권고 7건 반영(채널 이름 검사·이름으로 고르기·로그 시험·세 곳 설정 옮기기 비교)
- 대상: PR #140 / 커밋 2bb196ae951469dffcf65b78c32e7561809c3d1c / main 대비 diff(`git diff origin/main...HEAD`, 9개 파일 +257 -6)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build -I <저장소 밖 mirror.gradle>`(Maven Central 차단 환경, 저장소 파일 변경 없음): BUILD SUCCESSFUL, 138 작업. 전체 테스트 결과 XML 합계 1478건, 건너뜀 0, 실패 0, 오류 0.
    - `NotificationSetupMappingTest` 2건, `NotificationSetupTest` 13건, `NotificationTesterTest` 16건 모두 통과.
  - 변이 확인 1(되돌림): `WatchdogMain.setup`의 `.kakaoRestApiKey(k.restApiKey()...)`를 `k.clientSecret()`으로 바꾸면 `NotificationSetupMappingTest#같은_알림_설정이면_엔진_감시_실험실이_같은_값으로_옮긴다`가 실패한다. 세 곳 비교 시험이 실제로 값을 본다.
  - 변이 확인 2(되돌림): `NotificationSetup.start`에서 `named(channels, SLACK)`과 `named(channels, KAKAO)`를 맞바꾸면 `NotificationSetupTest#켜진_채널만_표준_순서로_꾸린다`, `#큐와_외부_채널_목록은_같은_규칙으로_꾸린다`가 실패한다. 긴급 1순위(Slack) 자리가 시험으로 고정돼 있다.
  - 두 변이 모두 `git checkout --`로 되돌렸고 작업 트리가 깨끗함을 확인했다.

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행, BUILD SUCCESSFUL, 1478건 실패·건너뜀 0 |
| B2 | 통과 | 새 의존 없음. 운영 코드 변경은 `autotrade-notification-jar`(NotificationSetup.java)와 `autotrade-app-lab`(NotificationTester.java)뿐이고 import 추가 없음. 다리 클래스 3개(`EngineNotificationMapping`, `WatchdogNotificationMapping`, `LabNotificationMapping`)는 `autotrade-architecture-test`의 시험 소스이며, 이 모듈은 이미 모든 프로젝트를 testImplementation으로 둔다(autotrade-architecture-test/build.gradle.kts 주석·구성). `OurModules.architectureImportOption`(OurModules.java:336-345)은 이 모듈 시험 출력을 빼고 `ModuleOwnershipTest`는 `DO_NOT_INCLUDE_TESTS`(ModuleOwnershipTest.java:217)라 다리 클래스가 다른 모듈 패키지(`com.autotrade.app.engine` 등)에 있어도 소유 규칙·ArchUnit 범위에 들어가지 않는다. ArchUnit 규칙 변경 없음 |
| B3 | 통과 | 공개 레코드 `NotificationSetup.ExternalChannel`의 생성자가 이름 검사로 좁아졌다(NotificationSetup.java:93-101). 운영 코드에서 이 생성자를 부르는 곳은 같은 파일 `ready`/`skipped`(:105, :109)뿐(`grep "ExternalChannel("` 결과)이고 이름은 늘 `SLACK`/`KAKAO`다. 설정 키·스키마·REST API 변경 없음. 계약 축소는 Javadoc(:78-88)과 D-103 2026-10-16 항목에 기록됨. 외부 소비자가 없는 모듈 내부 생성 규칙이라 별도 ADR은 요구하지 않는다 |
| B4 | 해당 없음 | 시뮬레이션·전략 코드 변경 없음. 새 코드에 시스템 시계·난수 없음 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험: 이름 검사 `NotificationSetupTest#외부_채널_이름은_slack_kakao뿐이고_꾸린_채널_이름과_같다`(:185), 로그 분기 `#켰는데_못_꾸린_채널은_이유마다_로그를_남기고_꺼진_채널은_조용하다`(:205)·`#모르는_이유_코드는_다른_채널_문구로_떨어지지_않고_채널_이름과_코드를_적는다`(:226), 화면 이름 `NotificationTesterTest#채널_화면_이름은_채널마다_정하고_모르는_채널을_Slack으로_적지_않는다`, 세 곳 동등 `NotificationSetupMappingTest` 2건. 지우거나 건너뛴 시험 없음(diff에 삭제된 `@Test` 없음, 건너뜀 0) |
| B7 | 해당(risk-ops 검토 필요) | 보호 경로 변경: `autotrade-architecture-test/*`(새 시험·다리 클래스 4개)와 `autotrade-notification-jar/*`(실전 엔진·감시가 쓰는 `NotificationSetup.start`)가 `scripts/ci/protected-paths.txt`에 걸린다. 프로젝트 지침 11장에 따라 risk-ops 검토, 사용자 머지, 머지 전 독립 QA가 따로 필요하다. 이 검토 시점에 `docs/reviews/pr-140/risk-ops.md`는 없다 |
| B8 | 통과 | 새 시험의 값은 `SLACK-URL-PH`, `ACCESS-PH`, `REST-PH` 등 자리표시자. 새 로그 문구(NotificationSetup.java:310)는 채널 이름과 이유 코드만 적고 설정 값을 넣지 않으며, `#켰는데_못_꾸린_채널은...`이 두 채널 실패 로그에 자리표시자 비밀값이 없음을 단언한다. 커밋 메시지에 비밀값 없음. gitleaks는 이 컨테이너에 없어 CI 결과에 맡긴다(B8 판정은 diff 직접 확인 기준) |

## 점검표 밖 발견
- [사실] PR #138 architect 권고 R1·R2·R3이 모두 반영됐다. R1은 공개 범위를 넓히지 않고 리플렉션 없이 같은 패키지 시험 다리로 풀었고(D-086 취지와 맞음), 변이 확인 1로 실효가 있음을 확인했다.
- [사실] `NotificationSetup.equals`(NotificationSetup.java:321-328)는 비밀값·표시 시간대·재동의 문구까지 모든 필드를 비교하므로, `NotificationSetupMappingTest`는 세 프로세스의 `kakaoReconsentHelp`가 같아야 통과한다. 지금은 셋 다 `RuntimeConfig.Notification.KAKAO_RECONSENT_HELP`를 넘긴다. 나중에 프로세스마다 재동의 문구를 다르게 하려면 이 시험의 비교 범위를 함께 정해야 한다(지금 고칠 것 없음).
- [추론] `named`(NotificationSetup.java:284-290)는 이름이 정확히 하나가 아니면 `IllegalStateException`을 던져 `start`의 "설정이 틀려도 던지지 않는다" 설명과 결이 다르다. 다만 `externalChannels`가 늘 Slack·카카오 하나씩 만드는 고정 목록이라 설정 값으로는 닿을 수 없고 프로그래밍 오류만 드러낸다. 문제 삼지 않는다.
- [사실] `NotificationTester.label`의 `default -> channel`(NotificationTester.java:119)은 이제 `ExternalChannel` 이름 검사 때문에 운영 경로에서 닿지 않는 방어 분기다. 해롭지 않다.
- [사실] `logSkip`이 private에서 package-private으로 넓어졌다(NotificationSetup.java:296). 같은 모듈 시험용이고 모듈 밖 공개 API는 바뀌지 않는다.
- [사실] 사용자 문구·채널 구성 결과·실험실 API 응답 형태는 바뀌지 않았다(기존 `NotificationTesterTest`·`NotificationSetupTest` 시험 수정 없이 통과, 추가 단언만 있음).

## 판정
승인 (B7: 보호 경로 변경이므로 risk-ops 검토, 사용자 머지, 머지 전 독립 QA가 별도로 필요)
