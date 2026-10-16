# risk-ops 검토: 알림 설정을 이름 있는 빌더로, 시간대·재동의 문구는 부르는 쪽이 (PR #128 후속)
- 대상: PR #132 / 브랜치 `claude/project-thread-7fa1t2` / 커밋 d06dd7c(PR head, `gh api repos/hwan82/autotrade-system/pulls/132`로 확인) / main(81b5285) 대비 diff `git diff origin/main...HEAD` 전체(커밋 1개, 11개 파일, +401 −40)
- 점검표: A
- 직접 실행한 것:
  - 이 컨테이너에서 Maven Central이 막혀 저장소 밖 스크래치의 Gradle init 스크립트로 공개 미러(`maven-central.storage-download.googleapis.com/maven2`)를 앞에 두고 실행했다(저장소 파일 변경 없음).
  - d06dd7c에서 `./gradlew build --continue` → **BUILD SUCCESSFUL**(138 tasks 모두 실행, 3분 17초). JUnit XML 집계(테스트/실패/오류/건너뜀): autotrade-risk 55/0/0/0, autotrade-architecture-test 44/0/0/0, autotrade-app-engine 55/0/0/0, autotrade-app-watchdog 53/0/0/0, autotrade-notification-jar 77/0/0/0, autotrade-config 40/0/0/0.
  - 변이 시험(실행 뒤 되돌림, 작업 트리 깨끗함 확인): `NotificationQueue.accept`의 억제 조건에서 `last.at() != null &&`를 지우고 `:autotrade-notification-jar:test --tests '*NotificationQueueTest'` → 16건 중 2건 실패, 바로 새 시험 `상한으로_버린_종류가_새_창에서_다시_오면_받는다`·`큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`. D-100 반영 기록의 "지우면 둘 다 실패" 주장과 일치한다. 기존 P1·P2 시험은 이 변이를 잡지 못했다(이번 시험이 실제로 빈 곳을 메운다).
  - 반복 시험: CPU 4코어 모두에 `yes` 부하를 건 채 `NotificationQueueTest` 전체를 `--rerun-tasks`로 15회 → 15/15 통과.
  - 재동의 문구 비교: 바뀌기 전 `NotificationQueue`의 문자열 연결과 새 `RuntimeConfig.Notification.KAKAO_RECONSENT_HELP`를 그대로 이어 붙여 비교 → 같음(사용자가 받는 문구 불변).
  - CI(d06dd7c, `gh api .../commits/d06dd7c/check-runs`): `Gradle build`·`Secret scan and collector boundary`(gitleaks)·`DB schema (PostgreSQL 16)`·`Lab screens (Playwright)` 모두 success. gitleaks는 로컬에서 따로 돌리지 않았다.

## 이 PR의 실전 경로 영향 범위(판정의 전제)
- [사실] 보호 경로 `autotrade-app-engine/*`·`autotrade-app-watchdog/*`·`autotrade-config/*`·`autotrade-notification-jar/*`(scripts/ci/protected-paths.txt)를 바꾸므로 실전 관련 PR이다. 사용자 직접 머지와 독립 QA 대상이다.
- [사실] 실전 엔진에서 바뀌는 것은 `EngineMain.setup`(EngineMain.java:129-144)이 알림 설정 값을 옮기는 방식(위치 인자 생성자 → 이름 있는 빌더)과, 표시 시간대·재동의 문구를 `RuntimeConfig.Notification` 상수(RuntimeConfig.java:73, 76-78)에서 넘기는 것뿐이다. 감시(WatchdogMain.java:126-141)도 같다.
- [사실] 알림 큐의 동작 변경은 재동의 알림 본문을 필드에서 읽는 것 하나다(NotificationQueue.java:226-227). 억제·상한·큐 가득·닫기 로직은 바뀌지 않았다(diff에 `accept`·`drop`·`close` 변경 없음).
- [사실] 주문 출구·Guard·블랙리스트·주문 큐·토큰 버킷·시세 게이트웨이·보호 시세·서버 측 손절 코드와 보호 테스트(`autotrade-architecture-test` 전체, RealTradingGuard·GuardedOrderGateway 테스트) 파일은 diff에 없다(`git diff origin/main...HEAD --stat`).
- [사실] 지운 공개 API(`NotificationSetup` 위치 인자 생성자, `logOnly()`, `NotificationSetup.DISPLAY_ZONE`)를 쓰는 곳은 저장소 어디에도 남지 않았다(`grep -rn "NotificationSetup\|DISPLAY_ZONE\|logOnly"`, 실험실 포함). 컴파일 성공으로도 확인.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 통과 | Guard 생성은 그대로 `startGuard` → `new RealTradingGuard(realTrading.accountRefs(), clock, ...)`이고 알림 큐는 Guard 다음에 만든다(EngineMain.java:92-94, 149-151). 새 빌더 값과 `RuntimeConfig.Notification` 상수는 Guard에 닿지 않는다. 새 상수는 `static final`이라 레코드 구성 요소·JSON 키가 아니어서 설정 파일로 바꿀 수 없다(RuntimeConfig.java:73, 76). `RealTradingGuardTest`·`EngineMainTest`(`실전_계좌가_설정돼_있어도_기동_직후_모두_OFF다`, `설정_파일에_활성화를_넣어도_Guard는_OFF로_시작한다` 포함) 전체 빌드에서 통과 |
| A2 | 통과 | 주문 출구·ArchUnit 규칙 diff 없음. 새 운영 코드(`NotificationSetup.Builder`)는 `ZoneId`·`Objects`·`String`만 쓰고 리플렉션 없음(NotificationSetup.java:63-148). `:autotrade-architecture-test:test` 44건(ArchitectureTest·ModuleOwnershipTest·RealPathReflectionTest) 통과, 고정 값 변경 없음 |
| A3 | 통과 | 증권사 주문 연동 코드 변경 없음. 알림 HTTP 대상(Slack·카카오)과 보내는 스레드 구조는 그대로다(NotificationSetup.java:159-163) |
| A4 | 해당 없음 | 주문 타임아웃·조회 우선 코드 변경 없음 |
| A5 | 해당 없음 | 멱등 키 생성·저장 코드 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 코드 변경 없음. 알림 모듈은 도메인을 모른다(ArchitectureTest 통과) |
| A7 | 통과 | 주문 큐·토큰 버킷·킬스위치 변경 없음. 알림 `offer`는 여전히 막지 않고 던지지 않는다(NotificationQueue.java:138-144, 변경 없음). 재동의 알림도 같은 `offer`로 들어간다(:227). `NotificationQueueTest#채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다`·`#채널이_Error를_던져도_넣는_쪽으로_번지지_않고_다음_알림은_보낸다` 통과 |
| A8 | 통과 | 엔진·감시의 프로세스 의존 변경 없음(엔진 `build.gradle.kts` diff 없음). 새 실패 지점은 빌더의 `displayZone` 필수 검사(NotificationSetup.java:55, 130-133)인데 엔진·감시는 고정 상수를 넘기므로 기동 때 던질 수 없다(EngineMain.java:141, WatchdogMain.java:138, `EngineMainTest`·`WatchdogMainTest#알림_설정_값을_이름대로_옮기고_시간대와_재동의_문구를_넘긴다`). 빈 재동의 문구는 일반 문구로 바뀌어 던지지 않는다(NotificationSetup.java:139-143, `NotificationSetupTest#재동의_문구는_부르는_쪽이_넘긴_것을_쓰고_없으면_일반_문구를_쓴다`). 5.2 정지 순서 코드 변경 없음 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 코드 변경 없음 |
| A10 | 통과 | 시험 값은 모두 자리표시자(`SLACK-URL-PH`, `REST-PH`, `CLIENT-PH`, `ACCESS-PH`, `REFRESH-PH`, `REST-KEY-PH`; EngineMainTest.java:262-285, WatchdogMainTest.java:88-111, NotificationQueueTest.java:339-350). `toString`은 여전히 값을 숨기고(NotificationSetup.java:220-222, `NotificationSetupTest` 비노출 시험 통과) 새 `hashCode`는 비밀값을 쓰지 않는다(:215-217). 새 `equals`는 비밀값을 비교만 하고 밖으로 내지 않는다(:205-213). 재동의 문구 두 개(RuntimeConfig.java:76-78, NotificationQueue.java:49-50)에 비밀값 없음. gitleaks CI success |
| A11 | 통과 | 보호 테스트 파일 diff 없음, `@Disabled` 추가 없음, 고정 값 변경 없음. 기존 시험에서 지운 줄은 import 순서 1줄과 지운 API 호출 2줄(`new NotificationSetup(...)`, `logOnly()`)뿐이고 같은 값을 빌더로 넣도록 바뀌었다(약화 아님, `git diff ... -- '*Test*.java' \| grep '^-'`). 새 동작마다 실패 경우를 포함한 시험이 있다: 시간대 누락 시 던짐(`NotificationSetupTest#표시_시간대는_부르는_쪽이_반드시_넣는다`), 값 자리 바뀜 검출(`EngineMainTest`·`WatchdogMainTest` 새 시험의 `isNotEqualTo`), 값 하나만 달라도 다름(`NotificationSetupTest#같은_값이면_같고_값이_하나라도_다르면_다르다`), 재동의 본문 주입(`NotificationQueueTest#재동의_알림_본문은_부르는_쪽이_넘긴_문구다`), QA Q1 순서 시험 둘(변이로 실패 확인, 위 "직접 실행한 것") |
| A12 | 통과 | 장애표 #8·#9·#10의 감지·알림 동작과 O1(긴급 Slack)의 전제가 바뀌지 않는다: 억제·상한 로직 diff 없음, 엔진·감시가 넘기는 표시 시간대는 이전과 같은 `Asia/Seoul`, 재동의 문구는 이전과 글자까지 같다(위 문자열 비교). 장애 테스트 8종의 전제(주문·대사·계좌 격리)에 닿는 코드 없음 |
| A13 | 통과 | 시장 분기 없음. 시간대는 표시용으로만 넘기고 억제 창 계산은 그대로 주입 `Clock`의 `Instant` 비교다(NotificationQueue.java:146-156). 표시 시간대가 부르는 쪽 상수로 옮겨졌을 뿐 값은 같다 |
| A14 | 통과 | D-100 "반영 기록"(D-100 80-85행)이 코드와 일치한다: 빌더로만 생성(위치 인자 생성자 없음), `displayZone` 필수, 값은 `RuntimeConfig.Notification.DISPLAY_ZONE`·`KAKAO_RECONSENT_HELP`, 라이브러리엔 일반 문구만(`DEFAULT_KAKAO_RECONSENT_HELP`에 `secrets.json`·`notification.kakao`·`실전` 없음, `NotificationSetupTest`가 확인). 결정 내용이 바뀌지 않아 새 D 번호를 쓰지 않은 판단에 동의한다(사용자가 받는 동작 불변). ADR-001 모듈 표가 갱신됐다. 기획서·config-keys.md는 바뀔 내용이 없다(설정 키 추가·변경 없음) |

## 점검표 밖 발견

### R1(권고): 큐 가득 시험의 이론상 시간 경쟁
- [사실] `큐가_차서_버린_긴급_알림도_같은_종류가_다시_오면_받는다`(NotificationQueueTest.java:249-276)는 `pending() < CAPACITY`가 될 때까지 넣고, 이어서 `pending()==CAPACITY`와 다음 `offer`가 false임을 본다. 보내는 스레드가 첫 알림을 꺼내 막히기 **전에** 64건이 다 들어가면 루프가 끝난 직후 스레드가 한 건을 꺼내 `pending`이 63이 되고, 그러면 단언이 실패하거나 `엔진 멈춤`이 받아질 수 있다.
- [추론] 실제로는 64건을 넣는 시간이 스레드 깨어나는 시간보다 길어 거의 나지 않는다. CPU 부하 15회 반복에서 재현하지 못했다.
- 권고: 시험의 sink 동작에 `CountDownLatch entered`를 두어 보내는 스레드가 첫 알림에서 막힌 것을 확인한 뒤 채우면 경쟁이 사라진다. 판정에 영향 없음.

### R2(참고): 공개 `equals`가 비밀값을 비교한다
- `NotificationSetup.equals`(NotificationSetup.java:205-213)는 시험용이라 적혀 있으나 공개 메서드다. 값을 밖으로 내지 않으므로 비밀값 노출은 아니다. 운영 코드에서 쓰는 곳은 없다(grep). 조치 불요.

## 판정
승인 (risk-ops 기준. 실전 관련 PR이므로 독립 QA와 사용자 직접 머지가 남아 있다. R1은 권고)
