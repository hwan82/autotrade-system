# architect 검토: 알림 채널 이름 검사를 꾸리기 try 밖으로(PR #140 QA 시나리오 1·risk-ops K1)
- 대상: PR #144 / 커밋 9ca77865375c91f3f0b9134ea4cf5ac1335c71e9 / `git diff origin/main...HEAD` 전체(파일 5개, +50 −11)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --no-daemon` → Maven Central 429(`archunit-1.4.1.jar` 내려받기 실패)로 `:autotrade-architecture-test:compileTestJava` 실패. 코드 원인 아님.
  - `./gradlew --init-script <scratchpad>/mirror.init.gradle build --no-daemon` (저장소 밖 미러 init 스크립트, 사용자 허용) → BUILD SUCCESSFUL, 138 tasks. 시험 결과 XML 합계 1492개, 실패·오류·건너뜀 0. `NotificationSetupTest` 15개 모두 통과(새 시험 2개 포함).
  - 변이 1개: `NotificationSetup.built`를 `try { return ExternalChannel.ready(name, build.get()); } catch (RuntimeException e) { return skipped(...); }`(이름 검사를 try 안으로 되돌림)로 바꿔 `:autotrade-notification-jar:test` → 86개 중 1개 실패, 실패 시험은 `NotificationSetupTest#채널_이름이_어긋나는_코드_실수는_설정_오류로_바뀌지_않고_그대로_드러난다`. 변이는 `git checkout`으로 되돌렸다(작업 트리 깨끗함 확인).

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 미러 init 스크립트로 `./gradlew build` 직접 실행, BUILD SUCCESSFUL. 첫 실행 실패는 Maven Central 429(내려받기)뿐이고 컴파일·시험 실패 아님 |
| B2 | 통과 | 변경은 `autotrade-notification-jar` 안에서만(`java.util.function.Supplier` 추가뿐, 새 의존 없음). 자동매매 도메인 참조 없음. ArchUnit(`autotrade-architecture-test`) 빌드 안에서 통과 |
| B3 | 통과 | 공개 계약 무변경: `externalChannels`·`start`·`ExternalChannel` 시그니처 그대로, 새 `built`는 패키지 전용 static(`NotificationSetup.java:282`). `SlackWebhookChannel.name()`·`KakaoMemoChannel.name()`은 같은 문자열 `"slack"`·`"kakao"`를 상수로 돌려줌(`NotificationSetup.java:35,37`). 설정 키·스키마·REST 무변경. 결정 기록은 D-103 이력에 추가(`docs/decisions/D-103-settings-notification-test-send.md:69-72`, 대안 2개·남은 위험·되돌릴 조건 포함) |
| B4 | 해당 없음 | 시뮬레이션·시계·난수 코드 무변경. 골든 시험 포함 전체 빌드 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작 시험 2개: `NotificationSetupTest#채널_이름이_어긋나는_코드_실수는_설정_오류로_바뀌지_않고_그대로_드러난다`(이름 불일치는 `IllegalArgumentException`, 꾸리기 예외만 이유 코드, 정상 채널은 같은 객체), `#꾸린_채널_이름은_외부_채널_상수와_같다`(Slack·카카오 둘 다 꾸려 `channel().name()==name()`). 위 변이를 실제로 잡음. 지우거나 끈 시험 없음(diff는 시험 추가만) |
| B7 | 해당(risk-ops 검토 필요) | `autotrade-notification-jar/*`는 보호 경로(`scripts/ci/protected-paths.txt`)이고 실전 엔진·감시 기동 경로(`EngineMain.java:94,118`, `WatchdogMain.java:115`)에서 쓰인다. 지침 10·11장에 따라 risk-ops 검토, 사용자 직접 머지, 머지 전 독립 QA 대상. 이 검토의 판정은 architect 범위에 한한다 |
| B8 | 통과 | 새 예외 문구는 채널 이름만 싣는다(`NotificationSetup.java:101`, 기존 문구). 꾸리기 예외는 이전과 같이 메시지를 버리고 이유 코드만 남김(`built`의 catch). 키·토큰 값이 코드·로그·문서·커밋에 없음 |

## 점검표 밖 발견
- R1(권고, 막지 않음): `externalChannels` Javadoc의 "던지지 않는다"(`NotificationSetup.java:230`)와 `start` Javadoc의 "채널 설정이 틀려도 던지지 않고"(`:214`)는 설정 값에 대해서는 여전히 맞지만, 이번 변경으로 채널 클래스의 `name()`이 상수와 어긋나는 코드 실수는 `IllegalArgumentException`으로 나간다. D-103에는 이 위험이 적혀 있으나 메서드 계약 문서에는 없다. 다음 알림 PR에서 "설정 값 문제로는 던지지 않는다. 채널 이름 불일치(코드 실수)는 `IllegalArgumentException`" 정도로 한 줄 보태길 권한다. 지금 도달 경로는 코드 변경뿐이고, 새 시험 `#꾸린_채널_이름은_외부_채널_상수와_같다`가 머지 전에 잡는다.
- K1(기록): 부수 효과로 `build`가 null을 돌려주면 생기는 `NullPointerException`(`ExternalChannel.ready`의 `requireNonNull`)도 이제 try 밖에서 나간다. 두 공급자(`slack`·`kakao` 람다)는 null을 돌려줄 수 없어 도달하지 않으며, 코드 실수를 드러내는 같은 취지라 문제로 보지 않는다.
- K2(기록): 실험실 `NotificationTester`(`NotificationTester.java:85`)도 `externalChannels`를 부르므로 같은 코드 실수에서는 시험 보내기 요청이 예외로 끝난다. 엔진과 같은 규칙을 공유한다는 D-103 취지와 맞는다. 엔진 기동 실패가 주문을 만들지 않는다는 점(Guard는 알림 앞에서 OFF로 시작, `EngineMain.java:92-94`)은 확인했고, 실전 영향 판단은 risk-ops 몫이다.
- 대안 검토는 D-103 이력에 있다(상수만 / try 밖만 / 둘 다). 둘 다 고른 이유가 타당하다고 본다.

## 판정
승인
