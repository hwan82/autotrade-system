# architect 검토: D-103 설정 화면 알림 시험 보내기(D-100 후속 2번)
- 대상: PR #135 / 브랜치 `claude/project-thread-1ge5lz` / 커밋 1ba352b / `git diff origin/main...HEAD` 전체(12개 파일, +864 −6)
- 점검표: B
- 직접 실행한 것:
  - `./gradlew build --init-script <미러>` → `BUILD SUCCESSFUL in 4m`, 종료 코드 0. 새 시험 결과 파일 확인: `NotificationTesterTest` 11건, `NotificationTestControllerTest` 3건, `SettingsControllerTest` 10건(새 1건 포함) 모두 실패·건너뜀 0
  - `./gradlew :autotrade-app-lab:bootJar :autotrade-app-lab:testClasses :autotrade-app-experiment:classes` 뒤 `autotrade-ui/e2e`에서 `npx playwright test tests/03-settings-notification-test.spec.mjs` → 1 passed. 실험실 로그 `알림 시험(UI): slack=SKIPPED(disabled), kakao=SKIPPED(disabled)` 한 줄 확인
  - GitHub check-runs(1ba352b) 조회: DB schema·Secret scan·Lab screens(Playwright) success, Gradle build 진행 중(참고용, 판정 근거는 위 직접 실행)

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 `./gradlew build` 직접 실행 성공(ArchUnit·금지 의존 검사 포함) |
| B2 | 통과 | 새 코드는 `autotrade-app-lab`의 `com.autotrade.app.lab.settings`에만 있음. `autotrade-notification-jar`는 기존 의존(autotrade-app-lab/build.gradle.kts `implementation(project(":autotrade-notification-jar"))`)이고 공개 API(`SlackWebhookChannel.of`, `KakaoMemoChannel`, `FileKakaoTokenStore`, `EventRoutes.with`)만 씀. 순수 모듈·보호 경로(scripts/ci/protected-paths.txt) 무변경. ArchUnit 통과 |
| B3 | 실패 | 새 REST API `POST /api/settings/notification-test`(응답 `{sentAt, channels[{channel,label,status,detail,message}]}`, 새 오류 코드 `TEST_RUNNING` 409·`TEST_TOO_SOON` 429)가 REST 계약 문서 [ADR-005](../../adr/ADR-005-ui-dashboard.md)에 없다. ADR-005 84행 공통 오류 코드 목록에 429·두 코드 없음, 125~126행 API 표에 `/api/settings`의 GET·PUT만 있음, 6장(설정 화면)에 알림 시험 언급 없음. D-103·기획서 UI-04 행은 갱신됐지만 API 표의 단일 원본은 ADR-005다(다른 엔드포인트는 모두 여기 등재). 설정 키·스키마 변경은 없음 |
| B4 | 해당 없음 | 시뮬레이션·전략 코드 무변경. 새 코드는 주입 `Clock`만 씀(NotificationTestController.java:54·62·71, NotificationTester.java:84). 골든 테스트는 위 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 새 동작마다 시험: 꺼진 채널 HTTP 없음, 채널별 따로 발송, 404·시간 초과 설명, URL 형식·BIZ·빈 값·링크 형식 건너뜀, 토큰 파일 기록·다른 프로세스 토큰 사용, 리프레시 거절 재동의 안내, 중단 처리(NotificationTesterTest), 409·429·예외 뒤 회복(NotificationTestControllerTest), CSRF 403·비밀값 없음·429(SettingsControllerTest#알림_시험은_CSRF_토큰이_있어야_하고_꺼진_채널은_보내지_않는다), 화면 e2e 03. 지운·건너뛴 시험 없음(diff에 삭제 시험 없음) |
| B7 | risk-ops 검토 필요 | 실전 주문 경로·Guard·블랙리스트는 건드리지 않는다. 그러나 실험실 프로세스가 실전 엔진·감시가 읽는 공유 상태 파일 `<설정 폴더>/notification-state/kakao-token.json`에 쓰게 된다(NotificationTester.java:121~128, `KakaoMemoChannel.refresh`→`store.save`). 엔진의 `KakaoMemoChannel.adoptLatest`는 더 늦게 만료되는 파일 토큰으로 갈아탄다(autotrade-notification-jar/.../KakaoMemoChannel.java:143~162). 실전 엔진의 장애 알림 경로에 다른 프로세스가 입력을 넣는 변경이고, D-103 스스로 risk-ops 교차 검토를 담당으로 적었다. `docs/reviews/pr-135/risk-ops.md`는 아직 없다 |
| B8 | 통과 | 시험 값은 `PLACEHOLDER-…`·`example.invalid`뿐(NotificationTesterTest.java:35~41). 로그는 채널·상태·사유 코드만(NotificationTestController.java:64~66), 결과에 비밀값 없음을 시험(`assertNoSecrets`, SettingsControllerTest `assertNoPlaintext`). 요청 본문을 받지 않아 비밀값이 새 경로로 오가지 않음. 예외 문구는 결과에 넣지 않고 클래스 이름만(NotificationTester.java:146) |

## 점검표 밖 발견
- **A1(수정 필요, B3)**: ADR-005에 `POST /api/settings/notification-test` 행(본문 없음, 응답 모양, 채널 순서 Slack→카카오톡, status 값, 409 `TEST_RUNNING`·429 `TEST_TOO_SOON`·CSRF 403, 최대 약 40초 막힘)과 84행 공통 오류 코드에 두 코드를 더하고, 6장 설정 화면 표에 알림 시험을 한 줄 적는다. 계약 단일 원본이 ADR-005라서 D-103만으로는 부족하다.
- **A2(권고)**: 채널 구성 규칙이 `NotificationSetup`(notification-jar)과 `NotificationTester`(app-lab) 두 곳에 있다(Webhook 접두사, ME·BIZ, 빈 값, `NO_ACCESS_TOKEN` 자리 값). 지금은 같은 규칙임을 대조로 확인했다(NotificationSetup.java `slack()`·`kakao()` ↔ NotificationTester.java:92~132. 빈 값 판정은 `meReady()`로 먼저 하지만 결과는 같다). D-103의 변경 제안(알림 모듈에 채널 목록·채널별 시험 공개 메서드)을 다음 알림 PR 후속 과제로 결정 로그에 남겨 어긋남을 막을 것. 그 전까지 어긋나면 "실험실 시험 성공 ≠ 엔진 알림 성공"이 된다.
- **A3(권고)**: `NotificationTester.kakao`는 시험마다 새 `KakaoMemoChannel`을 만들어 거절 기억(`rejectedRefreshToken`)이 시험 사이에 남지 않는다. 리프레시 토큰이 거절된 상태에서 사용자가 10초마다 누르면 그때마다 카카오 토큰 엔드포인트를 부른다. 위험은 작지만(사람이 누를 때만, 10초 간격) risk-ops가 B7 검토 때 함께 보면 좋다.
- 확인한 것(문제 없음): 같은 설정 폴더 해석(LabWebConfiguration `store.paths().runtimeFile().getParent()` = EngineMain.java:118과 같은 방식), `Secret.of`가 값을 strip해 엔진과 출처 해시가 같음, 동시성(잠금 안 상태 전이·finally에서 해제, 예외 뒤에도 다음 시험 가능), 인터럽트 표시 복원, `LocalOnlyRequestFilter`가 새 POST에도 CSRF 403(시험으로 확인).

## 판정
수정 필요(A1: ADR-005에 새 REST API·오류 코드 등재). 머지 전 B7 risk-ops 검토 원문(`docs/reviews/pr-135/risk-ops.md`)도 있어야 한다.

## 재검토 2026-10-16
- 대상: PR #135 / 브랜치 `claude/project-thread-1ge5lz` 헤드 68d9e13(ef235ae 검토 반영 + 원문 되살림) / `git diff origin/main...HEAD` 전체(15개 파일, +973 −7)
- 원문 보존 확인: `git diff d86ffde 68d9e13 -- docs/reviews/pr-135/architect.md` 차이 없음. ef235ae가 지운 이 파일은 내가 쓴 d86ffde 내용 그대로 되살아났다
- 직접 실행한 것: `./gradlew build --init-script <미러>` → BUILD SUCCESSFUL, 종료 코드 0. `NotificationTesterTest` 13건(새 2건 `갱신이_시간_초과면_토큰_파일을_만들지_않는다`, `카카오가_새_리프레시_토큰을_주면_토큰_파일에_남겨_엔진이_이어_쓴다` 포함) 실패·건너뜀 0. 1차 이후 운영 코드(`autotrade-app-lab/src/main`, `autotrade-ui/src/main`)와 e2e 시험은 바뀌지 않아(`git diff d86ffde HEAD`에 시험 1개·문서 3개만 있음) 1차 e2e 결과(1 passed)가 그대로 유효하다

| # | 결과 | 근거 |
|---|---|---|
| B1 | 통과 | 위 빌드 직접 실행 성공 |
| B2 | 통과 | 1차와 같음. 추가 변경은 시험·문서뿐이라 모듈 의존 변화 없음, ArchUnit 통과 |
| B3 | 통과 | A1 반영: ADR-005 84행 공통 오류에 `TEST_RUNNING` 409·`TEST_TOO_SOON` 429, 5장 API 표에 `POST /api/settings/notification-test` 행(본문 없음, 응답 모양, 채널 순서, status 값, 사유 코드, 409·429·403, 최대 약 40초), 6장 설정 화면 표에 알림 시험 행. 코드와 대조: 응답 `TestView(sentAt, channels)`(NotificationTestController.java:80), `ChannelResult` 필드(NotificationTester.java:79), 사유 코드 `ok`(SendResult.java:18)·`kakao-reconsent-required`(KakaoMemoChannel.java:50) 일치 |
| B4 | 해당 없음 | 시뮬레이션 코드 변경 없음. 골든 테스트는 빌드에서 그대로 통과 |
| B5 | 해당 없음 | 금액 계산 없음 |
| B6 | 통과 | 1차 시험 그대로이고 risk-ops K1·K2 시험 2건과 거절 시 파일 없음 단언이 추가됨(NotificationTesterTest). 지운·건너뛴 시험 없음 |
| B7 | 통과 | risk-ops 검토 원문 `docs/reviews/pr-135/risk-ops.md` 있음(판정 "승인", 1ba352b 대상). 그 뒤 변경은 risk-ops 권고 K1·K2를 반영한 시험과 문서뿐이고 운영 코드 변경이 없어 risk-ops 판정 범위를 벗어나지 않는다. 보호 경로 변경 없음 |
| B8 | 통과 | 새 시험 값도 자리표시자(`PLACEHOLDER-KAKAO-REFRESH-0007`), 결과에 회전 토큰이 없음을 단언 |

### 1차 지적 처리
- A1(수정 필요): 해결(위 B3).
- A2(권고, 채널 구성 규칙 중복): D-103 "반영 기록"에 다음 알림 모듈 PR의 변경 제안으로 넘긴다고 기록됨. 받아들인다.
- A3(권고, 거절 상태 연타 시 토큰 엔드포인트 재호출): risk-ops가 검토했고 별도 지적 없음. 10초 간격·사람이 누를 때만이라 남은 위험으로 둔다.

### 판정
승인
