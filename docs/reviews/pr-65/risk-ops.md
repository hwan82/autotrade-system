# risk-ops 검토: runbook 3.6 반영 시간 숫자로, 옛 시험일 정리 절차, 정지 오조작 방지, Q-050 replayCheck 보호 (PR #63 QA Q1·R1~R3)
- 대상: PR #65 / 브랜치 `claude/project-thread-h3g45k` / 커밋 `adea58a6d646b9d8f4a5a1dc56baf94b34c597e3`. GitHub REST `pulls/65`의 head SHA와 같다(상태 open, base main, mergeable true, mergeable_state clean). merge-base `a6f2602`는 GitHub `branches/main` SHA와 같아 main은 움직이지 않았다. 검토 범위는 `git diff origin/main...HEAD` 전체다.
  - 바뀐 파일 3개: `docs/runbooks/collector.md`(+2 −2), `docs/decisions/D-064-krx-2027-calendar.md`(+1), `docs/decisions/README.md`(+1). 커밋은 1개(`adea58a`)다.
  - `src/main`·`src/test`·`*.json`·`*.kts`·`*.sql` 변경은 0건이다.
  - PR 본문, 커밋 메시지, PR #63 QA 원문(`docs/reviews/pr-63/qa.md` Q1·R1~R3)은 판정 근거로 쓰지 않았다. 확인할 주장으로만 보고, 새 문장마다 코드·테스트·직접 계산으로 대조했다.
- 점검표: A
- 직접 실행한 것. 검토 전용 격리 clone `/tmp/claude-0/review-pr65-riskops`에서 `adea58a`를 checkout하고 작업 트리는 바꾸지 않았다.
  - `./gradlew build --no-daemon` → 종료 코드 0(BUILD SUCCESSFUL). 새 clone이라 테스트가 실제로 돌았다.
    - 결과 XML 합계: tests 897 / failures 0 / errors 0 / skipped 0.
    - 모듈별(테스트/실패/오류/건너뜀): `:autotrade-risk` 55/0/0/0, `:autotrade-architecture-test` 31/0/0/0, `:autotrade-app-engine` 48/0/0/0, `:autotrade-marketdata` 89/0/0/0, `:autotrade-app-gateway` 38/0/0/0, `:autotrade-app-collector` 61/0/0/0, `:autotrade-app-experiment` 11/0/0/0, `:autotrade-app-watchdog` 1/0/0/0.
  - 반영 시간 숫자 검증. jshell에 빌드된 `autotrade-marketdata`·`autotrade-domain` jar와 Jackson을 올리고, 번들 캘린더 전체(`ExchangeCalendar.bundled(KRX, US)`)로 `SessionProtection.check`를 서울 시각마다 호출했다.
    - 2026-10-13(KRX 정규일, 미국 서머타임 중): 15:59 Inside KRX, 16:00 Clear, 18:29 Clear, 21:29 Clear, 21:30 Inside US.
    - 이튿날 10-14: 05:29 Inside US, 05:30 Clear, 07:59 Clear, 08:00 Inside KRX.
    - 2026-11-10(미국 서머타임 아님): 22:29 Clear, 22:30 Inside US.
    - 이튿날 11-11: 06:29 Inside US, 06:30 Clear, 07:59 Clear, 08:00 Inside KRX.
    - 2026-11-19(수능일, `verified: false`): 07:59 Clear, 08:00 Inside KRX, 16:59 Inside KRX, 17:00 Clear.
  - CI(head `adea58a`, REST check-runs): Gradle build, Secret scan and collector boundary, DB schema (PostgreSQL 16) 모두 completed/success.
  - diff 추가 줄에서 `@Disabled|appkey|secret|token|[0-9]{8,}` grep: 0건.

## 새 문장의 사실 대조

### 6번: 반영 작업 시간을 숫자로 (`collector.md:71`, PR #63 QA Q1)
문장: "서울 16:00(수능일은 17:00)~18:30이고, 그때 못 하면 미국장 보호가 끝난 뒤(서머타임 중 05:30, 아닐 때 06:30)~08:00에 한다."

- [사실] KRX 정규장은 09:00~15:30이다(`krx.json:6-7`). 보호 구간은 개장 60분 전부터 폐장 30분 뒤까지다(`SessionProtection.java:21-22`, 판정 `:64-65`). 그래서 KRX 보호는 08:00~16:00이고, 위 계산에서 16:00과 08:00 경계가 맞았다.
- [사실] 수능일 10:00~16:30은 두 경우 모두 보호가 17:00에 끝난다.
  - 미확인(`verified: false`)이면 정규장과 합쳐(`ExchangeCalendar.java:147-153`) 08:00~17:00을 보호한다. 계산 결과 16:59 Inside, 17:00 Clear였고, 테스트 `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`도 이번 빌드에서 통과했다.
  - 확인된 날이면 `:154`에 따라 10:00~16:30 그대로 쓰여 09:00~17:00을 보호한다.
  - 반영 전이라 캘린더에 없는 새 시험일은 코드상 16:00에 Clear지만 실제 폐장은 16:30이다. 이때는 "수능일은 17:00" 문구가 사람 절차로 그 공백을 덮는다.
- [사실] 미국장 보호는 09:30~16:00 ET(`us.json`)에 60/30분을 더한 구간이다. 서울 시각으로는 서머타임 중 21:30~05:30, 아닐 때 22:30~06:30이다. 위 계산의 21:30·05:30·22:30·06:30 경계가 문장과 같다.
- [사실] 미국 특별 운영일은 조기 폐장(13:00)만 있어(`us.json:44-48`) 보호 구간이 줄기만 한다. 문장의 숫자는 보수적인 경계다.
- [사실] 18:30은 매일 예약 실행 시작 시각(`collector.md:54`)이다. "예약 실행이 돌지 않는 시간"이라는 기존 조건과 맞다.
- [사실] `replayCheck`는 시간 보호를 확인하지 않는다(아래). 숫자로 적은 창이 PR #63 risk-ops N1, QA 시나리오 3의 "21:30 뒤 실행이 미국장 보호 안" 오독을 막는다.

### 2번: 남겨 둔 옛 시험일 항목의 정리 절차 (`collector.md:67` 끝, PR #63 QA R1)
- [사실] 정규장 운영이 확인되면 특별 운영일 항목을 지운다. 그러면 `session`·`protectionSession` 모두 정규장이 되고 보호는 08:00~16:00이다.
  - 미확인 상태(08:00~17:00)보다 좁아지지만, 거래소 공지로 확인된 실제 운영 시간과 같다. 3번 절차(공지로 확인되면 공지를 따름)와 같은 원칙이다.
- [사실] 늦춘 시간 유지가 확인되면 `verified: true`로 바꾼다. 그러면 보호는 09:00~17:00이 되어 실제 운영 구간을 그대로 덮는다.
- [사실] 두 경우 모두 새 출처 키를 쓰고 6번 절차를 밟게 했다. 이것은 `calendar_digest`가 출처 표와 수록 범위만 해시한다는 3번 경고(`collector.md:68`)와 맞는다.
- [사실] 특별 운영일 항목을 지워도 깨지는 테스트는 없다. `autotrade-marketdata/src/test`에서 수능 개수를 단정하는 테스트는 0건이고, 보호 테스트는 2027-11-18 항목을 쓴다.
- [추론] 공지가 없을 때 녹화 첫 체결 시각으로 확인하는 경로는 그날이 지난 뒤에만 쓸 수 있다. 그래서 보호(안전)에는 영향이 없고 데이터 정확도만 다룬다. "정리 전에는 그날 녹화를 실험에 쓰지 않는다"는 PR #63 risk-ops N3 우려를 절차로 닫는다. 첫 체결 시각만으로는 폐장 시각을 확인하지 못하는 점은 N1에 적었다.

### 2번: 정지 때 `java.exe`를 골라 끝내지 않는다 (PR #63 QA R2)
- [사실] 실전 엔진·게이트웨이·감시·수집기·실험 명령은 모두 Gradle `application` 플러그인의 Java 프로그램이다.
  - `autotrade-app-engine/build.gradle.kts:7-9`, `autotrade-app-gateway/build.gradle.kts:6-8`, `autotrade-app-watchdog/build.gradle.kts:6-8`, `autotrade-app-collector/build.gradle.kts:8-10`, `autotrade-app-experiment/build.gradle.kts:9-11`.
  - 그래서 작업 관리자에서는 모두 같은 `java.exe`로 보인다. 문장의 이유가 맞다.
- [사실] 이 문장은 감시가 PID로 프로세스를 죽이지 않는 이유(ADR-007:230, PID 재사용 때 실전 엔진을 죽일 위험)와 같은 위험을 사람 조작에도 막는다. 실전 엔진을 잘못 끝낼 수 있는 사람 조작 하나를 줄이므로 보호를 넓힌다.

### Q-050 등록 (`docs/decisions/README.md:90`, PR #63 QA R3, risk-ops N2)
- [사실] `autotrade-app-gateway/src/main`에서 `SessionProtection`·`RunGuard`·`RunGate`·`ExperimentGate`·`stop-request` grep은 0건이다. `replayCheck` 작업은 `JavaExec`일 뿐이다(`autotrade-app-gateway/build.gradle.kts:28-29`). Q-050의 현황 설명이 맞다.
- [사실] 비교 대상의 클래스 이름도 맞다: `RunGuard`(`autotrade-app-collector/.../RunGuard.java:27`), `ExperimentGate implements RunGate`(`autotrade-app-experiment/.../ExperimentGate.java:28`). D-063(`README.md:68`)이 실험 명령 관문 결정이다.
- [사실] 번호 충돌이 없다. Q-049는 닫힌 표에 있고(`README.md:125`), Q-050은 처음 쓰는 번호다.
- [사실] 이 PR로 막는 조건이 추적된다. 문구는 "7단계 실전 활성화 판정(점검표 D7·A8) 전에 닫는다"이고, 담당은 data-engineer와 risk-ops다.

### D-064 갱신 이력 새 줄 (`D-064-krx-2027-calendar.md:45`)
- 반영 시간 숫자, 옛 시험일 정리 절차, `java.exe` 구절, Q-050 등록, "데이터·동작 변경 없음"이 runbook과 README diff와 하나씩 맞는다.

## 점검표 A

| # | 결과 | 근거 |
|---|---|---|
| A1 | 해당 없음 | RealTradingGuard 코드·설정·테스트 변경이 없다(diff는 `docs/` 3개 파일). `:autotrade-risk` 55/0/0/0 통과 |
| A2 | 해당 없음 | 주문 출구·Guard 팩토리·ArchUnit 규칙 변경이 없다. `:autotrade-architecture-test` 31/0/0/0 통과 |
| A3 | 해당 없음 | 주문 라우팅·증권사 전환 코드 변경이 없다. 새 문장은 캘린더 항목 정리와 단발 명령의 실행 시간·정지 방법만 다룬다 |
| A4 | 해당 없음 | 주문 타임아웃·조회 경로 변경이 없다 |
| A5 | 해당 없음 | 멱등 키 생성·저장 변경이 없다 |
| A6 | 해당 없음 | 블랙리스트·수동 보유 필터 변경이 없다 |
| A7 | 해당 없음 | 계좌별 큐·토큰 버킷·킬스위치 변경이 없다. `:autotrade-app-engine` 48/0/0/0 통과 |
| A8 | 통과 | 판정 코드(`ExchangeCalendar.java:137-155`, `SessionProtection.java:52-73`, `RunGuard`, `ExperimentGate`)와 판정 입력(`krx.json`, `us.json`)은 그대로다. 새 구절 세 개는 실전 엔진과 같은 PC 자원을 다투는 시간을 줄이거나 오조작을 막는다: ① 반영 작업 창을 국내·미국 보호 구간을 모두 피하는 숫자로 고정(직접 계산으로 경계 확인) ② `java.exe` 일괄 종료 금지 ③ 코드 보호 공백(`replayCheck`)을 Q-050으로 추적하고 7단계 판정 전에 닫게 함. 옛 시험일 항목 정리는 확인된 운영 시간에 보호를 맞추는 것이고, 확인 전에는 08:00~17:00이 그대로다. 남은 공백(`replayCheck`에 코드 보호 없음)은 Q-050이 7단계 전 조건으로 걸고 있다 |
| A9 | 통과 | 보호 시세 경로·서버 측 손절 코드 변경이 없다. 실전 엔진·감시 `src/main`에 `ExchangeCalendar`·`SessionProtection` 참조가 0건이어서 캘린더 정리가 실전 보호로 이어지지 않는다. 게이트웨이는 캘린더에서 `zone()`만 읽고(`Gateway.java:63`), "설치 폴더를 건드리지 않는다" 문장은 그대로다 |
| A10 | 통과 | diff에 키·계좌번호·토큰이 없다(위 grep 0건). CI Secret scan success |
| A11 | 통과 | 테스트 파일 변경 0건. `ArchitectureTest`, RealTradingGuard·GuardedOrderGateway 테스트, `RunGuardTest`·`ExperimentGateTest`·`ExchangeCalendarTest`, 장애 테스트 중 삭제·비활성화·약화된 것이 없다. 코드 동작 변경이 없어 새 테스트 요구도 없다. 새 문장이 기대는 보호 경계는 기존 테스트(`ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`)와 직접 계산으로 확인했다. Q-050 추천안에 "실패 테스트를 둔다"가 있다 |
| A12 | 통과 | `broker-and-fault-design.md` diff 0줄. 장애표 17행(#1~#17)·해외 O1~O8·장애 테스트 8종의 문구와 전제가 그대로다. 새 문장은 #10(PC 자원 고갈)과 5.2 0번의 전제("장 시간 보호 구간에는 단발 명령이 돌지 않는다")를 사람 절차로 지키는 범위를 넓힌다. `replayCheck`를 5.2 0번 대상에 넣을지는 Q-050에서 정하고, 정하면 대응표를 함께 바꿔야 한다 |
| A13 | 통과 | 미국 캘린더·`MarketRules`·금액 계산 변경이 없다. 새 숫자는 모두 서울 현지 시각이고, 미국장 보호 경계는 `America/New_York` 캘린더와 서머타임 전환을 반영한 값이다(2026-10-13 서머타임 중, 2026-11-10 아님 계산으로 확인) |
| A14 | 통과 | D-064(미확인 특별 운영일은 정규장과 합침, 갱신 절차는 runbook 3.6)와 충돌이 없다. `D-064-krx-2027-calendar.md:45` 이력이 diff와 맞다. 미확정 후속 과제는 Q-050(`README.md:90`)으로 기록했다. 동작·데이터 변경이 없어 새 D 번호는 필요 없고, 기획서도 변경 대상이 아니다 |

## 실전 관련 PR 여부
**실전 관련 PR이다(사용자 직접 머지 대상, 독립 QA 관문).**
- 운영 코드·데이터·테스트는 바뀌지 않는다.
- 그러나 runbook 3.6은 장 시간 보호(broker-and-fault-design 5.2 정지 순서 0번, 6장 #10, D-058·D-063·D-064)의 판정 입력 `krx.json`을 갱신하는 절차다. 이 PR은 그 절차와 실행 중 명령의 정지 방법을 바꾸고, 7단계 실전 활성화 판정을 막는 미확정 항목(Q-050)을 새로 건다.
- 따라서 지침 10장의 "장애 대응을 건드리는 PR"에 해당한다. 같은 절차를 바꾼 PR #53~#56, #62, #63과 같은 판단이다.

## 점검표 밖 발견
- **N1 (권고, 데이터 정확도 — 실전 무관, 머지를 막지 않음)**: 공지 없이 정리할 때 "녹화의 첫 체결 시각"만으로는 개장 시각만 확인된다. 늦춘 시간 유지는 폐장 16:30도 함께 바뀌는 경우다. 마지막 체결 시각(정규장이면 15:30 무렵, 늦춘 시간이면 16:30 무렵)도 함께 대조하기를 권한다. 그날이 지난 뒤의 정리라서 보호에는 영향이 없다.
- **N2 (권고, 절차 — 머지를 막지 않음)**: 반영 창(16:00 또는 17:00 ~ 18:30)은 시작 시각만 정하고, 작업이 18:30을 넘길 때의 처리를 적지 않았다. `build` 전체 테스트와 `replayCheck`가 길어지면 18:30 예약 실행이나 21:30 미국장 보호와 겹칠 수 있다. "끝나지 않으면 그날 예약 실행을 미루고, 21:30(서머타임이 아니면 22:30) 전에 `replayCheck`를 멈춘다" 같은 한 구절을 권한다. `replayCheck`의 근본 해결은 Q-050이다.
- **N3 (기록, 추적)**: Q-050의 "7단계 판정 전에 닫는다"는 결정 로그 미확정 표에만 있다. 점검표 D·broker-and-fault-design 7장의 실전 활성화 조건 목록에는 직접 걸려 있지 않다. 지금은 D7(A 전 항목을 main 기준으로 다시 확인) 때 A8을 보면서 Q-050이 닫혔는지 확인해야 한다. 7단계 관문 검토자(risk-ops, QA)가 이 항목을 확인한다.
- **N4 (기록, 이전 지적 유지)**: 다음은 바뀌지 않았다. 셋 다 이번 diff 범위 밖이고 머지를 막지 않는다.
  - `ExchangeCalendar` 생성자가 `source` 키가 있는지 검사하지 않는다.
  - 수록 범위 만료 자동 경보가 없다(다음 마감 서울 2027-12-31 00:00).
  - 작업 스케줄러 "끝내기"가 하위 `java.exe`까지 끝내는지는 Windows 실측이 없다 `[가정]`.

## 판정
승인.
- 점검표 A에 실패와 확인 못 함이 없다.
- 새 문장 네 개의 사실 주장을 모두 확인했다. 확인한 근거는 다음과 같다.
  - 코드: `ExchangeCalendar.java:147-154`, `SessionProtection.java:21-22`·`:64-65`, 게이트웨이 grep 0건, 각 모듈의 `application` 설정.
  - 테스트: `ExchangeCalendarTest#장_시간_보호는_확인_안_된_날짜를_틀려도_보호가_빠지지_않는_쪽으로_본다`.
  - 번들 캘린더로 직접 계산한 보호 경계 19개 시각.
  - 격리 clone `./gradlew build`: 897건, 실패 0.
  - 확인한 주장: ① 반영 창 16:00(수능일 17:00)~18:30 / 05:30·06:30~08:00 ② 옛 시험일 정리 절차 ③ `java.exe` 오조작 방지 ④ Q-050 현황.
- 모두 main보다 보호를 줄이지 않는다. 옛 시험일 정리는 거래소 공지로 확인된 실제 운영 시간에 보호를 맞춘다.
- 실전 관련 PR(장애 대응: 장 시간 보호 판정 입력의 갱신 절차, 7단계 차단 항목 Q-050 신설)이므로, 지침 10장에 따라 사용자가 직접 확인하고 머지하며 지침 11장의 독립 QA 관문 대상이다.
- N1(폐장 시각 대조)·N2(반영 작업이 18:30을 넘길 때의 처리)는 머지를 막지 않는 권고다. Q-050은 7단계 실전 활성화 전에 닫혀야 한다.

판정: 승인
