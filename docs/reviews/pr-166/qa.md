# QA 검토: 감시 회복 뒤 재결측 경고·점검 단계 한 곳으로 (D-110, PR #163 QA 후속)
- 대상: PR #166 / 브랜치 `claude/project-thread-gpua1p` / 헤드 커밋 fee6946 / main 대비 diff 전체(merge-base 9346538 = 현재 origin/main, 커밋 5개, 12파일 +397 −26: 운영 `Watchdog`·`WatchdogMain`, 시험 `WatchdogTest`·`WatchdogMainTest`·`MonitorAgentTest`, 문서 D-106·D-110(새)·결정 색인·`docs/qa/pending.md`·검토 원문 2개·기획서)
- 점검표: A 전체 + B1, B6 (실전 관련 PR: 보호 경로 `autotrade-app-watchdog`·`autotrade-monitor-agent-jar`, `scripts/ci/protected-paths.txt`)
- 직접 실행한 것:
  - `./gradlew build --rerun-tasks` (헤드 fee6946): 1~3회째 Maven Central 429로 의존성 받기 실패(환경 문제, 코드 무관), 4회째 **BUILD SUCCESSFUL in 2m 42s**(미러 init 스크립트 없이, 20초 간격 재시도 루프 하나, 138 tasks executed). 테스트 결과 XML 합계 **1,572건, 실패 0, 오류 0, 건너뜀 0**. `WatchdogTest` 35, `WatchdogMainTest` 15, `MonitorAgentTest` 18, `RealTradingGuardTest` 31, `ArchitectureTest` 19, `ModuleOwnershipTest` 13, `RealPathReflectionTest` 10 통과
  - 변이 8개(V1~V8, 개발 쪽·risk-ops 변이와 겹치지 않게 고름. 한 곳씩 바꾸고 `:autotrade-app-watchdog:test :autotrade-monitor-agent-jar:test --rerun-tasks`, 각 뒤 `git checkout`, 마지막 `git status` 깨끗): 5개 잡힘, 3개 생존(아래 B6·Q1·Q2)
  - 탐침 시험 2개(임시 시험 클래스, 커밋 안 함, 실행 뒤 지움):
    - PA: cpu·드라이브 값을 시드 고정 무작위(시드 1~300, 결측 확률 시드마다 0.1~0.7)로 5초마다 빠지게 하고 60분(720점검) 돌림. 매 점검 뒤 "지금 빠진 값마다 마지막 회복 INFO 뒤에 그 값을 적은 결측 WARN이 있다"를 검사 → 이 헤드 **위반 0건**. `newName`을 main대로 되돌리면 **위반 35,325건**(시드 1부터). 결측 알림(`Alerts.send`) 최대 10분당 4건(main 논리 3건), 60분 결측 WARN 최대 20건·회복 INFO 최대 6건(점검마다 경고하는 폭주 없음)
    - PB: R1 순서(cpu 결측 → cpu 회복 → 드라이브 결측 → 드라이브 회복·cpu 재결측 → cpu 25분 계속 결측)를 실제 `NotificationQueue.offer`(시작 안 함, 받음 여부만)에 넣음 → `reading-missing [cpu] 받음`, `reading-recovered [cpu] 받음`, `reading-missing [disk] 받음`, `reading-missing [cpu]` **억제(false)**. 그 뒤 25분 동안 결측 WARN은 로그로 2번 더 났지만(합계 5) 결측 알림은 다시 넣지 않음(아래 R1)

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | diff에 `RealTradingGuard`·Guard 설정·Guard 시험 변경 없음(`git diff --name-only origin/main...HEAD`에 `autotrade-risk`·`autotrade-app-engine`·`autotrade-architecture-test` 경로 0건). `RealTradingGuardTest` 31건 통과 |
| A2 | 통과 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경 없음. 새 타입은 `Watchdog` 안 private record `Assessment`(`Watchdog.java:141`)와 새 import `java.util.function.Supplier`뿐. `ArchitectureTest` 19·`RealPathReflectionTest` 10·`ModuleOwnershipTest` 13 통과 |
| A3 | 해당 없음 | 증권사 연동·주문 라우팅 변경 없음 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경 없음 |
| A5 | 해당 없음 | 멱등 키 변경 없음 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경 없음 |
| A7 | 해당 없음 | 계좌 큐·토큰 버킷·킬스위치 변경 없음 |
| A8 | 통과 | 감시 점검 네 단계가 `Watchdog.stage`(`Watchdog.java:149-164`)로 돌고, 단계가 던지면 그 단계만 버리고 실패 로그는 `WatchdogLog`로 낸다(`:152-154`). 자원 단계 대신 값은 예전 catch와 같은 `UNKNOWN`·"자원 값을 읽지 못함"(`:141-143`)이라 정지 요청을 새로 만들지 않고 켜진 것은 유지한다. 정지 요청 판정(`pressure`, `StopRequests.update`) 변경 없음, 변이 V3(정지 요청 단계에 늘 `UNKNOWN`)는 3건으로 잡힘. 실전 엔진은 바뀌지 않았다. 다만 자원 단계가 이제 판정·사유를 한 람다로 묶어 `detail(snapshot)`이 던지면 이미 계산한 CRITICAL도 버린다(예전에는 `pressure`가 먼저 대입돼 남았다). `detail`은 산술·문자열뿐이라 던질 경로를 찾지 못했다 `[추론]`(risk-ops K2와 같음, R2) |
| A9 | 해당 없음 | 보호 시세 경로·서버 측 손절 변경 없음 |
| A10 | 통과 | 새·바뀐 문구는 단계 이름 + 예외 클래스 이름(`Watchdog.java:152`), 자원 이름 집합, 기동 실패 첫머리뿐. `점검_단계_실패는_단계_이름과_예외_종류로_남긴다`(`WatchdogTest.java:759`)가 예외 메시지 미기록을 고정. 추가 줄을 `appkey|secret|token|password|계좌번호`로 찾은 결과는 검토 원문의 B8·A10 설명 문장 2줄뿐(값 아님) |
| A11 | 통과 | 지우거나 끈 시험 없음(`-@Test`·`@Disabled` 0건). 보호 시험(`autotrade-architecture-test`, Guard 시험, 장애 시험)과 고정 값(지문·허용 목록·소유 표) 변경 없음. 기존 시험 기대값 변경 없음(`WatchdogMainTest`는 `doesNotContain(")(")` 단언 2줄 추가뿐, `:131-132`). 새 동작마다 실패 경우 시험이 있다: R1(`WatchdogTest.java:776`), 로그 삼킴 회귀 4건(`:660,681,702,725`), `stage` 2건(`:742,759`), DEBUG 주기 재출력(`MonitorAgentTest.java:459`) |
| A12 | 통과 | 장애표 #8(엔진 크래시)·#9 감지인 하트비트 바뀜·재기동 알림과 #10(PC 자원 고갈)의 정지 요청 파일·결측 경고가 로그 핸들러 장애 중에도 나감을 새 시험이 고정한다. 결측 WARN은 더 일찍 나는 쪽으로만 바뀌었고(탐침 PA), 하트비트 판정·임계·알림 종류·결측 간격 변경 없음. 장애 테스트 8종 전제(주문·시세·킬스위치)와 해외 O1~O8 경로 변경 없음. 단, 자원 값이 빠진 점검에서도 하트비트 알림이 나가는지는 시험이 없다(Q1) |
| A13 | 해당 없음 | 시장 규칙·시간대·통화 계산 변경 없음 |
| A14 | 통과 | D-110 결정 1(`Watchdog.java:255`), 결정 2(`:107-164`), 결정 3(위 시험 이름 실재)이 코드와 맞다. 결정 색인 1줄, 기획서 v14.49 변경 이력·MON-02 행, D-106 후속 반영(문구 변경, 종료 코드 무변경)이 있고 "추천안 채택(자율)", 보호 경로라 사용자 머지로 확정한다고 적혀 있다. runbook `docs/runbooks/collector.md:62`가 기대는 "감시가 기동 중 프로그램 오류로 멈췄습니다"는 새 첫머리에도 그대로 있다(`WatchdogMain.java:195`). D-110 위험 1의 "채널의 마지막 알림은 다른 값 이름의 '읽지 못함'일 수 있다"는 탐침 PB와 맞다 |
| B1 | 통과 | 위 `./gradlew build --rerun-tasks` 직접 실행, 1,572건 통과(429 3회 뒤) |
| B6 | 통과 | 새 동작에 시험이 있고 지우거나 건너뛴 시험이 없다(A11). 내 변이 8개 중 잡힘 5: V3(정지 요청 단계에 `UNKNOWN` 고정), V4(`newName` 둘째 항 포함 방향 반대), V5(`missingSinceRecovery.addAll`을 `newName` 판정 앞으로), V7(첫머리에서 "프로그램" 낱말 삭제), V8(DEBUG 주기에 들고 있던 줄을 새 줄 뒤로). 생존 3: V1(정지 요청 단계 실패 로그 수준 ERROR→WARNING), V2(자원 판정이 `UNKNOWN`이면 하트비트 점검 단계를 건너뜀), V6(자원 단계 실패 문구 바꿈). 지금 코드는 맞다. 생존은 회귀를 막는 시험이 없다는 뜻이라 권고 Q1·Q2로 둔다 |

## 점검표 밖 발견
- 위험 시나리오 시도(잘못된 주문·중복 주문·보호 공백 관점):
  1. cpu·드라이브가 무작위로 빠졌다 돌아옴(탐침 PA, 300시드×60분) → 빠진 값마다 마지막 회복 뒤 결측 WARN이 있다(위반 0, main 논리 35,325). 결측 알림·WARN이 점검마다 나는 폭주 없음. 압력은 값이 빠지면 `UNKNOWN`이라 정지 요청을 새로 만들지 않고 켜진 것은 유지(판정 무변경). 주문 경로와 무관.
  2. 드라이브 읽기 실패로 자원 판정이 `UNKNOWN`인 동안 실전 엔진이 멈춤 → 지금 코드는 하트비트 단계가 독립이라 멈춤 알림이 나간다(코드로 확인, `Watchdog.java:116-125`는 `assessment`를 보지 않음). 그러나 그 결합을 넣는 변이 V2가 생존했다(Q1). 이 PR이 `assessment`를 `tick()` 지역 값으로 모아 단계 사이에 값이 흐르게 된 만큼 회귀 위험이 생긴 자리다 `[추론]`.
  3. 로그 핸들러가 모든 기록에 던지는 중 단계 실패 → 다음 단계 진행(`WatchdogTest.java:742`), 재기동·하트비트 읽기 실패·회복·정지 요청 해제·쓰기 실패 알림이 나감(`:660-740`). PR #163 QA가 생존으로 본 QM1~QM4·QM7·QM8·QM12는 risk-ops 원문의 같은 변이 결과대로 이 헤드에서 잡힌다(내 V8로 QM12 계열도 확인).
- **R1 (권고, main에도 있는 결함, 이 PR이 고치지 못한 부분)**: R1 경우 cpu 재결측의 결측 알림은 알림 큐에서 억제된다(탐침 PB: 같은 대상 `readings`·같은 종류가 10분 안, `NotificationQueue.java:150-157`). 그 뒤 cpu가 25분 계속 빠져도 반복 WARN은 로그로만 나고(`Watchdog.java:272-276`의 `newName || afterRecovery` 조건) 결측 알림을 다시 넣지 않으므로, **채널에는 cpu 결측이 끝까지 오지 않고 마지막 결측 알림은 이미 돌아온 드라이브 이름**이다. 채널 마지막 메시지가 "읽지 못함"이라 결측 상태 자체는 보인다(D-110 위험 1과 같은 판단). 억제된 결측 알림이 있었으면 간격이 찬 반복 경고 때 한 번 다시 넣는 것을 권한다. 이 PR이 만든 결함은 아니라 판정에 넣지 않는다.
- **R2 (참고)**: A8에 적은 대로 자원 단계에서 `detail`이 던지면 예전에는 남던 CRITICAL 판정이 이제 `UNKNOWN`으로 바뀐다(정지 요청을 새로 만들지 않는 쪽). 던질 경로를 찾지 못해 현재 영향은 없다 `[추론]`. `detail` 계산을 판정 뒤 별도 대신 값으로 나누면 예전 의미를 지킬 수 있다.
- **Q1 (권고)**: 자원 값이 빠진(`UNKNOWN`) 점검에서도 하트비트 바뀜 알림이 나가는지 고정하는 시험이 없다(V2 생존). 드라이브 읽기 실패와 엔진 멈춤이 겹치는 시험(`diskFails = true` 상태에서 `process-stale` 알림)을 권한다.
- **Q2 (참고)**: `tick()`이 넘기는 단계 실패 로그의 수준·문구는 시험이 고정하지 않는다(V1·V6 생존). `stage` 자체 시험(`:759`)은 호출자가 넘긴 값을 그대로 쓰는지만 본다. D-110 결정 2의 "실패 로그 문구 그대로"를 지키려면 `tick()` 경로 시험이 필요하지만, 운영에 단계를 던지게 할 주입점이 없어 지금 구조로는 닿지 않는다.
- 개발 쪽 검토 확인: risk-ops K1(채널 억제)은 탐침 PB로, K2(자원 단계 의미 변화)는 코드로 같은 결과를 확인했다. architect·risk-ops 원문의 빌드 건수 1,572는 내 실행과 같다. 마지막 커밋 fee6946은 문서·주석·`pending.md`만 바꿨다(`git show --stat fee6946`). GitHub `mergeable_state: clean`.

## 판정
승인
