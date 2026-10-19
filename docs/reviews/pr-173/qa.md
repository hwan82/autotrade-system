# QA 검토: 감시 운영 배선이 알림 큐 받음 여부를 넘기는지 시험 (PR #170 QA Q1 후속)
- 대상: PR #173 / 브랜치 `claude/project-thread-0ssuqw` / 헤드 커밋 094b94f / main 대비 diff 전체(merge-base dc41d07 = 현재 origin/main, 커밋 4개, 7파일 +106 −0: 운영 `Watchdog`, 시험 `WatchdogMainTest`, 문서 D-111 후속 절·`docs/qa-out-status.md`·`docs/qa/pending.md`·검토 원문 2개)
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 보호 경로 `autotrade-app-watchdog`, `scripts/ci/protected-paths.txt:9`)
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 094b94f): 1~4회째 Maven Central 429로 의존성 받기 실패(환경 문제, 코드 무관), 5회째 **BUILD SUCCESSFUL in 3m 46s**(미러 init 스크립트 없이, 20초 간격 재시도 루프 하나, 138 tasks executed). 테스트 결과 XML 합계 **1,586건, 실패 0, 오류 0, 건너뜀 0**. `WatchdogTest` 46, `WatchdogMainTest` 16(새 시험 `운영_배선은_알림_큐가_받았는지를_그대로_감시에_넘긴다` 통과 기록 확인), `NotificationQueueTest` 17, `RealTradingGuardTest` 31, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10 통과
  - 변이 7개(P1~P7, 개발 쪽·architect·risk-ops 변이 W1~W3 "늘 true·늘 false·뒤집기"와 겹치지 않게 고름. 한 곳씩 바꾸고 `:autotrade-app-watchdog:test --rerun-tasks`, 각 뒤 `git checkout`, 마지막 `git status` 깨끗): 4개 잡힘, 생존 3개 중 1개는 같은 동작, 2개는 배선 인자 시험 공백(아래 B6, Q1)
  - 반복 실행: 바쁜 스레드 8개(4코어)를 띄운 채 `:autotrade-app-watchdog:test --tests '*WatchdogMainTest' --rerun-tasks` 10회 → 10/10 통과(architect N3·risk-ops K2의 INFO 창 상한 흔들림 재현 안 됨)
  - GitHub: `mergeable_state: clean`, 헤드 094b94f 체크 4종(Gradle build, DB schema, Secret scan and collector boundary, Lab screens) 모두 success

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 `RealTradingGuard`·Guard 설정·Guard 시험 변경 없음(`git diff --stat origin/main...pr173 -- autotrade-architecture-test autotrade-risk autotrade-app-engine autotrade-notification-jar docs/team scripts .github` 빈 결과). `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. 감시는 주문 API를 부르지 않는다. 새 타입 없음, 새 메서드는 패키지 전용 읽기 `Watchdog.alerts()`(`Watchdog.java:417`) 하나. `ArchitectureTest` 19·`RealPathReflectionTest` 10·`ModuleOwnershipTest` 13 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 운영 동작 변경 없음: `alerts()`는 필드를 그대로 돌려주고(`Watchdog.java:416-419`) 호출처는 시험 하나. 운영 배선 `WatchdogMain.java:124`(`notifications::offer`)는 그대로다. 정지 요청 판정·임계·알림 종류·억제 간격 무변경. 결측 알림 재전송(D-111 결정 1)이 운영 배선의 받음 여부에 기대는 사슬이 이제 실제 기동 경로로 고정된다(W1 계열 변이를 새 시험이 잡음, 내 P5 감시가 필드를 감싸 늘 true도 잡힘). 실전 엔진은 바뀌지 않았다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 문자열은 대상 `wiring-probe`, 고정 문구, 자원 이름 `cpu.system`뿐. 추가 줄을 `appkey|appsecret|secret|token|password|계좌번호`로 찾은 결과는 검토 원문의 A10·B8 설명 문장 2줄뿐(값 아님) |
| A11 | 통과 | 지운 줄 0(diff −0), `@Disabled` 0건. 보호 시험(`autotrade-architecture-test`, Guard 시험, 장애 시험)과 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 기대값 변경 없음. 새 시험은 받음(true)·억제(false)·종류 바뀜(true)·닫힘(false)을 모두 본다. 단, 배선이 대상·제목·본문 인자를 제자리로 넘기는지는 시험이 없다(Q1) |
| A12 | 통과 | 장애표 6장 행·`docs/team` 변경 없음. 장애표 #10(PC 자원) 감지 알림의 배선 쪽 회귀를 막는 시험이 늘었다. 장애 테스트 8종 전제(주문·시세·킬스위치)와 해외 O1~O8 경로 변경 없음 |
| A13 | 해당 없음 | 시장 규칙·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-111 후속 반영 절(`D-111…md:49-52`)이 코드와 맞다: 시험 이름 실재(`WatchdogMainTest.java:251`), `alerts()` 추가, 운영 동작 무변경. "늘 true·늘 false 둘 다 잡힘"은 개발 쪽 주장으로 보고 대신 내 변이 P3·P4·P5로 같은 시험이 잡는 것을 확인. R1을 그대로 두는 판단은 PR #170 QA R1(참고)과 같다. `docs/qa-out-status.md` 추가 줄이 PR #170 QA 판정(Q1 해결, R1 미처리)을 기록하고, 병합 커밋 094b94f는 `qa-out-status.md`·`pending.md` 각 1줄만 더했다(`git show --stat 094b94f`). 새 D 번호 없음. 지침 8장 과금: 새 외부 호출 없음 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행, 1,586건 통과(429 4회 뒤). PR #170 QA의 1,585건 + 새 시험 1건과 맞고 risk-ops 원문의 1,586건과도 맞다 |
| B6 | 통과 | 새 시험이 있고 지우거나 건너뛴 시험이 없다(A11). 내 변이 7개 중 잡힘 4: P3(배선이 종류를 늘 `READING_MISSING`으로, 새 시험과 `운영_배선으로_감시가_본_엔진_멈춤을_알림_큐에_넣는다` 실패), P4(`Watchdog.close()`에서 큐 닫기 삭제, 새 시험의 닫힘 단언 실패), P5(`Watchdog` 생성자가 받은 `Alerts`를 감싸 늘 true, 새 시험과 `WatchdogTest` 재전송 시험 2건 실패), P6(`Watchdog.alert` 예외 시 true, `결측_알림을_넣다_던지면_다음_반복_경고_때_다시_넣는다` 실패). 생존 3: P7(배선이 대상 이름 뒤에 고정 글자를 붙임)은 대상끼리 여전히 구별되어 같은 동작. P1(배선이 대상을 상수 하나로 뭉갬)·P2(배선이 제목과 본문을 바꿔 넘김)는 생존, 이 PR이 바꾼 줄이 아닌 main부터의 배선 인자 시험 공백이라 권고 Q1로 둔다 |

## 점검표 밖 발견
- 위험 시나리오 시도(잘못된 주문·중복 주문·보호 공백 관점):
  1. 시험용 `alerts()`로 운영에서 알림 출구를 바꾸거나 우회할 수 있는가 → 반환만 하는 패키지 전용 읽기이고 필드는 생성자에서 한 번 정해진다(`Watchdog.java:98`). 호출처는 시험 하나, 설정 파일 등 외부 입력으로 닿을 길이 없다. 보호 공백 없음.
  2. 새 시험이 점검 스레드와 같은 큐를 써 운영 알림과 섞이거나 흔들리는가(architect N3·risk-ops K2) → 대상 이름 `wiring-probe`는 감시 대상(프로세스·`readings`·`stop-request`)과 겹치지 않는다. 바쁜 스레드 8개 부하에서 10회 반복 모두 통과. 기동 직후 알림이 20건(`NotificationQueue.MAX_PER_WINDOW`)을 넘게 되면 세 번째 단언(INFO)이 다른 이유로 실패할 수 있다는 지적은 맞다 `[추론]`. 지금은 재현되지 않아 판정에 넣지 않는다.
  3. 배선이 받음 여부는 그대로 넘기되 인자를 잘못 넘기는 회귀(P1·P2) → 모든 시험이 통과한다. P1이면 큐의 억제 키가 대상 하나로 합쳐져(`NotificationQueue.java:150-157`, 대상별 마지막 종류·시각으로 억제), 예컨대 엔진 하트비트 멈춤 알림 뒤 10분 안의 시세 게이트웨이 하트비트 멈춤 알림(같은 종류)이 억제되어 채널에 오지 않는다 `[추론]`. 이 경우 D-111 재전송은 결측 알림에만 있어 하트비트 멈춤 알림은 다시 오지 않는다 `[추론]`. 지금 코드는 맞고(`WatchdogMain.java:124`가 메서드 참조라 인자 순서가 같다) 회귀를 막는 시험이 없다는 뜻이다(Q1).
- **Q1 (권고)**: `WatchdogMain`이 감시에 넘기는 알림 출구가 대상·제목·본문을 제자리로 넘기는지 고정하는 시험이 없다(변이 P1 대상 뭉갬, P2 제목·본문 바꿈 생존). 새 시험이 받음 여부를 고정했으니, 같은 시험에서 서로 다른 두 대상에 같은 종류를 넣어 둘 다 true인지, 그리고 큐에 쌓인 알림의 제목·본문이 넣은 값과 같은지 보는 단언을 더하기를 권한다. main부터 있던 공백이며 이 PR의 diff가 만든 것은 아니다.
- **R1 (참고, 개발 쪽 검토 원문 정정)**: risk-ops 원문 "점검표 밖 발견" 마지막 항목은 "PR #170 risk-ops K1(표시를 '모두 읽힘' 점검에서 지우는 변이 M10 생존)은 … 여전히 열려 있다"고 쓰지만, 그 회귀를 막는 시험 `WatchdogTest#억제된_결측_알림은_값이_잠깐_다_읽혀도_잊지_않는다`(`WatchdogTest.java:875`)가 이 헤드에 있고 이번 빌드에서 통과했다(PR #170 QA 보고서도 K1 반영을 확인). 내 변이 P5에서도 이 시험이 실패해 살아 있는 시험임을 확인했다. M10 자체는 다시 돌리지 않았다. 판정에는 영향 없다.
- 개발 쪽 검토 확인: architect N1·risk-ops K1(`qa-out-status.md` 충돌)은 094b94f로 해소, GitHub `mergeable_state: clean`, 헤드 CI 4종 success. PR 본문의 시험 1,586건은 내 실행과 맞다. architect N2(재전송 사슬은 `WatchdogTest` 재전송 시험과 합쳐 닫힘)는 P5가 새 시험과 `WatchdogTest` 2건을 함께 실패시켜 맞다.

## 판정
승인
