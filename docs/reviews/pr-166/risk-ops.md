# risk-ops 검토: D-110 감시 회복 뒤 재결측 경고·점검 단계 한 곳으로(PR #163 QA 후속) + 감시 기동 실패 문구 괄호 정리
- 대상: PR #166 / 헤드 커밋 **9235416**(92354164cc3d07eceb87e0e75bc6679465699045, 브랜치 `claude/project-thread-gpua1p`) / main 대비 diff 전체(`git diff origin/main...HEAD`, 머지 기준 9346538 = 현재 origin/main, 커밋 2개 e3f61bb·9235416, 파일 9개 +300 −26). 검토 중 9235416이 올라와 그 커밋도 따로 확인하고 빌드·변이를 새 헤드에서 다시 돌렸다.
- 점검표: A
- 직접 실행한 것(별도 clone, 메인 작업 폴더와 분리. `-I mirror.gradle`은 Maven Central 429 회피용 저장소 밖 미러 init 스크립트로 빌드 내용은 바꾸지 않는다):
  - `./gradlew build`(e3f61bb): BUILD SUCCESSFUL in 4m 6s, 종료 코드 0.
  - `./gradlew build`(헤드 9235416): **BUILD SUCCESSFUL in 47s**, 종료 코드 0(입력이 같은 모듈은 앞 빌드 결과를 UP-TO-DATE로 재사용, `:autotrade-app-watchdog:test`·`:autotrade-architecture-test:test`는 다시 돎). `*/build/test-results/test/*.xml` 합계 **1,572건, 실패·오류·건너뜀 0**. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 62, `:autotrade-app-watchdog` 81, `:autotrade-monitor-agent-jar` 36.
  - `git merge-tree --write-tree origin/main HEAD` 충돌 없음.
  - 변이 12개(헤드 9235416, 한 번에 하나, 범위 `:autotrade-app-watchdog:test :autotrade-monitor-agent-jar:test`, 각 뒤 `git checkout -- .`, 마지막 `git status` 깨끗):
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | R0 | `newName`을 main대로 `!warnedReadings.containsAll(missing)`만 | 잡힘(`회복을_알린_값이_다른_값의_경고_뒤_다시_빠지면_바로_알린다`) |
    | R2 | `newName`을 QA 제안대로 `!missingSinceRecovery.containsAll(missing)`만 | 잡힘(`간격이_찬_경고_뒤에_다시_빠진_값은_바로_알린다`) |
    | QM1 | 정지 요청 쓰기 실패 ERROR 로그를 `WatchdogLog` 없이 `LOG.log` | 잡힘(`감시_로그가_던져도_한_정지_요청_파일_쓰기_실패가_다른_파일과_켜짐_알림을_막지_않는다`) |
    | QM2 | 재기동 감지 WARN을 `LOG.log` | 잡힘(`감시_로그가_던져도_재기동_알림이_나간다`) |
    | QM3 | UNREADABLE WARN을 `LOG.log` | 잡힘(`감시_로그가_던져도_하트비트_읽기_실패와_회복_알림이_나간다`) |
    | QM4 | ALIVE INFO를 `LOG.log` | 잡힘(같은 시험) |
    | QM7 | 정지 요청 해제 INFO를 `LOG.log` | 잡힘(`감시_로그가_던져도_정지_요청_해제_알림이_나간다`) |
    | QM8 | `stage` catch 안 로그를 `LOG.log` | 잡힘(`점검_단계가_던지고_그_실패_로그도_던져도_다음_단계로_넘어간다`) |
    | QM12 | DEBUG 줄이 있는 주기에 들고 있던 줄을 다시 내지 않음 | 잡힘(`MonitorAgentTest#DEBUG가_켜진_주기에도_내지_못한_WARN을_DEBUG_줄_앞에_다시_낸다`) |
    | S1 | `tick()`의 자기 하트비트를 `stage` 없이 `heartbeat.beat()` | **생존**(아래 K3, 이중 방어라 결함 아님) |
    | S2 | `stage`가 삼키지 않고 다시 던짐 | 잡힘(2건) |
    | S3 | 자원 점검 단계 대신 값을 `UNKNOWN` 대신 `CLEAR` | **생존**(아래 K2, 예전 catch와 같은 시험 공백) |
    PR #163 QA가 생존으로 보고한 QM1~QM4·QM7·QM8·QM12가 이 헤드에서는 모두 잡힌다.
  - 탐침 1개(임시 시험, 실행 뒤 삭제): 필수 값 셋(cpu·메모리·드라이브)이 5초마다 오가는 세 패턴을 20분(240점검) 돌려 결측 WARN 수를 쟀다. 헤드 5·9·8회, main 논리(R0) 4·7·5회, 회복 INFO는 모두 2회. 점검마다 경고가 나는 폭주는 없고 간격당 수가 묶인다(D-110 결정 1의 주장과 맞음, 위험 1의 "값 하나당 최대 두 번 더"와 맞음).

## 검토 범위와 영향
- 운영 코드 변경: 감시 프로세스 `Watchdog.java`(`newName` 판정 1줄, 점검 네 단계를 `stage` 실행기로), `WatchdogMain.java`(프로그램 오류 첫머리 문구). 둘 다 보호 경로(`scripts/ci/protected-paths.txt:9`). 나머지는 시험(`WatchdogTest` +7, `WatchdogMainTest` 단언 2줄 추가, `MonitorAgentTest` +1, 보호 경로 `:17`)과 문서(D-110 새 결정, D-106 후속 절, 결정 색인 1줄, 기획서 v14.49 MON-02).
- 실전 영향 경로 확인: 정지 요청 판정 `Watchdog.pressure`(Watchdog.java:202-225)와 `StopRequests` 판정·파일 처리(StopRequests.java)는 바뀌지 않았다. `stopRequests.update`는 여전히 자원 단계의 판정을 받고(Watchdog.java:128), 자원 단계가 던지면 예전과 같이 `UNKNOWN`·"자원 값을 읽지 못함"(:142)으로 정지 요청을 새로 만들지 않고 켜진 것은 유지한다. 하트비트 판정(`HeartbeatWatch`)·알림 종류·`EventRoutes` 변경 없음.
- 실전 엔진(`autotrade-app-engine`)·Guard(`autotrade-risk`)·주문 경로·`autotrade-architecture-test`·`docs/team`·`scripts`·`.github` 변경 없음(`git diff --stat origin/main...HEAD -- <그 경로들>` 빈 결과).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·Guard 설정 로딩 변경 없음(diff에 `autotrade-risk`·`autotrade-app-engine` 없음). 회귀: `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. 감시는 주문 API를 부르지 않는다. ArchUnit 규칙 변경 없음, `:autotrade-architecture-test:test` 46건 통과(`ArchitectureTest`·`ModuleOwnershipTest`·`RealPathReflectionTest` 포함). 새 타입은 `Watchdog` 안 private record `Assessment`뿐이라 소유 표 영향 없음 |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 정지 순서 5.2 0번(장애표 #10)을 만드는 감시 판정과 정지 요청 파일 처리는 그대로이고, 네 단계가 서로 독립(한 단계가 던져도 다음 단계 진행)인 것도 그대로다(Watchdog.java:107-138, :149-164). 이제 단계 실행기를 직접 시험한다: `점검_단계가_던지고_그_실패_로그도_던져도_다음_단계로_넘어간다`(QM8·S2로 잡는 것 확인). 로그가 던져도 정지 요청 켜짐·해제·쓰기 실패 중 남은 파일 쓰기가 이어짐을 새 시험 2건이 고정(QM1·QM7 잡힘). 감시는 여전히 실전 엔진을 멈추지 않는다(알림 문구 :132). 미세한 의미 변화 1건은 K2(실사용 영향 없음) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 바뀐 로그·알림 문구는 단계 이름 + 예외 클래스 이름(Watchdog.java:153), 자원 이름 집합뿐이다. `점검_단계_실패는_단계_이름과_예외_종류로_남긴다`가 예외 메시지("비밀이 섞일 수 있는 문구")가 로그에 들어가지 않음을 고정한다. 기동 실패 표준 오류 줄은 예외 메시지를 그대로 적지만(WatchdogMain.java:201, :211) 이 PR에서 바뀐 것이 아니다(D-106). 추가 줄을 `appkey|appsecret|secret|token|password|계좌번호 모양` 정규식으로 찾아 0건. gitleaks는 이 컨테이너에 없어 CI에 맡긴다 |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·GuardedOrderGateway 테스트, 장애 테스트) 변경 없음. 보호 경로 안 기존 시험은 지우거나 기대값을 바꾼 것이 없다(`WatchdogMainTest`는 `doesNotContain(")(")` 단언 2줄 추가뿐). 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작마다 실패 경우 시험이 있다: R1(`회복을_알린_값이_다른_값의_경고_뒤_다시_빠지면_바로_알린다`, R0로 잡힘), 대안 ②를 버린 근거(R2로 기존 시험이 잡음), Q1 6건, Q2 1건. 변이 12개 중 10개 잡힘, 생존 2개는 K2·K3. 새 시험의 전역 로거 조작(`com.autotrade` 핸들러·수준)은 `finally`로 원복하고, 시험 병렬 실행 설정이 없어(build-logic·`junit-platform.properties` 없음) 다른 시험에 새지 않는다 |
| A12 | 통과 | 장애 대응표 6장 행 수 17 그대로(직접 셈), `docs/team` 변경 없음. 바뀐 것은 결측 WARN 판정(더 일찍 경고하는 쪽)과 단계 실패 처리 구조, 기동 실패 문구뿐이고 장애 테스트 8종의 전제(감시는 정지 요청 파일만 만들고 실전 엔진을 멈추지 않음, 장애표 #8 재기동 알림 문구)를 바꾸지 않는다. 해외 O1~O8 무관 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-110 결정 1~3이 코드와 맞다(Watchdog.java:255, :107-164, 시험 이름 실재 확인: WatchdogTest.java:660-776, :833, :479, MonitorAgentTest.java:459). 결정 색인 1줄, 기획서 v14.49 변경 이력·MON-02 행이 D-110을 가리킨다. 9235416의 문구 변경은 D-106 후속 절과 D-110 "함께 고친 것"에 적혔고, runbook(`docs/runbooks/collector.md:62`)이 찾는 첫머리 "감시가 기동 중 프로그램 오류로 멈췄습니다"는 새 문구에도 앞부분 그대로 있다. 표현 정밀도 권고 K1, 상호 참조 K4는 판정에 넣지 않는다 |

## 점검표 밖 발견
- **K1(권고) — R1 경우의 "바로 알림"은 알림 채널에서는 대개 억제된다**: 결측 알림은 대상 `"readings"` 하나로 넣는다(Watchdog.java:274). 알림 큐는 같은 대상·같은 종류를 10분 안에 다시 받으면 버리고 센다(NotificationQueue.java:150-157). R1 순서(cpu 결측 → cpu 회복 → 드라이브 결측 → cpu 재결측)에서 cpu 재결측 알림은 몇 초 전 드라이브 결측 알림과 대상·종류가 같아 큐에서 억제되고, 다음 같은 알림 본문에 "생략 1건"으로만 남는다. 즉 이 PR로 바로 나가는 것은 **WARN 로그**이고, 채널의 마지막 메시지는 "드라이브 결측"(값 이름은 틀리지만 "다시 읽음"이 아닌 안전한 쪽)이다. 시험의 `sent`는 큐 앞 가짜(WatchdogTest.java:65)라 이를 보지 않는다. D-110 위험 1이 "알림 큐가… 10분에 한 번만 보내므로 알림 수는 늘지 않고"라고 적어 사실상 인정하지만, 결정 1·색인·MON-02의 "바로 WARN·알림/경고·알림"은 채널 도달처럼 읽힌다. 다음 감시 작업에서 문장을 "바로 WARN, 알림은 큐 억제(D-100) 안에서"로 고치거나, 결측 알림 대상을 값 이름별로 나눌지 정하기를 권한다. 정지 요청 판정에는 영향이 없어 판정에 넣지 않는다.
- **K2(참고) — 자원 단계를 하나의 `stage`로 묶어 생긴 미세한 의미 변화와 시험 공백**: 예전에는 `pressure = pressure(snapshot)` 뒤 `detail(snapshot)`이 던지면 판정(예: CRITICAL)은 남고 사유만 기본 문구였다. 이제는 람다 전체가 버려져 `UNKNOWN`이 된다(정지 요청을 새로 만들지 않음). `detail`(Watchdog.java:310-319)은 산술·문자열 연결뿐이라 실제로 던질 경로가 없다 `[추론]`. 대신 값 자체(`Assessment.UNKNOWN`)를 바꾸는 변이 S3이 생존한다. 예전 catch 기본값도 시험되지 않았고 D-110 위험 2가 이를 적었다. 이 값이 `CLEAR`로 바뀌면 자원 단계가 던질 때 정지 요청이 해제될 수 있으므로, 다음 감시 작업에서 `stage`에 넘기는 대신 값이 `UNKNOWN`인지 고정하는 시험(예: `Assessment.UNKNOWN.pressure()` 단언 또는 수집원 주입으로 `reportMissing`을 던지게 하기)을 권한다.
- **K3(참고)** — 변이 S1(자기 하트비트를 `stage` 없이 직접 호출) 생존은 `HeartbeatWriter`가 자기 로그 출력 실패를 이미 삼키기 때문이다(HeartbeatWriter.java:98-104, PR #163 K1 수정). `stage` 감싸기는 이중 방어이며 코드 주석(Watchdog.java:109)도 그렇게 적는다. 결함 아님.
- **K4(참고) — 문서 상호 참조**: D-106 후속 절이 "실전 엔진 D-107 후속 R1과 같은 정리"라고 적지만 이 브랜치와 main의 D-107에는 그런 후속 절이 없고 `EngineMain.java:278`은 아직 괄호 문구다(열린 PR #165 쪽 변경으로 보임 `[추론]`). #165가 먼저 머지되지 않으면 참조가 비어 있다. 또 머지된 D-106 결정 2 본문에 괄호 주석을 넣어 예전 문구를 그대로 둔 채 바뀜을 표시했는데, 판정·종료 코드는 그대로이고 D-110에도 기록되어 결정을 뒤집는 변경은 아니다.
- 이전 검토 PR #163 risk-ops 재검토와 독립 QA의 R1·Q1·Q2는 위 변이(R0·QM1~QM4·QM7·QM8·QM12)와 시험으로 해결됨을 확인했다. QA가 제안한 대안 ②를 버린 근거(R2에서 `간격이_찬_경고_뒤에_다시_빠진_값은_바로_알린다` 실패)도 직접 재현했다.

## 판정
승인
