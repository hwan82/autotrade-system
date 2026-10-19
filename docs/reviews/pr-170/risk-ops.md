# risk-ops 검토: D-111 감시 억제된 결측 알림 재전송·판정과 사유 분리(PR #166 QA 후속)
- 대상: PR #170 / 헤드 커밋 **038a1d2**(038a1d22a249b8b14b8eeb5a69bdae71625f1ac8, 브랜치 `claude/project-thread-93kj2r`) / main 대비 diff 전체(`git diff origin/main...HEAD`, origin/main = e91f260, 커밋 1개, 파일 6개 +345 −18)
- 점검표: A
- 직접 실행한 것(별도 clone, 메인 작업 폴더와 분리):
  - `./gradlew build`(헤드 038a1d2): **BUILD SUCCESSFUL in 4m 16s**, 종료 코드 0, 138 작업 모두 실행. `*/build/test-results/test/*.xml` 합계 **1,584건, 실패·오류·건너뜀 0**. 모듈별: `:autotrade-risk` 55, `:autotrade-architecture-test` 46, `:autotrade-app-engine` 64, `:autotrade-app-watchdog` 91, `:autotrade-notification-jar` 86.
  - `git merge-tree --write-tree origin/main HEAD` 충돌 없음. D-111 번호는 origin/main과 다른 원격 브랜치 100개 어디에도 없다(이 브랜치만).
  - 변이 12개(한 번에 하나, `:autotrade-app-watchdog:test --rerun-tasks`, 각 뒤 `git checkout`, 마지막 `git status` 깨끗):
    | 변이 | 내용 | 결과 |
    |---|---|---|
    | M1 | 재전송 조건에서 `|| missingAlertPending` 삭제 | 잡힘(`큐가_억제한_결측_알림은_간격이_찬_반복_경고_때_한_번_다시_넣는다`, `결측_알림을_넣다_던지면_다음_반복_경고_때_다시_넣는다`) |
    | M2 | `alert` 반환값 무시(표시를 세우지 않음) | 잡힘(같은 2건) |
    | M3 | 표시를 늘 true(반복 경고마다 알림, 대안 ②) | 잡힘(4건, `큐가_받은_결측_알림은_반복_경고_때_다시_넣지_않는다` 포함) |
    | M4 | `alert` catch가 true | 잡힘(`결측_알림을_넣다_던지면_…`) |
    | M5 | 사유를 `stage` 없이 바로(사유 실패가 판정을 버림, 예전 R2) | 잡힘(`정지_요청_사유를_만들다_던져도_위험_판정은_남아_정지_요청을_만든다`) |
    | M6 | 자원 단계 대신 값 `UNKNOWN`→`CLEAR`(PR #166 S3) | 잡힘(`자원_단계가_실패하는_동안_켜진_정지_요청은_풀지_않는다`) |
    | M7 | 판정이 `UNKNOWN`이면 하트비트 단계 건너뜀(PR #166 QA V2) | 잡힘(2건, `드라이브를_읽지_못하는_점검에서도_엔진_멈춤_알림이_나간다` 포함) |
    | M8 | 정지 요청 단계 실패 수준 ERROR→WARNING(QA V1) | 잡힘(`정지_요청_단계_실패는_ERROR로_남기고_앞_단계_알림은_나간다`) |
    | M9 | 하트비트 단계에 `Stage.RESOURCES`를 넘김 | 잡힘(2건) |
    | M10 | 필수 값이 모두 읽힌 점검마다 표시를 지움 | **생존**(아래 K1) |
    | M11 | 사유 대신 문구 바꿈 | 잡힘(`정지_요청_사유를_…`) |
    | M12 | 단계 실행기에서 탐침 호출 삭제 | 잡힘(6건) |
  - 탐침 1개(임시 시험, 실행 뒤 삭제, `git status` 깨끗): R1 순서로 cpu 결측 알림을 억제시킨 뒤, 회복 INFO가 간격 안이라 미뤄지는 "모두 읽힘" 점검 한 번과 cpu 재결측을 넣고 11분 돌림. 헤드는 cpu 결측 알림이 채널(실제 `NotificationQueue`가 받은 것)에 2번(처음 + 간격이 찬 재전송), M10은 1번(재전송 유실). 즉 헤드 코드는 맞고 M10 같은 회귀를 지금 시험이 못 잡는다.

## 검토 범위와 영향
- 운영 코드 변경: 감시 프로세스 `Alerts.java`(`send`가 `boolean`, `NONE`은 true), `Watchdog.java`(결측 알림 재전송 표시, 자원 단계 판정·사유 분리, `Stage` 열거형, 패키지 전용 생성자의 단계 탐침). 둘 다 보호 경로(`scripts/ci/protected-paths.txt`의 `autotrade-app-watchdog/*`). 시험은 `WatchdogTest` +11건(기존 시험 제거·기대값 변경 없음, 지워진 줄은 헬퍼 생성자 호출 1줄뿐). 문서는 D-111 새 결정, 결정 색인 1줄, 기획서 v14.50 MON-02.
- 운영 배선: `WatchdogMain`은 그대로 `notifications::offer`를 넘긴다(WatchdogMain.java:124). `NotificationQueue#offer`는 이미 `boolean`을 돌려주고 던지지 않는다(NotificationQueue.java:137-143). 운영 생성자는 빈 탐침(Watchdog.java:91)이고 탐침을 받는 생성자는 패키지 전용이며, 운영 코드에서 `stageProbe`·`Watchdog.Stage`를 쓰는 곳은 `Watchdog.java`뿐이다(grep).
- 재전송 폭주 여부: 표시가 알림을 더 넣게 하는 경우는 `!newName && !afterRecovery && due`, 즉 간격(10분)이 찬 반복 경고뿐이다(Watchdog.java:318-330). `newName`·`afterRecovery`는 예전에도 알림을 넣었다. 따라서 추가 알림은 간격당 최대 1건이고, 받으면 표시가 지워진다(:335). 큐는 같은 대상·종류의 억제 창을 마지막으로 **받은** 시각에서 재고(NotificationQueue.java:153-157, 억제 때 `at`을 옮기지 않음), 결측 알림은 결측 경고 때만 넣으므로 간격이 찬 반복 경고 시각은 마지막으로 받은 결측 알림에서 `MISSING_REPEAT`(10분, Watchdog.java:62) 이상 뒤다. `QUIET`도 10분(NotificationQueue.java:40)이라 재전송은 받힌다. `READING_MISSING`은 URGENT라(EventRoutes.java:84) INFO 상한(`MAX_PER_WINDOW`)으로 버려지지 않는다.
- 정지 요청 판정: `pressure`(Watchdog.java:261-284)와 `StopRequests` 변경 없음. 자원 단계는 판정을 먼저 내고(:131) 사유만 `DETAIL` 단계로 감싸(:134) 사유가 던져도 CRITICAL이 남는다(PR #166 이전 의미 복원, M5로 확인). 자원 단계 자체가 던지면 예전처럼 `UNKNOWN`·"자원 값을 읽지 못함"(:198)이라 새로 만들지 않고 켜진 것은 유지(M6로 확인).
- 실전 엔진(`autotrade-app-engine`)·Guard(`autotrade-risk`)·주문 경로·`autotrade-architecture-test`·`autotrade-notification-jar`·`docs/team`·`scripts`·`.github` 변경 없음(`git diff --stat origin/main...HEAD -- <그 경로들>` 빈 결과).

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard·Guard 설정 로딩 변경 없음(diff에 `autotrade-risk`·`autotrade-app-engine` 없음). 회귀: `:autotrade-risk:test` 55건 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리 변경 없음. 감시는 주문 API를 부르지 않는다. ArchUnit 규칙 변경 없음, `:autotrade-architecture-test:test` 46건 통과. 새 타입은 `Watchdog` 안 중첩 열거형 `Stage`뿐이라 소유 표 영향 없음 |
| A3 | 해당 없음 | 증권사 주문 연동·라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 정지 순서 5.2 0번(장애표 #10)을 만드는 감시 판정·정지 요청 처리는 그대로이고 사유 실패에서 오히려 더 안전해졌다(Watchdog.java:128-136, M5 잡힘). 다섯 단계가 서로 독립임을 이제 `tick()` 경로로 고정한다(WatchdogTest.java:964-1023, M7·M9·M12 잡힘). 자원 값이 빠진 점검에서도 엔진 멈춤 알림(장애표 #8 감지)이 나감을 시험이 고정한다(:913, M7 잡힘). 재전송은 간격당 최대 1건이라 감시 점검 부하가 늘지 않고, 알림은 큐에 넣기만 한다. 감시는 여전히 실전 엔진을 멈추지 않는다(알림 문구 Watchdog.java:153) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 로그·알림 문구는 단계 문구 + 예외 클래스 이름(Watchdog.java:211), 자원 이름 집합, 고정 문구("사유를 만들지 못함")뿐이다. 추가 줄을 `appkey|appsecret|secret|token|password|계좌번호 모양` 정규식으로 찾아 0건. gitleaks는 이 컨테이너에 없어 CI에 맡긴다 |
| A11 | 통과 | 보호 테스트(`autotrade-architecture-test`, Guard·GuardedOrderGateway 테스트, 장애 테스트) 변경 없음. `WatchdogTest`에서 지운 `@Test`·`@Disabled` 0건, 기존 기대값 변경 없음(지운 줄은 헬퍼의 생성자 호출 1줄이고 같은 콜백을 새 생성자로 넘김, WatchdogTest.java:62-73). 고정 값(지문·허용 목록·소유 표) 변경 없음. 새 동작마다 실패 경우 시험이 있고 변이 12개 중 11개를 잡는다. 생존 1개(M10)는 지금 코드가 맞고 회귀 방지 시험이 빠진 것이라 K1 권고로 둔다. 새 시험의 로그 핸들러는 `finally`로 뗀다(WatchdogTest.java:935-950) |
| A12 | 통과 | 장애 대응표 6장 행 수 17 그대로(직접 셈), `docs/team` 변경 없음. 바뀐 것은 결측 알림이 채널에 더 확실히 닿는 쪽(장애표 #10 감지 강화), 사유 실패 시 판정 유지(#10 대응 강화), 시험용 주입점뿐이다. 장애 테스트 8종 전제(주문·시세·킬스위치, 감시는 정지 요청 파일만 만들고 실전 엔진을 멈추지 않음)와 해외 O1~O8 경로 변경 없음 |
| A13 | 해당 없음 | 시장별 경로·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-111 결정 1~4가 코드와 맞다(Watchdog.java:78, :330-336, :128-136, :165-194, `Alerts.java:15,18`). 검증 절의 시험 이름 11개가 모두 실재한다(WatchdogTest.java:825-1018). "회복 때 표시를 따로 지우지 않는다"는 결정 1 문장도 코드와 맞다(그리고 지우면 안 된다, K1). 결정 색인(README.md:117)과 기획서 v14.50 변경 이력·MON-02 행이 D-111을 가리킨다. 보호 경로라 사용자 머지로 확정한다고 적었다 |

## 점검표 밖 발견
- **K1(권고) — 표시를 "모두 읽힘" 점검에서 지우면 R1이 되살아나는데 시험이 못 잡는다(M10 생존)**: 회복 INFO는 간격(10분)마다 한 번이라, 억제 직후 값이 잠깐 모두 읽혀도 회복 INFO는 미뤄지고(Watchdog.java:299) `warnedSinceRecovery`가 true로 남는다. 그 뒤 cpu가 다시 빠지면 `newName`·`afterRecovery`가 모두 false라 간격이 찰 때까지 조용하고, 간격이 찬 반복 경고의 재전송만이 cpu를 채널에 보낸다. 지금 코드는 `missing.isEmpty()` 쪽에서 표시를 건드리지 않아 맞다(탐침: 헤드 cpu 알림 2건, M10 1건). 그러나 누가 "회복이면 표시를 지운다"를 `if (missing.isEmpty())` 첫머리에 넣으면 R1이 다시 생기고 11개 새 시험 어느 것도 실패하지 않는다. D-111 검증 절이 "회복 때 표시 지우기는 효과가 없어 뺐다"고 적은 것은 회복 INFO 분기에 대해서는 맞지만, 미룬 회복 경우까지는 다루지 않는다. 다음 감시 작업에서 위 탐침 순서(억제 → 모두 읽힘(회복 미룸) → 재결측 → 간격 찬 반복 경고에서 cpu 알림)를 시험으로 고정하기를 권한다. 지금 동작은 맞으므로 판정에 넣지 않는다.
- **K2(참고) — 재전송이 받히는 근거는 `MISSING_REPEAT`(10분) ≥ 큐 `QUIET`(10분)에 기댄다**: 두 상수는 서로 다른 모듈에 있고 관계를 고정하는 시험은 없다(R1 시험은 실제 큐로 그 결과만 본다). 다만 `QUIET`가 늘어도 재전송이 억제되면 표시가 남아 다음 간격에 다시 넣으므로 유실은 아니고 늦어질 뿐이다(자기 회복). 판정 영향 없음.
- **K3(참고)** — 재전송 본문은 지금 빠진 값이고 억제됐던 값이 그 사이 돌아왔으면 그 값 이름은 채널에 오지 않는다(D-111 위험 1이 적음). 그 값의 회복 INFO 본문(`missingSinceRecovery`)에는 이름이 남아, 채널 마지막 메시지가 "읽지 못함" 또는 그 값을 포함한 "다시 읽음"이 되어 안전한 쪽이다.
- PR #166 독립 QA R1·R2·Q1·Q2와 risk-ops PR #166 K1·K2가 위 변이(M1~M9)와 시험으로 해결됨을 확인했다. PR #166 K2에서 생존했던 S3(대신 값 CLEAR)은 이제 M6로 잡힌다.

## 판정
승인
