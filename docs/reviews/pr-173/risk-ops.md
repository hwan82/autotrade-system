# risk-ops 검토: 감시 운영 배선이 알림 큐 받음 여부를 넘기는지 시험(PR #170 QA Q1)
- 대상: PR #173 / 헤드 커밋 **3c05a92**(브랜치 `claude/project-thread-0ssuqw`) / main 대비 diff 전체(`git diff origin/main...HEAD`, merge-base f81f2f7, origin/main = dc41d07, 커밋 1개, 파일 4개 +32 −0)
- 점검표: A
- 직접 실행한 것(별도 clone, 메인 작업 폴더와 분리):
  - `LC_ALL=C.UTF-8 ./gradlew -I <저장소 밖 mirror.gradle> build`(헤드 3c05a92): **BUILD SUCCESSFUL in 3m 22s**, 종료 코드 0, 138 작업 모두 실행. `*/build/test-results/test/*.xml` 합계 **1,586건, 실패·오류·건너뜀 0**. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 64, `:autotrade-app-watchdog` 93(새 시험 `운영_배선은_알림_큐가_받았는지를_그대로_감시에_넘긴다` 통과 기록 확인), `:autotrade-notification-jar` 86.
  - 배선 변이 3개(`WatchdogMain.java:124`의 `notifications::offer`만 바꿈, 한 번에 하나, `:autotrade-app-watchdog:test --rerun-tasks`, 각 뒤 `git checkout`, 마지막 `git status` 깨끗):
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | W1 | 큐에 넣고 늘 true(QA 변이 Q1) | 잡힘 — 93건 중 1건 실패, `WatchdogMainTest#운영_배선은_알림_큐가_받았는지를_그대로_감시에_넘긴다`("Expecting value to be false but was true") |
    | W2 | 큐에 넣고 늘 false | 잡힘 — 같은 시험 1건("to be true but was false") |
    | W3 | 받음 여부 뒤집기 | 잡힘 — 같은 시험 1건 |
    세 변이 모두 이 PR의 새 시험만 잡는다. 즉 이 시험 없이는 W1이 생존한다는 PR #170 QA Q1 지적이 맞고, 이 PR이 그 빈틈을 메운다.
  - `git merge-tree --write-tree origin/main HEAD`: **`docs/qa-out-status.md` 한 곳 충돌**(아래 K1). 코드 파일 충돌 없음.

## 검토 범위와 영향
- 운영 코드 변경: `Watchdog.java:416-419` 패키지 전용 읽기 `alerts()` 하나뿐이다. 필드 `alerts`를 그대로 돌려주며 상태·흐름을 바꾸지 않는다. 호출하는 곳은 `WatchdogMainTest.java:256` 하나(grep, build 산출물 제외). 기존 `notifications()`(:412)와 같은 시험용 주입 없는 읽기다.
- 운영 배선 자체(`WatchdogMain.java:124` `notifications::offer`)는 바뀌지 않았다. `NotificationQueue#offer`는 던지지 않고 `boolean`을 돌려준다(NotificationQueue.java:137-143). 억제는 같은 대상·같은 종류가 마지막으로 받은 시각부터 `QUIET`(10분) 안일 때(:153-157), 닫힘이면 false(:146).
- 새 시험(`WatchdogMainTest.java:250-269`)은 실제 기동 `WatchdogMain.start`로 만든 감시의 `Alerts`로 ① 결측 첫 알림 true ② 같은 대상·종류 false(억제) ③ 종류가 바뀐 `READING_RECOVERED` true ④ `close()` 뒤 false를 본다. 대상 이름 `wiring-probe`는 감시 점검이 쓰는 대상(프로세스 `engine`·`gateway`·`lab`, WatchdogSettings.java:56, 자원 이름)과 겹치지 않는다. `finally`로 감시를 닫는다.
- 실전 엔진(`autotrade-app-engine`)·Guard(`autotrade-risk`)·주문 경로·`autotrade-architecture-test`·`autotrade-notification-jar`·`docs/team`·`scripts`·`.github` 변경 없음(`git diff --stat origin/main...HEAD -- <그 경로들>` 빈 결과). 감시 모듈은 보호 경로(`scripts/ci/protected-paths.txt:9` `autotrade-app-watchdog/*`, CODEOWNERS :6)라 실전 관련 PR로 다룬다.

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·Guard 설정 로딩 변경 없음(diff에 `autotrade-risk`·`autotrade-app-engine` 없음). 회귀: `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. 감시는 주문 API를 부르지 않는다. ArchUnit 규칙·소유 표 변경 없음, `:autotrade-architecture-test:test` 46건 통과. 새 타입 없음 |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 운영 동작 변경 없음(`alerts()`는 읽기 전용, Watchdog.java:416-419). 정지 순서 5.2 0번(장애표 #10)을 받치는 결측 알림 재전송(D-111 결정 1)이 운영 배선에서도 성립함을 이제 실제 기동 경로로 고정한다(W1·W2·W3 잡힘). 감시는 여전히 실전 엔진을 멈추지 않고 정지 요청 파일·알림만 만든다. `:autotrade-app-engine:test` 64건 통과 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 추가 줄의 문자열은 대상 `wiring-probe`, 고정 문구, 자원 이름 `cpu.system`뿐이다. 추가 줄을 `appkey|appsecret|secret|token|password|계좌번호 모양` 정규식으로 찾아 0건. gitleaks는 이 컨테이너에 없어 CI에 맡긴다 |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·GuardedOrderGateway 테스트, 장애 테스트) 변경 없음. 지운 줄 0(diff +32 −0), `@Disabled` 0건, 기존 기대값 변경 없음. 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 시험은 억제(false)·비억제(true)·닫힘(false)의 실패 경우를 모두 담고 변이 3개를 모두 잡는다 |
| A12 | 통과 | 장애 대응표 6장 행 수 17 그대로(직접 셈), `docs/team` 변경 없음. 바뀐 것은 시험과 시험용 읽기뿐이라 장애 테스트 8종 전제와 해외 O1~O8 경로에 영향 없음. 장애표 #10 감지(결측 알림이 채널에 닿음)의 배선 쪽 회귀를 막는 시험이 늘었다 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-111 "후속 반영" 절(D-111…md:49-52)의 설명이 코드·시험과 맞다: 시험 이름 실재(WatchdogMainTest.java:251), `alerts()` 추가와 운영 동작 무변경, "늘 true·늘 false 둘 다 잡음"을 W1·W2로 직접 확인. R1을 그대로 두는 판단은 PR #170 risk-ops K3과 같다. `docs/qa-out-status.md` 줄이 PR #170 QA 판정 처리(Q1 해결, R1 미처리)를 기록한다 |

## 점검표 밖 발견
- **K1(머지 전 처리, 판정 영향 없음) — `docs/qa-out-status.md` 충돌**: 이 브랜치는 f81f2f7에서 갈라졌고, 그 뒤 main에 들어온 dc41d07(PR #171)이 같은 표 끝에 PR #169 처리 줄을 더했다. 이 브랜치도 같은 자리에 PR #170 처리 줄을 더해 `git merge-tree`가 충돌을 낸다. 두 줄 모두 남기고(날짜 순서상 PR #169 줄 다음 PR #170 줄) 합친 뒤 CI를 다시 돌리면 된다. 코드 파일은 겹치지 않는다. 합친 결과가 그 두 줄 외에 다른 것을 바꾸면 재검토가 필요하다.
- **K2(참고) — 세 번째 단언은 INFO 창 상한에 기댄다**: `READING_RECOVERED`는 INFO라(EventRoutes.java:91) 같은 10분 창에서 이미 20건이 받혔으면 상한으로 버려진다(NotificationQueue.java:162-164). 시험 시계는 고정이라 기동 알림(`WATCHDOG_STARTED`)과 첫 점검 알림(프로세스 3개 하트비트 등)이 모두 같은 창에 든다. 지금 그 수는 20보다 훨씬 작아 실제로 흔들리지 않았다(전체 빌드 1회 + 변이 실행 6회 모두 원래 코드로는 통과·변이로는 정확히 이 시험만 실패). 앞으로 감시가 기동 직후 알림을 크게 늘리면 이 단언이 다른 이유로 실패할 수 있다는 정도로만 적는다.
- PR #170 독립 QA Q1(변이 Q1 생존)이 이 PR로 해결됨을 W1로 확인했다. PR #170 risk-ops K1(표시를 "모두 읽힘" 점검에서 지우는 변이 M10 생존)은 이 PR 범위 밖이며 여전히 열려 있다(이 diff는 `WatchdogTest`를 바꾸지 않는다). 다음 감시 PR에서 다루기를 다시 권한다.

## 판정
승인
