# QA 검토: 감시 억제된 결측 알림 재전송·판정과 사유 분리 (D-111, PR #166 QA 후속)
- 대상: PR #170 / 브랜치 `claude/project-thread-93kj2r` / 헤드 커밋 32524d0 / main 대비 diff 전체(merge-base e91f260 = 현재 origin/main, 커밋 5개, 9파일 +486 −18: 운영 `Alerts`·`Watchdog`, 시험 `WatchdogTest`, 문서 D-111(새)·결정 색인·`docs/qa/pending.md`·검토 원문 2개·기획서)
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 보호 경로 `autotrade-app-watchdog`, `scripts/ci/protected-paths.txt`)
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 32524d0): 1~4회째 Maven Central 429로 의존성 받기 실패(환경 문제, 코드 무관), 5회째 **BUILD SUCCESSFUL in 2m 46s**(미러 init 스크립트 없이, 20초 간격 재시도 루프 하나, 138 tasks executed). 테스트 결과 XML 합계 **1,585건, 실패 0, 오류 0, 건너뜀 0**. `WatchdogTest` 46, `WatchdogMainTest` 15, `NotificationQueueTest` 17, `RealTradingGuardTest` 31, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10 통과
  - 변이 9개(Q1~Q9, 개발 쪽·risk-ops 변이 M1~M12와 겹치지 않게 고름. 한 곳씩 바꾸고 `:autotrade-app-watchdog:test --rerun-tasks`, 각 뒤 `git checkout`, 마지막 `git status` 깨끗): 6개 잡힘, 생존 3개 중 2개는 같은 동작(아래 B6), 1개는 시험 공백(Q1)
  - 탐침 시험 1개(임시 시험 클래스, 커밋 안 함, 실행 뒤 지움): PC. cpu·드라이브 값을 시드 고정 무작위(시드 1~300, 결측 확률·상태 유지 확률 시드마다 다름)로 빠지게 하고 2시간(1,440점검, 5초 간격) 돌림. 알림은 실제 `NotificationQueue`(`start()`로 비움)에 넣고 받은 것만 채널로 봄. 매 점검 뒤 "21분 이상 계속 빠진 값마다, 그 값 이름을 담은 마지막 받은 알림이 `reading-missing`이다"를 검사 → 이 헤드 **위반 0건**. 재전송 조건(`|| missingAlertPending`)을 빼면 **위반 290건**(첫 위반 시드 145, 드라이브 값의 마지막 받은 알림이 `reading-recovered`). 결측 알림 받음 최대 10분당 2건, 시드당 받은 알림 최대 32건, 큐가 차서 버린 것 0건(재전송이 폭주를 만들지 않음)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 `RealTradingGuard`·Guard 설정·Guard 시험 변경 없음(`git diff --stat origin/main...HEAD -- autotrade-architecture-test autotrade-risk autotrade-app-engine autotrade-notification-jar docs/team scripts .github` 빈 결과). `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. 감시는 주문 API를 부르지 않는다. 새 타입은 `Watchdog` 안 중첩 열거형 `Stage`(`Watchdog.java:165`)뿐. `ArchitectureTest` 19·`RealPathReflectionTest` 10·`ModuleOwnershipTest` 13 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 자원 단계가 판정을 먼저 내고(`Watchdog.java:131`) 사유만 `DETAIL` 단계로 감싸(`:134`) 사유가 던져도 CRITICAL이 남아 정지 요청을 만든다(`WatchdogTest.java:1045` 통과, 내 변이 Q8 사유 대신 값 바꿈 잡힘). 자원 단계 자체가 던지면 `UNKNOWN`이라 새로 만들지 않고 켜진 것은 유지(`:1017`, `:1025`). 다섯 단계가 서로 독립임을 `tick()` 경로로 고정(`:1009-1068`, 내 변이 Q5 탐침을 본문 뒤로 옮김 잡힘). 운영 생성자는 빈 탐침(`:91`)이고 `WatchdogMain.java:124`는 7인자 생성자를 그대로 쓴다. 정지 요청 판정(`pressure`, `StopRequests`) 변경 없음. 실전 엔진은 바뀌지 않았다 |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새 로그·알림 문구는 단계 문구 + 예외 클래스 이름, 자원 이름 집합, 고정 문구 "사유를 만들지 못함"뿐. 추가 줄을 `appkey|appsecret|secret|token|password|계좌번호`로 찾은 결과는 검토 원문의 A10·B8 설명 문장 2줄뿐(값 아님) |
| A11 | 통과 | 지우거나 끈 시험 없음(`-@Test`·`@Disabled` 0건). 보호 시험(`autotrade-architecture-test`, Guard 시험, 장애 시험)과 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 시험 기대값 변경 없음(지운 줄은 헬퍼 생성자 호출 1줄). 새 동작마다 실패 경우 시험이 있다: 재전송(`WatchdogTest.java:825,875,936`), 받은 알림은 다시 안 넣음(`:920`), 자원 결측 중 엔진 멈춤(`:958`), 단계별 실패(`:1009-1068`). 단, 운영 배선이 큐의 받음 여부를 넘기는지는 시험이 없다(Q1) |
| A12 | 통과 | 장애표 6장 행·`docs/team` 변경 없음. 바뀐 것은 장애표 #10(PC 자원) 감지 알림이 채널에 더 확실히 닿는 쪽(탐침 PC: 위반 290 → 0)과 사유 실패 시 판정 유지(#10 대응 강화). 장애표 #8 감지(엔진 멈춤 알림)가 자원 결측과 독립임을 새 시험이 고정. 장애 테스트 8종 전제(주문·시세·킬스위치)와 해외 O1~O8 경로 변경 없음 |
| A13 | 해당 없음 | 시장 규칙·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-111 결정 1(`Watchdog.java:78,330-336`, `Alerts.java`), 결정 2(`:131-135`), 결정 3(`:165-194`), 결정 4(위 시험 이름 실재)가 코드와 맞다. "회복 때 표시를 따로 지우지 않는다"는 내 변이 Q9(회복 INFO 때 지움)가 같은 동작으로 생존해 결정 1의 "효과가 없다" 설명과 맞다. 결정 색인 1줄, 기획서 v14.50 변경 이력·MON-02 행이 D-111을 가리키고, "추천안 채택(자율)", 보호 경로라 사용자 머지로 확정한다고 적혀 있다. 지침 8장 과금: 새 외부 호출 없음 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행, 1,585건 통과(429 4회 뒤). risk-ops 원문의 1,584건(헤드 038a1d2)에 K1 시험 1건이 더해진 수와 맞다 |
| B6 | 통과 | 새 동작에 시험이 있고 지우거나 건너뛴 시험이 없다(A11). 내 변이 9개 중 잡힘 6: Q2(재전송 표시가 있으면 간격 안에서도 경고, 점검마다 WARN), Q3(`DETAIL` 실패 수준 WARNING→ERROR), Q4(재전송 본문을 `missingSinceRecovery`로), Q5(탐침을 단계 본문 뒤로), Q6(표시를 한 번 세우면 안 지움), Q8(사유 대신 문구를 "자원 값을 읽지 못함"으로). 생존 3: Q7(재전송을 `due`일 때만)과 Q9(회복 INFO 때 표시 지움)는 같은 동작이다(Q7: 표시만으로는 이른 반환을 넘지 못하므로 `newName`·`afterRecovery`가 아니면 늘 `due`. Q9: 회복 INFO 뒤 첫 결측은 `afterRecovery`로 알림). Q1(`WatchdogMain` 배선이 큐 반환값을 버리고 늘 true)은 생존, 지금 코드는 맞고 회귀를 막는 시험이 없다는 뜻이라 권고 Q1로 둔다 |

## 점검표 밖 발견
- 위험 시나리오 시도(잘못된 주문·중복 주문·보호 공백 관점):
  1. cpu·드라이브가 무작위로 오래 빠졌다 돌아옴(탐침 PC, 300시드×2시간) → 21분 이상 빠진 값은 채널 마지막 상태가 늘 "읽지 못함"(위반 0, 재전송 없는 논리 290). 결측 알림은 10분당 최대 2건, 큐가 차서 버린 것 0이라 재전송이 알림 폭주를 만들지 않는다. 압력은 값이 빠지면 `UNKNOWN`이라 정지 요청을 새로 만들지 않고 켜진 것은 유지(판정 무변경). 주문 경로와 무관.
  2. 정지 요청 사유 작성이 던지는 중 드라이브 여유가 위험 → 정지 요청 파일 둘을 만들고 긴급 알림 본문이 "사유를 만들지 못함. "으로 시작(`WatchdogTest.java:1045`). PR #166의 "CRITICAL이 UNKNOWN으로 바뀌어 정지 요청을 못 만듦"(PR #166 QA R2)은 사라졌다. 정지 요청 파일 처리 단계가 던지면 ERROR 로그를 남기고 앞 단계 엔진 멈춤 알림은 나간다(`:1063`).
  3. 시험용 탐침이 운영에서 단계를 던지게 할 수 있는가 → 탐침 생성자는 패키지 전용이고 운영에서 감시를 만드는 곳은 `WatchdogMain.java:124` 하나이며 탐침 없는 생성자(빈 람다)를 쓴다. 외부 입력(설정 파일)으로 탐침을 바꿀 길이 없다.
  4. 운영 배선이 받음 여부를 버리는 회귀(내 변이 Q1: `notifications::offer`를 "넣고 늘 true"로) → 모든 시험이 통과한다. 그러면 R1(억제된 결측 알림이 채널에 끝까지 안 옴)이 운영에서만 되살아난다(아래 Q1).
- **Q1 (권고)**: `WatchdogMain`이 `NotificationQueue#offer`의 반환값을 `Alerts`로 그대로 넘기는지 고정하는 시험이 없다(변이 Q1 생존). D-111 결정 1은 이 배선에 기대고, 재전송 시험(`WatchdogTest.java:825,875`)은 시험 안에서 직접 만든 람다로만 큐에 넣는다. `WatchdogMainTest`에서 감시가 만든 실제 큐로 R1 순서를 돌리거나, 배선 람다가 억제 때 false를 돌려주는지 보는 시험 1건을 권한다.
- **R1 (참고)**: 재전송 본문은 지금 빠진 값이라, 억제됐던 값이 재전송 전에 돌아왔으면 그 이름은 "읽지 못함" 알림으로는 채널에 오지 않는다(D-111 위험 1, risk-ops K3와 같음). 그 값은 다음 회복 INFO 본문(`missingSinceRecovery`)에 "다시 읽음"으로 나타날 수 있어, 운영자는 "읽지 못함" 알림을 본 적 없는 값의 "다시 읽음"을 받을 수 있다 `[추론]`. 놓친 위험 상태는 아니라 판정에 넣지 않는다.
- 개발 쪽 검토 확인: risk-ops K1(M10)은 시험 `억제된_결측_알림은_값이_잠깐_다_읽혀도_잊지_않는다`(`WatchdogTest.java:875`)로 반영됐고 이 빌드에서 통과. K2(`MISSING_REPEAT` ≥ `QUIET`)는 두 상수가 각각 10분임을 코드로 확인(`NotificationQueue.java:40`). PR 본문의 `WatchdogTest` 35→46은 내 실행의 46건과 맞다. 마지막 커밋 32524d0은 시험 1건·D-111 문구·`pending.md`만 바꿨다(`git show --stat 32524d0`). GitHub `mergeable_state: clean`.

## 판정
승인
