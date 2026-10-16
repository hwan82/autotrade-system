# risk-ops 검토: 알림 재동의 문구 전체 시험·공개 5인자 NotificationQueue 생성자 삭제 (PR #132 QA Q1·R1)
- 대상: PR #134 / 브랜치 `claude/project-thread-dvuau0` / 커밋 0a0e8c4 / main(a2f94ac, 머지 기준점과 같음) 대비 diff `git diff origin/main...HEAD` 전체(커밋 1개, 4개 파일, +35 −7)
- 점검표: A
- 직접 실행한 것:
  - Maven Central 제한 때문에 저장소 밖 스크래치의 Gradle init 스크립트(미러)를 `--init-script`로 주고 실행했다(저장소 파일 변경 없음).
  - 0a0e8c4에서 `./gradlew build --continue`, 이어 `./gradlew build` 재실행 → **BUILD SUCCESSFUL**(138 actionable tasks). JUnit XML 집계(테스트/실패/오류/건너뜀): 전체 1,447/0/0/0, autotrade-risk 55/0/0/0, autotrade-architecture-test 44/0/0/0, autotrade-app-engine 55/0/0/0, autotrade-app-watchdog 53/0/0/0, autotrade-notification-jar 77/0/0/0, autotrade-config 42/0/0/0(새 `RuntimeConfigNotificationTest` 2건 포함, PR #132 때 40건). 새·바뀐 시험 결과 파일 `TEST-com.autotrade.config.RuntimeConfigNotificationTest.xml`, `TEST-com.autotrade.app.engine.command.NotifyingCommandAuditLogTest.xml` 생성 확인.
  - 변이 시험(QA M15: 문구에서 " 실전 엔진·감시를 다시 시작하세요." 삭제)을 돌리려 했으나 이 세션의 권한 판정이 작업 트리 수정을 막아 실행하지 못했다. 대신 코드로 확인: 새 시험은 문구 전체를 `isEqualTo`로, 그리고 `contains("실전 엔진·감시를 다시 시작하세요")`로 고정하므로(RuntimeConfigNotificationTest.java:14-19) 그 변이는 반드시 실패한다 `[추론, 문자열 동일 비교라 확실도 높음]`.
  - CI 상태(`gh api .../check-runs`)도 같은 이유로 조회하지 못했다. 판정은 위 로컬 빌드에 기댄다.

## 이 PR의 실전 경로 영향 범위(판정의 전제)
- [사실] 보호 경로 `autotrade-config/*`·`autotrade-notification-jar/*`(scripts/ci/protected-paths.txt:11, 16)와 `autotrade-app-engine` 시험을 바꾸므로 실전 관련 PR이다. 사용자 직접 머지와 독립 QA 대상이다.
- [사실] 운영 코드 변경은 `NotificationQueue`의 공개 5인자 생성자 삭제(javadoc 정리 포함) 하나다(NotificationQueue.java:82-96). 남은 공개 생성자는 문구를 받는 6인자(:93-96)이고, 패키지 내부 생성자 둘(:98-100, :102-)은 그대로다.
- [사실] 지운 생성자를 부르던 곳은 엔진 시험 `NotifyingCommandAuditLogTest` 하나였고 6인자로 바뀌었다(NotifyingCommandAuditLogTest.java:40-43). 저장소 전체 `grep -rn "new NotificationQueue("` 결과 운영 호출부는 `NotificationSetup.start`(NotificationSetup.java:162, 6인자) 한 곳뿐이고 실험실 모듈 호출은 없다. 컴파일 성공으로도 확인.
- [사실] 엔진·감시의 알림 조립(`EngineMain.startNotifications`·`setup`, EngineMain.java:117-125, 141-142; WatchdogMain.java:138-139), 재동의 문구 상수 값(RuntimeConfig.java:76-78), 큐의 억제·상한·큐 가득·닫기 로직은 diff에 없다.
- [사실] 주문 출구·Guard·블랙리스트·주문 큐·토큰 버킷·시세 게이트웨이·보호 시세·서버 측 손절 코드와 보호 테스트(`autotrade-architecture-test` 전체, RealTradingGuard·GuardedOrderGateway 테스트, 장애 테스트) 파일은 diff에 없다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성·기동 코드 diff 없음. 바뀐 것은 알림 큐 생성자 시그니처뿐이고 Guard에 닿지 않는다. 새 시험이 고정하는 `RuntimeConfig.Notification` 상수는 `static final`이라 설정 파일로 바꿀 수 없다(RuntimeConfig.java:73, 76). `RealTradingGuardTest`(autotrade-risk 55건)·`EngineMainTest`(기동 직후 OFF·설정 파일 활성화 무시 시험 포함, app-engine 55건) 이번 빌드에서 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 규칙·고정 값 diff 없음. 지운 생성자는 위임만 하던 코드라 새 경로가 생기지 않는다. `:autotrade-architecture-test:test` 44/0/0/0(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest) |
| A3 | 해당 없음 | 증권사 주문 연동 코드 변경 없음. 알림 채널 구성(NotificationSetup.java:159-163) 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 우선 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음. 알림 모듈은 도메인을 모른다(ArchitectureTest 통과) |
| A7 | 통과 | 주문 큐·토큰 버킷·킬스위치 변경 없음. 알림 `offer`·억제 로직 diff 없음. 엔진 쪽 `NotifyingCommandAuditLogTest`가 6인자로 같은 큐를 만들어 그대로 통과(단언 불변) |
| A8 | 통과 | 엔진·감시 프로세스 의존·기동 순서 변경 없음. 6인자 생성자는 `kakaoReconsentHelp`가 null이면 던지지만(NotificationQueue.java:110) 운영 호출부 `NotificationSetup.start`는 빌더에서 빈·null 문구를 일반 문구로 바꾼 값을 넘기므로(NotificationSetup.java:79, 139-143) 기동 때 던질 수 없다. `NotificationSetupTest#재동의_문구는_부르는_쪽이_넘긴_것을_쓰고_없으면_일반_문구를_쓴다` 통과. 5.2 정지 순서 코드 변경 없음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 추가된 줄에 키·계좌번호·토큰 값 없음(`git diff ... \| grep -iE 'token\|key\|secret\|account'`의 추가 줄은 설정 키 이름 `notification.kakao.refresh-token`을 담은 안내 문구뿐, 값 아님, RuntimeConfigNotificationTest.java:15-17). gitleaks CI 결과는 조회하지 못해(위 "직접 실행한 것") diff 수동 점검으로 판정 |
| A11 | 통과 | 보호 테스트 파일 diff 없음, `@Disabled`·`assume` 없음, 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 시험에서 지운 줄은 `NotifyingCommandAuditLogTest`의 5인자 호출 1줄이고 같은 값에 일반 문구를 명시한 6인자로 바뀌었다(동작 동일, 단언 불변). 새 시험 `RuntimeConfigNotificationTest#재동의_안내는_토큰을_넣을_곳과_다시_시작할_프로세스와_그동안의_채널을_모두_알린다`는 문구 전체 동일 비교라 단계 하나만 빠져도 실패한다(QA 변이 M15를 잡음, 위 `[추론]`). `#알림_표시_시간대는_서울이다`도 추가 |
| A12 | 통과 | 장애표 #8·#9·#10의 감지·알림 동작과 O1(긴급 Slack)의 전제가 바뀌지 않는다: 사용자에게 가는 재동의 문구·표시 시간대 값은 이전과 같고(RuntimeConfig.java diff 없음) 이번에 시험으로 고정됐다. 장애 테스트 8종의 전제(주문·대사·계좌 격리)에 닿는 코드 없음 |
| A13 | 해당 없음 | 시장별 경로 변경 없음. `DISPLAY_ZONE`은 알림 표시용이며 계산에 쓰지 않는다(RuntimeConfig.java:69-73 주석) |
| A14 | 통과 | D-100 반영 기록 추가분(D-100-notification-wiring-watchdog-engine.md:86-88)이 코드와 일치: 시험 이름 일치(RuntimeConfigNotificationTest.java:12), 공개 생성자 6인자 하나(NotificationQueue.java:93-96), 5인자를 쓰던 곳이 엔진 시험 하나였다는 설명(grep으로 확인). 결정 내용(문구·채널·억제 규칙) 불변이라 새 D 번호를 쓰지 않은 판단에 동의. R1 삭제는 "추천안 채택(자율)"으로 표시되고 대안·되돌릴 조건이 적혀 있다. 설정 키 변경 없음 → config-keys.md·기획서 갱신 불필요 |

## 점검표 밖 발견

### R1(참고): 패키지 내부 생성자는 여전히 일반 문구로 위임한다
- [사실] `NotificationQueue(Notifier, EventRoutes, Clock, ZoneId, String, LogChannel)`(NotificationQueue.java:98-100)는 `DEFAULT_KAKAO_RECONSENT_HELP`로 위임한다. 패키지 내부 전용이라 이 모듈 밖(엔진·감시)에서는 부를 수 없고 시험(NotificationQueueTest)만 쓴다. QA R1의 우려(밖에서 문구를 빠뜨림)는 해소됐다. 조치 불요.

### R2(참고): 엔진 시험이 일반 문구를 쓴다
- `NotifyingCommandAuditLogTest`(NotifyingCommandAuditLogTest.java:42-43)는 운영 엔진과 달리 `DEFAULT_KAKAO_RECONSENT_HELP`를 넘긴다. 이 시험은 재동의 알림을 다루지 않아 영향 없다. 운영과 같게 `RuntimeConfig.Notification.KAKAO_RECONSENT_HELP`를 넘기면 더 자연스럽지만 판정에 영향 없음.

### 검증 한계
- 변이 시험 실행과 CI 조회는 이 세션의 권한 판정으로 막혀 하지 못했다. 둘 다 점검표 항목의 유일한 근거가 아니다(A11은 시험 코드의 전체 동일 비교, A10은 diff 수동 점검과 로컬 빌드). 독립 QA가 M15 변이를 다시 돌려 확인하기를 권한다.

## 판정
승인 (risk-ops 기준. 실전 관련 PR이므로 독립 QA와 사용자 직접 머지가 남아 있다. R1·R2는 참고)
